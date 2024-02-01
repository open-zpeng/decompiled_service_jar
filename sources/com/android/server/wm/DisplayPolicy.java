package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ContextImpl;
import android.app.LoadedApk;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.IApplicationToken;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.accessibility.AccessibilityManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.ScreenShapeHelper;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.widget.PointerLocationView;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowOrientationListener;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.BarController;
import com.android.server.wm.SystemGesturesPointerEventListener;
import com.android.server.wm.utils.InsetUtils;
import com.xiaopeng.server.policy.xpPhoneWindowManager;
import com.xiaopeng.server.policy.xpSystemGesturesListener;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
public class DisplayPolicy {
    private static final boolean ALTERNATE_CAR_MODE_NAV_SIZE = false;
    private static final boolean DEBUG = false;
    private static final int MSG_DISABLE_POINTER_LOCATION = 5;
    private static final int MSG_DISPOSE_INPUT_CONSUMER = 3;
    private static final int MSG_ENABLE_POINTER_LOCATION = 4;
    private static final int MSG_REQUEST_TRANSIENT_BARS = 2;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION = 1;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS = 0;
    private static final int MSG_UPDATE_DREAMING_SLEEP_TOKEN = 1;
    private static final int NAV_BAR_FORCE_TRANSPARENT = 2;
    private static final int NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED = 0;
    private static final int NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE = 1;
    private static final long PANIC_GESTURE_EXPIRATION = 30000;
    private static final int SYSTEM_UI_CHANGING_LAYOUT = -1073709042;
    private static final String TAG = "WindowManager";
    private final AccessibilityManager mAccessibilityManager;
    private final Runnable mAcquireSleepTokenRunnable;
    private boolean mAllowLockscreenWhenOn;
    private volatile boolean mAllowSeamlessRotationDespiteNavBarMoving;
    private volatile boolean mAwake;
    private int mBottomGestureAdditionalInset;
    private final boolean mCarDockEnablesAccelerometer;
    private final Context mContext;
    private Resources mCurrentUserResources;
    private final boolean mDeskDockEnablesAccelerometer;
    private final DisplayContent mDisplayContent;
    private boolean mDreamingLockscreen;
    @GuardedBy({"mHandler"})
    private ActivityTaskManagerInternal.SleepToken mDreamingSleepToken;
    private boolean mDreamingSleepTokenNeeded;
    IApplicationToken mFocusedApp;
    private WindowState mFocusedWindow;
    private boolean mForceShowSystemBars;
    private boolean mForceShowSystemBarsFromExternal;
    private boolean mForceStatusBar;
    private boolean mForceStatusBarFromKeyguard;
    private boolean mForceStatusBarTransparent;
    private boolean mForcingShowNavBar;
    private int mForcingShowNavBarLayer;
    private final Handler mHandler;
    private volatile boolean mHasNavigationBar;
    private volatile boolean mHasStatusBar;
    private volatile boolean mHdmiPlugged;
    private final ImmersiveModeConfirmation mImmersiveModeConfirmation;
    private volatile boolean mKeyguardDrawComplete;
    private int mLastDockedStackSysUiFlags;
    private WindowState mLastFocusedWindow;
    private int mLastFullscreenStackSysUiFlags;
    private boolean mLastShowingDream;
    int mLastSystemUiFlags;
    private boolean mLastWindowSleepTokenNeeded;
    private final Object mLock;
    private volatile boolean mNavigationBarAlwaysShowOnSideGesture;
    private volatile boolean mNavigationBarCanMove;
    private final BarController mNavigationBarController;
    private volatile boolean mNavigationBarLetsThroughTaps;
    private long mPendingPanicGestureUptime;
    private volatile boolean mPersistentVrModeEnabled;
    private PointerLocationView mPointerLocationView;
    private RefreshRatePolicy mRefreshRatePolicy;
    private final Runnable mReleaseSleepTokenRunnable;
    private volatile boolean mScreenOnEarly;
    private volatile boolean mScreenOnFully;
    private volatile WindowManagerPolicy.ScreenOnListener mScreenOnListener;
    private final ScreenshotHelper mScreenshotHelper;
    private final WindowManagerService mService;
    private boolean mShowingDream;
    private int mSideGestureInset;
    private final StatusBarController mStatusBarController;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private final SystemGesturesPointerEventListener mSystemGestures;
    private WindowState mTopDockedOpaqueOrDimmingWindowState;
    private WindowState mTopDockedOpaqueWindowState;
    private WindowState mTopFullscreenOpaqueOrDimmingWindowState;
    private WindowState mTopFullscreenOpaqueWindowState;
    private boolean mTopIsFullscreen;
    private volatile boolean mWindowManagerDrawComplete;
    private int mWindowOutsetBottom;
    @GuardedBy({"mHandler"})
    private ActivityTaskManagerInternal.SleepToken mWindowSleepToken;
    private boolean mWindowSleepTokenNeeded;
    private static final Rect sTmpDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
    private static final Rect sTmpRect = new Rect();
    private static final Rect sTmpDockedFrame = new Rect();
    private static final Rect sTmpNavFrame = new Rect();
    private static final Rect sTmpLastParentFrame = new Rect();
    private final Object mServiceAcquireLock = new Object();
    private volatile int mLidState = -1;
    private volatile int mDockMode = 0;
    private final ArraySet<WindowState> mScreenDecorWindows = new ArraySet<>();
    private WindowState mStatusBar = null;
    private final int[] mStatusBarHeightForRotation = new int[4];
    private WindowState mNavigationBar = null;
    private int mNavigationBarPosition = 4;
    private int[] mNavigationBarHeightForRotationDefault = new int[4];
    private int[] mNavigationBarWidthForRotationDefault = new int[4];
    private int[] mNavigationBarHeightForRotationInCarMode = new int[4];
    private int[] mNavigationBarWidthForRotationInCarMode = new int[4];
    private int[] mNavigationBarFrameHeightForRotationDefault = new int[4];
    private final BarController.OnBarVisibilityChangedListener mNavBarVisibilityListener = new BarController.OnBarVisibilityChangedListener() { // from class: com.android.server.wm.DisplayPolicy.1
        @Override // com.android.server.wm.BarController.OnBarVisibilityChangedListener
        public void onBarVisibilityChanged(boolean visible) {
            if (DisplayPolicy.this.mAccessibilityManager != null) {
                DisplayPolicy.this.mAccessibilityManager.notifyAccessibilityButtonVisibilityChanged(visible);
            }
        }
    };
    private int mResettingSystemUiFlags = 0;
    private int mForceClearedSystemUiFlags = 0;
    private final Rect mNonDockedStackBounds = new Rect();
    private final Rect mDockedStackBounds = new Rect();
    private final Rect mLastNonDockedStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();
    private boolean mLastFocusNeedsMenu = false;
    private int mNavBarOpacityMode = 0;
    private WindowManagerPolicy.InputConsumer mInputConsumer = null;
    private Insets mForwardedInsets = Insets.NONE;
    private final Runnable mClearHideNavigationFlag = new Runnable() { // from class: com.android.server.wm.DisplayPolicy.3
        @Override // java.lang.Runnable
        public void run() {
            synchronized (DisplayPolicy.this.mLock) {
                DisplayPolicy.access$1772(DisplayPolicy.this, -3);
                DisplayPolicy.this.mDisplayContent.reevaluateStatusBarVisibility();
            }
        }
    };
    private final Runnable mHiddenNavPanic = new Runnable() { // from class: com.android.server.wm.DisplayPolicy.4
        @Override // java.lang.Runnable
        public void run() {
            synchronized (DisplayPolicy.this.mLock) {
                if (DisplayPolicy.this.mService.mPolicy.isUserSetupComplete()) {
                    DisplayPolicy.this.mPendingPanicGestureUptime = SystemClock.uptimeMillis();
                    if (!DisplayPolicy.isNavBarEmpty(DisplayPolicy.this.mLastSystemUiFlags)) {
                        DisplayPolicy.this.mNavigationBarController.showTransient();
                    }
                }
            }
        }
    };

    static /* synthetic */ int access$1772(DisplayPolicy x0, int x1) {
        int i = x0.mForceClearedSystemUiFlags & x1;
        x0.mForceClearedSystemUiFlags = i;
        return i;
    }

    private StatusBarManagerInternal getStatusBarManagerInternal() {
        StatusBarManagerInternal statusBarManagerInternal;
        synchronized (this.mServiceAcquireLock) {
            if (this.mStatusBarManagerInternal == null) {
                this.mStatusBarManagerInternal = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
            }
            statusBarManagerInternal = this.mStatusBarManagerInternal;
        }
        return statusBarManagerInternal;
    }

