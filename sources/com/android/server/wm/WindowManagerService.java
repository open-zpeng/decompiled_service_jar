package com.android.server.wm;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
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
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.configstore.V1_0.ISurfaceFlingerConfigs;
import android.hardware.configstore.V1_0.OptionalBool;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerExecutor;
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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDisplayFoldListener;
import android.view.IDockedStackListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedStackListener;
import android.view.IRecentsAnimationRunner;
import android.view.IRotationWatcher;
import android.view.ISystemGestureExclusionListener;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.RemoteAnimationAdapter;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.pm.DumpState;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.ShutdownThread;
import com.android.server.usage.AppStandbyController;
import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.RecentsAnimationController;
import com.android.server.wm.SharedDisplayContainer;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowState;
import com.xiaopeng.app.xpDialogInfo;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.server.wm.WindowFrameController;
import com.xiaopeng.server.wm.xpWindowManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.view.ISharedDisplayListener;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.WindowFrameModel;
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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

@SuppressLint({"all"})
/* loaded from: classes2.dex */
public class WindowManagerService extends IWindowManager.Stub implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    private static final boolean ALWAYS_KEEP_CURRENT = true;
    private static final int ANIMATION_COMPLETED_TIMEOUT_MS = 5000;
    private static final int ANIMATION_DURATION_SCALE = 2;
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";
    static final long DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS = 5000000000L;
    private static final String DENSITY_OVERRIDE = "ro.config.density_override";
    private static final int INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS = 1000;
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 7200000;
    static final int LAYER_OFFSET_DIM = 1;
    static final int LAYER_OFFSET_THUMBNAIL = 4;
    static final int LAYOUT_REPEAT_THRESHOLD = 4;
    static final int MAX_ANIMATION_DURATION = 10000;
    private static final int MAX_SCREENSHOT_RETRIES = 3;
    private static final int MIN_GESTURE_EXCLUSION_LIMIT_DP = 200;
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
    static final int UPDATE_FOCUS_REMOVING_FOCUS = 4;
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;
    static final int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;
    static final int WINDOWS_FREEZING_SCREENS_NONE = 0;
    static final int WINDOWS_FREEZING_SCREENS_TIMEOUT = 2;
    private static final int WINDOW_ANIMATION_SCALE = 0;
    static final int WINDOW_FREEZE_TIMEOUT_DURATION = 2000;
    static final int WINDOW_LAYER_MULTIPLIER = 5;
    static final int WINDOW_REPLACEMENT_TIMEOUT_DURATION = 2000;
    private static final boolean is3DUI;
    private static WindowManagerService sInstance;
    static WindowManagerThreadPriorityBooster sThreadPriorityBooster;
    AccessibilityController mAccessibilityController;
    final IActivityManager mActivityManager;
    final IActivityTaskManager mActivityTaskManager;
    final boolean mAllowAnimationsInLowPowerMode;
    final boolean mAllowBootMessages;
    boolean mAllowTheaterModeWakeFromLayout;
    final ActivityManagerInternal mAmInternal;
    private boolean mAnimationsDisabled;
    final WindowAnimator mAnimator;
    final AppOpsManager mAppOps;
    final ActivityTaskManagerInternal mAtmInternal;
    final ActivityTaskManagerService mAtmService;
    CircularDisplayMask mCircularDisplayMask;
    final Context mContext;
    int mCurrentUserId;
    boolean mDisableTransitionAnimation;
    final DisplayManager mDisplayManager;
    final DisplayManagerInternal mDisplayManagerInternal;
    boolean mDisplayReady;
    final DisplayWindowSettings mDisplayWindowSettings;
    Rect mDockedStackCreateBounds;
    final DragDropController mDragDropController;
    final long mDrawLockTimeoutMillis;
    EmulatorDisplayOverlay mEmulatorDisplayOverlay;
    private int mEnterAnimId;
    private boolean mEventDispatchingEnabled;
    private int mExitAnimId;
    boolean mFocusMayChange;
    boolean mForceDesktopModeOnExternalDisplays;
    boolean mForceResizableTasks;
    private int mFrozenDisplayId;
    final WindowManagerGlobalLock mGlobalLock;
    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;
    private boolean mHasHdrSupport;
    final boolean mHasPermanentDpad;
    private boolean mHasWideColorGamutSupport;
    final HighRefreshRateBlacklist mHighRefreshRateBlacklist;
    private Session mHoldingScreenOn;
    private PowerManager.WakeLock mHoldingScreenWakeLock;
    boolean mInTouchMode;
    final InputManagerService mInputManager;
    boolean mIsPc;
    boolean mIsTouchDevice;
    private final KeyguardDisableHandler mKeyguardDisableHandler;
    boolean mKeyguardGoingAway;
    boolean mKeyguardOrAodShowingOnDefaultDisplay;
    String mLastANRState;
    private final LatencyTracker mLatencyTracker;
    final boolean mLimitedAlphaCompositing;
    final boolean mLowRamTaskSnapshotsAndRecents;
    final int mMaxUiWidth;
    final boolean mOnlyCore;
    @VisibleForTesting
    boolean mPerDisplayFocusEnabled;
    final PackageManagerInternal mPmInternal;
    @VisibleForTesting
    WindowManagerPolicy mPolicy;
    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;
    private RecentsAnimationController mRecentsAnimationController;
    RootWindowContainer mRoot;
    boolean mSafeMode;
    private final PowerManager.WakeLock mScreenFrozenLock;
    SettingsObserver mSettingsObserver;
    SharedDisplayContainer mSharedDisplayContainer;
    StrictModeFlash mStrictModeFlash;
    boolean mSupportsFreeformWindowManagement;
    boolean mSupportsPictureInPicture;
    final SurfaceAnimationRunner mSurfaceAnimationRunner;
    boolean mSystemGestureExcludedByPreQStickyImmersive;
    int mSystemGestureExclusionLimitDp;
    public long mSystemGestureExclusionLogDebounceTimeoutMillis;
    final TaskPositioningController mTaskPositioningController;
    final TaskSnapshotController mTaskSnapshotController;
    private WindowContentFrameStats mTempWindowRenderStats;
    private final SurfaceControl.Transaction mTransaction;
    TransactionFactory mTransactionFactory;
    int mTransactionSequence;
    private float mTransitionAnimationScaleSetting;
    private ViewServer mViewServer;
    Runnable mWaitingForDrawnCallback;
    Watermark mWatermark;
    private float mWindowAnimationScaleSetting;
    final WindowSurfacePlacer mWindowPlacerLocked;
    final WindowTracing mWindowTracing;
    static final boolean localLOGV = WindowManagerDebugConfig.DEBUG;
    static final boolean CUSTOM_SCREEN_ROTATION = FeatureOption.FO_SCREEN_ROTATION_ENABLED;
    private static final int delayTime = SystemProperties.getInt("persist.sys.anima.delay", (int) PhoneWindowManager.TOAST_WINDOW_TIMEOUT);
    int mVr2dDisplayId = -1;
    boolean mVrModeEnabled = false;
    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() { // from class: com.android.server.wm.WindowManagerService.1
        public void onVrStateChanged(boolean enabled) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mVrModeEnabled = enabled;
                    WindowManagerService.this.mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$h9zRxk6xP2dliCTsIiNVg_lH9kA
                        @Override // java.util.function.BiConsumer
                        public final void accept(Object obj, Object obj2) {
                            ((DisplayPolicy) obj).onVrStateChangedLw(((Boolean) obj2).booleanValue());
                        }
                    }, PooledLambda.__(), Boolean.valueOf(enabled)));
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.wm.WindowManagerService.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (((action.hashCode() == 988075300 && action.equals("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED")) ? (char) 0 : (char) 65535) == 0) {
                WindowManagerService.this.mKeyguardDisableHandler.updateKeyguardEnabled(getSendingUserId());
            }
        }
    };
    private final PriorityDump.PriorityDumper mPriorityDumper = new AnonymousClass3();
    int[] mCurrentProfileIds = new int[0];
    boolean mShowAlertWindowNotifications = true;
    final ArraySet<Session> mSessions = new ArraySet<>();
    final WindowHashMap mWindowMap = new WindowHashMap();
    final ArrayList<AppWindowToken> mWindowReplacementTimeouts = new ArrayList<>();
    final ArrayList<WindowState> mResizingWindows = new ArrayList<>();
    final ArrayList<WindowState> mPendingRemove = new ArrayList<>();
    WindowState[] mPendingRemoveTmp = new WindowState[20];
    final SparseArray<Configuration> mProcessConfigurations = new SparseArray<>();
    final ArrayList<WindowState> mDestroySurface = new ArrayList<>();
    final ArrayList<WindowState> mDestroyPreservedSurface = new ArrayList<>();
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
    boolean mSystemReady = false;
    WindowState mLastWakeLockHoldingWindow = null;
    WindowState mLastWakeLockObscuringWindow = null;
    int mDockedStackCreateMode = 0;
    ArrayList<RotationWatcher> mRotationWatchers = new ArrayList<>();
    final WallpaperVisibilityListeners mWallpaperVisibilityListeners = new WallpaperVisibilityListeners();
    boolean mDisplayFrozen = false;
    long mDisplayFreezeTime = 0;
    int mLastDisplayFreezeDuration = 0;
    Object mLastFinishedFreezeSource = null;
    boolean mSwitchingUser = false;
    int mWindowsFreezingScreen = 0;
    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;
    final H mH = new H();
    final Handler mAnimationHandler = new Handler(AnimationThread.getHandler().getLooper());
    private int mSeamlessRotationCount = 0;
    private boolean mRotatingSeamlessly = false;
    private float mAnimatorDurationScaleSetting = 1.0f;
    boolean mPointerLocationEnabled = false;
    final ArrayMap<AnimationAdapter, SurfaceAnimator> mAnimationTransferMap = new ArrayMap<>();
    final ArrayList<WindowChangeListener> mWindowChangeListeners = new ArrayList<>();
    boolean mWindowsChanged = false;
    final Configuration mTempConfiguration = new Configuration();
    SurfaceBuilderFactory mSurfaceBuilderFactory = new SurfaceBuilderFactory() { // from class: com.android.server.wm.-$$Lambda$XZ-U3HlCFtHp_gydNmNMeRmQMCI
        @Override // com.android.server.wm.SurfaceBuilderFactory
        public final SurfaceControl.Builder make(SurfaceSession surfaceSession) {
            return new SurfaceControl.Builder(surfaceSession);
        }
    };
    SurfaceFactory mSurfaceFactory = new SurfaceFactory() { // from class: com.android.server.wm.-$$Lambda$6DEhn1zqxqV5_Ytb_NyzMW23Ano
        @Override // com.android.server.wm.SurfaceFactory
        public final Surface make() {
            return new Surface();
        }
    };
    final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier = new WindowManagerInternal.AppTransitionListener() { // from class: com.android.server.wm.WindowManagerService.4
        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionCancelledLocked(int transit) {
            WindowManagerService.this.mAtmInternal.notifyAppTransitionCancelled();
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionFinishedLocked(IBinder token) {
            WindowManagerService.this.mAtmInternal.notifyAppTransitionFinished();
            AppWindowToken atoken = WindowManagerService.this.mRoot.getAppWindowToken(token);
            if (atoken == null) {
                return;
            }
            if (atoken.mLaunchTaskBehind) {
                try {
                    WindowManagerService.this.mActivityTaskManager.notifyLaunchTaskBehindComplete(atoken.token);
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
                    WindowManagerService.this.mActivityTaskManager.notifyEnterAnimationComplete(atoken.token);
                } catch (RemoteException e2) {
                }
            }
        }
    };
    final ArrayList<AppFreezeListener> mAppFreezeListeners = new ArrayList<>();
    final InputManagerCallback mInputManagerCallback = new InputManagerCallback(this);
    MousePositionTracker mMousePositionTracker = new MousePositionTracker();

    /* loaded from: classes2.dex */
    interface AppFreezeListener {
        void onAppFreezeTimeout();
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    private @interface UpdateAnimationScaleMode {
    }

    /* loaded from: classes2.dex */
    public interface WindowChangeListener {
        void focusChanged();

        void windowsChanged();
    }

    static {
        is3DUI = FeatureOption.FO_PROJECT_UI_TYPE == 2;
        sThreadPriorityBooster = new WindowManagerThreadPriorityBooster();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.wm.WindowManagerService$3  reason: invalid class name */
    /* loaded from: classes2.dex */
    public class AnonymousClass3 implements PriorityDump.PriorityDumper {
        AnonymousClass3() {
        }

        @Override // com.android.server.utils.PriorityDump.PriorityDumper
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            if (asProto && WindowManagerService.this.mWindowTracing.isEnabled()) {
                WindowManagerService.this.mWindowTracing.stopTrace(null, false);
                BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$3$FRNc42I1SE4lD0XFYgIp8RCUXng
                    @Override // java.lang.Runnable
                    public final void run() {
                        WindowManagerService.AnonymousClass3.this.lambda$dumpCritical$0$WindowManagerService$3();
                    }
                });
            }
            WindowManagerService.this.doDump(fd, pw, new String[]{"-a"}, asProto);
        }

        public /* synthetic */ void lambda$dumpCritical$0$WindowManagerService$3() {
            WindowManagerService.this.mWindowTracing.writeTraceToFile();
            WindowManagerService.this.mWindowTracing.startTrace(null);
        }

        @Override // com.android.server.utils.PriorityDump.PriorityDumper
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            WindowManagerService.this.doDump(fd, pw, args, asProto);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDragLayerLocked() {
        return (this.mPolicy.getWindowLayerFromTypeLw(2016) * 10000) + 1000;
    }

    /* loaded from: classes2.dex */
    class RotationWatcher {
        final IBinder.DeathRecipient mDeathRecipient;
        final int mDisplayId;
        final IRotationWatcher mWatcher;

        RotationWatcher(IRotationWatcher watcher, IBinder.DeathRecipient deathRecipient, int displayId) {
            this.mWatcher = watcher;
            this.mDeathRecipient = deathRecipient;
            this.mDisplayId = displayId;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class SettingsObserver extends ContentObserver {
        private final Uri mAnimationDurationScaleUri;
        private final Uri mDisplayInversionEnabledUri;
        private final Uri mImmersiveModeConfirmationsUri;
        private final Uri mPointerLocationUri;
        private final Uri mPolicyControlUri;
        private final Uri mTransitionAnimationScaleUri;
        private final Uri mWindowAnimationScaleUri;

        public SettingsObserver() {
            super(new Handler());
            this.mDisplayInversionEnabledUri = Settings.Secure.getUriFor("accessibility_display_inversion_enabled");
            this.mWindowAnimationScaleUri = Settings.Global.getUriFor("window_animation_scale");
            this.mTransitionAnimationScaleUri = Settings.Global.getUriFor("transition_animation_scale");
            this.mAnimationDurationScaleUri = Settings.Global.getUriFor("animator_duration_scale");
            this.mImmersiveModeConfirmationsUri = Settings.Secure.getUriFor("immersive_mode_confirmations");
            this.mPolicyControlUri = Settings.Global.getUriFor("policy_control");
            this.mPointerLocationUri = Settings.System.getUriFor("pointer_location");
            ContentResolver resolver = WindowManagerService.this.mContext.getContentResolver();
            resolver.registerContentObserver(this.mDisplayInversionEnabledUri, false, this, -1);
            resolver.registerContentObserver(this.mWindowAnimationScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mTransitionAnimationScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mAnimationDurationScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mImmersiveModeConfirmationsUri, false, this, -1);
            resolver.registerContentObserver(this.mPolicyControlUri, false, this, -1);
            resolver.registerContentObserver(this.mPointerLocationUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            int mode;
            if (uri == null) {
                return;
            }
            if (this.mImmersiveModeConfirmationsUri.equals(uri) || this.mPolicyControlUri.equals(uri)) {
                updateSystemUiSettings();
            } else if (this.mDisplayInversionEnabledUri.equals(uri)) {
                WindowManagerService.this.updateCircularDisplayMaskIfNeeded();
            } else if (this.mPointerLocationUri.equals(uri)) {
                updatePointerLocation();
            } else {
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
        public void updateSystemUiSettings() {
            boolean changed;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    changed = ImmersiveModeConfirmation.loadSetting(WindowManagerService.this.mCurrentUserId, WindowManagerService.this.mContext) || PolicyControl.reloadFromSetting(WindowManagerService.this.mContext);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            if (changed) {
                WindowManagerService.this.updateRotation(false, false);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void updatePointerLocation() {
            ContentResolver resolver = WindowManagerService.this.mContext.getContentResolver();
            boolean enablePointerLocation = Settings.System.getIntForUser(resolver, "pointer_location", 0, -2) != 0;
            if (WindowManagerService.this.mPointerLocationEnabled == enablePointerLocation) {
                return;
            }
            WindowManagerService windowManagerService = WindowManagerService.this;
            windowManagerService.mPointerLocationEnabled = enablePointerLocation;
            synchronized (windowManagerService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$1z_bkwouqOBIC89HKBNNqb1FoaY
                        @Override // java.util.function.BiConsumer
                        public final void accept(Object obj, Object obj2) {
                            ((DisplayPolicy) obj).setPointerLocationEnabled(((Boolean) obj2).booleanValue());
                        }
                    }, PooledLambda.__(), Boolean.valueOf(WindowManagerService.this.mPointerLocationEnabled)));
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
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
            synchronized (this.mGlobalLock) {
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
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                SurfaceControl.closeTransaction();
                this.mWindowTracing.logState(where);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Trace.traceEnd(32L);
        }
    }

    static WindowManagerService getInstance() {
        return sInstance;
    }

    public static WindowManagerService main(Context context, InputManagerService im, boolean showBootMsgs, boolean onlyCore, WindowManagerPolicy policy, ActivityTaskManagerService atm) {
        return main(context, im, showBootMsgs, onlyCore, policy, atm, $$Lambda$hBnABSAsqXWvQ0zKwHWE4BZ3Mc0.INSTANCE);
    }

    @VisibleForTesting
    public static WindowManagerService main(final Context context, final InputManagerService im, final boolean showBootMsgs, final boolean onlyCore, final WindowManagerPolicy policy, final ActivityTaskManagerService atm, final TransactionFactory transactionFactory) {
        DisplayThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$wGh8jzmWqrd_7ruovSXZoiIk1s0
            @Override // java.lang.Runnable
            public final void run() {
                WindowManagerService.sInstance = new WindowManagerService(context, im, showBootMsgs, onlyCore, policy, atm, transactionFactory);
            }
        }, 0L);
        return sInstance;
    }

    private void initPolicy() {
        UiThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.WindowManagerService.5
            @Override // java.lang.Runnable
            public void run() {
                WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());
                WindowManagerPolicy windowManagerPolicy = WindowManagerService.this.mPolicy;
                Context context = WindowManagerService.this.mContext;
                WindowManagerService windowManagerService = WindowManagerService.this;
                windowManagerPolicy.init(context, windowManagerService, windowManagerService);
            }
        }, 0L);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver result) {
        new WindowManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    private WindowManagerService(Context context, InputManagerService inputManager, boolean showBootMsgs, boolean onlyCore, WindowManagerPolicy policy, ActivityTaskManagerService atm, TransactionFactory transactionFactory) {
        this.mWindowAnimationScaleSetting = 1.0f;
        this.mTransitionAnimationScaleSetting = 1.0f;
        this.mAnimationsDisabled = false;
        this.mTransactionFactory = $$Lambda$hBnABSAsqXWvQ0zKwHWE4BZ3Mc0.INSTANCE;
        LockGuard.installLock(this, 5);
        this.mGlobalLock = atm.getGlobalLock();
        this.mAtmService = atm;
        this.mContext = context;
        this.mAllowBootMessages = showBootMsgs;
        this.mOnlyCore = onlyCore;
        this.mLimitedAlphaCompositing = context.getResources().getBoolean(17891512);
        this.mHasPermanentDpad = context.getResources().getBoolean(17891467);
        this.mInTouchMode = context.getResources().getBoolean(17891398);
        this.mDrawLockTimeoutMillis = context.getResources().getInteger(17694802);
        this.mAllowAnimationsInLowPowerMode = context.getResources().getBoolean(17891341);
        this.mMaxUiWidth = context.getResources().getInteger(17694836);
        this.mDisableTransitionAnimation = context.getResources().getBoolean(17891409);
        this.mPerDisplayFocusEnabled = context.getResources().getBoolean(17891332);
        this.mLowRamTaskSnapshotsAndRecents = context.getResources().getBoolean(17891480);
        this.mInputManager = inputManager;
        this.mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
        this.mDisplayWindowSettings = new DisplayWindowSettings(this);
        this.mTransactionFactory = transactionFactory;
        this.mTransaction = this.mTransactionFactory.make();
        this.mPolicy = policy;
        this.mAnimator = new WindowAnimator(this);
        this.mRoot = new RootWindowContainer(this);
        this.mWindowPlacerLocked = new WindowSurfacePlacer(this);
        this.mTaskSnapshotController = new TaskSnapshotController(this);
        this.mWindowTracing = WindowTracing.createDefaultAndStartLooper(this, Choreographer.getInstance());
        LocalServices.addService(WindowManagerPolicy.class, this.mPolicy);
        this.mDisplayManager = (DisplayManager) context.getSystemService("display");
        this.mKeyguardDisableHandler = KeyguardDisableHandler.create(this.mContext, this.mPolicy, this.mH);
        this.mPowerManager = (PowerManager) context.getSystemService("power");
        this.mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
        PowerManagerInternal powerManagerInternal = this.mPowerManagerInternal;
        if (powerManagerInternal != null) {
            powerManagerInternal.registerLowPowerModeObserver(new PowerManagerInternal.LowPowerModeListener() { // from class: com.android.server.wm.WindowManagerService.6
                public int getServiceType() {
                    return 3;
                }

                public void onLowPowerModeChanged(PowerSaveState result) {
                    synchronized (WindowManagerService.this.mGlobalLock) {
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
        this.mActivityManager = ActivityManager.getService();
        this.mActivityTaskManager = ActivityTaskManager.getService();
        this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        this.mAtmInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        this.mAppOps = (AppOpsManager) context.getSystemService("appops");
        AppOpsManager.OnOpChangedListener onOpChangedListener = new AppOpsManager.OnOpChangedInternalListener() { // from class: com.android.server.wm.WindowManagerService.7
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
        context.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.wm.WindowManagerService.8
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String[] affectedPackages = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                boolean suspended = "android.intent.action.PACKAGES_SUSPENDED".equals(intent.getAction());
                WindowManagerService.this.updateHiddenWhileSuspendedState(new ArraySet(Arrays.asList(affectedPackages)), suspended);
            }
        }, UserHandle.ALL, suspendPackagesFilter, null, null);
        ContentResolver resolver = context.getContentResolver();
        this.mWindowAnimationScaleSetting = Settings.Global.getFloat(resolver, "window_animation_scale", this.mWindowAnimationScaleSetting);
        this.mTransitionAnimationScaleSetting = Settings.Global.getFloat(resolver, "transition_animation_scale", context.getResources().getFloat(17105050));
        setAnimatorDurationScale(Settings.Global.getFloat(resolver, "animator_duration_scale", this.mAnimatorDurationScaleSetting));
        this.mForceDesktopModeOnExternalDisplays = Settings.Global.getInt(resolver, "force_desktop_mode_on_external_displays", 0) != 0;
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        this.mLatencyTracker = LatencyTracker.getInstance(context);
        this.mSettingsObserver = new SettingsObserver();
        this.mHoldingScreenWakeLock = this.mPowerManager.newWakeLock(536870922, TAG);
        this.mHoldingScreenWakeLock.setReferenceCounted(false);
        this.mSurfaceAnimationRunner = new SurfaceAnimationRunner(this.mPowerManagerInternal);
        this.mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(17891357);
        this.mTaskPositioningController = new TaskPositioningController(this, this.mInputManager, this.mActivityTaskManager, this.mH.getLooper());
        this.mDragDropController = new DragDropController(this, this.mH.getLooper());
        this.mHighRefreshRateBlacklist = HighRefreshRateBlacklist.create(context.getResources());
        this.mSystemGestureExclusionLimitDp = Math.max(200, DeviceConfig.getInt("android:window_manager", "system_gesture_exclusion_limit_dp", 0));
        this.mSystemGestureExclusionLogDebounceTimeoutMillis = DeviceConfig.getInt("android:window_manager", "system_gesture_exclusion_log_debounce_millis", 0);
        this.mSystemGestureExcludedByPreQStickyImmersive = DeviceConfig.getBoolean("android:window_manager", "system_gestures_excluded_by_pre_q_sticky_immersive", false);
        DeviceConfig.addOnPropertiesChangedListener("android:window_manager", new HandlerExecutor(this.mH), new DeviceConfig.OnPropertiesChangedListener() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$vZ2iP62NKu_V2W-h0-abrxnOgoI
            public final void onPropertiesChanged(DeviceConfig.Properties properties) {
                WindowManagerService.this.lambda$new$1$WindowManagerService(properties);
            }
        });
        xpWindowManagerService.get(this.mContext).init();
        this.mSharedDisplayContainer = new SharedDisplayContainer();
        this.mSharedDisplayContainer.set(this, atm);
        this.mSharedDisplayContainer.init();
        AppTaskPolicy.get().setSharedDisplayContainer(this.mSharedDisplayContainer);
        LocalServices.addService(WindowManagerInternal.class, new LocalService());
    }

    public /* synthetic */ void lambda$new$1$WindowManagerService(DeviceConfig.Properties properties) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                int exclusionLimitDp = Math.max(200, DeviceConfig.getInt("android:window_manager", "system_gesture_exclusion_limit_dp", 0));
                boolean excludedByPreQSticky = DeviceConfig.getBoolean("android:window_manager", "system_gestures_excluded_by_pre_q_sticky_immersive", false);
                if (this.mSystemGestureExcludedByPreQStickyImmersive != excludedByPreQSticky || this.mSystemGestureExclusionLimitDp != exclusionLimitDp) {
                    this.mSystemGestureExclusionLimitDp = exclusionLimitDp;
                    this.mSystemGestureExcludedByPreQStickyImmersive = excludedByPreQSticky;
                    this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$JQG7CszycLV40zONwvdlvplb1TI
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            ((DisplayContent) obj).updateSystemGestureExclusionLimit();
                        }
                    });
                }
                this.mSystemGestureExclusionLogDebounceTimeoutMillis = DeviceConfig.getInt("android:window_manager", "system_gesture_exclusion_log_debounce_millis", 0);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
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

    public InputManagerCallback getInputManagerCallback() {
        return this.mInputManagerCallback;
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

    /* JADX WARN: Not initialized variable reg: 37, insn: 0x040d: MOVE  (r28 I:??[OBJECT, ARRAY]) = (r37 I:??[OBJECT, ARRAY] A[D('parentWindow' com.android.server.wm.WindowState)]), block:B:190:0x0402 */
    /* JADX WARN: Removed duplicated region for block: B:314:0x06ff A[Catch: all -> 0x072f, TryCatch #11 {all -> 0x072f, blocks: (B:306:0x06ea, B:308:0x06f0, B:314:0x06ff, B:315:0x0706, B:319:0x070d, B:321:0x0713, B:323:0x0717, B:333:0x0746, B:336:0x0751, B:345:0x079b, B:347:0x07a1, B:352:0x07c9, B:372:0x0813, B:390:0x0860, B:392:0x0866, B:399:0x088f, B:401:0x0899, B:404:0x08a9, B:407:0x08bf, B:409:0x08c5, B:411:0x08cd, B:355:0x07d5, B:358:0x07e2, B:363:0x07f5, B:367:0x0804, B:325:0x071d), top: B:504:0x06ea }] */
    /* JADX WARN: Removed duplicated region for block: B:318:0x070b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int addWindow(com.android.server.wm.Session r45, android.view.IWindow r46, int r47, android.view.WindowManager.LayoutParams r48, int r49, int r50, android.graphics.Rect r51, android.graphics.Rect r52, android.graphics.Rect r53, android.graphics.Rect r54, android.view.DisplayCutout.ParcelableWrapper r55, android.view.InputChannel r56, android.view.InsetsState r57) {
        /*
            Method dump skipped, instructions count: 2623
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.addWindow(com.android.server.wm.Session, android.view.IWindow, int, android.view.WindowManager$LayoutParams, int, int, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.view.DisplayCutout$ParcelableWrapper, android.view.InputChannel, android.view.InsetsState):int");
    }

    private DisplayContent getDisplayContentOrCreate(int displayId, IBinder token) {
        Display display;
        WindowToken wToken;
        if (token != null && (wToken = this.mRoot.getWindowToken(token)) != null) {
            return wToken.getDisplayContent();
        }
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
        Rect frame = replacedWindow.getVisibleFrameLw();
        DisplayContent dc = atoken.getDisplayContent();
        dc.mOpeningApps.add(atoken);
        dc.prepareAppTransition(18, true, 0, false);
        dc.mAppTransition.overridePendingAppTransitionClipReveal(frame.left, frame.top, frame.width(), frame.height());
        dc.executeAppTransition();
        return true;
    }

    private void prepareNoneTransitionForRelaunching(AppWindowToken atoken) {
        DisplayContent dc = atoken.getDisplayContent();
        if (this.mDisplayFrozen && !dc.mOpeningApps.contains(atoken) && atoken.isRelaunching()) {
            dc.mOpeningApps.add(atoken);
            dc.prepareAppTransition(0, false, 0, false);
            dc.executeAppTransition();
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, client, false);
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
        onWindowRemoved(win);
        this.mWindowMap.remove(win.mClient.asBinder());
        markForSeamlessRotation(win, false);
        win.resetAppOpsState();
        DisplayContent dc = win.getDisplayContent();
        if (dc.mCurrentFocus == null) {
            dc.mWinRemovedSinceNullFocus.add(win);
        }
        this.mPendingRemove.remove(win);
        this.mResizingWindows.remove(win);
        updateNonSystemOverlayWindowsVisibilityIfNeeded(win, false);
        this.mWindowsChanged = true;
        if (WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT) {
            Slog.v(TAG, "Final remove of window: " + win);
        }
        DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.mInputMethodWindow == win) {
            displayContent.setInputMethodWindowLocked(null);
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
        if (win.mAttrs.type == 2013) {
            dc.mWallpaperController.clearLastWallpaperTimeoutTime();
            dc.pendingLayoutChanges |= 4;
        } else if ((win.mAttrs.flags & DumpState.DUMP_DEXOPT) != 0) {
            dc.pendingLayoutChanges |= 4;
        }
        if (!this.mWindowPlacerLocked.isInLayout()) {
            dc.assignWindowLayers(true);
            this.mWindowPlacerLocked.performSurfacePlacement();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }
        dc.getInputMonitor().updateInputWindowsLw(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateHiddenWhileSuspendedState(ArraySet<String> packages, boolean suspended) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
            synchronized (this.mGlobalLock) {
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
            synchronized (this.mGlobalLock) {
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
                    if (this.mAccessibilityController != null && (w.getDisplayContent().getDisplayId() == 0 || w.getDisplayContent().getParentWindow() != null)) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    outDisplayFrame.setEmpty();
                    resetPriorityAfterLockedSection();
                    return;
                }
                outDisplayFrame.set(win.getDisplayFrameLw());
                if (win.inSizeCompatMode()) {
                    outDisplayFrame.scale(win.mInvGlobalScale);
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        WindowState window;
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (this.mAccessibilityController != null && (window = this.mWindowMap.get(token)) != null) {
                    this.mAccessibilityController.onRectangleOnScreenRequestedLocked(window.getDisplayId(), rectangle);
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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

    private boolean hasStatusBarPermission(int pid, int uid) {
        return this.mContext.checkPermission("android.permission.STATUS_BAR", pid, uid) == 0;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(59:15|16|(1:18)|19|20|21|(3:23|24|25)(1:308)|26|(9:28|29|30|31|(3:33|(1:37)|38)|39|40|41|(18:43|44|(1:46)|48|49|50|(1:52)|53|(1:55)|56|(1:62)|63|(1:71)|72|(1:74)|75|(3:77|(1:79)(1:293)|80)(1:294)|81)(2:296|297))(1:305)|(12:82|83|(1:85)(1:290)|86|(1:88)(1:289)|89|(1:91)|92|(1:288)|100|(1:287)(1:104)|105)|(20:(67:109|110|(57:117|118|(1:283)(1:122)|123|(1:125)(1:282)|126|(1:281)(1:130)|131|(1:133)|134|(1:136)(1:280)|137|(1:279)(1:145)|(1:278)(4:151|(1:153)|154|(1:156)(1:277))|157|(9:159|160|161|162|163|(1:165)|166|(2:168|(1:170))(1:258)|171)(4:265|266|(4:272|(1:274)|275|276)(1:270)|271)|(1:257)(1:175)|176|(1:178)(1:256)|(2:180|(1:182))|(1:184)|185|(1:187)|188|189|190|191|(1:253)(1:195)|196|(1:198)|199|(1:201)|202|(1:204)|205|(1:207)(1:252)|208|(2:210|211)(1:248)|212|213|214|215|216|217|(1:219)(1:240)|220|(1:239)|224|(1:226)(1:238)|227|(1:229)|230|231|232|(1:234)(1:237)|235|236)|284|118|(1:120)|283|123|(0)(0)|126|(1:128)|281|131|(0)|134|(0)(0)|137|(3:139|141|143)|279|(1:147)|278|157|(0)(0)|(1:173)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(1:193)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213|214|215|216|217|(0)(0)|220|(1:222)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|(66:112|114|117|118|(0)|283|123|(0)(0)|126|(0)|281|131|(0)|134|(0)(0)|137|(0)|279|(0)|278|157|(0)(0)|(0)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(0)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213|214|215|216|217|(0)(0)|220|(0)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|214|215|216|217|(0)(0)|220|(0)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|285|110|284|118|(0)|283|123|(0)(0)|126|(0)|281|131|(0)|134|(0)(0)|137|(0)|279|(0)|278|157|(0)(0)|(0)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(0)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213) */
    /* JADX WARN: Can't wrap try/catch for region: R(70:15|16|(1:18)|19|20|21|(3:23|24|25)(1:308)|26|(9:28|29|30|31|(3:33|(1:37)|38)|39|40|41|(18:43|44|(1:46)|48|49|50|(1:52)|53|(1:55)|56|(1:62)|63|(1:71)|72|(1:74)|75|(3:77|(1:79)(1:293)|80)(1:294)|81)(2:296|297))(1:305)|82|83|(1:85)(1:290)|86|(1:88)(1:289)|89|(1:91)|92|(1:288)|100|(1:287)(1:104)|105|(20:(67:109|110|(57:117|118|(1:283)(1:122)|123|(1:125)(1:282)|126|(1:281)(1:130)|131|(1:133)|134|(1:136)(1:280)|137|(1:279)(1:145)|(1:278)(4:151|(1:153)|154|(1:156)(1:277))|157|(9:159|160|161|162|163|(1:165)|166|(2:168|(1:170))(1:258)|171)(4:265|266|(4:272|(1:274)|275|276)(1:270)|271)|(1:257)(1:175)|176|(1:178)(1:256)|(2:180|(1:182))|(1:184)|185|(1:187)|188|189|190|191|(1:253)(1:195)|196|(1:198)|199|(1:201)|202|(1:204)|205|(1:207)(1:252)|208|(2:210|211)(1:248)|212|213|214|215|216|217|(1:219)(1:240)|220|(1:239)|224|(1:226)(1:238)|227|(1:229)|230|231|232|(1:234)(1:237)|235|236)|284|118|(1:120)|283|123|(0)(0)|126|(1:128)|281|131|(0)|134|(0)(0)|137|(3:139|141|143)|279|(1:147)|278|157|(0)(0)|(1:173)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(1:193)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213|214|215|216|217|(0)(0)|220|(1:222)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|(66:112|114|117|118|(0)|283|123|(0)(0)|126|(0)|281|131|(0)|134|(0)(0)|137|(0)|279|(0)|278|157|(0)(0)|(0)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(0)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213|214|215|216|217|(0)(0)|220|(0)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|214|215|216|217|(0)(0)|220|(0)|239|224|(0)(0)|227|(0)|230|231|232|(0)(0)|235|236)|285|110|284|118|(0)|283|123|(0)(0)|126|(0)|281|131|(0)|134|(0)(0)|137|(0)|279|(0)|278|157|(0)(0)|(0)|257|176|(0)(0)|(0)|(0)|185|(0)|188|189|190|191|(0)|253|196|(0)|199|(0)|202|(0)|205|(0)(0)|208|(0)(0)|212|213) */
    /* JADX WARN: Code restructure failed: missing block: B:285:0x05cf, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:287:0x05d5, code lost:
        r0 = th;
     */
    /* JADX WARN: Removed duplicated region for block: B:135:0x0263 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:141:0x0272  */
    /* JADX WARN: Removed duplicated region for block: B:142:0x0275  */
    /* JADX WARN: Removed duplicated region for block: B:145:0x027f A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:151:0x029c A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x02d1  */
    /* JADX WARN: Removed duplicated region for block: B:155:0x02d3  */
    /* JADX WARN: Removed duplicated region for block: B:158:0x02d8 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:167:0x02f0 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:181:0x0337 A[Catch: all -> 0x05f4, TRY_LEAVE, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:199:0x03b3  */
    /* JADX WARN: Removed duplicated region for block: B:212:0x0424 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:218:0x0437  */
    /* JADX WARN: Removed duplicated region for block: B:219:0x0439  */
    /* JADX WARN: Removed duplicated region for block: B:221:0x043c A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:225:0x0448 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:228:0x0454 A[Catch: all -> 0x05f4, TryCatch #10 {all -> 0x05f4, blocks: (B:91:0x01b2, B:93:0x01b6, B:95:0x01f3, B:99:0x01fa, B:101:0x0200, B:102:0x0204, B:104:0x0213, B:106:0x021b, B:108:0x0223, B:111:0x022f, B:118:0x023e, B:124:0x024d, B:126:0x0251, B:128:0x0255, B:133:0x025d, B:135:0x0263, B:139:0x026e, B:143:0x0277, B:145:0x027f, B:147:0x0283, B:149:0x0291, B:151:0x029c, B:152:0x02ca, B:156:0x02d4, B:158:0x02d8, B:160:0x02dc, B:162:0x02e3, B:167:0x02f0, B:169:0x02f6, B:171:0x02fa, B:173:0x02fe, B:174:0x031e, B:176:0x0324, B:179:0x032d, B:181:0x0337, B:183:0x0341, B:187:0x034c, B:189:0x0354, B:191:0x035a, B:193:0x0362, B:212:0x0424, B:216:0x0433, B:221:0x043c, B:223:0x0442, B:225:0x0448, B:226:0x0450, B:228:0x0454, B:195:0x0371, B:196:0x03ad, B:200:0x03b9, B:202:0x03c8, B:204:0x03ce, B:210:0x041d, B:205:0x03e0, B:207:0x03e4, B:209:0x0419, B:110:0x022b), top: B:323:0x01b2, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:233:0x046d A[Catch: all -> 0x05d5, TryCatch #11 {all -> 0x05d5, blocks: (B:231:0x0461, B:233:0x046d, B:235:0x0471, B:237:0x048c, B:239:0x0490, B:240:0x0495, B:242:0x0499, B:243:0x049f, B:245:0x04a7, B:246:0x04aa, B:248:0x04b0), top: B:324:0x0461 }] */
    /* JADX WARN: Removed duplicated region for block: B:239:0x0490 A[Catch: all -> 0x05d5, TryCatch #11 {all -> 0x05d5, blocks: (B:231:0x0461, B:233:0x046d, B:235:0x0471, B:237:0x048c, B:239:0x0490, B:240:0x0495, B:242:0x0499, B:243:0x049f, B:245:0x04a7, B:246:0x04aa, B:248:0x04b0), top: B:324:0x0461 }] */
    /* JADX WARN: Removed duplicated region for block: B:242:0x0499 A[Catch: all -> 0x05d5, TryCatch #11 {all -> 0x05d5, blocks: (B:231:0x0461, B:233:0x046d, B:235:0x0471, B:237:0x048c, B:239:0x0490, B:240:0x0495, B:242:0x0499, B:243:0x049f, B:245:0x04a7, B:246:0x04aa, B:248:0x04b0), top: B:324:0x0461 }] */
    /* JADX WARN: Removed duplicated region for block: B:245:0x04a7 A[Catch: all -> 0x05d5, TryCatch #11 {all -> 0x05d5, blocks: (B:231:0x0461, B:233:0x046d, B:235:0x0471, B:237:0x048c, B:239:0x0490, B:240:0x0495, B:242:0x0499, B:243:0x049f, B:245:0x04a7, B:246:0x04aa, B:248:0x04b0), top: B:324:0x0461 }] */
    /* JADX WARN: Removed duplicated region for block: B:248:0x04b0 A[Catch: all -> 0x05d5, TRY_LEAVE, TryCatch #11 {all -> 0x05d5, blocks: (B:231:0x0461, B:233:0x046d, B:235:0x0471, B:237:0x048c, B:239:0x0490, B:240:0x0495, B:242:0x0499, B:243:0x049f, B:245:0x04a7, B:246:0x04aa, B:248:0x04b0), top: B:324:0x0461 }] */
    /* JADX WARN: Removed duplicated region for block: B:250:0x04b4  */
    /* JADX WARN: Removed duplicated region for block: B:252:0x04b7  */
    /* JADX WARN: Removed duplicated region for block: B:254:0x04bd A[Catch: all -> 0x05cf, TryCatch #7 {all -> 0x05cf, blocks: (B:253:0x04b9, B:255:0x04c2, B:254:0x04bd), top: B:318:0x04b5 }] */
    /* JADX WARN: Removed duplicated region for block: B:261:0x050a A[Catch: all -> 0x0623, TryCatch #3 {all -> 0x0623, blocks: (B:259:0x04e7, B:261:0x050a, B:263:0x0555, B:265:0x0559, B:268:0x057b, B:272:0x0584, B:274:0x058a, B:275:0x05ac, B:276:0x05af, B:267:0x055d, B:302:0x061e, B:208:0x03fa), top: B:311:0x0022 }] */
    /* JADX WARN: Removed duplicated region for block: B:262:0x0553  */
    /* JADX WARN: Removed duplicated region for block: B:265:0x0559 A[Catch: all -> 0x0623, TryCatch #3 {all -> 0x0623, blocks: (B:259:0x04e7, B:261:0x050a, B:263:0x0555, B:265:0x0559, B:268:0x057b, B:272:0x0584, B:274:0x058a, B:275:0x05ac, B:276:0x05af, B:267:0x055d, B:302:0x061e, B:208:0x03fa), top: B:311:0x0022 }] */
    /* JADX WARN: Removed duplicated region for block: B:270:0x057f  */
    /* JADX WARN: Removed duplicated region for block: B:271:0x0582  */
    /* JADX WARN: Removed duplicated region for block: B:274:0x058a A[Catch: all -> 0x0623, TryCatch #3 {all -> 0x0623, blocks: (B:259:0x04e7, B:261:0x050a, B:263:0x0555, B:265:0x0559, B:268:0x057b, B:272:0x0584, B:274:0x058a, B:275:0x05ac, B:276:0x05af, B:267:0x055d, B:302:0x061e, B:208:0x03fa), top: B:311:0x0022 }] */
    /* JADX WARN: Removed duplicated region for block: B:279:0x05b5  */
    /* JADX WARN: Removed duplicated region for block: B:280:0x05c5  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int relayoutWindow(com.android.server.wm.Session r39, android.view.IWindow r40, int r41, android.view.WindowManager.LayoutParams r42, int r43, int r44, int r45, int r46, long r47, android.graphics.Rect r49, android.graphics.Rect r50, android.graphics.Rect r51, android.graphics.Rect r52, android.graphics.Rect r53, android.graphics.Rect r54, android.graphics.Rect r55, android.view.DisplayCutout.ParcelableWrapper r56, android.util.MergedConfiguration r57, android.view.SurfaceControl r58, android.view.InsetsState r59) {
        /*
            Method dump skipped, instructions count: 1573
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.relayoutWindow(com.android.server.wm.Session, android.view.IWindow, int, android.view.WindowManager$LayoutParams, int, int, int, int, long, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.view.DisplayCutout$ParcelableWrapper, android.util.MergedConfiguration, android.view.SurfaceControl, android.view.InsetsState):int");
    }

    private boolean tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator, boolean focusMayChange) {
        int transit = 2;
        if (win.mAttrs.type == 3) {
            transit = 5;
        }
        if (win.isWinVisibleLw() && winAnimator.applyAnimationLocked(transit, false)) {
            focusMayChange = true;
            win.mAnimatingExit = true;
        } else if (win.isAnimating()) {
            win.mAnimatingExit = true;
        } else if (win.getDisplayContent().mWallpaperController.isWallpaperTarget(win)) {
            win.mAnimatingExit = true;
        } else {
            DisplayContent displayContent = win.getDisplayContent();
            if (displayContent.mInputMethodWindow == win) {
                displayContent.setInputMethodWindowLocked(null);
            }
            boolean stopped = win.mAppToken != null ? win.mAppToken.mAppStopped : true;
            win.mDestroying = true;
            win.destroySurface(false, stopped);
        }
        AccessibilityController accessibilityController = this.mAccessibilityController;
        if (accessibilityController != null) {
            accessibilityController.onWindowTransitionLocked(win, transit);
        }
        SurfaceControl.openTransaction();
        winAnimator.detachChildren();
        SurfaceControl.closeTransaction();
        return focusMayChange;
    }

    private int createSurfaceControl(SurfaceControl outSurfaceControl, int result, WindowState win, WindowStateAnimator winAnimator) {
        if (!win.mHasSurface) {
            result |= 4;
        }
        try {
            Trace.traceBegin(32L, "createSurfaceControl");
            WindowSurfaceController surfaceController = winAnimator.createSurfaceLocked(win.mAttrs.type, win.mOwnerUid, win.mPid);
            Trace.traceEnd(32L);
            if (surfaceController != null) {
                surfaceController.getSurfaceControl(outSurfaceControl);
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                    Slog.i(TAG, "  OUT SURFACE " + outSurfaceControl + ": copied");
                }
            } else {
                Slog.w(TAG, "Failed to create surface control for " + win);
                outSurfaceControl.release();
            }
            return result;
        } catch (Throwable th) {
            Trace.traceEnd(32L);
            throw th;
        }
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
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
            synchronized (this.mGlobalLock) {
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
                    if ((win.mAttrs.flags & DumpState.DUMP_DEXOPT) != 0) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = getDisplayContentOrCreate(displayId, null);
                if (dc == null) {
                    Slog.w(TAG, "addWindowToken: Attempted to add token: " + binder + " for non-exiting displayId=" + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
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
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w(TAG, "removeWindowToken: Attempted to remove token: " + binder + " for non-exiting displayId=" + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                WindowToken token = dc.removeWindowToken(binder);
                if (token != null) {
                    dc.getInputMonitor().updateInputWindowsLw(true);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setNewDisplayOverrideConfiguration(Configuration overrideConfig, DisplayContent dc) {
        if (dc.mWaitingForConfig) {
            dc.mWaitingForConfig = false;
            this.mLastFinishedFreezeSource = "new-config";
        }
        this.mRoot.setDisplayOverrideConfigurationIfNeeded(overrideConfig, dc);
    }

    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().prepareAppTransition(transit, alwaysKeepCurrent, 0, false);
    }

    public void overridePendingAppTransitionMultiThumbFuture(IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback, boolean scaleUp, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to call overridePendingAppTransitionMultiThumbFuture for the display " + displayId + " that does not exist.");
                    resetPriorityAfterLockedSection();
                    return;
                }
                displayContent.mAppTransition.overridePendingAppTransitionMultiThumbFuture(specsFuture, callback, scaleUp);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter, int displayId) {
        if (!checkCallingPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS", "overridePendingAppTransitionRemote()")) {
            throw new SecurityException("Requires CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to call overridePendingAppTransitionRemote for the display " + displayId + " that does not exist.");
                    resetPriorityAfterLockedSection();
                    return;
                }
                displayContent.mAppTransition.overridePendingAppTransitionRemote(remoteAnimationAdapter);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void endProlongedAnimations() {
    }

    public void executeAppTransition() {
        if (!checkCallingPermission("android.permission.MANAGE_APP_TOKENS", "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().executeAppTransition();
    }

    public void initializeRecentsAnimation(int targetActivityType, IRecentsAnimationRunner recentsAnimationRunner, RecentsAnimationController.RecentsAnimationCallbacks callbacks, int displayId, SparseBooleanArray recentTaskIds) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRecentsAnimationController = new RecentsAnimationController(this, recentsAnimationRunner, callbacks, displayId);
                this.mRoot.getDisplayContent(displayId).mAppTransition.updateBooster();
                this.mRecentsAnimationController.initialize(targetActivityType, recentTaskIds);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @VisibleForTesting
    void setRecentsAnimationController(RecentsAnimationController controller) {
        this.mRecentsAnimationController = controller;
    }

    public RecentsAnimationController getRecentsAnimationController() {
        return this.mRecentsAnimationController;
    }

    public boolean canStartRecentsAnimation() {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (getDefaultDisplayContentLocked().mAppTransition.isTransitionSet()) {
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
        RecentsAnimationController recentsAnimationController = this.mRecentsAnimationController;
        if (recentsAnimationController != null) {
            recentsAnimationController.cancelAnimationSynchronously(reorderMode, reason);
        }
    }

    public void cleanupRecentsAnimation(@RecentsAnimationController.ReorderMode int reorderMode) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (this.mRecentsAnimationController != null) {
                    RecentsAnimationController controller = this.mRecentsAnimationController;
                    this.mRecentsAnimationController = null;
                    controller.cleanupAnimation(reorderMode);
                    getDefaultDisplayContentLocked().mAppTransition.updateBooster();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setAppFullscreen(IBinder token, boolean toOpaque) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        if (wtoken == null || (win = wtoken.findMainWindow()) == null) {
            return;
        }
        win.mWinAnimator.setOpaqueLocked(isOpaque & (!PixelFormat.formatHasAlpha(win.getAttrs().format)));
    }

    public void setDockedStackCreateState(int mode, Rect bounds) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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

    public void notifyShowingDreamChanged() {
        notifyKeyguardFlagsChanged(null, 0);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public WindowManagerPolicy.WindowState getInputMethodWindowLw() {
        return this.mRoot.getCurrentInputMethodWindow();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void notifyKeyguardTrustedChanged() {
        this.mAtmInternal.notifyKeyguardTrustedChanged();
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

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void onPowerKeyDown(boolean isScreenOn) {
        this.mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$99XNq73vh8e4HVH9BuxFhbLxKVY
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((DisplayPolicy) obj).onPowerKeyDown(((Boolean) obj2).booleanValue());
            }
        }, PooledLambda.__(), Boolean.valueOf(isScreenOn)));
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void onUserSwitched() {
        this.mSettingsObserver.updateSystemUiSettings();
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllDisplayPolicies(new Consumer() { // from class: com.android.server.wm.-$$Lambda$_jL5KNK44AQYPj1d8Hd3FYO0W-M
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ((DisplayPolicy) obj).resetSystemUiVisibilityLw();
                    }
                });
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void moveDisplayToTop(int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null && this.mRoot.getTopChild() != displayContent) {
                    this.mRoot.positionChildAt(Integer.MAX_VALUE, displayContent, true);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferSurfaceLayout() {
        this.mWindowPlacerLocked.deferLayout();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueSurfaceLayout() {
        this.mWindowPlacerLocked.continueLayout();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyKeyguardFlagsChanged(Runnable callback, int displayId) {
        this.mAtmInternal.notifyKeyguardFlagsChanged(callback, displayId);
    }

    public boolean isKeyguardTrusted() {
        boolean isKeyguardTrustedLw;
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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

    public void disableKeyguard(IBinder token, String tag, int userId) {
        int userId2 = this.mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, 2, "disableKeyguard", (String) null);
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        int callingUid = Binder.getCallingUid();
        long origIdentity = Binder.clearCallingIdentity();
        try {
            this.mKeyguardDisableHandler.disableKeyguard(token, tag, callingUid, userId2);
        } finally {
            Binder.restoreCallingIdentity(origIdentity);
        }
    }

    public void reenableKeyguard(IBinder token, int userId) {
        int userId2 = this.mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, 2, "reenableKeyguard", (String) null);
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        Preconditions.checkNotNull(token, "token is null");
        int callingUid = Binder.getCallingUid();
        long origIdentity = Binder.clearCallingIdentity();
        try {
            this.mKeyguardDisableHandler.reenableKeyguard(token, callingUid, userId2);
        } finally {
            Binder.restoreCallingIdentity(origIdentity);
        }
    }

    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.DISABLE_KEYGUARD") != 0) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        this.mPolicy.exitKeyguardSecurely(new WindowManagerPolicy.OnKeyguardExitResult() { // from class: com.android.server.wm.WindowManagerService.9
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

    public boolean isKeyguardSecure(int userId) {
        if (userId != UserHandle.getCallingUserId() && !checkCallingPermission("android.permission.INTERACT_ACROSS_USERS", "isKeyguardSecure")) {
            throw new SecurityException("Requires INTERACT_ACROSS_USERS permission");
        }
        long origId = Binder.clearCallingIdentity();
        try {
            return this.mPolicy.isKeyguardSecure(userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean isShowingDream() {
        boolean isShowingDreamLw;
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                isShowingDreamLw = getDefaultDisplayContentLocked().getDisplayPolicy().isShowingDreamLw();
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        if (which == 0) {
            this.mWindowAnimationScaleSetting = scale2;
        } else if (which == 1) {
            this.mTransitionAnimationScaleSetting = scale2;
        } else if (which == 2) {
            this.mAnimatorDurationScaleSetting = scale2;
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
        if (which != 0) {
            if (which != 1) {
                if (which == 2) {
                    return this.mAnimatorDurationScaleSetting;
                }
                return 0.0f;
            }
            return this.mTransitionAnimationScaleSetting;
        }
        return this.mWindowAnimationScaleSetting;
    }

    public float[] getAnimationScales() {
        return new float[]{this.mWindowAnimationScaleSetting, this.mTransitionAnimationScaleSetting, this.mAnimatorDurationScaleSetting};
    }

    public float getCurrentAnimatorScale() {
        float f;
        synchronized (this.mGlobalLock) {
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
    public void registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.registerPointerEventListener(listener);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public void unregisterPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.unregisterPointerEventListener(listener);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
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
        synchronized (this.mGlobalLock) {
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

    public void setCurrentUser(final int newUserId, int[] currentProfileIds) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mCurrentUserId = newUserId;
                this.mCurrentProfileIds = currentProfileIds;
                this.mPolicy.setCurrentUserLw(newUserId);
                this.mKeyguardDisableHandler.setCurrentUser(newUserId);
                this.mRoot.switchUser();
                this.mWindowPlacerLocked.performSurfacePlacement();
                DisplayContent displayContent = getDefaultDisplayContentLocked();
                TaskStack stack = displayContent.getSplitScreenPrimaryStackIgnoringVisibility();
                displayContent.mDividerControllerLocked.notifyDockedStackExistsChanged(stack != null && stack.hasTaskForUser(newUserId));
                this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$05fsn8aS3Yh8PJChNK4X3zTgx6M
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ((DisplayContent) obj).mAppTransition.setCurrentUser(newUserId);
                    }
                });
                if (this.mDisplayReady) {
                    int forcedDensity = getForcedDisplayDensityForUserLocked(newUserId);
                    int targetDensity = forcedDensity != 0 ? forcedDensity : displayContent.mInitialDisplayDensity;
                    displayContent.setForcedDensity(targetDensity, -2);
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
        int i = 0;
        while (true) {
            int[] iArr = this.mCurrentProfileIds;
            if (i < iArr.length) {
                if (iArr[i] == userId) {
                    return true;
                }
                i++;
            } else {
                return false;
            }
        }
    }

    public void enableScreenAfterBoot() {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_BOOT) {
                    Slog.i(TAG, "performEnableScreen: mDisplayEnabled=" + this.mDisplayEnabled + " mForceDisplayEnabled=" + this.mForceDisplayEnabled + " mShowingBootMessages=" + this.mShowingBootMessages + " mSystemBooted=" + this.mSystemBooted + " mOnlyCore=" + this.mOnlyCore, new RuntimeException("here").fillInStackTrace());
                }
                if (this.mDisplayEnabled) {
                    resetPriorityAfterLockedSection();
                } else if (!this.mSystemBooted && !this.mShowingBootMessages) {
                    resetPriorityAfterLockedSection();
                } else if (!this.mShowingBootMessages && !this.mPolicy.canDismissBootAnimation()) {
                    resetPriorityAfterLockedSection();
                } else if (!this.mForceDisplayEnabled && getDefaultDisplayContentLocked().checkWaitingForWindows()) {
                    resetPriorityAfterLockedSection();
                } else {
                    if (this.mPolicy != null) {
                        this.mPolicy.onBootChanged(this.mSystemBooted, this.mShowingBootMessages);
                    }
                    boolean allowFinishBootAnimation = this.mPolicy != null ? this.mPolicy.allowFinishBootAnimation() : true;
                    if (is3DUI) {
                        this.mDisplayEnabled = true;
                        if (!this.mBootAnimationStopped) {
                            this.mBootAnimationStopped = true;
                            Slog.i(TAG, "performEnableScreen: enabled display and notify animation exit later");
                            new Thread(new Runnable() { // from class: com.android.server.wm.WindowManagerService.10
                                @Override // java.lang.Runnable
                                public void run() {
                                    try {
                                        Thread.sleep(WindowManagerService.delayTime);
                                    } catch (Exception e) {
                                    }
                                    Trace.asyncTraceBegin(32L, "Stop bootanim", 0);
                                    SystemProperties.set("service.bootanim.exit", "1");
                                    Slog.i(WindowManagerService.TAG, "performEnableScreen: notify animation exit");
                                    try {
                                        IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                                        if (surfaceFlinger != null) {
                                            Slog.i(WindowManagerService.TAG, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                                            Parcel data = Parcel.obtain();
                                            data.writeInterfaceToken("android.ui.ISurfaceComposer");
                                            surfaceFlinger.transact(1, data, null, 0);
                                            data.recycle();
                                        }
                                    } catch (RemoteException e2) {
                                        Slog.e(WindowManagerService.TAG, "Boot completed: SurfaceFlinger is dead!");
                                    }
                                    EventLog.writeEvent((int) EventLogTags.WM_BOOT_ANIMATION_DONE, SystemClock.uptimeMillis());
                                    Trace.asyncTraceEnd(32L, "Stop bootanim", 0);
                                    if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || WindowManagerDebugConfig.DEBUG_BOOT) {
                                        Slog.i(WindowManagerService.TAG, "******************** ENABLING SCREEN!");
                                    }
                                    WindowManagerService.this.mInputManagerCallback.setEventDispatchingLw(WindowManagerService.this.mEventDispatchingEnabled);
                                }
                            }).start();
                        }
                    } else {
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
                        this.mInputManagerCallback.setEventDispatchingLw(this.mEventDispatchingEnabled);
                    }
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        if (this.mContext.getResources().getConfiguration().isScreenRound() && this.mContext.getResources().getBoolean(17891605)) {
            synchronized (this.mGlobalLock) {
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
            int showMask = inversionState != 1 ? 1 : 0;
            Message m = this.mH.obtainMessage(35);
            m.arg1 = showMask;
            this.mH.sendMessage(m);
        }
    }

    public void showEmulatorDisplayOverlayIfNeeded() {
        if (this.mContext.getResources().getBoolean(17891601) && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false) && Build.IS_EMULATOR) {
            H h = this.mH;
            h.sendMessage(h.obtainMessage(36));
        }
    }

    public void showCircularMask(boolean visible) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION showCircularMask(visible=" + visible + ")");
                }
                openSurfaceTransaction();
                if (visible) {
                    if (this.mCircularDisplayMask == null) {
                        int screenOffset = this.mContext.getResources().getInteger(17694959);
                        int maskThickness = this.mContext.getResources().getDimensionPixelSize(17105048);
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
        synchronized (this.mGlobalLock) {
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
        if (on) {
            H h = this.mH;
            h.sendMessage(h.obtainMessage(25, 1, pid));
            H h2 = this.mH;
            h2.sendMessageDelayed(h2.obtainMessage(25, 0, pid), 1000L);
            return;
        }
        H h3 = this.mH;
        h3.sendMessage(h3.obtainMessage(25, 0, pid));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showStrictModeViolation(int arg, int pid) {
        boolean on = arg != 0;
        synchronized (this.mGlobalLock) {
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
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(0);
                screenshotWallpaperLocked = dc.mWallpaperController.screenshotWallpaperLocked();
            }
            resetPriorityAfterLockedSection();
            return screenshotWallpaperLocked;
        } finally {
            Trace.traceEnd(32L);
        }
    }

    public boolean requestAssistScreenshot(final IAssistDataReceiver receiver) {
        final Bitmap bm;
        if (!checkCallingPermission("android.permission.READ_FRAME_BUFFER", "requestAssistScreenshot()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        synchronized (this.mGlobalLock) {
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
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$Zv37mcLTUXyG89YznyHzluaKNE0
            @Override // java.lang.Runnable
            public final void run() {
                receiver.onHandleAssistScreenshot(bm);
            }
        });
        return true;
    }

    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, int userId, boolean reducedResolution, boolean restoreFromDisk) {
        return this.mTaskSnapshotController.getSnapshot(taskId, userId, restoreFromDisk, reducedResolution);
    }

    public void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        synchronized (this.mGlobalLock) {
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRotateForApp(int displayId, int fixedToUserRotation) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to set rotate for app for a missing display.");
                    resetPriorityAfterLockedSection();
                    return;
                }
                display.getDisplayRotation().setFixedToUserRotation(fixedToUserRotation);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void freezeRotation(int rotation) {
        freezeDisplayRotation(0, rotation);
    }

    public void freezeDisplayRotation(int displayId, int rotation) {
        if (!checkCallingPermission("android.permission.SET_ORIENTATION", "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > 3) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid rotation constant.");
        }
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to freeze rotation for a missing display.");
                    resetPriorityAfterLockedSection();
                    return;
                }
                display.getDisplayRotation().freezeRotation(rotation);
                resetPriorityAfterLockedSection();
                Binder.restoreCallingIdentity(origId);
                updateRotationUnchecked(false, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void thawRotation() {
        thawDisplayRotation(0);
    }

    public void thawDisplayRotation(int displayId) {
        if (!checkCallingPermission("android.permission.SET_ORIENTATION", "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "thawRotation: mRotation=" + getDefaultDisplayRotation());
        }
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to thaw rotation for a missing display.");
                    resetPriorityAfterLockedSection();
                    return;
                }
                display.getDisplayRotation().thawRotation();
                resetPriorityAfterLockedSection();
                Binder.restoreCallingIdentity(origId);
                updateRotationUnchecked(false, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean isRotationFrozen() {
        return isDisplayRotationFrozen(0);
    }

    public boolean isDisplayRotationFrozen(int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to thaw rotation for a missing display.");
                    resetPriorityAfterLockedSection();
                    return false;
                }
                boolean isRotationFrozen = display.getDisplayRotation().isRotationFrozen();
                resetPriorityAfterLockedSection();
                return isRotationFrozen;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout) {
        updateRotationUnchecked(alwaysSendConfiguration, forceRelayout);
    }

    private void updateRotationUnchecked(boolean alwaysSendConfiguration, boolean forceRelayout) {
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "updateRotationUnchecked: alwaysSendConfiguration=" + alwaysSendConfiguration + " forceRelayout=" + forceRelayout);
        }
        Trace.traceBegin(32L, "updateRotation");
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                boolean layoutNeeded = false;
                int displayCount = this.mRoot.mChildren.size();
                for (int i = 0; i < displayCount; i++) {
                    DisplayContent displayContent = (DisplayContent) this.mRoot.mChildren.get(i);
                    Trace.traceBegin(32L, "updateRotation: display");
                    boolean rotationChanged = displayContent.updateRotationUnchecked();
                    Trace.traceEnd(32L);
                    if (!rotationChanged || forceRelayout) {
                        displayContent.setLayoutNeeded();
                        layoutNeeded = true;
                    }
                    if (rotationChanged || alwaysSendConfiguration) {
                        displayContent.sendNewConfiguration();
                    }
                }
                if (layoutNeeded) {
                    Trace.traceBegin(32L, "updateRotation: performSurfacePlacement");
                    this.mWindowPlacerLocked.performSurfacePlacement();
                    Trace.traceEnd(32L);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
            Trace.traceEnd(32L);
        }
    }

    public int getDefaultDisplayRotation() {
        int rotation;
        synchronized (this.mGlobalLock) {
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

    public int watchRotation(IRotationWatcher watcher, int displayId) {
        DisplayContent displayContent;
        int rotation;
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                displayContent = this.mRoot.getDisplayContent(displayId);
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        if (displayContent == null) {
            throw new IllegalArgumentException("Trying to register rotation event for invalid display: " + displayId);
        }
        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() { // from class: com.android.server.wm.WindowManagerService.11
            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                synchronized (WindowManagerService.this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                try {
                    boostPriorityForLockedSection();
                    watcher.asBinder().linkToDeath(dr, 0);
                    this.mRotationWatchers.add(new RotationWatcher(watcher, dr, displayId));
                } catch (RemoteException e) {
                }
                rotation = displayContent.getRotation();
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        return rotation;
    }

    public void removeRotationWatcher(IRotationWatcher watcher) {
        IBinder watcherBinder = watcher.asBinder();
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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

    public void registerSystemGestureExclusionListener(ISystemGestureExclusionListener listener, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    throw new IllegalArgumentException("Trying to register visibility event for invalid display: " + displayId);
                }
                displayContent.registerSystemGestureExclusionListener(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void unregisterSystemGestureExclusionListener(ISystemGestureExclusionListener listener, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    throw new IllegalArgumentException("Trying to register visibility event for invalid display: " + displayId);
                }
                displayContent.unregisterSystemGestureExclusionListener(listener);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportSystemGestureExclusionChanged(Session session, IWindow window, List<Rect> exclusionRects) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                WindowState win = windowForClientLocked(session, window, true);
                if (win.setSystemGestureExclusion(exclusionRects)) {
                    win.getDisplayContent().updateSystemGestureExclusion();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void registerDisplayFoldListener(IDisplayFoldListener listener) {
        this.mPolicy.registerDisplayFoldListener(listener);
    }

    public void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        this.mPolicy.unregisterDisplayFoldListener(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOverrideFoldedArea(Rect area) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                this.mPolicy.setOverrideFoldedArea(area);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getFoldedArea() {
        Rect foldedArea;
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                foldedArea = this.mPolicy.getFoldedArea();
            }
            resetPriorityAfterLockedSection();
            return foldedArea;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int getPreferredOptionsPanelGravity(int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    resetPriorityAfterLockedSection();
                    return 81;
                }
                int preferredOptionsPanelGravity = displayContent.getPreferredOptionsPanelGravity();
                resetPriorityAfterLockedSection();
                return preferredOptionsPanelGravity;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean startViewServer(int port) {
        if (!isSystemSecure() && checkCallingPermission("android.permission.DUMP", "startViewServer") && port >= 1024) {
            ViewServer viewServer = this.mViewServer;
            if (viewServer != null) {
                if (!viewServer.isRunning()) {
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
        ViewServer viewServer;
        if (isSystemSecure() || !checkCallingPermission("android.permission.DUMP", "stopViewServer") || (viewServer = this.mViewServer) == null) {
            return false;
        }
        return viewServer.stop();
    }

    public boolean isViewServerRunning() {
        ViewServer viewServer;
        return !isSystemSecure() && checkCallingPermission("android.permission.DUMP", "isViewServerRunning") && (viewServer = this.mViewServer) != null && viewServer.isRunning();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean viewServerListWindows(Socket client) {
        if (isSystemSecure()) {
            return false;
        }
        final ArrayList<WindowState> windows = new ArrayList<>();
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$Yf21B7QM1fRVFGIQy6MImYjka28
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
            } catch (Exception e) {
                if (out == null) {
                    return false;
                }
                out.close();
                return false;
            } catch (Throwable th) {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e2) {
                    }
                }
                throw th;
            }
        } catch (IOException e3) {
            return false;
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
                    parameters = index < parameters.length() ? parameters.substring(index + 1) : "";
                    window = findWindow(hashCode);
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
                        } catch (IOException e) {
                        }
                    }
                }
            } catch (Exception e2) {
                Slog.w(TAG, "Could not send command " + command + " with parameters " + parameters, e2);
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
        } catch (IOException e3) {
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
        data.recycle();
        reply.recycle();
        if (out != null) {
            out.close();
        }
        return success;
    }

    public void addWindowChangeListener(WindowChangeListener listener) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                window = this.mRoot.getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$tOeHm8ndyhv8iLNQ_GHuZ7HhJdw
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return WindowManagerService.lambda$findWindow$5(hashCode, (WindowState) obj);
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
    public static /* synthetic */ boolean lambda$findWindow$5(int hashCode, WindowState w) {
        return System.identityHashCode(w) == hashCode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendNewConfiguration(int displayId) {
        try {
            boolean configUpdated = this.mActivityTaskManager.updateDisplayOverrideConfiguration((Configuration) null, displayId);
            if (!configUpdated) {
                synchronized (this.mGlobalLock) {
                    boostPriorityForLockedSection();
                    DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                    if (dc != null && dc.mWaitingForConfig) {
                        dc.mWaitingForConfig = false;
                        this.mLastFinishedFreezeSource = "config-unchanged";
                        dc.setLayoutNeeded();
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mEventDispatchingEnabled = enabled;
                if (this.mDisplayEnabled) {
                    this.mInputManagerCallback.setEventDispatchingLw(enabled);
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
        synchronized (this.mGlobalLock) {
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
        return this.mRoot.getTopFocusedDisplayContent().mCurrentFocus;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getImeFocusStackLocked() {
        DisplayContent topFocusedDisplay = this.mRoot.getTopFocusedDisplayContent();
        AppWindowToken focusedApp = topFocusedDisplay.mFocusedApp;
        if (focusedApp == null || focusedApp.getTask() == null) {
            return null;
        }
        return focusedApp.getTask().mStack;
    }

    public boolean detectSafeMode() {
        boolean z;
        if (!this.mInputManagerCallback.waitForInputDevicesReady(1000L)) {
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
                SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, "");
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                if (this.mMaxUiWidth > 0) {
                    this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$_tfpDlf3MkHSDi8MNIOlvGgvLS8
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            WindowManagerService.this.lambda$displayReady$6$WindowManagerService((DisplayContent) obj);
                        }
                    });
                }
                boolean changed = applyForcedPropertiesForDefaultDisplay();
                this.mAnimator.ready();
                this.mDisplayReady = true;
                if (changed) {
                    reconfigureDisplayLocked(getDefaultDisplayContentLocked());
                }
                this.mIsTouchDevice = this.mContext.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        try {
            this.mActivityTaskManager.updateConfiguration((Configuration) null);
        } catch (RemoteException e) {
        }
        updateCircularDisplayMaskIfNeeded();
    }

    public /* synthetic */ void lambda$displayReady$6$WindowManagerService(DisplayContent displayContent) {
        displayContent.setMaxUiWidth(this.mMaxUiWidth);
    }

    public void systemReady() {
        this.mSystemReady = true;
        this.mPolicy.systemReady();
        this.mRoot.forAllDisplayPolicies(new Consumer() { // from class: com.android.server.wm.-$$Lambda$cJE-iQ28Rv-ThCcuht9wXeFzPgo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((DisplayPolicy) obj).systemReady();
            }
        });
        this.mTaskSnapshotController.systemReady();
        this.mHasWideColorGamutSupport = queryWideColorGamutSupport();
        this.mHasHdrSupport = queryHdrSupport();
        Handler handler = UiThread.getHandler();
        final SettingsObserver settingsObserver = this.mSettingsObserver;
        Objects.requireNonNull(settingsObserver);
        handler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$iQxeP_PsHHArcPSFabJ3FXyPKNc
            @Override // java.lang.Runnable
            public final void run() {
                WindowManagerService.SettingsObserver.this.updateSystemUiSettings();
            }
        });
        Handler handler2 = UiThread.getHandler();
        final SettingsObserver settingsObserver2 = this.mSettingsObserver;
        Objects.requireNonNull(settingsObserver2);
        handler2.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$B58NKEOrr2mhFWeS3bqpaZnd11o
            @Override // java.lang.Runnable
            public final void run() {
                WindowManagerService.SettingsObserver.this.updatePointerLocation();
            }
        });
        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService("vrmanager"));
        if (vrManager != null) {
            try {
                boolean vrModeEnabled = vrManager.getVrModeState();
                synchronized (this.mGlobalLock) {
                    boostPriorityForLockedSection();
                    vrManager.registerListener(this.mVrStateCallbacks);
                    if (vrModeEnabled) {
                        this.mVrModeEnabled = vrModeEnabled;
                        this.mVrStateCallbacks.onVrStateChanged(vrModeEnabled);
                    }
                }
                resetPriorityAfterLockedSection();
            } catch (RemoteException e) {
            }
        }
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

    private static boolean queryHdrSupport() {
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasHdr = surfaceFlinger.hasHDRDisplay();
            if (hasHdr != null) {
                return hasHdr.value;
            }
            return false;
        } catch (RemoteException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class H extends Handler {
        public static final int ALL_WINDOWS_DRAWN = 33;
        public static final int ANIMATION_FAILSAFE = 60;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int BOOT_TIMEOUT = 23;
        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int ENABLE_SCREEN = 16;
        public static final int FORCE_GC = 15;
        public static final int NEW_ANIMATOR_SCALE = 34;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;
        public static final int ON_POINTER_DOWN_OUTSIDE_FOCUS = 62;
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
            WindowState lastFocus;
            WindowState newFocus;
            ArrayList<WindowState> losers;
            Runnable callback;
            Runnable callback2;
            boolean bootAnimationComplete;
            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                Slog.v(WindowManagerService.TAG, "handleMessage: entry what=" + msg.what);
            }
            int i = msg.what;
            if (i == 2) {
                DisplayContent displayContent = (DisplayContent) msg.obj;
                AccessibilityController accessibilityController = null;
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (WindowManagerService.this.mAccessibilityController != null && (displayContent.isDefaultDisplay || displayContent.getParentWindow() != null)) {
                            accessibilityController = WindowManagerService.this.mAccessibilityController;
                        }
                        lastFocus = displayContent.mLastFocus;
                        newFocus = displayContent.mCurrentFocus;
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                if (lastFocus == newFocus) {
                    return;
                }
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        displayContent.mLastFocus = newFocus;
                        if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                            Slog.i(WindowManagerService.TAG, "Focus moving from " + lastFocus + " to " + newFocus + " displayId=" + displayContent.getDisplayId());
                        }
                        if (newFocus != null && lastFocus != null && !newFocus.isDisplayedLw()) {
                            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                                Slog.i(WindowManagerService.TAG, "Delaying loss of focus...");
                            }
                            displayContent.mLosingFocus.add(lastFocus);
                            lastFocus = null;
                        }
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
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
                if (lastFocus != null) {
                    if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                        Slog.i(WindowManagerService.TAG, "Losing focus: " + lastFocus);
                    }
                    lastFocus.reportFocusChangedSerialized(false, WindowManagerService.this.mInTouchMode);
                }
            } else if (i == 3) {
                DisplayContent displayContent2 = (DisplayContent) msg.obj;
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        losers = displayContent2.mLosingFocus;
                        displayContent2.mLosingFocus = new ArrayList<>();
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
            } else if (i == 11) {
                DisplayContent displayContent3 = (DisplayContent) msg.obj;
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        displayContent3.onWindowFreezeTimeout();
                        WindowManagerService.this.stopFreezingDisplayLocked();
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i == 30) {
                synchronized (WindowManagerService.this.mGlobalLock) {
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
            } else if (i == 41) {
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        DisplayContent displayContent4 = WindowManagerService.this.getDefaultDisplayContentLocked();
                        displayContent4.getDockedDividerController().reevaluateVisibility(false);
                        displayContent4.adjustForImeIfNeeded();
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i == 46) {
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        for (int i3 = WindowManagerService.this.mWindowReplacementTimeouts.size() - 1; i3 >= 0; i3--) {
                            AppWindowToken token = WindowManagerService.this.mWindowReplacementTimeouts.get(i3);
                            token.onWindowReplacementTimeout();
                        }
                        WindowManagerService.this.mWindowReplacementTimeouts.clear();
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i == 58) {
                WindowManagerService.this.mAmInternal.setHasOverlayUi(msg.arg1, msg.arg2 == 1);
            } else if (i == 51) {
                int mode = msg.arg1;
                if (mode == 0) {
                    WindowManagerService windowManagerService = WindowManagerService.this;
                    windowManagerService.mWindowAnimationScaleSetting = Settings.Global.getFloat(windowManagerService.mContext.getContentResolver(), "window_animation_scale", WindowManagerService.this.mWindowAnimationScaleSetting);
                } else if (mode == 1) {
                    WindowManagerService windowManagerService2 = WindowManagerService.this;
                    windowManagerService2.mTransitionAnimationScaleSetting = Settings.Global.getFloat(windowManagerService2.mContext.getContentResolver(), "transition_animation_scale", WindowManagerService.this.mTransitionAnimationScaleSetting);
                } else if (mode == 2) {
                    WindowManagerService windowManagerService3 = WindowManagerService.this;
                    windowManagerService3.mAnimatorDurationScaleSetting = Settings.Global.getFloat(windowManagerService3.mContext.getContentResolver(), "animator_duration_scale", WindowManagerService.this.mAnimatorDurationScaleSetting);
                    WindowManagerService.this.dispatchNewAnimatorScaleLocked(null);
                }
            } else if (i == 52) {
                WindowState window = (WindowState) msg.obj;
                synchronized (WindowManagerService.this.mGlobalLock) {
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
            } else if (i == 54) {
                DisplayContent displayContent5 = (DisplayContent) msg.obj;
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        displayContent5.onSeamlessRotationTimeout();
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i != 55) {
                switch (i) {
                    case 14:
                        Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "window_animation_scale", WindowManagerService.this.mWindowAnimationScaleSetting);
                        Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "transition_animation_scale", WindowManagerService.this.mTransitionAnimationScaleSetting);
                        Settings.Global.putFloat(WindowManagerService.this.mContext.getContentResolver(), "animator_duration_scale", WindowManagerService.this.mAnimatorDurationScaleSetting);
                        break;
                    case 15:
                        synchronized (WindowManagerService.this.mGlobalLock) {
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
                        synchronized (WindowManagerService.this.mGlobalLock) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                Slog.w(WindowManagerService.TAG, "App freeze timeout expired.");
                                WindowManagerService.this.mWindowsFreezingScreen = 2;
                                for (int i4 = WindowManagerService.this.mAppFreezeListeners.size() - 1; i4 >= 0; i4--) {
                                    WindowManagerService.this.mAppFreezeListeners.get(i4).onAppFreezeTimeout();
                                }
                            } finally {
                                WindowManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        break;
                    case 18:
                        DisplayContent displayContent6 = (DisplayContent) msg.obj;
                        removeMessages(18, displayContent6);
                        if (displayContent6.isReady()) {
                            WindowManagerService.this.sendNewConfiguration(displayContent6.getDisplayId());
                            break;
                        } else if (WindowManagerDebugConfig.DEBUG_CONFIGURATION) {
                            String reason = displayContent6.getParent() == null ? "detached" : "unready";
                            Slog.w(WindowManagerService.TAG, "Trying to send configuration to " + reason + " display=" + displayContent6);
                            break;
                        }
                        break;
                    case 19:
                        if (WindowManagerService.this.mWindowsChanged) {
                            synchronized (WindowManagerService.this.mGlobalLock) {
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
                    default:
                        switch (i) {
                            case 22:
                                WindowManagerService.this.notifyHardKeyboardStatusChange();
                                break;
                            case 23:
                                WindowManagerService.this.performBootTimeout();
                                break;
                            case WAITING_FOR_DRAWN_TIMEOUT /* 24 */:
                                synchronized (WindowManagerService.this.mGlobalLock) {
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
                            default:
                                switch (i) {
                                    case 32:
                                        try {
                                            WindowManagerService.this.mActivityTaskManager.notifyActivityDrawn((IBinder) msg.obj);
                                            break;
                                        } catch (RemoteException e) {
                                            break;
                                        }
                                    case 33:
                                        synchronized (WindowManagerService.this.mGlobalLock) {
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
                                            } catch (RemoteException e2) {
                                                break;
                                            }
                                        } else {
                                            ArrayList<IWindowSessionCallback> callbacks = new ArrayList<>();
                                            synchronized (WindowManagerService.this.mGlobalLock) {
                                                try {
                                                    WindowManagerService.boostPriorityForLockedSection();
                                                    for (int i5 = 0; i5 < WindowManagerService.this.mSessions.size(); i5++) {
                                                        callbacks.add(WindowManagerService.this.mSessions.valueAt(i5).mCallback);
                                                    }
                                                } finally {
                                                    WindowManagerService.resetPriorityAfterLockedSection();
                                                }
                                            }
                                            WindowManagerService.resetPriorityAfterLockedSection();
                                            for (int i6 = 0; i6 < callbacks.size(); i6++) {
                                                try {
                                                    callbacks.get(i6).onAnimatorScaleChanged(scale);
                                                } catch (RemoteException e3) {
                                                }
                                            }
                                            break;
                                        }
                                    case 35:
                                        WindowManagerService.this.showCircularMask(msg.arg1 == 1);
                                        break;
                                    case 36:
                                        WindowManagerService.this.showEmulatorDisplayOverlay();
                                        break;
                                    case 37:
                                        synchronized (WindowManagerService.this.mGlobalLock) {
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
                                        synchronized (WindowManagerService.this.mGlobalLock) {
                                            try {
                                                WindowManagerService.boostPriorityForLockedSection();
                                                WindowManagerService.this.mLastANRState = null;
                                            } finally {
                                                WindowManagerService.resetPriorityAfterLockedSection();
                                            }
                                        }
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        WindowManagerService.this.mAtmInternal.clearSavedANRState();
                                        break;
                                    case 39:
                                        synchronized (WindowManagerService.this.mGlobalLock) {
                                            try {
                                                WindowManagerService.boostPriorityForLockedSection();
                                                WallpaperController wallpaperController = (WallpaperController) msg.obj;
                                                if (wallpaperController != null && wallpaperController.processWallpaperDrawPendingTimeout()) {
                                                    WindowManagerService.this.mWindowPlacerLocked.performSurfacePlacement();
                                                }
                                            } finally {
                                                WindowManagerService.resetPriorityAfterLockedSection();
                                            }
                                        }
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        break;
                                    default:
                                        switch (i) {
                                            case ANIMATION_FAILSAFE /* 60 */:
                                                synchronized (WindowManagerService.this.mGlobalLock) {
                                                    try {
                                                        WindowManagerService.boostPriorityForLockedSection();
                                                        if (WindowManagerService.this.mRecentsAnimationController != null) {
                                                            WindowManagerService.this.mRecentsAnimationController.scheduleFailsafe();
                                                        }
                                                    } finally {
                                                        WindowManagerService.resetPriorityAfterLockedSection();
                                                    }
                                                }
                                                WindowManagerService.resetPriorityAfterLockedSection();
                                                break;
                                            case RECOMPUTE_FOCUS /* 61 */:
                                                synchronized (WindowManagerService.this.mGlobalLock) {
                                                    try {
                                                        WindowManagerService.boostPriorityForLockedSection();
                                                        WindowManagerService.this.updateFocusedWindowLocked(0, true);
                                                    } finally {
                                                    }
                                                }
                                                WindowManagerService.resetPriorityAfterLockedSection();
                                                break;
                                            case ON_POINTER_DOWN_OUTSIDE_FOCUS /* 62 */:
                                                synchronized (WindowManagerService.this.mGlobalLock) {
                                                    try {
                                                        WindowManagerService.boostPriorityForLockedSection();
                                                        IBinder touchedToken = (IBinder) msg.obj;
                                                        WindowManagerService.this.onPointerDownOutsideFocusLocked(touchedToken);
                                                    } finally {
                                                    }
                                                }
                                                WindowManagerService.resetPriorityAfterLockedSection();
                                                break;
                                        }
                                }
                        }
                }
            } else {
                synchronized (WindowManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        WindowManagerService.this.restorePointerIconLocked((DisplayContent) msg.obj, msg.arg1, msg.arg2);
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                Slog.v(WindowManagerService.TAG, "handleMessage: exit");
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void sendNewMessageDelayed(int what, Object obj, long delayMillis) {
            removeMessages(what, obj);
            sendMessageDelayed(obtainMessage(what, obj), delayMillis);
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

    public IWindowSession openSession(IWindowSessionCallback callback) {
        return new Session(this, callback);
    }

    public void getInitialDisplaySize(int displayId, Point size) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedSize(width, height);
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
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedScalingMode(mode);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean applyForcedPropertiesForDefaultDisplay() {
        int pos;
        boolean changed = false;
        DisplayContent displayContent = getDefaultDisplayContentLocked();
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
                    changed = true;
                }
            } catch (NumberFormatException e) {
            }
        }
        int density = getForcedDisplayDensityForUserLocked(this.mCurrentUserId);
        if (density != 0 && density != displayContent.mBaseDisplayDensity) {
            displayContent.mBaseDisplayDensity = density;
            changed = true;
        }
        int mode = Settings.Global.getInt(this.mContext.getContentResolver(), "display_scaling_force", 0);
        if (displayContent.mDisplayScalingDisabled != (mode != 0)) {
            Slog.i(TAG, "FORCED DISPLAY SCALING DISABLED");
            displayContent.mDisplayScalingDisabled = true;
            return true;
        }
        return changed;
    }

    public void clearForcedDisplaySize(int displayId) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedSize(displayContent.mInitialDisplayWidth, displayContent.mInitialDisplayHeight);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public int getInitialDisplayDensity(int displayId) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        int targetUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "setForcedDisplayDensityForUser", null);
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(density, targetUserId);
                }
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
        int callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "clearForcedDisplayDensityForUser", null);
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(displayContent.mInitialDisplayDensity, callingUserId);
                }
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reconfigureDisplayLocked(DisplayContent displayContent) {
        if (!displayContent.isReady()) {
            return;
        }
        displayContent.configureDisplayPolicy();
        displayContent.setLayoutNeeded();
        boolean configChanged = displayContent.updateOrientationFromAppTokens();
        Configuration currentDisplayConfig = displayContent.getConfiguration();
        this.mTempConfiguration.setTo(currentDisplayConfig);
        displayContent.computeScreenConfiguration(this.mTempConfiguration);
        if (configChanged | (currentDisplayConfig.diff(this.mTempConfiguration) != 0)) {
            displayContent.mWaitingForConfig = true;
            startFreezingDisplayLocked(0, 0, displayContent);
            displayContent.sendNewConfiguration();
        }
        this.mWindowPlacerLocked.performSurfacePlacement();
    }

    public void setOverscan(int displayId, int left, int top, int right, int bottom) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != 0) {
            throw new SecurityException("Must hold permission android.permission.WRITE_SECURE_SETTINGS");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
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
        this.mDisplayWindowSettings.setOverscanLocked(displayInfo, left, top, right, bottom);
        reconfigureDisplayLocked(displayContent);
    }

    public void startWindowTrace() {
        this.mWindowTracing.startTrace(null);
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
                this.mH.sendNewMessageDelayed(11, w.getDisplayContent(), 2000L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkDrawnWindowsLocked() {
        if (this.mWaitingForDrawn.isEmpty() || this.mWaitingForDrawnCallback == null) {
            return;
        }
        int j = this.mWaitingForDrawn.size();
        while (true) {
            j--;
            if (j < 0) {
                break;
            }
            WindowState win = this.mWaitingForDrawn.get(j);
            if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
                Slog.i(TAG, "Waiting for drawn " + win + ": removed=" + win.mRemoved + " visible=" + win.isVisibleLw() + " mHasSurface=" + win.mHasSurface + " drawState=" + win.mWinAnimator.mDrawState);
            }
            if (win.mRemoved || !win.mHasSurface || !win.isVisibleByPolicy()) {
                if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
                    Slog.w(TAG, "Aborted waiting for drawn: " + win);
                }
                this.mWaitingForDrawn.remove(win);
            } else if (win.hasDrawnLw()) {
                if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
                    Slog.d(TAG, "Window drawn win=" + win);
                }
                this.mWaitingForDrawn.remove(win);
            }
        }
        if (this.mWaitingForDrawn.isEmpty()) {
            if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
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
        synchronized (this.mGlobalLock) {
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
        WindowAnimator windowAnimator = this.mAnimator;
        if (windowAnimator != null) {
            windowAnimator.scheduleAnimation();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        Trace.traceBegin(32L, "wmUpdateFocus");
        boolean changed = this.mRoot.updateFocusedWindowLocked(mode, updateInputWindows);
        Trace.traceEnd(32L);
        return changed;
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
        this.mInputManagerCallback.freezeInputDispatchingLw();
        if (displayContent.mAppTransition.isTransitionSet()) {
            displayContent.mAppTransition.freeze();
        }
        Slog.i(TAG, "startFreezingDisplayLocked: exitAnim=" + exitAnim + " enterAnim=" + enterAnim + " displayId=" + this.mFrozenDisplayId);
        this.mLatencyTracker.onActionStart(6);
        if (CUSTOM_SCREEN_ROTATION) {
            this.mExitAnimId = exitAnim;
            this.mEnterAnimId = enterAnim;
            ScreenRotationAnimation screenRotationAnimation = this.mAnimator.getScreenRotationAnimationLocked(this.mFrozenDisplayId);
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
            }
            boolean isSecure = displayContent.hasSecureWindowOnScreen();
            displayContent.updateDisplayInfo();
            this.mAnimator.setScreenRotationAnimationLocked(this.mFrozenDisplayId, new ScreenRotationAnimation(this.mContext, displayContent, displayContent.getDisplayRotation().isFixedToUserRotation(), isSecure, this));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:62:0x0136  */
    /* JADX WARN: Removed duplicated region for block: B:65:0x0142  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void stopFreezingDisplayLocked() {
        /*
            Method dump skipped, instructions count: 395
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.stopFreezingDisplayLocked():void");
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
                } catch (FileNotFoundException e) {
                    if (ind != null) {
                        ind.close();
                    } else if (in != null) {
                        in.close();
                    }
                } catch (IOException e2) {
                    if (ind != null) {
                        ind.close();
                    } else if (in != null) {
                        in.close();
                    }
                } catch (Throwable th) {
                    if (ind != null) {
                        try {
                            ind.close();
                        } catch (IOException e3) {
                        }
                    } else if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e4) {
                        }
                    }
                    throw th;
                }
            } catch (IOException e5) {
            }
        } catch (IOException e6) {
        }
    }

    public void setRecentsVisibility(boolean visible) {
        this.mAtmInternal.enforceCallerIsRecentsOrHasPermission("android.permission.STATUS_BAR", "setRecentsVisibility()");
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        this.mAtmInternal.enforceCallerIsRecentsOrHasPermission("android.permission.STATUS_BAR", "setShelfHeight()");
        synchronized (this.mGlobalLock) {
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

    public void statusBarVisibilityChanged(int displayId, int visibility) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.STATUS_BAR") != 0) {
            throw new SecurityException("Caller does not hold permission android.permission.STATUS_BAR");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.statusBarVisibilityChanged(visibility);
                } else {
                    Slog.w(TAG, "statusBarVisibilityChanged with invalid displayId=" + displayId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setForceShowSystemBars(boolean show) {
        boolean isAutomotive = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
        if (!isAutomotive) {
            throw new UnsupportedOperationException("Force showing system bars is only supportedfor Automotive use cases.");
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.STATUS_BAR") != 0) {
            throw new SecurityException("Caller does not hold permission android.permission.STATUS_BAR");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$XcHmyRxMY5ULhjLiV-sIKnPtvOM
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((DisplayPolicy) obj).setForceShowSystemBars(((Boolean) obj2).booleanValue());
                    }
                }, PooledLambda.__(), Boolean.valueOf(show)));
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
        synchronized (this.mGlobalLock) {
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

    public int getNavBarPosition(int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "getNavBarPosition with invalid displayId=" + displayId + " callers=" + Debug.getCallers(3));
                    resetPriorityAfterLockedSection();
                    return -1;
                }
                displayContent.performLayout(false, false);
                int navBarPosition = displayContent.getDisplayPolicy().getNavBarPosition();
                resetPriorityAfterLockedSection();
                return navBarPosition;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs
    public WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    WindowManagerPolicy.InputConsumer createInputConsumer = displayContent.getInputMonitor().createInputConsumer(looper, name, inputEventReceiverFactory);
                    resetPriorityAfterLockedSection();
                    return createInputConsumer;
                }
                resetPriorityAfterLockedSection();
                return null;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void createInputConsumer(IBinder token, String name, int displayId, InputChannel inputChannel) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display != null) {
                    display.getInputMonitor().createInputConsumer(token, name, inputChannel, Binder.getCallingPid(), Binder.getCallingUserHandle());
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean destroyInputConsumer(String name, int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent display = this.mRoot.getDisplayContent(displayId);
                if (display != null) {
                    boolean destroyInputConsumer = display.getInputMonitor().destroyInputConsumer(name);
                    resetPriorityAfterLockedSection();
                    return destroyInputConsumer;
                }
                resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public Region getCurrentImeTouchRegion() {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.RESTRICTED_VR_ACCESS") != 0) {
            throw new SecurityException("getCurrentImeTouchRegion is restricted to VR services");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                Region r = new Region();
                for (int i = this.mRoot.mChildren.size() - 1; i >= 0; i--) {
                    DisplayContent displayContent = (DisplayContent) this.mRoot.mChildren.get(i);
                    if (displayContent.mInputMethodWindow != null) {
                        displayContent.mInputMethodWindow.getTouchableRegion(r);
                        resetPriorityAfterLockedSection();
                        return r;
                    }
                }
                resetPriorityAfterLockedSection();
                return r;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean hasNavigationBar(int displayId) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    resetPriorityAfterLockedSection();
                    return false;
                }
                boolean hasNavigationBar = dc.getDisplayPolicy().hasNavigationBar();
                resetPriorityAfterLockedSection();
                return hasNavigationBar;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                AppWindowToken appWindow = this.mRoot.getAppWindowToken(token);
                if (appWindow != null) {
                    appWindow.getDisplayContent().mUnknownAppVisibilityController.notifyAppResumedFinished(appWindow);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void notifyTaskRemovedFromRecents(int taskId, int userId) {
        synchronized (this.mGlobalLock) {
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
    }

    private void dumpHighRefreshRateBlacklist(PrintWriter pw) {
        pw.println("WINDOW MANAGER HIGH REFRESH RATE BLACKLIST (dumpsys window refresh)");
        this.mHighRefreshRateBlacklist.dump(pw);
    }

    private void dumpTraceStatus(PrintWriter pw) {
        pw.println("WINDOW MANAGER TRACE (dumpsys window trace)");
        pw.print(this.mWindowTracing.getStatus() + "\n");
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
    public void writeToProtoLocked(ProtoOutputStream proto, int logLevel) {
        this.mPolicy.writeToProto(proto, 1146756268033L);
        this.mRoot.writeToProto(proto, 1146756268034L, logLevel);
        DisplayContent topFocusedDisplayContent = this.mRoot.getTopFocusedDisplayContent();
        if (topFocusedDisplayContent.mCurrentFocus != null) {
            topFocusedDisplayContent.mCurrentFocus.writeIdentifierToProto(proto, 1146756268035L);
        }
        if (topFocusedDisplayContent.mFocusedApp != null) {
            topFocusedDisplayContent.mFocusedApp.writeNameToProto(proto, 1138166333444L);
        }
        WindowState imeWindow = this.mRoot.getCurrentInputMethodWindow();
        if (imeWindow != null) {
            imeWindow.writeIdentifierToProto(proto, 1146756268037L);
        }
        proto.write(1133871366150L, this.mDisplayFrozen);
        DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
        proto.write(1120986464263L, defaultDisplayContent.getRotation());
        proto.write(1120986464264L, defaultDisplayContent.getLastOrientation());
    }

    private void dumpWindowsLocked(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
        dumpWindowsNoHeaderLocked(pw, dumpAll, windows);
    }

    private void dumpWindowsNoHeaderLocked(final PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
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
        ArrayList<WindowState> arrayList = this.mForceRemoves;
        if (arrayList != null && arrayList.size() > 0) {
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
        if (this.mResizingWindows.size() > 0) {
            pw.println();
            pw.println("  Windows waiting to resize:");
            for (int i5 = this.mResizingWindows.size() - 1; i5 >= 0; i5--) {
                WindowState w5 = this.mResizingWindows.get(i5);
                if (windows == null || windows.contains(w5)) {
                    pw.print("  Resizing #");
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
        if (this.mWaitingForDrawn.size() > 0) {
            pw.println();
            pw.println("  Clients waiting for these windows to be drawn:");
            for (int i6 = this.mWaitingForDrawn.size() - 1; i6 >= 0; i6--) {
                WindowState win = this.mWaitingForDrawn.get(i6);
                pw.print("  Waiting #");
                pw.print(i6);
                pw.print(' ');
                pw.print(win);
            }
        }
        pw.println();
        pw.print("  mGlobalConfiguration=");
        pw.println(this.mRoot.getConfiguration());
        pw.print("  mHasPermanentDpad=");
        pw.println(this.mHasPermanentDpad);
        this.mRoot.dumpTopFocusedDisplayId(pw);
        this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$pgbw_FPqeLJMP83kqiaVcOei-Ds
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                WindowManagerService.lambda$dumpWindowsNoHeaderLocked$7(pw, (DisplayContent) obj);
            }
        });
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
        this.mInputManagerCallback.dump(pw, "  ");
        this.mTaskSnapshotController.dump(pw, "  ");
        if (dumpAll) {
            WindowState imeWindow = this.mRoot.getCurrentInputMethodWindow();
            if (imeWindow != null) {
                pw.print("  mInputMethodWindow=");
                pw.println(imeWindow);
            }
            this.mWindowPlacerLocked.dump(pw, "  ");
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
            DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
            pw.print("  mRotation=");
            pw.print(defaultDisplayContent.getRotation());
            pw.print("  mLastWindowForcedOrientation=");
            pw.print(defaultDisplayContent.getLastWindowForcedOrientation());
            pw.print(" mLastOrientation=");
            pw.println(defaultDisplayContent.getLastOrientation());
            pw.print(" waitingForConfig=");
            pw.println(defaultDisplayContent.mWaitingForConfig);
            pw.print("  Animation settings: disabled=");
            pw.print(this.mAnimationsDisabled);
            pw.print(" window=");
            pw.print(this.mWindowAnimationScaleSetting);
            pw.print(" transition=");
            pw.print(this.mTransitionAnimationScaleSetting);
            pw.print(" animator=");
            pw.println(this.mAnimatorDurationScaleSetting);
            if (this.mRecentsAnimationController != null) {
                pw.print("  mRecentsAnimationController=");
                pw.println(this.mRecentsAnimationController);
                this.mRecentsAnimationController.dump(pw, "    ");
            }
            PolicyControl.dump("  ", pw);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dumpWindowsNoHeaderLocked$7(PrintWriter pw, DisplayContent dc) {
        WindowState inputMethodTarget = dc.mInputMethodTarget;
        if (inputMethodTarget != null) {
            pw.print("  mInputMethodTarget in display# ");
            pw.print(dc.getDisplayId());
            pw.print(' ');
            pw.println(inputMethodTarget);
        }
    }

    private boolean dumpWindows(PrintWriter pw, String name, String[] args, int opti, boolean dumpAll) {
        final ArrayList<WindowState> windows = new ArrayList<>();
        if ("apps".equals(name) || "visible".equals(name) || "visible-apps".equals(name)) {
            final boolean appsOnly = name.contains("apps");
            final boolean visibleOnly = name.contains("visible");
            synchronized (this.mGlobalLock) {
                try {
                    boostPriorityForLockedSection();
                    if (appsOnly) {
                        this.mRoot.dumpDisplayContents(pw);
                    }
                    this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$C4RecYWtrllidEGWyvVvRsY6lno
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            WindowManagerService.lambda$dumpWindows$8(visibleOnly, appsOnly, windows, (WindowState) obj);
                        }
                    }, true);
                } finally {
                    resetPriorityAfterLockedSection();
                }
            }
            resetPriorityAfterLockedSection();
        } else {
            synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
    public static /* synthetic */ void lambda$dumpWindows$8(boolean visibleOnly, boolean appsOnly, ArrayList windows, WindowState w) {
        if (!visibleOnly || w.mWinAnimator.getShown()) {
            if (!appsOnly || w.mAppToken != null) {
                windows.add(w);
            }
        }
    }

    private void dumpLastANRLocked(PrintWriter pw) {
        pw.println("WINDOW MANAGER LAST ANR (dumpsys window lastanr)");
        String str = this.mLastANRState;
        if (str == null) {
            pw.println("  <no ANR has occurred since boot>");
        } else {
            pw.println(str);
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
        for (int i = this.mRoot.getChildCount() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mRoot.getChildAt(i);
            int displayId = dc.getDisplayId();
            if (!dc.mWinAddedSinceNullFocus.isEmpty()) {
                pw.println("  Windows added in display #" + displayId + " since null focus: " + dc.mWinAddedSinceNullFocus);
            }
            if (!dc.mWinRemovedSinceNullFocus.isEmpty()) {
                pw.println("  Windows removed in display #" + displayId + " since null focus: " + dc.mWinRemovedSinceNullFocus);
            }
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
    /* JADX WARN: Code restructure failed: missing block: B:25:0x00bc, code lost:
        r2 = new android.util.proto.ProtoOutputStream(r11);
        r4 = r10.mGlobalLock;
     */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x00c3, code lost:
        monitor-enter(r4);
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x00c4, code lost:
        boostPriorityForLockedSection();
        writeToProtoLocked(r2, 0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00ca, code lost:
        monitor-exit(r4);
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00cb, code lost:
        resetPriorityAfterLockedSection();
        r2.flush();
     */
    /* JADX WARN: Code restructure failed: missing block: B:30:0x00d1, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x00d2, code lost:
        r3 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x00d7, code lost:
        throw r3;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void doDump(java.io.FileDescriptor r11, final java.io.PrintWriter r12, java.lang.String[] r13, boolean r14) {
        /*
            Method dump skipped, instructions count: 769
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowManagerService.doDump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[], boolean):void");
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mGlobalLock) {
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

    public void onOverlayChanged() {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$oXZopy-e9ykF6MR6QjHAIi3bGRc
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        WindowManagerService.lambda$onOverlayChanged$10((DisplayContent) obj);
                    }
                });
                requestTraversal();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onOverlayChanged$10(DisplayContent displayContent) {
        displayContent.getDisplayPolicy().onOverlayChangedLw();
        displayContent.updateDisplayInfo();
    }

    public void onDisplayChanged(int displayId) {
        synchronized (this.mGlobalLock) {
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
        return this.mGlobalLock;
    }

    public void setWillReplaceWindow(IBinder token, boolean animate) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = getDefaultDisplayContentLocked();
                dc.getDockedDividerController().setTouchRegion(touchRegion);
                dc.updateTouchExcludeRegion();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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

    public void setSupportsFreeformWindowManagement(boolean supportsFreeformWindowManagement) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mSupportsFreeformWindowManagement = supportsFreeformWindowManagement;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    void setForceDesktopModeOnExternalDisplays(boolean forceDesktopModeOnExternalDisplays) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mForceDesktopModeOnExternalDisplays = forceDesktopModeOnExternalDisplays;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setIsPc(boolean isPc) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mIsPc = isPc;
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
        this.mAtmInternal.enforceCallerIsRecentsOrHasPermission("android.permission.REGISTER_WINDOW_MANAGER_LISTENERS", "registerDockedStackListener()");
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
        synchronized (this.mGlobalLock) {
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
            dc.getDisplayPolicy().getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, di.displayCutout, outInsets);
        }
    }

    public void setForwardedInsets(int displayId, Insets insets) throws RemoteException {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                int callingUid = Binder.getCallingUid();
                int displayOwnerUid = dc.getDisplay().getOwnerUid();
                if (callingUid != displayOwnerUid) {
                    throw new SecurityException("Only owner of the display can set ForwardedInsets to it.");
                }
                dc.setForwardedInsets(insets);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void intersectDisplayInsetBounds(Rect display, Rect insets, Rect inOutBounds) {
        this.mTmpRect3.set(display);
        this.mTmpRect3.inset(insets);
        inOutBounds.intersect(this.mTmpRect3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
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
                synchronized (this.mGlobalLock) {
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

    private void checkCallerOwnsDisplay(int displayId) {
        Display display = this.mDisplayManager.getDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Cannot find display for non-existent displayId: " + displayId);
        }
        int callingUid = Binder.getCallingUid();
        int displayOwnerUid = display.getOwnerUid();
        if (callingUid != displayOwnerUid) {
            throw new SecurityException("The caller doesn't own the display.");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparentDisplayContent(IWindow client, SurfaceControl sc, int displayId) {
        checkCallerOwnsDisplay(displayId);
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                long token = Binder.clearCallingIdentity();
                WindowState win = windowForClientLocked((Session) null, client, false);
                if (win == null) {
                    Slog.w(TAG, "Bad requesting window " + client);
                    Binder.restoreCallingIdentity(token);
                    resetPriorityAfterLockedSection();
                    return;
                }
                getDisplayContentOrCreate(displayId, null).reparentDisplayContent(win, sc);
                Binder.restoreCallingIdentity(token);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateDisplayContentLocation(IWindow client, int x, int y, int displayId) {
        checkCallerOwnsDisplay(displayId);
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                long token = Binder.clearCallingIdentity();
                WindowState win = windowForClientLocked((Session) null, client, false);
                if (win == null) {
                    Slog.w(TAG, "Bad requesting window " + client);
                    Binder.restoreCallingIdentity(token);
                    resetPriorityAfterLockedSection();
                    return;
                }
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.updateLocation(win, x, y);
                }
                Binder.restoreCallingIdentity(token);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTapExcludeRegion(IWindow client, int regionId, Region region) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                WindowState callingWin = windowForClientLocked((Session) null, client, false);
                if (callingWin == null) {
                    Slog.w(TAG, "Bad requesting window " + client);
                    resetPriorityAfterLockedSection();
                    return;
                }
                callingWin.updateTapExcludeRegion(regionId, region);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void dontOverrideDisplayInfo(int displayId) {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                boostPriorityForLockedSection();
                DisplayContent dc = getDisplayContentOrCreate(displayId, null);
                if (dc == null) {
                    throw new IllegalArgumentException("Trying to configure a non existent display.");
                }
                dc.mShouldOverrideDisplayConfiguration = false;
                this.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, (DisplayInfo) null);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public int getWindowingMode(int displayId) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "getWindowingMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to get windowing mode of a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return 0;
                }
                int windowingModeLocked = this.mDisplayWindowSettings.getWindowingModeLocked(displayContent);
                resetPriorityAfterLockedSection();
                return windowingModeLocked;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setWindowingMode(int displayId, int mode) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "setWindowingMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to set windowing mode to a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                int lastWindowingMode = displayContent.getWindowingMode();
                this.mDisplayWindowSettings.setWindowingModeLocked(displayContent, mode);
                reconfigureDisplayLocked(displayContent);
                if (lastWindowingMode != displayContent.getWindowingMode()) {
                    this.mH.removeMessages(18);
                    long origId = Binder.clearCallingIdentity();
                    sendNewConfiguration(displayId);
                    Binder.restoreCallingIdentity(origId);
                    displayContent.executeAppTransition();
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @WindowManager.RemoveContentMode
    public int getRemoveContentMode(int displayId) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "getRemoveContentMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to get remove mode of a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return 0;
                }
                int removeContentModeLocked = this.mDisplayWindowSettings.getRemoveContentModeLocked(displayContent);
                resetPriorityAfterLockedSection();
                return removeContentModeLocked;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setRemoveContentMode(int displayId, @WindowManager.RemoveContentMode int mode) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "setRemoveContentMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to set remove mode to a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                this.mDisplayWindowSettings.setRemoveContentModeLocked(displayContent, mode);
                reconfigureDisplayLocked(displayContent);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean shouldShowWithInsecureKeyguard(int displayId) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "shouldShowWithInsecureKeyguard()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to get flag of a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return false;
                }
                boolean shouldShowWithInsecureKeyguardLocked = this.mDisplayWindowSettings.shouldShowWithInsecureKeyguardLocked(displayContent);
                resetPriorityAfterLockedSection();
                return shouldShowWithInsecureKeyguardLocked;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "setShouldShowWithInsecureKeyguard()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to set flag to a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return;
                }
                this.mDisplayWindowSettings.setShouldShowWithInsecureKeyguardLocked(displayContent, shouldShow);
                reconfigureDisplayLocked(displayContent);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean shouldShowSystemDecors(int displayId) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "shouldShowSystemDecors()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to get system decors flag of a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return false;
                } else if (displayContent.isUntrustedVirtualDisplay()) {
                    resetPriorityAfterLockedSection();
                    return false;
                } else {
                    boolean supportsSystemDecorations = displayContent.supportsSystemDecorations();
                    resetPriorityAfterLockedSection();
                    return supportsSystemDecorations;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setShouldShowSystemDecors(int displayId, boolean shouldShow) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "setShouldShowSystemDecors()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to set system decors flag to a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                } else if (displayContent.isUntrustedVirtualDisplay()) {
                    throw new SecurityException("Attempted to set system decors flag to an untrusted virtual display: " + displayId);
                } else {
                    this.mDisplayWindowSettings.setShouldShowSystemDecorsLocked(displayContent, shouldShow);
                    reconfigureDisplayLocked(displayContent);
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean shouldShowIme(int displayId) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "shouldShowIme()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
                boolean z = false;
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to get IME flag of a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                    return false;
                } else if (displayContent.isUntrustedVirtualDisplay()) {
                    resetPriorityAfterLockedSection();
                    return false;
                } else {
                    z = (this.mDisplayWindowSettings.shouldShowImeLocked(displayContent) || this.mForceDesktopModeOnExternalDisplays) ? true : true;
                    resetPriorityAfterLockedSection();
                    return z;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setShouldShowIme(int displayId, boolean shouldShow) {
        if (!checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "setShouldShowIme()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    Slog.w(TAG, "Attempted to set IME flag to a display that does not exist: " + displayId);
                    resetPriorityAfterLockedSection();
                } else if (displayContent.isUntrustedVirtualDisplay()) {
                    throw new SecurityException("Attempted to set IME flag to an untrusted virtual display: " + displayId);
                } else {
                    this.mDisplayWindowSettings.setShouldShowImeLocked(displayContent, shouldShow);
                    reconfigureDisplayLocked(displayContent);
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
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
        if (seamlesslyRotated == w.mSeamlesslyRotated || w.mForceSeamlesslyRotate) {
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
            w.getDisplayContent().updateRotationAndSendNewConfigIfNeeded();
        }
    }

    /* loaded from: classes2.dex */
    private final class LocalService extends WindowManagerInternal {
        private LocalService() {
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void requestTraversalFromDisplayManager() {
            WindowManagerService.this.requestTraversal();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setMagnificationSpec(int displayId, MagnificationSpec spec) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.setMagnificationSpecLocked(displayId, spec);
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
        public void setForceShowMagnifiableBounds(int displayId, boolean show) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.setForceShowMagnifiableBoundsLocked(displayId, show);
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
        public void getMagnificationRegion(int displayId, Region magnificationRegion) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController != null) {
                        WindowManagerService.this.mAccessibilityController.getMagnificationRegionLocked(displayId, magnificationRegion);
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
            synchronized (WindowManagerService.this.mGlobalLock) {
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
        public boolean setMagnificationCallbacks(int displayId, WindowManagerInternal.MagnificationCallbacks callbacks) {
            boolean result;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerService.this.mAccessibilityController == null) {
                        WindowManagerService.this.mAccessibilityController = new AccessibilityController(WindowManagerService.this);
                    }
                    result = WindowManagerService.this.mAccessibilityController.setMagnificationCallbacksLocked(displayId, callbacks);
                    if (!WindowManagerService.this.mAccessibilityController.hasCallbacksLocked()) {
                        WindowManagerService.this.mAccessibilityController = null;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return result;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setWindowsForAccessibilityCallback(WindowManagerInternal.WindowsForAccessibilityCallback callback) {
            synchronized (WindowManagerService.this.mGlobalLock) {
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
            synchronized (WindowManagerService.this.mGlobalLock) {
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
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState windowState = WindowManagerService.this.mWindowMap.get(token);
                    if (windowState != null) {
                        outBounds.set(windowState.getFrameLw());
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
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mWaitingForDrawnCallback = callback;
                    WindowManagerService.this.getDefaultDisplayContentLocked().waitForAllWindowsDrawn();
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
        public void setForcedDisplaySize(int displayId, int width, int height) {
            WindowManagerService.this.setForcedDisplaySize(displayId, width, height);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void clearForcedDisplaySize(int displayId) {
            WindowManagerService.this.clearForcedDisplaySize(displayId);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void addWindowToken(IBinder token, int type, int displayId) {
            WindowManagerService.this.addWindowToken(token, type, displayId);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void removeWindowToken(IBinder binder, boolean removeWindows, int displayId) {
            synchronized (WindowManagerService.this.mGlobalLock) {
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
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.getDefaultDisplayContentLocked().mAppTransition.registerListenerLocked(listener);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void reportPasswordChanged(int userId) {
            WindowManagerService.this.mKeyguardDisableHandler.updateKeyguardEnabled(userId);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public int getInputMethodWindowVisibleHeight(int displayId) {
            int inputMethodWindowVisibleHeight;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    DisplayContent dc = WindowManagerService.this.mRoot.getDisplayContent(displayId);
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
        public void updateInputMethodWindowStatus(IBinder imeToken, boolean imeWindowVisible, boolean dismissImeOnBackKeyPressed) {
            WindowManagerService.this.mPolicy.setDismissImeOnBackKeyPressed(dismissImeOnBackKeyPressed);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void updateInputMethodTargetWindow(IBinder imeToken, IBinder imeTargetWindowToken) {
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                Slog.w(WindowManagerService.TAG, "updateInputMethodTargetWindow: imeToken=" + imeToken + " imeTargetWindowToken=" + imeTargetWindowToken);
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isHardKeyboardAvailable() {
            boolean z;
            synchronized (WindowManagerService.this.mGlobalLock) {
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
            synchronized (WindowManagerService.this.mGlobalLock) {
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
        public boolean isStackVisibleLw(int windowingMode) {
            DisplayContent dc = WindowManagerService.this.getDefaultDisplayContentLocked();
            return dc.isStackVisible(windowingMode);
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void computeWindowsForAccessibility() {
            AccessibilityController accessibilityController;
            synchronized (WindowManagerService.this.mGlobalLock) {
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
                accessibilityController.performComputeChangedWindowsNotLocked(true);
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void setVr2dDisplayId(int vr2dDisplayId) {
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.d(WindowManagerService.TAG, "setVr2dDisplayId called for: " + vr2dDisplayId);
            }
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mVr2dDisplayId = vr2dDisplayId;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
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
            synchronized (WindowManagerService.this.mGlobalLock) {
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

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isUidFocused(int uid) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    for (int i = WindowManagerService.this.mRoot.getChildCount() - 1; i >= 0; i--) {
                        DisplayContent displayContent = (DisplayContent) WindowManagerService.this.mRoot.getChildAt(i);
                        if (displayContent.mCurrentFocus != null && uid == displayContent.mCurrentFocus.getOwningUid()) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isInputMethodClientFocus(int uid, int pid, int displayId) {
            if (displayId == -1) {
                return false;
            }
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    DisplayContent displayContent = WindowManagerService.this.mRoot.getTopFocusedDisplayContent();
                    if (displayContent != null && displayContent.getDisplayId() == displayId && displayContent.hasAccess(uid)) {
                        if (displayContent.isInputMethodClientFocus(uid, pid)) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                        WindowState currentFocus = displayContent.mCurrentFocus;
                        if (currentFocus != null && currentFocus.mSession.mUid == uid && currentFocus.mSession.mPid == pid) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return false;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean isUidAllowedOnDisplay(int displayId, int uid) {
            boolean z = true;
            if (displayId == 0) {
                return true;
            }
            if (displayId == -1) {
                return false;
            }
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    DisplayContent displayContent = WindowManagerService.this.mRoot.getDisplayContent(displayId);
                    if (displayContent == null || !displayContent.hasAccess(uid)) {
                        z = false;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public int getDisplayIdForWindow(IBinder windowToken) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState window = WindowManagerService.this.mWindowMap.get(windowToken);
                    if (window != null) {
                        int displayId = window.getDisplayContent().getDisplayId();
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return displayId;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return -1;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public int getTopFocusedDisplayId() {
            int displayId;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    displayId = WindowManagerService.this.mRoot.getTopFocusedDisplayContent().getDisplayId();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return displayId;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean shouldShowSystemDecorOnDisplay(int displayId) {
            boolean shouldShowSystemDecors;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    shouldShowSystemDecors = WindowManagerService.this.shouldShowSystemDecors(displayId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return shouldShowSystemDecors;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public boolean shouldShowIme(int displayId) {
            boolean shouldShowIme;
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    shouldShowIme = WindowManagerService.this.shouldShowIme(displayId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return shouldShowIme;
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void addNonHighRefreshRatePackage(final String packageName) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$LocalService$_nYJRiVOgbON7mI191FIzNAk4Xs
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            ((DisplayContent) obj).getDisplayPolicy().getRefreshRatePolicy().addNonHighRefreshRatePackage(packageName);
                        }
                    });
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.WindowManagerInternal
        public void removeNonHighRefreshRatePackage(final String packageName) {
            synchronized (WindowManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowManagerService.this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$LocalService$rEGrcIRCgYp-4kzr5xA12LKQX0E
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            ((DisplayContent) obj).getDisplayPolicy().getRefreshRatePolicy().removeNonHighRefreshRatePackage(packageName);
                        }
                    });
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void inSurfaceTransaction(Runnable exec) {
        SurfaceControl.openTransaction();
        try {
            exec.run();
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    public void disableNonVrUi(boolean disable) {
        synchronized (this.mGlobalLock) {
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
    public boolean hasHdrSupport() {
        return this.mHasHdrSupport && hasWideColorGamutSupport();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateNonSystemOverlayWindowsVisibilityIfNeeded(WindowState win, boolean surfaceShown) {
        if (!win.hideNonSystemOverlayWindowsWhenVisible() && !this.mHidingNonSystemOverlayWindows.contains(win)) {
            return;
        }
        boolean systemAlertWindowsHidden = !this.mHidingNonSystemOverlayWindows.isEmpty();
        if (surfaceShown && win.hideNonSystemOverlayWindowsWhenVisible()) {
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
        this.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$nQHccAXNqWhpUTYdUQi4f3vYirA
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).setForceHideNonSystemOverlayWindowIfNeeded(hideSystemAlertWindows);
            }
        }, false);
    }

    public void applyMagnificationSpecLocked(int displayId, MagnificationSpec spec) {
        DisplayContent displayContent = this.mRoot.getDisplayContent(displayId);
        if (displayContent != null) {
            displayContent.applyMagnificationSpec(spec);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeSurfaceBuilder(SurfaceSession s) {
        return this.mSurfaceBuilderFactory.make(s);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSeamlessRotation() {
        this.mSeamlessRotationCount = 0;
        this.mRotatingSeamlessly = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRotatingSeamlessly() {
        return this.mRotatingSeamlessly;
    }

    void finishSeamlessRotation() {
        this.mRotatingSeamlessly = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskStateChanged(int lockTaskState) {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$5zz5Ugt4wxIXoNE3lZS6NA9z_Jk
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((DisplayPolicy) obj).onLockTaskStateChangedLw(((Integer) obj2).intValue());
                    }
                }, PooledLambda.__(), Integer.valueOf(lockTaskState)));
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setAodShowing(boolean aodShowing) {
        synchronized (this.mGlobalLock) {
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

    public boolean injectInputAfterTransactionsApplied(InputEvent ev, int mode) {
        boolean isDown;
        KeyEvent keyEvent;
        if (ev instanceof KeyEvent) {
            KeyEvent keyEvent2 = (KeyEvent) ev;
            isDown = keyEvent2.getAction() == 0;
            keyEvent = keyEvent2.getAction() == 1 ? 1 : null;
        } else {
            MotionEvent motionEvent = (MotionEvent) ev;
            isDown = motionEvent.getAction() == 0;
            keyEvent = motionEvent.getAction() == 1 ? 1 : null;
        }
        boolean isMouseEvent = ev.getSource() == 8194;
        if (isDown || isMouseEvent) {
            syncInputTransactions();
        }
        boolean result = ((InputManagerInternal) LocalServices.getService(InputManagerInternal.class)).injectInputEvent(ev, mode);
        if (keyEvent != null) {
            syncInputTransactions();
        }
        return result;
    }

    public void syncInputTransactions() {
        waitForAnimationsToComplete();
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mWindowPlacerLocked.performSurfacePlacementIfScheduled();
                this.mRoot.forAllDisplays(new Consumer() { // from class: com.android.server.wm.-$$Lambda$WindowManagerService$QGTApvQkj7JVfTvOVrLJ6s24-v8
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ((DisplayContent) obj).getInputMonitor().updateInputWindowsImmediately();
                    }
                });
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        new SurfaceControl.Transaction().syncInputWindows().apply(true);
    }

    private void waitForAnimationsToComplete() {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                long timeoutRemaining = 5000;
                while (this.mRoot.isSelfOrChildAnimating() && timeoutRemaining > 0) {
                    long startTime = System.currentTimeMillis();
                    try {
                        this.mGlobalLock.wait(timeoutRemaining);
                    } catch (InterruptedException e) {
                    }
                    timeoutRemaining -= System.currentTimeMillis() - startTime;
                }
                if (this.mRoot.isSelfOrChildAnimating()) {
                    Log.w(TAG, "Timed out waiting for animations to complete.");
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAnimationFinished() {
        synchronized (this.mGlobalLock) {
            try {
                boostPriorityForLockedSection();
                this.mGlobalLock.notifyAll();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPointerDownOutsideFocusLocked(IBinder touchedToken) {
        WindowState touchedWindow = windowForClientLocked((Session) null, touchedToken, false);
        if (touchedWindow == null || !touchedWindow.canReceiveKeys()) {
            return;
        }
        handleTaskFocusChange(touchedWindow.getTask());
        handleDisplayFocusChange(touchedWindow);
    }

    private void handleTaskFocusChange(Task task) {
        if (task == null) {
            return;
        }
        TaskStack stack = task.mStack;
        if (stack.isActivityTypeHome()) {
            return;
        }
        try {
            this.mActivityTaskManager.setFocusedTask(task.mTaskId);
        } catch (RemoteException e) {
        }
    }

    private void handleDisplayFocusChange(WindowState window) {
        WindowContainer parent;
        DisplayContent displayContent = window.getDisplayContent();
        if (displayContent != null && window.canReceiveKeys() && (parent = displayContent.getParent()) != null && parent.getTopChild() != displayContent) {
            parent.positionChildAt(Integer.MAX_VALUE, displayContent, true);
            displayContent.mAcitvityDisplay.ensureActivitiesVisible(null, 0, false, true);
        }
    }

    public Configuration getXuiConfiguration(String packageName) {
        xpPackageInfo xpi = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
        if (xpi != null) {
            try {
                DisplayContent dc = getDisplayContentOrCreate(0, null);
                Configuration config = dc.getConfiguration();
                return xpWindowManagerService.get(this.mContext).getXuiConfiguration(packageName, config);
            } catch (Exception e) {
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
            long origId = Binder.clearCallingIdentity();
            try {
                WindowState win = this.mWindowMap.get(windowToken);
                if (win != null) {
                    this.mActivityTaskManager.setFocusedTask(win.getTask().mTaskId);
                }
                Binder.restoreCallingIdentity(origId);
            } catch (Exception e) {
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(origId);
                throw th;
            }
        }
    }

    public int getAppTaskId(IBinder token) {
        WindowState win;
        synchronized (this.mWindowMap) {
            long origId = Binder.clearCallingIdentity();
            try {
                win = this.mWindowMap.get(token);
            } catch (Exception e) {
                Binder.restoreCallingIdentity(origId);
            }
            if (win != null && win.getTask() != null) {
                int i = win.getTask().mTaskId;
                Binder.restoreCallingIdentity(origId);
                return i;
            }
            Binder.restoreCallingIdentity(origId);
            return -1;
        }
    }

    public Rect getDisplayBounds() {
        return xpWindowManagerService.get(this.mContext).getDisplayBounds();
    }

    public void onPositionEventChanged(String packageName, int event, int from, int to, WindowState win) {
        synchronized (this.mWindowMap) {
            this.mSharedDisplayContainer.onPositionEventChanged(packageName, event, from, to, win, this.mWindowMap);
        }
    }

    public ArrayList<SharedDisplayContainer.SharedRecord> getSharedRecord() {
        ArrayList<SharedDisplayContainer.SharedRecord> sharedRecord;
        synchronized (this.mWindowMap) {
            sharedRecord = SharedDisplayContainer.getSharedRecord(this.mRoot);
        }
        return sharedRecord;
    }

    public ArrayList<WindowState> getVisibleWindows(int type, int value) {
        synchronized (this.mWindowMap) {
            if (this.mRoot != null) {
                ArrayList<WindowState> output = new ArrayList<>();
                this.mRoot.getVisibleWindows(type, value, output);
                return output;
            }
            return null;
        }
    }

    public void onWindowAdded(WindowState win) {
        if (win == null) {
            return;
        }
        this.mSharedDisplayContainer.onWindowAdded(win);
        xpWindowManagerService.get(this.mContext).onWindowAdded(win.mAttrs);
    }

    public void onWindowRemoved(WindowState win) {
        if (win == null) {
            return;
        }
        this.mSharedDisplayContainer.onWindowRemoved(win);
        xpWindowManagerService.get(this.mContext).onWindowRemoved(win.mAttrs);
    }

    public WindowFrameModel getWindowFrame(WindowManager.LayoutParams lp) {
        WindowFrameController.get();
        return WindowFrameController.getWindowFrame(lp);
    }

    public int getScreenId(String packageName) {
        if (SharedDisplayManager.enable()) {
            return WindowManager.findScreenId(getSharedId(packageName));
        }
        return 0;
    }

    public void setScreenId(String packageName, int screenId) {
        if (SharedDisplayManager.enable()) {
            setSharedId(packageName, screenId);
        }
    }

    public int getSharedId(String packageName) {
        return SharedDisplayContainer.getSharedId(packageName);
    }

    public void setSharedId(String packageName, int sharedId) {
        synchronized (this.mWindowMap) {
            SharedDisplayContainer.setSharedId(packageName, sharedId);
            SharedDisplayContainer.setSharedId(packageName, sharedId, this.mWindowMap);
        }
    }

    public List<String> getSharedPackages() {
        return SharedDisplayContainer.SharedDisplayImpl.getSharedPackages();
    }

    public List<String> getFilterPackages(int sharedId) {
        return SharedDisplayContainer.SharedDisplayImpl.getFilterPackages(sharedId);
    }

    public void setSharedEvent(int event, int sharedId, String extras) {
        SharedDisplayContainer.SharedDisplayImpl.setSharedEvent(this.mSharedDisplayContainer, event, sharedId, extras);
    }

    public void registerSharedListener(ISharedDisplayListener listener) {
        SharedDisplayContainer.SharedDisplayImpl.registerSharedListener(listener);
    }

    public void unregisterSharedListener(ISharedDisplayListener listener) {
        SharedDisplayContainer.SharedDisplayImpl.unregisterSharedListener(listener);
    }

    public Rect getActivityBounds(String packageName, boolean fullscreen) {
        return this.mSharedDisplayContainer.getActivityBounds(packageName, fullscreen);
    }

    public String getTopActivity(int type, int id) {
        return SharedDisplayContainer.SharedDisplayImpl.getTopActivity(this.mAtmService, type, id);
    }

    public String getTopWindow() {
        return SharedDisplayContainer.SharedDisplayImpl.getTopWindow(this.mSharedDisplayContainer);
    }

    public void handleSwipeEvent(int direction, String property) {
        SharedDisplayContainer.SharedDisplayImpl.handleSwipeEvent(direction, this.mAtmService.mContext, property);
    }

    public boolean isSharedScreenEnabled(int screenId) {
        return SharedDisplayContainer.SharedDisplayImpl.isSharedScreenEnabled(screenId);
    }

    public boolean isSharedPackageEnabled(String packageName) {
        return SharedDisplayContainer.SharedDisplayImpl.isSharedPackageEnabled(packageName);
    }

    public void setSharedScreenPolicy(int screenId, int policy) {
        SharedDisplayContainer.SharedDisplayImpl.setSharedScreenPolicy(screenId, policy);
    }

    public void setSharedPackagePolicy(String packageName, int policy) {
        SharedDisplayContainer.SharedDisplayImpl.setSharedPackagePolicy(packageName, policy);
    }

    public xpDialogInfo getTopDialog(Bundle extras) {
        return SharedDisplayFactory.getTopDialog(this.mContext, extras);
    }

    public boolean dismissDialog(Bundle extras) {
        return SharedDisplayFactory.dismissDialog(this.mContext, extras);
    }

    public void setModeEvent(int sharedId, int mode, String extra) {
        SharedDisplayContainer.SharedDisplayImpl.setModeEvent(sharedId, mode, extra);
    }

    public void setPackageSettings(String packageName, Bundle extras) {
        SharedDisplayPolicy.setPackageSettings(packageName, extras);
    }

    public int getAppPolicy(Bundle extras) {
        int type = extras != null ? extras.getInt("type", 0) : 0;
        return AppTaskPolicy.get().getAppPolicy(this.mContext, type, extras, null);
    }

    public Bitmap screenshot(int screenId, Bundle extras) {
        long ident = Binder.clearCallingIdentity();
        try {
            return xpWindowManagerService.screenshot(screenId, extras);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
