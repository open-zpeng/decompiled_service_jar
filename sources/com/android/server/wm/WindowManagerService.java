package com.android.server.wm;

import android.animation.AnimationHandler;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IAssistDataReceiver;
import android.app.admin.DevicePolicyCache;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.configstore.V1_0.ISurfaceFlingerConfigs;
import android.hardware.configstore.V1_0.OptionalBool;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDockedStackListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedStackListener;
import android.view.IRecentsAnimationRunner;
import android.view.IRotationWatcher;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.inputmethod.InputMethodManagerInternal;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.UiModeManagerService;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.input.InputManagerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.ShutdownThread;
import com.android.server.usage.AppStandbyController;
import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.RecentsAnimationController;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowState;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.server.wm.xpWindowManagerService;
import com.xiaopeng.util.DebugOption;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Socket;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class WindowManagerService extends IWindowManager.Stub implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    private static final boolean ALWAYS_KEEP_CURRENT = true;
    private static final int ANIMATION_DURATION_SCALE = 2;
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";
    static final boolean CUSTOM_SCREEN_ROTATION = true;
    static final long DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS = 5000000000L;
    private static final String DENSITY_OVERRIDE = "ro.config.density_override";
    private static final int INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS = 1000;
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 7200000;
    static final int LAYER_OFFSET_DIM = 1;
    static final int LAYER_OFFSET_THUMBNAIL = 4;
    static final int LAYOUT_REPEAT_THRESHOLD = 4;
    static final int MAX_ANIMATION_DURATION = 10000;
    private static final int MAX_SCREENSHOT_RETRIES = 3;
    static final boolean PROFILE_ORIENTATION = false;
    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";
    static final int SEAMLESS_ROTATION_TIMEOUT_DURATION = 2000;
    private static final String SIZE_OVERRIDE = "ro.config.size_override";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";
    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String TAG = "WindowManager";
    private static final int TRANSITION_ANIMATION_SCALE = 1;
    static final int TYPE_LAYER_MULTIPLIER = 10000;
    static final int TYPE_LAYER_OFFSET = 1000;
    static final int UPDATE_FOCUS_NORMAL = 0;
    static final int UPDATE_FOCUS_PLACING_SURFACES = 2;
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;
    static final int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;
    static final int WINDOWS_FREEZING_SCREENS_NONE = 0;
    static final int WINDOWS_FREEZING_SCREENS_TIMEOUT = 2;
    private static final int WINDOW_ANIMATION_SCALE = 0;
    static final int WINDOW_FREEZE_TIMEOUT_DURATION = 2000;
    static final int WINDOW_LAYER_MULTIPLIER = 5;
    static final int WINDOW_REPLACEMENT_TIMEOUT_DURATION = 2000;
    private static final String XP_POWER_STATE = "sys.xiaopeng.power_state";
    private static WindowManagerService sInstance;
    AccessibilityController mAccessibilityController;
    final IActivityManager mActivityManager;
    final boolean mAllowAnimationsInLowPowerMode;
    final boolean mAllowBootMessages;
    boolean mAllowTheaterModeWakeFromLayout;
    final ActivityManagerInternal mAmInternal;
    private boolean mAnimationsDisabled;
    final WindowAnimator mAnimator;
    final AppOpsManager mAppOps;
    final AppTransition mAppTransition;
    final BoundsAnimationController mBoundsAnimationController;
    CircularDisplayMask mCircularDisplayMask;
    final Context mContext;
    int mCurrentUserId;
    int mDeferredRotationPauseCount;
    boolean mDisableTransitionAnimation;
    final DisplayManager mDisplayManager;
    final DisplayManagerInternal mDisplayManagerInternal;
    boolean mDisplayReady;
    final DisplaySettings mDisplaySettings;
    Rect mDockedStackCreateBounds;
    final DragDropController mDragDropController;
    final long mDrawLockTimeoutMillis;
    EmulatorDisplayOverlay mEmulatorDisplayOverlay;
    private int mEnterAnimId;
    private boolean mEventDispatchingEnabled;
    private int mExitAnimId;
    boolean mFocusMayChange;
    private int mFrozenDisplayId;
    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;
    final boolean mHasPermanentDpad;
    private boolean mHasWideColorGamutSupport;
    final boolean mHaveInputMethods;
    private Session mHoldingScreenOn;
    private PowerManager.WakeLock mHoldingScreenWakeLock;
    boolean mInTouchMode;
    final InputManagerService mInputManager;
    IInputMethodManager mInputMethodManager;
    boolean mInputMethodTargetWaitingAnim;
    boolean mIsTouchDevice;
    private final KeyguardDisableHandler mKeyguardDisableHandler;
    boolean mKeyguardGoingAway;
    boolean mKeyguardOrAodShowingOnDefaultDisplay;
    String mLastANRState;
    private final LatencyTracker mLatencyTracker;
    final boolean mLimitedAlphaCompositing;
    final int mMaxUiWidth;
    final boolean mOnlyCore;
    final PackageManagerInternal mPmInternal;
    private final PointerEventDispatcher mPointerEventDispatcher;
    final WindowManagerPolicy mPolicy;
    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;
    private RecentsAnimationController mRecentsAnimationController;
    RootWindowContainer mRoot;
    boolean mSafeMode;
    private final PowerManager.WakeLock mScreenFrozenLock;
    SettingsObserver mSettingsObserver;
    StrictModeFlash mStrictModeFlash;
    final SurfaceAnimationRunner mSurfaceAnimationRunner;
    final TaskPositioningController mTaskPositioningController;
    final TaskSnapshotController mTaskSnapshotController;
    private WindowContentFrameStats mTempWindowRenderStats;
    int mTransactionSequence;
    private float mTransitionAnimationScaleSetting;
    private ViewServer mViewServer;
    Runnable mWaitingForDrawnCallback;
    Watermark mWatermark;
    private float mWindowAnimationScaleSetting;
    final WindowSurfacePlacer mWindowPlacerLocked;
    final WindowTracing mWindowTracing;
    static final boolean localLOGV = WindowManagerDebugConfig.DEBUG;
    static WindowManagerThreadPriorityBooster sThreadPriorityBooster = new WindowManagerThreadPriorityBooster();
    int mVr2dDisplayId = -1;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.wm.WindowManagerService.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (((action.hashCode() == 988075300 && action.equals("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED")) ? (char) 0 : (char) 65535) == 0) {
                WindowManagerService.this.mKeyguardDisableHandler.sendEmptyMessage(3);
            }
        }
    };
    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.wm.WindowManagerService.2
        @Override // com.android.server.utils.PriorityDump.PriorityDumper
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            WindowManagerService.this.doDump(fd, pw, new String[]{"-a"}, asProto);
        }

        @Override // com.android.server.utils.PriorityDump.PriorityDumper
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            WindowManagerService.this.doDump(fd, pw, args, asProto);
        }
    };
    int[] mCurrentProfileIds = new int[0];
    boolean mShowAlertWindowNotifications = true;
    final ArraySet<Session> mSessions = new ArraySet<>();
    final WindowHashMap mWindowMap = new WindowHashMap();
    final ArrayList<AppWindowToken> mFinishedStarting = new ArrayList<>();
    final ArrayList<AppWindowToken> mWindowReplacementTimeouts = new ArrayList<>();
    final ArrayList<WindowState> mResizingWindows = new ArrayList<>();
    final ArrayList<WindowState> mPendingRemove = new ArrayList<>();
    WindowState[] mPendingRemoveTmp = new WindowState[20];
    final ArrayList<WindowState> mDestroySurface = new ArrayList<>();
    final ArrayList<WindowState> mDestroyPreservedSurface = new ArrayList<>();
    ArrayList<WindowState> mLosingFocus = new ArrayList<>();
    final ArrayList<WindowState> mForceRemoves = new ArrayList<>();
    ArrayList<WindowState> mWaitingForDrawn = new ArrayList<>();
    private ArrayList<WindowState> mHidingNonSystemOverlayWindows = new ArrayList<>();
    final float[] mTmpFloats = new float[9];
    final Rect mTmpRect = new Rect();
    final Rect mTmpRect2 = new Rect();
    final Rect mTmpRect3 = new Rect();
    final RectF mTmpRectF = new RectF();
    final Matrix mTmpTransform = new Matrix();
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    boolean mForceDisplayEnabled = false;
    boolean mShowingBootMessages = false;
    boolean mBootAnimationStopped = false;
    WindowState mLastWakeLockHoldingWindow = null;
    WindowState mLastWakeLockObscuringWindow = null;
    int mDockedStackCreateMode = 0;
    boolean mForceResizableTasks = false;
    boolean mSupportsPictureInPicture = false;
    ArrayList<RotationWatcher> mRotationWatchers = new ArrayList<>();
    final WallpaperVisibilityListeners mWallpaperVisibilityListeners = new WallpaperVisibilityListeners();
    int mSystemDecorLayer = 0;
    final Rect mScreenRect = new Rect();
    boolean mDisplayFrozen = false;
    long mDisplayFreezeTime = 0;
    int mLastDisplayFreezeDuration = 0;
    Object mLastFinishedFreezeSource = null;
    boolean mWaitingForConfig = false;
    boolean mSwitchingUser = false;
    int mWindowsFreezingScreen = 0;
    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;
    int mLastStatusBarVisibility = 0;
    int mLastDispatchedSystemUiVisibility = 0;
    boolean mSkipAppTransitionAnimation = false;
    final ArraySet<AppWindowToken> mOpeningApps = new ArraySet<>();
    final ArraySet<AppWindowToken> mClosingApps = new ArraySet<>();
    final UnknownAppVisibilityController mUnknownAppVisibilityController = new UnknownAppVisibilityController(this);
    final H mH = new H();
    final Handler mAnimationHandler = new Handler(AnimationThread.getHandler().getLooper());
    WindowState mCurrentFocus = null;
    WindowState mLastFocus = null;
    private final ArrayList<WindowState> mWinAddedSinceNullFocus = new ArrayList<>();
    private final ArrayList<WindowState> mWinRemovedSinceNullFocus = new ArrayList<>();
    WindowState mInputMethodTarget = null;
    WindowState mInputMethodWindow = null;
    private int mSeamlessRotationCount = 0;
    private boolean mRotatingSeamlessly = false;
    AppWindowToken mFocusedApp = null;
    private float mAnimatorDurationScaleSetting = 1.0f;
    final ArrayMap<AnimationAdapter, SurfaceAnimator> mAnimationTransferMap = new ArrayMap<>();
    final ArrayList<WindowChangeListener> mWindowChangeListeners = new ArrayList<>();
    boolean mWindowsChanged = false;
    final Configuration mTempConfiguration = new Configuration();
    final List<IBinder> mNoAnimationNotifyOnTransitionFinished = new ArrayList();
    SurfaceBuilderFactory mSurfaceBuilderFactory = new SurfaceBuilderFactory() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$XZ-U3HlCFtHp_gydNmNMeRmQMCI
        @Override // com.android.server.wm.SurfaceBuilderFactory
        public final SurfaceControl.Builder make(SurfaceSession surfaceSession) {
            return WindowManagerService.m30lambda$XZU3HlCFtHp_gydNmNMeRmQMCI(surfaceSession);
        }
    };
    TransactionFactory mTransactionFactory = new TransactionFactory() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$hBnABSAsqXWvQ0zKwHWE4BZ3Mc0
        @Override // com.android.server.wm.TransactionFactory
        public final SurfaceControl.Transaction make() {
            return WindowManagerService.lambda$hBnABSAsqXWvQ0zKwHWE4BZ3Mc0();
        }
    };
    private final SurfaceControl.Transaction mTransaction = this.mTransactionFactory.make();
    final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier = new WindowManagerInternal.AppTransitionListener() { // from class: com.android.server.wm.WindowManagerService.3
        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionCancelledLocked(int transit) {
            WindowManagerService.this.mH.sendEmptyMessage(48);
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionFinishedLocked(IBinder token) {
            WindowManagerService.this.mH.sendEmptyMessage(49);
            AppWindowToken atoken = WindowManagerService.this.mRoot.getAppWindowToken(token);
            if (atoken == null) {
                return;
            }
            if (atoken.mLaunchTaskBehind) {
                try {
                    WindowManagerService.this.mActivityManager.notifyLaunchTaskBehindComplete(atoken.token);
                } catch (RemoteException e) {
                }
                atoken.mLaunchTaskBehind = false;
                return;
            }
            atoken.updateReportedVisibilityLocked();
            if (atoken.mEnteringAnimation) {
                if (WindowManagerService.this.getRecentsAnimationController() != null && WindowManagerService.this.getRecentsAnimationController().isTargetApp(atoken)) {
                    return;
                }
                atoken.mEnteringAnimation = false;
                try {
                    WindowManagerService.this.mActivityManager.notifyEnterAnimationComplete(atoken.token);
                } catch (RemoteException e2) {
                }
            }
        }
    };
    final ArrayList<AppFreezeListener> mAppFreezeListeners = new ArrayList<>();
    final InputMonitor mInputMonitor = new InputMonitor(this);
    MousePositionTracker mMousePositionTracker = new MousePositionTracker();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface AppFreezeListener {
        void onAppFreezeTimeout();
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface UpdateAnimationScaleMode {
    }

    /* loaded from: classes.dex */
    public interface WindowChangeListener {
        void focusChanged();

        void windowsChanged();
    }

    /* renamed from: lambda$XZ-U3HlCFtHp_gydNmNMeRmQMCI  reason: not valid java name */
    public static /* synthetic */ SurfaceControl.Builder m30lambda$XZU3HlCFtHp_gydNmNMeRmQMCI(SurfaceSession surfaceSession) {
        return new SurfaceControl.Builder(surfaceSession);
    }

    public static /* synthetic */ SurfaceControl.Transaction lambda$hBnABSAsqXWvQ0zKwHWE4BZ3Mc0() {
        return new SurfaceControl.Transaction();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDragLayerLocked() {
        return (this.mPolicy.getWindowLayerFromTypeLw(2016) * 10000) + 1000;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class RotationWatcher {
        final IBinder.DeathRecipient mDeathRecipient;
        final int mDisplayId;
        final IRotationWatcher mWatcher;

        RotationWatcher(IRotationWatcher watcher, IBinder.DeathRecipient deathRecipient, int displayId) {
            this.mWatcher = watcher;
            this.mDeathRecipient = deathRecipient;
            this.mDisplayId = displayId;
        }
    }

    /* loaded from: classes.dex */
    private final class SettingsObserver extends ContentObserver {
        private final Uri mAnimationDurationScaleUri;
        private final Uri mDisplayInversionEnabledUri;
        private final Uri mTransitionAnimationScaleUri;
        private final Uri mWindowAnimationScaleUri;

        public SettingsObserver() {
            super(new Handler());
            this.mDisplayInversionEnabledUri = Settings.Secure.getUriFor("accessibility_display_inversion_enabled");
            this.mWindowAnimationScaleUri = Settings.Global.getUriFor("window_animation_scale");
            this.mTransitionAnimationScaleUri = Settings.Global.getUriFor("transition_animation_scale");
            this.mAnimationDurationScaleUri = Settings.Global.getUriFor("animator_duration_scale");
            ContentResolver resolver = WindowManagerService.this.mContext.getContentResolver();
            resolver.registerContentObserver(this.mDisplayInversionEnabledUri, false, this, -1);
            resolver.registerContentObserver(this.mWindowAnimationScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mTransitionAnimationScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mAnimationDurationScaleUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            int mode;
            if (uri == null) {
                return;
            }
            if (this.mDisplayInversionEnabledUri.equals(uri)) {
                WindowManagerService.this.updateCircularDisplayMaskIfNeeded();
                return;
            }
            if (this.mWindowAnimationScaleUri.equals(uri)) {
                mode = 0;
            } else if (this.mTransitionAnimationScaleUri.equals(uri)) {
                mode = 1;
            } else if (this.mAnimationDurationScaleUri.equals(uri)) {
                mode = 2;
            } else {
                return;
            }
            Message m = WindowManagerService.this.mH.obtainMessage(51, mode, 0);
            WindowManagerService.this.mH.sendMessage(m);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void boostPriorityForLockedSection() {
        sThreadPriorityBooster.boost();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void resetPriorityAfterLockedSection() {
        sThreadPriorityBooster.reset();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void openSurfaceTransaction() {
        try {
            Trace.traceBegin(32L, "openSurfaceTransaction");
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                SurfaceControl.openTransaction();
            }
            resetPriorityAfterLockedSection();
        } finally {
            Trace.traceEnd(32L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeSurfaceTransaction(String where) {
        try {
            Trace.traceBegin(32L, "closeSurfaceTransaction");
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                traceStateLocked(where);
                SurfaceControl.closeTransaction();
            }
            resetPriorityAfterLockedSection();
        } finally {
            Trace.traceEnd(32L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static WindowManagerService getInstance() {
        return sInstance;
    }

    public static WindowManagerService main(final Context context, final InputManagerService im, final boolean haveInputMethods, final boolean showBootMsgs, final boolean onlyCore, final WindowManagerPolicy policy) {
        DisplayThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$qOaUiWHWefHk1N5K-T4WND2mknQ
            @Override // java.lang.Runnable
            public final void run() {
                WindowManagerService.sInstance = new WindowManagerService(context, im, haveInputMethods, showBootMsgs, onlyCore, policy);
            }
        }, 0L);
        return sInstance;
    }

    private void initPolicy() {
        UiThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.WindowManagerService.4
            @Override // java.lang.Runnable
            public void run() {
                WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());
                WindowManagerService.this.mPolicy.init(WindowManagerService.this.mContext, WindowManagerService.this, WindowManagerService.this);
            }
        }, 0L);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver result) {
        new WindowManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    private WindowManagerService(Context context, InputManagerService inputManager, boolean haveInputMethods, boolean showBootMsgs, boolean onlyCore, WindowManagerPolicy policy) {
        this.mDisableTransitionAnimation = false;
        this.mWindowAnimationScaleSetting = 1.0f;
        this.mTransitionAnimationScaleSetting = 1.0f;
        this.mAnimationsDisabled = false;
        LockGuard.installLock(this, 5);
        this.mContext = context;
        this.mHaveInputMethods = haveInputMethods;
        this.mAllowBootMessages = showBootMsgs;
        this.mOnlyCore = onlyCore;
        this.mLimitedAlphaCompositing = context.getResources().getBoolean(17957022);
        this.mHasPermanentDpad = context.getResources().getBoolean(17956985);
        this.mInTouchMode = context.getResources().getBoolean(17956920);
        this.mDrawLockTimeoutMillis = context.getResources().getInteger(17694782);
        this.mAllowAnimationsInLowPowerMode = context.getResources().getBoolean(17956871);
        this.mMaxUiWidth = context.getResources().getInteger(17694812);
        this.mDisableTransitionAnimation = context.getResources().getBoolean(17956931);
        this.mInputManager = inputManager;
        this.mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
        this.mDisplaySettings = new DisplaySettings();
        int oldMaskone = StrictMode.allowThreadDiskReadsMask();
        try {
            this.mDisplaySettings.readSettingsLocked();
            StrictMode.setThreadPolicyMask(oldMaskone);
            this.mPolicy = policy;
            this.mAnimator = new WindowAnimator(this);
            this.mRoot = new RootWindowContainer(this);
            this.mWindowPlacerLocked = new WindowSurfacePlacer(this);
            this.mTaskSnapshotController = new TaskSnapshotController(this);
            this.mWindowTracing = WindowTracing.createDefaultAndStartLooper(context);
            LocalServices.addService(WindowManagerPolicy.class, this.mPolicy);
            if (this.mInputManager != null) {
                InputChannel inputChannel = this.mInputManager.monitorInput(TAG);
                this.mPointerEventDispatcher = inputChannel != null ? new PointerEventDispatcher(inputChannel) : null;
            } else {
                this.mPointerEventDispatcher = null;
            }
            this.mDisplayManager = (DisplayManager) context.getSystemService("display");
            this.mKeyguardDisableHandler = new KeyguardDisableHandler(this.mContext, this.mPolicy);
            this.mPowerManager = (PowerManager) context.getSystemService("power");
            this.mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
            if (this.mPowerManagerInternal != null) {
                this.mPowerManagerInternal.registerLowPowerModeObserver(new PowerManagerInternal.LowPowerModeListener() { // from class: com.android.server.wm.WindowManagerService.5
                    public int getServiceType() {
                        return 3;
                    }

                    public void onLowPowerModeChanged(PowerSaveState result) {
                        synchronized (WindowManagerService.this.mWindowMap) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                boolean enabled = result.batterySaverEnabled;
                                if (WindowManagerService.this.mAnimationsDisabled != enabled && !WindowManagerService.this.mAllowAnimationsInLowPowerMode) {
                                    WindowManagerService.this.mAnimationsDisabled = enabled;
                                    WindowManagerService.this.dispatchNewAnimatorScaleLocked(null);
                                }
                            } catch (Throwable th) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                throw th;
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                });
                this.mAnimationsDisabled = this.mPowerManagerInternal.getLowPowerState(3).batterySaverEnabled;
            }
            this.mScreenFrozenLock = this.mPowerManager.newWakeLock(1, "SCREEN_FROZEN");
            this.mScreenFrozenLock.setReferenceCounted(false);
            this.mAppTransition = new AppTransition(context, this);
            this.mAppTransition.registerListenerLocked(this.mActivityManagerAppTransitionNotifier);
            AnimationHandler animationHandler = new AnimationHandler();
            this.mBoundsAnimationController = new BoundsAnimationController(context, this.mAppTransition, AnimationThread.getHandler(), animationHandler);
            this.mActivityManager = ActivityManager.getService();
            this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
            this.mAppOps = (AppOpsManager) context.getSystemService("appops");
            AppOpsManager.OnOpChangedListener onOpChangedListener = new AppOpsManager.OnOpChangedInternalListener() { // from class: com.android.server.wm.WindowManagerService.6
                public void onOpChanged(int op, String packageName) {
                    WindowManagerService.this.updateAppOpsState();
                }
            };
            this.mAppOps.startWatchingMode(24, (String) null, onOpChangedListener);
            this.mAppOps.startWatchingMode(45, (String) null, onOpChangedListener);
            this.mPmInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            IntentFilter suspendPackagesFilter = new IntentFilter();
            suspendPackagesFilter.addAction("android.intent.action.PACKAGES_SUSPENDED");
            suspendPackagesFilter.addAction("android.intent.action.PACKAGES_UNSUSPENDED");
            context.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.wm.WindowManagerService.7
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    String[] affectedPackages = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                    boolean suspended = "android.intent.action.PACKAGES_SUSPENDED".equals(intent.getAction());
                    WindowManagerService.this.updateHiddenWhileSuspendedState(new ArraySet(Arrays.asList(affectedPackages)), suspended);
                }
            }, UserHandle.ALL, suspendPackagesFilter, null, null);
            this.mWindowAnimationScaleSetting = Settings.Global.getFloat(context.getContentResolver(), "window_animation_scale", this.mWindowAnimationScaleSetting);
            this.mTransitionAnimationScaleSetting = Settings.Global.getFloat(context.getContentResolver(), "transition_animation_scale", context.getResources().getFloat(17104977));
            setAnimatorDurationScale(Settings.Global.getFloat(context.getContentResolver(), "animator_duration_scale", this.mAnimatorDurationScaleSetting));
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED");
            this.mContext.registerReceiver(this.mBroadcastReceiver, filter);
            this.mLatencyTracker = LatencyTracker.getInstance(context);
            this.mSettingsObserver = new SettingsObserver();
            this.mHoldingScreenWakeLock = this.mPowerManager.newWakeLock(536870922, TAG);
            this.mHoldingScreenWakeLock.setReferenceCounted(false);
            this.mSurfaceAnimationRunner = new SurfaceAnimationRunner(this.mPowerManagerInternal);
            this.mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(17956886);
            this.mTaskPositioningController = new TaskPositioningController(this, this.mInputManager, this.mInputMonitor, this.mActivityManager, this.mH.getLooper());
            this.mDragDropController = new DragDropController(this, this.mH.getLooper());
            xpWindowManagerService.get(this.mContext).init();
            LocalServices.addService(WindowManagerInternal.class, new LocalService());
        } catch (Throwable th) {
            StrictMode.setThreadPolicyMask(oldMaskone);
            throw th;
        }
    }

    public void onInitReady() {
        initPolicy();
        Watchdog.getInstance().addMonitor(this);
        openSurfaceTransaction();
        try {
            createWatermarkInTransaction();
            closeSurfaceTransaction("createWatermarkInTransaction");
            showEmulatorDisplayOverlayIfNeeded();
        } catch (Throwable th) {
            closeSurfaceTransaction("createWatermarkInTransaction");
            throw th;
        }
    }

    public InputMonitor getInputMonitor() {
        return this.mInputMonitor;
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Window Manager Crash", e);
            }
            throw e;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean excludeWindowTypeFromTapOutTask(int windowType) {
        if (windowType == 2000 || windowType == 2012 || windowType == 2019) {
            return true;
        }
        return false;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(22:30|(2:34|(4:36|37|38|39)(2:40|(4:44|45|46|47)))|48|(3:448|449|(4:451|452|453|454))|50|(1:52)(1:447)|53|(1:55)(2:445|446)|56|(1:58)(1:441)|59|60|(2:62|(2:70|(4:72|73|74|75)(2:76|(4:78|79|80|81)(2:82|(4:84|85|86|87)(2:88|(4:90|91|92|93)(2:94|(4:96|97|98|99)(2:100|(4:102|103|104|105)(2:106|(8:114|115|(1:117)(1:320)|118|(1:120)(1:319)|121|122|123)(4:110|111|112|113))))))))(4:66|67|68|69))(2:323|(12:348|(2:350|(4:352|353|354|355))(2:359|(2:361|(4:363|364|365|366))(2:367|(2:369|(4:371|372|373|374))(2:375|(2:377|(4:379|380|381|382))(2:383|(2:385|(4:387|388|389|390))(6:391|392|(5:422|423|424|425|(9:433|125|126|127|128|129|130|131|(4:133|134|135|136)(2:137|(4:139|140|141|142)(5:143|(1:145)(1:310)|146|147|(3:149|150|151)(52:(1:308)(1:155)|(1:157)|158|(6:283|284|285|286|287|(4:289|290|291|292)(1:(1:300)))(1:160)|161|162|(1:164)|165|(2:167|168)(1:280)|169|170|(2:173|(37:175|176|177|178|(2:180|181)(2:266|(1:268)(2:269|(1:271)(3:272|273|(1:275)(2:276|(1:278)))))|182|183|(5:257|258|259|260|261)(1:185)|186|187|188|(1:194)|202|203|204|205|206|(1:251)(1:210)|211|(1:213)|214|(1:216)|217|(1:250)|221|(1:225)|228|(1:230)(1:248)|231|(1:247)|235|(1:239)|240|241|(1:243)|244|245))|279|176|177|178|(0)(0)|182|183|(0)(0)|186|187|188|(3:190|192|194)|202|203|204|205|206|(1:208)|251|211|(0)|214|(0)|217|(1:219)|250|221|(1:249)(2:223|225)|228|(0)(0)|231|(1:233)|247|235|(2:237|239)|240|241|(0)|244|245))))(4:429|430|431|432))(2:394|(2:396|(4:398|399|400|401)(10:402|357|358|126|127|128|129|130|131|(0)(0)))(3:403|404|(13:407|408|409|410|411|412|126|127|128|129|130|131|(0)(0))(9:406|358|126|127|128|129|130|131|(0)(0))))|199|200|201)))))|356|357|358|126|127|128|129|130|131|(0)(0))(2:327|(4:329|330|331|332)(2:333|(4:335|336|337|338)(2:339|(1:347)(4:343|344|345|346)))))|124|125|126|127|128|129|130|131|(0)(0)) */
    /* JADX WARN: Code restructure failed: missing block: B:265:0x05d0, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:402:0x0820, code lost:
        r0.computeImeTarget(true);
     */
    /* JADX WARN: Code restructure failed: missing block: B:430:0x08a8, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:432:0x08b2, code lost:
        r0 = th;
     */
    /* JADX WARN: Not initialized variable reg: 38, insn: 0x038f: MOVE  (r1 I:??[OBJECT, ARRAY]) = (r38 I:??[OBJECT, ARRAY] A[D('parentWindow' com.android.server.wm.WindowState)]), block:B:174:0x0388 */
    /* JADX WARN: Removed duplicated region for block: B:261:0x05ab A[Catch: all -> 0x05d0, TRY_ENTER, TryCatch #12 {all -> 0x05d0, blocks: (B:261:0x05ab, B:262:0x05cb, B:269:0x05df, B:270:0x05e6, B:280:0x0612, B:284:0x0619, B:289:0x0624), top: B:469:0x05a9 }] */
    /* JADX WARN: Removed duplicated region for block: B:267:0x05d9 A[Catch: all -> 0x08a8, TRY_ENTER, TRY_LEAVE, TryCatch #5 {all -> 0x08a8, blocks: (B:259:0x05a2, B:267:0x05d9, B:273:0x05eb, B:277:0x05f8), top: B:455:0x05a2 }] */
    /* JADX WARN: Removed duplicated region for block: B:336:0x0714  */
    /* JADX WARN: Removed duplicated region for block: B:338:0x071c  */
    /* JADX WARN: Removed duplicated region for block: B:366:0x0776  */
    /* JADX WARN: Removed duplicated region for block: B:386:0x07e7 A[Catch: all -> 0x0894, TryCatch #16 {all -> 0x0894, blocks: (B:378:0x07a4, B:380:0x07af, B:382:0x07b5, B:384:0x07cd, B:386:0x07e7, B:387:0x07eb, B:389:0x07ef, B:390:0x07f3, B:392:0x07f7, B:395:0x0803, B:397:0x080f, B:402:0x0820, B:403:0x0824, B:405:0x082d, B:407:0x083a, B:409:0x0844, B:412:0x0877, B:414:0x087d, B:417:0x0886, B:411:0x0848, B:394:0x07ff), top: B:475:0x07a4 }] */
    /* JADX WARN: Removed duplicated region for block: B:389:0x07ef A[Catch: all -> 0x0894, TryCatch #16 {all -> 0x0894, blocks: (B:378:0x07a4, B:380:0x07af, B:382:0x07b5, B:384:0x07cd, B:386:0x07e7, B:387:0x07eb, B:389:0x07ef, B:390:0x07f3, B:392:0x07f7, B:395:0x0803, B:397:0x080f, B:402:0x0820, B:403:0x0824, B:405:0x082d, B:407:0x083a, B:409:0x0844, B:412:0x0877, B:414:0x087d, B:417:0x0886, B:411:0x0848, B:394:0x07ff), top: B:475:0x07a4 }] */
    /* JADX WARN: Removed duplicated region for block: B:405:0x082d A[Catch: all -> 0x0894, TryCatch #16 {all -> 0x0894, blocks: (B:378:0x07a4, B:380:0x07af, B:382:0x07b5, B:384:0x07cd, B:386:0x07e7, B:387:0x07eb, B:389:0x07ef, B:390:0x07f3, B:392:0x07f7, B:395:0x0803, B:397:0x080f, B:402:0x0820, B:403:0x0824, B:405:0x082d, B:407:0x083a, B:409:0x0844, B:412:0x0877, B:414:0x087d, B:417:0x0886, B:411:0x0848, B:394:0x07ff), top: B:475:0x07a4 }] */
    /* JADX WARN: Removed duplicated region for block: B:406:0x0838  */
    /* JADX WARN: Removed duplicated region for block: B:420:0x088d  */
    /* JADX WARN: Removed duplicated region for block: B:459:0x0759 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int addWindow(com.android.server.wm.Session r56, android.view.IWindow r57, int r58, android.view.WindowManager.LayoutParams r59, int r60, int r61, android.graphics.Rect r62, android.graphics.Rect r63, android.graphics.Rect r64, android.graphics.Rect r65, android.view.DisplayCutout.ParcelableWrapper r66, android.view.InputChannel r67) {
        /*
            Method dump skipped, instructions count: 2303
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.addWindow(com.android.server.wm.Session, android.view.IWindow, int, android.view.WindowManager$LayoutParams, int, int, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.view.DisplayCutout$ParcelableWrapper, android.view.InputChannel):int");
    }

    private DisplayContent getDisplayContentOrCreate(int displayId) {
        Display display;
        DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
        if (displayContent == null && (display = this.mDisplayManager.getDisplay(displayId)) != null) {
            return this.mRoot.createDisplayContent(display, null);
        }
        return displayContent;
    }

    private boolean doesAddToastWindowRequireToken(String packageName, int callingUid, WindowState attachedWindow) {
        ApplicationInfo appInfo;
        if (attachedWindow != null) {
            return attachedWindow.mAppToken != null && attachedWindow.mAppToken.mTargetSdk >= 26;
        }
        try {
            appInfo = this.mContext.getPackageManager().getApplicationInfoAsUser(packageName, 0, UserHandle.getUserId(callingUid));
        } catch (PackageManager.NameNotFoundException e) {
        }
        if (appInfo.uid == callingUid) {
            return appInfo.targetSdkVersion >= 26;
        }
        throw new SecurityException("Package " + packageName + " not in UID " + callingUid);
    }

    private boolean prepareWindowReplacementTransition(AppWindowToken atoken) {
        atoken.clearAllDrawn();
        WindowState replacedWindow = atoken.getReplacingWindow();
        if (replacedWindow == null) {
            return false;
        }
        Rect frame = replacedWindow.mVisibleFrame;
        this.mOpeningApps.add(atoken);
        prepareAppTransition(18, true);
        this.mAppTransition.overridePendingAppTransitionClipReveal(frame.left, frame.top, frame.width(), frame.height());
        executeAppTransition();
        return true;
    }

    private void prepareNoneTransitionForRelaunching(AppWindowToken atoken) {
        if (this.mDisplayFrozen && !this.mOpeningApps.contains(atoken) && atoken.isRelaunching()) {
            this.mOpeningApps.add(atoken);
            prepareAppTransition(0, false);
            executeAppTransition();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSecureLocked(WindowState w) {
        return (w.mAttrs.flags & 8192) != 0 || DevicePolicyCache.getInstance().getScreenCaptureDisabled(UserHandle.getUserId(w.mOwnerUid));
    }

    public void refreshScreenCaptureDisabled(int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1000) {
            throw new SecurityException("Only system can call refreshScreenCaptureDisabled.");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.setSecureSurfaceState(userId, DevicePolicyCache.getInstance().getScreenCaptureDisabled(userId));
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeWindow(Session session, IWindow client) {
        WindowState win;
        synchronized (this.mWindowMap) {
            try {
                try {
                    boostPriorityForLockedSection();
                    win = windowForClientLocked(session, client, false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (win == null) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                win.removeIfPossible();
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postWindowRemoveCleanupLocked(WindowState win) {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "postWindowRemoveCleanupLocked: " + win);
        }
        xpWindowManagerService.get(this.mContext).onWindowRemoved(win.mAttrs);
        this.mWindowMap.remove(win.mClient.asBinder());
        markForSeamlessRotation(win, false);
        win.resetAppOpsState();
        if (this.mCurrentFocus == null) {
            this.mWinRemovedSinceNullFocus.add(win);
        }
        this.mPendingRemove.remove(win);
        this.mResizingWindows.remove(win);
        updateNonSystemOverlayWindowsVisibilityIfNeeded(win, false);
        this.mWindowsChanged = true;
        if (WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT) {
            Slog.v(TAG, "Final remove of window: " + win);
        }
        if (this.mInputMethodWindow == win) {
            setInputMethodWindowLocked(null);
        }
        WindowToken token = win.mToken;
        AppWindowToken atoken = win.mAppToken;
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "Removing " + win + " from " + token);
        }
        if (token.isEmpty()) {
            if (!token.mPersistOnEmpty) {
                token.removeImmediately();
            } else if (atoken != null) {
                atoken.firstWindowDrawn = false;
                atoken.clearAllDrawn();
                TaskStack stack = atoken.getStack();
                if (stack != null) {
                    stack.mExitingAppTokens.remove(atoken);
                }
            }
        }
        if (atoken != null) {
            atoken.postWindowRemoveStartingWindowCleanup(win);
        }
        DisplayContent dc = win.getDisplayContent();
        if (win.mAttrs.type == 2013) {
            dc.mWallpaperController.clearLastWallpaperTimeoutTime();
            dc.pendingLayoutChanges |= 4;
        } else if ((win.mAttrs.flags & 1048576) != 0) {
            dc.pendingLayoutChanges |= 4;
        }
        if (dc != null && !this.mWindowPlacerLocked.isInLayout()) {
            dc.assignWindowLayers(true);
            this.mWindowPlacerLocked.performSurfacePlacement();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }
        this.mInputMonitor.updateInputWindowsLw(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInputMethodWindowLocked(WindowState win) {
        this.mInputMethodWindow = win;
        DisplayContent dc = win != null ? win.getDisplayContent() : getDefaultDisplayContentLocked();
        dc.computeImeTarget(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateHiddenWhileSuspendedState(ArraySet<String> packages, boolean suspended) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.updateHiddenWhileSuspendedState(packages, suspended);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAppOpsState() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.updateAppOpsState();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void logSurface(WindowState w, String msg, boolean withStackTrace) {
        String str = "  SURFACE " + msg + ": " + w;
        if (withStackTrace) {
            logWithStack(TAG, str);
        } else {
            Slog.i(TAG, str);
        }
    }

    static void logSurface(SurfaceControl s, String title, String msg) {
        String str = "  SURFACE " + s + ": " + msg + " / " + title;
        Slog.i(TAG, str);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void logWithStack(String tag, String s) {
        RuntimeException e = null;
        if (WindowManagerDebugConfig.SHOW_STACK_CRAWLS) {
            e = new RuntimeException();
            e.fillInStackTrace();
        }
        Slog.i(tag, s, e);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTransparentRegionWindow(Session session, IWindow client, Region region) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                WindowState w = windowForClientLocked(session, client, false);
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                    logSurface(w, "transparentRegionHint=" + region, false);
                }
                if (w != null && w.mHasSurface) {
                    w.mWinAnimator.setTransparentRegionHintLocked(region);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInsetsWindow(Session session, IWindow client, int touchableInsets, Rect contentInsets, Rect visibleInsets, Region touchableRegion) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                WindowState w = windowForClientLocked(session, client, false);
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.d(TAG, "setInsetsWindow " + w + ", contentInsets=" + w.mGivenContentInsets + " -> " + contentInsets + ", visibleInsets=" + w.mGivenVisibleInsets + " -> " + visibleInsets + ", touchableRegion=" + w.mGivenTouchableRegion + " -> " + touchableRegion + ", touchableInsets " + w.mTouchableInsets + " -> " + touchableInsets);
                }
                if (w != null) {
                    w.mGivenInsetsPending = false;
                    w.mGivenContentInsets.set(contentInsets);
                    w.mGivenVisibleInsets.set(visibleInsets);
                    w.mGivenTouchableRegion.set(touchableRegion);
                    w.mTouchableInsets = touchableInsets;
                    if (w.mGlobalScale != 1.0f) {
                        w.mGivenContentInsets.scale(w.mGlobalScale);
                        w.mGivenVisibleInsets.scale(w.mGlobalScale);
                        w.mGivenTouchableRegion.scale(w.mGlobalScale);
                    }
                    w.setDisplayLayoutNeeded();
                    this.mWindowPlacerLocked.performSurfacePlacement();
                    if (this.mAccessibilityController != null && w.getDisplayContent().getDisplayId() == 0) {
                        this.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
                    }
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void getWindowDisplayFrame(Session session, IWindow client, Rect outDisplayFrame) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    outDisplayFrame.setEmpty();
                    resetPriorityAfterLockedSection();
                    return;
                }
                outDisplayFrame.set(win.mDisplayFrame);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        WindowState window;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mAccessibilityController != null && (window = this.mWindowMap.get(token)) != null && window.getDisplayId() == 0) {
                    this.mAccessibilityController.onRectangleOnScreenRequestedLocked(rectangle);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public IWindowId getWindowId(IBinder token) {
        WindowState.WindowId windowId;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState window = this.mWindowMap.get(token);
                windowId = window != null ? window.mWindowId : null;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return windowId;
    }

    public void pokeDrawLock(Session session, IBinder token) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState window = windowForClientLocked(session, token, false);
                if (window != null) {
                    window.pokeDrawLockLw(this.mDrawLockTimeoutMillis);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:84:0x0188
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public int relayoutWindow(com.android.server.wm.Session r39, android.view.IWindow r40, int r41, android.view.WindowManager.LayoutParams r42, int r43, int r44, int r45, int r46, long r47, android.graphics.Rect r49, android.graphics.Rect r50, android.graphics.Rect r51, android.graphics.Rect r52, android.graphics.Rect r53, android.graphics.Rect r54, android.graphics.Rect r55, android.view.DisplayCutout.ParcelableWrapper r56, android.util.MergedConfiguration r57, android.view.Surface r58) {
        /*
            Method dump skipped, instructions count: 1603
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.relayoutWindow(com.android.server.wm.Session, android.view.IWindow, int, android.view.WindowManager$LayoutParams, int, int, int, int, long, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.view.DisplayCutout$ParcelableWrapper, android.util.MergedConfiguration, android.view.Surface):int");
    }

    private boolean tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator, boolean isDefaultDisplay, boolean focusMayChange) {
        int transit = 2;
        if (win.mAttrs.type == 3) {
            transit = 5;
        }
        if (win.isWinVisibleLw() && winAnimator.applyAnimationLocked(transit, false)) {
            focusMayChange = isDefaultDisplay;
            win.mAnimatingExit = true;
        } else if (win.mWinAnimator.isAnimationSet()) {
            win.mAnimatingExit = true;
        } else if (win.getDisplayContent().mWallpaperController.isWallpaperTarget(win)) {
            win.mAnimatingExit = true;
        } else {
            if (this.mInputMethodWindow == win) {
                setInputMethodWindowLocked(null);
            }
            boolean stopped = win.mAppToken != null ? win.mAppToken.mAppStopped : true;
            win.mDestroying = true;
            win.destroySurface(false, stopped);
        }
        if (this.mAccessibilityController != null && win.getDisplayId() == 0) {
            this.mAccessibilityController.onWindowTransitionLocked(win, transit);
        }
        SurfaceControl.openTransaction();
        winAnimator.detachChildren();
        SurfaceControl.closeTransaction();
        return focusMayChange;
    }

    private int createSurfaceControl(Surface outSurface, int result, WindowState win, WindowStateAnimator winAnimator) {
        if (!win.mHasSurface) {
            result |= 4;
        }
        try {
            Trace.traceBegin(32L, "createSurfaceControl");
            WindowSurfaceController surfaceController = winAnimator.createSurfaceLocked(win.mAttrs.type, win.mOwnerUid);
            if (surfaceController != null) {
                surfaceController.getSurface(outSurface);
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                    Slog.i(TAG, "  OUT SURFACE " + outSurface + ": copied");
                }
            } else {
                Slog.w(TAG, "Failed to create surface control for " + win);
                outSurface.release();
            }
            return result;
        } finally {
            Trace.traceEnd(32L);
        }
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, client, false);
                if (win != null) {
                    boolean reclaimSomeSurfaceMemory = this.mRoot.reclaimSomeSurfaceMemory(win.mWinAnimator, "from-client", false);
                    resetPriorityAfterLockedSection();
                    return reclaimSomeSurfaceMemory;
                }
                resetPriorityAfterLockedSection();
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishDrawingWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, client, false);
                if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("finishDrawingWindow: ");
                    sb.append(win);
                    sb.append(" mDrawState=");
                    sb.append(win != null ? win.mWinAnimator.drawStateToString() : "null");
                    Slog.d(TAG, sb.toString());
                }
                if (win != null && win.mWinAnimator.finishDrawingLocked()) {
                    if ((win.mAttrs.flags & 1048576) != 0) {
                        win.getDisplayContent().pendingLayoutChanges |= 4;
                    }
                    win.setDisplayLayoutNeeded();
                    this.mWindowPlacerLocked.requestTraversal();
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean checkCallingPermission(String permission, String func) {
        if (Binder.getCallingPid() == Process.myPid() || this.mContext.checkCallingPermission(permission) == 0) {
            return true;
        }
        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires " + permission;
        Slog.w(TAG, msg);
        return false;
    }

    public void addWindowToken(IBinder binder, int type, int displayId) {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "addWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                WindowToken token = dc.getWindowToken(binder);
                if (token != null) {
                    Slog.w(TAG, "addWindowToken: Attempted to add binder token: " + binder + " for already created window token: " + token + " displayId=" + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                if (type == 2013) {
                    new WallpaperWindowToken(this, binder, true, dc, true);
                } else {
                    new WindowToken(this, binder, type, true, dc, true);
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void removeWindowToken(IBinder binder, int displayId) {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "removeWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w(TAG, "removeWindowToken: Attempted to remove token: " + binder + " for non-exiting displayId=" + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                WindowToken token = dc.removeWindowToken(binder);
                if (token != null) {
                    this.mInputMonitor.updateInputWindowsLw(true);
                    resetPriorityAfterLockedSection();
                    return;
                }
                Slog.w(TAG, "removeWindowToken: Attempted to remove non-existing token: " + binder);
                resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public Configuration updateOrientationFromAppTokens(Configuration currentConfig, IBinder freezeThisOneIfNeeded, int displayId) {
        return updateOrientationFromAppTokens(currentConfig, freezeThisOneIfNeeded, displayId, false);
    }

    public Configuration updateOrientationFromAppTokens(Configuration currentConfig, IBinder freezeThisOneIfNeeded, int displayId, boolean forceUpdate) {
        Configuration config;
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "updateOrientationFromAppTokens()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                config = updateOrientationFromAppTokensLocked(currentConfig, freezeThisOneIfNeeded, displayId, forceUpdate);
            }
            resetPriorityAfterLockedSection();
            return config;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private Configuration updateOrientationFromAppTokensLocked(Configuration currentConfig, IBinder freezeThisOneIfNeeded, int displayId, boolean forceUpdate) {
        AppWindowToken atoken;
        if (!this.mDisplayReady) {
            return null;
        }
        if (updateOrientationFromAppTokensLocked(displayId, forceUpdate)) {
            if (freezeThisOneIfNeeded != null && !this.mRoot.mOrientationChangeComplete && (atoken = this.mRoot.getAppWindowToken(freezeThisOneIfNeeded)) != null) {
                atoken.startFreezingScreen();
            }
            Configuration config = computeNewConfigurationLocked(displayId);
            return config;
        } else if (currentConfig == null) {
            return null;
        } else {
            this.mTempConfiguration.unset();
            this.mTempConfiguration.updateFrom(currentConfig);
            DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
            displayContent.computeScreenConfiguration(this.mTempConfiguration);
            if (currentConfig.diff(this.mTempConfiguration) == 0) {
                return null;
            }
            this.mWaitingForConfig = true;
            displayContent.setLayoutNeeded();
            int[] anim = new int[2];
            this.mPolicy.selectRotationAnimationLw(anim);
            startFreezingDisplayLocked(anim[0], anim[1], displayContent);
            Configuration config2 = new Configuration(this.mTempConfiguration);
            return config2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateOrientationFromAppTokensLocked(int displayId) {
        return updateOrientationFromAppTokensLocked(displayId, false);
    }

    boolean updateOrientationFromAppTokensLocked(int displayId, boolean forceUpdate) {
        long ident = Binder.clearCallingIdentity();
        try {
            DisplayContent dc = this.mRoot.getDisplayContent(displayId);
            int req = dc.getOrientation();
            if (req != dc.getLastOrientation() || forceUpdate) {
                dc.setLastOrientation(req);
                if (dc.isDefaultDisplay) {
                    this.mPolicy.setCurrentOrientationLw(req);
                }
                return dc.updateRotationUnchecked(forceUpdate);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean rotationNeedsUpdateLocked() {
        DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
        int lastOrientation = defaultDisplayContent.getLastOrientation();
        int oldRotation = defaultDisplayContent.getRotation();
        boolean oldAltOrientation = defaultDisplayContent.getAltOrientation();
        int rotation = this.mPolicy.rotationForOrientationLw(lastOrientation, oldRotation, true);
        boolean altOrientation = !this.mPolicy.rotationHasCompatibleMetricsLw(lastOrientation, rotation);
        return (oldRotation == rotation && oldAltOrientation == altOrientation) ? false : true;
    }

    public int[] setNewDisplayOverrideConfiguration(Configuration overrideConfig, int displayId) {
        int[] displayOverrideConfigurationIfNeeded;
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "setNewDisplayOverrideConfiguration()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mWaitingForConfig) {
                    this.mWaitingForConfig = false;
                    this.mLastFinishedFreezeSource = "new-config";
                }
                displayOverrideConfigurationIfNeeded = this.mRoot.setDisplayOverrideConfigurationIfNeeded(overrideConfig, displayId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return displayOverrideConfigurationIfNeeded;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusTaskRegionLocked(AppWindowToken previousFocus) {
        Task focusedTask = this.mFocusedApp != null ? this.mFocusedApp.getTask() : null;
        Task previousTask = previousFocus != null ? previousFocus.getTask() : null;
        DisplayContent focusedDisplayContent = focusedTask != null ? focusedTask.getDisplayContent() : null;
        DisplayContent previousDisplayContent = previousTask != null ? previousTask.getDisplayContent() : null;
        if (previousDisplayContent != null && previousDisplayContent != focusedDisplayContent) {
            previousDisplayContent.setTouchExcludeRegion(null);
        }
        if (focusedDisplayContent != null) {
            focusedDisplayContent.setTouchExcludeRegion(focusedTask);
        }
    }

    public void setFocusedApp(IBinder token, boolean moveFocusNow) {
        AppWindowToken newFocus;
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "setFocusedApp()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (token == null) {
                    if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                        Slog.v(TAG, "Clearing focused app, was " + this.mFocusedApp);
                    }
                    newFocus = null;
                } else {
                    newFocus = this.mRoot.getAppWindowToken(token);
                    if (newFocus == null) {
                        Slog.w(TAG, "Attempted to set focus to non-existing app token: " + token);
                    }
                    if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                        Slog.v(TAG, "Set focused app to: " + newFocus + " old focus=" + this.mFocusedApp + " moveFocusNow=" + moveFocusNow);
                    }
                }
                boolean changed = this.mFocusedApp != newFocus;
                if (changed) {
                    AppWindowToken prev = this.mFocusedApp;
                    this.mFocusedApp = newFocus;
                    this.mInputMonitor.setFocusedAppLw(newFocus);
                    setFocusTaskRegionLocked(prev);
                }
                if (moveFocusNow && changed) {
                    long origId = Binder.clearCallingIdentity();
                    updateFocusedWindowLocked(0, true);
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        prepareAppTransition(transit, alwaysKeepCurrent, 0, false);
    }

    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent, int flags, boolean forceOverride) {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                boolean prepared = this.mAppTransition.prepareAppTransitionLocked(transit, alwaysKeepCurrent, flags, forceOverride);
                DisplayContent dc = this.mRoot.getDisplayContent(0);
                if (prepared && dc != null && dc.okToAnimate()) {
                    this.mSkipAppTransitionAnimation = false;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getPendingAppTransition() {
        return this.mAppTransition.getAppTransition();
    }

    public void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim, IRemoteCallback startedCallback) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransition(packageName, enterAnim, exitAnim, startedCallback);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth, int startHeight) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionScaleUp(startX, startY, startWidth, startHeight);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionClipReveal(int startX, int startY, int startWidth, int startHeight) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionClipReveal(startX, startY, startWidth, startHeight);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionThumb(GraphicBuffer srcThumb, int startX, int startY, IRemoteCallback startedCallback, boolean scaleUp) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionThumb(srcThumb, startX, startY, startedCallback, scaleUp);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionAspectScaledThumb(GraphicBuffer srcThumb, int startX, int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback, boolean scaleUp) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionAspectScaledThumb(srcThumb, startX, startY, targetWidth, targetHeight, startedCallback, scaleUp);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionMultiThumb(AppTransitionAnimationSpec[] specs, IRemoteCallback onAnimationStartedCallback, IRemoteCallback onAnimationFinishedCallback, boolean scaleUp) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionMultiThumb(specs, onAnimationStartedCallback, onAnimationFinishedCallback, scaleUp);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionStartCrossProfileApps() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionStartCrossProfileApps();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionInPlace(String packageName, int anim) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overrideInPlaceAppTransition(packageName, anim);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionMultiThumbFuture(IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback, boolean scaleUp) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionMultiThumbFuture(specsFuture, callback, scaleUp);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter) {
        if (!checkCallingPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS", "overridePendingAppTransitionRemote()")) {
            throw new SecurityException("Requires CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAppTransition.overridePendingAppTransitionRemote(remoteAnimationAdapter);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void endProlongedAnimations() {
    }

    public void executeAppTransition() {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.w(TAG, "Execute app transition: " + this.mAppTransition + " Callers=" + Debug.getCallers(5));
                }
                if (this.mAppTransition.isTransitionSet()) {
                    this.mAppTransition.setReady();
                    this.mWindowPlacerLocked.requestTraversal();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void initializeRecentsAnimation(int targetActivityType, IRecentsAnimationRunner recentsAnimationRunner, RecentsAnimationController.RecentsAnimationCallbacks callbacks, int displayId, SparseBooleanArray recentTaskIds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRecentsAnimationController = new RecentsAnimationController(this, recentsAnimationRunner, callbacks, displayId);
                this.mAppTransition.updateBooster();
                this.mRecentsAnimationController.initialize(targetActivityType, recentTaskIds);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public RecentsAnimationController getRecentsAnimationController() {
        return this.mRecentsAnimationController;
    }

    public boolean canStartRecentsAnimation() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mAppTransition.isTransitionSet()) {
                    resetPriorityAfterLockedSection();
                    return false;
                }
                resetPriorityAfterLockedSection();
                return true;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void cancelRecentsAnimationSynchronously(@RecentsAnimationController.ReorderMode int reorderMode, String reason) {
        if (this.mRecentsAnimationController != null) {
            this.mRecentsAnimationController.cancelAnimationSynchronously(reorderMode, reason);
        }
    }

    public void cleanupRecentsAnimation(@RecentsAnimationController.ReorderMode int reorderMode) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mRecentsAnimationController != null) {
                    this.mRecentsAnimationController.cleanupAnimation(reorderMode);
                    this.mRecentsAnimationController = null;
                    this.mAppTransition.updateBooster();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setAppFullscreen(IBinder token, boolean toOpaque) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken atoken = this.mRoot.getAppWindowToken(token);
                if (atoken != null) {
                    atoken.setFillsParent(toOpaque);
                    setWindowOpaqueLocked(token, toOpaque);
                    this.mWindowPlacerLocked.requestTraversal();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setWindowOpaque(IBinder token, boolean isOpaque) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                setWindowOpaqueLocked(token, isOpaque);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private void setWindowOpaqueLocked(IBinder token, boolean isOpaque) {
        WindowState win;
        AppWindowToken wtoken = this.mRoot.getAppWindowToken(token);
        if (wtoken != null && (win = wtoken.findMainWindow()) != null) {
            win.mWinAnimator.setOpaqueLocked(isOpaque);
        }
    }

    public void setDockedStackCreateState(int mode, Rect bounds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                setDockedStackCreateStateLocked(mode, bounds);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDockedStackCreateStateLocked(int mode, Rect bounds) {
        this.mDockedStackCreateMode = mode;
        this.mDockedStackCreateBounds = bounds;
    }

    public void checkSplitScreenMinimizedChanged(boolean animate) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                displayContent.getDockedDividerController().checkMinimizeChanged(animate);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isValidPictureInPictureAspectRatio(int displayId, float aspectRatio) {
        DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
        return displayContent.getPinnedStackController().isValidPictureInPictureAspectRatio(aspectRatio);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void getStackBounds(int windowingMode, int activityType, Rect bounds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                TaskStack stack = this.mRoot.getStack(windowingMode, activityType);
                if (stack != null) {
                    stack.getBounds(bounds);
                    resetPriorityAfterLockedSection();
                    return;
                }
                bounds.setEmpty();
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void notifyShowingDreamChanged() {
        notifyKeyguardFlagsChanged(null);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public WindowManagerPolicy.WindowState getInputMethodWindowLw() {
        return this.mInputMethodWindow;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void notifyKeyguardTrustedChanged() {
        this.mH.sendEmptyMessage(57);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void screenTurningOff(WindowManagerPolicy.ScreenOffListener listener) {
        this.mTaskSnapshotController.screenTurningOff(listener);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void triggerAnimationFailsafe() {
        this.mH.sendEmptyMessage(60);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void onKeyguardShowingAndNotOccludedChanged() {
        this.mH.sendEmptyMessage(61);
    }

    public void deferSurfaceLayout() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWindowPlacerLocked.deferLayout();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void continueSurfaceLayout() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWindowPlacerLocked.continueLayout();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean containsShowWhenLockedWindow(IBinder token) {
        boolean z;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken wtoken = this.mRoot.getAppWindowToken(token);
                z = wtoken != null && wtoken.containsShowWhenLockedWindow();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return z;
    }

    public boolean containsDismissKeyguardWindow(IBinder token) {
        boolean z;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken wtoken = this.mRoot.getAppWindowToken(token);
                z = wtoken != null && wtoken.containsDismissKeyguardWindow();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyKeyguardFlagsChanged(final Runnable callback) {
        Runnable wrappedCallback;
        if (callback != null) {
            wrappedCallback = new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$5dMkMeana3BB2vTfpghrIR2jQMg
                @Override // java.lang.Runnable
                public final void run() {
                    WindowManagerService.lambda$notifyKeyguardFlagsChanged$1(WindowManagerService.this, callback);
                }
            };
        } else {
            wrappedCallback = null;
        }
        this.mH.obtainMessage(56, wrappedCallback).sendToTarget();
    }

    public static /* synthetic */ void lambda$notifyKeyguardFlagsChanged$1(WindowManagerService windowManagerService, Runnable callback) {
        synchronized (windowManagerService.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                callback.run();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isKeyguardTrusted() {
        boolean isKeyguardTrustedLw;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                isKeyguardTrustedLw = this.mPolicy.isKeyguardTrustedLw();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return isKeyguardTrustedLw;
    }

    public void setKeyguardGoingAway(boolean keyguardGoingAway) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mKeyguardGoingAway = keyguardGoingAway;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setKeyguardOrAodShowingOnDefaultDisplay(boolean showing) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mKeyguardOrAodShowingOnDefaultDisplay = showing;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void startFreezingScreen(int exitAnim, int enterAnim) {
        if (!checkCallingPermission("android.permission.FREEZE_SCREEN", "startFreezingScreen()")) {
            throw new SecurityException("Requires FREEZE_SCREEN permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (!this.mClientFreezingScreen) {
                    this.mClientFreezingScreen = true;
                    long origId = Binder.clearCallingIdentity();
                    startFreezingDisplayLocked(exitAnim, enterAnim);
                    this.mH.removeMessages(30);
                    this.mH.sendEmptyMessageDelayed(30, 5000L);
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void stopFreezingScreen() {
        if (!checkCallingPermission("android.permission.FREEZE_SCREEN", "stopFreezingScreen()")) {
            throw new SecurityException("Requires FREEZE_SCREEN permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mClientFreezingScreen) {
                    this.mClientFreezingScreen = false;
                    this.mLastFinishedFreezeSource = "client";
                    long origId = Binder.clearCallingIdentity();
                    stopFreezingDisplayLocked();
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void disableKeyguard(IBinder token, String tag) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        if (Binder.getCallingUid() != 1000 && isKeyguardSecure()) {
            Log.d(TAG, "current mode is SecurityMode, ignore disableKeyguard");
        } else if (!isCurrentProfileLocked(UserHandle.getCallingUserId())) {
            Log.d(TAG, "non-current profiles, ignore disableKeyguard");
        } else if (token == null) {
            throw new IllegalArgumentException("token == null");
        } else {
            this.mKeyguardDisableHandler.sendMessage(this.mKeyguardDisableHandler.obtainMessage(1, new Pair(token, tag)));
        }
    }

    public void reenableKeyguard(IBinder token) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        if (token == null) {
            throw new IllegalArgumentException("token == null");
        }
        this.mKeyguardDisableHandler.sendMessage(this.mKeyguardDisableHandler.obtainMessage(2, token));
    }

    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        this.mPolicy.exitKeyguardSecurely(new WindowManagerPolicy.OnKeyguardExitResult() { // from class: com.android.server.wm.WindowManagerService.8
            @Override // com.android.server.policy.WindowManagerPolicy.OnKeyguardExitResult
            public void onKeyguardExitResult(boolean success) {
                try {
                    callback.onKeyguardExitResult(success);
                } catch (RemoteException e) {
                }
            }
        });
    }

    public boolean isKeyguardLocked() {
        return this.mPolicy.isKeyguardLocked();
    }

    public boolean isKeyguardShowingAndNotOccluded() {
        return this.mPolicy.isKeyguardShowingAndNotOccluded();
    }

    public boolean isKeyguardSecure() {
        int userId = UserHandle.getCallingUserId();
        long origId = Binder.clearCallingIdentity();
        try {
            return this.mPolicy.isKeyguardSecure(userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean isShowingDream() {
        boolean isShowingDreamLw;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                isShowingDreamLw = this.mPolicy.isShowingDreamLw();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return isShowingDreamLw;
    }

    public void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) {
        if (!checkCallingPermission("android.permission.CONTROL_KEYGUARD", "dismissKeyguard")) {
            throw new SecurityException("Requires CONTROL_KEYGUARD permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.dismissKeyguardLw(callback, message);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void onKeyguardOccludedChanged(boolean occluded) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.onKeyguardOccludedChangedLw(occluded);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setSwitchingUser(boolean switching) {
        if (!checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "setSwitchingUser()")) {
            throw new SecurityException("Requires INTERACT_ACROSS_USERS_FULL permission");
        }
        this.mPolicy.setSwitchingUser(switching);
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mSwitchingUser = switching;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    void showGlobalActions() {
        this.mPolicy.showGlobalActions();
    }

    public void closeSystemDialogs(String reason) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.closeSystemDialogs(reason);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    static float fixScale(float scale) {
        if (scale < 0.0f) {
            scale = 0.0f;
        } else if (scale > 20.0f) {
            scale = 20.0f;
        }
        return Math.abs(scale);
    }

    public void setAnimationScale(int which, float scale) {
        if (!checkCallingPermission("android.permission.SET_ANIMATION_SCALE", "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }
        float scale2 = fixScale(scale);
        switch (which) {
            case 0:
                this.mWindowAnimationScaleSetting = scale2;
                break;
            case 1:
                this.mTransitionAnimationScaleSetting = scale2;
                break;
            case 2:
                this.mAnimatorDurationScaleSetting = scale2;
                break;
        }
        this.mH.sendEmptyMessage(14);
    }

    public void setAnimationScales(float[] scales) {
        if (!checkCallingPermission("android.permission.SET_ANIMATION_SCALE", "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }
        if (scales != null) {
            if (scales.length >= 1) {
                this.mWindowAnimationScaleSetting = fixScale(scales[0]);
            }
            if (scales.length >= 2) {
                this.mTransitionAnimationScaleSetting = fixScale(scales[1]);
            }
            if (scales.length >= 3) {
                this.mAnimatorDurationScaleSetting = fixScale(scales[2]);
                dispatchNewAnimatorScaleLocked(null);
            }
        }
        this.mH.sendEmptyMessage(14);
    }

    private void setAnimatorDurationScale(float scale) {
        this.mAnimatorDurationScaleSetting = scale;
        ValueAnimator.setDurationScale(scale);
    }

    public float getWindowAnimationScaleLocked() {
        if (this.mAnimationsDisabled) {
            return 0.0f;
        }
        return this.mWindowAnimationScaleSetting;
    }

    public float getTransitionAnimationScaleLocked() {
        if (this.mAnimationsDisabled) {
            return 0.0f;
        }
        return this.mTransitionAnimationScaleSetting;
    }

    public float getAnimationScale(int which) {
        switch (which) {
            case 0:
                return this.mWindowAnimationScaleSetting;
            case 1:
                return this.mTransitionAnimationScaleSetting;
            case 2:
                return this.mAnimatorDurationScaleSetting;
            default:
                return 0.0f;
        }
    }

    public float[] getAnimationScales() {
        return new float[]{this.mWindowAnimationScaleSetting, this.mTransitionAnimationScaleSetting, this.mAnimatorDurationScaleSetting};
    }

    public float getCurrentAnimatorScale() {
        float f;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                f = this.mAnimationsDisabled ? 0.0f : this.mAnimatorDurationScaleSetting;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dispatchNewAnimatorScaleLocked(Session session) {
        this.mH.obtainMessage(34, session).sendToTarget();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener) {
        this.mPointerEventDispatcher.registerInputEventListener(listener);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void unregisterPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener) {
        this.mPointerEventDispatcher.unregisterInputEventListener(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canDispatchPointerEvents() {
        return this.mPointerEventDispatcher != null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public int getLidState() {
        int sw = this.mInputManager.getSwitchState(-1, -256, 0);
        if (sw > 0) {
            return 0;
        }
        return sw == 0 ? 1 : -1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void lockDeviceNow() {
        lockNow(null);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public int getCameraLensCoverState() {
        int sw = this.mInputManager.getSwitchState(-1, -256, 9);
        if (sw > 0) {
            return 1;
        }
        return sw == 0 ? 0 : -1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void switchKeyboardLayout(int deviceId, int direction) {
        this.mInputManager.switchKeyboardLayout(deviceId, direction);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void switchInputMethod(boolean forwardDirection) {
        InputMethodManagerInternal inputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
        if (inputMethodManagerInternal != null) {
            inputMethodManagerInternal.switchInputMethod(forwardDirection);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void shutdown(boolean confirm) {
        ShutdownThread.shutdown(ActivityThread.currentActivityThread().getSystemUiContext(), "userrequested", confirm);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void reboot(boolean confirm) {
        ShutdownThread.reboot(ActivityThread.currentActivityThread().getSystemUiContext(), "userrequested", confirm);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void rebootSafeMode(boolean confirm) {
        ShutdownThread.rebootSafeMode(ActivityThread.currentActivityThread().getSystemUiContext(), confirm);
    }

    public void setCurrentProfileIds(int[] currentProfileIds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mCurrentProfileIds = currentProfileIds;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setCurrentUser(int newUserId, int[] currentProfileIds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mCurrentUserId = newUserId;
                this.mCurrentProfileIds = currentProfileIds;
                this.mAppTransition.setCurrentUser(newUserId);
                this.mPolicy.setCurrentUserLw(newUserId);
                boolean z = true;
                this.mPolicy.enableKeyguard(true);
                this.mRoot.switchUser();
                this.mWindowPlacerLocked.performSurfacePlacement();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                TaskStack stack = displayContent.getSplitScreenPrimaryStackIgnoringVisibility();
                DockedStackDividerController dockedStackDividerController = displayContent.mDividerControllerLocked;
                if (stack == null || !stack.hasTaskForUser(newUserId)) {
                    z = false;
                }
                dockedStackDividerController.notifyDockedStackExistsChanged(z);
                if (this.mDisplayReady) {
                    int forcedDensity = getForcedDisplayDensityForUserLocked(newUserId);
                    int targetDensity = forcedDensity != 0 ? forcedDensity : displayContent.mInitialDisplayDensity;
                    setForcedDisplayDensityLocked(displayContent, targetDensity);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCurrentProfileLocked(int userId) {
        if (userId == this.mCurrentUserId) {
            return true;
        }
        for (int i = 0; i < this.mCurrentProfileIds.length; i++) {
            if (this.mCurrentProfileIds[i] == userId) {
                return true;
            }
        }
        return false;
    }

    public void enableScreenAfterBoot() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_BOOT) {
                    RuntimeException here = new RuntimeException("here");
                    here.fillInStackTrace();
                    Slog.i(TAG, "enableScreenAfterBoot: mDisplayEnabled=" + this.mDisplayEnabled + " mForceDisplayEnabled=" + this.mForceDisplayEnabled + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted, here);
                }
                if (this.mSystemBooted) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                this.mSystemBooted = true;
                hideBootMessagesLocked();
                this.mH.sendEmptyMessageDelayed(23, 30000L);
                resetPriorityAfterLockedSection();
                this.mPolicy.systemBooted();
                performEnableScreen();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void enableScreenIfNeeded() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                enableScreenIfNeededLocked();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enableScreenIfNeededLocked() {
        if (WindowManagerDebugConfig.DEBUG_BOOT) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG, "enableScreenIfNeededLocked: mDisplayEnabled=" + this.mDisplayEnabled + " mForceDisplayEnabled=" + this.mForceDisplayEnabled + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted, here);
        }
        if (this.mDisplayEnabled) {
            return;
        }
        if (!this.mSystemBooted && !this.mShowingBootMessages) {
            return;
        }
        this.mH.sendEmptyMessage(16);
    }

    public void performBootTimeout() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mDisplayEnabled) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                Slog.w(TAG, "***** BOOT TIMEOUT: forcing display enabled");
                this.mForceDisplayEnabled = true;
                resetPriorityAfterLockedSection();
                performEnableScreen();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void onSystemUiStarted() {
        this.mPolicy.onSystemUiStarted();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void performEnableScreen() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_BOOT) {
                    Slog.i(TAG, "performEnableScreen: mDisplayEnabled=" + this.mDisplayEnabled + " mForceDisplayEnabled=" + this.mForceDisplayEnabled + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted + " mOnlyCore=" + this.mOnlyCore, new RuntimeException("here").fillInStackTrace());
                }
                if (this.mDisplayEnabled) {
                    resetPriorityAfterLockedSection();
                } else if (!this.mSystemBooted && !this.mShowingBootMessages) {
                    resetPriorityAfterLockedSection();
                } else if (!this.mForceDisplayEnabled && getDefaultDisplayContentLocked().checkWaitingForWindows()) {
                    resetPriorityAfterLockedSection();
                } else {
                    if (this.mPolicy != null) {
                        this.mPolicy.onBootChanged(this.mSystemBooted, this.mShowingBootMessages);
                    }
                    boolean allowFinishBootAnimation = this.mPolicy != null ? this.mPolicy.allowFinishBootAnimation() : true;
                    if (!this.mBootAnimationStopped) {
                        Trace.asyncTraceBegin(32L, "Stop bootanim", 0);
                        if (allowFinishBootAnimation) {
                            SystemProperties.set("service.bootanim.exit", "1");
                        }
                        this.mBootAnimationStopped = true;
                    }
                    if (!this.mForceDisplayEnabled && !checkBootAnimationCompleteLocked()) {
                        if (WindowManagerDebugConfig.DEBUG_BOOT) {
                            Slog.i(TAG, "performEnableScreen: Waiting for anim complete");
                        }
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    try {
                        IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                        if (surfaceFlinger != null && allowFinishBootAnimation) {
                            Slog.i(TAG, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                            Parcel data = Parcel.obtain();
                            data.writeInterfaceToken("android.ui.ISurfaceComposer");
                            surfaceFlinger.transact(1, data, null, 0);
                            data.recycle();
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Boot completed: SurfaceFlinger is dead!");
                    }
                    EventLog.writeEvent((int) EventLogTags.WM_BOOT_ANIMATION_DONE, SystemClock.uptimeMillis());
                    Trace.asyncTraceEnd(32L, "Stop bootanim", 0);
                    this.mDisplayEnabled = true;
                    if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || WindowManagerDebugConfig.DEBUG_BOOT) {
                        Slog.i(TAG, "******************** ENABLING SCREEN!");
                    }
                    this.mInputMonitor.setEventDispatchingLw(this.mEventDispatchingEnabled);
                    resetPriorityAfterLockedSection();
                    try {
                        this.mActivityManager.bootAnimationComplete();
                    } catch (RemoteException e2) {
                    }
                    this.mPolicy.enableScreenAfterBoot();
                    updateRotationUnchecked(false, false);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean checkBootAnimationCompleteLocked() {
        if (this.mPolicy.checkBootAnimationCompleted()) {
            return true;
        }
        if (SystemService.isRunning(BOOT_ANIMATION_SERVICE)) {
            this.mH.removeMessages(37);
            this.mH.sendEmptyMessageDelayed(37, 200L);
            if (WindowManagerDebugConfig.DEBUG_BOOT) {
                Slog.i(TAG, "checkBootAnimationComplete: Waiting for anim complete");
                return false;
            }
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_BOOT) {
            Slog.i(TAG, "checkBootAnimationComplete: Animation complete!");
        }
        return true;
    }

    public void showBootMessage(CharSequence msg, boolean always) {
        boolean first = false;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_BOOT) {
                    RuntimeException here = new RuntimeException("here");
                    here.fillInStackTrace();
                    Slog.i(TAG, "showBootMessage: msg=" + ((Object) msg) + " always=" + always + " mAllowBootMessages=" + this.mAllowBootMessages + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted, here);
                }
                if (!this.mAllowBootMessages) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                if (!this.mShowingBootMessages) {
                    if (!always) {
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    first = true;
                }
                if (this.mSystemBooted) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                this.mShowingBootMessages = true;
                this.mPolicy.showBootMessage(msg, always);
                resetPriorityAfterLockedSection();
                if (first) {
                    performEnableScreen();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void hideBootMessagesLocked() {
        if (WindowManagerDebugConfig.DEBUG_BOOT) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG, "hideBootMessagesLocked: mDisplayEnabled=" + this.mDisplayEnabled + " mForceDisplayEnabled=" + this.mForceDisplayEnabled + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted, here);
        }
        if (this.mShowingBootMessages) {
            this.mShowingBootMessages = false;
            this.mPolicy.hideBootMessages();
        }
    }

    public void setInTouchMode(boolean mode) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mInTouchMode = mode;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateCircularDisplayMaskIfNeeded() {
        int currentUserId;
        if (this.mContext.getResources().getConfiguration().isScreenRound() && this.mContext.getResources().getBoolean(17957102)) {
            synchronized (this.mWindowMap) {
                try {
                    boostPriorityForLockedSection();
                    currentUserId = this.mCurrentUserId;
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            int inversionState = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "accessibility_display_inversion_enabled", 0, currentUserId);
            int showMask = inversionState == 1 ? 0 : 1;
            Message m = this.mH.obtainMessage(35);
            m.arg1 = showMask;
            this.mH.sendMessage(m);
        }
    }

    public void showEmulatorDisplayOverlayIfNeeded() {
        if (this.mContext.getResources().getBoolean(17957098) && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false) && Build.IS_EMULATOR) {
            this.mH.sendMessage(this.mH.obtainMessage(36));
        }
    }

    public void showCircularMask(boolean visible) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION showCircularMask(visible=" + visible + ")");
                }
                openSurfaceTransaction();
                if (visible) {
                    if (this.mCircularDisplayMask == null) {
                        int screenOffset = this.mContext.getResources().getInteger(17694935);
                        int maskThickness = this.mContext.getResources().getDimensionPixelSize(17104975);
                        this.mCircularDisplayMask = new CircularDisplayMask(getDefaultDisplayContentLocked(), (this.mPolicy.getWindowLayerFromTypeLw(2018) * 10000) + 10, screenOffset, maskThickness);
                    }
                    this.mCircularDisplayMask.setVisibility(true);
                } else if (this.mCircularDisplayMask != null) {
                    this.mCircularDisplayMask.setVisibility(false);
                    this.mCircularDisplayMask = null;
                }
                closeSurfaceTransaction("showCircularMask");
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION showCircularMask(visible=" + visible + ")");
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void showEmulatorDisplayOverlay() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION showEmulatorDisplayOverlay");
                }
                openSurfaceTransaction();
                if (this.mEmulatorDisplayOverlay == null) {
                    this.mEmulatorDisplayOverlay = new EmulatorDisplayOverlay(this.mContext, getDefaultDisplayContentLocked(), (this.mPolicy.getWindowLayerFromTypeLw(2018) * 10000) + 10);
                }
                this.mEmulatorDisplayOverlay.setVisibility(true);
                closeSurfaceTransaction("showEmulatorDisplayOverlay");
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION showEmulatorDisplayOverlay");
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void showStrictModeViolation(boolean on) {
        int pid = Binder.getCallingPid();
        if (!on) {
            this.mH.sendMessage(this.mH.obtainMessage(25, 0, pid));
            return;
        }
        this.mH.sendMessage(this.mH.obtainMessage(25, 1, pid));
        this.mH.sendMessageDelayed(this.mH.obtainMessage(25, 0, pid), 1000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showStrictModeViolation(int arg, int pid) {
        boolean on = arg != 0;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (on && !this.mRoot.canShowStrictModeViolation(pid)) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                if (WindowManagerDebugConfig.SHOW_VERBOSE_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION showStrictModeViolation");
                }
                SurfaceControl.openTransaction();
                if (this.mStrictModeFlash == null) {
                    this.mStrictModeFlash = new StrictModeFlash(getDefaultDisplayContentLocked());
                }
                this.mStrictModeFlash.setVisibility(on);
                SurfaceControl.closeTransaction();
                if (WindowManagerDebugConfig.SHOW_VERBOSE_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION showStrictModeViolation");
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setStrictModeVisualIndicatorPreference(String value) {
        SystemProperties.set("persist.sys.strictmode.visual", value);
    }

    public Bitmap screenshotWallpaper() {
        Bitmap screenshotWallpaperLocked;
        if (!checkCallingPermission("android.permission.READ_FRAME_BUFFER", "screenshotWallpaper()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        try {
            Trace.traceBegin(32L, "screenshotWallpaper");
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                screenshotWallpaperLocked = this.mRoot.mWallpaperController.screenshotWallpaperLocked();
            }
            resetPriorityAfterLockedSection();
            return screenshotWallpaperLocked;
        } finally {
            Trace.traceEnd(32L);
        }
    }

    public boolean requestAssistScreenshot(final IAssistDataReceiver receiver) {
        Bitmap bm;
        final Bitmap bm2;
        if (!checkCallingPermission("android.permission.READ_FRAME_BUFFER", "requestAssistScreenshot()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(0);
                if (displayContent == null) {
                    if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                        Slog.i(TAG, "Screenshot returning null. No Display for displayId=0");
                    }
                    bm = null;
                } else {
                    bm = displayContent.screenshotDisplayLocked(Bitmap.Config.ARGB_8888);
                }
                bm2 = bm;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$CbEzJbdxOpfZ-AMUAcOVQZxepOo
            @Override // java.lang.Runnable
            public final void run() {
                receiver.onHandleAssistScreenshot(bm2);
            }
        });
        return true;
    }

    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, int userId, boolean reducedResolution) {
        return this.mTaskSnapshotController.getSnapshot(taskId, userId, true, reducedResolution);
    }

    public void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mTaskSnapshotController.removeObsoleteTaskFiles(persistentTaskIds, runningUserIds);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void freezeRotation(int rotation) {
        if (!checkCallingPermission("android.permission.SET_ORIENTATION", "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > 3) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid rotation constant.");
        }
        int defaultDisplayRotation = getDefaultDisplayRotation();
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "freezeRotation: mRotation=" + defaultDisplayRotation);
        }
        long origId = Binder.clearCallingIdentity();
        try {
            this.mPolicy.setUserRotationMode(1, rotation == -1 ? defaultDisplayRotation : rotation);
            Binder.restoreCallingIdentity(origId);
            updateRotationUnchecked(false, false);
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(origId);
            throw th;
        }
    }

    public void thawRotation() {
        if (!checkCallingPermission("android.permission.SET_ORIENTATION", "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "thawRotation: mRotation=" + getDefaultDisplayRotation());
        }
        long origId = Binder.clearCallingIdentity();
        try {
            this.mPolicy.setUserRotationMode(0, 777);
            Binder.restoreCallingIdentity(origId);
            updateRotationUnchecked(false, false);
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(origId);
            throw th;
        }
    }

    public void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout) {
        updateRotationUnchecked(alwaysSendConfiguration, forceRelayout);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pauseRotationLocked() {
        this.mDeferredRotationPauseCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeRotationLocked() {
        if (this.mDeferredRotationPauseCount > 0) {
            this.mDeferredRotationPauseCount--;
            if (this.mDeferredRotationPauseCount == 0) {
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                boolean changed = displayContent.updateRotationUnchecked();
                if (changed) {
                    this.mH.obtainMessage(18, Integer.valueOf(displayContent.getDisplayId())).sendToTarget();
                }
            }
        }
    }

    private void updateRotationUnchecked(boolean alwaysSendConfiguration, boolean forceRelayout) {
        boolean rotationChanged;
        int displayId;
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "updateRotationUnchecked: alwaysSendConfiguration=" + alwaysSendConfiguration + " forceRelayout=" + forceRelayout);
        }
        Trace.traceBegin(32L, "updateRotation");
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                Trace.traceBegin(32L, "updateRotation: display");
                rotationChanged = displayContent.updateRotationUnchecked();
                Trace.traceEnd(32L);
                if (!rotationChanged || forceRelayout) {
                    displayContent.setLayoutNeeded();
                    Trace.traceBegin(32L, "updateRotation: performSurfacePlacement");
                    this.mWindowPlacerLocked.performSurfacePlacement();
                    Trace.traceEnd(32L);
                }
                displayId = displayContent.getDisplayId();
            }
            resetPriorityAfterLockedSection();
            if (rotationChanged || alwaysSendConfiguration) {
                Trace.traceBegin(32L, "updateRotation: sendNewConfiguration");
                sendNewConfiguration(displayId);
                Trace.traceEnd(32L);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            Trace.traceEnd(32L);
        }
    }

    public int getDefaultDisplayRotation() {
        int rotation;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                rotation = getDefaultDisplayContentLocked().getRotation();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return rotation;
    }

    public boolean isRotationFrozen() {
        return this.mPolicy.getUserRotationMode() == 1;
    }

    public int watchRotation(IRotationWatcher watcher, int displayId) {
        int defaultDisplayRotation;
        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() { // from class: com.android.server.wm.WindowManagerService.9
            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                synchronized (WindowManagerService.this.mWindowMap) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        int i = 0;
                        while (i < WindowManagerService.this.mRotationWatchers.size()) {
                            if (watcherBinder == WindowManagerService.this.mRotationWatchers.get(i).mWatcher.asBinder()) {
                                RotationWatcher removed = WindowManagerService.this.mRotationWatchers.remove(i);
                                IBinder binder = removed.mWatcher.asBinder();
                                if (binder != null) {
                                    binder.unlinkToDeath(this, 0);
                                }
                                i--;
                            }
                            i++;
                        }
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        };
        synchronized (this.mWindowMap) {
            try {
                try {
                    boostPriorityForLockedSection();
                    watcher.asBinder().linkToDeath(dr, 0);
                    this.mRotationWatchers.add(new RotationWatcher(watcher, dr, displayId));
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (RemoteException e) {
            }
            defaultDisplayRotation = getDefaultDisplayRotation();
        }
        resetPriorityAfterLockedSection();
        return defaultDisplayRotation;
    }

    public void removeRotationWatcher(IRotationWatcher watcher) {
        IBinder watcherBinder = watcher.asBinder();
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                int i = 0;
                while (i < this.mRotationWatchers.size()) {
                    RotationWatcher rotationWatcher = this.mRotationWatchers.get(i);
                    if (watcherBinder == rotationWatcher.mWatcher.asBinder()) {
                        RotationWatcher removed = this.mRotationWatchers.remove(i);
                        IBinder binder = removed.mWatcher.asBinder();
                        if (binder != null) {
                            binder.unlinkToDeath(removed.mDeathRecipient, 0);
                        }
                        i--;
                    }
                    i++;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean registerWallpaperVisibilityListener(IWallpaperVisibilityListener listener, int displayId) {
        boolean isWallpaperVisible;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    throw new IllegalArgumentException("Trying to register visibility event for invalid display: " + displayId);
                }
                this.mWallpaperVisibilityListeners.registerWallpaperVisibilityListener(listener, displayId);
                isWallpaperVisible = displayContent.mWallpaperController.isWallpaperVisible();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return isWallpaperVisible;
    }

    public void unregisterWallpaperVisibilityListener(IWallpaperVisibilityListener listener, int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWallpaperVisibilityListeners.unregisterWallpaperVisibilityListener(listener, displayId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getPreferredOptionsPanelGravity() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                int rotation = displayContent.getRotation();
                if (displayContent.mInitialDisplayWidth < displayContent.mInitialDisplayHeight) {
                    switch (rotation) {
                        case 1:
                            resetPriorityAfterLockedSection();
                            return 85;
                        case 2:
                            resetPriorityAfterLockedSection();
                            return 81;
                        case 3:
                            resetPriorityAfterLockedSection();
                            return 8388691;
                        default:
                            return 81;
                    }
                }
                switch (rotation) {
                    case 1:
                        resetPriorityAfterLockedSection();
                        return 81;
                    case 2:
                        resetPriorityAfterLockedSection();
                        return 8388691;
                    case 3:
                        resetPriorityAfterLockedSection();
                        return 81;
                    default:
                        resetPriorityAfterLockedSection();
                        return 85;
                }
            } finally {
                resetPriorityAfterLockedSection();
            }
        }
    }

    public boolean startViewServer(int port) {
        if (!isSystemSecure() && checkCallingPermission("android.permission.DUMP", "startViewServer") && port >= 1024) {
            if (this.mViewServer != null) {
                if (!this.mViewServer.isRunning()) {
                    try {
                        return this.mViewServer.start();
                    } catch (IOException e) {
                        Slog.w(TAG, "View server did not start");
                    }
                }
                return false;
            }
            try {
                this.mViewServer = new ViewServer(this, port);
                return this.mViewServer.start();
            } catch (IOException e2) {
                Slog.w(TAG, "View server did not start");
                return false;
            }
        }
        return false;
    }

    private boolean isSystemSecure() {
        return "1".equals(SystemProperties.get(SYSTEM_SECURE, "1")) && "0".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
    }

    public boolean stopViewServer() {
        if (isSystemSecure() || !checkCallingPermission("android.permission.DUMP", "stopViewServer") || this.mViewServer == null) {
            return false;
        }
        return this.mViewServer.stop();
    }

    public boolean isViewServerRunning() {
        return !isSystemSecure() && checkCallingPermission("android.permission.DUMP", "isViewServerRunning") && this.mViewServer != null && this.mViewServer.isRunning();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean viewServerListWindows(Socket client) {
        if (isSystemSecure()) {
            return false;
        }
        final ArrayList<WindowState> windows = new ArrayList<>();
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$CIuXGvNhVwi8txA2L_PmZnPJavk
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        windows.add((WindowState) obj);
                    }
                }, false);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        BufferedWriter out = null;
        try {
            try {
                OutputStream clientStream = client.getOutputStream();
                out = new BufferedWriter(new OutputStreamWriter(clientStream), 8192);
                int count = windows.size();
                for (int i = 0; i < count; i++) {
                    WindowState w = windows.get(i);
                    out.write(Integer.toHexString(System.identityHashCode(w)));
                    out.write(32);
                    out.append(w.mAttrs.getTitle());
                    out.write(10);
                }
                out.write("DONE.\n");
                out.flush();
                out.close();
                return true;
            } catch (Exception e) {
                if (out == null) {
                    return false;
                }
                out.close();
                return false;
            } catch (Throwable th2) {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e2) {
                    }
                }
                throw th2;
            }
        } catch (IOException e3) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean viewServerGetFocusedWindow(Socket client) {
        if (isSystemSecure()) {
            return false;
        }
        WindowState focusedWindow = getFocusedWindow();
        BufferedWriter out = null;
        try {
            try {
                OutputStream clientStream = client.getOutputStream();
                out = new BufferedWriter(new OutputStreamWriter(clientStream), 8192);
                if (focusedWindow != null) {
                    out.write(Integer.toHexString(System.identityHashCode(focusedWindow)));
                    out.write(32);
                    out.append(focusedWindow.mAttrs.getTitle());
                }
                out.write(10);
                out.flush();
                out.close();
                return true;
            } catch (IOException e) {
                return false;
            }
        } catch (Exception e2) {
            if (out == null) {
                return false;
            }
            out.close();
            return false;
        } catch (Throwable th) {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e3) {
                }
            }
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean viewServerWindowCommand(Socket client, String command, String parameters) {
        WindowState window;
        if (isSystemSecure()) {
            return false;
        }
        boolean success = true;
        Parcel data = null;
        Parcel reply = null;
        BufferedWriter out = null;
        try {
            try {
                try {
                    int index = parameters.indexOf(32);
                    if (index == -1) {
                        index = parameters.length();
                    }
                    String code = parameters.substring(0, index);
                    int hashCode = (int) Long.parseLong(code, 16);
                    parameters = index < parameters.length() ? parameters.substring(index + 1) : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    window = findWindow(hashCode);
                } catch (Exception e) {
                    Slog.w(TAG, "Could not send command " + command + " with parameters " + parameters, e);
                    success = false;
                    if (data != null) {
                        data.recycle();
                    }
                    if (reply != null) {
                        reply.recycle();
                    }
                    if (out != null) {
                        out.close();
                    }
                }
            } catch (IOException e2) {
            }
            if (window == null) {
                return false;
            }
            data = Parcel.obtain();
            data.writeInterfaceToken("android.view.IWindow");
            data.writeString(command);
            data.writeString(parameters);
            data.writeInt(1);
            ParcelFileDescriptor.fromSocket(client).writeToParcel(data, 0);
            reply = Parcel.obtain();
            IBinder binder = window.mClient.asBinder();
            binder.transact(1, data, reply, 0);
            reply.readException();
            if (!client.isOutputShutdown()) {
                out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
                out.write("DONE\n");
                out.flush();
            }
            if (data != null) {
                data.recycle();
            }
            if (reply != null) {
                reply.recycle();
            }
            if (out != null) {
                out.close();
            }
            return success;
        } finally {
            if (data != null) {
                data.recycle();
            }
            if (reply != null) {
                reply.recycle();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e3) {
                }
            }
        }
    }

    public void addWindowChangeListener(WindowChangeListener listener) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWindowChangeListeners.add(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void removeWindowChangeListener(WindowChangeListener listener) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWindowChangeListeners.remove(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyWindowsChanged() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mWindowChangeListeners.isEmpty()) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                WindowChangeListener[] windowChangeListeners = (WindowChangeListener[]) this.mWindowChangeListeners.toArray(new WindowChangeListener[this.mWindowChangeListeners.size()]);
                resetPriorityAfterLockedSection();
                for (WindowChangeListener windowChangeListener : windowChangeListeners) {
                    windowChangeListener.windowsChanged();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyFocusChanged() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mWindowChangeListeners.isEmpty()) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                WindowChangeListener[] windowChangeListeners = (WindowChangeListener[]) this.mWindowChangeListeners.toArray(new WindowChangeListener[this.mWindowChangeListeners.size()]);
                resetPriorityAfterLockedSection();
                for (WindowChangeListener windowChangeListener : windowChangeListeners) {
                    windowChangeListener.focusChanged();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private WindowState findWindow(final int hashCode) {
        WindowState window;
        if (hashCode == -1) {
            return getFocusedWindow();
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                window = this.mRoot.getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$r4TV5nJBkjzvUCeyV6sY2bt-bEA
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return WindowManagerService.lambda$findWindow$4(hashCode, (WindowState) obj);
                    }
                });
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return window;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$findWindow$4(int hashCode, WindowState w) {
        return System.identityHashCode(w) == hashCode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendNewConfiguration(int displayId) {
        try {
            boolean configUpdated = this.mActivityManager.updateDisplayOverrideConfiguration((Configuration) null, displayId);
            if (!configUpdated) {
                synchronized (this.mWindowMap) {
                    boostPriorityForLockedSection();
                    if (this.mWaitingForConfig) {
                        this.mWaitingForConfig = false;
                        this.mLastFinishedFreezeSource = "config-unchanged";
                        DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                        if (dc != null) {
                            dc.setLayoutNeeded();
                        }
                        this.mWindowPlacerLocked.performSurfacePlacement();
                    }
                }
                resetPriorityAfterLockedSection();
            }
        } catch (RemoteException e) {
        }
    }

    public Configuration computeNewConfiguration(int displayId) {
        Configuration computeNewConfigurationLocked;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                computeNewConfigurationLocked = computeNewConfigurationLocked(displayId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return computeNewConfigurationLocked;
    }

    private Configuration computeNewConfigurationLocked(int displayId) {
        if (!this.mDisplayReady) {
            return null;
        }
        Configuration config = new Configuration();
        DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
        displayContent.computeScreenConfiguration(config);
        return config;
    }

    void notifyHardKeyboardStatusChange() {
        WindowManagerInternal.OnHardKeyboardStatusChangeListener listener;
        boolean available;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                listener = this.mHardKeyboardStatusChangeListener;
                available = this.mHardKeyboardAvailable;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (listener != null) {
            listener.onHardKeyboardStatusChange(available);
        }
    }

    public void setEventDispatching(boolean enabled) {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "setEventDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mEventDispatchingEnabled = enabled;
                if (this.mDisplayEnabled) {
                    this.mInputMonitor.setEventDispatchingLw(enabled);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private WindowState getFocusedWindow() {
        WindowState focusedWindowLocked;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                focusedWindowLocked = getFocusedWindowLocked();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return focusedWindowLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WindowState getFocusedWindowLocked() {
        return this.mCurrentFocus;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getImeFocusStackLocked() {
        if (this.mFocusedApp == null || this.mFocusedApp.getTask() == null) {
            return null;
        }
        return this.mFocusedApp.getTask().mStack;
    }

    public boolean detectSafeMode() {
        boolean z;
        if (!this.mInputMonitor.waitForInputDevicesReady(1000L)) {
            Slog.w(TAG, "Devices still not ready after waiting 1000 milliseconds before attempting to detect safe mode.");
        }
        if (Settings.Global.getInt(this.mContext.getContentResolver(), "safe_boot_disallowed", 0) != 0) {
            return false;
        }
        int menuState = this.mInputManager.getKeyCodeState(-1, -256, 82);
        int sState = this.mInputManager.getKeyCodeState(-1, -256, 47);
        int dpadState = this.mInputManager.getKeyCodeState(-1, UsbTerminalTypes.TERMINAL_IN_MIC, 23);
        int trackballState = this.mInputManager.getScanCodeState(-1, 65540, 272);
        int volumeDownState = this.mInputManager.getKeyCodeState(-1, -256, 25);
        if (menuState <= 0 && sState <= 0 && dpadState <= 0 && trackballState <= 0 && volumeDownState <= 0) {
            z = false;
        } else {
            z = true;
        }
        this.mSafeMode = z;
        try {
            if (SystemProperties.getInt(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, 0) != 0 || SystemProperties.getInt(ShutdownThread.RO_SAFEMODE_PROPERTY, 0) != 0) {
                this.mSafeMode = true;
                SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
        } catch (IllegalArgumentException e) {
        }
        if (this.mSafeMode) {
            Log.i(TAG, "SAFE MODE ENABLED (menu=" + menuState + " s=" + sState + " dpad=" + dpadState + " trackball=" + trackballState + ")");
            if (SystemProperties.getInt(ShutdownThread.RO_SAFEMODE_PROPERTY, 0) == 0) {
                SystemProperties.set(ShutdownThread.RO_SAFEMODE_PROPERTY, "1");
            }
        } else {
            Log.i(TAG, "SAFE MODE not enabled");
        }
        this.mPolicy.setSafeMode(this.mSafeMode);
        return this.mSafeMode;
    }

    public void displayReady() {
        int displayCount = this.mRoot.mChildren.size();
        for (int i = 0; i < displayCount; i++) {
            DisplayContent display = (DisplayContent) this.mRoot.mChildren.get(i);
            displayReady(display.getDisplayId());
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                if (this.mMaxUiWidth > 0) {
                    displayContent.setMaxUiWidth(this.mMaxUiWidth);
                }
                readForcedDisplayPropertiesLocked(displayContent);
                this.mDisplayReady = true;
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        try {
            this.mActivityManager.updateConfiguration((Configuration) null);
        } catch (RemoteException e) {
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mIsTouchDevice = this.mContext.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
                getDefaultDisplayContentLocked().configureDisplayPolicy();
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        try {
            this.mActivityManager.updateConfiguration((Configuration) null);
        } catch (RemoteException e2) {
        }
        updateCircularDisplayMaskIfNeeded();
    }

    private void displayReady(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    this.mAnimator.addDisplayLocked(displayId);
                    displayContent.initializeDisplayBaseInfo();
                    reconfigureDisplayLocked(displayContent);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void systemReady() {
        this.mPolicy.systemReady();
        this.mTaskSnapshotController.systemReady();
        this.mHasWideColorGamutSupport = queryWideColorGamutSupport();
    }

    private static boolean queryWideColorGamutSupport() {
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasWideColor = surfaceFlinger.hasWideColorDisplay();
            if (hasWideColor != null) {
                return hasWideColor.value;
            }
            return false;
        } catch (RemoteException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class H extends Handler {
        public static final int ALL_WINDOWS_DRAWN = 33;
        public static final int ANIMATION_FAILSAFE = 60;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int APP_TRANSITION_TIMEOUT = 13;
        public static final int BOOT_TIMEOUT = 23;
        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int DO_ANIMATION_CALLBACK = 26;
        public static final int ENABLE_SCREEN = 16;
        public static final int FORCE_GC = 15;
        public static final int NEW_ANIMATOR_SCALE = 34;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;
        public static final int NOTIFY_APP_TRANSITION_CANCELLED = 48;
        public static final int NOTIFY_APP_TRANSITION_FINISHED = 49;
        public static final int NOTIFY_APP_TRANSITION_STARTING = 47;
        public static final int NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED = 53;
        public static final int NOTIFY_KEYGUARD_FLAGS_CHANGED = 56;
        public static final int NOTIFY_KEYGUARD_TRUSTED_CHANGED = 57;
        public static final int PERSIST_ANIMATION_SCALE = 14;
        public static final int RECOMPUTE_FOCUS = 61;
        public static final int REPORT_FOCUS_CHANGE = 2;
        public static final int REPORT_HARD_KEYBOARD_STATUS_CHANGE = 22;
        public static final int REPORT_LOSING_FOCUS = 3;
        public static final int REPORT_WINDOWS_CHANGE = 19;
        public static final int RESET_ANR_MESSAGE = 38;
        public static final int RESTORE_POINTER_ICON = 55;
        public static final int SEAMLESS_ROTATION_TIMEOUT = 54;
        public static final int SEND_NEW_CONFIGURATION = 18;
        public static final int SET_HAS_OVERLAY_UI = 58;
        public static final int SET_RUNNING_REMOTE_ANIMATION = 59;
        public static final int SHOW_CIRCULAR_DISPLAY_MASK = 35;
        public static final int SHOW_EMULATOR_DISPLAY_OVERLAY = 36;
        public static final int SHOW_STRICT_MODE_VIOLATION = 25;
        public static final int UNUSED = 0;
        public static final int UPDATE_ANIMATION_SCALE = 51;
        public static final int UPDATE_DOCKED_STACK_DIVIDER = 41;
        public static final int WAITING_FOR_DRAWN_TIMEOUT = 24;
        public static final int WALLPAPER_DRAW_PENDING_TIMEOUT = 39;
        public static final int WINDOW_FREEZE_TIMEOUT = 11;
        public static final int WINDOW_HIDE_TIMEOUT = 52;
        public static final int WINDOW_REPLACEMENT_TIMEOUT = 46;

        H() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            ArrayList<WindowState> losers;
            Runnable callback;
            Runnable callback2;
            boolean bootAnimationComplete;
            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                Slog.v(WindowManagerService.TAG, "handleMessage: entry what=" + msg.what);
            }
            int i = 0;
            switch (msg.what) {
                case 2:
                    AccessibilityController accessibilityController = null;
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerService.this.mAccessibilityController != null && WindowManagerService.this.getDefaultDisplayContentLocked().getDisplayId() == 0) {
                                accessibilityController = WindowManagerService.this.mAccessibilityController;
                            }
                            WindowState lastFocus = WindowManagerService.this.mLastFocus;
                            WindowState newFocus = WindowManagerService.this.mCurrentFocus;
                            if (lastFocus == newFocus) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            }
                            WindowManagerService.this.mLastFocus = newFocus;
                            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                                Slog.i(WindowManagerService.TAG, "Focus moving from " + lastFocus + " to " + newFocus);
                            }
                            if (newFocus != null && lastFocus != null && !newFocus.isDisplayedLw()) {
                                WindowManagerService.this.mLosingFocus.add(lastFocus);
                                lastFocus = null;
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            WindowState lastFocus2 = lastFocus;
                            if (accessibilityController != null) {
                                accessibilityController.onWindowFocusChangedNotLocked();
                            }
                            if (newFocus != null) {
                                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                                    Slog.i(WindowManagerService.TAG, "Gaining focus: " + newFocus);
                                }
                                newFocus.reportFocusChangedSerialized(true, WindowManagerService.this.mInTouchMode);
                                WindowManagerService.this.notifyFocusChanged();
                            }
                            if (lastFocus2 != null) {
                                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                                    Slog.i(WindowManagerService.TAG, "Losing focus: " + lastFocus2);
                                }
                                lastFocus2.reportFocusChangedSerialized(false, WindowManagerService.this.mInTouchMode);
                                break;
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    break;
                case 3:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            losers = WindowManagerService.this.mLosingFocus;
                            WindowManagerService.this.mLosingFocus = new ArrayList<>();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    int N = losers.size();
                    for (int i2 = 0; i2 < N; i2++) {
                        if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                            Slog.i(WindowManagerService.TAG, "Losing delayed focus: " + losers.get(i2));
                        }
                        losers.get(i2).reportFocusChangedSerialized(false, WindowManagerService.this.mInTouchMode);
                    }
                    break;
                case 11:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            WindowManagerService.this.getDefaultDisplayContentLocked().onWindowFreezeTimeout();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 13:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerService.this.mAppTransition.isTransitionSet() || !WindowManagerService.this.mOpeningApps.isEmpty() || !WindowManagerService.this.mClosingApps.isEmpty()) {
                                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                                    Slog.v(WindowManagerService.TAG, "*** APP TRANSITION TIMEOUT. isTransitionSet()=" + WindowManagerService.this.mAppTransition.isTransitionSet() + " mOpeningApps.size()=" + WindowManagerService.this.mOpeningApps.size() + " mClosingApps.size()=" + WindowManagerService.this.mClosingApps.size());
                                }
                                WindowManagerService.this.mAppTransition.setTimeout();
                                WindowManagerService.this.mWindowPlacerLocked.performSurfacePlacement();
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 14:
                    Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "window_animation_scale", WindowManagerService.this.mWindowAnimationScaleSetting);
                    Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "transition_animation_scale", WindowManagerService.this.mTransitionAnimationScaleSetting);
                    Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "animator_duration_scale", WindowManagerService.this.mAnimatorDurationScaleSetting);
                    break;
                case 15:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (!WindowManagerService.this.mAnimator.isAnimating() && !WindowManagerService.this.mAnimator.isAnimationScheduled()) {
                                if (WindowManagerService.this.mDisplayFrozen) {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    return;
                                }
                                WindowManagerService.resetPriorityAfterLockedSection();
                                Runtime.getRuntime().gc();
                                break;
                            }
                            sendEmptyMessageDelayed(15, 2000L);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                case 16:
                    WindowManagerService.this.performEnableScreen();
                    break;
                case 17:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            Slog.w(WindowManagerService.TAG, "App freeze timeout expired.");
                            WindowManagerService.this.mWindowsFreezingScreen = 2;
                            for (int i3 = WindowManagerService.this.mAppFreezeListeners.size() - 1; i3 >= 0; i3--) {
                                WindowManagerService.this.mAppFreezeListeners.get(i3).onAppFreezeTimeout();
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 18:
                    removeMessages(18, msg.obj);
                    int displayId = ((Integer) msg.obj).intValue();
                    if (WindowManagerService.this.mRoot.getDisplayContent(displayId) != null) {
                        WindowManagerService.this.sendNewConfiguration(displayId);
                        break;
                    } else if (WindowManagerDebugConfig.DEBUG_CONFIGURATION) {
                        Slog.w(WindowManagerService.TAG, "Trying to send configuration to non-existing displayId=" + displayId);
                        break;
                    }
                    break;
                case REPORT_WINDOWS_CHANGE /* 19 */:
                    if (WindowManagerService.this.mWindowsChanged) {
                        synchronized (WindowManagerService.this.mWindowMap) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                WindowManagerService.this.mWindowsChanged = false;
                            } finally {
                                WindowManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        WindowManagerService.this.notifyWindowsChanged();
                        break;
                    }
                    break;
                case REPORT_HARD_KEYBOARD_STATUS_CHANGE /* 22 */:
                    WindowManagerService.this.notifyHardKeyboardStatusChange();
                    break;
                case BOOT_TIMEOUT /* 23 */:
                    WindowManagerService.this.performBootTimeout();
                    break;
                case 24:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            Slog.w(WindowManagerService.TAG, "Timeout waiting for drawn: undrawn=" + WindowManagerService.this.mWaitingForDrawn);
                            WindowManagerService.this.mWaitingForDrawn.clear();
                            callback = WindowManagerService.this.mWaitingForDrawnCallback;
                            WindowManagerService.this.mWaitingForDrawnCallback = null;
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (callback != null) {
                        callback.run();
                        break;
                    }
                    break;
                case SHOW_STRICT_MODE_VIOLATION /* 25 */:
                    WindowManagerService.this.showStrictModeViolation(msg.arg1, msg.arg2);
                    break;
                case DO_ANIMATION_CALLBACK /* 26 */:
                    try {
                        ((IRemoteCallback) msg.obj).sendResult((Bundle) null);
                        break;
                    } catch (RemoteException e) {
                        break;
                    }
                case 30:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerService.this.mClientFreezingScreen) {
                                WindowManagerService.this.mClientFreezingScreen = false;
                                WindowManagerService.this.mLastFinishedFreezeSource = "client-timeout";
                                WindowManagerService.this.stopFreezingDisplayLocked();
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 32:
                    try {
                        WindowManagerService.this.mActivityManager.notifyActivityDrawn((IBinder) msg.obj);
                        break;
                    } catch (RemoteException e2) {
                        break;
                    }
                case 33:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            callback2 = WindowManagerService.this.mWaitingForDrawnCallback;
                            WindowManagerService.this.mWaitingForDrawnCallback = null;
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (callback2 != null) {
                        callback2.run();
                        break;
                    }
                    break;
                case 34:
                    float scale = WindowManagerService.this.getCurrentAnimatorScale();
                    ValueAnimator.setDurationScale(scale);
                    Session session = (Session) msg.obj;
                    if (session != null) {
                        try {
                            session.mCallback.onAnimatorScaleChanged(scale);
                            break;
                        } catch (RemoteException e3) {
                            break;
                        }
                    } else {
                        ArrayList<IWindowSessionCallback> callbacks = new ArrayList<>();
                        synchronized (WindowManagerService.this.mWindowMap) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                for (int i4 = 0; i4 < WindowManagerService.this.mSessions.size(); i4++) {
                                    callbacks.add(WindowManagerService.this.mSessions.valueAt(i4).mCallback);
                                }
                            } finally {
                                WindowManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        while (true) {
                            int i5 = i;
                            int i6 = callbacks.size();
                            if (i5 >= i6) {
                                break;
                            } else {
                                try {
                                    callbacks.get(i5).onAnimatorScaleChanged(scale);
                                } catch (RemoteException e4) {
                                }
                                i = i5 + 1;
                            }
                        }
                    }
                case 35:
                    WindowManagerService.this.showCircularMask(msg.arg1 == 1);
                    break;
                case 36:
                    WindowManagerService.this.showEmulatorDisplayOverlay();
                    break;
                case 37:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerDebugConfig.DEBUG_BOOT) {
                                Slog.i(WindowManagerService.TAG, "CHECK_IF_BOOT_ANIMATION_FINISHED:");
                            }
                            bootAnimationComplete = WindowManagerService.this.checkBootAnimationCompleteLocked();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (bootAnimationComplete) {
                        WindowManagerService.this.performEnableScreen();
                        break;
                    }
                    break;
                case 38:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            WindowManagerService.this.mLastANRState = null;
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    WindowManagerService.this.mAmInternal.clearSavedANRState();
                    break;
                case 39:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerService.this.mRoot.mWallpaperController.processWallpaperDrawPendingTimeout()) {
                                WindowManagerService.this.mWindowPlacerLocked.performSurfacePlacement();
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 41:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            DisplayContent displayContent = WindowManagerService.this.getDefaultDisplayContentLocked();
                            displayContent.getDockedDividerController().reevaluateVisibility(false);
                            displayContent.adjustForImeIfNeeded();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case WINDOW_REPLACEMENT_TIMEOUT /* 46 */:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            for (int i7 = WindowManagerService.this.mWindowReplacementTimeouts.size() - 1; i7 >= 0; i7--) {
                                AppWindowToken token = WindowManagerService.this.mWindowReplacementTimeouts.get(i7);
                                token.onWindowReplacementTimeout();
                            }
                            WindowManagerService.this.mWindowReplacementTimeouts.clear();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 47:
                    WindowManagerService.this.mAmInternal.notifyAppTransitionStarting((SparseIntArray) msg.obj, msg.getWhen());
                    break;
                case 48:
                    WindowManagerService.this.mAmInternal.notifyAppTransitionCancelled();
                    break;
                case 49:
                    WindowManagerService.this.mAmInternal.notifyAppTransitionFinished();
                    break;
                case 51:
                    int mode = msg.arg1;
                    switch (mode) {
                        case 0:
                            WindowManagerService.this.mWindowAnimationScaleSetting = Settings.Global.getFloat(WindowManagerService.this.mContext.getContentResolver(), "window_animation_scale", WindowManagerService.this.mWindowAnimationScaleSetting);
                            break;
                        case 1:
                            WindowManagerService.this.mTransitionAnimationScaleSetting = Settings.Global.getFloat(WindowManagerService.this.mContext.getContentResolver(), "transition_animation_scale", WindowManagerService.this.mTransitionAnimationScaleSetting);
                            break;
                        case 2:
                            WindowManagerService.this.mAnimatorDurationScaleSetting = Settings.Global.getFloat(WindowManagerService.this.mContext.getContentResolver(), "animator_duration_scale", WindowManagerService.this.mAnimatorDurationScaleSetting);
                            WindowManagerService.this.dispatchNewAnimatorScaleLocked(null);
                            break;
                    }
                case 52:
                    WindowState window = (WindowState) msg.obj;
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            window.mAttrs.flags &= -129;
                            window.hidePermanentlyLw();
                            window.setDisplayLayoutNeeded();
                            WindowManagerService.this.mWindowPlacerLocked.performSurfacePlacement();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 53:
                    WindowManagerService.this.mAmInternal.notifyDockedStackMinimizedChanged(msg.arg1 == 1);
                    break;
                case 54:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            DisplayContent dc = WindowManagerService.this.getDefaultDisplayContentLocked();
                            dc.onSeamlessRotationTimeout();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 55:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            WindowManagerService.this.restorePointerIconLocked((DisplayContent) msg.obj, msg.arg1, msg.arg2);
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case 56:
                    WindowManagerService.this.mAmInternal.notifyKeyguardFlagsChanged((Runnable) msg.obj);
                    break;
                case NOTIFY_KEYGUARD_TRUSTED_CHANGED /* 57 */:
                    WindowManagerService.this.mAmInternal.notifyKeyguardTrustedChanged();
                    break;
                case SET_HAS_OVERLAY_UI /* 58 */:
                    WindowManagerService.this.mAmInternal.setHasOverlayUi(msg.arg1, msg.arg2 == 1);
                    break;
                case SET_RUNNING_REMOTE_ANIMATION /* 59 */:
                    WindowManagerService.this.mAmInternal.setRunningRemoteAnimation(msg.arg1, msg.arg2 == 1);
                    break;
                case 60:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (WindowManagerService.this.mRecentsAnimationController != null) {
                                WindowManagerService.this.mRecentsAnimationController.scheduleFailsafe();
                            }
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
                case RECOMPUTE_FOCUS /* 61 */:
                    synchronized (WindowManagerService.this.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            WindowManagerService.this.updateFocusedWindowLocked(0, true);
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    break;
            }
            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                Slog.v(WindowManagerService.TAG, "handleMessage: exit");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroyPreservedSurfaceLocked() {
        for (int i = this.mDestroyPreservedSurface.size() - 1; i >= 0; i--) {
            WindowState w = this.mDestroyPreservedSurface.get(i);
            w.mWinAnimator.destroyPreservedSurfaceLocked();
        }
        this.mDestroyPreservedSurface.clear();
    }

    public IWindowSession openSession(IWindowSessionCallback callback, IInputMethodClient client, IInputContext inputContext) {
        if (client == null) {
            throw new IllegalArgumentException("null client");
        }
        if (inputContext == null) {
            throw new IllegalArgumentException("null inputContext");
        }
        Session session = new Session(this, callback, client, inputContext);
        return session;
    }

    public boolean inputMethodClientHasFocus(IInputMethodClient client) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (getDefaultDisplayContentLocked().inputMethodClientHasFocus(client)) {
                    resetPriorityAfterLockedSection();
                    return true;
                } else if (this.mCurrentFocus == null || this.mCurrentFocus.mSession.mClient == null || this.mCurrentFocus.mSession.mClient.asBinder() != client.asBinder()) {
                    resetPriorityAfterLockedSection();
                    return false;
                } else {
                    resetPriorityAfterLockedSection();
                    return true;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void getInitialDisplaySize(int displayId, Point size) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                    size.x = displayContent.mInitialDisplayWidth;
                    size.y = displayContent.mInitialDisplayHeight;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void getBaseDisplaySize(int displayId, Point size) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                    size.x = displayContent.mBaseDisplayWidth;
                    size.y = displayContent.mBaseDisplayHeight;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setForcedDisplaySize(int displayId, int width, int height) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        if (displayId != 0) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    int width2 = Math.min(Math.max(width, 200), displayContent.mInitialDisplayWidth * 2);
                    int height2 = Math.min(Math.max(height, 200), displayContent.mInitialDisplayHeight * 2);
                    setForcedDisplaySizeLocked(displayContent, width2, height2);
                    ContentResolver contentResolver = this.mContext.getContentResolver();
                    Settings.Global.putString(contentResolver, "display_size_forced", width2 + "," + height2);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setForcedDisplayScalingMode(int displayId, int mode) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        if (displayId != 0) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    mode = (mode < 0 || mode > 1) ? 0 : 0;
                    setForcedDisplayScalingModeLocked(displayContent, mode);
                    Settings.Global.putInt(this.mContext.getContentResolver(), "display_scaling_force", mode);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setForcedDisplayScalingModeLocked(DisplayContent displayContent, int mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Using display scaling mode: ");
        sb.append(mode == 0 ? UiModeManagerService.Shell.NIGHT_MODE_STR_AUTO : "off");
        Slog.i(TAG, sb.toString());
        displayContent.mDisplayScalingDisabled = mode != 0;
        reconfigureDisplayLocked(displayContent);
    }

    private void readForcedDisplayPropertiesLocked(DisplayContent displayContent) {
        int pos;
        String sizeStr = Settings.Global.getString(this.mContext.getContentResolver(), "display_size_forced");
        if (sizeStr == null || sizeStr.length() == 0) {
            sizeStr = SystemProperties.get(SIZE_OVERRIDE, (String) null);
        }
        if (sizeStr != null && sizeStr.length() > 0 && (pos = sizeStr.indexOf(44)) > 0 && sizeStr.lastIndexOf(44) == pos) {
            try {
                int width = Integer.parseInt(sizeStr.substring(0, pos));
                int height = Integer.parseInt(sizeStr.substring(pos + 1));
                if (displayContent.mBaseDisplayWidth != width || displayContent.mBaseDisplayHeight != height) {
                    Slog.i(TAG, "FORCED DISPLAY SIZE: " + width + "x" + height);
                    displayContent.updateBaseDisplayMetrics(width, height, displayContent.mBaseDisplayDensity);
                }
            } catch (NumberFormatException e) {
            }
        }
        int density = getForcedDisplayDensityForUserLocked(this.mCurrentUserId);
        if (density != 0) {
            displayContent.mBaseDisplayDensity = density;
        }
        int mode = Settings.Global.getInt(this.mContext.getContentResolver(), "display_scaling_force", 0);
        if (mode != 0) {
            Slog.i(TAG, "FORCED DISPLAY SCALING DISABLED");
            displayContent.mDisplayScalingDisabled = true;
        }
    }

    private void setForcedDisplaySizeLocked(DisplayContent displayContent, int width, int height) {
        Slog.i(TAG, "Using new display size: " + width + "x" + height);
        displayContent.updateBaseDisplayMetrics(width, height, displayContent.mBaseDisplayDensity);
        reconfigureDisplayLocked(displayContent);
    }

    public void clearForcedDisplaySize(int displayId) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        if (displayId != 0) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    setForcedDisplaySizeLocked(displayContent, displayContent.mInitialDisplayWidth, displayContent.mInitialDisplayHeight);
                    Settings.Global.putString(this.mContext.getContentResolver(), "display_size_forced", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public int getInitialDisplayDensity(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                    int i = displayContent.mInitialDisplayDensity;
                    resetPriorityAfterLockedSection();
                    return i;
                }
                resetPriorityAfterLockedSection();
                return -1;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getBaseDisplayDensity(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                    int i = displayContent.mBaseDisplayDensity;
                    resetPriorityAfterLockedSection();
                    return i;
                }
                resetPriorityAfterLockedSection();
                return -1;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setForcedDisplayDensityForUser(int displayId, int density, int userId) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        if (displayId != 0) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        int targetUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "setForcedDisplayDensityForUser", null);
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && this.mCurrentUserId == targetUserId) {
                    setForcedDisplayDensityLocked(displayContent, density);
                }
                Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "display_density_forced", Integer.toString(density), targetUserId);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void clearForcedDisplayDensityForUser(int displayId, int userId) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        if (displayId != 0) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        int callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "clearForcedDisplayDensityForUser", null);
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && this.mCurrentUserId == callingUserId) {
                    setForcedDisplayDensityLocked(displayContent, displayContent.mInitialDisplayDensity);
                }
                Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "display_density_forced", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, callingUserId);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int getForcedDisplayDensityForUserLocked(int userId) {
        String densityStr = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "display_density_forced", userId);
        if (densityStr == null || densityStr.length() == 0) {
            densityStr = SystemProperties.get(DENSITY_OVERRIDE, (String) null);
        }
        if (densityStr != null && densityStr.length() > 0) {
            try {
                return Integer.parseInt(densityStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private void setForcedDisplayDensityLocked(DisplayContent displayContent, int density) {
        displayContent.mBaseDisplayDensity = density;
        reconfigureDisplayLocked(displayContent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reconfigureDisplayLocked(DisplayContent displayContent) {
        if (!displayContent.isReady()) {
            return;
        }
        displayContent.configureDisplayPolicy();
        displayContent.setLayoutNeeded();
        int displayId = displayContent.getDisplayId();
        boolean configChanged = updateOrientationFromAppTokensLocked(displayId);
        Configuration currentDisplayConfig = displayContent.getConfiguration();
        this.mTempConfiguration.setTo(currentDisplayConfig);
        displayContent.computeScreenConfiguration(this.mTempConfiguration);
        if (configChanged | (currentDisplayConfig.diff(this.mTempConfiguration) != 0)) {
            this.mWaitingForConfig = true;
            startFreezingDisplayLocked(0, 0, displayContent);
            this.mH.obtainMessage(18, Integer.valueOf(displayId)).sendToTarget();
        }
        this.mWindowPlacerLocked.performSurfacePlacement();
    }

    public void getDisplaysInFocusOrder(SparseIntArray displaysInFocusOrder) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.getDisplaysInFocusOrder(displaysInFocusOrder);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setOverscan(int displayId, int left, int top, int right, int bottom) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mWindowMap) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    setOverscanLocked(displayContent, left, top, right, bottom);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setOverscanLocked(DisplayContent displayContent, int left, int top, int right, int bottom) {
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        displayInfo.overscanLeft = left;
        displayInfo.overscanTop = top;
        displayInfo.overscanRight = right;
        displayInfo.overscanBottom = bottom;
        this.mDisplaySettings.setOverscanLocked(displayInfo.uniqueId, displayInfo.name, left, top, right, bottom);
        this.mDisplaySettings.writeSettingsLocked();
        reconfigureDisplayLocked(displayContent);
    }

    public void startWindowTrace() {
        try {
            this.mWindowTracing.startTrace(null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stopWindowTrace() {
        this.mWindowTracing.stopTrace(null);
    }

    public boolean isWindowTraceEnabled() {
        return this.mWindowTracing.isEnabled();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final WindowState windowForClientLocked(Session session, IWindow client, boolean throwOnError) {
        return windowForClientLocked(session, client.asBinder(), throwOnError);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final WindowState windowForClientLocked(Session session, IBinder client, boolean throwOnError) {
        WindowState win = this.mWindowMap.get(client);
        if (localLOGV) {
            Slog.v(TAG, "Looking up client " + client + ": " + win);
        }
        if (win == null) {
            if (throwOnError) {
                throw new IllegalArgumentException("Requested window " + client + " does not exist");
            }
            Slog.w(TAG, "Failed looking up window callers=" + Debug.getCallers(3));
            return null;
        } else if (session != null && win.mSession != session) {
            if (throwOnError) {
                throw new IllegalArgumentException("Requested window " + client + " is in session " + win.mSession + ", not " + session);
            }
            Slog.w(TAG, "Failed looking up window callers=" + Debug.getCallers(3));
            return null;
        } else {
            return win;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeWindowFreezingScreenIfNeededLocked(WindowState w) {
        if (!w.mToken.okToDisplay() && this.mWindowsFreezingScreen != 2) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Changing surface while display frozen: " + w);
            }
            w.setOrientationChanging(true);
            w.mLastFreezeDuration = 0;
            this.mRoot.mOrientationChangeComplete = false;
            if (this.mWindowsFreezingScreen == 0) {
                this.mWindowsFreezingScreen = 1;
                this.mH.removeMessages(11);
                this.mH.sendEmptyMessageDelayed(11, 2000L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int handleAnimatingStoppedAndTransitionLocked() {
        this.mAppTransition.setIdle();
        for (int i = this.mNoAnimationNotifyOnTransitionFinished.size() - 1; i >= 0; i--) {
            IBinder token = this.mNoAnimationNotifyOnTransitionFinished.get(i);
            this.mAppTransition.notifyAppTransitionFinishedLocked(token);
        }
        this.mNoAnimationNotifyOnTransitionFinished.clear();
        DisplayContent dc = getDefaultDisplayContentLocked();
        dc.mWallpaperController.hideDeferredWallpapersIfNeeded();
        dc.onAppTransitionDone();
        int changes = 0 | 1;
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG, "Wallpaper layer changed: assigning layers + relayout");
        }
        dc.computeImeTarget(true);
        this.mRoot.mWallpaperMayChange = true;
        this.mFocusMayChange = true;
        return changes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkDrawnWindowsLocked() {
        if (this.mWaitingForDrawn.isEmpty() || this.mWaitingForDrawnCallback == null) {
            return;
        }
        for (int j = this.mWaitingForDrawn.size() - 1; j >= 0; j--) {
            WindowState win = this.mWaitingForDrawn.get(j);
            if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || DebugOption.DEBUG_WAKE_DRAW) {
                Slog.i(TAG, "Waiting for drawn " + win + ": removed=" + win.mRemoved + " visible=" + win.isVisibleLw() + " mHasSurface=" + win.mHasSurface + " drawState=" + win.mWinAnimator.mDrawState);
            }
            if (win.mRemoved || !win.mHasSurface || !win.mPolicyVisibility) {
                if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || DebugOption.DEBUG_WAKE_DRAW) {
                    Slog.w(TAG, "Aborted waiting for drawn: " + win);
                }
                this.mWaitingForDrawn.remove(win);
            } else if (win.hasDrawnLw()) {
                if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || DebugOption.DEBUG_WAKE_DRAW) {
                    Slog.d(TAG, "Window drawn win=" + win);
                }
                this.mWaitingForDrawn.remove(win);
            }
        }
        if (this.mWaitingForDrawn.isEmpty()) {
            if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || DebugOption.DEBUG_WAKE_DRAW) {
                Slog.d(TAG, "All windows drawn!");
            }
            this.mH.removeMessages(24);
            this.mH.sendEmptyMessage(33);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHoldScreenLocked(Session newHoldScreen) {
        boolean hold = newHoldScreen != null;
        if (hold && this.mHoldingScreenOn != newHoldScreen) {
            this.mHoldingScreenWakeLock.setWorkSource(new WorkSource(newHoldScreen.mUid));
        }
        this.mHoldingScreenOn = newHoldScreen;
        boolean state = this.mHoldingScreenWakeLock.isHeld();
        if (hold != state) {
            if (hold) {
                this.mLastWakeLockHoldingWindow = this.mRoot.mHoldScreenWindow;
                this.mLastWakeLockObscuringWindow = null;
                this.mHoldingScreenWakeLock.acquire();
                this.mPolicy.keepScreenOnStartedLw();
                return;
            }
            this.mLastWakeLockHoldingWindow = null;
            this.mLastWakeLockObscuringWindow = this.mRoot.mObscuringWindow;
            this.mPolicy.keepScreenOnStoppedLw();
            this.mHoldingScreenWakeLock.release();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestTraversal() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mWindowPlacerLocked.requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAnimationLocked() {
        if (this.mAnimator != null) {
            this.mAnimator.scheduleAnimation();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        WindowState newFocus = this.mRoot.computeFocusedWindow();
        if (this.mCurrentFocus != newFocus) {
            Trace.traceBegin(32L, "wmUpdateFocus");
            this.mH.removeMessages(2);
            this.mH.sendEmptyMessage(2);
            DisplayContent displayContent = getDefaultDisplayContentLocked();
            boolean imWindowChanged = false;
            if (this.mInputMethodWindow != null) {
                WindowState prevTarget = this.mInputMethodTarget;
                WindowState newTarget = displayContent.computeImeTarget(true);
                imWindowChanged = prevTarget != newTarget;
                if (mode != 1 && mode != 3) {
                    int prevImeAnimLayer = this.mInputMethodWindow.mWinAnimator.mAnimLayer;
                    displayContent.assignWindowLayers(false);
                    imWindowChanged |= prevImeAnimLayer != this.mInputMethodWindow.mWinAnimator.mAnimLayer;
                }
            }
            if (imWindowChanged) {
                this.mWindowsChanged = true;
                displayContent.setLayoutNeeded();
                newFocus = this.mRoot.computeFocusedWindow();
            }
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT || localLOGV) {
                Slog.v(TAG, "Changing focus from " + this.mCurrentFocus + " to " + newFocus + " Callers=" + Debug.getCallers(4));
            }
            WindowState oldFocus = this.mCurrentFocus;
            this.mCurrentFocus = newFocus;
            this.mLosingFocus.remove(newFocus);
            if (this.mCurrentFocus != null) {
                this.mWinAddedSinceNullFocus.clear();
                this.mWinRemovedSinceNullFocus.clear();
            }
            int focusChanged = this.mPolicy.focusChangedLw(oldFocus, newFocus);
            if (imWindowChanged && oldFocus != this.mInputMethodWindow) {
                if (mode == 2) {
                    displayContent.performLayout(true, updateInputWindows);
                    focusChanged &= -2;
                } else if (mode == 3) {
                    displayContent.assignWindowLayers(false);
                }
            }
            if ((focusChanged & 1) != 0) {
                displayContent.setLayoutNeeded();
                if (mode == 2) {
                    displayContent.performLayout(true, updateInputWindows);
                }
            }
            if (mode != 1) {
                this.mInputMonitor.setInputFocusLw(this.mCurrentFocus, updateInputWindows);
            }
            displayContent.adjustForImeIfNeeded();
            displayContent.scheduleToastWindowsTimeoutIfNeededLocked(oldFocus, newFocus);
            Trace.traceEnd(32L);
            return true;
        }
        return false;
    }

    void startFreezingDisplayLocked(int exitAnim, int enterAnim) {
        startFreezingDisplayLocked(exitAnim, enterAnim, getDefaultDisplayContentLocked());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startFreezingDisplayLocked(int exitAnim, int enterAnim, DisplayContent displayContent) {
        if (this.mDisplayFrozen || this.mRotatingSeamlessly || !displayContent.isReady() || !this.mPolicy.isScreenOn() || !displayContent.okToAnimate()) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.d(TAG, "startFreezingDisplayLocked: exitAnim=" + exitAnim + " enterAnim=" + enterAnim + " called by " + Debug.getCallers(8));
        }
        this.mScreenFrozenLock.acquire();
        this.mDisplayFrozen = true;
        this.mDisplayFreezeTime = SystemClock.elapsedRealtime();
        this.mLastFinishedFreezeSource = null;
        this.mFrozenDisplayId = displayContent.getDisplayId();
        this.mInputMonitor.freezeInputDispatchingLw();
        this.mPolicy.setLastInputMethodWindowLw(null, null);
        if (this.mAppTransition.isTransitionSet()) {
            this.mAppTransition.freeze();
        }
        this.mLatencyTracker.onActionStart(6);
        if (displayContent.isDefaultDisplay) {
            this.mExitAnimId = exitAnim;
            this.mEnterAnimId = enterAnim;
            ScreenRotationAnimation screenRotationAnimation = this.mAnimator.getScreenRotationAnimationLocked(this.mFrozenDisplayId);
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
            }
            boolean isSecure = displayContent.hasSecureWindowOnScreen();
            displayContent.updateDisplayInfo();
            this.mAnimator.setScreenRotationAnimationLocked(this.mFrozenDisplayId, new ScreenRotationAnimation(this.mContext, displayContent, this.mPolicy.isDefaultOrientationForced(), isSecure, this));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopFreezingDisplayLocked() {
        if (!this.mDisplayFrozen) {
            return;
        }
        if (this.mWaitingForConfig || this.mAppsFreezingScreen > 0 || this.mWindowsFreezingScreen == 1 || this.mClientFreezingScreen || !this.mOpeningApps.isEmpty()) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.d(TAG, "stopFreezingDisplayLocked: Returning mWaitingForConfig=" + this.mWaitingForConfig + ", mAppsFreezingScreen=" + this.mAppsFreezingScreen + ", mWindowsFreezingScreen=" + this.mWindowsFreezingScreen + ", mClientFreezingScreen=" + this.mClientFreezingScreen + ", mOpeningApps.size()=" + this.mOpeningApps.size());
                return;
            }
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.d(TAG, "stopFreezingDisplayLocked: Unfreezing now");
        }
        DisplayContent displayContent = this.mRoot.getDisplayContent(this.mFrozenDisplayId);
        int displayId = this.mFrozenDisplayId;
        this.mFrozenDisplayId = -1;
        this.mDisplayFrozen = false;
        this.mInputMonitor.thawInputDispatchingLw();
        this.mLastDisplayFreezeDuration = (int) (SystemClock.elapsedRealtime() - this.mDisplayFreezeTime);
        StringBuilder sb = new StringBuilder(128);
        sb.append("Screen frozen for ");
        TimeUtils.formatDuration(this.mLastDisplayFreezeDuration, sb);
        if (this.mLastFinishedFreezeSource != null) {
            sb.append(" due to ");
            sb.append(this.mLastFinishedFreezeSource);
        }
        Slog.i(TAG, sb.toString());
        this.mH.removeMessages(17);
        this.mH.removeMessages(30);
        boolean updateRotation = false;
        ScreenRotationAnimation screenRotationAnimation = this.mAnimator.getScreenRotationAnimationLocked(displayId);
        if (screenRotationAnimation != null && screenRotationAnimation.hasScreenshot()) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.i(TAG, "**** Dismissing screen rotation animation");
            }
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            if (!this.mPolicy.validateRotationAnimationLw(this.mExitAnimId, this.mEnterAnimId, false)) {
                this.mEnterAnimId = 0;
                this.mExitAnimId = 0;
            }
            if (screenRotationAnimation.dismiss(this.mTransaction, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, getTransitionAnimationScaleLocked(), displayInfo.logicalWidth, displayInfo.logicalHeight, this.mExitAnimId, this.mEnterAnimId)) {
                this.mTransaction.apply();
                scheduleAnimationLocked();
            } else {
                screenRotationAnimation.kill();
                this.mAnimator.setScreenRotationAnimationLocked(displayId, null);
                updateRotation = true;
            }
        } else {
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
                this.mAnimator.setScreenRotationAnimationLocked(displayId, null);
            }
            updateRotation = true;
        }
        boolean configChanged = updateOrientationFromAppTokensLocked(displayId);
        this.mH.removeMessages(15);
        this.mH.sendEmptyMessageDelayed(15, 2000L);
        this.mScreenFrozenLock.release();
        if (updateRotation) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.d(TAG, "Performing post-rotate rotation");
            }
            configChanged |= displayContent.updateRotationUnchecked();
        }
        if (configChanged) {
            this.mH.obtainMessage(18, Integer.valueOf(displayId)).sendToTarget();
        }
        this.mLatencyTracker.onActionEnd(6);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int getPropertyInt(String[] tokens, int index, int defUnits, int defDps, DisplayMetrics dm) {
        String str;
        if (index < tokens.length && (str = tokens[index]) != null && str.length() > 0) {
            try {
                int val = Integer.parseInt(str);
                return val;
            } catch (Exception e) {
            }
        }
        if (defUnits == 0) {
            return defDps;
        }
        int val2 = (int) TypedValue.applyDimension(defUnits, defDps, dm);
        return val2;
    }

    void createWatermarkInTransaction() {
        String[] toks;
        if (this.mWatermark != null) {
            return;
        }
        File file = new File("/system/etc/setup.conf");
        FileInputStream in = null;
        DataInputStream ind = null;
        try {
            try {
                try {
                    in = new FileInputStream(file);
                    ind = new DataInputStream(in);
                    String line = ind.readLine();
                    if (line != null && (toks = line.split("%")) != null && toks.length > 0) {
                        DisplayContent displayContent = getDefaultDisplayContentLocked();
                        this.mWatermark = new Watermark(displayContent, displayContent.mRealDisplayMetrics, toks);
                    }
                    ind.close();
                } catch (IOException e) {
                }
            } catch (FileNotFoundException e2) {
                if (ind != null) {
                    ind.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (IOException e3) {
                if (ind != null) {
                    ind.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (Throwable th) {
                if (ind != null) {
                    try {
                        ind.close();
                    } catch (IOException e4) {
                    }
                } else if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e5) {
                    }
                }
                throw th;
            }
        } catch (IOException e6) {
        }
    }

    public void setRecentsVisibility(boolean visible) {
        this.mAmInternal.enforceCallerIsRecentsOrHasPermission("android.permission.STATUS_BAR", "setRecentsVisibility()");
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.setRecentsVisibilityLw(visible);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setPipVisibility(boolean visible) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.STATUS_BAR") != 0) {
            throw new SecurityException("Caller does not hold permission android.permission.STATUS_BAR");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.setPipVisibilityLw(visible);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setShelfHeight(boolean visible, int shelfHeight) {
        this.mAmInternal.enforceCallerIsRecentsOrHasPermission("android.permission.STATUS_BAR", "setShelfHeight()");
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getDefaultDisplayContentLocked().getPinnedStackController().setAdjustedForShelf(visible, shelfHeight);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void statusBarVisibilityChanged(int visibility) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.STATUS_BAR") != 0) {
            throw new SecurityException("Caller does not hold permission android.permission.STATUS_BAR");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mLastStatusBarVisibility = visibility;
                updateStatusBarVisibilityLocked(this.mPolicy.adjustSystemUiVisibilityLw(visibility));
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setNavBarVirtualKeyHapticFeedbackEnabled(boolean enabled) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.STATUS_BAR") != 0) {
            throw new SecurityException("Caller does not hold permission android.permission.STATUS_BAR");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.setNavBarVirtualKeyHapticFeedbackEnabledLw(enabled);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private boolean updateStatusBarVisibilityLocked(int visibility) {
        if (this.mLastDispatchedSystemUiVisibility == visibility) {
            return false;
        }
        int globalDiff = (this.mLastDispatchedSystemUiVisibility ^ visibility) & 7 & (~visibility);
        this.mLastDispatchedSystemUiVisibility = visibility;
        this.mInputManager.setSystemUiVisibility(visibility);
        getDefaultDisplayContentLocked().updateSystemUiVisibility(visibility, globalDiff);
        return true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void reevaluateStatusBarVisibility() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                int visibility = this.mPolicy.adjustSystemUiVisibilityLw(this.mLastStatusBarVisibility);
                if (updateStatusBarVisibilityLocked(visibility)) {
                    this.mWindowPlacerLocked.requestTraversal();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getNavBarPosition() {
        int navBarPosition;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
                defaultDisplayContent.performLayout(false, false);
                navBarPosition = this.mPolicy.getNavBarPosition();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return navBarPosition;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory) {
        WindowManagerPolicy.InputConsumer createInputConsumer;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                createInputConsumer = this.mInputMonitor.createInputConsumer(looper, name, inputEventReceiverFactory);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return createInputConsumer;
    }

    public void createInputConsumer(IBinder token, String name, InputChannel inputChannel) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mInputMonitor.createInputConsumer(token, name, inputChannel, Binder.getCallingPid(), Binder.getCallingUserHandle());
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean destroyInputConsumer(String name) {
        boolean destroyInputConsumer;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                destroyInputConsumer = this.mInputMonitor.destroyInputConsumer(name);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return destroyInputConsumer;
    }

    public Region getCurrentImeTouchRegion() {
        Region r;
        if (this.mContext.checkCallingOrSelfPermission("android.permission.RESTRICTED_VR_ACCESS") != 0) {
            throw new SecurityException("getCurrentImeTouchRegion is restricted to VR services");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                r = new Region();
                if (this.mInputMethodWindow != null) {
                    this.mInputMethodWindow.getTouchableRegion(r);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return r;
    }

    public boolean hasNavigationBar() {
        return this.mPolicy.hasNavigationBar();
    }

    public void lockNow(Bundle options) {
        this.mPolicy.lockNow(options);
    }

    public void showRecentApps() {
        this.mPolicy.showRecentApps();
    }

    public boolean isSafeModeEnabled() {
        return this.mSafeMode;
    }

    public boolean clearWindowContentFrameStats(IBinder token) {
        if (!checkCallingPermission("android.permission.FRAME_STATS", "clearWindowContentFrameStats()")) {
            throw new SecurityException("Requires FRAME_STATS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState windowState = this.mWindowMap.get(token);
                if (windowState == null) {
                    resetPriorityAfterLockedSection();
                    return false;
                }
                WindowSurfaceController surfaceController = windowState.mWinAnimator.mSurfaceController;
                if (surfaceController == null) {
                    resetPriorityAfterLockedSection();
                    return false;
                }
                boolean clearWindowContentFrameStats = surfaceController.clearWindowContentFrameStats();
                resetPriorityAfterLockedSection();
                return clearWindowContentFrameStats;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public WindowContentFrameStats getWindowContentFrameStats(IBinder token) {
        if (!checkCallingPermission("android.permission.FRAME_STATS", "getWindowContentFrameStats()")) {
            throw new SecurityException("Requires FRAME_STATS permission");
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState windowState = this.mWindowMap.get(token);
                if (windowState == null) {
                    resetPriorityAfterLockedSection();
                    return null;
                }
                WindowSurfaceController surfaceController = windowState.mWinAnimator.mSurfaceController;
                if (surfaceController == null) {
                    resetPriorityAfterLockedSection();
                    return null;
                }
                if (this.mTempWindowRenderStats == null) {
                    this.mTempWindowRenderStats = new WindowContentFrameStats();
                }
                WindowContentFrameStats stats = this.mTempWindowRenderStats;
                if (surfaceController.getWindowContentFrameStats(stats)) {
                    resetPriorityAfterLockedSection();
                    return stats;
                }
                resetPriorityAfterLockedSection();
                return null;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void notifyAppRelaunching(IBinder token) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindow = this.mRoot.getAppWindowToken(token);
                if (appWindow != null) {
                    appWindow.startRelaunching();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void notifyAppRelaunchingFinished(IBinder token) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindow = this.mRoot.getAppWindowToken(token);
                if (appWindow != null) {
                    appWindow.finishRelaunching();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void notifyAppRelaunchesCleared(IBinder token) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindow = this.mRoot.getAppWindowToken(token);
                if (appWindow != null) {
                    appWindow.clearRelaunching();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void notifyAppResumedFinished(IBinder token) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindow = this.mRoot.getAppWindowToken(token);
                if (appWindow != null) {
                    this.mUnknownAppVisibilityController.notifyAppResumedFinished(appWindow);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isAnyWindowVisibleForUid(final int callingUid) {
        boolean forAllWindows;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                forAllWindows = this.mRoot.forAllWindows(new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$WW-eTe74GlEZ-In74G0o1AzCjEo
                    public final boolean apply(Object obj) {
                        return WindowManagerService.lambda$isAnyWindowVisibleForUid$5(callingUid, (WindowState) obj);
                    }
                }, true);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return forAllWindows;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$isAnyWindowVisibleForUid$5(int callingUid, WindowState w) {
        return w.getOwningUid() == callingUid && w.isVisible();
    }

    public void notifyTaskRemovedFromRecents(int taskId, int userId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mTaskSnapshotController.notifyTaskRemovedFromRecents(taskId, userId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public int getDockedDividerInsetsLw() {
        return getDefaultDisplayContentLocked().getDockedDividerController().getContentInsets();
    }

    private void dumpPolicyLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER POLICY STATE (dumpsys window policy)");
        this.mPolicy.dump("    ", pw, args);
    }

    private void dumpAnimatorLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)");
        this.mAnimator.dumpLocked(pw, "    ", dumpAll);
    }

    private void dumpTokensLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER TOKENS (dumpsys window tokens)");
        this.mRoot.dumpTokens(pw, dumpAll);
        if (!this.mOpeningApps.isEmpty() || !this.mClosingApps.isEmpty()) {
            pw.println();
            if (this.mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps=");
                pw.println(this.mOpeningApps);
            }
            if (this.mClosingApps.size() > 0) {
                pw.print("  mClosingApps=");
                pw.println(this.mClosingApps);
            }
        }
    }

    private void dumpSessionsLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER SESSIONS (dumpsys window sessions)");
        for (int i = 0; i < this.mSessions.size(); i++) {
            Session s = this.mSessions.valueAt(i);
            pw.print("  Session ");
            pw.print(s);
            pw.println(':');
            s.dump(pw, "    ");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProtoLocked(ProtoOutputStream proto, boolean trim) {
        this.mPolicy.writeToProto(proto, 1146756268033L);
        this.mRoot.writeToProto(proto, 1146756268034L, trim);
        if (this.mCurrentFocus != null) {
            this.mCurrentFocus.writeIdentifierToProto(proto, 1146756268035L);
        }
        if (this.mFocusedApp != null) {
            this.mFocusedApp.writeNameToProto(proto, 1138166333444L);
        }
        if (this.mInputMethodWindow != null) {
            this.mInputMethodWindow.writeIdentifierToProto(proto, 1146756268037L);
        }
        proto.write(1133871366150L, this.mDisplayFrozen);
        DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
        proto.write(1120986464263L, defaultDisplayContent.getRotation());
        proto.write(1120986464264L, defaultDisplayContent.getLastOrientation());
        this.mAppTransition.writeToProto(proto, 1146756268041L);
    }

    void traceStateLocked(String where) {
        Trace.traceBegin(32L, "traceStateLocked");
        try {
            try {
                this.mWindowTracing.traceStateLocked(where, this);
            } catch (Exception e) {
                Log.wtf(TAG, "Exception while tracing state", e);
            }
        } finally {
            Trace.traceEnd(32L);
        }
    }

    private void dumpWindowsLocked(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
        dumpWindowsNoHeaderLocked(pw, dumpAll, windows);
    }

    private void dumpWindowsNoHeaderLocked(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        this.mRoot.dumpWindowsNoHeader(pw, dumpAll, windows);
        if (!this.mHidingNonSystemOverlayWindows.isEmpty()) {
            pw.println();
            pw.println("  Hiding System Alert Windows:");
            for (int i = this.mHidingNonSystemOverlayWindows.size() - 1; i >= 0; i--) {
                WindowState w = this.mHidingNonSystemOverlayWindows.get(i);
                pw.print("  #");
                pw.print(i);
                pw.print(' ');
                pw.print(w);
                if (dumpAll) {
                    pw.println(":");
                    w.dump(pw, "    ", true);
                } else {
                    pw.println();
                }
            }
        }
        if (this.mPendingRemove.size() > 0) {
            pw.println();
            pw.println("  Remove pending for:");
            for (int i2 = this.mPendingRemove.size() - 1; i2 >= 0; i2--) {
                WindowState w2 = this.mPendingRemove.get(i2);
                if (windows == null || windows.contains(w2)) {
                    pw.print("  Remove #");
                    pw.print(i2);
                    pw.print(' ');
                    pw.print(w2);
                    if (dumpAll) {
                        pw.println(":");
                        w2.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (this.mForceRemoves != null && this.mForceRemoves.size() > 0) {
            pw.println();
            pw.println("  Windows force removing:");
            for (int i3 = this.mForceRemoves.size() - 1; i3 >= 0; i3--) {
                WindowState w3 = this.mForceRemoves.get(i3);
                pw.print("  Removing #");
                pw.print(i3);
                pw.print(' ');
                pw.print(w3);
                if (dumpAll) {
                    pw.println(":");
                    w3.dump(pw, "    ", true);
                } else {
                    pw.println();
                }
            }
        }
        if (this.mDestroySurface.size() > 0) {
            pw.println();
            pw.println("  Windows waiting to destroy their surface:");
            for (int i4 = this.mDestroySurface.size() - 1; i4 >= 0; i4--) {
                WindowState w4 = this.mDestroySurface.get(i4);
                if (windows == null || windows.contains(w4)) {
                    pw.print("  Destroy #");
                    pw.print(i4);
                    pw.print(' ');
                    pw.print(w4);
                    if (dumpAll) {
                        pw.println(":");
                        w4.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (this.mLosingFocus.size() > 0) {
            pw.println();
            pw.println("  Windows losing focus:");
            for (int i5 = this.mLosingFocus.size() - 1; i5 >= 0; i5--) {
                WindowState w5 = this.mLosingFocus.get(i5);
                if (windows == null || windows.contains(w5)) {
                    pw.print("  Losing #");
                    pw.print(i5);
                    pw.print(' ');
                    pw.print(w5);
                    if (dumpAll) {
                        pw.println(":");
                        w5.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (this.mResizingWindows.size() > 0) {
            pw.println();
            pw.println("  Windows waiting to resize:");
            for (int i6 = this.mResizingWindows.size() - 1; i6 >= 0; i6--) {
                WindowState w6 = this.mResizingWindows.get(i6);
                if (windows == null || windows.contains(w6)) {
                    pw.print("  Resizing #");
                    pw.print(i6);
                    pw.print(' ');
                    pw.print(w6);
                    if (dumpAll) {
                        pw.println(":");
                        w6.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (this.mWaitingForDrawn.size() > 0) {
            pw.println();
            pw.println("  Clients waiting for these windows to be drawn:");
            for (int i7 = this.mWaitingForDrawn.size() - 1; i7 >= 0; i7--) {
                WindowState win = this.mWaitingForDrawn.get(i7);
                pw.print("  Waiting #");
                pw.print(i7);
                pw.print(' ');
                pw.print(win);
            }
        }
        pw.println();
        pw.print("  mGlobalConfiguration=");
        pw.println(this.mRoot.getConfiguration());
        pw.print("  mHasPermanentDpad=");
        pw.println(this.mHasPermanentDpad);
        pw.print("  mCurrentFocus=");
        pw.println(this.mCurrentFocus);
        if (this.mLastFocus != this.mCurrentFocus) {
            pw.print("  mLastFocus=");
            pw.println(this.mLastFocus);
        }
        pw.print("  mFocusedApp=");
        pw.println(this.mFocusedApp);
        if (this.mInputMethodTarget != null) {
            pw.print("  mInputMethodTarget=");
            pw.println(this.mInputMethodTarget);
        }
        pw.print("  mInTouchMode=");
        pw.println(this.mInTouchMode);
        pw.print("  mLastDisplayFreezeDuration=");
        TimeUtils.formatDuration(this.mLastDisplayFreezeDuration, pw);
        if (this.mLastFinishedFreezeSource != null) {
            pw.print(" due to ");
            pw.print(this.mLastFinishedFreezeSource);
        }
        pw.println();
        pw.print("  mLastWakeLockHoldingWindow=");
        pw.print(this.mLastWakeLockHoldingWindow);
        pw.print(" mLastWakeLockObscuringWindow=");
        pw.print(this.mLastWakeLockObscuringWindow);
        pw.println();
        this.mInputMonitor.dump(pw, "  ");
        this.mUnknownAppVisibilityController.dump(pw, "  ");
        this.mTaskSnapshotController.dump(pw, "  ");
        if (dumpAll) {
            pw.print("  mSystemDecorLayer=");
            pw.print(this.mSystemDecorLayer);
            pw.print(" mScreenRect=");
            pw.println(this.mScreenRect.toShortString());
            if (this.mLastStatusBarVisibility != 0) {
                pw.print("  mLastStatusBarVisibility=0x");
                pw.println(Integer.toHexString(this.mLastStatusBarVisibility));
            }
            if (this.mInputMethodWindow != null) {
                pw.print("  mInputMethodWindow=");
                pw.println(this.mInputMethodWindow);
            }
            this.mWindowPlacerLocked.dump(pw, "  ");
            this.mRoot.mWallpaperController.dump(pw, "  ");
            pw.print("  mSystemBooted=");
            pw.print(this.mSystemBooted);
            pw.print(" mDisplayEnabled=");
            pw.println(this.mDisplayEnabled);
            this.mRoot.dumpLayoutNeededDisplayIds(pw);
            pw.print("  mTransactionSequence=");
            pw.println(this.mTransactionSequence);
            pw.print("  mDisplayFrozen=");
            pw.print(this.mDisplayFrozen);
            pw.print(" windows=");
            pw.print(this.mWindowsFreezingScreen);
            pw.print(" client=");
            pw.print(this.mClientFreezingScreen);
            pw.print(" apps=");
            pw.print(this.mAppsFreezingScreen);
            pw.print(" waitingForConfig=");
            pw.println(this.mWaitingForConfig);
            DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
            pw.print("  mRotation=");
            pw.print(defaultDisplayContent.getRotation());
            pw.print(" mAltOrientation=");
            pw.println(defaultDisplayContent.getAltOrientation());
            pw.print("  mLastWindowForcedOrientation=");
            pw.print(defaultDisplayContent.getLastWindowForcedOrientation());
            pw.print(" mLastOrientation=");
            pw.println(defaultDisplayContent.getLastOrientation());
            pw.print("  mDeferredRotationPauseCount=");
            pw.println(this.mDeferredRotationPauseCount);
            pw.print("  Animation settings: disabled=");
            pw.print(this.mAnimationsDisabled);
            pw.print(" window=");
            pw.print(this.mWindowAnimationScaleSetting);
            pw.print(" transition=");
            pw.print(this.mTransitionAnimationScaleSetting);
            pw.print(" animator=");
            pw.println(this.mAnimatorDurationScaleSetting);
            pw.print("  mSkipAppTransitionAnimation=");
            pw.println(this.mSkipAppTransitionAnimation);
            pw.println("  mLayoutToAnim:");
            this.mAppTransition.dump(pw, "    ");
            if (this.mRecentsAnimationController != null) {
                pw.print("  mRecentsAnimationController=");
                pw.println(this.mRecentsAnimationController);
                this.mRecentsAnimationController.dump(pw, "    ");
            }
        }
    }

    private boolean dumpWindows(PrintWriter pw, String name, String[] args, int opti, boolean dumpAll) {
        final ArrayList<WindowState> windows = new ArrayList<>();
        if ("apps".equals(name) || "visible".equals(name) || "visible-apps".equals(name)) {
            final boolean appsOnly = name.contains("apps");
            final boolean visibleOnly = name.contains("visible");
            synchronized (this.mWindowMap) {
                try {
                    boostPriorityForLockedSection();
                    if (appsOnly) {
                        this.mRoot.dumpDisplayContents(pw);
                    }
                    this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$BFlKCXakaED5j-9cdbfdK5Lfsfw
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            WindowManagerService.lambda$dumpWindows$6(visibleOnly, appsOnly, windows, (WindowState) obj);
                        }
                    }, true);
                } finally {
                    resetPriorityAfterLockedSection();
                }
            }
            resetPriorityAfterLockedSection();
        } else {
            synchronized (this.mWindowMap) {
                try {
                    boostPriorityForLockedSection();
                    this.mRoot.getWindowsByName(windows, name);
                } finally {
                }
            }
            resetPriorityAfterLockedSection();
        }
        if (windows.size() <= 0) {
            return false;
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                dumpWindowsLocked(pw, dumpAll, windows);
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dumpWindows$6(boolean visibleOnly, boolean appsOnly, ArrayList windows, WindowState w) {
        if (!visibleOnly || w.mWinAnimator.getShown()) {
            if (!appsOnly || w.mAppToken != null) {
                windows.add(w);
            }
        }
    }

    private void dumpLastANRLocked(PrintWriter pw) {
        pw.println("WINDOW MANAGER LAST ANR (dumpsys window lastanr)");
        if (this.mLastANRState == null) {
            pw.println("  <no ANR has occurred since boot>");
        } else {
            pw.println(this.mLastANRState);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveANRStateLocked(AppWindowToken appWindowToken, WindowState windowState, String reason) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new FastPrintWriter(sw, false, 1024);
        pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
        if (appWindowToken != null) {
            pw.println("  Application at fault: " + appWindowToken.stringName);
        }
        if (windowState != null) {
            pw.println("  Window at fault: " + ((Object) windowState.mAttrs.getTitle()));
        }
        if (reason != null) {
            pw.println("  Reason: " + reason);
        }
        if (!this.mWinAddedSinceNullFocus.isEmpty()) {
            pw.println("  Windows added since null focus: " + this.mWinAddedSinceNullFocus);
        }
        if (!this.mWinRemovedSinceNullFocus.isEmpty()) {
            pw.println("  Windows removed since null focus: " + this.mWinRemovedSinceNullFocus);
        }
        pw.println();
        dumpWindowsNoHeaderLocked(pw, true, null);
        pw.println();
        pw.println("Last ANR continued");
        this.mRoot.dumpDisplayContents(pw);
        pw.close();
        this.mLastANRState = sw.toString();
        this.mH.removeMessages(38);
        this.mH.sendEmptyMessageDelayed(38, AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PriorityDump.dump(this.mPriorityDumper, fd, pw, args);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x00b9, code lost:
        r2 = new android.util.proto.ProtoOutputStream(r11);
        r3 = r10.mWindowMap;
     */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x00c0, code lost:
        monitor-enter(r3);
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x00c1, code lost:
        boostPriorityForLockedSection();
        writeToProtoLocked(r2, false);
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00c7, code lost:
        monitor-exit(r3);
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00c8, code lost:
        resetPriorityAfterLockedSection();
        r2.flush();
     */
    /* JADX WARN: Code restructure failed: missing block: B:30:0x00ce, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x00cf, code lost:
        r1 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x00d4, code lost:
        throw r1;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void doDump(java.io.FileDescriptor r11, final java.io.PrintWriter r12, java.lang.String[] r13, boolean r14) {
        /*
            Method dump skipped, instructions count: 739
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.doDump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[], boolean):void");
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mWindowMap) {
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
    public DisplayContent getDefaultDisplayContentLocked() {
        return this.mRoot.getDisplayContent(0);
    }

    public void onDisplayAdded(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                Display display = this.mDisplayManager.getDisplay(displayId);
                if (display != null) {
                    displayReady(displayId);
                }
                this.mWindowPlacerLocked.requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void onDisplayRemoved(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mAnimator.removeDisplayLocked(displayId);
                this.mWindowPlacerLocked.requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void onOverlayChanged() {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.onOverlayChangedLw();
                getDefaultDisplayContentLocked().updateDisplayInfo();
                requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void onDisplayChanged(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.updateDisplayInfo();
                }
                this.mWindowPlacerLocked.requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public Object getWindowManagerLock() {
        return this.mWindowMap;
    }

    public void setWillReplaceWindow(IBinder token, boolean animate) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindowToken = this.mRoot.getAppWindowToken(token);
                if (appWindowToken == null) {
                    Slog.w(TAG, "Attempted to set replacing window on non-existing app token " + token);
                    resetPriorityAfterLockedSection();
                } else if (!appWindowToken.hasContentToDisplay()) {
                    Slog.w(TAG, "Attempted to set replacing window on app token with no content" + token);
                    resetPriorityAfterLockedSection();
                } else {
                    appWindowToken.setWillReplaceWindows(animate);
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillReplaceWindows(IBinder token, boolean childrenOnly) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindowToken = this.mRoot.getAppWindowToken(token);
                if (appWindowToken == null) {
                    Slog.w(TAG, "Attempted to set replacing window on non-existing app token " + token);
                    resetPriorityAfterLockedSection();
                } else if (!appWindowToken.hasContentToDisplay()) {
                    Slog.w(TAG, "Attempted to set replacing window on app token with no content" + token);
                    resetPriorityAfterLockedSection();
                } else {
                    if (childrenOnly) {
                        appWindowToken.setWillReplaceChildWindows();
                    } else {
                        appWindowToken.setWillReplaceWindows(false);
                    }
                    scheduleClearWillReplaceWindows(token, true);
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void scheduleClearWillReplaceWindows(IBinder token, boolean replacing) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindowToken = this.mRoot.getAppWindowToken(token);
                if (appWindowToken == null) {
                    Slog.w(TAG, "Attempted to reset replacing window on non-existing app token " + token);
                    resetPriorityAfterLockedSection();
                    return;
                }
                if (replacing) {
                    scheduleWindowReplacementTimeouts(appWindowToken);
                } else {
                    appWindowToken.clearWillReplaceWindows();
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleWindowReplacementTimeouts(AppWindowToken appWindowToken) {
        if (!this.mWindowReplacementTimeouts.contains(appWindowToken)) {
            this.mWindowReplacementTimeouts.add(appWindowToken);
        }
        this.mH.removeMessages(46);
        this.mH.sendEmptyMessageDelayed(46, 2000L);
    }

    public int getDockedStackSide() {
        int dockSide;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                TaskStack dockedStack = getDefaultDisplayContentLocked().getSplitScreenPrimaryStackIgnoringVisibility();
                dockSide = dockedStack == null ? -1 : dockedStack.getDockSide();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return dockSide;
    }

    public void setDockedStackResizing(boolean resizing) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getDefaultDisplayContentLocked().getDockedDividerController().setResizing(resizing);
                requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setDockedStackDividerTouchRegion(Rect touchRegion) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getDefaultDisplayContentLocked().getDockedDividerController().setTouchRegion(touchRegion);
                setFocusTaskRegionLocked(null);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getDefaultDisplayContentLocked().getDockedDividerController().setResizeDimLayer(visible, targetWindowingMode, alpha);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setForceResizableTasks(boolean forceResizableTasks) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mForceResizableTasks = forceResizableTasks;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setSupportsPictureInPicture(boolean supportsPictureInPicture) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mSupportsPictureInPicture = supportsPictureInPicture;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int dipToPixel(int dip, DisplayMetrics displayMetrics) {
        return (int) TypedValue.applyDimension(1, dip, displayMetrics);
    }

    public void registerDockedStackListener(IDockedStackListener listener) {
        if (!checkCallingPermission("android.permission.REGISTER_WINDOW_MANAGER_LISTENERS", "registerDockedStackListener()")) {
            return;
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getDefaultDisplayContentLocked().mDividerControllerLocked.registerDockedStackListener(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void registerPinnedStackListener(int displayId, IPinnedStackListener listener) {
        if (!checkCallingPermission("android.permission.REGISTER_WINDOW_MANAGER_LISTENERS", "registerPinnedStackListener()") || !this.mSupportsPictureInPicture) {
            return;
        }
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                displayContent.getPinnedStackController().registerPinnedStackListener(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        try {
            WindowState focusedWindow = getFocusedWindow();
            if (focusedWindow != null && focusedWindow.mClient != null) {
                getFocusedWindow().mClient.requestAppKeyboardShortcuts(receiver, deviceId);
            }
        } catch (RemoteException e) {
        }
    }

    public void getStableInsets(int displayId, Rect outInsets) throws RemoteException {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                getStableInsetsLocked(displayId, outInsets);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStableInsetsLocked(int displayId, Rect outInsets) {
        outInsets.setEmpty();
        DisplayContent dc = this.mRoot.getDisplayContent(displayId);
        if (dc != null) {
            DisplayInfo di = dc.getDisplayInfo();
            this.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, di.displayCutout, outInsets);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void intersectDisplayInsetBounds(Rect display, Rect insets, Rect inOutBounds) {
        this.mTmpRect3.set(display);
        this.mTmpRect3.inset(insets);
        inOutBounds.intersect(this.mTmpRect3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class MousePositionTracker implements WindowManagerPolicyConstants.PointerEventListener {
        private boolean mLatestEventWasMouse;
        private float mLatestMouseX;
        private float mLatestMouseY;

        private MousePositionTracker() {
        }

        void updatePosition(float x, float y) {
            synchronized (this) {
                this.mLatestEventWasMouse = true;
                this.mLatestMouseX = x;
                this.mLatestMouseY = y;
            }
        }

        public void onPointerEvent(MotionEvent motionEvent) {
            if (motionEvent.isFromSource(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1)) {
                updatePosition(motionEvent.getRawX(), motionEvent.getRawY());
                return;
            }
            synchronized (this) {
                this.mLatestEventWasMouse = false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePointerIcon(IWindow client) {
        synchronized (this.mMousePositionTracker) {
            if (this.mMousePositionTracker.mLatestEventWasMouse) {
                float mouseX = this.mMousePositionTracker.mLatestMouseX;
                float mouseY = this.mMousePositionTracker.mLatestMouseY;
                synchronized (this.mWindowMap) {
                    try {
                        boostPriorityForLockedSection();
                        if (this.mDragDropController.dragDropActiveLocked()) {
                            resetPriorityAfterLockedSection();
                            return;
                        }
                        WindowState callingWin = windowForClientLocked((Session) null, client, false);
                        if (callingWin == null) {
                            Slog.w(TAG, "Bad requesting window " + client);
                            resetPriorityAfterLockedSection();
                            return;
                        }
                        DisplayContent displayContent = callingWin.getDisplayContent();
                        if (displayContent == null) {
                            resetPriorityAfterLockedSection();
                            return;
                        }
                        WindowState windowUnderPointer = displayContent.getTouchableWinAtPointLocked(mouseX, mouseY);
                        if (windowUnderPointer != callingWin) {
                            resetPriorityAfterLockedSection();
                            return;
                        }
                        try {
                            windowUnderPointer.mClient.updatePointerIcon(windowUnderPointer.translateToWindowX(mouseX), windowUnderPointer.translateToWindowY(mouseY));
                        } catch (RemoteException e) {
                            Slog.w(TAG, "unable to update pointer icon");
                        }
                        resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void restorePointerIconLocked(DisplayContent displayContent, float latestX, float latestY) {
        this.mMousePositionTracker.updatePosition(latestX, latestY);
        WindowState windowUnderPointer = displayContent.getTouchableWinAtPointLocked(latestX, latestY);
        if (windowUnderPointer != null) {
            try {
                windowUnderPointer.mClient.updatePointerIcon(windowUnderPointer.translateToWindowX(latestX), windowUnderPointer.translateToWindowY(latestY));
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "unable to restore pointer icon");
                return;
            }
        }
        InputManager.getInstance().setPointerIconType(1000);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTapExcludeRegion(IWindow client, int regionId, int left, int top, int width, int height) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                WindowState callingWin = windowForClientLocked((Session) null, client, false);
                if (callingWin == null) {
                    Slog.w(TAG, "Bad requesting window " + client);
                    resetPriorityAfterLockedSection();
                    return;
                }
                callingWin.updateTapExcludeRegion(regionId, left, top, width, height);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void dontOverrideDisplayInfo(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = getDisplayContentOrCreate(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Trying to register a non existent display.");
                }
                dc.mShouldOverrideDisplayConfiguration = false;
                this.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, (DisplayInfo) null);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver) throws RemoteException {
        if (!checkCallingPermission("android.permission.REGISTER_WINDOW_MANAGER_LISTENERS", "registerShortcutKey")) {
            throw new SecurityException("Requires REGISTER_WINDOW_MANAGER_LISTENERS permission");
        }
        this.mPolicy.registerShortcutKey(shortcutCode, shortcutKeyReceiver);
    }

    public void requestUserActivityNotification() {
        if (!checkCallingPermission("android.permission.USER_ACTIVITY", "requestUserActivityNotification()")) {
            throw new SecurityException("Requires USER_ACTIVITY permission");
        }
        this.mPolicy.requestUserActivityNotification();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void markForSeamlessRotation(WindowState w, boolean seamlesslyRotated) {
        if (seamlesslyRotated == w.mSeamlesslyRotated) {
            return;
        }
        w.mSeamlesslyRotated = seamlesslyRotated;
        if (seamlesslyRotated) {
            this.mSeamlessRotationCount++;
        } else {
            this.mSeamlessRotationCount--;
        }
        if (this.mSeamlessRotationCount == 0) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.i(TAG, "Performing post-rotate rotation after seamless rotation");
            }
            finishSeamlessRotation();
            DisplayContent displayContent = w.getDisplayContent();
            if (displayContent.updateRotationUnchecked()) {
                this.mH.obtainMessage(18, Integer.valueOf(displayContent.getDisplayId())).sendToTarget();
            }
        }
    }

    /* loaded from: classes.dex */
    private final class LocalService extends WindowManagerInternal {
        private LocalService() {
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void requestTraversalFromDisplayManager() {
            WindowManagerService.this.requestTraversal();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setMagnificationSpec(MagnificationSpec spec) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.setMagnificationSpecLocked(spec);
                    } else {
                        throw new IllegalStateException("Magnification callbacks not set!");
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            if (Binder.getCallingPid() != Process.myPid()) {
                spec.recycle();
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setForceShowMagnifiableBounds(boolean show) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.setForceShowMagnifiableBoundsLocked(show);
                    } else {
                        throw new IllegalStateException("Magnification callbacks not set!");
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void getMagnificationRegion(Region magnificationRegion) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.getMagnificationRegionLocked(magnificationRegion);
                    } else {
                        throw new IllegalStateException("Magnification callbacks not set!");
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public MagnificationSpec getCompatibleMagnificationSpecForWindow(IBinder windowToken) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState windowState = WindowManagerService.this.mWindowMap.get(windowToken);
                    if (windowState == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    }
                    MagnificationSpec spec = null;
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        spec = WindowManagerService.this.mAccessibilityController.getMagnificationSpecForWindowLocked(windowState);
                    }
                    if ((spec == null || spec.isNop()) && windowState.mGlobalScale == 1.0f) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    }
                    MagnificationSpec spec2 = spec == null ? MagnificationSpec.obtain() : MagnificationSpec.obtain(spec);
                    spec2.scale *= windowState.mGlobalScale;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return spec2;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setMagnificationCallbacks(WindowManagerInternal.MagnificationCallbacks callbacks) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController == null) {
                        WindowManagerService.this.mAccessibilityController = new AccessibilityController(WindowManagerService.this);
                    }
                    WindowManagerService.this.mAccessibilityController.setMagnificationCallbacksLocked(callbacks);
                    if (!WindowManagerService.this.mAccessibilityController.hasCallbacksLocked()) {
                        WindowManagerService.this.mAccessibilityController = null;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setWindowsForAccessibilityCallback(WindowManagerInternal.WindowsForAccessibilityCallback callback) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController == null) {
                        WindowManagerService.this.mAccessibilityController = new AccessibilityController(WindowManagerService.this);
                    }
                    WindowManagerService.this.mAccessibilityController.setWindowsForAccessibilityCallback(callback);
                    if (!WindowManagerService.this.mAccessibilityController.hasCallbacksLocked()) {
                        WindowManagerService.this.mAccessibilityController = null;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setInputFilter(IInputFilter filter) {
            WindowManagerService.this.mInputManager.setInputFilter(filter);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public IBinder getFocusedWindowToken() {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState windowState = WindowManagerService.this.getFocusedWindowLocked();
                    if (windowState != null) {
                        IBinder asBinder = windowState.mClient.asBinder();
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return asBinder;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return null;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isKeyguardLocked() {
            return WindowManagerService.this.isKeyguardLocked();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isKeyguardShowingAndNotOccluded() {
            return WindowManagerService.this.isKeyguardShowingAndNotOccluded();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void showGlobalActions() {
            WindowManagerService.this.showGlobalActions();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void getWindowFrame(IBinder token, Rect outBounds) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState windowState = WindowManagerService.this.mWindowMap.get(token);
                    if (windowState != null) {
                        outBounds.set(windowState.mFrame);
                    } else {
                        outBounds.setEmpty();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void waitForAllWindowsDrawn(Runnable callback, long timeout) {
            boolean allWindowsDrawn = false;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mWaitingForDrawnCallback = callback;
                    WindowManagerService.this.getDefaultDisplayContentLocked().waitForAllWindowsDrawn();
                    if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || DebugOption.DEBUG_WAKE_DRAW) {
                        Slog.i(WindowManagerService.TAG, "waitForAllWindowsDrawn method finish");
                    }
                    WindowManagerService.this.mWindowPlacerLocked.requestTraversal();
                    WindowManagerService.this.mH.removeMessages(24);
                    if (WindowManagerService.this.mWaitingForDrawn.isEmpty()) {
                        allWindowsDrawn = true;
                    } else {
                        WindowManagerService.this.mH.sendEmptyMessageDelayed(24, timeout);
                        WindowManagerService.this.checkDrawnWindowsLocked();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            if (allWindowsDrawn) {
                callback.run();
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void addWindowToken(IBinder token, int type, int displayId) {
            WindowManagerService.this.addWindowToken(token, type, displayId);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void removeWindowToken(IBinder binder, boolean removeWindows, int displayId) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (removeWindows) {
                        DisplayContent dc = WindowManagerService.this.mRoot.getDisplayContent(displayId);
                        if (dc == null) {
                            Slog.w(WindowManagerService.TAG, "removeWindowToken: Attempted to remove token: " + binder + " for non-exiting displayId=" + displayId);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        WindowToken token = dc.removeWindowToken(binder);
                        if (token == null) {
                            Slog.w(WindowManagerService.TAG, "removeWindowToken: Attempted to remove non-existing token: " + binder);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        token.removeAllWindowsIfPossible();
                    }
                    WindowManagerService.this.removeWindowToken(binder, displayId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void registerAppTransitionListener(WindowManagerInternal.AppTransitionListener listener) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mAppTransition.registerListenerLocked(listener);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public int getInputMethodWindowVisibleHeight() {
            int inputMethodWindowVisibleHeight;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    DisplayContent dc = WindowManagerService.this.getDefaultDisplayContentLocked();
                    inputMethodWindowVisibleHeight = dc.mDisplayFrames.getInputMethodWindowVisibleHeight();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return inputMethodWindowVisibleHeight;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void saveLastInputMethodWindowForTransition() {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mInputMethodWindow != null) {
                        WindowManagerService.this.mPolicy.setLastInputMethodWindowLw(WindowManagerService.this.mInputMethodWindow, WindowManagerService.this.mInputMethodTarget);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void clearLastInputMethodWindowForTransition() {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mPolicy.setLastInputMethodWindowLw(null, null);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void updateInputMethodWindowStatus(IBinder imeToken, boolean imeWindowVisible, boolean dismissImeOnBackKeyPressed, IBinder targetWindowToken) {
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                Slog.w(WindowManagerService.TAG, "updateInputMethodWindowStatus: imeToken=" + imeToken + " dismissImeOnBackKeyPressed=" + dismissImeOnBackKeyPressed + " imeWindowVisible=" + imeWindowVisible + " targetWindowToken=" + targetWindowToken);
            }
            WindowManagerService.this.mPolicy.setDismissImeOnBackKeyPressed(dismissImeOnBackKeyPressed);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isHardKeyboardAvailable() {
            boolean z;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    z = WindowManagerService.this.mHardKeyboardAvailable;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setOnHardKeyboardStatusChangeListener(WindowManagerInternal.OnHardKeyboardStatusChangeListener listener) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mHardKeyboardStatusChangeListener = listener;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isStackVisible(int windowingMode) {
            boolean isStackVisible;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    DisplayContent dc = WindowManagerService.this.getDefaultDisplayContentLocked();
                    isStackVisible = dc.isStackVisible(windowingMode);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return isStackVisible;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isDockedDividerResizing() {
            boolean isResizing;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    isResizing = WindowManagerService.this.getDefaultDisplayContentLocked().getDockedDividerController().isResizing();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return isResizing;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void computeWindowsForAccessibility() {
            AccessibilityController accessibilityController;
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    accessibilityController = WindowManagerService.this.mAccessibilityController;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            if (accessibilityController != null) {
                accessibilityController.performComputeChangedWindowsNotLocked();
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setVr2dDisplayId(int vr2dDisplayId) {
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.d(WindowManagerService.TAG, "setVr2dDisplayId called for: " + vr2dDisplayId);
            }
            synchronized (WindowManagerService.this) {
                WindowManagerService.this.mVr2dDisplayId = vr2dDisplayId;
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void registerDragDropControllerCallback(WindowManagerInternal.IDragDropCallback callback) {
            WindowManagerService.this.mDragDropController.registerCallback(callback);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void lockNow() {
            WindowManagerService.this.lockNow(null);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public int getWindowOwnerUserId(IBinder token) {
            synchronized (WindowManagerService.this.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState window = WindowManagerService.this.mWindowMap.get(token);
                    if (window != null) {
                        int userId = UserHandle.getUserId(window.mOwnerUid);
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return userId;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return -10000;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerAppFreezeListener(AppFreezeListener listener) {
        if (!this.mAppFreezeListeners.contains(listener)) {
            this.mAppFreezeListeners.add(listener);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterAppFreezeListener(AppFreezeListener listener) {
        this.mAppFreezeListeners.remove(listener);
    }

    public void inSurfaceTransaction(Runnable exec) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                SurfaceControl.openTransaction();
                exec.run();
                SurfaceControl.closeTransaction();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void disableNonVrUi(boolean disable) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                boolean showAlertWindowNotifications = !disable;
                if (showAlertWindowNotifications == this.mShowAlertWindowNotifications) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                this.mShowAlertWindowNotifications = showAlertWindowNotifications;
                for (int i = this.mSessions.size() - 1; i >= 0; i--) {
                    Session s = this.mSessions.valueAt(i);
                    s.setShowingAlertWindowNotificationAllowed(this.mShowAlertWindowNotifications);
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasWideColorGamutSupport() {
        return this.mHasWideColorGamutSupport && SystemProperties.getInt("persist.sys.sf.native_mode", 0) != 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateNonSystemOverlayWindowsVisibilityIfNeeded(WindowState win, boolean surfaceShown) {
        if (!win.hideNonSystemOverlayWindowsWhenVisible() && !this.mHidingNonSystemOverlayWindows.contains(win)) {
            return;
        }
        boolean systemAlertWindowsHidden = !this.mHidingNonSystemOverlayWindows.isEmpty();
        if (surfaceShown) {
            if (!this.mHidingNonSystemOverlayWindows.contains(win)) {
                this.mHidingNonSystemOverlayWindows.add(win);
            }
        } else {
            this.mHidingNonSystemOverlayWindows.remove(win);
        }
        final boolean hideSystemAlertWindows = !this.mHidingNonSystemOverlayWindows.isEmpty();
        if (systemAlertWindowsHidden == hideSystemAlertWindows) {
            return;
        }
        this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$Im11Aa6VC8N6keu5Oze1AI0XzoQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).setForceHideNonSystemOverlayWindowIfNeeded(hideSystemAlertWindows);
            }
        }, false);
    }

    public void applyMagnificationSpec(MagnificationSpec spec) {
        getDefaultDisplayContentLocked().applyMagnificationSpec(spec);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeSurfaceBuilder(SurfaceSession s) {
        return this.mSurfaceBuilderFactory.make(s);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendSetRunningRemoteAnimation(int pid, boolean runningRemoteAnimation) {
        this.mH.obtainMessage(59, pid, runningRemoteAnimation ? 1 : 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSeamlessRotation() {
        this.mSeamlessRotationCount = 0;
        this.mRotatingSeamlessly = true;
    }

    void finishSeamlessRotation() {
        this.mRotatingSeamlessly = false;
    }

    public void onLockTaskStateChanged(int lockTaskState) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                this.mPolicy.onLockTaskStateChangedLw(lockTaskState);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setAodShowing(boolean aodShowing) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                if (this.mPolicy.setAodShowing(aodShowing)) {
                    this.mWindowPlacerLocked.performSurfacePlacement();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public Configuration getXuiConfiguration(String packageName) {
        xpPackageInfo xpi = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
        if (xpi != null) {
            try {
                DisplayContent dc = getDisplayContentOrCreate(0);
                Configuration config = dc.getConfiguration();
                return xpWindowManagerService.get(this.mContext).getXuiConfiguration(packageName, config);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public WindowManager.LayoutParams getXuiLayoutParams(WindowManager.LayoutParams attrs) {
        return xpWindowManagerService.get(this.mContext).getXuiLayoutParams(attrs);
    }

    public boolean isImeLayerExist() {
        return xpWindowManagerService.get(this.mContext).isImeLayerExist();
    }

    public Rect getXuiRectByType(int type) {
        return xpWindowManagerService.get(this.mContext).getXuiRectByType(type);
    }

    public int getXuiStyle() {
        return xpWindowManagerService.get(this.mContext).getXuiStyle();
    }

    public int getImmPosition() {
        return xpWindowManagerService.get(this.mContext).getImmPosition();
    }

    public int getXuiLayer(int type) {
        return xpWindowManagerService.get(this.mContext).getXuiLayer(type);
    }

    public int getXuiSubLayer(int type) {
        return xpWindowManagerService.get(this.mContext).getXuiSubLayer(type);
    }

    public int[] getXuiRoundCorner(int type) {
        return xpWindowManagerService.get(this.mContext).getXuiRoundCorner(type);
    }

    public void setFocusedAppNoChecked(IBinder windowToken, boolean moveFocusNow) {
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    WindowState win = this.mWindowMap.get(windowToken);
                    if (win != null) {
                        this.mActivityManager.setFocusedTask(win.getTask().mTaskId);
                    }
                    Binder.restoreCallingIdentity(origId);
                } catch (Exception e) {
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getAppTaskId(IBinder token) {
        WindowState win;
        synchronized (this.mWindowMap) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    win = this.mWindowMap.get(token);
                } catch (Exception e) {
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                if (win != null && win.getTask() != null) {
                    int i = win.getTask().mTaskId;
                    resetPriorityAfterLockedSection();
                    return i;
                }
                Binder.restoreCallingIdentity(origId);
                resetPriorityAfterLockedSection();
                return -1;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public Rect getRealDisplayRect() {
        return xpWindowManagerService.get(this.mContext).getRealDisplayRect();
    }

    public Bitmap screenshot(int screenId, Bundle extras) {
        long ident = Binder.clearCallingIdentity();
        try {
            return xpWindowManagerService.screenshot(screenId, extras);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public String getTopWindow() {
        return xpActivityManagerService.getTopWindow();
    }
}