    /* loaded from: classes2.dex */
    private class PolicyHandler extends Handler {
        PolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                DisplayPolicy.this.updateDreamingSleepToken(msg.arg1 != 0);
            } else if (i == 2) {
                WindowState targetBar = msg.arg1 == 0 ? DisplayPolicy.this.mStatusBar : DisplayPolicy.this.mNavigationBar;
                if (targetBar != null) {
                    DisplayPolicy.this.requestTransientBars(targetBar);
                }
            } else if (i == 3) {
                DisplayPolicy.this.disposeInputConsumer((WindowManagerPolicy.InputConsumer) msg.obj);
            } else if (i == 4) {
                DisplayPolicy.this.enablePointerLocation();
            } else if (i == 5) {
                DisplayPolicy.this.disablePointerLocation();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayPolicy(final WindowManagerService service, DisplayContent displayContent) {
        this.mService = service;
        this.mContext = displayContent.isDefaultDisplay ? service.mContext : service.mContext.createDisplayContext(displayContent.getDisplay());
        this.mDisplayContent = displayContent;
        this.mLock = service.getWindowManagerLock();
        final int displayId = displayContent.getDisplayId();
        this.mStatusBarController = new StatusBarController(displayId);
        this.mNavigationBarController = new BarController("NavigationBar", displayId, 134217728, 536870912, Integer.MIN_VALUE, 2, 134217728, 32768);
        Resources r = this.mContext.getResources();
        this.mCarDockEnablesAccelerometer = r.getBoolean(17891387);
        this.mDeskDockEnablesAccelerometer = r.getBoolean(17891402);
        this.mForceShowSystemBarsFromExternal = r.getBoolean(17891461);
        this.mAccessibilityManager = (AccessibilityManager) this.mContext.getSystemService("accessibility");
        if (!displayContent.isDefaultDisplay) {
            this.mAwake = true;
            this.mScreenOnEarly = true;
            this.mScreenOnFully = true;
        }
        Looper looper = UiThread.getHandler().getLooper();
        this.mHandler = new PolicyHandler(looper);
        this.mSystemGestures = new SystemGesturesPointerEventListener(this.mContext, this.mHandler, new SystemGesturesPointerEventListener.Callbacks() { // from class: com.android.server.wm.DisplayPolicy.2
            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromTop(int screenId, int count) {
                if (DisplayPolicy.this.mStatusBar != null) {
                    DisplayPolicy displayPolicy = DisplayPolicy.this;
                    displayPolicy.requestTransientBars(displayPolicy.mStatusBar);
                }
                xpSystemGesturesListener.handleGestureEvent(DisplayPolicy.this.mContext, screenId, 1, count, true, null, DisplayPolicy.this.mFocusedWindow);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromBottom(int screenId, int count) {
                if (DisplayPolicy.this.mNavigationBar != null && DisplayPolicy.this.mNavigationBarPosition == 4) {
                    DisplayPolicy displayPolicy = DisplayPolicy.this;
                    displayPolicy.requestTransientBars(displayPolicy.mNavigationBar);
                }
                xpSystemGesturesListener.handleGestureEvent(DisplayPolicy.this.mContext, screenId, 2, count, true, null, DisplayPolicy.this.mFocusedWindow);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromRight(int screenId, int count) {
                Region excludedRegion = Region.obtain();
                synchronized (DisplayPolicy.this.mLock) {
                    DisplayPolicy.this.mDisplayContent.calculateSystemGestureExclusion(excludedRegion, null);
                }
                boolean sideAllowed = DisplayPolicy.this.mNavigationBarAlwaysShowOnSideGesture || DisplayPolicy.this.mNavigationBarPosition == 2;
                if (DisplayPolicy.this.mNavigationBar != null && sideAllowed && !DisplayPolicy.this.mSystemGestures.currentGestureStartedInRegion(excludedRegion)) {
                    DisplayPolicy displayPolicy = DisplayPolicy.this;
                    displayPolicy.requestTransientBars(displayPolicy.mNavigationBar);
                }
                excludedRegion.recycle();
                xpSystemGesturesListener.handleGestureEvent(DisplayPolicy.this.mContext, screenId, 3, count, true, null, DisplayPolicy.this.mFocusedWindow);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromLeft(int screenId, int count) {
                Region excludedRegion = Region.obtain();
                synchronized (DisplayPolicy.this.mLock) {
                    DisplayPolicy.this.mDisplayContent.calculateSystemGestureExclusion(excludedRegion, null);
                }
                boolean z = true;
                if (!DisplayPolicy.this.mNavigationBarAlwaysShowOnSideGesture && DisplayPolicy.this.mNavigationBarPosition != 1) {
                    z = false;
                }
                boolean sideAllowed = z;
                if (DisplayPolicy.this.mNavigationBar != null && sideAllowed && !DisplayPolicy.this.mSystemGestures.currentGestureStartedInRegion(excludedRegion)) {
                    DisplayPolicy displayPolicy = DisplayPolicy.this;
                    displayPolicy.requestTransientBars(displayPolicy.mNavigationBar);
                }
                excludedRegion.recycle();
                xpSystemGesturesListener.handleGestureEvent(DisplayPolicy.this.mContext, screenId, 4, count, true, null, DisplayPolicy.this.mFocusedWindow);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromMultiPointer(int screenId, int direction, int count, boolean isSwipeFromTopAreaValid, String extras) {
                xpSystemGesturesListener.handleGestureEvent(DisplayPolicy.this.mContext, screenId, direction, count, isSwipeFromTopAreaValid, extras, DisplayPolicy.this.mFocusedWindow);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onFling(int duration) {
                if (DisplayPolicy.this.mService.mPowerManagerInternal != null) {
                    DisplayPolicy.this.mService.mPowerManagerInternal.powerHint(2, duration);
                }
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onDebug() {
            }

            private WindowOrientationListener getOrientationListener() {
                DisplayRotation rotation = DisplayPolicy.this.mDisplayContent.getDisplayRotation();
                if (rotation != null) {
                    return rotation.getOrientationListener();
                }
                return null;
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onDown() {
                WindowOrientationListener listener = getOrientationListener();
                if (listener != null) {
                    listener.onTouchStart();
                }
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onUpOrCancel() {
                WindowOrientationListener listener = getOrientationListener();
                if (listener != null) {
                    listener.onTouchEnd();
                }
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onMouseHoverAtTop() {
                DisplayPolicy.this.mHandler.removeMessages(2);
                Message msg = DisplayPolicy.this.mHandler.obtainMessage(2);
                msg.arg1 = 0;
                DisplayPolicy.this.mHandler.sendMessageDelayed(msg, 500L);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onMouseHoverAtBottom() {
                DisplayPolicy.this.mHandler.removeMessages(2);
                Message msg = DisplayPolicy.this.mHandler.obtainMessage(2);
                msg.arg1 = 1;
                DisplayPolicy.this.mHandler.sendMessageDelayed(msg, 500L);
            }

            @Override // com.android.server.wm.SystemGesturesPointerEventListener.Callbacks
            public void onMouseLeaveFromEdge() {
                DisplayPolicy.this.mHandler.removeMessages(2);
            }
        });
        displayContent.registerPointerEventListener(this.mSystemGestures);
        displayContent.mAppTransition.registerListenerLocked(this.mStatusBarController.getAppTransitionListener());
        this.mImmersiveModeConfirmation = new ImmersiveModeConfirmation(this.mContext, looper, this.mService.mVrModeEnabled);
        this.mAcquireSleepTokenRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$j3sY1jb4WFF_F3wOT9D2fB2mOts
            @Override // java.lang.Runnable
            public final void run() {
                DisplayPolicy.this.lambda$new$0$DisplayPolicy(service, displayId);
            }
        };
        this.mReleaseSleepTokenRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$_FsvHpVUi-gbWmSpT009cJNNmgM
            @Override // java.lang.Runnable
            public final void run() {
                DisplayPolicy.this.lambda$new$1$DisplayPolicy();
            }
        };
        this.mScreenshotHelper = displayContent.isDefaultDisplay ? new ScreenshotHelper(this.mContext) : null;
        if (this.mDisplayContent.isDefaultDisplay) {
            this.mHasStatusBar = true;
            this.mHasNavigationBar = this.mContext.getResources().getBoolean(17891517);
            String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
            if ("1".equals(navBarOverride)) {
                this.mHasNavigationBar = false;
            } else if ("0".equals(navBarOverride)) {
                this.mHasNavigationBar = true;
            }
        } else {
            this.mHasStatusBar = false;
            this.mHasNavigationBar = this.mDisplayContent.supportsSystemDecorations();
        }
        this.mRefreshRatePolicy = new RefreshRatePolicy(this.mService, this.mDisplayContent.getDisplayInfo(), this.mService.mHighRefreshRateBlacklist);
    }

    public /* synthetic */ void lambda$new$0$DisplayPolicy(WindowManagerService service, int displayId) {
        if (this.mWindowSleepToken != null) {
            return;
        }
        ActivityTaskManagerInternal activityTaskManagerInternal = service.mAtmInternal;
        this.mWindowSleepToken = activityTaskManagerInternal.acquireSleepToken("WindowSleepTokenOnDisplay" + displayId, displayId);
    }

    public /* synthetic */ void lambda$new$1$DisplayPolicy() {
        ActivityTaskManagerInternal.SleepToken sleepToken = this.mWindowSleepToken;
        if (sleepToken == null) {
            return;
        }
        sleepToken.release();
        this.mWindowSleepToken = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mSystemGestures.systemReady();
        if (this.mService.mPointerLocationEnabled) {
            setPointerLocationEnabled(true);
        }
    }

    private int getDisplayId() {
        return this.mDisplayContent.getDisplayId();
    }

    public void setHdmiPlugged(boolean plugged) {
        setHdmiPlugged(plugged, false);
    }

    public void setHdmiPlugged(boolean plugged, boolean force) {
        if (force || this.mHdmiPlugged != plugged) {
            this.mHdmiPlugged = plugged;
            this.mService.updateRotation(true, true);
            Intent intent = new Intent("android.intent.action.HDMI_PLUGGED");
            intent.addFlags(67108864);
            intent.putExtra("state", plugged);
            this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isHdmiPlugged() {
        return this.mHdmiPlugged;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCarDockEnablesAccelerometer() {
        return this.mCarDockEnablesAccelerometer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDeskDockEnablesAccelerometer() {
        return this.mDeskDockEnablesAccelerometer;
    }

    public void setPersistentVrModeEnabled(boolean persistentVrModeEnabled) {
        this.mPersistentVrModeEnabled = persistentVrModeEnabled;
    }

    public boolean isPersistentVrModeEnabled() {
        return this.mPersistentVrModeEnabled;
    }

    public void setDockMode(int dockMode) {
        this.mDockMode = dockMode;
    }

    public int getDockMode() {
        return this.mDockMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForceShowSystemBars(boolean forceShowSystemBars) {
        this.mForceShowSystemBarsFromExternal = forceShowSystemBars;
    }

    public boolean hasNavigationBar() {
        return this.mHasNavigationBar;
    }

    public boolean hasStatusBar() {
        return this.mHasStatusBar;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSideGestures() {
        return this.mHasNavigationBar && this.mSideGestureInset > 0;
    }

    public boolean navigationBarCanMove() {
        return this.mNavigationBarCanMove;
    }

    public void setLidState(int lidState) {
        this.mLidState = lidState;
    }

    public int getLidState() {
        return this.mLidState;
    }

    public void setAwake(boolean awake) {
        this.mAwake = awake;
    }

    public boolean isAwake() {
        return this.mAwake;
    }

    public boolean isScreenOnEarly() {
        return this.mScreenOnEarly;
    }

    public boolean isScreenOnFully() {
        return this.mScreenOnFully;
    }

    public boolean isKeyguardDrawComplete() {
        return this.mKeyguardDrawComplete;
    }

    public boolean isWindowManagerDrawComplete() {
        return this.mWindowManagerDrawComplete;
    }

    public WindowManagerPolicy.ScreenOnListener getScreenOnListener() {
        return this.mScreenOnListener;
    }

    public void screenTurnedOn(WindowManagerPolicy.ScreenOnListener screenOnListener) {
        synchronized (this.mLock) {
            this.mScreenOnEarly = true;
            this.mScreenOnFully = false;
            this.mKeyguardDrawComplete = false;
            this.mWindowManagerDrawComplete = false;
            this.mScreenOnListener = screenOnListener;
        }
    }

    public void screenTurnedOff() {
        synchronized (this.mLock) {
            this.mScreenOnEarly = false;
            this.mScreenOnFully = false;
            this.mKeyguardDrawComplete = false;
            this.mWindowManagerDrawComplete = false;
            this.mScreenOnListener = null;
        }
    }

    public boolean finishKeyguardDrawn() {
        synchronized (this.mLock) {
            if (this.mScreenOnEarly && !this.mKeyguardDrawComplete) {
                this.mKeyguardDrawComplete = true;
                this.mWindowManagerDrawComplete = false;
                return true;
            }
            return false;
        }
    }

    public boolean finishWindowsDrawn() {
        synchronized (this.mLock) {
            if (this.mScreenOnEarly && !this.mWindowManagerDrawComplete) {
                this.mWindowManagerDrawComplete = true;
                return true;
            }
            return false;
        }
    }

    public boolean finishScreenTurningOn() {
        synchronized (this.mLock) {
            if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
                Slog.d(TAG, "finishScreenTurningOn: mAwake=" + this.mAwake + ", mScreenOnEarly=" + this.mScreenOnEarly + ", mScreenOnFully=" + this.mScreenOnFully + ", mKeyguardDrawComplete=" + this.mKeyguardDrawComplete + ", mWindowManagerDrawComplete=" + this.mWindowManagerDrawComplete);
            }
            if (!this.mScreenOnFully && this.mScreenOnEarly && this.mWindowManagerDrawComplete && (!this.mAwake || this.mKeyguardDrawComplete)) {
                if (WindowManagerDebugConfig.DEBUG_SCREEN_ON) {
                    Slog.i(TAG, "Finished screen turning on...");
                }
                this.mScreenOnListener = null;
                this.mScreenOnFully = true;
                return true;
            }
            return false;
        }
    }

    private boolean hasStatusBarServicePermission(int pid, int uid) {
        return this.mContext.checkPermission("android.permission.STATUS_BAR_SERVICE", pid, uid) == 0;
    }

    /* JADX WARN: Code restructure failed: missing block: B:27:0x0044, code lost:
        if (r2 != 2006) goto L22;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void adjustWindowParamsLw(com.android.server.wm.WindowState r7, android.view.WindowManager.LayoutParams r8, int r9, int r10) {
        /*
            r6 = this;
            int r0 = r8.privateFlags
            r1 = 4194304(0x400000, float:5.877472E-39)
            r0 = r0 & r1
            r1 = 1
            if (r0 == 0) goto La
            r0 = r1
            goto Lb
        La:
            r0 = 0
        Lb:
            android.util.ArraySet<com.android.server.wm.WindowState> r2 = r6.mScreenDecorWindows
            boolean r2 = r2.contains(r7)
            if (r2 == 0) goto L1b
            if (r0 != 0) goto L28
            android.util.ArraySet<com.android.server.wm.WindowState> r2 = r6.mScreenDecorWindows
            r2.remove(r7)
            goto L28
        L1b:
            if (r0 == 0) goto L28
            boolean r2 = r6.hasStatusBarServicePermission(r9, r10)
            if (r2 == 0) goto L28
            android.util.ArraySet<com.android.server.wm.WindowState> r2 = r6.mScreenDecorWindows
            r2.add(r7)
        L28:
            int r2 = r8.type
            r3 = 2000(0x7d0, float:2.803E-42)
            if (r2 == r3) goto L98
            r4 = 2013(0x7dd, float:2.821E-42)
            if (r2 == r4) goto L95
            r4 = 2015(0x7df, float:2.824E-42)
            if (r2 == r4) goto L86
            r4 = 2023(0x7e7, float:2.835E-42)
            if (r2 == r4) goto L95
            r1 = 2036(0x7f4, float:2.853E-42)
            if (r2 == r1) goto L7f
            r1 = 2005(0x7d5, float:2.81E-42)
            if (r2 == r1) goto L47
            r1 = 2006(0x7d6, float:2.811E-42)
            if (r2 == r1) goto L86
            goto Lb0
        L47:
            long r1 = r8.hideTimeoutMilliseconds
            r4 = 0
            int r1 = (r1 > r4 ? 1 : (r1 == r4 ? 0 : -1))
            r4 = 6000(0x1770, double:2.9644E-320)
            if (r1 < 0) goto L57
            long r1 = r8.hideTimeoutMilliseconds
            int r1 = (r1 > r4 ? 1 : (r1 == r4 ? 0 : -1))
            if (r1 <= 0) goto L59
        L57:
            r8.hideTimeoutMilliseconds = r4
        L59:
            android.view.accessibility.AccessibilityManager r1 = r6.mAccessibilityManager
            long r4 = r8.hideTimeoutMilliseconds
            int r2 = (int) r4
            r4 = 2
            int r1 = r1.getRecommendedTimeoutMillis(r2, r4)
            long r1 = (long) r1
            r8.hideTimeoutMilliseconds = r1
            r1 = 16973828(0x1030004, float:2.406091E-38)
            r8.windowAnimations = r1
            boolean r1 = r6.canToastShowWhenLocked(r9)
            if (r1 == 0) goto L78
            int r1 = r8.flags
            r2 = 524288(0x80000, float:7.34684E-40)
            r1 = r1 | r2
            r8.flags = r1
        L78:
            int r1 = r8.flags
            r1 = r1 | 16
            r8.flags = r1
            goto Lb0
        L7f:
            int r1 = r8.flags
            r1 = r1 | 8
            r8.flags = r1
            goto Lb0
        L86:
            int r1 = r8.flags
            r1 = r1 | 24
            r8.flags = r1
            int r1 = r8.flags
            r2 = -262145(0xfffffffffffbffff, float:NaN)
            r1 = r1 & r2
            r8.flags = r1
            goto Lb0
        L95:
            r8.layoutInDisplayCutoutMode = r1
            goto Lb0
        L98:
            com.android.server.wm.WindowManagerService r1 = r6.mService
            com.android.server.policy.WindowManagerPolicy r1 = r1.mPolicy
            boolean r1 = r1.isKeyguardOccluded()
            if (r1 == 0) goto Lb0
            int r1 = r8.flags
            r2 = -1048577(0xffffffffffefffff, float:NaN)
            r1 = r1 & r2
            r8.flags = r1
            int r1 = r8.privateFlags
            r1 = r1 & (-1025(0xfffffffffffffbff, float:NaN))
            r8.privateFlags = r1
        Lb0:
            int r1 = r8.type
            if (r1 == r3) goto Lba
            int r1 = r8.privateFlags
            r1 = r1 & (-1025(0xfffffffffffffbff, float:NaN))
            r8.privateFlags = r1
        Lba:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayPolicy.adjustWindowParamsLw(com.android.server.wm.WindowState, android.view.WindowManager$LayoutParams, int, int):void");
    }

    boolean canToastShowWhenLocked(final int callingPid) {
        return this.mDisplayContent.forAllWindows(new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$pqtzqy0ti-csynvTP9P1eQUE-gE
            public final boolean apply(Object obj) {
                return DisplayPolicy.lambda$canToastShowWhenLocked$2(callingPid, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$canToastShowWhenLocked$2(int callingPid, WindowState w) {
        return callingPid == w.mSession.mPid && w.isVisible() && w.canShowWhenLocked();
    }

    /* JADX WARN: Code restructure failed: missing block: B:16:0x002f, code lost:
        if (r0 != 2033) goto L16;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int prepareAddWindowLw(com.android.server.wm.WindowState r7, android.view.WindowManager.LayoutParams r8) {
        /*
            Method dump skipped, instructions count: 229
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayPolicy.prepareAddWindowLw(com.android.server.wm.WindowState, android.view.WindowManager$LayoutParams):int");
    }

    public /* synthetic */ void lambda$prepareAddWindowLw$3$DisplayPolicy(DisplayFrames displayFrames, WindowState windowState, Rect rect) {
        rect.top = 0;
        rect.bottom = getStatusBarHeight(displayFrames);
    }

    public /* synthetic */ void lambda$prepareAddWindowLw$4$DisplayPolicy(DisplayFrames displayFrames, WindowState windowState, Rect inOutFrame) {
        inOutFrame.top -= this.mBottomGestureAdditionalInset;
    }

    public /* synthetic */ void lambda$prepareAddWindowLw$5$DisplayPolicy(DisplayFrames displayFrames, WindowState windowState, Rect inOutFrame) {
        inOutFrame.left = 0;
        inOutFrame.top = 0;
        inOutFrame.bottom = displayFrames.mDisplayHeight;
        inOutFrame.right = displayFrames.mUnrestricted.left + this.mSideGestureInset;
    }

    public /* synthetic */ void lambda$prepareAddWindowLw$6$DisplayPolicy(DisplayFrames displayFrames, WindowState windowState, Rect inOutFrame) {
        inOutFrame.left = displayFrames.mUnrestricted.right - this.mSideGestureInset;
        inOutFrame.top = 0;
        inOutFrame.bottom = displayFrames.mDisplayHeight;
        inOutFrame.right = displayFrames.mDisplayWidth;
    }

    public /* synthetic */ void lambda$prepareAddWindowLw$7$DisplayPolicy(DisplayFrames displayFrames, WindowState windowState, Rect inOutFrame) {
        if ((windowState.getAttrs().flags & 16) != 0 || this.mNavigationBarLetsThroughTaps) {
            inOutFrame.setEmpty();
        }
    }

    public void removeWindowLw(WindowState win) {
        if (this.mStatusBar == win) {
            this.mStatusBar = null;
            this.mStatusBarController.setWindow(null);
            if (this.mDisplayContent.isDefaultDisplay) {
                this.mService.mPolicy.setKeyguardCandidateLw(null);
            }
            this.mDisplayContent.setInsetProvider(0, null, null);
        } else if (this.mNavigationBar == win) {
            this.mNavigationBar = null;
            this.mNavigationBarController.setWindow(null);
            this.mDisplayContent.setInsetProvider(1, null, null);
        }
        if (this.mLastFocusedWindow == win) {
            this.mLastFocusedWindow = null;
        }
        this.mScreenDecorWindows.remove(win);
    }

    private int getStatusBarHeight(DisplayFrames displayFrames) {
        return Math.max(this.mStatusBarHeightForRotation[displayFrames.mRotation], displayFrames.mDisplayCutoutSafe.top);
    }

    public int selectAnimationLw(WindowState win, int transit) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.i(TAG, "selectAnimation in " + win + ": transit=" + transit);
        }
        if (win == this.mStatusBar) {
            boolean isKeyguard = (win.getAttrs().privateFlags & 1024) != 0;
            boolean expanded = win.getAttrs().height == -1 && win.getAttrs().width == -1;
            if (isKeyguard || expanded) {
                return -1;
            }
            if (transit == 2 || transit == 4) {
                return 17432622;
            }
            if (transit == 1 || transit == 3) {
                return 17432621;
            }
        } else if (win == this.mNavigationBar) {
            if (win.getAttrs().windowAnimations != 0) {
                return 0;
            }
            int i = this.mNavigationBarPosition;
            if (i == 4) {
                if (transit == 2 || transit == 4) {
                    if (this.mService.mPolicy.isKeyguardShowingAndNotOccluded()) {
                        return 17432616;
                    }
                    return 17432615;
                } else if (transit == 1 || transit == 3) {
                    return 17432614;
                }
            } else if (i == 2) {
                if (transit == 2 || transit == 4) {
                    return 17432620;
                }
                if (transit == 1 || transit == 3) {
                    return 17432619;
                }
            } else if (i == 1) {
                if (transit == 2 || transit == 4) {
                    return 17432618;
                }
                if (transit == 1 || transit == 3) {
                    return 17432617;
                }
            }
        } else if (win.getAttrs().type == 2034) {
            return selectDockedDividerAnimationLw(win, transit);
        }
        if (transit == 5) {
            if (win.hasAppShownWindows()) {
                if (WindowManagerDebugConfig.DEBUG_ANIM) {
                    Slog.i(TAG, "**** STARTING EXIT");
                    return 17432595;
                }
                return 17432595;
            }
        } else if (win.getAttrs().type == 2023 && this.mDreamingLockscreen && transit == 1) {
            return -1;
        }
        return 0;
    }

    private int selectDockedDividerAnimationLw(WindowState win, int transit) {
        int insets = this.mDisplayContent.getDockedDividerController().getContentInsets();
        Rect frame = win.getFrameLw();
        boolean behindNavBar = this.mNavigationBar != null && ((this.mNavigationBarPosition == 4 && frame.top + insets >= this.mNavigationBar.getFrameLw().top) || ((this.mNavigationBarPosition == 2 && frame.left + insets >= this.mNavigationBar.getFrameLw().left) || (this.mNavigationBarPosition == 1 && frame.right - insets <= this.mNavigationBar.getFrameLw().right)));
        boolean landscape = frame.height() > frame.width();
        boolean offscreenLandscape = landscape && (frame.right - insets <= 0 || frame.left + insets >= win.getDisplayFrameLw().right);
        boolean offscreenPortrait = !landscape && (frame.top - insets <= 0 || frame.bottom + insets >= win.getDisplayFrameLw().bottom);
        boolean offscreen = offscreenLandscape || offscreenPortrait;
        if (behindNavBar || offscreen) {
            return 0;
        }
        if (transit == 1 || transit == 3) {
            return 17432576;
        }
        return transit == 2 ? 17432577 : 0;
    }

    public void selectRotationAnimationLw(int[] anim) {
        boolean forceJumpcut = (this.mScreenOnFully && this.mService.mPolicy.okToAnimate()) ? false : true;
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            StringBuilder sb = new StringBuilder();
            sb.append("selectRotationAnimation mTopFullscreen=");
            sb.append(this.mTopFullscreenOpaqueWindowState);
            sb.append(" rotationAnimation=");
            WindowState windowState = this.mTopFullscreenOpaqueWindowState;
            sb.append(windowState == null ? "0" : Integer.valueOf(windowState.getAttrs().rotationAnimation));
            sb.append(" forceJumpcut=");
            sb.append(forceJumpcut);
            Slog.i(TAG, sb.toString());
        }
        if (forceJumpcut) {
            anim[0] = 17432708;
            anim[1] = 17432707;
            return;
        }
        WindowState windowState2 = this.mTopFullscreenOpaqueWindowState;
        if (windowState2 != null) {
            int animationHint = windowState2.getRotationAnimationHint();
            if (animationHint < 0 && this.mTopIsFullscreen) {
                animationHint = this.mTopFullscreenOpaqueWindowState.getAttrs().rotationAnimation;
            }
            if (animationHint != 1) {
                if (animationHint == 2) {
                    anim[0] = 17432708;
                    anim[1] = 17432707;
                    return;
                } else if (animationHint != 3) {
                    anim[1] = 0;
                    anim[0] = 0;
                    return;
                }
            }
            anim[0] = 17432709;
            anim[1] = 17432707;
            return;
        }
        anim[1] = 0;
        anim[0] = 0;
    }

    public boolean validateRotationAnimationLw(int exitAnimId, int enterAnimId, boolean forceDefault) {
        switch (exitAnimId) {
            case 17432708:
            case 17432709:
                if (forceDefault) {
                    return false;
                }
                int[] anim = new int[2];
                selectRotationAnimationLw(anim);
                if (exitAnimId == anim[0] && enterAnimId == anim[1]) {
                    return true;
                }
                return false;
            default:
                return true;
        }
    }

    public int adjustSystemUiVisibilityLw(int visibility) {
        this.mStatusBarController.adjustSystemUiVisibilityLw(this.mLastSystemUiFlags, visibility);
        this.mNavigationBarController.adjustSystemUiVisibilityLw(this.mLastSystemUiFlags, visibility);
        this.mResettingSystemUiFlags &= visibility;
        return (~this.mResettingSystemUiFlags) & visibility & (~this.mForceClearedSystemUiFlags);
    }

    public boolean areSystemBarsForcedShownLw(WindowState windowState) {
        return this.mForceShowSystemBars;
    }

    public boolean getLayoutHintLw(WindowManager.LayoutParams attrs, Rect taskBounds, DisplayFrames displayFrames, boolean floatingStack, Rect outFrame, Rect outContentInsets, Rect outStableInsets, Rect outOutsets, DisplayCutout.ParcelableWrapper outDisplayCutout) {
        Rect sf;
        Rect cf;
        int outset;
        int fl = PolicyControl.getWindowFlags(null, attrs);
        int pfl = attrs.privateFlags;
        int requestedSysUiVis = PolicyControl.getSystemUiVisibility(null, attrs);
        int sysUiVis = getImpliedSysUiFlagsForLayout(attrs) | requestedSysUiVis;
        int displayRotation = displayFrames.mRotation;
        boolean useOutsets = outOutsets != null && shouldUseOutsets(attrs, fl);
        if (useOutsets && (outset = this.mWindowOutsetBottom) > 0) {
            if (displayRotation == 0) {
                outOutsets.bottom += outset;
            } else if (displayRotation == 1) {
                outOutsets.right += outset;
            } else if (displayRotation == 2) {
                outOutsets.top += outset;
            } else if (displayRotation == 3) {
                outOutsets.left += outset;
            }
        }
        boolean layoutInScreen = (fl & 256) != 0;
        boolean layoutInScreenAndInsetDecor = layoutInScreen && (65536 & fl) != 0;
        boolean screenDecor = (pfl & DumpState.DUMP_CHANGES) != 0;
        if (layoutInScreenAndInsetDecor && !screenDecor) {
            if ((sysUiVis & 512) != 0) {
                outFrame.set(displayFrames.mUnrestricted);
            } else {
                outFrame.set(displayFrames.mRestricted);
            }
            if (floatingStack) {
                sf = null;
            } else {
                sf = displayFrames.mStable;
            }
            if (floatingStack) {
                cf = null;
            } else if ((sysUiVis & 256) != 0) {
                if ((fl & 1024) != 0) {
                    cf = displayFrames.mStableFullscreen;
                } else {
                    cf = displayFrames.mStable;
                }
            } else if ((fl & 1024) != 0 || (33554432 & fl) != 0) {
                cf = displayFrames.mOverscan;
            } else {
                cf = displayFrames.mCurrent;
            }
            if (taskBounds != null) {
                outFrame.intersect(taskBounds);
            }
            InsetUtils.insetsBetweenFrames(outFrame, cf, outContentInsets);
            InsetUtils.insetsBetweenFrames(outFrame, sf, outStableInsets);
            outDisplayCutout.set(displayFrames.mDisplayCutout.calculateRelativeTo(outFrame).getDisplayCutout());
            return this.mForceShowSystemBars;
        }
        if (layoutInScreen) {
            outFrame.set(displayFrames.mUnrestricted);
        } else {
            outFrame.set(displayFrames.mStable);
        }
        if (taskBounds != null) {
            outFrame.intersect(taskBounds);
        }
        outContentInsets.setEmpty();
        outStableInsets.setEmpty();
        outDisplayCutout.set(DisplayCutout.NO_CUTOUT);
        return this.mForceShowSystemBars;
    }

    private static int getImpliedSysUiFlagsForLayout(WindowManager.LayoutParams attrs) {
        boolean forceWindowDrawsBarBackgrounds = (attrs.privateFlags & 131072) != 0 && attrs.height == -1 && attrs.width == -1;
        if ((attrs.flags & Integer.MIN_VALUE) == 0 && !forceWindowDrawsBarBackgrounds) {
            return 0;
        }
        int impliedFlags = 0 | 512;
        return impliedFlags | 1024;
    }

    private static boolean shouldUseOutsets(WindowManager.LayoutParams attrs, int fl) {
        if (SharedDisplayManager.enable() || xpWindowManager.isApplicationAlertWindowType(attrs)) {
            return false;
        }
        return attrs.type == 2013 || (33555456 & fl) != 0;
    }

    /* loaded from: classes2.dex */
    private final class HideNavInputEventReceiver extends InputEventReceiver {
        HideNavInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        public void onInputEvent(InputEvent event) {
            try {
                if ((event instanceof MotionEvent) && (event.getSource() & 2) != 0) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getAction() == 0) {
                        boolean changed = false;
                        synchronized (DisplayPolicy.this.mLock) {
                            if (DisplayPolicy.this.mInputConsumer != null) {
                                int newVal = DisplayPolicy.this.mResettingSystemUiFlags | 2 | 1 | 4;
                                if (DisplayPolicy.this.mResettingSystemUiFlags != newVal) {
                                    DisplayPolicy.this.mResettingSystemUiFlags = newVal;
                                    changed = true;
                                }
                                int newVal2 = DisplayPolicy.this.mForceClearedSystemUiFlags | 2;
                                if (DisplayPolicy.this.mForceClearedSystemUiFlags != newVal2) {
                                    DisplayPolicy.this.mForceClearedSystemUiFlags = newVal2;
                                    changed = true;
                                    DisplayPolicy.this.mHandler.postDelayed(DisplayPolicy.this.mClearHideNavigationFlag, 1000L);
                                }
                                if (changed) {
                                    DisplayPolicy.this.mDisplayContent.reevaluateStatusBarVisibility();
                                }
                            }
                        }
                    }
                }
            } finally {
                finishInputEvent(event, false);
            }
        }
    }

    public void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {
        WindowState windowState;
        displayFrames.onBeginLayout();
        this.mSystemGestures.screenWidth = displayFrames.mUnrestricted.width();
        this.mSystemGestures.screenHeight = displayFrames.mUnrestricted.height();
        int sysui = this.mLastSystemUiFlags;
        boolean navVisible = (sysui & 2) == 0;
        boolean navTranslucent = ((-2147450880) & sysui) != 0;
        boolean immersive = (sysui & 2048) != 0;
        boolean immersiveSticky = (sysui & 4096) != 0;
        boolean navAllowedHidden = immersive || immersiveSticky;
        boolean navTranslucent2 = navTranslucent & (!immersiveSticky);
        boolean navTranslucent3 = isStatusBarKeyguard();
        boolean isKeyguardShowing = navTranslucent3 && !this.mService.mPolicy.isKeyguardOccluded();
        boolean statusBarForcesShowingNavigation = (isKeyguardShowing || (windowState = this.mStatusBar) == null || (windowState.getAttrs().privateFlags & DumpState.DUMP_VOLUMES) == 0) ? false : true;
        if (navVisible || navAllowedHidden) {
            WindowManagerPolicy.InputConsumer inputConsumer = this.mInputConsumer;
            if (inputConsumer != null) {
                Handler handler = this.mHandler;
                handler.sendMessage(handler.obtainMessage(3, inputConsumer));
                this.mInputConsumer = null;
            }
        } else if (this.mInputConsumer == null && this.mStatusBar != null && canHideNavigationBar()) {
            this.mInputConsumer = this.mService.createInputConsumer(this.mHandler.getLooper(), "nav_input_consumer", new InputEventReceiver.Factory() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$FpQuLkFb2EnHvk4Uzhr9G5Rn_xI
                public final InputEventReceiver createInputEventReceiver(InputChannel inputChannel, Looper looper) {
                    return DisplayPolicy.this.lambda$beginLayoutLw$8$DisplayPolicy(inputChannel, looper);
                }
            }, displayFrames.mDisplayId);
            InputManager.getInstance().setPointerIconType(0);
        }
        boolean updateSysUiVisibility = layoutNavigationBar(displayFrames, uiMode, navVisible | (!canHideNavigationBar()), navTranslucent2, navAllowedHidden, statusBarForcesShowingNavigation);
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.i(TAG, "mDock rect:" + displayFrames.mDock);
        }
        if (updateSysUiVisibility | layoutStatusBar(displayFrames, sysui, isKeyguardShowing)) {
            updateSystemUiVisibilityLw();
        }
        layoutScreenDecorWindows(displayFrames);
        if (displayFrames.mDisplayCutoutSafe.top > displayFrames.mUnrestricted.top) {
            displayFrames.mDisplayCutoutSafe.top = Math.max(displayFrames.mDisplayCutoutSafe.top, displayFrames.mStable.top);
        }
        displayFrames.mCurrent.inset(this.mForwardedInsets);
        displayFrames.mContent.inset(this.mForwardedInsets);
    }

    public /* synthetic */ InputEventReceiver lambda$beginLayoutLw$8$DisplayPolicy(InputChannel x$0, Looper x$1) {
        return new HideNavInputEventReceiver(x$0, x$1);
    }

    private void layoutScreenDecorWindows(DisplayFrames displayFrames) {
        DisplayPolicy displayPolicy = this;
        if (displayPolicy.mScreenDecorWindows.isEmpty()) {
            return;
        }
        sTmpRect.setEmpty();
        int displayId = displayFrames.mDisplayId;
        Rect dockFrame = displayFrames.mDock;
        int displayHeight = displayFrames.mDisplayHeight;
        int displayWidth = displayFrames.mDisplayWidth;
        int i = displayPolicy.mScreenDecorWindows.size() - 1;
        while (i >= 0) {
            WindowState w = displayPolicy.mScreenDecorWindows.valueAt(i);
            if (w.getDisplayId() == displayId && w.isVisibleLw()) {
                w.getWindowFrames().setFrames(displayFrames.mUnrestricted, displayFrames.mUnrestricted, displayFrames.mUnrestricted, displayFrames.mUnrestricted, displayFrames.mUnrestricted, sTmpRect, displayFrames.mUnrestricted, displayFrames.mUnrestricted);
                w.getWindowFrames().setDisplayCutout(displayFrames.mDisplayCutout);
                w.computeFrameLw();
                Rect frame = w.getFrameLw();
                if (frame.left <= 0 && frame.top <= 0) {
                    if (frame.bottom >= displayHeight) {
                        dockFrame.left = Math.max(frame.right, dockFrame.left);
                    } else if (frame.right >= displayWidth) {
                        dockFrame.top = Math.max(frame.bottom, dockFrame.top);
                    } else {
                        Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w + " not docked on left or top of display. frame=" + frame + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                    }
                } else if (frame.right >= displayWidth && frame.bottom >= displayHeight) {
                    if (frame.top <= 0) {
                        dockFrame.right = Math.min(frame.left, dockFrame.right);
                    } else if (frame.left <= 0) {
                        dockFrame.bottom = Math.min(frame.top, dockFrame.bottom);
                    } else {
                        Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w + " not docked on right or bottom of display. frame=" + frame + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                    }
                } else {
                    Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w + " not docked on one of the sides of the display. frame=" + frame + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                }
            }
            i--;
            displayPolicy = this;
        }
        displayFrames.mRestricted.set(dockFrame);
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mSystem.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        displayFrames.mRestrictedOverscan.set(dockFrame);
    }

    private boolean layoutStatusBar(DisplayFrames displayFrames, int sysui, boolean isKeyguardShowing) {
        if (this.mStatusBar == null) {
            return false;
        }
        sTmpRect.setEmpty();
        WindowFrames windowFrames = this.mStatusBar.getWindowFrames();
        windowFrames.setFrames(displayFrames.mUnrestricted, displayFrames.mUnrestricted, displayFrames.mStable, displayFrames.mStable, displayFrames.mStable, sTmpRect, displayFrames.mStable, displayFrames.mStable);
        windowFrames.setDisplayCutout(displayFrames.mDisplayCutout);
        this.mStatusBar.computeFrameLw();
        displayFrames.mStable.top = displayFrames.mUnrestricted.top + this.mStatusBarHeightForRotation[displayFrames.mRotation];
        displayFrames.mStable.top = Math.max(displayFrames.mStable.top, displayFrames.mDisplayCutoutSafe.top);
        sTmpRect.set(this.mStatusBar.getContentFrameLw());
        sTmpRect.intersect(displayFrames.mDisplayCutoutSafe);
        sTmpRect.top = this.mStatusBar.getContentFrameLw().top;
        sTmpRect.bottom = displayFrames.mStable.top;
        this.mStatusBarController.setContentFrame(sTmpRect);
        boolean statusBarTransient = (67108864 & sysui) != 0;
        boolean statusBarTranslucent = (1073741832 & sysui) != 0;
        if (this.mStatusBar.isVisibleLw() && !statusBarTransient) {
            Rect dockFrame = displayFrames.mDock;
            dockFrame.top = displayFrames.mStable.top;
            displayFrames.mContent.set(dockFrame);
            displayFrames.mVoiceContent.set(dockFrame);
            displayFrames.mCurrent.set(dockFrame);
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.v(TAG, "Status bar: " + String.format("dock=%s content=%s cur=%s", dockFrame.toString(), displayFrames.mContent.toString(), displayFrames.mCurrent.toString()));
            }
            if (!statusBarTranslucent && !this.mStatusBarController.wasRecentlyTranslucent() && !this.mStatusBar.isAnimatingLw()) {
                displayFrames.mSystem.top = displayFrames.mStable.top;
            }
        }
        return this.mStatusBarController.checkHiddenLw();
    }

    private boolean layoutNavigationBar(DisplayFrames displayFrames, int uiMode, boolean navVisible, boolean navTranslucent, boolean navAllowedHidden, boolean statusBarForcesShowingNavigation) {
        if (this.mNavigationBar == null) {
            return false;
        }
        Rect navigationFrame = sTmpNavFrame;
        boolean transientNavBarShowing = this.mNavigationBarController.isTransientShowing();
        int rotation = displayFrames.mRotation;
        int displayHeight = displayFrames.mDisplayHeight;
        int displayWidth = displayFrames.mDisplayWidth;
        Rect dockFrame = displayFrames.mDock;
        this.mNavigationBarPosition = navigationBarPosition(displayWidth, displayHeight, rotation);
        Rect cutoutSafeUnrestricted = sTmpRect;
        cutoutSafeUnrestricted.set(displayFrames.mUnrestricted);
        cutoutSafeUnrestricted.intersectUnchecked(displayFrames.mDisplayCutoutSafe);
        int left = this.mNavigationBarPosition;
        if (left == 4) {
            int top = cutoutSafeUnrestricted.bottom - getNavigationBarHeight(rotation, uiMode);
            int topNavBar = cutoutSafeUnrestricted.bottom - getNavigationBarFrameHeight(rotation, uiMode);
            navigationFrame.set(0, topNavBar, displayWidth, displayFrames.mUnrestricted.bottom);
            Rect rect = displayFrames.mStable;
            displayFrames.mStableFullscreen.bottom = top;
            rect.bottom = top;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect2 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.bottom = top;
                rect2.bottom = top;
                dockFrame.bottom = top;
            } else {
                this.mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.bottom = top;
            }
        } else if (left == 2) {
            int left2 = cutoutSafeUnrestricted.right - getNavigationBarWidth(rotation, uiMode);
            navigationFrame.set(left2, 0, displayFrames.mUnrestricted.right, displayHeight);
            Rect rect3 = displayFrames.mStable;
            displayFrames.mStableFullscreen.right = left2;
            rect3.right = left2;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect4 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.right = left2;
                rect4.right = left2;
                dockFrame.right = left2;
            } else {
                this.mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.right = left2;
            }
        } else if (left == 1) {
            int right = cutoutSafeUnrestricted.left + getNavigationBarWidth(rotation, uiMode);
            navigationFrame.set(displayFrames.mUnrestricted.left, 0, right, displayHeight);
            Rect rect5 = displayFrames.mStable;
            displayFrames.mStableFullscreen.left = right;
            rect5.left = right;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect6 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.left = right;
                rect6.left = right;
                dockFrame.left = right;
            } else {
                this.mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.left = right;
            }
        }
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        sTmpRect.setEmpty();
        this.mNavigationBar.getWindowFrames().setFrames(navigationFrame, navigationFrame, navigationFrame, displayFrames.mDisplayCutoutSafe, navigationFrame, sTmpRect, navigationFrame, displayFrames.mDisplayCutoutSafe);
        this.mNavigationBar.getWindowFrames().setDisplayCutout(displayFrames.mDisplayCutout);
        this.mNavigationBar.computeFrameLw();
        this.mNavigationBarController.setContentFrame(this.mNavigationBar.getContentFrameLw());
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.i(TAG, "mNavigationBar frame: " + navigationFrame);
        }
        return this.mNavigationBarController.checkHiddenLw();
    }

    private void setAttachedWindowFrames(WindowState win, int fl, int adjust, WindowState attached, boolean insetDecors, Rect pf, Rect df, Rect of, Rect cf, Rect vf, DisplayFrames displayFrames) {
        if (!win.isInputMethodTarget() && attached.isInputMethodTarget()) {
            vf.set(displayFrames.mDock);
            cf.set(displayFrames.mDock);
            of.set(displayFrames.mDock);
            df.set(displayFrames.mDock);
        } else {
            Rect parentDisplayFrame = attached.getDisplayFrameLw();
            Rect parentOverscan = attached.getOverscanFrameLw();
            WindowManager.LayoutParams attachedAttrs = attached.mAttrs;
            if ((attachedAttrs.privateFlags & 131072) != 0 && (attachedAttrs.flags & Integer.MIN_VALUE) == 0 && (attachedAttrs.systemUiVisibility & 512) == 0) {
                parentOverscan = new Rect(parentOverscan);
                parentOverscan.intersect(displayFrames.mRestrictedOverscan);
                parentDisplayFrame = new Rect(parentDisplayFrame);
                parentDisplayFrame.intersect(displayFrames.mRestrictedOverscan);
            }
            if (adjust != 16) {
                cf.set((1073741824 & fl) != 0 ? attached.getContentFrameLw() : parentOverscan);
            } else {
                cf.set(attached.getContentFrameLw());
                if (attached.isVoiceInteraction()) {
                    cf.intersectUnchecked(displayFrames.mVoiceContent);
                } else if (win.isInputMethodTarget() || attached.isInputMethodTarget()) {
                    cf.intersectUnchecked(displayFrames.mContent);
                }
            }
            df.set(insetDecors ? parentDisplayFrame : cf);
            of.set(insetDecors ? parentOverscan : cf);
            vf.set(attached.getVisibleFrameLw());
        }
        pf.set((fl & 256) == 0 ? attached.getFrameLw() : df);
    }

    private void applyStableConstraints(int sysui, int fl, Rect r, DisplayFrames displayFrames) {
        if ((sysui & 256) == 0) {
            return;
        }
        if ((fl & 1024) != 0) {
            r.intersectUnchecked(displayFrames.mStableFullscreen);
        } else {
            r.intersectUnchecked(displayFrames.mStable);
        }
    }

    private boolean canReceiveInput(WindowState win) {
        boolean notFocusable = (win.getAttrs().flags & 8) != 0;
        boolean altFocusableIm = (win.getAttrs().flags & 131072) != 0;
        boolean notFocusableForIm = notFocusable ^ altFocusableIm;
        return !notFocusableForIm;
    }

    /* JADX WARN: Removed duplicated region for block: B:173:0x04a6  */
    /* JADX WARN: Removed duplicated region for block: B:184:0x04d1  */
    /* JADX WARN: Removed duplicated region for block: B:187:0x04e1  */
    /* JADX WARN: Removed duplicated region for block: B:188:0x04f2  */
    /* JADX WARN: Removed duplicated region for block: B:293:0x0812  */
    /* JADX WARN: Removed duplicated region for block: B:294:0x0818  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void layoutWindowLw(com.android.server.wm.WindowState r56, com.android.server.wm.WindowState r57, com.android.server.wm.DisplayFrames r58) {
        /*
            Method dump skipped, instructions count: 2801
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayPolicy.layoutWindowLw(com.android.server.wm.WindowState, com.android.server.wm.WindowState, com.android.server.wm.DisplayFrames):void");
    }

    private void layoutWallpaper(DisplayFrames displayFrames, Rect pf, Rect df, Rect of, Rect cf) {
        df.set(displayFrames.mOverscan);
        pf.set(displayFrames.mOverscan);
        cf.set(displayFrames.mUnrestricted);
        of.set(displayFrames.mUnrestricted);
    }

    private void offsetInputMethodWindowLw(WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top) + win.getGivenContentInsetsLw().top;
        displayFrames.mContent.bottom = Math.min(displayFrames.mContent.bottom, top);
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
        int top2 = win.getVisibleFrameLw().top + win.getGivenVisibleInsetsLw().top;
        displayFrames.mCurrent.bottom = Math.min(displayFrames.mCurrent.bottom, top2);
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.v(TAG, "Input method: mDockBottom=" + displayFrames.mDock.bottom + " mContentBottom=" + displayFrames.mContent.bottom + " mCurBottom=" + displayFrames.mCurrent.bottom);
        }
    }

    private void offsetVoiceInputWindowLw(WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top);
        int top2 = top + win.getGivenContentInsetsLw().top;
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top2);
    }

    public void beginPostLayoutPolicyLw() {
        this.mTopFullscreenOpaqueWindowState = null;
        this.mTopFullscreenOpaqueOrDimmingWindowState = null;
        this.mTopDockedOpaqueWindowState = null;
        this.mTopDockedOpaqueOrDimmingWindowState = null;
        this.mForceStatusBar = false;
        this.mForceStatusBarFromKeyguard = false;
        this.mForceStatusBarTransparent = false;
        this.mForcingShowNavBar = false;
        this.mForcingShowNavBarLayer = -1;
        this.mAllowLockscreenWhenOn = false;
        this.mShowingDream = false;
        this.mWindowSleepTokenNeeded = false;
    }

    public void applyPostLayoutPolicyLw(WindowState win, WindowManager.LayoutParams attrs, WindowState attached, WindowState imeTarget) {
        boolean affectsSystemUi = win.canAffectSystemUiFlags();
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.i(TAG, "Win " + win + ": affectsSystemUi=" + affectsSystemUi);
        }
        this.mService.mPolicy.applyKeyguardPolicyLw(win, imeTarget);
        int fl = PolicyControl.getWindowFlags(win, attrs);
        if (this.mTopFullscreenOpaqueWindowState == null && affectsSystemUi && attrs.type == 2011) {
            this.mForcingShowNavBar = true;
            this.mForcingShowNavBarLayer = win.getSurfaceLayer();
        }
        if (attrs.type == 2000) {
            if ((attrs.privateFlags & 1024) != 0) {
                this.mForceStatusBarFromKeyguard = true;
            }
            if ((attrs.privateFlags & 4096) != 0) {
                this.mForceStatusBarTransparent = true;
            }
        }
        boolean inFullScreenOrSplitScreenSecondaryWindowingMode = false;
        boolean appWindow = attrs.type >= 1 && attrs.type < 2000;
        int windowingMode = win.getWindowingMode();
        if (windowingMode == 1 || windowingMode == 4) {
            inFullScreenOrSplitScreenSecondaryWindowingMode = true;
        }
        if (this.mTopFullscreenOpaqueWindowState == null && affectsSystemUi) {
            if ((fl & 2048) != 0) {
                this.mForceStatusBar = true;
            }
            if (attrs.type == 2023 && (!this.mDreamingLockscreen || (win.isVisibleLw() && win.hasDrawnLw()))) {
                this.mShowingDream = true;
                appWindow = true;
            }
            if (appWindow && attached == null && attrs.isFullscreen() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, "Fullscreen window: " + win);
                }
                this.mTopFullscreenOpaqueWindowState = win;
                if (this.mTopFullscreenOpaqueOrDimmingWindowState == null) {
                    this.mTopFullscreenOpaqueOrDimmingWindowState = win;
                }
                if ((fl & 1) != 0) {
                    this.mAllowLockscreenWhenOn = true;
                }
            }
        }
        if (affectsSystemUi && attrs.type == 2031) {
            if (this.mTopFullscreenOpaqueWindowState == null) {
                this.mTopFullscreenOpaqueWindowState = win;
                if (this.mTopFullscreenOpaqueOrDimmingWindowState == null) {
                    this.mTopFullscreenOpaqueOrDimmingWindowState = win;
                }
            }
            if (this.mTopDockedOpaqueWindowState == null) {
                this.mTopDockedOpaqueWindowState = win;
                if (this.mTopDockedOpaqueOrDimmingWindowState == null) {
                    this.mTopDockedOpaqueOrDimmingWindowState = win;
                }
            }
        }
        if (this.mTopFullscreenOpaqueOrDimmingWindowState == null && affectsSystemUi && win.isDimming() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
            this.mTopFullscreenOpaqueOrDimmingWindowState = win;
        }
        if (this.mTopDockedOpaqueWindowState == null && affectsSystemUi && appWindow && attached == null && attrs.isFullscreen() && windowingMode == 3) {
            this.mTopDockedOpaqueWindowState = win;
            if (this.mTopDockedOpaqueOrDimmingWindowState == null) {
                this.mTopDockedOpaqueOrDimmingWindowState = win;
            }
        }
        if (this.mTopDockedOpaqueOrDimmingWindowState == null && affectsSystemUi && win.isDimming() && windowingMode == 3) {
            this.mTopDockedOpaqueOrDimmingWindowState = win;
        }
    }

    public int finishPostLayoutPolicyLw() {
        int changes = 0;
        boolean topIsFullscreen = false;
        boolean z = true;
        if (!this.mShowingDream) {
            this.mDreamingLockscreen = this.mService.mPolicy.isKeyguardShowingAndNotOccluded();
            if (this.mDreamingSleepTokenNeeded) {
                this.mDreamingSleepTokenNeeded = false;
                this.mHandler.obtainMessage(1, 0, 1).sendToTarget();
            }
        } else if (!this.mDreamingSleepTokenNeeded) {
            this.mDreamingSleepTokenNeeded = true;
            this.mHandler.obtainMessage(1, 1, 1).sendToTarget();
        }
        if (this.mStatusBar != null) {
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.i(TAG, "force=" + this.mForceStatusBar + " forcefkg=" + this.mForceStatusBarFromKeyguard + " top=" + this.mTopFullscreenOpaqueWindowState);
            }
            boolean shouldBeTransparent = (!this.mForceStatusBarTransparent || this.mForceStatusBar || this.mForceStatusBarFromKeyguard) ? false : true;
            if (!shouldBeTransparent) {
                this.mStatusBarController.setShowTransparent(false);
            } else if (!this.mStatusBar.isVisibleLw()) {
                this.mStatusBarController.setShowTransparent(true);
            }
            boolean statusBarForcesShowingNavigation = (this.mStatusBar.getAttrs().privateFlags & DumpState.DUMP_VOLUMES) != 0;
            boolean topAppHidesStatusBar = topAppHidesStatusBar();
            if (this.mForceStatusBar || this.mForceStatusBarFromKeyguard || this.mForceStatusBarTransparent || statusBarForcesShowingNavigation) {
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, "Showing status bar: forced");
                }
                if (this.mStatusBarController.setBarShowingLw(true)) {
                    changes = 0 | 1;
                }
                if (!this.mTopIsFullscreen || !this.mStatusBar.isAnimatingLw()) {
                    z = false;
                }
                topIsFullscreen = z;
                if ((this.mForceStatusBarFromKeyguard || statusBarForcesShowingNavigation) && this.mStatusBarController.isTransientShowing()) {
                    StatusBarController statusBarController = this.mStatusBarController;
                    int i = this.mLastSystemUiFlags;
                    statusBarController.updateVisibilityLw(false, i, i);
                }
            } else if (this.mTopFullscreenOpaqueWindowState != null) {
                topIsFullscreen = topAppHidesStatusBar;
                if (this.mStatusBarController.isTransientShowing()) {
                    if (this.mStatusBarController.setBarShowingLw(true)) {
                        changes = 0 | 1;
                    }
                } else if (topIsFullscreen && !this.mDisplayContent.isStackVisible(5) && !this.mDisplayContent.isStackVisible(3)) {
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                        Slog.v(TAG, "** HIDING status bar");
                    }
                    if (this.mStatusBarController.setBarShowingLw(false)) {
                        changes = 0 | 1;
                    } else if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                        Slog.v(TAG, "Status bar already hiding");
                    }
                } else {
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                        Slog.v(TAG, "** SHOWING status bar: top is not fullscreen");
                    }
                    if (this.mStatusBarController.setBarShowingLw(true)) {
                        changes = 0 | 1;
                    }
                    topAppHidesStatusBar = false;
                }
            }
            this.mStatusBarController.setTopAppHidesStatusBar(topAppHidesStatusBar);
        }
        boolean shouldBeTransparent2 = this.mTopIsFullscreen;
        if (shouldBeTransparent2 != topIsFullscreen) {
            if (!topIsFullscreen) {
                changes |= 1;
            }
            this.mTopIsFullscreen = topIsFullscreen;
        }
        if ((updateSystemUiVisibilityLw() & SYSTEM_UI_CHANGING_LAYOUT) != 0) {
            changes |= 1;
        }
        boolean z2 = this.mShowingDream;
        if (z2 != this.mLastShowingDream) {
            this.mLastShowingDream = z2;
            this.mService.notifyShowingDreamChanged();
        }
        updateWindowSleepToken();
        this.mService.mPolicy.setAllowLockscreenWhenOn(getDisplayId(), this.mAllowLockscreenWhenOn);
        return changes;
    }

    private void updateWindowSleepToken() {
        if (this.mWindowSleepTokenNeeded && !this.mLastWindowSleepTokenNeeded) {
            this.mHandler.removeCallbacks(this.mReleaseSleepTokenRunnable);
            this.mHandler.post(this.mAcquireSleepTokenRunnable);
        } else if (!this.mWindowSleepTokenNeeded && this.mLastWindowSleepTokenNeeded) {
            this.mHandler.removeCallbacks(this.mAcquireSleepTokenRunnable);
            this.mHandler.post(this.mReleaseSleepTokenRunnable);
        }
        this.mLastWindowSleepTokenNeeded = this.mWindowSleepTokenNeeded;
    }

    private boolean topAppHidesStatusBar() {
        WindowState windowState = this.mTopFullscreenOpaqueWindowState;
        if (windowState == null) {
            return false;
        }
        int fl = PolicyControl.getWindowFlags(null, windowState.getAttrs());
        if (WindowManagerService.localLOGV) {
            Slog.d(TAG, "frame: " + this.mTopFullscreenOpaqueWindowState.getFrameLw());
            Slog.d(TAG, "attr: " + this.mTopFullscreenOpaqueWindowState.getAttrs() + " lp.flags=0x" + Integer.toHexString(fl));
        }
        return ((fl & 1024) == 0 && (this.mLastSystemUiFlags & 4) == 0) ? false : true;
    }

    public void switchUser() {
        updateCurrentUserResources();
    }

    public void onOverlayChangedLw() {
        updateCurrentUserResources();
        onConfigurationChanged();
        this.mSystemGestures.onConfigurationChanged();
    }

    public void onConfigurationChanged() {
        DisplayRotation displayRotation = this.mDisplayContent.getDisplayRotation();
        Resources res = getCurrentUserResources();
        int portraitRotation = displayRotation.getPortraitRotation();
        int upsideDownRotation = displayRotation.getUpsideDownRotation();
        int landscapeRotation = displayRotation.getLandscapeRotation();
        int seascapeRotation = displayRotation.getSeascapeRotation();
        int uiMode = this.mService.mPolicy.getUiMode();
        if (hasStatusBar()) {
            int[] iArr = this.mStatusBarHeightForRotation;
            int dimensionPixelSize = res.getDimensionPixelSize(17105440);
            iArr[upsideDownRotation] = dimensionPixelSize;
            iArr[portraitRotation] = dimensionPixelSize;
            int[] iArr2 = this.mStatusBarHeightForRotation;
            int dimensionPixelSize2 = res.getDimensionPixelSize(17105439);
            iArr2[seascapeRotation] = dimensionPixelSize2;
            iArr2[landscapeRotation] = dimensionPixelSize2;
        } else {
            int[] iArr3 = this.mStatusBarHeightForRotation;
            iArr3[seascapeRotation] = 0;
            iArr3[landscapeRotation] = 0;
            iArr3[upsideDownRotation] = 0;
            iArr3[portraitRotation] = 0;
        }
        int[] iArr4 = this.mNavigationBarHeightForRotationDefault;
        int dimensionPixelSize3 = res.getDimensionPixelSize(17105292);
        iArr4[upsideDownRotation] = dimensionPixelSize3;
        iArr4[portraitRotation] = dimensionPixelSize3;
        int[] iArr5 = this.mNavigationBarHeightForRotationDefault;
        int dimensionPixelSize4 = res.getDimensionPixelSize(17105294);
        iArr5[seascapeRotation] = dimensionPixelSize4;
        iArr5[landscapeRotation] = dimensionPixelSize4;
        int[] iArr6 = this.mNavigationBarFrameHeightForRotationDefault;
        int dimensionPixelSize5 = res.getDimensionPixelSize(17105289);
        iArr6[upsideDownRotation] = dimensionPixelSize5;
        iArr6[portraitRotation] = dimensionPixelSize5;
        int[] iArr7 = this.mNavigationBarFrameHeightForRotationDefault;
        int dimensionPixelSize6 = res.getDimensionPixelSize(17105290);
        iArr7[seascapeRotation] = dimensionPixelSize6;
        iArr7[landscapeRotation] = dimensionPixelSize6;
        int[] iArr8 = this.mNavigationBarWidthForRotationDefault;
        int dimensionPixelSize7 = res.getDimensionPixelSize(17105297);
        iArr8[seascapeRotation] = dimensionPixelSize7;
        iArr8[landscapeRotation] = dimensionPixelSize7;
        iArr8[upsideDownRotation] = dimensionPixelSize7;
        iArr8[portraitRotation] = dimensionPixelSize7;
        int[] iArr9 = this.mStatusBarHeightForRotation;
        int statusBarHeight = xpPhoneWindowManager.get(this.mContext).getStatusBarHeight();
        iArr9[seascapeRotation] = statusBarHeight;
        iArr9[landscapeRotation] = statusBarHeight;
        iArr9[upsideDownRotation] = statusBarHeight;
        iArr9[portraitRotation] = statusBarHeight;
        int[] iArr10 = this.mNavigationBarHeightForRotationDefault;
        int navigationBarHeight = xpPhoneWindowManager.get(this.mContext).getNavigationBarHeight();
        iArr10[seascapeRotation] = navigationBarHeight;
        iArr10[landscapeRotation] = navigationBarHeight;
        iArr10[upsideDownRotation] = navigationBarHeight;
        iArr10[portraitRotation] = navigationBarHeight;
        int[] iArr11 = this.mNavigationBarFrameHeightForRotationDefault;
        int navigationBarHeight2 = xpPhoneWindowManager.get(this.mContext).getNavigationBarHeight();
        iArr11[seascapeRotation] = navigationBarHeight2;
        iArr11[landscapeRotation] = navigationBarHeight2;
        iArr11[upsideDownRotation] = navigationBarHeight2;
        iArr11[portraitRotation] = navigationBarHeight2;
        int[] iArr12 = this.mNavigationBarWidthForRotationDefault;
        int navigationBarWidth = xpPhoneWindowManager.get(this.mContext).getNavigationBarWidth();
        iArr12[seascapeRotation] = navigationBarWidth;
        iArr12[landscapeRotation] = navigationBarWidth;
        iArr12[upsideDownRotation] = navigationBarWidth;
        iArr12[portraitRotation] = navigationBarWidth;
        this.mNavBarOpacityMode = res.getInteger(17694849);
        this.mSideGestureInset = res.getDimensionPixelSize(17105051);
        this.mNavigationBarLetsThroughTaps = res.getBoolean(17891488);
        this.mNavigationBarAlwaysShowOnSideGesture = res.getBoolean(17891485);
        this.mBottomGestureAdditionalInset = res.getDimensionPixelSize(17105291) - getNavigationBarFrameHeight(portraitRotation, uiMode);
        updateConfigurationAndScreenSizeDependentBehaviors();
        this.mWindowOutsetBottom = ScreenShapeHelper.getWindowOutsetBottomPx(this.mContext.getResources());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateConfigurationAndScreenSizeDependentBehaviors() {
        Resources res = getCurrentUserResources();
        this.mNavigationBarCanMove = this.mDisplayContent.mBaseDisplayWidth != this.mDisplayContent.mBaseDisplayHeight && res.getBoolean(17891486);
        this.mAllowSeamlessRotationDespiteNavBarMoving = res.getBoolean(17891346);
    }

    private void updateCurrentUserResources() {
        int userId = this.mService.mAmInternal.getCurrentUserId();
        Context uiContext = getSystemUiContext();
        if (userId == 0) {
            this.mCurrentUserResources = uiContext.getResources();
            return;
        }
        LoadedApk pi = ActivityThread.currentActivityThread().getPackageInfo(uiContext.getPackageName(), (CompatibilityInfo) null, 0, userId);
        this.mCurrentUserResources = ResourcesManager.getInstance().getResources((IBinder) null, pi.getResDir(), (String[]) null, pi.getOverlayDirs(), pi.getApplicationInfo().sharedLibraryFiles, this.mDisplayContent.getDisplayId(), (Configuration) null, uiContext.getResources().getCompatibilityInfo(), (ClassLoader) null);
    }

    @VisibleForTesting
    Resources getCurrentUserResources() {
        if (this.mCurrentUserResources == null) {
            updateCurrentUserResources();
        }
        return this.mCurrentUserResources;
    }

    @VisibleForTesting
    Context getContext() {
        return this.mContext;
    }

    private Context getSystemUiContext() {
        ContextImpl systemUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        return this.mDisplayContent.isDefaultDisplay ? systemUiContext : systemUiContext.createDisplayContext(this.mDisplayContent.getDisplay());
    }

    private int getNavigationBarWidth(int rotation, int uiMode) {
        return this.mNavigationBarWidthForRotationDefault[rotation];
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyDisplayReady() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$m-UPXUZKrPpeFUjrauzoJMNbYjM
            @Override // java.lang.Runnable
            public final void run() {
                DisplayPolicy.this.lambda$notifyDisplayReady$9$DisplayPolicy();
            }
        });
    }

    public /* synthetic */ void lambda$notifyDisplayReady$9$DisplayPolicy() {
        int displayId = getDisplayId();
        getStatusBarManagerInternal().onDisplayReady(displayId);
        WallpaperManagerInternal wpMgr = (WallpaperManagerInternal) LocalServices.getService(WallpaperManagerInternal.class);
        if (wpMgr != null) {
            wpMgr.onDisplayReady(displayId);
        }
    }

    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode, DisplayCutout displayCutout) {
        int navBarPosition;
        int width = fullWidth;
        if (hasNavigationBar() && ((navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation)) == 1 || navBarPosition == 2)) {
            width -= getNavigationBarWidth(rotation, uiMode);
        }
        if (displayCutout != null) {
            return width - (displayCutout.getSafeInsetLeft() + displayCutout.getSafeInsetRight());
        }
        return width;
    }

    private int getNavigationBarHeight(int rotation, int uiMode) {
        return this.mNavigationBarHeightForRotationDefault[rotation];
    }

    private int getNavigationBarFrameHeight(int rotation, int uiMode) {
        return this.mNavigationBarFrameHeightForRotationDefault[rotation];
    }

    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode, DisplayCutout displayCutout) {
        int height = fullHeight;
        if (hasNavigationBar()) {
            int navigationBarHeight = getNavigationBarHeight(rotation, uiMode);
            height = xpPhoneWindowManager.get(this.mContext).getDisplayHeight(fullWidth, fullHeight, navigationBarHeight, this.mNavigationBarCanMove);
        }
        if (displayCutout != null) {
            return height - (displayCutout.getSafeInsetTop() + displayCutout.getSafeInsetBottom());
        }
        return height;
    }

    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode, DisplayCutout displayCutout) {
        return getNonDecorDisplayWidth(fullWidth, fullHeight, rotation, uiMode, displayCutout);
    }

    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode, DisplayCutout displayCutout) {
        int statusBarHeight = this.mStatusBarHeightForRotation[rotation];
        if (displayCutout != null) {
            statusBarHeight = Math.max(0, statusBarHeight - displayCutout.getSafeInsetTop());
        }
        return getNonDecorDisplayHeight(fullWidth, fullHeight, rotation, uiMode, displayCutout) - statusBarHeight;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float getWindowCornerRadius() {
        if (this.mDisplayContent.getDisplay().getType() == 1) {
            return ScreenDecorationsUtils.getWindowCornerRadius(this.mContext.getResources());
        }
        return 0.0f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isShowingDreamLw() {
        return this.mShowingDream;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void convertNonDecorInsetsToStableInsets(Rect inOutInsets, int rotation) {
        inOutInsets.top = Math.max(inOutInsets.top, this.mStatusBarHeightForRotation[rotation]);
    }

    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight, DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();
        getNonDecorInsetsLw(displayRotation, displayWidth, displayHeight, displayCutout, outInsets);
        convertNonDecorInsetsToStableInsets(outInsets, displayRotation);
    }

    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight, DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();
        if (hasNavigationBar()) {
            int uiMode = this.mService.mPolicy.getUiMode();
            int position = navigationBarPosition(displayWidth, displayHeight, displayRotation);
            if (position == 4) {
                outInsets.bottom = getNavigationBarHeight(displayRotation, uiMode);
            } else if (position == 2) {
                outInsets.right = getNavigationBarWidth(displayRotation, uiMode);
            } else if (position == 1) {
                outInsets.left = getNavigationBarWidth(displayRotation, uiMode);
            }
        }
        if (displayCutout != null) {
            outInsets.left += displayCutout.getSafeInsetLeft();
            outInsets.top += displayCutout.getSafeInsetTop();
            outInsets.right += displayCutout.getSafeInsetRight();
            outInsets.bottom += displayCutout.getSafeInsetBottom();
        }
    }

    public void setForwardedInsets(Insets forwardedInsets) {
        this.mForwardedInsets = forwardedInsets;
    }

    public Insets getForwardedInsets() {
        return this.mForwardedInsets;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int navigationBarPosition(int displayWidth, int displayHeight, int displayRotation) {
        return xpPhoneWindowManager.get(this.mContext).navigationBarPosition();
    }

    public int getNavBarPosition() {
        return this.mNavigationBarPosition;
    }

    public int focusChangedLw(WindowState lastFocus, WindowState newFocus) {
        this.mFocusedWindow = newFocus;
        this.mLastFocusedWindow = lastFocus;
        if (this.mDisplayContent.isDefaultDisplay) {
            this.mService.mPolicy.onDefaultDisplayFocusChangedLw(newFocus);
        }
        if ((updateSystemUiVisibilityLw() & SYSTEM_UI_CHANGING_LAYOUT) != 0) {
            return 1;
        }
        return 0;
    }

    public boolean allowAppAnimationsLw() {
        return !this.mShowingDream;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateDreamingSleepToken(boolean acquire) {
        if (acquire) {
            int displayId = getDisplayId();
            if (this.mDreamingSleepToken == null) {
                ActivityTaskManagerInternal activityTaskManagerInternal = this.mService.mAtmInternal;
                this.mDreamingSleepToken = activityTaskManagerInternal.acquireSleepToken("DreamOnDisplay" + displayId, displayId);
                return;
            }
            return;
        }
        ActivityTaskManagerInternal.SleepToken sleepToken = this.mDreamingSleepToken;
        if (sleepToken != null) {
            sleepToken.release();
            this.mDreamingSleepToken = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestTransientBars(WindowState swipeTarget) {
        synchronized (this.mLock) {
            if (this.mService.mPolicy.isUserSetupComplete()) {
                boolean sb = this.mStatusBarController.checkShowTransientBarLw();
                boolean nb = this.mNavigationBarController.checkShowTransientBarLw() && !isNavBarEmpty(this.mLastSystemUiFlags);
                if (sb || nb) {
                    if (!nb && swipeTarget == this.mNavigationBar) {
                        return;
                    }
                    if (sb) {
                        this.mStatusBarController.showTransient();
                    }
                    if (nb) {
                        this.mNavigationBarController.showTransient();
                    }
                    this.mImmersiveModeConfirmation.confirmCurrentPrompt();
                    updateSystemUiVisibilityLw();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disposeInputConsumer(WindowManagerPolicy.InputConsumer inputConsumer) {
        if (inputConsumer != null) {
            inputConsumer.dismiss();
        }
    }

    private boolean isStatusBarKeyguard() {
        WindowState windowState = this.mStatusBar;
        return (windowState == null || (windowState.getAttrs().privateFlags & 1024) == 0) ? false : true;
    }

    private boolean isKeyguardOccluded() {
        return this.mService.mPolicy.isKeyguardOccluded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetSystemUiVisibilityLw() {
        this.mLastSystemUiFlags = 0;
        updateSystemUiVisibilityLw();
    }

    private int updateSystemUiVisibilityLw() {
        WindowState winCandidate;
        int tmpVisibility;
        WindowState windowState;
        WindowState winCandidate2 = this.mFocusedWindow;
        if (winCandidate2 == null) {
            winCandidate2 = this.mTopFullscreenOpaqueWindowState;
        }
        if (winCandidate2 != null) {
            if (winCandidate2.getAttrs().token != this.mImmersiveModeConfirmation.getWindowToken()) {
                winCandidate = winCandidate2;
            } else {
                WindowState windowState2 = this.mLastFocusedWindow;
                boolean lastFocusCanReceiveKeys = windowState2 != null && windowState2.canReceiveKeys();
                if (isStatusBarKeyguard()) {
                    windowState = this.mStatusBar;
                } else {
                    windowState = lastFocusCanReceiveKeys ? this.mLastFocusedWindow : this.mTopFullscreenOpaqueWindowState;
                }
                WindowState winCandidate3 = windowState;
                if (winCandidate3 == null) {
                    return 0;
                }
                winCandidate = winCandidate3;
            }
            final WindowState win = winCandidate;
            if ((win.getAttrs().privateFlags & 1024) == 0 || !isKeyguardOccluded()) {
                this.mDisplayContent.getInsetsStateController().onBarControllingWindowChanged(this.mTopFullscreenOpaqueWindowState);
                int tmpVisibility2 = PolicyControl.getSystemUiVisibility(win, null) & (~this.mResettingSystemUiFlags) & (~this.mForceClearedSystemUiFlags);
                if (this.mForcingShowNavBar && win.getSurfaceLayer() < this.mForcingShowNavBarLayer) {
                    tmpVisibility = tmpVisibility2 & (~PolicyControl.adjustClearableFlags(win, 7));
                } else {
                    tmpVisibility = tmpVisibility2;
                }
                final int fullscreenVisibility = updateLightStatusBarLw(0, this.mTopFullscreenOpaqueWindowState, this.mTopFullscreenOpaqueOrDimmingWindowState);
                final int dockedVisibility = updateLightStatusBarLw(0, this.mTopDockedOpaqueWindowState, this.mTopDockedOpaqueOrDimmingWindowState);
                this.mService.getStackBounds(0, 2, this.mNonDockedStackBounds);
                this.mService.getStackBounds(3, 1, this.mDockedStackBounds);
                Pair<Integer, Boolean> result = updateSystemBarsLw(win, this.mLastSystemUiFlags, tmpVisibility);
                final int visibility = ((Integer) result.first).intValue();
                int diff = visibility ^ this.mLastSystemUiFlags;
                int fullscreenDiff = fullscreenVisibility ^ this.mLastFullscreenStackSysUiFlags;
                int dockedDiff = dockedVisibility ^ this.mLastDockedStackSysUiFlags;
                final boolean needsMenu = win.getNeedsMenuLw(this.mTopFullscreenOpaqueWindowState);
                if (diff == 0 && fullscreenDiff == 0 && dockedDiff == 0 && this.mLastFocusNeedsMenu == needsMenu && this.mFocusedApp == win.getAppToken() && this.mLastNonDockedStackBounds.equals(this.mNonDockedStackBounds) && this.mLastDockedStackBounds.equals(this.mDockedStackBounds)) {
                    return 0;
                }
                this.mLastSystemUiFlags = visibility;
                this.mLastFullscreenStackSysUiFlags = fullscreenVisibility;
                this.mLastDockedStackSysUiFlags = dockedVisibility;
                this.mLastFocusNeedsMenu = needsMenu;
                this.mFocusedApp = win.getAppToken();
                this.mLastNonDockedStackBounds.set(this.mNonDockedStackBounds);
                this.mLastDockedStackBounds.set(this.mDockedStackBounds);
                final Rect fullscreenStackBounds = new Rect(this.mNonDockedStackBounds);
                final Rect dockedStackBounds = new Rect(this.mDockedStackBounds);
                final boolean isNavbarColorManagedByIme = ((Boolean) result.second).booleanValue();
                this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$DisplayPolicy$qQY9m_Itua9TDy-Nk3zzDxvjEwE
                    @Override // java.lang.Runnable
                    public final void run() {
                        DisplayPolicy.this.lambda$updateSystemUiVisibilityLw$10$DisplayPolicy(visibility, fullscreenVisibility, dockedVisibility, fullscreenStackBounds, dockedStackBounds, isNavbarColorManagedByIme, win, needsMenu);
                    }
                });
                return diff;
            }
            return 0;
        }
        return 0;
    }

    public /* synthetic */ void lambda$updateSystemUiVisibilityLw$10$DisplayPolicy(int visibility, int fullscreenVisibility, int dockedVisibility, Rect fullscreenStackBounds, Rect dockedStackBounds, boolean isNavbarColorManagedByIme, WindowState win, boolean needsMenu) {
        StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
        if (statusBar != null) {
            int displayId = getDisplayId();
            statusBar.setSystemUiVisibility(displayId, visibility, fullscreenVisibility, dockedVisibility, -1, fullscreenStackBounds, dockedStackBounds, isNavbarColorManagedByIme, win.toString());
            statusBar.topAppWindowChanged(displayId, needsMenu);
        }
    }

    private int updateLightStatusBarLw(int vis, WindowState opaque, WindowState opaqueOrDimming) {
        boolean onKeyguard = isStatusBarKeyguard() && !isKeyguardOccluded();
        WindowState statusColorWin = onKeyguard ? this.mStatusBar : opaqueOrDimming;
        if (statusColorWin != null && (statusColorWin == opaque || onKeyguard)) {
            return (vis & (-8193)) | (PolicyControl.getSystemUiVisibility(statusColorWin, null) & 8192);
        }
        if (statusColorWin != null && statusColorWin.isDimming()) {
            return vis & (-8193);
        }
        return vis;
    }

    @VisibleForTesting
    static WindowState chooseNavigationColorWindowLw(WindowState opaque, WindowState opaqueOrDimming, WindowState imeWindow, int navBarPosition) {
        boolean imeWindowCanNavColorWindow = imeWindow != null && imeWindow.isVisibleLw() && navBarPosition == 4 && (PolicyControl.getWindowFlags(imeWindow, null) & Integer.MIN_VALUE) != 0;
        if (opaque != null && opaqueOrDimming == opaque) {
            return imeWindowCanNavColorWindow ? imeWindow : opaque;
        } else if (opaqueOrDimming == null || !opaqueOrDimming.isDimming()) {
            if (imeWindowCanNavColorWindow) {
                return imeWindow;
            }
            return null;
        } else if (!imeWindowCanNavColorWindow) {
            return opaqueOrDimming;
        } else {
            if (WindowManager.LayoutParams.mayUseInputMethod(PolicyControl.getWindowFlags(opaqueOrDimming, null))) {
                return imeWindow;
            }
            return opaqueOrDimming;
        }
    }

    @VisibleForTesting
    static int updateLightNavigationBarLw(int vis, WindowState opaque, WindowState opaqueOrDimming, WindowState imeWindow, WindowState navColorWin) {
        if (navColorWin != null) {
            if (navColorWin == imeWindow || navColorWin == opaque) {
                return (vis & (-17)) | (PolicyControl.getSystemUiVisibility(navColorWin, null) & 16);
            }
            if (navColorWin == opaqueOrDimming && navColorWin.isDimming()) {
                return vis & (-17);
            }
            return vis;
        }
        return vis;
    }

    /* JADX WARN: Code restructure failed: missing block: B:107:0x0186, code lost:
        if (r1 != false) goto L121;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private android.util.Pair<java.lang.Integer, java.lang.Boolean> updateSystemBarsLw(com.android.server.wm.WindowState r37, int r38, int r39) {
        /*
            Method dump skipped, instructions count: 581
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayPolicy.updateSystemBarsLw(com.android.server.wm.WindowState, int, int):android.util.Pair");
    }

    private boolean drawsBarBackground(int vis, WindowState win, BarController controller, int translucentFlag) {
        if (controller.isTransparentAllowed(win)) {
            if (win == null) {
                return true;
            }
            boolean drawsSystemBars = (win.getAttrs().flags & Integer.MIN_VALUE) != 0;
            boolean forceDrawsSystemBars = (win.getAttrs().privateFlags & 131072) != 0;
            if (forceDrawsSystemBars) {
                return true;
            }
            return drawsSystemBars && (vis & translucentFlag) == 0;
        }
        return false;
    }

    private boolean drawsStatusBarBackground(int vis, WindowState win) {
        return drawsBarBackground(vis, win, this.mStatusBarController, 67108864);
    }

    private boolean drawsNavigationBarBackground(int vis, WindowState win) {
        return drawsBarBackground(vis, win, this.mNavigationBarController, 134217728);
    }

    private int configureNavBarOpacity(int visibility, boolean dockedStackVisible, boolean freeformStackVisible, boolean isDockedDividerResizing, boolean fullscreenDrawsBackground, boolean dockedDrawsNavigationBarBackground) {
        int i = this.mNavBarOpacityMode;
        if (i == 2) {
            if (fullscreenDrawsBackground && dockedDrawsNavigationBarBackground) {
                return setNavBarTransparentFlag(visibility);
            }
            if (dockedStackVisible) {
                return setNavBarOpaqueFlag(visibility);
            }
            return visibility;
        } else if (i == 0) {
            if (dockedStackVisible || freeformStackVisible || isDockedDividerResizing) {
                return setNavBarOpaqueFlag(visibility);
            }
            if (fullscreenDrawsBackground) {
                return setNavBarTransparentFlag(visibility);
            }
            return visibility;
        } else if (i == 1) {
            if (isDockedDividerResizing) {
                return setNavBarOpaqueFlag(visibility);
            }
            if (freeformStackVisible) {
                return setNavBarTranslucentFlag(visibility);
            }
            return setNavBarOpaqueFlag(visibility);
        } else {
            return visibility;
        }
    }

    private int setNavBarOpaqueFlag(int visibility) {
        return 2147450879 & visibility;
    }

    private int setNavBarTranslucentFlag(int visibility) {
        return Integer.MIN_VALUE | (visibility & (-32769));
    }

    private int setNavBarTransparentFlag(int visibility) {
        return 32768 | (visibility & Integer.MAX_VALUE);
    }

    private void clearClearableFlagsLw() {
        int i = this.mResettingSystemUiFlags;
        int newVal = i | 7;
        if (newVal != i) {
            this.mResettingSystemUiFlags = newVal;
            this.mDisplayContent.reevaluateStatusBarVisibility();
        }
    }

    private boolean isImmersiveMode(int vis) {
        return (this.mNavigationBar == null || (vis & 2) == 0 || (vis & 6144) == 0 || !canHideNavigationBar()) ? false : true;
    }

    private boolean canHideNavigationBar() {
        return hasNavigationBar();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isNavBarEmpty(int systemUiFlags) {
        return (systemUiFlags & 23068672) == 23068672;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldRotateSeamlessly(DisplayRotation displayRotation, int oldRotation, int newRotation) {
        WindowState w;
        if (oldRotation == displayRotation.getUpsideDownRotation() || newRotation == displayRotation.getUpsideDownRotation()) {
            return false;
        }
        if ((navigationBarCanMove() || this.mAllowSeamlessRotationDespiteNavBarMoving) && (w = this.mTopFullscreenOpaqueWindowState) != null && w == this.mFocusedWindow) {
            return (w.mAppToken == null || w.mAppToken.matchParentBounds()) && !w.isAnimatingLw() && w.getAttrs().rotationAnimation == 3;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPowerKeyDown(boolean isScreenOn) {
        boolean panic = this.mImmersiveModeConfirmation.onPowerKeyDown(isScreenOn, SystemClock.elapsedRealtime(), isImmersiveMode(this.mLastSystemUiFlags), isNavBarEmpty(this.mLastSystemUiFlags));
        if (panic) {
            this.mHandler.post(this.mHiddenNavPanic);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onVrStateChangedLw(boolean enabled) {
        this.mImmersiveModeConfirmation.onVrStateChangedLw(enabled);
    }

    public void onLockTaskStateChangedLw(int lockTaskState) {
        this.mImmersiveModeConfirmation.onLockTaskModeChangedLw(lockTaskState);
    }

    public void takeScreenshot(int screenshotType) {
        ScreenshotHelper screenshotHelper = this.mScreenshotHelper;
        if (screenshotHelper != null) {
            WindowState windowState = this.mStatusBar;
            boolean z = false;
            boolean z2 = windowState != null && windowState.isVisibleLw();
            WindowState windowState2 = this.mNavigationBar;
            if (windowState2 != null && windowState2.isVisibleLw()) {
                z = true;
            }
            screenshotHelper.takeScreenshot(screenshotType, z2, z, this.mHandler, (Consumer) null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RefreshRatePolicy getRefreshRatePolicy() {
        return this.mRefreshRatePolicy;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("DisplayPolicy");
        String prefix2 = prefix + "  ";
        pw.print(prefix2);
        pw.print("mCarDockEnablesAccelerometer=");
        pw.print(this.mCarDockEnablesAccelerometer);
        pw.print(" mDeskDockEnablesAccelerometer=");
        pw.println(this.mDeskDockEnablesAccelerometer);
        pw.print(prefix2);
        pw.print("mDockMode=");
        pw.print(Intent.dockStateToString(this.mDockMode));
        pw.print(" mLidState=");
        pw.println(WindowManagerPolicy.WindowManagerFuncs.lidStateToString(this.mLidState));
        pw.print(prefix2);
        pw.print("mAwake=");
        pw.print(this.mAwake);
        pw.print(" mScreenOnEarly=");
        pw.print(this.mScreenOnEarly);
        pw.print(" mScreenOnFully=");
        pw.println(this.mScreenOnFully);
        pw.print(prefix2);
        pw.print("mKeyguardDrawComplete=");
        pw.print(this.mKeyguardDrawComplete);
        pw.print(" mWindowManagerDrawComplete=");
        pw.println(this.mWindowManagerDrawComplete);
        pw.print(prefix2);
        pw.print("mHdmiPlugged=");
        pw.println(this.mHdmiPlugged);
        if (this.mLastSystemUiFlags != 0 || this.mResettingSystemUiFlags != 0 || this.mForceClearedSystemUiFlags != 0) {
            pw.print(prefix2);
            pw.print("mLastSystemUiFlags=0x");
            pw.print(Integer.toHexString(this.mLastSystemUiFlags));
            pw.print(" mResettingSystemUiFlags=0x");
            pw.print(Integer.toHexString(this.mResettingSystemUiFlags));
            pw.print(" mForceClearedSystemUiFlags=0x");
            pw.println(Integer.toHexString(this.mForceClearedSystemUiFlags));
        }
        if (this.mLastFocusNeedsMenu) {
            pw.print(prefix2);
            pw.print("mLastFocusNeedsMenu=");
            pw.println(this.mLastFocusNeedsMenu);
        }
        pw.print(prefix2);
        pw.print("mShowingDream=");
        pw.print(this.mShowingDream);
        pw.print(" mDreamingLockscreen=");
        pw.print(this.mDreamingLockscreen);
        pw.print(" mDreamingSleepToken=");
        pw.println(this.mDreamingSleepToken);
        if (this.mStatusBar != null) {
            pw.print(prefix2);
            pw.print("mStatusBar=");
            pw.print(this.mStatusBar);
            pw.print(" isStatusBarKeyguard=");
            pw.println(isStatusBarKeyguard());
        }
        if (this.mNavigationBar != null) {
            pw.print(prefix2);
            pw.print("mNavigationBar=");
            pw.println(this.mNavigationBar);
            pw.print(prefix2);
            pw.print("mNavBarOpacityMode=");
            pw.println(this.mNavBarOpacityMode);
            pw.print(prefix2);
            pw.print("mNavigationBarCanMove=");
            pw.println(this.mNavigationBarCanMove);
            pw.print(prefix2);
            pw.print("mNavigationBarPosition=");
            pw.println(this.mNavigationBarPosition);
        }
        if (this.mFocusedWindow != null) {
            pw.print(prefix2);
            pw.print("mFocusedWindow=");
            pw.println(this.mFocusedWindow);
        }
        if (this.mFocusedApp != null) {
            pw.print(prefix2);
            pw.print("mFocusedApp=");
            pw.println(this.mFocusedApp);
        }
        if (this.mTopFullscreenOpaqueWindowState != null) {
            pw.print(prefix2);
            pw.print("mTopFullscreenOpaqueWindowState=");
            pw.println(this.mTopFullscreenOpaqueWindowState);
        }
        if (this.mTopFullscreenOpaqueOrDimmingWindowState != null) {
            pw.print(prefix2);
            pw.print("mTopFullscreenOpaqueOrDimmingWindowState=");
            pw.println(this.mTopFullscreenOpaqueOrDimmingWindowState);
        }
        if (this.mForcingShowNavBar) {
            pw.print(prefix2);
            pw.print("mForcingShowNavBar=");
            pw.println(this.mForcingShowNavBar);
            pw.print(prefix2);
            pw.print("mForcingShowNavBarLayer=");
            pw.println(this.mForcingShowNavBarLayer);
        }
        pw.print(prefix2);
        pw.print("mTopIsFullscreen=");
        pw.print(this.mTopIsFullscreen);
        pw.print(prefix2);
        pw.print("mForceStatusBar=");
        pw.print(this.mForceStatusBar);
        pw.print(" mForceStatusBarFromKeyguard=");
        pw.println(this.mForceStatusBarFromKeyguard);
        pw.print(" mForceShowSystemBarsFromExternal=");
        pw.println(this.mForceShowSystemBarsFromExternal);
        pw.print(prefix2);
        pw.print("mAllowLockscreenWhenOn=");
        pw.println(this.mAllowLockscreenWhenOn);
        this.mStatusBarController.dump(pw, prefix2);
        this.mNavigationBarController.dump(pw, prefix2);
        pw.print(prefix2);
        pw.println("Looper state:");
        this.mHandler.getLooper().dump(new PrintWriterPrinter(pw), prefix2 + "  ");
    }

    private boolean supportsPointerLocation() {
        return this.mDisplayContent.isDefaultDisplay || !this.mDisplayContent.isPrivate();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPointerLocationEnabled(boolean pointerLocationEnabled) {
        if (!supportsPointerLocation()) {
            return;
        }
        this.mHandler.sendEmptyMessage(pointerLocationEnabled ? 4 : 5);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enablePointerLocation() {
        if (this.mPointerLocationView != null) {
            return;
        }
        this.mPointerLocationView = new PointerLocationView(this.mContext);
        this.mPointerLocationView.setPrintCoords(false);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1);
        lp.type = 2015;
        lp.flags = 1304;
        lp.layoutInDisplayCutoutMode = 1;
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= 16777216;
            lp.privateFlags |= 2;
        }
        lp.format = -3;
        lp.setTitle("PointerLocation - display " + getDisplayId());
        lp.inputFeatures = lp.inputFeatures | 2;
        WindowManager wm = (WindowManager) this.mContext.getSystemService(WindowManager.class);
        wm.addView(this.mPointerLocationView, lp);
        this.mDisplayContent.registerPointerEventListener(this.mPointerLocationView);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disablePointerLocation() {
        WindowManagerPolicyConstants.PointerEventListener pointerEventListener = this.mPointerLocationView;
        if (pointerEventListener == null) {
            return;
        }
        this.mDisplayContent.unregisterPointerEventListener(pointerEventListener);
        WindowManager wm = (WindowManager) this.mContext.getSystemService(WindowManager.class);
        wm.removeView(this.mPointerLocationView);
        this.mPointerLocationView = null;
    }
}
