package com.android.server.policy;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.IUiModeManager;
import android.app.ProfilerInfo;
import android.app.SearchManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.input.InputManagerInternal;
import android.media.AudioAttributes;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.IDreamManager;
import android.service.vr.IPersistentVrStateCallbacks;
import android.telecom.TelecomManager;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.MutableBoolean;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.IApplicationToken;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.autofill.AutofillManagerInternal;
import android.view.inputmethod.InputMethodManagerInternal;
import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.PhoneWindow;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ScreenShapeHelper;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.widget.PointerLocationView;
import com.android.server.GestureLauncherService;
import com.android.server.LocalServices;
import com.android.server.NetworkManagementService;
import com.android.server.SystemServiceManager;
import com.android.server.UiModeManagerService;
import com.android.server.audio.AudioService;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.DumpState;
import com.android.server.policy.BarController;
import com.android.server.policy.SystemGesturesPointerEventListener;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.policy.keyguard.KeyguardStateMonitor;
import com.android.server.policy.xkeymgr.XGlobalKeyManager;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.AppTransition;
import com.android.server.wm.DisplayFrames;
import com.android.server.wm.WindowManagerInternal;
import com.xiaopeng.app.BootProgressDialog;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.policy.xpBootManagerPolicy;
import com.xiaopeng.server.policy.xpPhoneWindowManager;
import com.xiaopeng.server.policy.xpSystemGesturesListener;
import com.xiaopeng.util.DebugOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.xpWindowManager;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class PhoneWindowManager implements WindowManagerPolicy {
    static final boolean ALTERNATE_CAR_MODE_NAV_SIZE = false;
    private static final int BRIGHTNESS_STEPS = 10;
    private static final long BUGREPORT_TV_GESTURE_TIMEOUT_MILLIS = 1000;
    static final boolean DEBUG = false;
    static final boolean DEBUG_INPUT = false;
    static final boolean DEBUG_KEYGUARD = false;
    static final boolean DEBUG_LAYOUT = false;
    static final boolean DEBUG_SPLASH_SCREEN = false;
    static final int DOUBLE_TAP_HOME_NOTHING = 0;
    static final int DOUBLE_TAP_HOME_RECENT_SYSTEM_UI = 1;
    static final boolean ENABLE_DESK_DOCK_HOME_CAPTURE = false;
    static final boolean ENABLE_VR_HEADSET_HOME_CAPTURE = true;
    private static final float KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER = 2.5f;
    static final int LAST_LONG_PRESS_HOME_BEHAVIOR = 2;
    static final int LONG_PRESS_BACK_GO_TO_VOICE_ASSIST = 1;
    static final int LONG_PRESS_BACK_NOTHING = 0;
    static final int LONG_PRESS_HOME_ALL_APPS = 1;
    static final int LONG_PRESS_HOME_ASSIST = 2;
    static final int LONG_PRESS_HOME_NOTHING = 0;
    static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    static final int LONG_PRESS_POWER_GO_TO_VOICE_ASSIST = 4;
    static final int LONG_PRESS_POWER_NOTHING = 0;
    static final int LONG_PRESS_POWER_SHUT_OFF = 2;
    static final int LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM = 3;
    private static final int MSG_ACCESSIBILITY_SHORTCUT = 20;
    private static final int MSG_ACCESSIBILITY_TV = 22;
    private static final int MSG_BACK_LONG_PRESS = 18;
    private static final int MSG_BUGREPORT_TV = 21;
    private static final int MSG_DISABLE_POINTER_LOCATION = 2;
    private static final int MSG_DISPATCH_BACK_KEY_TO_AUTOFILL = 23;
    private static final int MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK = 4;
    private static final int MSG_DISPATCH_MEDIA_KEY_WITH_WAKE_LOCK = 3;
    private static final int MSG_DISPATCH_SHOW_GLOBAL_ACTIONS = 10;
    private static final int MSG_DISPATCH_SHOW_RECENTS = 9;
    private static final int MSG_DISPOSE_INPUT_CONSUMER = 19;
    private static final int MSG_ENABLE_POINTER_LOCATION = 1;
    private static final int MSG_HANDLE_ALL_APPS = 25;
    private static final int MSG_HIDE_BOOT_MESSAGE = 11;
    private static final int MSG_KEYEVENT_VOLUMEDOWN = 102;
    private static final int MSG_KEYEVENT_VOLUMEMUTE = 103;
    private static final int MSG_KEYEVENT_VOLUMEUP = 101;
    private static final int MSG_KEYGUARD_DRAWN_COMPLETE = 5;
    private static final int MSG_KEYGUARD_DRAWN_TIMEOUT = 6;
    private static final int MSG_LAUNCH_ASSIST = 26;
    private static final int MSG_LAUNCH_ASSIST_LONG_PRESS = 27;
    private static final int MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK = 12;
    private static final int MSG_NOTIFY_USER_ACTIVITY = 29;
    private static final int MSG_POWER_DELAYED_PRESS = 13;
    private static final int MSG_POWER_LONG_PRESS = 14;
    private static final int MSG_POWER_VERY_LONG_PRESS = 28;
    private static final int MSG_REQUEST_TRANSIENT_BARS = 16;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION = 1;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS = 0;
    private static final int MSG_RINGER_TOGGLE_CHORD = 30;
    private static final int MSG_SHOW_PICTURE_IN_PICTURE_MENU = 17;
    private static final int MSG_SYSTEM_KEY_PRESS = 24;
    private static final int MSG_UPDATE_DREAMING_SLEEP_TOKEN = 15;
    private static final int MSG_WINDOW_MANAGER_DRAWN_COMPLETE = 7;
    static final int MULTI_PRESS_POWER_BRIGHTNESS_BOOST = 2;
    static final int MULTI_PRESS_POWER_NOTHING = 0;
    static final int MULTI_PRESS_POWER_THEATER_MODE = 1;
    static final int NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED = 0;
    static final int NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE = 1;
    private static final long PANIC_GESTURE_EXPIRATION = 30000;
    static final int PENDING_KEY_NULL = -1;
    static final boolean PRINT_ANIM = false;
    private static final long SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS = 150;
    static final int SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME = 5;
    static final int SHORT_PRESS_POWER_GO_HOME = 4;
    static final int SHORT_PRESS_POWER_GO_TO_SLEEP = 1;
    static final int SHORT_PRESS_POWER_NOTHING = 0;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP = 2;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME = 3;
    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP = 0;
    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME = 1;
    static final int SHORT_PRESS_WINDOW_NOTHING = 0;
    static final int SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE = 1;
    static final boolean SHOW_SPLASH_SCREENS = true;
    public static final String SYSTEM_DIALOG_REASON_ASSIST = "assist";
    public static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    public static final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";
    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    public static final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";
    static final int SYSTEM_UI_CHANGING_LAYOUT = -1073709042;
    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String SYSUI_SCREENSHOT_ERROR_RECEIVER = "com.android.systemui.screenshot.ScreenshotServiceErrorReceiver";
    private static final String SYSUI_SCREENSHOT_SERVICE = "com.android.systemui.screenshot.TakeScreenshotService";
    static final String TAG = "WindowManager";
    public static final int TOAST_WINDOW_TIMEOUT = 6000;
    private static final int USER_ACTIVITY_NOTIFICATION_DELAY = 200;
    static final int VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    static final int VERY_LONG_PRESS_POWER_NOTHING = 0;
    static final int WAITING_FOR_DRAWN_TIMEOUT = 1000;
    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK;
    static final boolean localLOGV = false;
    static final Rect mTmpContentFrame;
    static final Rect mTmpDecorFrame;
    private static final Rect mTmpDisplayCutoutSafeExceptMaybeBarsRect;
    static final Rect mTmpDisplayFrame;
    static final Rect mTmpNavigationFrame;
    static final Rect mTmpOutsetFrame;
    static final Rect mTmpOverscanFrame;
    static final Rect mTmpParentFrame;
    private static final Rect mTmpRect;
    static final Rect mTmpStableFrame;
    static final Rect mTmpVisibleFrame;
    private boolean mA11yShortcutChordVolumeUpKeyConsumed;
    private long mA11yShortcutChordVolumeUpKeyTime;
    private boolean mA11yShortcutChordVolumeUpKeyTriggered;
    AccessibilityManager mAccessibilityManager;
    private AccessibilityShortcutController mAccessibilityShortcutController;
    private boolean mAccessibilityTvKey1Pressed;
    private boolean mAccessibilityTvKey2Pressed;
    private boolean mAccessibilityTvScheduled;
    ActivityManagerInternal mActivityManagerInternal;
    boolean mAllowLockscreenWhenOn;
    boolean mAllowStartActivityForLongPressOnPowerDuringSetup;
    private boolean mAllowTheaterModeWakeFromCameraLens;
    private boolean mAllowTheaterModeWakeFromKey;
    private boolean mAllowTheaterModeWakeFromLidSwitch;
    private boolean mAllowTheaterModeWakeFromMotion;
    private boolean mAllowTheaterModeWakeFromMotionWhenNotDreaming;
    private boolean mAllowTheaterModeWakeFromPowerKey;
    private boolean mAllowTheaterModeWakeFromWakeGesture;
    private boolean mAodShowing;
    AppOpsManager mAppOpsManager;
    AudioManagerInternal mAudioManagerInternal;
    AutofillManagerInternal mAutofillManagerInternal;
    volatile boolean mAwake;
    volatile boolean mBackKeyHandled;
    volatile boolean mBeganFromNonInteractive;
    boolean mBootMessageNeedsHiding;
    PowerManager.WakeLock mBroadcastWakeLock;
    private boolean mBugreportTvKey1Pressed;
    private boolean mBugreportTvKey2Pressed;
    private boolean mBugreportTvScheduled;
    BurnInProtectionHelper mBurnInProtectionHelper;
    long[] mCalendarDateVibePattern;
    volatile boolean mCameraGestureTriggeredDuringGoingToSleep;
    boolean mCarDockEnablesAccelerometer;
    Intent mCarDockIntent;
    int mCarDockRotation;
    boolean mConsumeSearchKeyUp;
    Context mContext;
    private int mCurrentUserId;
    int mDemoHdmiRotation;
    boolean mDemoHdmiRotationLock;
    int mDemoRotation;
    boolean mDemoRotationLock;
    boolean mDeskDockEnablesAccelerometer;
    Intent mDeskDockIntent;
    int mDeskDockRotation;
    private volatile boolean mDismissImeOnBackKeyPressed;
    Display mDisplay;
    int mDockLayer;
    int mDoublePressOnPowerBehavior;
    private int mDoubleTapOnHomeBehavior;
    DreamManagerInternal mDreamManagerInternal;
    boolean mDreamingLockscreen;
    ActivityManagerInternal.SleepToken mDreamingSleepToken;
    boolean mDreamingSleepTokenNeeded;
    volatile boolean mEndCallKeyHandled;
    int mEndcallBehavior;
    IApplicationToken mFocusedApp;
    WindowManagerPolicy.WindowState mFocusedWindow;
    boolean mForceShowSystemBars;
    boolean mForceStatusBar;
    boolean mForceStatusBarFromKeyguard;
    private boolean mForceStatusBarTransparent;
    boolean mForcingShowNavBar;
    int mForcingShowNavBarLayer;
    GlobalActions mGlobalActions;
    private GlobalKeyManager mGlobalKeyManager;
    private boolean mGoToSleepOnButtonPressTheaterMode;
    volatile boolean mGoingToSleep;
    private boolean mHandleVolumeKeysInWM;
    Handler mHandler;
    private boolean mHasFeatureLeanback;
    private boolean mHasFeatureWatch;
    boolean mHaveBuiltInKeyboard;
    boolean mHavePendingMediaKeyRepeatWithWakeLock;
    HdmiControl mHdmiControl;
    boolean mHdmiPlugged;
    boolean mHomeConsumed;
    boolean mHomeDoubleTapPending;
    Intent mHomeIntent;
    boolean mHomePressed;
    private ImmersiveModeConfirmation mImmersiveModeConfirmation;
    int mIncallBackBehavior;
    int mIncallPowerBehavior;
    int mInitialMetaState;
    InputManagerInternal mInputManagerInternal;
    InputMethodManagerInternal mInputMethodManagerInternal;
    private boolean mKeyguardBound;
    KeyguardServiceDelegate mKeyguardDelegate;
    boolean mKeyguardDrawComplete;
    private boolean mKeyguardDrawnOnce;
    volatile boolean mKeyguardOccluded;
    private boolean mKeyguardOccludedChanged;
    boolean mLanguageSwitchKeyPressed;
    int mLastDockedStackSysUiFlags;
    int mLastFullscreenStackSysUiFlags;
    private boolean mLastShowingDream;
    int mLastSystemUiFlags;
    private boolean mLastWindowSleepTokenNeeded;
    boolean mLidControlsScreenLock;
    boolean mLidControlsSleep;
    int mLidKeyboardAccessibility;
    int mLidNavigationAccessibility;
    int mLidOpenRotation;
    int mLockScreenTimeout;
    boolean mLockScreenTimerActive;
    MetricsLogger mLogger;
    int mLongPressOnBackBehavior;
    private int mLongPressOnHomeBehavior;
    int mLongPressOnPowerBehavior;
    long[] mLongPressVibePattern;
    int mMetaState;
    private boolean mNotifyUserActivity;
    MyOrientationListener mOrientationListener;
    boolean mPendingCapsLockToggle;
    private boolean mPendingKeyguardOccluded;
    boolean mPendingMetaAction;
    private long mPendingPanicGestureUptime;
    private volatile boolean mPersistentVrModeEnabled;
    volatile boolean mPictureInPictureVisible;
    PointerLocationView mPointerLocationView;
    volatile boolean mPowerKeyHandled;
    volatile int mPowerKeyPressCounter;
    PowerManager.WakeLock mPowerKeyWakeLock;
    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;
    boolean mPreloadedRecentApps;
    int mRecentAppsHeldModifiers;
    volatile boolean mRecentsVisible;
    volatile boolean mRequestedOrGoingToSleep;
    boolean mSafeMode;
    long[] mSafeModeEnabledVibePattern;
    ActivityManagerInternal.SleepToken mScreenOffSleepToken;
    boolean mScreenOnEarly;
    boolean mScreenOnFully;
    WindowManagerPolicy.ScreenOnListener mScreenOnListener;
    private boolean mScreenshotChordEnabled;
    private long mScreenshotChordPowerKeyTime;
    private boolean mScreenshotChordPowerKeyTriggered;
    private boolean mScreenshotChordVolumeDownKeyConsumed;
    private long mScreenshotChordVolumeDownKeyTime;
    private boolean mScreenshotChordVolumeDownKeyTriggered;
    private ScreenshotHelper mScreenshotHelper;
    boolean mSearchKeyShortcutPending;
    SearchManager mSearchManager;
    SettingsObserver mSettingsObserver;
    int mShortPressOnPowerBehavior;
    int mShortPressOnSleepBehavior;
    int mShortPressOnWindowBehavior;
    ShortcutManager mShortcutManager;
    int mShowRotationSuggestions;
    boolean mShowingDream;
    int mStatusBarLayer;
    StatusBarManagerInternal mStatusBarManagerInternal;
    IStatusBarService mStatusBarService;
    boolean mSupportAutoRotation;
    private boolean mSupportLongPressPowerWhenNonInteractive;
    boolean mSystemBooted;
    @VisibleForTesting
    SystemGesturesPointerEventListener mSystemGestures;
    boolean mSystemNavigationKeysEnabled;
    boolean mSystemReady;
    WindowManagerPolicy.WindowState mTopDockedOpaqueOrDimmingWindowState;
    WindowManagerPolicy.WindowState mTopDockedOpaqueWindowState;
    WindowManagerPolicy.WindowState mTopFullscreenOpaqueOrDimmingWindowState;
    WindowManagerPolicy.WindowState mTopFullscreenOpaqueWindowState;
    boolean mTopIsFullscreen;
    int mTriplePressOnPowerBehavior;
    int mUiMode;
    IUiModeManager mUiModeManager;
    int mUndockedHdmiRotation;
    boolean mUseTvRouting;
    int mVeryLongPressOnPowerBehavior;
    int mVeryLongPressTimeout;
    Vibrator mVibrator;
    Intent mVrHeadsetHomeIntent;
    volatile VrManagerInternal mVrManagerInternal;
    boolean mWakeGestureEnabledSetting;
    MyWakeGestureListener mWakeGestureListener;
    IWindowManager mWindowManager;
    boolean mWindowManagerDrawComplete;
    WindowManagerPolicy.WindowManagerFuncs mWindowManagerFuncs;
    WindowManagerInternal mWindowManagerInternal;
    @GuardedBy("mHandler")
    private ActivityManagerInternal.SleepToken mWindowSleepToken;
    private boolean mWindowSleepTokenNeeded;
    static final boolean DEBUG_WAKEUP = DebugOption.DEBUG_WAKE_DRAW;
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    static SparseArray<String> sApplicationLaunchKeyCategories = new SparseArray<>();
    private final Object mLock = new Object();
    private final Object mScreenLock = new Object();
    final Object mServiceAquireLock = new Object();
    boolean mEnableShiftMenuBugReports = false;
    private final ArraySet<WindowManagerPolicy.WindowState> mScreenDecorWindows = new ArraySet<>();
    WindowManagerPolicy.WindowState mStatusBar = null;
    private final int[] mStatusBarHeightForRotation = new int[4];
    WindowManagerPolicy.WindowState mNavigationBar = null;
    boolean mHasNavigationBar = false;
    boolean mNavigationBarCanMove = false;
    int mNavigationBarPosition = 4;
    int[] mNavigationBarHeightForRotationDefault = new int[4];
    int[] mNavigationBarWidthForRotationDefault = new int[4];
    int[] mNavigationBarHeightForRotationInCarMode = new int[4];
    int[] mNavigationBarWidthForRotationInCarMode = new int[4];
    private LongSparseArray<IShortcutService> mShortcutKeyServices = new LongSparseArray<>();
    private boolean mEnableCarDockHomeCapture = true;
    final Runnable mWindowManagerDrawCallback = new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.1
        @Override // java.lang.Runnable
        public void run() {
            if (PhoneWindowManager.DEBUG_WAKEUP) {
                Slog.i(PhoneWindowManager.TAG, "All windows ready for display!");
            }
            PhoneWindowManager.this.mHandler.sendEmptyMessage(7);
        }
    };
    final KeyguardServiceDelegate.DrawnListener mKeyguardDrawnCallback = new KeyguardServiceDelegate.DrawnListener() { // from class: com.android.server.policy.PhoneWindowManager.2
        @Override // com.android.server.policy.keyguard.KeyguardServiceDelegate.DrawnListener
        public void onDrawn() {
            if (PhoneWindowManager.DEBUG_WAKEUP) {
                Slog.d(PhoneWindowManager.TAG, "mKeyguardDelegate.ShowListener.onDrawn.");
            }
            PhoneWindowManager.this.mHandler.sendEmptyMessage(5);
        }
    };
    WindowManagerPolicy.WindowState mLastInputMethodWindow = null;
    WindowManagerPolicy.WindowState mLastInputMethodTargetWindow = null;
    volatile boolean mNavBarVirtualKeyHapticFeedbackEnabled = true;
    volatile int mPendingWakeKey = -1;
    int mLidState = -1;
    int mCameraLensCoverState = -1;
    int mDockMode = 0;
    private boolean mForceDefaultOrientation = false;
    int mUserRotationMode = 0;
    int mUserRotation = 0;
    int mAllowAllRotations = -1;
    boolean mOrientationSensorEnabled = false;
    int mCurrentAppOrientation = -1;
    boolean mHasSoftInput = false;
    boolean mTranslucentDecorEnabled = true;
    int mPointerLocationMode = 0;
    int mResettingSystemUiFlags = 0;
    int mForceClearedSystemUiFlags = 0;
    final Rect mNonDockedStackBounds = new Rect();
    final Rect mDockedStackBounds = new Rect();
    final Rect mLastNonDockedStackBounds = new Rect();
    final Rect mLastDockedStackBounds = new Rect();
    boolean mLastFocusNeedsMenu = false;
    WindowManagerPolicy.InputConsumer mInputConsumer = null;
    int mNavBarOpacityMode = 0;
    int mLandscapeRotation = 0;
    int mSeascapeRotation = 0;
    int mPortraitRotation = 0;
    int mUpsideDownRotation = 0;
    private int mRingerToggleChord = 0;
    private final SparseArray<KeyCharacterMap.FallbackAction> mFallbackActions = new SparseArray<>();
    private final LogDecelerateInterpolator mLogDecelerateInterpolator = new LogDecelerateInterpolator(100, 0);
    private final MutableBoolean mTmpBoolean = new MutableBoolean(false);
    private UEventObserver mHDMIObserver = new UEventObserver() { // from class: com.android.server.policy.PhoneWindowManager.3
        public void onUEvent(UEventObserver.UEvent event) {
            PhoneWindowManager.this.setHdmiPlugged("1".equals(event.get("SWITCH_STATE")));
        }
    };
    final IPersistentVrStateCallbacks mPersistentVrModeListener = new IPersistentVrStateCallbacks.Stub() { // from class: com.android.server.policy.PhoneWindowManager.4
        public void onPersistentVrStateChanged(boolean enabled) {
            PhoneWindowManager.this.mPersistentVrModeEnabled = enabled;
        }
    };
    private final StatusBarController mStatusBarController = new StatusBarController();
    private final BarController mNavigationBarController = new BarController("NavigationBar", 134217728, 536870912, Integer.MIN_VALUE, 2, 134217728, 32768);
    private final BarController.OnBarVisibilityChangedListener mNavBarVisibilityListener = new BarController.OnBarVisibilityChangedListener() { // from class: com.android.server.policy.PhoneWindowManager.5
        @Override // com.android.server.policy.BarController.OnBarVisibilityChangedListener
        public void onBarVisibilityChanged(boolean visible) {
            PhoneWindowManager.this.mAccessibilityManager.notifyAccessibilityButtonVisibilityChanged(visible);
        }
    };
    private final Runnable mAcquireSleepTokenRunnable = new Runnable() { // from class: com.android.server.policy.-$$Lambda$PhoneWindowManager$qkEs_boDTAbqA6wKqcLwnsgoklc
        @Override // java.lang.Runnable
        public final void run() {
            PhoneWindowManager.lambda$new$0(PhoneWindowManager.this);
        }
    };
    private final Runnable mReleaseSleepTokenRunnable = new Runnable() { // from class: com.android.server.policy.-$$Lambda$PhoneWindowManager$SMVPfeuVGHeByGLchxVc-pxEEMw
        @Override // java.lang.Runnable
        public final void run() {
            PhoneWindowManager.lambda$new$1(PhoneWindowManager.this);
        }
    };
    private final Runnable mEndCallLongPress = new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.6
        @Override // java.lang.Runnable
        public void run() {
            PhoneWindowManager.this.mEndCallKeyHandled = true;
            PhoneWindowManager.this.performHapticFeedbackLw(null, 0, false);
            PhoneWindowManager.this.showGlobalActionsInternal();
        }
    };
    private final ScreenshotRunnable mScreenshotRunnable = new ScreenshotRunnable();
    private final Runnable mHomeDoubleTapTimeoutRunnable = new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.7
        @Override // java.lang.Runnable
        public void run() {
            if (PhoneWindowManager.this.mHomeDoubleTapPending) {
                PhoneWindowManager.this.mHomeDoubleTapPending = false;
                PhoneWindowManager.this.handleShortPressOnHome();
            }
        }
    };
    private final Runnable mClearHideNavigationFlag = new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.12
        @Override // java.lang.Runnable
        public void run() {
            synchronized (PhoneWindowManager.this.mWindowManagerFuncs.getWindowManagerLock()) {
                PhoneWindowManager.this.mForceClearedSystemUiFlags &= -3;
            }
            PhoneWindowManager.this.mWindowManagerFuncs.reevaluateStatusBarVisibility();
        }
    };
    BroadcastReceiver mDockReceiver = new BroadcastReceiver() { // from class: com.android.server.policy.PhoneWindowManager.13
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.DOCK_EVENT".equals(intent.getAction())) {
                PhoneWindowManager.this.mDockMode = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
            } else {
                try {
                    IUiModeManager uiModeService = IUiModeManager.Stub.asInterface(ServiceManager.getService("uimode"));
                    PhoneWindowManager.this.mUiMode = uiModeService.getCurrentModeType();
                } catch (RemoteException e) {
                }
            }
            PhoneWindowManager.this.updateRotation(true);
            synchronized (PhoneWindowManager.this.mLock) {
                PhoneWindowManager.this.updateOrientationListenerLp();
            }
        }
    };
    BroadcastReceiver mDreamReceiver = new BroadcastReceiver() { // from class: com.android.server.policy.PhoneWindowManager.14
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.DREAMING_STARTED".equals(intent.getAction())) {
                if (PhoneWindowManager.this.mKeyguardDelegate != null) {
                    PhoneWindowManager.this.mKeyguardDelegate.onDreamingStarted();
                }
            } else if ("android.intent.action.DREAMING_STOPPED".equals(intent.getAction()) && PhoneWindowManager.this.mKeyguardDelegate != null) {
                PhoneWindowManager.this.mKeyguardDelegate.onDreamingStopped();
            }
        }
    };
    BroadcastReceiver mMultiuserReceiver = new BroadcastReceiver() { // from class: com.android.server.policy.PhoneWindowManager.15
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.USER_SWITCHED".equals(intent.getAction())) {
                PhoneWindowManager.this.mSettingsObserver.onChange(false);
                synchronized (PhoneWindowManager.this.mWindowManagerFuncs.getWindowManagerLock()) {
                    PhoneWindowManager.this.mLastSystemUiFlags = 0;
                    PhoneWindowManager.this.updateSystemUiVisibilityLw();
                }
            }
        }
    };
    private final Runnable mHiddenNavPanic = new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.16
        @Override // java.lang.Runnable
        public void run() {
            synchronized (PhoneWindowManager.this.mWindowManagerFuncs.getWindowManagerLock()) {
                if (PhoneWindowManager.this.isUserSetupComplete()) {
                    PhoneWindowManager.this.mPendingPanicGestureUptime = SystemClock.uptimeMillis();
                    if (!PhoneWindowManager.isNavBarEmpty(PhoneWindowManager.this.mLastSystemUiFlags)) {
                        PhoneWindowManager.this.mNavigationBarController.showTransient();
                    }
                }
            }
        }
    };
    AlertDialog mBootMsgDialog = null;
    ScreenLockTimeout mScreenLockTimeout = new ScreenLockTimeout();

    static {
        sApplicationLaunchKeyCategories.append(64, "android.intent.category.APP_BROWSER");
        sApplicationLaunchKeyCategories.append(65, "android.intent.category.APP_EMAIL");
        sApplicationLaunchKeyCategories.append(207, "android.intent.category.APP_CONTACTS");
        sApplicationLaunchKeyCategories.append(208, "android.intent.category.APP_CALENDAR");
        sApplicationLaunchKeyCategories.append(209, "android.intent.category.APP_MUSIC");
        sApplicationLaunchKeyCategories.append(NetworkManagementService.NetdResponseCode.TetherStatusResult, "android.intent.category.APP_CALCULATOR");
        mTmpParentFrame = new Rect();
        mTmpDisplayFrame = new Rect();
        mTmpOverscanFrame = new Rect();
        mTmpContentFrame = new Rect();
        mTmpVisibleFrame = new Rect();
        mTmpDecorFrame = new Rect();
        mTmpStableFrame = new Rect();
        mTmpNavigationFrame = new Rect();
        mTmpOutsetFrame = new Rect();
        mTmpDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
        mTmpRect = new Rect();
        WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = new int[]{2003, 2010};
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleKeyEvent(int keyCode) {
        String pkgName = this.mContext.getOpPackageName();
        switch (keyCode) {
            case 101:
                try {
                    getAudioService().adjustSuggestedStreamVolume(1, Integer.MIN_VALUE, 4101, pkgName, TAG);
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Error dispatching volume up in dispatchTvAudioEvent.", e);
                    return;
                }
            case 102:
                try {
                    getAudioService().adjustSuggestedStreamVolume(-1, Integer.MIN_VALUE, 4101, pkgName, TAG);
                    return;
                } catch (Exception e2) {
                    Log.e(TAG, "Error dispatching volume down in dispatchTvAudioEvent.", e2);
                    return;
                }
            case 103:
                try {
                    if (getAudioService().isStreamMute(3)) {
                        getAudioService().adjustStreamVolume(3, 100, 4101, pkgName);
                    } else {
                        getAudioService().adjustStreamVolume(3, -100, 4101, pkgName);
                    }
                    return;
                } catch (Exception e3) {
                    Log.e(TAG, "Error dispatching mute in dispatchTvAudioEvent.", e3);
                    return;
                }
            default:
                return;
        }
    }

    /* loaded from: classes.dex */
    private class PolicyHandler extends Handler {
        private PolicyHandler() {
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            switch (i) {
                case 1:
                    PhoneWindowManager.this.enablePointerLocation();
                    return;
                case 2:
                    PhoneWindowManager.this.disablePointerLocation();
                    return;
                case 3:
                    PhoneWindowManager.this.dispatchMediaKeyWithWakeLock((KeyEvent) msg.obj);
                    return;
                case 4:
                    PhoneWindowManager.this.dispatchMediaKeyRepeatWithWakeLock((KeyEvent) msg.obj);
                    return;
                case 5:
                    if (PhoneWindowManager.DEBUG_WAKEUP) {
                        Slog.i(PhoneWindowManager.TAG, "Setting mKeyguardDrawComplete");
                    }
                    PhoneWindowManager.this.finishKeyguardDrawn();
                    return;
                case 6:
                    Slog.w(PhoneWindowManager.TAG, "Keyguard drawn timeout. Setting mKeyguardDrawComplete");
                    PhoneWindowManager.this.finishKeyguardDrawn();
                    return;
                case 7:
                    if (PhoneWindowManager.DEBUG_WAKEUP) {
                        Slog.i(PhoneWindowManager.TAG, "Setting mWindowManagerDrawComplete");
                    }
                    PhoneWindowManager.this.finishWindowsDrawn();
                    return;
                default:
                    switch (i) {
                        case 9:
                            PhoneWindowManager.this.showRecentApps(false);
                            return;
                        case 10:
                            PhoneWindowManager.this.showGlobalActionsInternal();
                            return;
                        case 11:
                            PhoneWindowManager.this.handleHideBootMessage();
                            return;
                        case 12:
                            PhoneWindowManager.this.launchVoiceAssistWithWakeLock();
                            return;
                        case 13:
                            PhoneWindowManager.this.powerPress(((Long) msg.obj).longValue(), msg.arg1 != 0, msg.arg2);
                            PhoneWindowManager.this.finishPowerKeyPress();
                            return;
                        case 14:
                            PhoneWindowManager.this.powerLongPress();
                            return;
                        case 15:
                            PhoneWindowManager.this.updateDreamingSleepToken(msg.arg1 != 0);
                            return;
                        case 16:
                            WindowManagerPolicy.WindowState targetBar = msg.arg1 == 0 ? PhoneWindowManager.this.mStatusBar : PhoneWindowManager.this.mNavigationBar;
                            if (targetBar != null) {
                                PhoneWindowManager.this.requestTransientBars(targetBar);
                                return;
                            }
                            return;
                        case 17:
                            PhoneWindowManager.this.showPictureInPictureMenuInternal();
                            return;
                        case 18:
                            PhoneWindowManager.this.backLongPress();
                            return;
                        case 19:
                            PhoneWindowManager.this.disposeInputConsumer((WindowManagerPolicy.InputConsumer) msg.obj);
                            return;
                        case 20:
                            PhoneWindowManager.this.accessibilityShortcutActivated();
                            return;
                        case 21:
                            PhoneWindowManager.this.requestFullBugreport();
                            return;
                        case 22:
                            if (PhoneWindowManager.this.mAccessibilityShortcutController.isAccessibilityShortcutAvailable(false)) {
                                PhoneWindowManager.this.accessibilityShortcutActivated();
                                return;
                            }
                            return;
                        case 23:
                            PhoneWindowManager.this.mAutofillManagerInternal.onBackKeyPressed();
                            return;
                        case 24:
                            PhoneWindowManager.this.sendSystemKeyToStatusBar(msg.arg1);
                            return;
                        case 25:
                            PhoneWindowManager.this.launchAllAppsAction();
                            return;
                        case 26:
                            int deviceId = msg.arg1;
                            String hint = (String) msg.obj;
                            PhoneWindowManager.this.launchAssistAction(hint, deviceId);
                            return;
                        case PhoneWindowManager.MSG_LAUNCH_ASSIST_LONG_PRESS /* 27 */:
                            PhoneWindowManager.this.launchAssistLongPressAction();
                            return;
                        case 28:
                            PhoneWindowManager.this.powerVeryLongPress();
                            return;
                        case 29:
                            removeMessages(29);
                            Intent intent = new Intent("android.intent.action.USER_ACTIVITY_NOTIFICATION");
                            intent.addFlags(1073741824);
                            PhoneWindowManager.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, "android.permission.USER_ACTIVITY");
                            break;
                        case 30:
                            break;
                        default:
                            switch (i) {
                                case 101:
                                case 102:
                                case 103:
                                    PhoneWindowManager.this.handleKeyEvent(msg.what);
                                    return;
                                default:
                                    return;
                            }
                    }
                    PhoneWindowManager.this.handleRingerChordGesture();
                    return;
            }
        }
    }

    /* loaded from: classes.dex */
    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = PhoneWindowManager.this.mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor("end_button_behavior"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("incall_power_button_behavior"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("incall_back_button_behavior"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("wake_gesture_enabled"), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation"), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor("user_rotation"), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor("screen_off_timeout"), false, this, -1);
            resolver.registerContentObserver(Settings.System.getUriFor("pointer_location"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("default_input_method"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("immersive_mode_confirmations"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("show_rotation_suggestions"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("volume_hush_gesture"), false, this, -1);
            resolver.registerContentObserver(Settings.Global.getUriFor("policy_control"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("system_navigation_keys_enabled"), false, this, -1);
            PhoneWindowManager.this.updateSettings();
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            PhoneWindowManager.this.updateSettings();
            PhoneWindowManager.this.updateRotation(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MyWakeGestureListener extends WakeGestureListener {
        MyWakeGestureListener(Context context, Handler handler) {
            super(context, handler);
        }

        @Override // com.android.server.policy.WakeGestureListener
        public void onWakeUp() {
            synchronized (PhoneWindowManager.this.mLock) {
                if (PhoneWindowManager.this.shouldEnableWakeGestureLp()) {
                    PhoneWindowManager.this.performHapticFeedbackLw(null, 1, false);
                    PhoneWindowManager.this.wakeUp(SystemClock.uptimeMillis(), PhoneWindowManager.this.mAllowTheaterModeWakeFromWakeGesture, "android.policy:GESTURE");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MyOrientationListener extends WindowOrientationListener {
        private SparseArray<Runnable> mRunnableCache;

        MyOrientationListener(Context context, Handler handler) {
            super(context, handler);
            this.mRunnableCache = new SparseArray<>(5);
        }

        /* loaded from: classes.dex */
        private class UpdateRunnable implements Runnable {
            private final int mRotation;

            UpdateRunnable(int rotation) {
                this.mRotation = rotation;
            }

            @Override // java.lang.Runnable
            public void run() {
                PhoneWindowManager.this.mPowerManagerInternal.powerHint(2, 0);
                if (PhoneWindowManager.this.isRotationChoicePossible(PhoneWindowManager.this.mCurrentAppOrientation)) {
                    boolean isValid = PhoneWindowManager.this.isValidRotationChoice(PhoneWindowManager.this.mCurrentAppOrientation, this.mRotation);
                    PhoneWindowManager.this.sendProposedRotationChangeToStatusBarInternal(this.mRotation, isValid);
                    return;
                }
                PhoneWindowManager.this.updateRotation(false);
            }
        }

        @Override // com.android.server.policy.WindowOrientationListener
        public void onProposedRotationChanged(int rotation) {
            Runnable r = this.mRunnableCache.get(rotation, null);
            if (r == null) {
                r = new UpdateRunnable(rotation);
                this.mRunnableCache.put(rotation, r);
            }
            PhoneWindowManager.this.mHandler.post(r);
        }
    }

    public static /* synthetic */ void lambda$new$0(PhoneWindowManager phoneWindowManager) {
        if (phoneWindowManager.mWindowSleepToken != null) {
            return;
        }
        phoneWindowManager.mWindowSleepToken = phoneWindowManager.mActivityManagerInternal.acquireSleepToken("WindowSleepToken", 0);
    }

    public static /* synthetic */ void lambda$new$1(PhoneWindowManager phoneWindowManager) {
        if (phoneWindowManager.mWindowSleepToken == null) {
            return;
        }
        phoneWindowManager.mWindowSleepToken.release();
        phoneWindowManager.mWindowSleepToken = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRingerChordGesture() {
        if (this.mRingerToggleChord == 0) {
            return;
        }
        getAudioManagerInternal();
        this.mAudioManagerInternal.silenceRingerModeInternal("volume_hush");
        Settings.Secure.putInt(this.mContext.getContentResolver(), "hush_gesture_used", 1);
        this.mLogger.action(1440, this.mRingerToggleChord);
    }

    IStatusBarService getStatusBarService() {
        IStatusBarService iStatusBarService;
        synchronized (this.mServiceAquireLock) {
            if (this.mStatusBarService == null) {
                this.mStatusBarService = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
            }
            iStatusBarService = this.mStatusBarService;
        }
        return iStatusBarService;
    }

    StatusBarManagerInternal getStatusBarManagerInternal() {
        StatusBarManagerInternal statusBarManagerInternal;
        synchronized (this.mServiceAquireLock) {
            if (this.mStatusBarManagerInternal == null) {
                this.mStatusBarManagerInternal = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
            }
            statusBarManagerInternal = this.mStatusBarManagerInternal;
        }
        return statusBarManagerInternal;
    }

    AudioManagerInternal getAudioManagerInternal() {
        AudioManagerInternal audioManagerInternal;
        synchronized (this.mServiceAquireLock) {
            if (this.mAudioManagerInternal == null) {
                this.mAudioManagerInternal = (AudioManagerInternal) LocalServices.getService(AudioManagerInternal.class);
            }
            audioManagerInternal = this.mAudioManagerInternal;
        }
        return audioManagerInternal;
    }

    boolean needSensorRunningLp() {
        if (this.mSupportAutoRotation && (this.mCurrentAppOrientation == 4 || this.mCurrentAppOrientation == 10 || this.mCurrentAppOrientation == 7 || this.mCurrentAppOrientation == 6)) {
            return true;
        }
        if ((this.mCarDockEnablesAccelerometer && this.mDockMode == 2) || (this.mDeskDockEnablesAccelerometer && (this.mDockMode == 1 || this.mDockMode == 3 || this.mDockMode == 4))) {
            return true;
        }
        if (this.mUserRotationMode == 1) {
            return this.mSupportAutoRotation && this.mShowRotationSuggestions == 1;
        }
        return this.mSupportAutoRotation;
    }

    void updateOrientationListenerLp() {
        if (!this.mOrientationListener.canDetectOrientation()) {
            return;
        }
        boolean disable = true;
        if (this.mScreenOnEarly && this.mAwake && this.mKeyguardDrawComplete && this.mWindowManagerDrawComplete && needSensorRunningLp()) {
            disable = false;
            if (!this.mOrientationSensorEnabled) {
                this.mOrientationListener.enable(true);
                this.mOrientationSensorEnabled = true;
            }
        }
        if (disable && this.mOrientationSensorEnabled) {
            this.mOrientationListener.disable();
            this.mOrientationSensorEnabled = false;
        }
    }

    private void interceptBackKeyDown() {
        this.mBackKeyHandled = false;
        if (hasLongPressOnBackBehavior()) {
            Message msg = this.mHandler.obtainMessage(18);
            msg.setAsynchronous(true);
            this.mHandler.sendMessageDelayed(msg, ViewConfiguration.get(this.mContext).getDeviceGlobalActionKeyTimeout());
        }
    }

    private boolean interceptBackKeyUp(KeyEvent event) {
        TelecomManager telecomManager;
        boolean handled = this.mBackKeyHandled;
        cancelPendingBackKeyAction();
        if (this.mHasFeatureWatch && (telecomManager = getTelecommService()) != null) {
            if (telecomManager.isRinging()) {
                telecomManager.silenceRinger();
                return false;
            } else if ((this.mIncallBackBehavior & 1) != 0 && telecomManager.isInCall()) {
                return telecomManager.endCall();
            }
        }
        if (this.mAutofillManagerInternal != null && event.getKeyCode() == 4) {
            this.mHandler.sendMessage(this.mHandler.obtainMessage(23));
        }
        return handled;
    }

    private void interceptPowerKeyDown(KeyEvent event, boolean interactive) {
        if (!this.mPowerKeyWakeLock.isHeld()) {
            this.mPowerKeyWakeLock.acquire();
        }
        if (this.mPowerKeyPressCounter != 0) {
            this.mHandler.removeMessages(13);
        }
        boolean panic = this.mImmersiveModeConfirmation.onPowerKeyDown(interactive, SystemClock.elapsedRealtime(), isImmersiveMode(this.mLastSystemUiFlags), isNavBarEmpty(this.mLastSystemUiFlags));
        if (panic) {
            this.mHandler.post(this.mHiddenNavPanic);
        }
        Handler handler = this.mHandler;
        WindowManagerPolicy.WindowManagerFuncs windowManagerFuncs = this.mWindowManagerFuncs;
        Objects.requireNonNull(windowManagerFuncs);
        handler.post(new $$Lambda$oXa0y3A00RiQs6KTPBgpkGtgw(windowManagerFuncs));
        if (interactive && !this.mScreenshotChordPowerKeyTriggered && (event.getFlags() & 1024) == 0) {
            this.mScreenshotChordPowerKeyTriggered = true;
            this.mScreenshotChordPowerKeyTime = event.getDownTime();
            interceptScreenshotChord();
            interceptRingerToggleChord();
        }
        TelecomManager telecomManager = getTelecommService();
        boolean hungUp = false;
        if (telecomManager != null) {
            if (telecomManager.isRinging()) {
                telecomManager.silenceRinger();
            } else if ((this.mIncallPowerBehavior & 2) != 0 && telecomManager.isInCall() && interactive) {
                hungUp = telecomManager.endCall();
            }
        }
        GestureLauncherService gestureService = (GestureLauncherService) LocalServices.getService(GestureLauncherService.class);
        boolean gesturedServiceIntercepted = false;
        if (gestureService != null) {
            gesturedServiceIntercepted = gestureService.interceptPowerKeyDown(event, interactive, this.mTmpBoolean);
            if (this.mTmpBoolean.value && this.mRequestedOrGoingToSleep) {
                this.mCameraGestureTriggeredDuringGoingToSleep = true;
            }
        }
        sendSystemKeyToStatusBarAsync(event.getKeyCode());
        this.mPowerKeyHandled = hungUp || this.mScreenshotChordVolumeDownKeyTriggered || this.mA11yShortcutChordVolumeUpKeyTriggered || gesturedServiceIntercepted;
        if (this.mPowerKeyHandled) {
            return;
        }
        if (interactive) {
            if (hasLongPressOnPowerBehavior()) {
                if ((event.getFlags() & 128) != 0) {
                    powerLongPress();
                    return;
                }
                Message msg = this.mHandler.obtainMessage(14);
                msg.setAsynchronous(true);
                this.mHandler.sendMessageDelayed(msg, ViewConfiguration.get(this.mContext).getDeviceGlobalActionKeyTimeout());
                if (hasVeryLongPressOnPowerBehavior()) {
                    Message longMsg = this.mHandler.obtainMessage(28);
                    longMsg.setAsynchronous(true);
                    this.mHandler.sendMessageDelayed(longMsg, this.mVeryLongPressTimeout);
                    return;
                }
                return;
            }
            return;
        }
        wakeUpFromPowerKey(event.getDownTime());
        if (this.mSupportLongPressPowerWhenNonInteractive && hasLongPressOnPowerBehavior()) {
            if ((event.getFlags() & 128) != 0) {
                powerLongPress();
            } else {
                Message msg2 = this.mHandler.obtainMessage(14);
                msg2.setAsynchronous(true);
                this.mHandler.sendMessageDelayed(msg2, ViewConfiguration.get(this.mContext).getDeviceGlobalActionKeyTimeout());
                if (hasVeryLongPressOnPowerBehavior()) {
                    Message longMsg2 = this.mHandler.obtainMessage(28);
                    longMsg2.setAsynchronous(true);
                    this.mHandler.sendMessageDelayed(longMsg2, this.mVeryLongPressTimeout);
                }
            }
            this.mBeganFromNonInteractive = true;
            return;
        }
        int maxCount = getMaxMultiPressPowerCount();
        if (maxCount <= 1) {
            this.mPowerKeyHandled = true;
        } else {
            this.mBeganFromNonInteractive = true;
        }
    }

    private void interceptPowerKeyUp(KeyEvent event, boolean interactive, boolean canceled) {
        boolean handled = canceled || this.mPowerKeyHandled;
        this.mScreenshotChordPowerKeyTriggered = false;
        cancelPendingScreenshotChordAction();
        cancelPendingPowerKeyAction();
        if (!handled) {
            this.mPowerKeyPressCounter++;
            int maxCount = getMaxMultiPressPowerCount();
            long eventTime = event.getDownTime();
            if (this.mPowerKeyPressCounter < maxCount) {
                Message msg = this.mHandler.obtainMessage(13, interactive ? 1 : 0, this.mPowerKeyPressCounter, Long.valueOf(eventTime));
                msg.setAsynchronous(true);
                this.mHandler.sendMessageDelayed(msg, ViewConfiguration.getMultiPressTimeout());
                return;
            }
            powerPress(eventTime, interactive, this.mPowerKeyPressCounter);
        }
        finishPowerKeyPress();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void finishPowerKeyPress() {
        this.mBeganFromNonInteractive = false;
        this.mPowerKeyPressCounter = 0;
        if (this.mPowerKeyWakeLock.isHeld()) {
            this.mPowerKeyWakeLock.release();
        }
    }

    private void cancelPendingPowerKeyAction() {
        if (!this.mPowerKeyHandled) {
            this.mPowerKeyHandled = true;
            this.mHandler.removeMessages(14);
        }
        if (hasVeryLongPressOnPowerBehavior()) {
            this.mHandler.removeMessages(28);
        }
    }

    private void cancelPendingBackKeyAction() {
        if (!this.mBackKeyHandled) {
            this.mBackKeyHandled = true;
            this.mHandler.removeMessages(18);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void powerPress(long eventTime, boolean interactive, int count) {
        if (this.mScreenOnEarly && !this.mScreenOnFully) {
            Slog.i(TAG, "Suppressed redundant power key press while already in the process of turning the screen on.");
            return;
        }
        Slog.d(TAG, "powerPress: eventTime=" + eventTime + " interactive=" + interactive + " count=" + count + " beganFromNonInteractive=" + this.mBeganFromNonInteractive + " mShortPressOnPowerBehavior=" + this.mShortPressOnPowerBehavior);
        if (count == 2) {
            powerMultiPressAction(eventTime, interactive, this.mDoublePressOnPowerBehavior);
        } else if (count == 3) {
            powerMultiPressAction(eventTime, interactive, this.mTriplePressOnPowerBehavior);
        } else if (!interactive || this.mBeganFromNonInteractive) {
        } else {
            switch (this.mShortPressOnPowerBehavior) {
                case 0:
                default:
                    return;
                case 1:
                    goToSleep(eventTime, 4, 0);
                    return;
                case 2:
                    goToSleep(eventTime, 4, 1);
                    return;
                case 3:
                    goToSleep(eventTime, 4, 1);
                    launchHomeFromHotKey();
                    return;
                case 4:
                    shortPressPowerGoHome();
                    return;
                case 5:
                    if (this.mDismissImeOnBackKeyPressed) {
                        if (this.mInputMethodManagerInternal == null) {
                            this.mInputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
                        }
                        if (this.mInputMethodManagerInternal != null) {
                            this.mInputMethodManagerInternal.hideCurrentInputMethod();
                            return;
                        }
                        return;
                    }
                    shortPressPowerGoHome();
                    return;
            }
        }
    }

    private void goToSleep(long eventTime, int reason, int flags) {
        this.mRequestedOrGoingToSleep = true;
        this.mPowerManager.goToSleep(eventTime, reason, flags);
    }

    private void shortPressPowerGoHome() {
        launchHomeFromHotKey(true, false);
        if (isKeyguardShowingAndNotOccluded()) {
            this.mKeyguardDelegate.onShortPowerPressedGoHome();
        }
    }

    private void powerMultiPressAction(long eventTime, boolean interactive, int behavior) {
        switch (behavior) {
            case 0:
            default:
                return;
            case 1:
                if (!isUserSetupComplete()) {
                    Slog.i(TAG, "Ignoring toggling theater mode - device not setup.");
                    return;
                } else if (isTheaterModeEnabled()) {
                    Slog.i(TAG, "Toggling theater mode off.");
                    Settings.Global.putInt(this.mContext.getContentResolver(), "theater_mode_on", 0);
                    if (!interactive) {
                        wakeUpFromPowerKey(eventTime);
                        return;
                    }
                    return;
                } else {
                    Slog.i(TAG, "Toggling theater mode on.");
                    Settings.Global.putInt(this.mContext.getContentResolver(), "theater_mode_on", 1);
                    if (this.mGoToSleepOnButtonPressTheaterMode && interactive) {
                        goToSleep(eventTime, 4, 0);
                        return;
                    }
                    return;
                }
            case 2:
                Slog.i(TAG, "Starting brightness boost.");
                if (!interactive) {
                    wakeUpFromPowerKey(eventTime);
                }
                this.mPowerManager.boostScreenBrightness(eventTime);
                return;
        }
    }

    private int getMaxMultiPressPowerCount() {
        if (this.mTriplePressOnPowerBehavior != 0) {
            return 3;
        }
        if (this.mDoublePressOnPowerBehavior != 0) {
            return 2;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void powerLongPress() {
        int behavior = getResolvedLongPressOnPowerBehavior();
        switch (behavior) {
            case 0:
            default:
                return;
            case 1:
                this.mPowerKeyHandled = true;
                performHapticFeedbackLw(null, 0, false);
                showGlobalActionsInternal();
                return;
            case 2:
            case 3:
                this.mPowerKeyHandled = true;
                performHapticFeedbackLw(null, 0, false);
                sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
                this.mWindowManagerFuncs.shutdown(behavior == 2);
                return;
            case 4:
                this.mPowerKeyHandled = true;
                performHapticFeedbackLw(null, 0, false);
                boolean keyguardActive = this.mKeyguardDelegate != null ? this.mKeyguardDelegate.isShowing() : false;
                if (!keyguardActive) {
                    Intent intent = new Intent("android.intent.action.VOICE_ASSIST");
                    if (this.mAllowStartActivityForLongPressOnPowerDuringSetup) {
                        this.mContext.startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
                        return;
                    } else {
                        startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
                        return;
                    }
                }
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void powerVeryLongPress() {
        switch (this.mVeryLongPressOnPowerBehavior) {
            case 0:
            default:
                return;
            case 1:
                this.mPowerKeyHandled = true;
                performHapticFeedbackLw(null, 0, false);
                showGlobalActionsInternal();
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void backLongPress() {
        boolean keyguardActive;
        this.mBackKeyHandled = true;
        switch (this.mLongPressOnBackBehavior) {
            case 0:
            default:
                return;
            case 1:
                if (this.mKeyguardDelegate == null) {
                    keyguardActive = false;
                } else {
                    keyguardActive = this.mKeyguardDelegate.isShowing();
                }
                if (!keyguardActive) {
                    Intent intent = new Intent("android.intent.action.VOICE_ASSIST");
                    startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
                    return;
                }
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void accessibilityShortcutActivated() {
        this.mAccessibilityShortcutController.performAccessibilityShortcut();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disposeInputConsumer(WindowManagerPolicy.InputConsumer inputConsumer) {
        if (inputConsumer != null) {
            inputConsumer.dismiss();
        }
    }

    private void sleepPress() {
        if (this.mShortPressOnSleepBehavior == 1) {
            launchHomeFromHotKey(false, true);
        }
    }

    private void sleepRelease(long eventTime) {
        switch (this.mShortPressOnSleepBehavior) {
            case 0:
            case 1:
                Slog.i(TAG, "sleepRelease() calling goToSleep(GO_TO_SLEEP_REASON_SLEEP_BUTTON)");
                goToSleep(eventTime, 6, 0);
                return;
            default:
                return;
        }
    }

    private int getResolvedLongPressOnPowerBehavior() {
        if (FactoryTest.isLongPressOnPowerOffEnabled()) {
            return 3;
        }
        return this.mLongPressOnPowerBehavior;
    }

    private boolean hasLongPressOnPowerBehavior() {
        return getResolvedLongPressOnPowerBehavior() != 0;
    }

    private boolean hasVeryLongPressOnPowerBehavior() {
        return this.mVeryLongPressOnPowerBehavior != 0;
    }

    private boolean hasLongPressOnBackBehavior() {
        return this.mLongPressOnBackBehavior != 0;
    }

    private void interceptScreenshotChord() {
        if (this.mScreenshotChordEnabled && this.mScreenshotChordVolumeDownKeyTriggered && this.mScreenshotChordPowerKeyTriggered && !this.mA11yShortcutChordVolumeUpKeyTriggered) {
            long now = SystemClock.uptimeMillis();
            if (now <= this.mScreenshotChordVolumeDownKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS && now <= this.mScreenshotChordPowerKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                this.mScreenshotChordVolumeDownKeyConsumed = true;
                cancelPendingPowerKeyAction();
                this.mScreenshotRunnable.setScreenshotType(1);
                this.mHandler.postDelayed(this.mScreenshotRunnable, getScreenshotChordLongPressDelay());
            }
        }
    }

    private void interceptAccessibilityShortcutChord() {
        if (this.mAccessibilityShortcutController.isAccessibilityShortcutAvailable(isKeyguardLocked()) && this.mScreenshotChordVolumeDownKeyTriggered && this.mA11yShortcutChordVolumeUpKeyTriggered && !this.mScreenshotChordPowerKeyTriggered) {
            long now = SystemClock.uptimeMillis();
            if (now <= this.mScreenshotChordVolumeDownKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS && now <= this.mA11yShortcutChordVolumeUpKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                this.mScreenshotChordVolumeDownKeyConsumed = true;
                this.mA11yShortcutChordVolumeUpKeyConsumed = true;
                this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(20), getAccessibilityShortcutTimeout());
            }
        }
    }

    private void interceptRingerToggleChord() {
        if (this.mRingerToggleChord != 0 && this.mScreenshotChordPowerKeyTriggered && this.mA11yShortcutChordVolumeUpKeyTriggered) {
            long now = SystemClock.uptimeMillis();
            if (now <= this.mA11yShortcutChordVolumeUpKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS && now <= this.mScreenshotChordPowerKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                this.mA11yShortcutChordVolumeUpKeyConsumed = true;
                cancelPendingPowerKeyAction();
                this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(30), getRingerToggleChordDelay());
            }
        }
    }

    private long getAccessibilityShortcutTimeout() {
        ViewConfiguration config = ViewConfiguration.get(this.mContext);
        if (Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "accessibility_shortcut_dialog_shown", 0, this.mCurrentUserId) == 0) {
            return config.getAccessibilityShortcutKeyTimeout();
        }
        return config.getAccessibilityShortcutKeyTimeoutAfterConfirmation();
    }

    private long getScreenshotChordLongPressDelay() {
        if (this.mKeyguardDelegate.isShowing()) {
            return KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER * ((float) ViewConfiguration.get(this.mContext).getDeviceGlobalActionKeyTimeout());
        }
        return ViewConfiguration.get(this.mContext).getDeviceGlobalActionKeyTimeout();
    }

    private long getRingerToggleChordDelay() {
        return ViewConfiguration.getTapTimeout();
    }

    private void cancelPendingScreenshotChordAction() {
        this.mHandler.removeCallbacks(this.mScreenshotRunnable);
    }

    private void cancelPendingAccessibilityShortcutAction() {
        this.mHandler.removeMessages(20);
    }

    private void cancelPendingRingerToggleChordAction() {
        this.mHandler.removeMessages(30);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ScreenshotRunnable implements Runnable {
        private int mScreenshotType;

        private ScreenshotRunnable() {
            this.mScreenshotType = 1;
        }

        public void setScreenshotType(int screenshotType) {
            this.mScreenshotType = screenshotType;
        }

        @Override // java.lang.Runnable
        public void run() {
            ScreenshotHelper screenshotHelper = PhoneWindowManager.this.mScreenshotHelper;
            int i = this.mScreenshotType;
            boolean z = false;
            boolean z2 = PhoneWindowManager.this.mStatusBar != null && PhoneWindowManager.this.mStatusBar.isVisibleLw();
            if (PhoneWindowManager.this.mNavigationBar != null && PhoneWindowManager.this.mNavigationBar.isVisibleLw()) {
                z = true;
            }
            screenshotHelper.takeScreenshot(i, z2, z, PhoneWindowManager.this.mHandler);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void showGlobalActions() {
        this.mHandler.removeMessages(10);
        this.mHandler.sendEmptyMessage(10);
    }

    void showGlobalActionsInternal() {
        if (this.mGlobalActions == null) {
            this.mGlobalActions = new GlobalActions(this.mContext, this.mWindowManagerFuncs);
        }
        boolean keyguardShowing = isKeyguardShowingAndNotOccluded();
        this.mGlobalActions.showDialog(keyguardShowing, isDeviceProvisioned());
        if (keyguardShowing) {
            this.mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    boolean isDeviceProvisioned() {
        return Settings.Global.getInt(this.mContext.getContentResolver(), "device_provisioned", 0) != 0;
    }

    boolean isUserSetupComplete() {
        boolean isSetupComplete = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "user_setup_complete", 0, -2) != 0;
        if (this.mHasFeatureLeanback) {
            return isSetupComplete & isTvUserSetupComplete();
        }
        return isSetupComplete;
    }

    private boolean isTvUserSetupComplete() {
        return Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "tv_user_setup_complete", 0, -2) != 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleShortPressOnHome() {
        HdmiControl hdmiControl = getHdmiControl();
        if (hdmiControl != null) {
            hdmiControl.turnOnTv();
        }
        if (this.mDreamManagerInternal != null && this.mDreamManagerInternal.isDreaming()) {
            this.mDreamManagerInternal.stopDream(false);
        } else {
            launchHomeFromHotKey();
        }
    }

    private HdmiControl getHdmiControl() {
        if (this.mHdmiControl == null) {
            if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.hdmi.cec")) {
                return null;
            }
            HdmiControlManager manager = (HdmiControlManager) this.mContext.getSystemService("hdmi_control");
            HdmiPlaybackClient client = null;
            if (manager != null) {
                client = manager.getPlaybackClient();
            }
            this.mHdmiControl = new HdmiControl(client);
        }
        return this.mHdmiControl;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class HdmiControl {
        private final HdmiPlaybackClient mClient;

        private HdmiControl(HdmiPlaybackClient client) {
            this.mClient = client;
        }

        public void turnOnTv() {
            if (this.mClient == null) {
                return;
            }
            this.mClient.oneTouchPlay(new HdmiPlaybackClient.OneTouchPlayCallback() { // from class: com.android.server.policy.PhoneWindowManager.HdmiControl.1
                public void onComplete(int result) {
                    if (result != 0) {
                        Log.w(PhoneWindowManager.TAG, "One touch play failed: " + result);
                    }
                }
            });
        }
    }

    private void handleLongPressOnHome(int deviceId) {
        if (this.mLongPressOnHomeBehavior == 0) {
            return;
        }
        this.mHomeConsumed = true;
        performHapticFeedbackLw(null, 0, false);
        switch (this.mLongPressOnHomeBehavior) {
            case 1:
                launchAllAppsAction();
                return;
            case 2:
                launchAssistAction(null, deviceId);
                return;
            default:
                Log.w(TAG, "Undefined home long press behavior: " + this.mLongPressOnHomeBehavior);
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchAllAppsAction() {
        Intent intent = new Intent("android.intent.action.ALL_APPS");
        if (this.mHasFeatureLeanback) {
            PackageManager pm = this.mContext.getPackageManager();
            Intent intentLauncher = new Intent("android.intent.action.MAIN");
            intentLauncher.addCategory("android.intent.category.HOME");
            ResolveInfo resolveInfo = pm.resolveActivityAsUser(intentLauncher, 1048576, this.mCurrentUserId);
            if (resolveInfo != null) {
                intent.setPackage(resolveInfo.activityInfo.packageName);
            }
        }
        startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private void handleDoubleTapOnHome() {
        if (this.mDoubleTapOnHomeBehavior == 1) {
            this.mHomeConsumed = true;
            toggleRecentApps();
        }
    }

    private void showPictureInPictureMenu(KeyEvent event) {
        this.mHandler.removeMessages(17);
        Message msg = this.mHandler.obtainMessage(17);
        msg.setAsynchronous(true);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showPictureInPictureMenuInternal() {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.showPictureInPictureMenu();
        }
    }

    private boolean isRoundWindow() {
        return this.mContext.getResources().getConfiguration().isScreenRound();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void init(Context context, IWindowManager windowManager, WindowManagerPolicy.WindowManagerFuncs windowManagerFuncs) {
        int maxRadius;
        int minHorizontal;
        int maxHorizontal;
        int minVertical;
        int maxVertical;
        this.mContext = context;
        this.mWindowManager = windowManager;
        this.mWindowManagerFuncs = windowManagerFuncs;
        this.mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
        this.mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        this.mInputManagerInternal = (InputManagerInternal) LocalServices.getService(InputManagerInternal.class);
        this.mDreamManagerInternal = (DreamManagerInternal) LocalServices.getService(DreamManagerInternal.class);
        this.mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mHasFeatureWatch = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch");
        this.mHasFeatureLeanback = this.mContext.getPackageManager().hasSystemFeature("android.software.leanback");
        this.mAccessibilityShortcutController = new AccessibilityShortcutController(this.mContext, new Handler(), this.mCurrentUserId);
        this.mLogger = new MetricsLogger();
        boolean burnInProtectionEnabled = context.getResources().getBoolean(17956953);
        boolean burnInProtectionDevMode = SystemProperties.getBoolean("persist.debug.force_burn_in", false);
        if (burnInProtectionEnabled || burnInProtectionDevMode) {
            if (burnInProtectionDevMode) {
                minHorizontal = -8;
                int maxRadius2 = isRoundWindow() ? 6 : -1;
                maxHorizontal = 8;
                minVertical = -8;
                maxVertical = -4;
                maxRadius = maxRadius2;
            } else {
                Resources resources = context.getResources();
                int minHorizontal2 = resources.getInteger(17694750);
                int maxHorizontal2 = resources.getInteger(17694747);
                int minVertical2 = resources.getInteger(17694751);
                int maxVertical2 = resources.getInteger(17694749);
                maxRadius = resources.getInteger(17694748);
                minHorizontal = minHorizontal2;
                maxHorizontal = maxHorizontal2;
                minVertical = minVertical2;
                maxVertical = maxVertical2;
            }
            this.mBurnInProtectionHelper = new BurnInProtectionHelper(context, minHorizontal, maxHorizontal, minVertical, maxVertical, maxRadius);
        }
        this.mHandler = new PolicyHandler();
        this.mWakeGestureListener = new MyWakeGestureListener(this.mContext, this.mHandler);
        this.mOrientationListener = new MyOrientationListener(this.mContext, this.mHandler);
        try {
            this.mOrientationListener.setCurrentRotation(windowManager.getDefaultDisplayRotation());
        } catch (RemoteException e) {
        }
        this.mSettingsObserver = new SettingsObserver(this.mHandler);
        this.mSettingsObserver.observe();
        this.mShortcutManager = new ShortcutManager(context);
        this.mUiMode = context.getResources().getInteger(17694773);
        this.mHomeIntent = new Intent("android.intent.action.MAIN", (Uri) null);
        this.mHomeIntent.addCategory("android.intent.category.HOME");
        this.mHomeIntent.addFlags(270532608);
        this.mEnableCarDockHomeCapture = context.getResources().getBoolean(17956954);
        this.mCarDockIntent = new Intent("android.intent.action.MAIN", (Uri) null);
        this.mCarDockIntent.addCategory("android.intent.category.CAR_DOCK");
        this.mCarDockIntent.addFlags(270532608);
        this.mDeskDockIntent = new Intent("android.intent.action.MAIN", (Uri) null);
        this.mDeskDockIntent.addCategory("android.intent.category.DESK_DOCK");
        this.mDeskDockIntent.addFlags(270532608);
        this.mVrHeadsetHomeIntent = new Intent("android.intent.action.MAIN", (Uri) null);
        this.mVrHeadsetHomeIntent.addCategory("android.intent.category.VR_HOME");
        this.mVrHeadsetHomeIntent.addFlags(270532608);
        this.mPowerManager = (PowerManager) context.getSystemService("power");
        this.mBroadcastWakeLock = this.mPowerManager.newWakeLock(1, "PhoneWindowManager.mBroadcastWakeLock");
        this.mPowerKeyWakeLock = this.mPowerManager.newWakeLock(1, "PhoneWindowManager.mPowerKeyWakeLock");
        this.mEnableShiftMenuBugReports = "1".equals(SystemProperties.get("ro.debuggable"));
        this.mSupportAutoRotation = this.mContext.getResources().getBoolean(17957040);
        this.mLidOpenRotation = readRotation(17694797);
        this.mCarDockRotation = readRotation(17694755);
        this.mDeskDockRotation = readRotation(17694776);
        this.mUndockedHdmiRotation = readRotation(17694877);
        this.mCarDockEnablesAccelerometer = this.mContext.getResources().getBoolean(17956911);
        this.mDeskDockEnablesAccelerometer = this.mContext.getResources().getBoolean(17956924);
        this.mLidKeyboardAccessibility = this.mContext.getResources().getInteger(17694795);
        this.mLidNavigationAccessibility = this.mContext.getResources().getInteger(17694796);
        this.mLidControlsScreenLock = this.mContext.getResources().getBoolean(17956990);
        this.mLidControlsSleep = this.mContext.getResources().getBoolean(17956991);
        this.mTranslucentDecorEnabled = this.mContext.getResources().getBoolean(17956969);
        this.mAllowTheaterModeWakeFromKey = this.mContext.getResources().getBoolean(17956880);
        this.mAllowTheaterModeWakeFromPowerKey = this.mAllowTheaterModeWakeFromKey || this.mContext.getResources().getBoolean(17956884);
        this.mAllowTheaterModeWakeFromMotion = this.mContext.getResources().getBoolean(17956882);
        this.mAllowTheaterModeWakeFromMotionWhenNotDreaming = this.mContext.getResources().getBoolean(17956883);
        this.mAllowTheaterModeWakeFromCameraLens = this.mContext.getResources().getBoolean(17956877);
        this.mAllowTheaterModeWakeFromLidSwitch = this.mContext.getResources().getBoolean(17956881);
        this.mAllowTheaterModeWakeFromWakeGesture = this.mContext.getResources().getBoolean(17956879);
        this.mGoToSleepOnButtonPressTheaterMode = this.mContext.getResources().getBoolean(17956982);
        this.mSupportLongPressPowerWhenNonInteractive = this.mContext.getResources().getBoolean(17957043);
        this.mLongPressOnBackBehavior = this.mContext.getResources().getInteger(17694800);
        this.mShortPressOnPowerBehavior = this.mContext.getResources().getInteger(17694864);
        this.mLongPressOnPowerBehavior = this.mContext.getResources().getInteger(17694802);
        this.mVeryLongPressOnPowerBehavior = this.mContext.getResources().getInteger(17694879);
        this.mDoublePressOnPowerBehavior = this.mContext.getResources().getInteger(17694778);
        this.mTriplePressOnPowerBehavior = this.mContext.getResources().getInteger(17694876);
        this.mShortPressOnSleepBehavior = this.mContext.getResources().getInteger(17694865);
        this.mVeryLongPressTimeout = this.mContext.getResources().getInteger(17694880);
        this.mAllowStartActivityForLongPressOnPowerDuringSetup = this.mContext.getResources().getBoolean(17956876);
        this.mUseTvRouting = AudioSystem.getPlatformType(this.mContext) == 2;
        this.mHandleVolumeKeysInWM = this.mContext.getResources().getBoolean(17956984);
        readConfigurationDependentBehaviors();
        this.mAccessibilityManager = (AccessibilityManager) context.getSystemService("accessibility");
        IntentFilter filter = new IntentFilter();
        filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_ENTER_DESK_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_DESK_MODE);
        filter.addAction("android.intent.action.DOCK_EVENT");
        Intent intent = context.registerReceiver(this.mDockReceiver, filter);
        if (intent != null) {
            this.mDockMode = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
        }
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction("android.intent.action.DREAMING_STARTED");
        filter2.addAction("android.intent.action.DREAMING_STOPPED");
        context.registerReceiver(this.mDreamReceiver, filter2);
        context.registerReceiver(this.mMultiuserReceiver, new IntentFilter("android.intent.action.USER_SWITCHED"));
        this.mSystemGestures = new SystemGesturesPointerEventListener(context, new SystemGesturesPointerEventListener.Callbacks() { // from class: com.android.server.policy.PhoneWindowManager.8
            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromTop() {
                if (PhoneWindowManager.this.mStatusBar != null) {
                    PhoneWindowManager.this.requestTransientBars(PhoneWindowManager.this.mStatusBar);
                }
                xpSystemGesturesListener.handleSystemGestureEvent(1, false, PhoneWindowManager.this.mContext, PhoneWindowManager.this.mFocusedWindow);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromBottom() {
                if (PhoneWindowManager.this.mNavigationBar != null && PhoneWindowManager.this.mNavigationBarPosition == 4) {
                    PhoneWindowManager.this.requestTransientBars(PhoneWindowManager.this.mNavigationBar);
                }
                xpSystemGesturesListener.handleSystemGestureEvent(2, false, PhoneWindowManager.this.mContext, PhoneWindowManager.this.mFocusedWindow);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromRight() {
                if (PhoneWindowManager.this.mNavigationBar != null && PhoneWindowManager.this.mNavigationBarPosition == 2) {
                    PhoneWindowManager.this.requestTransientBars(PhoneWindowManager.this.mNavigationBar);
                }
                xpSystemGesturesListener.handleSystemGestureEvent(3, false, PhoneWindowManager.this.mContext, PhoneWindowManager.this.mFocusedWindow);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromLeft() {
                if (PhoneWindowManager.this.mNavigationBar != null && PhoneWindowManager.this.mNavigationBarPosition == 1) {
                    PhoneWindowManager.this.requestTransientBars(PhoneWindowManager.this.mNavigationBar);
                }
                xpSystemGesturesListener.handleSystemGestureEvent(4, false, PhoneWindowManager.this.mContext, PhoneWindowManager.this.mFocusedWindow);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onSwipeFromMultiPointer(int direction) {
                xpSystemGesturesListener.handleSystemGestureEvent(direction, true, PhoneWindowManager.this.mContext, PhoneWindowManager.this.mFocusedWindow);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onFling(int duration) {
                if (PhoneWindowManager.this.mPowerManagerInternal != null) {
                    PhoneWindowManager.this.mPowerManagerInternal.powerHint(2, duration);
                }
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onDebug() {
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onDown() {
                PhoneWindowManager.this.mOrientationListener.onTouchStart();
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onUpOrCancel() {
                PhoneWindowManager.this.mOrientationListener.onTouchEnd();
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onMouseHoverAtTop() {
                PhoneWindowManager.this.mHandler.removeMessages(16);
                Message msg = PhoneWindowManager.this.mHandler.obtainMessage(16);
                msg.arg1 = 0;
                PhoneWindowManager.this.mHandler.sendMessageDelayed(msg, 500L);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onMouseHoverAtBottom() {
                PhoneWindowManager.this.mHandler.removeMessages(16);
                Message msg = PhoneWindowManager.this.mHandler.obtainMessage(16);
                msg.arg1 = 1;
                PhoneWindowManager.this.mHandler.sendMessageDelayed(msg, 500L);
            }

            @Override // com.android.server.policy.SystemGesturesPointerEventListener.Callbacks
            public void onMouseLeaveFromEdge() {
                PhoneWindowManager.this.mHandler.removeMessages(16);
            }
        });
        this.mImmersiveModeConfirmation = new ImmersiveModeConfirmation(this.mContext);
        this.mWindowManagerFuncs.registerPointerEventListener(this.mSystemGestures);
        this.mVibrator = (Vibrator) context.getSystemService("vibrator");
        this.mLongPressVibePattern = getLongIntArray(this.mContext.getResources(), 17236017);
        this.mCalendarDateVibePattern = getLongIntArray(this.mContext.getResources(), 17235993);
        this.mSafeModeEnabledVibePattern = getLongIntArray(this.mContext.getResources(), 17236032);
        this.mScreenshotChordEnabled = this.mContext.getResources().getBoolean(17956968);
        this.mGlobalKeyManager = new GlobalKeyManager(this.mContext);
        initializeHdmiState();
        if (!this.mPowerManager.isInteractive()) {
            startedGoingToSleep(2);
            finishedGoingToSleep(2);
        }
        this.mWindowManagerInternal.registerAppTransitionListener(this.mStatusBarController.getAppTransitionListener());
        this.mWindowManagerInternal.registerAppTransitionListener(new WindowManagerInternal.AppTransitionListener() { // from class: com.android.server.policy.PhoneWindowManager.9
            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public int onAppTransitionStartingLocked(int transit, IBinder openToken, IBinder closeToken, long duration, long statusBarAnimationStartTime, long statusBarAnimationDuration) {
                return PhoneWindowManager.this.handleStartTransitionForKeyguardLw(transit, duration);
            }

            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public void onAppTransitionCancelledLocked(int transit) {
                PhoneWindowManager.this.handleStartTransitionForKeyguardLw(transit, 0L);
            }
        });
        this.mKeyguardDelegate = new KeyguardServiceDelegate(this.mContext, new KeyguardStateMonitor.StateCallback() { // from class: com.android.server.policy.PhoneWindowManager.10
            @Override // com.android.server.policy.keyguard.KeyguardStateMonitor.StateCallback
            public void onTrustedChanged() {
                PhoneWindowManager.this.mWindowManagerFuncs.notifyKeyguardTrustedChanged();
            }

            @Override // com.android.server.policy.keyguard.KeyguardStateMonitor.StateCallback
            public void onShowingChanged() {
                PhoneWindowManager.this.mWindowManagerFuncs.onKeyguardShowingAndNotOccludedChanged();
            }
        });
        this.mScreenshotHelper = new ScreenshotHelper(this.mContext);
        xpPhoneWindowManager.get(this.mContext).init();
    }

    private void readConfigurationDependentBehaviors() {
        Resources res = this.mContext.getResources();
        this.mLongPressOnHomeBehavior = res.getInteger(17694801);
        if (this.mLongPressOnHomeBehavior < 0 || this.mLongPressOnHomeBehavior > 2) {
            this.mLongPressOnHomeBehavior = 0;
        }
        this.mDoubleTapOnHomeBehavior = res.getInteger(17694779);
        if (this.mDoubleTapOnHomeBehavior < 0 || this.mDoubleTapOnHomeBehavior > 1) {
            this.mDoubleTapOnHomeBehavior = 0;
        }
        this.mShortPressOnWindowBehavior = 0;
        if (this.mContext.getPackageManager().hasSystemFeature("android.software.picture_in_picture")) {
            this.mShortPressOnWindowBehavior = 1;
        }
        this.mNavBarOpacityMode = res.getInteger(17694824);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setInitialDisplaySize(Display display, int width, int height, int density) {
        int shortSize;
        int longSize;
        if (this.mContext == null || display.getDisplayId() != 0) {
            return;
        }
        this.mDisplay = display;
        Resources res = this.mContext.getResources();
        boolean z = false;
        if (width > height) {
            shortSize = height;
            longSize = width;
            this.mLandscapeRotation = 0;
            this.mSeascapeRotation = 2;
            if (res.getBoolean(17957016)) {
                this.mPortraitRotation = 1;
                this.mUpsideDownRotation = 3;
            } else {
                this.mPortraitRotation = 3;
                this.mUpsideDownRotation = 1;
            }
        } else {
            shortSize = width;
            longSize = height;
            this.mPortraitRotation = 0;
            this.mUpsideDownRotation = 2;
            if (res.getBoolean(17957016)) {
                this.mLandscapeRotation = 3;
                this.mSeascapeRotation = 1;
            } else {
                this.mLandscapeRotation = 1;
                this.mSeascapeRotation = 3;
            }
        }
        int shortSizeDp = (shortSize * 160) / density;
        int longSizeDp = (longSize * 160) / density;
        this.mNavigationBarCanMove = width != height && shortSizeDp < 600;
        this.mHasNavigationBar = res.getBoolean(17957026);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            this.mHasNavigationBar = false;
        } else if ("0".equals(navBarOverride)) {
            this.mHasNavigationBar = true;
        }
        if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
            this.mDemoHdmiRotation = this.mPortraitRotation;
        } else {
            this.mDemoHdmiRotation = this.mLandscapeRotation;
        }
        this.mDemoHdmiRotationLock = SystemProperties.getBoolean("persist.demo.hdmirotationlock", false);
        if ("portrait".equals(SystemProperties.get("persist.demo.remoterotation"))) {
            this.mDemoRotation = this.mPortraitRotation;
        } else {
            this.mDemoRotation = this.mLandscapeRotation;
        }
        this.mDemoRotationLock = SystemProperties.getBoolean("persist.demo.rotationlock", false);
        boolean isCar = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
        boolean isTv = this.mContext.getPackageManager().hasSystemFeature("android.software.leanback");
        if (((longSizeDp >= 960 && shortSizeDp >= 720) || isCar || isTv) && res.getBoolean(17956979) && !"true".equals(SystemProperties.get("config.override_forced_orient"))) {
            z = true;
        }
        this.mForceDefaultOrientation = z;
    }

    private boolean canHideNavigationBar() {
        return this.mHasNavigationBar;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isDefaultOrientationForced() {
        return this.mForceDefaultOrientation;
    }

    public void updateSettings() {
        int pointerLocation;
        ContentResolver resolver = this.mContext.getContentResolver();
        boolean updateRotation = false;
        synchronized (this.mLock) {
            this.mEndcallBehavior = Settings.System.getIntForUser(resolver, "end_button_behavior", 2, -2);
            this.mIncallPowerBehavior = Settings.Secure.getIntForUser(resolver, "incall_power_button_behavior", 1, -2);
            boolean z = false;
            this.mIncallBackBehavior = Settings.Secure.getIntForUser(resolver, "incall_back_button_behavior", 0, -2);
            this.mSystemNavigationKeysEnabled = Settings.Secure.getIntForUser(resolver, "system_navigation_keys_enabled", 0, -2) == 1;
            this.mRingerToggleChord = Settings.Secure.getIntForUser(resolver, "volume_hush_gesture", 0, -2);
            if (!this.mContext.getResources().getBoolean(17957076)) {
                this.mRingerToggleChord = 0;
            }
            int showRotationSuggestions = Settings.Secure.getIntForUser(resolver, "show_rotation_suggestions", 1, -2);
            if (this.mShowRotationSuggestions != showRotationSuggestions) {
                this.mShowRotationSuggestions = showRotationSuggestions;
                updateOrientationListenerLp();
            }
            boolean wakeGestureEnabledSetting = Settings.Secure.getIntForUser(resolver, "wake_gesture_enabled", 0, -2) != 0;
            if (this.mWakeGestureEnabledSetting != wakeGestureEnabledSetting) {
                this.mWakeGestureEnabledSetting = wakeGestureEnabledSetting;
                updateWakeGestureListenerLp();
            }
            int userRotation = Settings.System.getIntForUser(resolver, "user_rotation", 0, -2);
            if (this.mUserRotation != userRotation) {
                this.mUserRotation = userRotation;
                updateRotation = true;
            }
            int userRotationMode = Settings.System.getIntForUser(resolver, "accelerometer_rotation", 0, -2) != 0 ? 0 : 1;
            if (this.mUserRotationMode != userRotationMode) {
                this.mUserRotationMode = userRotationMode;
                updateRotation = true;
                updateOrientationListenerLp();
            }
            if (this.mSystemReady && this.mPointerLocationMode != (pointerLocation = Settings.System.getIntForUser(resolver, "pointer_location", 0, -2))) {
                this.mPointerLocationMode = pointerLocation;
                this.mHandler.sendEmptyMessage(pointerLocation != 0 ? 1 : 2);
            }
            this.mLockScreenTimeout = Settings.System.getIntForUser(resolver, "screen_off_timeout", 0, -2);
            String imId = Settings.Secure.getStringForUser(resolver, "default_input_method", -2);
            if (imId != null && imId.length() > 0) {
                z = true;
            }
            boolean hasSoftInput = z;
            if (this.mHasSoftInput != hasSoftInput) {
                this.mHasSoftInput = hasSoftInput;
                updateRotation = true;
            }
            if (this.mImmersiveModeConfirmation != null) {
                this.mImmersiveModeConfirmation.loadSetting(this.mCurrentUserId);
            }
        }
        synchronized (this.mWindowManagerFuncs.getWindowManagerLock()) {
            PolicyControl.reloadFromSetting(this.mContext);
        }
        if (updateRotation) {
            updateRotation(true);
        }
    }

    private void updateWakeGestureListenerLp() {
        if (shouldEnableWakeGestureLp()) {
            this.mWakeGestureListener.requestWakeUpTrigger();
        } else {
            this.mWakeGestureListener.cancelWakeUpTrigger();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldEnableWakeGestureLp() {
        return this.mWakeGestureEnabledSetting && !this.mAwake && !(this.mLidControlsSleep && this.mLidState == 0) && this.mWakeGestureListener.isSupported();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enablePointerLocation() {
        if (this.mPointerLocationView == null) {
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
            lp.setTitle("PointerLocation");
            WindowManager wm = (WindowManager) this.mContext.getSystemService("window");
            lp.inputFeatures |= 2;
            wm.addView(this.mPointerLocationView, lp);
            this.mWindowManagerFuncs.registerPointerEventListener(this.mPointerLocationView);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disablePointerLocation() {
        if (this.mPointerLocationView != null) {
            this.mWindowManagerFuncs.unregisterPointerEventListener(this.mPointerLocationView);
            WindowManager wm = (WindowManager) this.mContext.getSystemService("window");
            wm.removeView(this.mPointerLocationView);
            this.mPointerLocationView = null;
        }
    }

    private int readRotation(int resID) {
        try {
            int rotation = this.mContext.getResources().getInteger(resID);
            if (rotation != 0) {
                if (rotation != 90) {
                    if (rotation != 180) {
                        if (rotation == 270) {
                            return 3;
                        }
                        return -1;
                    }
                    return 2;
                }
                return 1;
            }
            return 0;
        } catch (Resources.NotFoundException e) {
            return -1;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int checkAddPermission(WindowManager.LayoutParams attrs, int[] outAppOp) {
        ApplicationInfo appInfo;
        int type = attrs.type;
        boolean isRoundedCornerOverlay = (attrs.privateFlags & 1048576) != 0;
        if (isRoundedCornerOverlay && this.mContext.checkCallingOrSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") != 0) {
            return -8;
        }
        outAppOp[0] = -1;
        if ((type < 1 || type > 99) && ((type < 1000 || type > 1999) && (type < 2000 || type > 2999))) {
            return -10;
        }
        if (type < 2000 || type > 2999) {
            return 0;
        }
        if (!WindowManager.LayoutParams.isSystemAlertWindowType(type)) {
            if (type == 2005) {
                outAppOp[0] = 45;
                return 0;
            }
            if (type != 2011 && type != 2013 && type != 2023 && type != 2035 && type != 2037) {
                switch (type) {
                    case 2030:
                    case 2031:
                    case 2032:
                        break;
                    default:
                        return this.mContext.checkCallingOrSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") == 0 ? 0 : -8;
                }
            }
            return 0;
        }
        outAppOp[0] = 24;
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) == 1000) {
            return 0;
        }
        try {
            appInfo = this.mContext.getPackageManager().getApplicationInfoAsUser(attrs.packageName, 0, UserHandle.getUserId(callingUid));
        } catch (PackageManager.NameNotFoundException e) {
            appInfo = null;
        }
        if (appInfo == null || (type != 2038 && appInfo.targetSdkVersion >= 26)) {
            return this.mContext.checkCallingOrSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") == 0 ? 0 : -8;
        }
        int mode = this.mAppOpsManager.noteOpNoThrow(outAppOp[0], callingUid, attrs.packageName);
        switch (mode) {
            case 0:
            case 1:
                return 0;
            case 2:
                return appInfo.targetSdkVersion < 23 ? 0 : -8;
            default:
                return this.mContext.checkCallingOrSelfPermission("android.permission.SYSTEM_ALERT_WINDOW") == 0 ? 0 : -8;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean checkShowToOwnerOnly(WindowManager.LayoutParams attrs) {
        int i = attrs.type;
        if (i != 3 && i != 2014 && i != 2024 && i != 2030 && i != 2034 && i != 2037) {
            switch (i) {
                case 2000:
                case 2001:
                case 2002:
                    break;
                default:
                    switch (i) {
                        case 2007:
                        case 2008:
                        case 2009:
                            break;
                        default:
                            switch (i) {
                                case 2017:
                                case 2018:
                                case 2019:
                                case 2020:
                                case 2021:
                                case 2022:
                                    break;
                                default:
                                    switch (i) {
                                        case 2026:
                                        case 2027:
                                            break;
                                        default:
                                            if ((attrs.privateFlags & 16) == 0) {
                                                return true;
                                            }
                                            break;
                                    }
                            }
                    }
            }
        }
        return this.mContext.checkCallingOrSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") != 0;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void adjustWindowParamsLw(WindowManagerPolicy.WindowState win, WindowManager.LayoutParams attrs, boolean hasStatusBarServicePermission) {
        boolean isScreenDecor = (attrs.privateFlags & DumpState.DUMP_CHANGES) != 0;
        if (this.mScreenDecorWindows.contains(win)) {
            if (!isScreenDecor) {
                this.mScreenDecorWindows.remove(win);
            }
        } else if (isScreenDecor && hasStatusBarServicePermission) {
            this.mScreenDecorWindows.add(win);
        }
        int i = attrs.type;
        if (i != 2000) {
            if (i != 2013) {
                if (i != 2015) {
                    if (i != 2023) {
                        if (i == 2036) {
                            attrs.flags |= 8;
                        } else {
                            switch (i) {
                                case 2005:
                                    if (attrs.hideTimeoutMilliseconds < 0 || attrs.hideTimeoutMilliseconds > 6000) {
                                        attrs.hideTimeoutMilliseconds = 6000L;
                                    }
                                    attrs.windowAnimations = 16973828;
                                    break;
                            }
                        }
                    }
                }
                attrs.flags |= 24;
                attrs.flags &= -262145;
            }
            attrs.layoutInDisplayCutoutMode = 1;
        } else if (this.mKeyguardOccluded) {
            attrs.flags &= -1048577;
            attrs.privateFlags &= -1025;
        }
        if (attrs.type != 2000) {
            attrs.privateFlags &= -1025;
        }
    }

    private int getImpliedSysUiFlagsForLayout(WindowManager.LayoutParams attrs) {
        int impliedFlags = 0;
        if ((attrs.flags & Integer.MIN_VALUE) != 0) {
            impliedFlags = 0 | 512;
        }
        boolean forceWindowDrawsStatusBarBackground = (attrs.privateFlags & DumpState.DUMP_INTENT_FILTER_VERIFIERS) != 0;
        if ((Integer.MIN_VALUE & attrs.flags) != 0 || (forceWindowDrawsStatusBarBackground && attrs.height == -1 && attrs.width == -1)) {
            return impliedFlags | 1024;
        }
        return impliedFlags;
    }

    void readLidState() {
        this.mLidState = this.mWindowManagerFuncs.getLidState();
    }

    private void readCameraLensCoverState() {
        this.mCameraLensCoverState = this.mWindowManagerFuncs.getCameraLensCoverState();
    }

    private boolean isHidden(int accessibilityMode) {
        switch (accessibilityMode) {
            case 1:
                return this.mLidState == 0;
            case 2:
                return this.mLidState == 1;
            default:
                return false;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void adjustConfigurationLw(Configuration config, int keyboardPresence, int navigationPresence) {
        this.mHaveBuiltInKeyboard = (keyboardPresence & 1) != 0;
        readConfigurationDependentBehaviors();
        readLidState();
        if (config.keyboard == 1 || (keyboardPresence == 1 && isHidden(this.mLidKeyboardAccessibility))) {
            config.hardKeyboardHidden = 2;
            if (!this.mHasSoftInput) {
                config.keyboardHidden = 2;
            }
        }
        if (config.navigation == 1 || (navigationPresence == 1 && isHidden(this.mLidNavigationAccessibility))) {
            config.navigationHidden = 2;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onOverlayChangedLw() {
        onConfigurationChanged();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onConfigurationChanged() {
        Context uiContext = getSystemUiContext();
        Resources res = uiContext.getResources();
        int[] iArr = this.mStatusBarHeightForRotation;
        int i = this.mPortraitRotation;
        int[] iArr2 = this.mStatusBarHeightForRotation;
        int i2 = this.mUpsideDownRotation;
        int dimensionPixelSize = res.getDimensionPixelSize(17105337);
        iArr2[i2] = dimensionPixelSize;
        iArr[i] = dimensionPixelSize;
        int[] iArr3 = this.mStatusBarHeightForRotation;
        int i3 = this.mLandscapeRotation;
        int[] iArr4 = this.mStatusBarHeightForRotation;
        int i4 = this.mSeascapeRotation;
        int dimensionPixelSize2 = res.getDimensionPixelSize(17105336);
        iArr4[i4] = dimensionPixelSize2;
        iArr3[i3] = dimensionPixelSize2;
        int[] iArr5 = this.mNavigationBarHeightForRotationDefault;
        int i5 = this.mPortraitRotation;
        int[] iArr6 = this.mNavigationBarHeightForRotationDefault;
        int i6 = this.mUpsideDownRotation;
        int dimensionPixelSize3 = res.getDimensionPixelSize(17105200);
        iArr6[i6] = dimensionPixelSize3;
        iArr5[i5] = dimensionPixelSize3;
        int[] iArr7 = this.mNavigationBarHeightForRotationDefault;
        int i7 = this.mLandscapeRotation;
        int[] iArr8 = this.mNavigationBarHeightForRotationDefault;
        int i8 = this.mSeascapeRotation;
        int dimensionPixelSize4 = res.getDimensionPixelSize(17105202);
        iArr8[i8] = dimensionPixelSize4;
        iArr7[i7] = dimensionPixelSize4;
        int[] iArr9 = this.mNavigationBarWidthForRotationDefault;
        int i9 = this.mPortraitRotation;
        int[] iArr10 = this.mNavigationBarWidthForRotationDefault;
        int i10 = this.mUpsideDownRotation;
        int[] iArr11 = this.mNavigationBarWidthForRotationDefault;
        int i11 = this.mLandscapeRotation;
        int[] iArr12 = this.mNavigationBarWidthForRotationDefault;
        int i12 = this.mSeascapeRotation;
        int dimensionPixelSize5 = res.getDimensionPixelSize(17105205);
        iArr12[i12] = dimensionPixelSize5;
        iArr11[i11] = dimensionPixelSize5;
        iArr10[i10] = dimensionPixelSize5;
        iArr9[i9] = dimensionPixelSize5;
        int[] iArr13 = this.mStatusBarHeightForRotation;
        int i13 = this.mPortraitRotation;
        int[] iArr14 = this.mStatusBarHeightForRotation;
        int i14 = this.mUpsideDownRotation;
        int[] iArr15 = this.mStatusBarHeightForRotation;
        int i15 = this.mLandscapeRotation;
        int[] iArr16 = this.mStatusBarHeightForRotation;
        int i16 = this.mSeascapeRotation;
        int statusBarHeight = xpPhoneWindowManager.get(this.mContext).getStatusBarHeight();
        iArr16[i16] = statusBarHeight;
        iArr15[i15] = statusBarHeight;
        iArr14[i14] = statusBarHeight;
        iArr13[i13] = statusBarHeight;
        int[] iArr17 = this.mNavigationBarHeightForRotationDefault;
        int i17 = this.mPortraitRotation;
        int[] iArr18 = this.mNavigationBarHeightForRotationDefault;
        int i18 = this.mUpsideDownRotation;
        int[] iArr19 = this.mNavigationBarHeightForRotationDefault;
        int i19 = this.mLandscapeRotation;
        int[] iArr20 = this.mNavigationBarHeightForRotationDefault;
        int i20 = this.mSeascapeRotation;
        int navigationBarHeight = xpPhoneWindowManager.get(this.mContext).getNavigationBarHeight();
        iArr20[i20] = navigationBarHeight;
        iArr19[i19] = navigationBarHeight;
        iArr18[i18] = navigationBarHeight;
        iArr17[i17] = navigationBarHeight;
        int[] iArr21 = this.mNavigationBarWidthForRotationDefault;
        int i21 = this.mPortraitRotation;
        int[] iArr22 = this.mNavigationBarWidthForRotationDefault;
        int i22 = this.mUpsideDownRotation;
        int[] iArr23 = this.mNavigationBarWidthForRotationDefault;
        int i23 = this.mLandscapeRotation;
        int[] iArr24 = this.mNavigationBarWidthForRotationDefault;
        int i24 = this.mSeascapeRotation;
        int navigationBarWidth = xpPhoneWindowManager.get(this.mContext).getNavigationBarWidth();
        iArr24[i24] = navigationBarWidth;
        iArr23[i23] = navigationBarWidth;
        iArr22[i22] = navigationBarWidth;
        iArr21[i21] = navigationBarWidth;
    }

    @VisibleForTesting
    Context getSystemUiContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getMaxWallpaperLayer() {
        return getWindowLayerFromTypeLw(2000);
    }

    private int getNavigationBarWidth(int rotation, int uiMode) {
        return this.mNavigationBarWidthForRotationDefault[rotation];
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode, int displayId, DisplayCutout displayCutout) {
        int width = fullWidth;
        if (displayId == 0 && this.mHasNavigationBar && this.mNavigationBarCanMove && fullWidth > fullHeight) {
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

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode, int displayId, DisplayCutout displayCutout) {
        int height = fullHeight;
        if (displayId == 0 && this.mHasNavigationBar) {
            int navigationBarHeight = getNavigationBarHeight(rotation, uiMode);
            height = xpPhoneWindowManager.get(this.mContext).getDisplayHeight(fullWidth, fullHeight, navigationBarHeight, this.mNavigationBarCanMove);
        }
        if (displayCutout != null) {
            return height - (displayCutout.getSafeInsetTop() + displayCutout.getSafeInsetBottom());
        }
        return height;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode, int displayId, DisplayCutout displayCutout) {
        return getNonDecorDisplayWidth(fullWidth, fullHeight, rotation, uiMode, displayId, displayCutout);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode, int displayId, DisplayCutout displayCutout) {
        if (displayId == 0) {
            int statusBarHeight = this.mStatusBarHeightForRotation[rotation];
            if (displayCutout != null) {
                statusBarHeight = Math.max(0, statusBarHeight - displayCutout.getSafeInsetTop());
            }
            return getNonDecorDisplayHeight(fullWidth, fullHeight, rotation, uiMode, displayId, displayCutout) - statusBarHeight;
        }
        return fullHeight;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return attrs.type == 2000;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean canBeHiddenByKeyguardLw(WindowManagerPolicy.WindowState win) {
        int i = win.getAttrs().type;
        return (i == 2000 || i == 2013 || i == 2019 || i == 2023 || getWindowLayerLw(win) >= getWindowLayerFromTypeLw(2000)) ? false : true;
    }

    private boolean shouldBeHiddenByKeyguard(WindowManagerPolicy.WindowState win, WindowManagerPolicy.WindowState imeTarget) {
        if (win.getAppToken() != null) {
            return false;
        }
        WindowManager.LayoutParams attrs = win.getAttrs();
        boolean showImeOverKeyguard = (imeTarget == null || !imeTarget.isVisibleLw() || ((imeTarget.getAttrs().flags & DumpState.DUMP_FROZEN) == 0 && canBeHiddenByKeyguardLw(imeTarget))) ? false : true;
        boolean allowWhenLocked = (win.isInputMethodWindow() || imeTarget == this) && showImeOverKeyguard;
        if (isKeyguardLocked() && isKeyguardOccluded()) {
            allowWhenLocked |= ((524288 & attrs.flags) == 0 && (attrs.privateFlags & 256) == 0) ? false : true;
        }
        boolean keyguardLocked = isKeyguardLocked();
        boolean hideDockDivider = attrs.type == 2034 && !this.mWindowManagerInternal.isStackVisible(3);
        boolean hideIme = win.isInputMethodWindow() && (this.mAodShowing || !this.mWindowManagerDrawComplete);
        return (keyguardLocked && !allowWhenLocked && win.getDisplayId() == 0) || hideDockDivider || hideIme;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public WindowManagerPolicy.StartingSurface addSplashScreen(IBinder appToken, String packageName, int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        View view = null;
        if (packageName == null) {
            return null;
        }
        WindowManager wm = null;
        try {
            try {
                try {
                    Context displayContext = getDisplayContext(this.mContext, displayId);
                    if (displayContext == null) {
                        if (0 != 0 && view.getParent() == null) {
                            Log.w(TAG, "view not successfully added to wm, removing view");
                            wm.removeViewImmediate(null);
                        }
                        return null;
                    }
                    Context context = displayContext;
                    if (theme != context.getThemeResId() || labelRes != 0) {
                        try {
                            context = context.createPackageContext(packageName, 4);
                            context.setTheme(theme);
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                    }
                    if (overrideConfig != null && !overrideConfig.equals(Configuration.EMPTY)) {
                        Context overrideContext = context.createConfigurationContext(overrideConfig);
                        overrideContext.setTheme(theme);
                        TypedArray typedArray = overrideContext.obtainStyledAttributes(R.styleable.Window);
                        int resId = typedArray.getResourceId(1, 0);
                        if (resId != 0 && overrideContext.getDrawable(resId) != null) {
                            context = overrideContext;
                        }
                        typedArray.recycle();
                    }
                    PhoneWindow win = new PhoneWindow(context);
                    win.setIsStartingWindow(true);
                    CharSequence label = context.getResources().getText(labelRes, null);
                    if (label != null) {
                        win.setTitle(label, true);
                    } else {
                        try {
                            win.setTitle(nonLocalizedLabel, false);
                        } catch (WindowManager.BadTokenException e2) {
                            e = e2;
                            Log.w(TAG, appToken + " already running, starting window not displayed. " + e.getMessage());
                            return 0 != 0 ? null : null;
                        } catch (RuntimeException e3) {
                            e = e3;
                            Log.w(TAG, appToken + " failed creating starting window", e);
                            return 0 != 0 ? null : null;
                        } catch (Throwable th) {
                            th = th;
                            if (0 != 0) {
                                Log.w(TAG, "view not successfully added to wm, removing view");
                                wm.removeViewImmediate(null);
                            }
                            throw th;
                        }
                    }
                    win.setType(3);
                    try {
                        synchronized (this.mWindowManagerFuncs.getWindowManagerLock()) {
                            try {
                                int windowFlags2 = this.mKeyguardOccluded ? windowFlags | DumpState.DUMP_FROZEN : windowFlags;
                                try {
                                    try {
                                        win.setFlags(windowFlags2 | 16 | 8 | DumpState.DUMP_INTENT_FILTER_VERIFIERS, windowFlags2 | 16 | 8 | DumpState.DUMP_INTENT_FILTER_VERIFIERS);
                                        win.setDefaultIcon(icon);
                                        win.setDefaultLogo(logo);
                                        win.setLayout(-1, -1);
                                        WindowManager.LayoutParams params = win.getAttributes();
                                        params.token = appToken;
                                        params.packageName = packageName;
                                        params.windowAnimations = 0;
                                        params.privateFlags |= 1;
                                        params.privateFlags |= 16;
                                        if (!compatInfo.supportsScreen()) {
                                            params.privateFlags |= 128;
                                        }
                                        params.copyFrom(xpPhoneWindowManager.getStartingWindowLayoutParams(params, null));
                                        win.setAttributes(params);
                                        params.setTitle("Splash Screen " + packageName);
                                        addSplashscreenContent(win, context);
                                        WindowManager wm2 = (WindowManager) context.getSystemService("window");
                                        View view2 = win.getDecorView();
                                        wm2.addView(view2, params);
                                        SplashScreenSurface splashScreenSurface = view2.getParent() != null ? new SplashScreenSurface(view2, appToken) : null;
                                        if (view2 != null && view2.getParent() == null) {
                                            Log.w(TAG, "view not successfully added to wm, removing view");
                                            wm2.removeViewImmediate(view2);
                                        }
                                        return splashScreenSurface;
                                    } catch (WindowManager.BadTokenException e4) {
                                        e = e4;
                                        Log.w(TAG, appToken + " already running, starting window not displayed. " + e.getMessage());
                                        if (0 != 0 || view.getParent() != null) {
                                        }
                                        Log.w(TAG, "view not successfully added to wm, removing view");
                                        wm.removeViewImmediate(null);
                                        return null;
                                    } catch (RuntimeException e5) {
                                        e = e5;
                                        Log.w(TAG, appToken + " failed creating starting window", e);
                                        if (0 != 0 || view.getParent() != null) {
                                        }
                                        Log.w(TAG, "view not successfully added to wm, removing view");
                                        wm.removeViewImmediate(null);
                                        return null;
                                    } catch (Throwable th2) {
                                        th = th2;
                                        if (0 != 0 && view.getParent() == null) {
                                            Log.w(TAG, "view not successfully added to wm, removing view");
                                            wm.removeViewImmediate(null);
                                        }
                                        throw th;
                                    }
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
                            }
                        }
                    } catch (WindowManager.BadTokenException e6) {
                        e = e6;
                    } catch (RuntimeException e7) {
                        e = e7;
                    }
                } catch (WindowManager.BadTokenException e8) {
                    e = e8;
                } catch (RuntimeException e9) {
                    e = e9;
                } catch (Throwable th6) {
                    th = th6;
                }
            } catch (Throwable th7) {
                th = th7;
            }
        } catch (WindowManager.BadTokenException e10) {
            e = e10;
        } catch (RuntimeException e11) {
            e = e11;
        } catch (Throwable th8) {
            th = th8;
        }
    }

    private void addSplashscreenContent(PhoneWindow win, Context ctx) {
        Drawable drawable;
        TypedArray a = ctx.obtainStyledAttributes(R.styleable.Window);
        int resId = a.getResourceId(48, 0);
        a.recycle();
        if (resId == 0 || (drawable = ctx.getDrawable(resId)) == null) {
            return;
        }
        View v = new View(ctx);
        v.setBackground(drawable);
        win.setContentView(v);
    }

    private Context getDisplayContext(Context context, int displayId) {
        if (displayId == 0) {
            return context;
        }
        DisplayManager dm = (DisplayManager) context.getSystemService("display");
        Display targetDisplay = dm.getDisplay(displayId);
        if (targetDisplay == null) {
            return null;
        }
        return context.createDisplayContext(targetDisplay);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int prepareAddWindowLw(WindowManagerPolicy.WindowState win, WindowManager.LayoutParams attrs) {
        if ((attrs.privateFlags & DumpState.DUMP_CHANGES) != 0) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", "PhoneWindowManager");
            this.mScreenDecorWindows.add(win);
        }
        int i = attrs.type;
        if (i == 2000) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", "PhoneWindowManager");
            if (this.mStatusBar == null || !this.mStatusBar.isAlive()) {
                this.mStatusBar = win;
                this.mStatusBarController.setWindow(win);
                setKeyguardOccludedLw(this.mKeyguardOccluded, true);
                return 0;
            }
            return -7;
        }
        if (i != 2014 && i != 2017) {
            if (i == 2019) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", "PhoneWindowManager");
                if (this.mNavigationBar == null || !this.mNavigationBar.isAlive()) {
                    this.mNavigationBar = win;
                    this.mNavigationBarController.setWindow(win);
                    this.mNavigationBarController.setOnBarVisibilityChangedListener(this.mNavBarVisibilityListener, true);
                    return 0;
                }
                return -7;
            } else if (i != 2024 && i != 2033) {
                return 0;
            }
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", "PhoneWindowManager");
        return 0;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void removeWindowLw(WindowManagerPolicy.WindowState win) {
        if (this.mStatusBar == win) {
            this.mStatusBar = null;
            this.mStatusBarController.setWindow(null);
        } else if (this.mNavigationBar == win) {
            this.mNavigationBar = null;
            this.mNavigationBarController.setWindow(null);
        }
        this.mScreenDecorWindows.remove(win);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int selectAnimationLw(WindowManagerPolicy.WindowState win, int transit) {
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
            if (this.mNavigationBarPosition == 4) {
                if (transit == 2 || transit == 4) {
                    if (isKeyguardShowingAndNotOccluded()) {
                        return 17432616;
                    }
                    return 17432615;
                } else if (transit == 1 || transit == 3) {
                    return 17432614;
                }
            } else if (this.mNavigationBarPosition == 2) {
                if (transit == 2 || transit == 4) {
                    return 17432620;
                }
                if (transit == 1 || transit == 3) {
                    return 17432619;
                }
            } else if (this.mNavigationBarPosition == 1) {
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
                return 17432595;
            }
        } else if (win.getAttrs().type == 2023 && this.mDreamingLockscreen && transit == 1) {
            return -1;
        }
        return 0;
    }

    private int selectDockedDividerAnimationLw(WindowManagerPolicy.WindowState win, int transit) {
        int insets = this.mWindowManagerFuncs.getDockedDividerInsetsLw();
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

    @Override // com.android.server.policy.WindowManagerPolicy
    public void selectRotationAnimationLw(int[] anim) {
        boolean forceJumpcut = (this.mScreenOnFully && okToAnimate()) ? false : true;
        if (forceJumpcut) {
            anim[0] = 17432689;
            anim[1] = 17432688;
        } else if (this.mTopFullscreenOpaqueWindowState != null) {
            int animationHint = this.mTopFullscreenOpaqueWindowState.getRotationAnimationHint();
            if (animationHint < 0 && this.mTopIsFullscreen) {
                animationHint = this.mTopFullscreenOpaqueWindowState.getAttrs().rotationAnimation;
            }
            switch (animationHint) {
                case 1:
                case 3:
                    anim[0] = 17432690;
                    anim[1] = 17432688;
                    return;
                case 2:
                    anim[0] = 17432689;
                    anim[1] = 17432688;
                    return;
                default:
                    anim[1] = 0;
                    anim[0] = 0;
                    return;
            }
        } else {
            anim[1] = 0;
            anim[0] = 0;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean validateRotationAnimationLw(int exitAnimId, int enterAnimId, boolean forceDefault) {
        switch (exitAnimId) {
            case 17432689:
            case 17432690:
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

    @Override // com.android.server.policy.WindowManagerPolicy
    public Animation createHiddenByKeyguardExit(boolean onWallpaper, boolean goingToNotificationShade) {
        int i;
        if (goingToNotificationShade) {
            return AnimationUtils.loadAnimation(this.mContext, 17432665);
        }
        Context context = this.mContext;
        if (onWallpaper) {
            i = 17432666;
        } else {
            i = 17432664;
        }
        AnimationSet set = (AnimationSet) AnimationUtils.loadAnimation(context, i);
        List<Animation> animations = set.getAnimations();
        for (int i2 = animations.size() - 1; i2 >= 0; i2--) {
            animations.get(i2).setInterpolator(this.mLogDecelerateInterpolator);
        }
        return set;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public Animation createKeyguardWallpaperExit(boolean goingToNotificationShade) {
        if (goingToNotificationShade) {
            return null;
        }
        return AnimationUtils.loadAnimation(this.mContext, 17432669);
    }

    private static void awakenDreams() {
        IDreamManager dreamManager = getDreamManager();
        if (dreamManager != null) {
            try {
                dreamManager.awaken();
            } catch (RemoteException e) {
            }
        }
    }

    static IDreamManager getDreamManager() {
        return IDreamManager.Stub.asInterface(ServiceManager.checkService("dreams"));
    }

    TelecomManager getTelecommService() {
        return (TelecomManager) this.mContext.getSystemService("telecom");
    }

    static IAudioService getAudioService() {
        IAudioService audioService = IAudioService.Stub.asInterface(ServiceManager.checkService("audio"));
        if (audioService == null) {
            Log.w(TAG, "Unable to find IAudioService interface.");
        }
        return audioService;
    }

    boolean keyguardOn() {
        return isKeyguardShowingAndNotOccluded() || inKeyguardRestrictedKeyInputMode();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public long interceptKeyBeforeDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        long j;
        InputDevice d;
        IStatusBarService service;
        String category;
        Intent shortcutIntent;
        boolean keyguardOn = keyguardOn();
        int keyCode = event.getKeyCode();
        int repeatCount = event.getRepeatCount();
        int metaState = event.getMetaState();
        int flags = event.getFlags();
        boolean down = event.getAction() == 0;
        boolean canceled = event.isCanceled();
        if (xpInputManagerService.XP_IMS_ENABLE) {
            long ret = xpInputManagerService.get(this.mContext).interceptKeyBeforeDispatching(win, event, policyFlags);
            if (ret == -1) {
                return -1L;
            }
        } else if (injectXPGlobalKeyEventBeforeDispatching(win, event, policyFlags)) {
            return -1L;
        }
        if (this.mScreenshotChordEnabled && (flags & 1024) == 0) {
            if (this.mScreenshotChordVolumeDownKeyTriggered && !this.mScreenshotChordPowerKeyTriggered) {
                long now = SystemClock.uptimeMillis();
                long timeoutTime = this.mScreenshotChordVolumeDownKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now < timeoutTime) {
                    return timeoutTime - now;
                }
            }
            if (keyCode == 25 && this.mScreenshotChordVolumeDownKeyConsumed) {
                if (down) {
                    return -1L;
                }
                this.mScreenshotChordVolumeDownKeyConsumed = false;
                return -1L;
            }
        }
        if (this.mAccessibilityShortcutController.isAccessibilityShortcutAvailable(false) && (flags & 1024) == 0) {
            if (this.mScreenshotChordVolumeDownKeyTriggered ^ this.mA11yShortcutChordVolumeUpKeyTriggered) {
                long now2 = SystemClock.uptimeMillis();
                long timeoutTime2 = (this.mScreenshotChordVolumeDownKeyTriggered ? this.mScreenshotChordVolumeDownKeyTime : this.mA11yShortcutChordVolumeUpKeyTime) + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now2 < timeoutTime2) {
                    return timeoutTime2 - now2;
                }
            }
            if (keyCode == 25 && this.mScreenshotChordVolumeDownKeyConsumed) {
                if (down) {
                    return -1L;
                }
                this.mScreenshotChordVolumeDownKeyConsumed = false;
                return -1L;
            } else if (keyCode == 24 && this.mA11yShortcutChordVolumeUpKeyConsumed) {
                if (down) {
                    return -1L;
                }
                this.mA11yShortcutChordVolumeUpKeyConsumed = false;
                return -1L;
            }
        }
        if (this.mRingerToggleChord != 0 && (flags & 1024) == 0) {
            if (this.mA11yShortcutChordVolumeUpKeyTriggered && !this.mScreenshotChordPowerKeyTriggered) {
                long now3 = SystemClock.uptimeMillis();
                long timeoutTime3 = this.mA11yShortcutChordVolumeUpKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now3 < timeoutTime3) {
                    return timeoutTime3 - now3;
                }
            }
            if (keyCode == 24 && this.mA11yShortcutChordVolumeUpKeyConsumed) {
                if (down) {
                    return -1L;
                }
                this.mA11yShortcutChordVolumeUpKeyConsumed = false;
                return -1L;
            }
        }
        if (this.mPendingMetaAction && !KeyEvent.isMetaKey(keyCode)) {
            this.mPendingMetaAction = false;
        }
        if (this.mPendingCapsLockToggle && !KeyEvent.isMetaKey(keyCode) && !KeyEvent.isAltKey(keyCode)) {
            this.mPendingCapsLockToggle = false;
        }
        if (keyCode == 3) {
            if (!down) {
                cancelPreloadRecentApps();
                this.mHomePressed = false;
                if (this.mHomeConsumed) {
                    this.mHomeConsumed = false;
                    return -1L;
                } else if (canceled) {
                    Log.i(TAG, "Ignoring HOME; event canceled.");
                    return -1L;
                } else if (this.mDoubleTapOnHomeBehavior == 0) {
                    handleShortPressOnHome();
                    return -1L;
                } else {
                    this.mHandler.removeCallbacks(this.mHomeDoubleTapTimeoutRunnable);
                    this.mHomeDoubleTapPending = true;
                    this.mHandler.postDelayed(this.mHomeDoubleTapTimeoutRunnable, ViewConfiguration.getDoubleTapTimeout());
                    return -1L;
                }
            }
            WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
            if (attrs != null) {
                int type = attrs.type;
                if (type == 2009 || (attrs.privateFlags & 1024) != 0 || type == 2008) {
                    return 0L;
                }
                int typeCount = WINDOW_TYPES_WHERE_HOME_DOESNT_WORK.length;
                for (int i = 0; i < typeCount; i++) {
                    if (type == WINDOW_TYPES_WHERE_HOME_DOESNT_WORK[i]) {
                        return -1L;
                    }
                }
            }
            if (repeatCount != 0) {
                if ((event.getFlags() & 128) == 0 || keyguardOn) {
                    return -1L;
                }
                handleLongPressOnHome(event.getDeviceId());
                return -1L;
            }
            this.mHomePressed = true;
            if (this.mHomeDoubleTapPending) {
                this.mHomeDoubleTapPending = false;
                this.mHandler.removeCallbacks(this.mHomeDoubleTapTimeoutRunnable);
                handleDoubleTapOnHome();
                return -1L;
            } else if (this.mDoubleTapOnHomeBehavior == 1) {
                preloadRecentApps();
                return -1L;
            } else {
                return -1L;
            }
        }
        if (keyCode == 82) {
            if (down && repeatCount == 0 && this.mEnableShiftMenuBugReports && (metaState & 1) == 1) {
                this.mContext.sendOrderedBroadcastAsUser(new Intent("android.intent.action.BUG_REPORT"), UserHandle.CURRENT, null, null, null, 0, null, null);
                return -1L;
            }
        } else if (keyCode == 84) {
            if (!down) {
                this.mSearchKeyShortcutPending = false;
                if (this.mConsumeSearchKeyUp) {
                    this.mConsumeSearchKeyUp = false;
                    return -1L;
                }
            } else if (repeatCount == 0) {
                this.mSearchKeyShortcutPending = true;
                this.mConsumeSearchKeyUp = false;
            }
            return 0L;
        } else if (keyCode == 187) {
            if (keyguardOn) {
                return -1L;
            }
            if (down && repeatCount == 0) {
                preloadRecentApps();
                return -1L;
            } else if (down) {
                return -1L;
            } else {
                toggleRecentApps();
                return -1L;
            }
        } else if (keyCode == 42 && event.isMetaPressed()) {
            if (down && (service = getStatusBarService()) != null) {
                try {
                    service.expandNotificationsPanel();
                } catch (RemoteException e) {
                }
            }
        } else if (keyCode == 47 && event.isMetaPressed() && event.isCtrlPressed()) {
            if (down && repeatCount == 0) {
                this.mScreenshotRunnable.setScreenshotType(event.isShiftPressed() ? 2 : 1);
                this.mHandler.post(this.mScreenshotRunnable);
                return -1L;
            }
        } else if (keyCode != 76 || !event.isMetaPressed()) {
            if (keyCode == 219) {
                Slog.wtf(TAG, "KEYCODE_ASSIST should be handled in interceptKeyBeforeQueueing");
                return -1L;
            } else if (keyCode == 231) {
                Slog.wtf(TAG, "KEYCODE_VOICE_ASSIST should be handled in interceptKeyBeforeQueueing");
                return -1L;
            } else if (keyCode == 120) {
                if (down && repeatCount == 0) {
                    this.mScreenshotRunnable.setScreenshotType(1);
                    this.mHandler.post(this.mScreenshotRunnable);
                    return -1L;
                }
                return -1L;
            } else {
                if (keyCode != 221 && keyCode != 220) {
                    if (keyCode == 24 || keyCode == 25 || keyCode == 164 || keyCode == 804 || keyCode == 805) {
                        if (this.mUseTvRouting) {
                            j = -1;
                        } else if (this.mHandleVolumeKeysInWM) {
                            j = -1;
                        } else if (this.mPersistentVrModeEnabled && (d = event.getDevice()) != null && !d.isExternal()) {
                            return -1L;
                        }
                        dispatchDirectAudioEvent(event);
                        return j;
                    } else if (keyCode == 61 && event.isMetaPressed()) {
                        return 0L;
                    } else {
                        if (this.mHasFeatureLeanback && interceptBugreportGestureTv(keyCode, down)) {
                            return -1L;
                        }
                        if (this.mHasFeatureLeanback && interceptAccessibilityGestureTv(keyCode, down)) {
                            return -1L;
                        }
                        if (keyCode == 284) {
                            if (down) {
                                return -1L;
                            }
                            this.mHandler.removeMessages(25);
                            Message msg = this.mHandler.obtainMessage(25);
                            msg.setAsynchronous(true);
                            msg.sendToTarget();
                            return -1L;
                        }
                    }
                }
                if (down) {
                    int direction = keyCode == 221 ? 1 : -1;
                    int auto = Settings.System.getIntForUser(this.mContext.getContentResolver(), "screen_brightness_mode", 0, -3);
                    if (auto != 0) {
                        Settings.System.putIntForUser(this.mContext.getContentResolver(), "screen_brightness_mode", 0, -3);
                    }
                    int min = this.mPowerManager.getMinimumScreenBrightnessSetting();
                    int max = this.mPowerManager.getMaximumScreenBrightnessSetting();
                    int step = ((((max - min) + 10) - 1) / 10) * direction;
                    ContentResolver contentResolver = this.mContext.getContentResolver();
                    int direction2 = this.mPowerManager.getDefaultScreenBrightnessSetting();
                    int brightness = Settings.System.getIntForUser(contentResolver, "screen_brightness", direction2, -3);
                    Settings.System.putIntForUser(this.mContext.getContentResolver(), "screen_brightness", Math.max(min, Math.min(max, brightness + step)), -3);
                    startActivityAsUser(new Intent("com.android.intent.action.SHOW_BRIGHTNESS_DIALOG"), UserHandle.CURRENT_OR_SELF);
                    return -1L;
                }
                return -1L;
            }
        } else if (down && repeatCount == 0 && !isKeyguardLocked()) {
            toggleKeyboardShortcutsMenu(event.getDeviceId());
        }
        boolean actionTriggered = false;
        if (KeyEvent.isModifierKey(keyCode)) {
            if (!this.mPendingCapsLockToggle) {
                this.mInitialMetaState = this.mMetaState;
                this.mPendingCapsLockToggle = true;
            } else if (event.getAction() == 1) {
                int altOnMask = this.mMetaState & 50;
                int metaOnMask = this.mMetaState & 458752;
                if (metaOnMask != 0 && altOnMask != 0 && this.mInitialMetaState == (this.mMetaState ^ (altOnMask | metaOnMask))) {
                    this.mInputManagerInternal.toggleCapsLock(event.getDeviceId());
                    actionTriggered = true;
                }
                this.mPendingCapsLockToggle = false;
            }
        }
        boolean actionTriggered2 = actionTriggered;
        this.mMetaState = metaState;
        if (actionTriggered2) {
            return -1L;
        }
        if (KeyEvent.isMetaKey(keyCode)) {
            if (down) {
                this.mPendingMetaAction = true;
                return -1L;
            } else if (this.mPendingMetaAction) {
                launchAssistAction("android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD", event.getDeviceId());
                return -1L;
            } else {
                return -1L;
            }
        }
        if (this.mSearchKeyShortcutPending) {
            KeyCharacterMap kcm = event.getKeyCharacterMap();
            if (kcm.isPrintingKey(keyCode)) {
                this.mConsumeSearchKeyUp = true;
                this.mSearchKeyShortcutPending = false;
                if (down && repeatCount == 0 && !keyguardOn) {
                    Intent shortcutIntent2 = this.mShortcutManager.getIntent(kcm, keyCode, metaState);
                    if (shortcutIntent2 == null) {
                        Slog.i(TAG, "Dropping unregistered shortcut key combination: SEARCH+" + KeyEvent.keyCodeToString(keyCode));
                        return -1L;
                    }
                    shortcutIntent2.addFlags(268435456);
                    try {
                        startActivityAsUser(shortcutIntent2, UserHandle.CURRENT);
                        dismissKeyboardShortcutsMenu();
                        return -1L;
                    } catch (ActivityNotFoundException ex) {
                        Slog.w(TAG, "Dropping shortcut key combination because the activity to which it is registered was not found: SEARCH+" + KeyEvent.keyCodeToString(keyCode), ex);
                        return -1L;
                    }
                }
                return -1L;
            }
        }
        if (down && repeatCount == 0 && !keyguardOn && (65536 & metaState) != 0) {
            KeyCharacterMap kcm2 = event.getKeyCharacterMap();
            if (kcm2.isPrintingKey(keyCode) && (shortcutIntent = this.mShortcutManager.getIntent(kcm2, keyCode, (-458753) & metaState)) != null) {
                shortcutIntent.addFlags(268435456);
                try {
                    startActivityAsUser(shortcutIntent, UserHandle.CURRENT);
                    dismissKeyboardShortcutsMenu();
                    return -1L;
                } catch (ActivityNotFoundException ex2) {
                    Slog.w(TAG, "Dropping shortcut key combination because the activity to which it is registered was not found: META+" + KeyEvent.keyCodeToString(keyCode), ex2);
                    return -1L;
                }
            }
        }
        if (down && repeatCount == 0 && !keyguardOn && (category = sApplicationLaunchKeyCategories.get(keyCode)) != null) {
            Intent intent = Intent.makeMainSelectorActivity("android.intent.action.MAIN", category);
            intent.setFlags(268435456);
            try {
                startActivityAsUser(intent, UserHandle.CURRENT);
                dismissKeyboardShortcutsMenu();
                return -1L;
            } catch (ActivityNotFoundException ex3) {
                Slog.w(TAG, "Dropping application launch key because the activity to which it is registered was not found: keyCode=" + keyCode + ", category=" + category, ex3);
                return -1L;
            }
        }
        if (down && repeatCount == 0 && keyCode == 61) {
            if (this.mRecentAppsHeldModifiers == 0 && !keyguardOn && isUserSetupComplete()) {
                int shiftlessModifiers = event.getModifiers() & (-194);
                if (KeyEvent.metaStateHasModifiers(shiftlessModifiers, 2)) {
                    this.mRecentAppsHeldModifiers = shiftlessModifiers;
                    showRecentApps(true);
                    return -1L;
                }
            }
        } else if (!down && this.mRecentAppsHeldModifiers != 0 && (this.mRecentAppsHeldModifiers & metaState) == 0) {
            this.mRecentAppsHeldModifiers = 0;
            hideRecentApps(true, false);
        }
        if (down && repeatCount == 0 && keyCode == 62 && (metaState & 28672) != 0) {
            int direction3 = (metaState & HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_PLUS) != 0 ? -1 : 1;
            this.mWindowManagerFuncs.switchKeyboardLayout(event.getDeviceId(), direction3);
            return -1L;
        } else if (down && repeatCount == 0 && (keyCode == 204 || (keyCode == 62 && (458752 & metaState) != 0))) {
            boolean forwardDirection = (metaState & HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_PLUS) == 0;
            this.mWindowManagerFuncs.switchInputMethod(forwardDirection);
            return -1L;
        } else if (this.mLanguageSwitchKeyPressed && !down && (keyCode == 204 || keyCode == 62)) {
            this.mLanguageSwitchKeyPressed = false;
            return -1L;
        } else if (isValidGlobalKey(keyCode) && this.mGlobalKeyManager.handleGlobalKey(this.mContext, keyCode, event)) {
            return -1L;
        } else {
            if (down) {
                long shortcutCode = keyCode;
                if (event.isCtrlPressed()) {
                    shortcutCode |= 17592186044416L;
                }
                if (event.isAltPressed()) {
                    shortcutCode |= 8589934592L;
                }
                if (event.isShiftPressed()) {
                    shortcutCode |= 4294967296L;
                }
                if (event.isMetaPressed()) {
                    shortcutCode |= 281474976710656L;
                }
                IShortcutService shortcutService = this.mShortcutKeyServices.get(shortcutCode);
                if (shortcutService != null) {
                    try {
                        if (isUserSetupComplete()) {
                            shortcutService.notifyShortcutKeyPressed(shortcutCode);
                            return -1L;
                        }
                        return -1L;
                    } catch (RemoteException e2) {
                        this.mShortcutKeyServices.delete(shortcutCode);
                        return -1L;
                    }
                }
            }
            return (65536 & metaState) != 0 ? -1L : 0L;
        }
    }

    private boolean injectXPGlobalKeyEventBeforeDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        return XGlobalKeyManager.getInstance(this.mContext).processKeyEventBeforeDispatching(win, event, policyFlags);
    }

    private boolean processUnhandleXPGlobalKeyAfterDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        return XGlobalKeyManager.getInstance(this.mContext).processUnhandleKeyEvent(win, event, policyFlags);
    }

    private boolean checkXPGlobalKeyEventIgnored(int key, boolean down) {
        return XGlobalKeyManager.getInstance(this.mContext).checkKeyIgnored(key, down);
    }

    private boolean interceptBugreportGestureTv(int keyCode, boolean down) {
        if (keyCode == 23) {
            this.mBugreportTvKey1Pressed = down;
        } else if (keyCode == 4) {
            this.mBugreportTvKey2Pressed = down;
        }
        if (this.mBugreportTvKey1Pressed && this.mBugreportTvKey2Pressed) {
            if (!this.mBugreportTvScheduled) {
                this.mBugreportTvScheduled = true;
                Message msg = Message.obtain(this.mHandler, 21);
                msg.setAsynchronous(true);
                this.mHandler.sendMessageDelayed(msg, 1000L);
            }
        } else if (this.mBugreportTvScheduled) {
            this.mHandler.removeMessages(21);
            this.mBugreportTvScheduled = false;
        }
        return this.mBugreportTvScheduled;
    }

    private boolean interceptAccessibilityGestureTv(int keyCode, boolean down) {
        if (keyCode == 4) {
            this.mAccessibilityTvKey1Pressed = down;
        } else if (keyCode == 20) {
            this.mAccessibilityTvKey2Pressed = down;
        }
        if (this.mAccessibilityTvKey1Pressed && this.mAccessibilityTvKey2Pressed) {
            if (!this.mAccessibilityTvScheduled) {
                this.mAccessibilityTvScheduled = true;
                Message msg = Message.obtain(this.mHandler, 22);
                msg.setAsynchronous(true);
                this.mHandler.sendMessageDelayed(msg, getAccessibilityShortcutTimeout());
            }
        } else if (this.mAccessibilityTvScheduled) {
            this.mHandler.removeMessages(22);
            this.mAccessibilityTvScheduled = false;
        }
        return this.mAccessibilityTvScheduled;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestFullBugreport() {
        if ("1".equals(SystemProperties.get("ro.debuggable")) || Settings.Global.getInt(this.mContext.getContentResolver(), "development_settings_enabled", 0) == 1) {
            try {
                ActivityManager.getService().requestBugReport(0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error taking bugreport", e);
            }
        }
    }

    private boolean canDispatchInput(WindowManagerPolicy.WindowState win, KeyEvent event) {
        if (event.getKeyCode() == 1011 || event.getKeyCode() == 1012 || event.getKeyCode() == 1031 || event.getKeyCode() == 1032 || event.getKeyCode() == 1013 || event.getKeyCode() == 1014 || event.getKeyCode() == 1033 || event.getKeyCode() == 1034) {
            return true;
        }
        return (win.getAttrs().type == 6 || win.getAttrs().type == 2008 || win.getAttrs().type == 9) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public KeyEvent dispatchUnhandledKey(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        KeyCharacterMap.FallbackAction fallbackAction;
        if (xpInputManagerService.XP_IMS_ENABLE) {
            xpInputManagerService.get(this.mContext).dispatchUnhandledKey(win, event, policyFlags);
        } else if (canDispatchInput(win, event)) {
            processUnhandleXPGlobalKeyAfterDispatching(win, event, policyFlags);
        }
        if ((event.getFlags() & 1024) == 0) {
            KeyCharacterMap kcm = event.getKeyCharacterMap();
            int keyCode = event.getKeyCode();
            int metaState = event.getMetaState();
            boolean initialDown = event.getAction() == 0 && event.getRepeatCount() == 0;
            if (initialDown) {
                fallbackAction = kcm.getFallbackAction(keyCode, metaState);
            } else {
                fallbackAction = this.mFallbackActions.get(keyCode);
            }
            if (fallbackAction != null) {
                int flags = event.getFlags() | 1024;
                KeyEvent fallbackEvent = KeyEvent.obtain(event.getDownTime(), event.getEventTime(), event.getAction(), fallbackAction.keyCode, event.getRepeatCount(), fallbackAction.metaState, event.getDeviceId(), event.getScanCode(), flags, event.getSource(), null);
                if (!interceptFallback(win, fallbackEvent, policyFlags)) {
                    fallbackEvent.recycle();
                    fallbackEvent = null;
                }
                if (initialDown) {
                    this.mFallbackActions.put(keyCode, fallbackAction);
                } else if (event.getAction() == 1) {
                    this.mFallbackActions.remove(keyCode);
                    fallbackAction.recycle();
                }
                return fallbackEvent;
            }
        }
        return null;
    }

    private boolean interceptFallback(WindowManagerPolicy.WindowState win, KeyEvent fallbackEvent, int policyFlags) {
        int actions = interceptKeyBeforeQueueing(fallbackEvent, policyFlags);
        if ((actions & 1) != 0) {
            long delayMillis = interceptKeyBeforeDispatching(win, fallbackEvent, policyFlags);
            if (delayMillis == 0) {
                return true;
            }
            return false;
        }
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutService) throws RemoteException {
        synchronized (this.mLock) {
            IShortcutService service = this.mShortcutKeyServices.get(shortcutCode);
            if (service != null && service.asBinder().pingBinder()) {
                throw new RemoteException("Key already exists.");
            }
            this.mShortcutKeyServices.put(shortcutCode, shortcutService);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onKeyguardOccludedChangedLw(boolean occluded) {
        if (this.mKeyguardDelegate != null && this.mKeyguardDelegate.isShowing()) {
            this.mPendingKeyguardOccluded = occluded;
            this.mKeyguardOccludedChanged = true;
            return;
        }
        setKeyguardOccludedLw(occluded, false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int handleStartTransitionForKeyguardLw(int transit, long duration) {
        if (this.mKeyguardOccludedChanged) {
            this.mKeyguardOccludedChanged = false;
            if (setKeyguardOccludedLw(this.mPendingKeyguardOccluded, false)) {
                return 5;
            }
        }
        if (AppTransition.isKeyguardGoingAwayTransit(transit)) {
            startKeyguardExitAnimation(SystemClock.uptimeMillis(), duration);
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchAssistLongPressAction() {
        performHapticFeedbackLw(null, 0, false);
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
        Intent intent = new Intent("android.intent.action.SEARCH_LONG_PRESS");
        intent.setFlags(268435456);
        try {
            SearchManager searchManager = getSearchManager();
            if (searchManager != null) {
                searchManager.stopSearch();
            }
            startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
            Slog.w(TAG, "No activity to handle assist long press action.", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchAssistAction(String hint, int deviceId) {
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
        if (!isUserSetupComplete()) {
            return;
        }
        Bundle args = null;
        if (deviceId > Integer.MIN_VALUE) {
            args = new Bundle();
            args.putInt("android.intent.extra.ASSIST_INPUT_DEVICE_ID", deviceId);
        }
        if ((this.mContext.getResources().getConfiguration().uiMode & 15) == 4) {
            ((SearchManager) this.mContext.getSystemService("search")).launchLegacyAssist(hint, UserHandle.myUserId(), args);
            return;
        }
        if (hint != null) {
            if (args == null) {
                args = new Bundle();
            }
            args.putBoolean(hint, true);
        }
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.startAssist(args);
        }
    }

    private void startActivityAsUser(Intent intent, UserHandle handle) {
        if (isUserSetupComplete()) {
            this.mContext.startActivityAsUser(intent, handle);
            return;
        }
        Slog.i(TAG, "Not starting activity because user setup is in progress: " + intent);
    }

    private SearchManager getSearchManager() {
        if (this.mSearchManager == null) {
            this.mSearchManager = (SearchManager) this.mContext.getSystemService("search");
        }
        return this.mSearchManager;
    }

    private void preloadRecentApps() {
        this.mPreloadedRecentApps = true;
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.preloadRecentApps();
        }
    }

    private void cancelPreloadRecentApps() {
        if (this.mPreloadedRecentApps) {
            this.mPreloadedRecentApps = false;
            StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
            if (statusbar != null) {
                statusbar.cancelPreloadRecentApps();
            }
        }
    }

    private void toggleRecentApps() {
        this.mPreloadedRecentApps = false;
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.toggleRecentApps();
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void showRecentApps() {
        this.mHandler.removeMessages(9);
        this.mHandler.obtainMessage(9).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showRecentApps(boolean triggeredFromAltTab) {
        this.mPreloadedRecentApps = false;
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.showRecentApps(triggeredFromAltTab);
        }
    }

    private void toggleKeyboardShortcutsMenu(int deviceId) {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.toggleKeyboardShortcutsMenu(deviceId);
        }
    }

    private void dismissKeyboardShortcutsMenu() {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.dismissKeyboardShortcutsMenu();
        }
    }

    private void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHome) {
        this.mPreloadedRecentApps = false;
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.hideRecentApps(triggeredFromAltTab, triggeredFromHome);
        }
    }

    void launchHomeFromHotKey() {
        launchHomeFromHotKey(true, true);
    }

    void launchHomeFromHotKey(final boolean awakenFromDreams, boolean respectKeyguard) {
        Handler handler = this.mHandler;
        WindowManagerPolicy.WindowManagerFuncs windowManagerFuncs = this.mWindowManagerFuncs;
        Objects.requireNonNull(windowManagerFuncs);
        handler.post(new $$Lambda$oXa0y3A00RiQs6KTPBgpkGtgw(windowManagerFuncs));
        if (respectKeyguard) {
            if (isKeyguardShowingAndNotOccluded()) {
                return;
            }
            if (!this.mKeyguardOccluded && this.mKeyguardDelegate.isInputRestricted()) {
                this.mKeyguardDelegate.verifyUnlock(new WindowManagerPolicy.OnKeyguardExitResult() { // from class: com.android.server.policy.PhoneWindowManager.11
                    @Override // com.android.server.policy.WindowManagerPolicy.OnKeyguardExitResult
                    public void onKeyguardExitResult(boolean success) {
                        if (success) {
                            PhoneWindowManager.this.startDockOrHome(true, awakenFromDreams);
                        }
                    }
                });
                return;
            }
        }
        if (this.mRecentsVisible) {
            try {
                ActivityManager.getService().stopAppSwitches();
            } catch (RemoteException e) {
            }
            if (awakenFromDreams) {
                awakenDreams();
            }
            hideRecentApps(false, true);
            return;
        }
        startDockOrHome(true, awakenFromDreams);
    }

    /* loaded from: classes.dex */
    final class HideNavInputEventReceiver extends InputEventReceiver {
        public HideNavInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        public void onInputEvent(InputEvent event, int displayId) {
            try {
                if ((event instanceof MotionEvent) && (event.getSource() & 2) != 0) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getAction() == 0) {
                        boolean changed = false;
                        synchronized (PhoneWindowManager.this.mWindowManagerFuncs.getWindowManagerLock()) {
                            if (PhoneWindowManager.this.mInputConsumer == null) {
                                return;
                            }
                            int newVal = PhoneWindowManager.this.mResettingSystemUiFlags | 2 | 1 | 4;
                            if (PhoneWindowManager.this.mResettingSystemUiFlags != newVal) {
                                PhoneWindowManager.this.mResettingSystemUiFlags = newVal;
                                changed = true;
                            }
                            int newVal2 = PhoneWindowManager.this.mForceClearedSystemUiFlags | 2;
                            if (PhoneWindowManager.this.mForceClearedSystemUiFlags != newVal2) {
                                PhoneWindowManager.this.mForceClearedSystemUiFlags = newVal2;
                                changed = true;
                                PhoneWindowManager.this.mHandler.postDelayed(PhoneWindowManager.this.mClearHideNavigationFlag, 1000L);
                            }
                            if (changed) {
                                PhoneWindowManager.this.mWindowManagerFuncs.reevaluateStatusBarVisibility();
                            }
                        }
                    }
                }
            } finally {
                finishInputEvent(event, false);
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setRecentsVisibilityLw(boolean visible) {
        this.mRecentsVisible = visible;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setPipVisibilityLw(boolean visible) {
        this.mPictureInPictureVisible = visible;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean enabled) {
        this.mNavBarVirtualKeyHapticFeedbackEnabled = enabled;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int adjustSystemUiVisibilityLw(int visibility) {
        this.mStatusBarController.adjustSystemUiVisibilityLw(this.mLastSystemUiFlags, visibility);
        this.mNavigationBarController.adjustSystemUiVisibilityLw(this.mLastSystemUiFlags, visibility);
        this.mResettingSystemUiFlags &= visibility;
        return (~this.mResettingSystemUiFlags) & visibility & (~this.mForceClearedSystemUiFlags);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean getLayoutHintLw(WindowManager.LayoutParams attrs, Rect taskBounds, DisplayFrames displayFrames, Rect outFrame, Rect outContentInsets, Rect outStableInsets, Rect outOutsets, DisplayCutout.ParcelableWrapper outDisplayCutout) {
        int availRight;
        int availRight2;
        int outset;
        int fl = PolicyControl.getWindowFlags(null, attrs);
        int pfl = attrs.privateFlags;
        int requestedSysUiVis = PolicyControl.getSystemUiVisibility(null, attrs);
        int sysUiVis = getImpliedSysUiFlagsForLayout(attrs) | requestedSysUiVis;
        int displayRotation = displayFrames.mRotation;
        int displayWidth = displayFrames.mDisplayWidth;
        int displayHeight = displayFrames.mDisplayHeight;
        boolean useOutsets = outOutsets != null && shouldUseOutsets(attrs, fl);
        if (useOutsets && (outset = ScreenShapeHelper.getWindowOutsetBottomPx(this.mContext.getResources())) > 0) {
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
            if (canHideNavigationBar() && (sysUiVis & 512) != 0) {
                outFrame.set(displayFrames.mUnrestricted);
                int availRight3 = displayFrames.mUnrestricted.right;
                availRight2 = availRight3;
                availRight = displayFrames.mUnrestricted.bottom;
            } else {
                outFrame.set(displayFrames.mRestricted);
                int availRight4 = displayFrames.mRestricted.right;
                availRight = displayFrames.mRestricted.bottom;
                availRight2 = availRight4;
            }
            int i = displayFrames.mStable.left;
            int pfl2 = displayFrames.mStable.top;
            outStableInsets.set(i, pfl2, availRight2 - displayFrames.mStable.right, availRight - displayFrames.mStable.bottom);
            if ((sysUiVis & 256) != 0) {
                if ((fl & 1024) != 0) {
                    outContentInsets.set(displayFrames.mStableFullscreen.left, displayFrames.mStableFullscreen.top, availRight2 - displayFrames.mStableFullscreen.right, availRight - displayFrames.mStableFullscreen.bottom);
                } else {
                    outContentInsets.set(outStableInsets);
                }
            } else if ((fl & 1024) != 0 || (33554432 & fl) != 0) {
                outContentInsets.setEmpty();
            } else {
                outContentInsets.set(displayFrames.mCurrent.left, displayFrames.mCurrent.top, availRight2 - displayFrames.mCurrent.right, availRight - displayFrames.mCurrent.bottom);
            }
            if (taskBounds != null) {
                calculateRelevantTaskInsets(taskBounds, outContentInsets, displayWidth, displayHeight);
                calculateRelevantTaskInsets(taskBounds, outStableInsets, displayWidth, displayHeight);
                outFrame.intersect(taskBounds);
            }
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

    private void calculateRelevantTaskInsets(Rect taskBounds, Rect inOutInsets, int displayWidth, int displayHeight) {
        mTmpRect.set(0, 0, displayWidth, displayHeight);
        mTmpRect.inset(inOutInsets);
        mTmpRect.intersect(taskBounds);
        int leftInset = mTmpRect.left - taskBounds.left;
        int topInset = mTmpRect.top - taskBounds.top;
        int rightInset = taskBounds.right - mTmpRect.right;
        int bottomInset = taskBounds.bottom - mTmpRect.bottom;
        inOutInsets.set(leftInset, topInset, rightInset, bottomInset);
    }

    private boolean shouldUseOutsets(WindowManager.LayoutParams attrs, int fl) {
        return attrs.type == 2013 || (33555456 & fl) != 0;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {
        Rect dcf;
        Rect df;
        Rect pf;
        DisplayFrames displayFrames2;
        displayFrames.onBeginLayout();
        this.mSystemGestures.screenWidth = displayFrames.mUnrestricted.width();
        this.mSystemGestures.screenHeight = displayFrames.mUnrestricted.height();
        this.mDockLayer = 268435456;
        this.mStatusBarLayer = -1;
        Rect pf2 = mTmpParentFrame;
        Rect df2 = mTmpDisplayFrame;
        Rect of = mTmpOverscanFrame;
        Rect vf = mTmpVisibleFrame;
        Rect dcf2 = mTmpDecorFrame;
        vf.set(displayFrames.mDock);
        of.set(displayFrames.mDock);
        df2.set(displayFrames.mDock);
        pf2.set(displayFrames.mDock);
        dcf2.setEmpty();
        if (displayFrames.mDisplayId == 0) {
            int sysui = this.mLastSystemUiFlags;
            boolean navVisible = (sysui & 2) == 0;
            boolean navTranslucent = ((-2147450880) & sysui) != 0;
            boolean immersive = (sysui & 2048) != 0;
            boolean immersiveSticky = (sysui & 4096) != 0;
            boolean navAllowedHidden = immersive || immersiveSticky;
            boolean navTranslucent2 = navTranslucent & (!immersiveSticky);
            boolean isKeyguardShowing = isStatusBarKeyguard() && !this.mKeyguardOccluded;
            if (!isKeyguardShowing) {
                navTranslucent2 &= areTranslucentBarsAllowed();
            }
            boolean navTranslucent3 = navTranslucent2;
            boolean statusBarExpandedNotKeyguard = (isKeyguardShowing || this.mStatusBar == null || (this.mStatusBar.getAttrs().privateFlags & DumpState.DUMP_VOLUMES) == 0) ? false : true;
            if (navVisible || navAllowedHidden) {
                if (this.mInputConsumer != null) {
                    this.mHandler.sendMessage(this.mHandler.obtainMessage(19, this.mInputConsumer));
                    this.mInputConsumer = null;
                }
            } else if (this.mInputConsumer == null && this.mStatusBar != null) {
                canHideNavigationBar();
            }
            boolean updateSysUiVisibility = layoutNavigationBar(displayFrames, uiMode, dcf2, navVisible | (!canHideNavigationBar()), navTranslucent3, navAllowedHidden, statusBarExpandedNotKeyguard);
            dcf = dcf2;
            df = df2;
            pf = pf2;
            displayFrames2 = displayFrames;
            if (updateSysUiVisibility | layoutStatusBar(displayFrames, pf2, df2, of, vf, dcf, sysui, isKeyguardShowing)) {
                updateSystemUiVisibilityLw();
            }
        } else {
            dcf = dcf2;
            df = df2;
            pf = pf2;
            displayFrames2 = displayFrames;
        }
        layoutScreenDecorWindows(displayFrames2, pf, df, dcf);
        if (displayFrames2.mDisplayCutoutSafe.top > displayFrames2.mUnrestricted.top) {
            displayFrames2.mDisplayCutoutSafe.top = Math.max(displayFrames2.mDisplayCutoutSafe.top, displayFrames2.mStable.top);
        }
    }

    private /* synthetic */ InputEventReceiver lambda$beginLayoutLw$2(InputChannel channel, Looper looper) {
        return new HideNavInputEventReceiver(channel, looper);
    }

    private void layoutScreenDecorWindows(DisplayFrames displayFrames, Rect pf, Rect df, Rect dcf) {
        if (this.mScreenDecorWindows.isEmpty()) {
            return;
        }
        int displayId = displayFrames.mDisplayId;
        Rect dockFrame = displayFrames.mDock;
        int displayHeight = displayFrames.mDisplayHeight;
        int displayWidth = displayFrames.mDisplayWidth;
        for (int i = this.mScreenDecorWindows.size() - 1; i >= 0; i--) {
            WindowManagerPolicy.WindowState w = this.mScreenDecorWindows.valueAt(i);
            if (w.getDisplayId() == displayId && w.isVisibleLw()) {
                w.computeFrameLw(pf, df, df, df, df, dcf, df, df, displayFrames.mDisplayCutout, false);
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
        }
        displayFrames.mRestricted.set(dockFrame);
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mSystem.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        displayFrames.mRestrictedOverscan.set(dockFrame);
    }

    private boolean layoutStatusBar(DisplayFrames displayFrames, Rect pf, Rect df, Rect of, Rect vf, Rect dcf, int sysui, boolean isKeyguardShowing) {
        if (this.mStatusBar == null) {
            return false;
        }
        of.set(displayFrames.mUnrestricted);
        df.set(displayFrames.mUnrestricted);
        pf.set(displayFrames.mUnrestricted);
        vf.set(displayFrames.mStable);
        this.mStatusBarLayer = this.mStatusBar.getSurfaceLayer();
        this.mStatusBar.computeFrameLw(pf, df, vf, vf, vf, dcf, vf, vf, displayFrames.mDisplayCutout, false);
        displayFrames.mStable.top = displayFrames.mUnrestricted.top + this.mStatusBarHeightForRotation[displayFrames.mRotation];
        displayFrames.mStable.top = Math.max(displayFrames.mStable.top, displayFrames.mDisplayCutoutSafe.top);
        mTmpRect.set(this.mStatusBar.getContentFrameLw());
        mTmpRect.intersect(displayFrames.mDisplayCutoutSafe);
        mTmpRect.top = this.mStatusBar.getContentFrameLw().top;
        mTmpRect.bottom = displayFrames.mStable.top;
        this.mStatusBarController.setContentFrame(mTmpRect);
        boolean statusBarTransient = (sysui & 67108864) != 0;
        boolean statusBarTranslucent = (sysui & 1073741832) != 0;
        if (!isKeyguardShowing) {
            statusBarTranslucent &= areTranslucentBarsAllowed();
        }
        if (this.mStatusBar.isVisibleLw() && !statusBarTransient) {
            Rect dockFrame = displayFrames.mDock;
            dockFrame.top = displayFrames.mStable.top;
            displayFrames.mContent.set(dockFrame);
            displayFrames.mVoiceContent.set(dockFrame);
            displayFrames.mCurrent.set(dockFrame);
            if (!this.mStatusBar.isAnimatingLw() && !statusBarTranslucent && !this.mStatusBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.top = displayFrames.mStable.top;
            }
        }
        return this.mStatusBarController.checkHiddenLw();
    }

    private boolean layoutNavigationBar(DisplayFrames displayFrames, int uiMode, Rect dcf, boolean navVisible, boolean navTranslucent, boolean navAllowedHidden, boolean statusBarExpandedNotKeyguard) {
        if (this.mNavigationBar == null) {
            return false;
        }
        boolean transientNavBarShowing = this.mNavigationBarController.isTransientShowing();
        int rotation = displayFrames.mRotation;
        int displayHeight = displayFrames.mDisplayHeight;
        int displayWidth = displayFrames.mDisplayWidth;
        Rect dockFrame = displayFrames.mDock;
        this.mNavigationBarPosition = navigationBarPosition(displayWidth, displayHeight, rotation);
        Rect cutoutSafeUnrestricted = mTmpRect;
        cutoutSafeUnrestricted.set(displayFrames.mUnrestricted);
        cutoutSafeUnrestricted.intersectUnchecked(displayFrames.mDisplayCutoutSafe);
        if (this.mNavigationBarPosition == 4) {
            int top = cutoutSafeUnrestricted.bottom - getNavigationBarHeight(rotation, uiMode);
            mTmpNavigationFrame.set(0, top, displayWidth, displayFrames.mUnrestricted.bottom);
            Rect rect = displayFrames.mStable;
            displayFrames.mStableFullscreen.bottom = top;
            rect.bottom = top;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (!navVisible) {
                this.mNavigationBarController.setBarShowingLw(statusBarExpandedNotKeyguard);
            } else {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect2 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.bottom = top;
                rect2.bottom = top;
                dockFrame.bottom = top;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.bottom = top;
            }
        } else if (this.mNavigationBarPosition == 2) {
            int left = cutoutSafeUnrestricted.right - getNavigationBarWidth(rotation, uiMode);
            mTmpNavigationFrame.set(left, 0, displayFrames.mUnrestricted.right, displayHeight);
            Rect rect3 = displayFrames.mStable;
            displayFrames.mStableFullscreen.right = left;
            rect3.right = left;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (!navVisible) {
                this.mNavigationBarController.setBarShowingLw(statusBarExpandedNotKeyguard);
            } else {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect4 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.right = left;
                rect4.right = left;
                dockFrame.right = left;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.right = left;
            }
        } else if (this.mNavigationBarPosition == 1) {
            int right = cutoutSafeUnrestricted.left + getNavigationBarWidth(rotation, uiMode);
            mTmpNavigationFrame.set(displayFrames.mUnrestricted.left, 0, right, displayHeight);
            Rect rect5 = displayFrames.mStable;
            displayFrames.mStableFullscreen.left = right;
            rect5.left = right;
            if (transientNavBarShowing) {
                this.mNavigationBarController.setBarShowingLw(true);
            } else if (!navVisible) {
                this.mNavigationBarController.setBarShowingLw(statusBarExpandedNotKeyguard);
            } else {
                this.mNavigationBarController.setBarShowingLw(true);
                Rect rect6 = displayFrames.mRestricted;
                displayFrames.mRestrictedOverscan.left = right;
                rect6.left = right;
                dockFrame.left = right;
            }
            if (navVisible && !navTranslucent && !navAllowedHidden && !this.mNavigationBar.isAnimatingLw() && !this.mNavigationBarController.wasRecentlyTranslucent()) {
                displayFrames.mSystem.left = right;
            }
        }
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        this.mStatusBarLayer = this.mNavigationBar.getSurfaceLayer();
        this.mNavigationBar.computeFrameLw(mTmpNavigationFrame, mTmpNavigationFrame, mTmpNavigationFrame, displayFrames.mDisplayCutoutSafe, mTmpNavigationFrame, dcf, mTmpNavigationFrame, displayFrames.mDisplayCutoutSafe, displayFrames.mDisplayCutout, false);
        this.mNavigationBarController.setContentFrame(this.mNavigationBar.getContentFrameLw());
        return this.mNavigationBarController.checkHiddenLw();
    }

    private int navigationBarPosition(int displayWidth, int displayHeight, int displayRotation) {
        return xpPhoneWindowManager.get(this.mContext).navigationBarPosition();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getSystemDecorLayerLw() {
        if (this.mStatusBar != null && this.mStatusBar.isVisibleLw()) {
            return this.mStatusBar.getSurfaceLayer();
        }
        if (this.mNavigationBar != null && this.mNavigationBar.isVisibleLw()) {
            return this.mNavigationBar.getSurfaceLayer();
        }
        return 0;
    }

    private void setAttachedWindowFrames(WindowManagerPolicy.WindowState win, int fl, int adjust, WindowManagerPolicy.WindowState attached, boolean insetDecors, Rect pf, Rect df, Rect of, Rect cf, Rect vf, DisplayFrames displayFrames) {
        if (!win.isInputMethodTarget() && attached.isInputMethodTarget()) {
            vf.set(displayFrames.mDock);
            cf.set(displayFrames.mDock);
            of.set(displayFrames.mDock);
            df.set(displayFrames.mDock);
        } else {
            if (adjust != 16) {
                cf.set((1073741824 & fl) != 0 ? attached.getContentFrameLw() : attached.getOverscanFrameLw());
            } else {
                cf.set(attached.getContentFrameLw());
                if (attached.isVoiceInteraction()) {
                    cf.intersectUnchecked(displayFrames.mVoiceContent);
                } else if (win.isInputMethodTarget() || attached.isInputMethodTarget()) {
                    cf.intersectUnchecked(displayFrames.mContent);
                }
            }
            df.set(insetDecors ? attached.getDisplayFrameLw() : cf);
            of.set(insetDecors ? attached.getOverscanFrameLw() : cf);
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

    private boolean canReceiveInput(WindowManagerPolicy.WindowState win) {
        boolean notFocusable = (win.getAttrs().flags & 8) != 0;
        boolean altFocusableIm = (win.getAttrs().flags & DumpState.DUMP_INTENT_FILTER_VERIFIERS) != 0;
        boolean notFocusableForIm = notFocusable ^ altFocusableIm;
        return !notFocusableForIm;
    }

    /* JADX WARN: Removed duplicated region for block: B:170:0x03ba  */
    /* JADX WARN: Removed duplicated region for block: B:177:0x03dc  */
    /* JADX WARN: Removed duplicated region for block: B:180:0x03ec  */
    /* JADX WARN: Removed duplicated region for block: B:182:0x0401  */
    /* JADX WARN: Removed duplicated region for block: B:275:0x0674  */
    /* JADX WARN: Removed duplicated region for block: B:276:0x067a  */
    /* JADX WARN: Removed duplicated region for block: B:284:0x0692  */
    /* JADX WARN: Removed duplicated region for block: B:285:0x0695  */
    /* JADX WARN: Removed duplicated region for block: B:301:0x06b8  */
    /* JADX WARN: Removed duplicated region for block: B:330:0x0722  */
    /* JADX WARN: Removed duplicated region for block: B:339:0x074b  */
    /* JADX WARN: Removed duplicated region for block: B:361:0x07f2  */
    /* JADX WARN: Removed duplicated region for block: B:362:0x0828  */
    @Override // com.android.server.policy.WindowManagerPolicy
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void layoutWindowLw(com.android.server.policy.WindowManagerPolicy.WindowState r77, com.android.server.policy.WindowManagerPolicy.WindowState r78, com.android.server.wm.DisplayFrames r79) {
        /*
            Method dump skipped, instructions count: 2188
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.PhoneWindowManager.layoutWindowLw(com.android.server.policy.WindowManagerPolicy$WindowState, com.android.server.policy.WindowManagerPolicy$WindowState, com.android.server.wm.DisplayFrames):void");
    }

    private void layoutWallpaper(DisplayFrames displayFrames, Rect pf, Rect df, Rect of, Rect cf) {
        df.set(displayFrames.mOverscan);
        pf.set(displayFrames.mOverscan);
        cf.set(displayFrames.mUnrestricted);
        of.set(displayFrames.mUnrestricted);
    }

    private void offsetInputMethodWindowLw(WindowManagerPolicy.WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top) + win.getGivenContentInsetsLw().top;
        displayFrames.mContent.bottom = Math.min(displayFrames.mContent.bottom, top);
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
        int top2 = win.getVisibleFrameLw().top + win.getGivenVisibleInsetsLw().top;
        displayFrames.mCurrent.bottom = Math.min(displayFrames.mCurrent.bottom, top2);
    }

    private void offsetVoiceInputWindowLw(WindowManagerPolicy.WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top);
        int top2 = top + win.getGivenContentInsetsLw().top;
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top2);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void beginPostLayoutPolicyLw(int displayWidth, int displayHeight) {
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

    @Override // com.android.server.policy.WindowManagerPolicy
    public void applyPostLayoutPolicyLw(WindowManagerPolicy.WindowState win, WindowManager.LayoutParams attrs, WindowManagerPolicy.WindowState attached, WindowManagerPolicy.WindowState imeTarget) {
        boolean affectsSystemUi = win.canAffectSystemUiFlags();
        applyKeyguardPolicyLw(win, imeTarget);
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
                this.mTopFullscreenOpaqueWindowState = win;
                if (this.mTopFullscreenOpaqueOrDimmingWindowState == null) {
                    this.mTopFullscreenOpaqueOrDimmingWindowState = win;
                }
                if ((fl & 1) != 0) {
                    this.mAllowLockscreenWhenOn = true;
                }
            }
        }
        if (affectsSystemUi && win.getAttrs().type == 2031) {
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
        if ((attrs.privateFlags & DumpState.DUMP_COMPILER_STATS) != 0 && win.canAcquireSleepToken()) {
            this.mWindowSleepTokenNeeded = true;
        }
    }

    private void applyKeyguardPolicyLw(WindowManagerPolicy.WindowState win, WindowManagerPolicy.WindowState imeTarget) {
        if (canBeHiddenByKeyguardLw(win)) {
            if (shouldBeHiddenByKeyguard(win, imeTarget)) {
                win.hideLw(false);
            } else {
                win.showLw(false);
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int finishPostLayoutPolicyLw() {
        WindowManager.LayoutParams lp;
        int changes = 0;
        boolean topIsFullscreen = false;
        if (this.mTopFullscreenOpaqueWindowState != null) {
            lp = this.mTopFullscreenOpaqueWindowState.getAttrs();
        } else {
            lp = null;
        }
        boolean z = true;
        if (!this.mShowingDream) {
            this.mDreamingLockscreen = isKeyguardShowingAndNotOccluded();
            if (this.mDreamingSleepTokenNeeded) {
                this.mDreamingSleepTokenNeeded = false;
                this.mHandler.obtainMessage(15, 0, 1).sendToTarget();
            }
        } else if (!this.mDreamingSleepTokenNeeded) {
            this.mDreamingSleepTokenNeeded = true;
            this.mHandler.obtainMessage(15, 1, 1).sendToTarget();
        }
        if (this.mStatusBar != null) {
            boolean shouldBeTransparent = (!this.mForceStatusBarTransparent || this.mForceStatusBar || this.mForceStatusBarFromKeyguard) ? false : true;
            if (!shouldBeTransparent) {
                this.mStatusBarController.setShowTransparent(false);
            } else if (!this.mStatusBar.isVisibleLw()) {
                this.mStatusBarController.setShowTransparent(true);
            }
            boolean statusBarExpanded = (this.mStatusBar.getAttrs().privateFlags & DumpState.DUMP_VOLUMES) != 0;
            boolean topAppHidesStatusBar = topAppHidesStatusBar();
            boolean isSystemUiChanged = xpPhoneWindowManager.isSystemUiVisibilityChanged(lp, this.mStatusBar, this.mNavigationBar);
            boolean isStatusBarVisible = lp == null || !xpWindowManager.hasFullscreenFlag(lp.systemUiVisibility, lp.flags);
            if (isSystemUiChanged || this.mForceStatusBar || this.mForceStatusBarFromKeyguard || this.mForceStatusBarTransparent || statusBarExpanded) {
                if (this.mStatusBarController.setBarShowingLw(isStatusBarVisible)) {
                    changes = 0 | 1;
                }
                if (!this.mTopIsFullscreen || !this.mStatusBar.isAnimatingLw()) {
                    z = false;
                }
                topIsFullscreen = z;
                if ((this.mForceStatusBarFromKeyguard || statusBarExpanded) && this.mStatusBarController.isTransientShowing()) {
                    this.mStatusBarController.updateVisibilityLw(false, this.mLastSystemUiFlags, this.mLastSystemUiFlags);
                }
            } else if (this.mTopFullscreenOpaqueWindowState != null) {
                topIsFullscreen = topAppHidesStatusBar;
                if (this.mStatusBarController.isTransientShowing()) {
                    if (this.mStatusBarController.setBarShowingLw(true)) {
                        changes = 0 | 1;
                    }
                } else if (topIsFullscreen && !this.mWindowManagerInternal.isStackVisible(5) && !this.mWindowManagerInternal.isStackVisible(3)) {
                    if (this.mStatusBarController.setBarShowingLw(false)) {
                        changes = 0 | 1;
                    }
                } else {
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
        if (this.mShowingDream != this.mLastShowingDream) {
            this.mLastShowingDream = this.mShowingDream;
            this.mWindowManagerFuncs.notifyShowingDreamChanged();
        }
        updateWindowSleepToken();
        updateLockScreenTimeout();
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
        if (this.mTopFullscreenOpaqueWindowState == null) {
            return false;
        }
        int fl = PolicyControl.getWindowFlags(null, this.mTopFullscreenOpaqueWindowState.getAttrs());
        return ((fl & 1024) == 0 && (this.mLastSystemUiFlags & 4) == 0) ? false : true;
    }

    private boolean setKeyguardOccludedLw(boolean isOccluded, boolean force) {
        boolean wasOccluded = this.mKeyguardOccluded;
        boolean showing = this.mKeyguardDelegate.isShowing();
        boolean changed = wasOccluded != isOccluded || force;
        if (!isOccluded && changed && showing) {
            this.mKeyguardOccluded = false;
            this.mKeyguardDelegate.setOccluded(false, true);
            if (this.mStatusBar != null) {
                this.mStatusBar.getAttrs().privateFlags |= 1024;
                if (!this.mKeyguardDelegate.hasLockscreenWallpaper()) {
                    this.mStatusBar.getAttrs().flags |= 1048576;
                }
            }
            return true;
        } else if (isOccluded && changed && showing) {
            this.mKeyguardOccluded = true;
            this.mKeyguardDelegate.setOccluded(true, false);
            if (this.mStatusBar != null) {
                this.mStatusBar.getAttrs().privateFlags &= -1025;
                this.mStatusBar.getAttrs().flags &= -1048577;
            }
            return true;
        } else if (changed) {
            this.mKeyguardOccluded = isOccluded;
            this.mKeyguardDelegate.setOccluded(isOccluded, false);
            return false;
        } else {
            return false;
        }
    }

    private boolean isStatusBarKeyguard() {
        return (this.mStatusBar == null || (this.mStatusBar.getAttrs().privateFlags & 1024) == 0) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean allowAppAnimationsLw() {
        return !this.mShowingDream;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int focusChangedLw(WindowManagerPolicy.WindowState lastFocus, WindowManagerPolicy.WindowState newFocus) {
        this.mFocusedWindow = newFocus;
        if ((updateSystemUiVisibilityLw() & SYSTEM_UI_CHANGING_LAYOUT) != 0) {
            return 1;
        }
        return 0;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        if (lidOpen == this.mLidState) {
            return;
        }
        this.mLidState = lidOpen ? 1 : 0;
        applyLidSwitchState();
        updateRotation(true);
        if (lidOpen) {
            wakeUp(SystemClock.uptimeMillis(), this.mAllowTheaterModeWakeFromLidSwitch, "android.policy:LID");
        } else if (!this.mLidControlsSleep) {
            this.mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {
        Intent intent;
        if (this.mCameraLensCoverState == lensCovered) {
            return;
        }
        if (this.mCameraLensCoverState == 1 && !lensCovered) {
            boolean keyguardActive = this.mKeyguardDelegate == null ? false : this.mKeyguardDelegate.isShowing();
            if (keyguardActive) {
                intent = new Intent("android.media.action.STILL_IMAGE_CAMERA_SECURE");
            } else {
                intent = new Intent("android.media.action.STILL_IMAGE_CAMERA");
            }
            wakeUp(whenNanos / 1000000, this.mAllowTheaterModeWakeFromCameraLens, "android.policy:CAMERA_COVER");
            startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
        }
        this.mCameraLensCoverState = lensCovered ? 1 : 0;
    }

    void setHdmiPlugged(boolean plugged) {
        if (this.mHdmiPlugged != plugged) {
            this.mHdmiPlugged = plugged;
            updateRotation(true, true);
            Intent intent = new Intent("android.intent.action.HDMI_PLUGGED");
            intent.addFlags(67108864);
            intent.putExtra(AudioService.CONNECT_INTENT_KEY_STATE, plugged);
            this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void initializeHdmiState() {
        int oldMask = StrictMode.allowThreadDiskReadsMask();
        try {
            initializeHdmiStateInternal();
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    void initializeHdmiStateInternal() {
        boolean plugged = false;
        if (new File("/sys/devices/virtual/switch/hdmi/state").exists()) {
            this.mHDMIObserver.startObserving("DEVPATH=/devices/virtual/switch/hdmi");
            FileReader reader = null;
            try {
                try {
                    try {
                        reader = new FileReader("/sys/class/switch/hdmi/state");
                        char[] buf = new char[15];
                        int n = reader.read(buf);
                        if (n > 1) {
                            plugged = Integer.parseInt(new String(buf, 0, n + (-1))) != 0;
                        }
                        reader.close();
                    } catch (Throwable th) {
                        if (reader != null) {
                            try {
                                reader.close();
                            } catch (IOException e) {
                            }
                        }
                        throw th;
                    }
                } catch (IOException ex) {
                    Slog.w(TAG, "Couldn't read hdmi state from /sys/class/switch/hdmi/state: " + ex);
                    if (reader != null) {
                        reader.close();
                    }
                } catch (NumberFormatException ex2) {
                    Slog.w(TAG, "Couldn't read hdmi state from /sys/class/switch/hdmi/state: " + ex2);
                    if (reader != null) {
                        reader.close();
                    }
                }
            } catch (IOException e2) {
            }
        }
        this.mHdmiPlugged = plugged ? false : true;
        setHdmiPlugged(!this.mHdmiPlugged);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:121:0x0177  */
    /* JADX WARN: Removed duplicated region for block: B:122:0x017d A[FALL_THROUGH] */
    @Override // com.android.server.policy.WindowManagerPolicy
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int interceptKeyBeforeQueueing(android.view.KeyEvent r21, int r22) {
        /*
            Method dump skipped, instructions count: 1012
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.PhoneWindowManager.interceptKeyBeforeQueueing(android.view.KeyEvent, int):int");
    }

    private void interceptSystemNavigationKey(KeyEvent event) {
        if (event.getAction() == 1) {
            if ((!this.mAccessibilityManager.isEnabled() || !this.mAccessibilityManager.sendFingerprintGesture(event.getKeyCode())) && this.mSystemNavigationKeysEnabled) {
                sendSystemKeyToStatusBarAsync(event.getKeyCode());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendSystemKeyToStatusBar(int keyCode) {
        IStatusBarService statusBar = getStatusBarService();
        if (statusBar != null) {
            try {
                statusBar.handleSystemKey(keyCode);
            } catch (RemoteException e) {
            }
        }
    }

    private void sendSystemKeyToStatusBarAsync(int keyCode) {
        Message message = this.mHandler.obtainMessage(24, keyCode, 0);
        message.setAsynchronous(true);
        this.mHandler.sendMessage(message);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendProposedRotationChangeToStatusBarInternal(int rotation, boolean isValid) {
        StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
        if (statusBar != null) {
            statusBar.onProposedRotationChanged(rotation, isValid);
        }
    }

    private static boolean isValidGlobalKey(int keyCode) {
        if (keyCode != 26) {
            switch (keyCode) {
                case NetworkManagementService.NetdResponseCode.ClatdStatusResult /* 223 */:
                case UsbDescriptor.CLASSID_WIRELESS /* 224 */:
                    return false;
                default:
                    return true;
            }
        }
        return false;
    }

    private boolean isWakeKeyWhenScreenOff(int keyCode) {
        if (keyCode != MSG_LAUNCH_ASSIST_LONG_PRESS && keyCode != 79 && keyCode != 130) {
            if (keyCode != 164) {
                if (keyCode != 222) {
                    switch (keyCode) {
                        case 24:
                        case 25:
                            break;
                        default:
                            switch (keyCode) {
                                case HdmiCecKeycode.CEC_KEYCODE_INITIAL_CONFIGURATION /* 85 */:
                                case HdmiCecKeycode.CEC_KEYCODE_SELECT_BROADCAST_TYPE /* 86 */:
                                case HdmiCecKeycode.CEC_KEYCODE_SELECT_SOUND_PRESENTATION /* 87 */:
                                case 88:
                                case 89:
                                case 90:
                                case 91:
                                    break;
                                default:
                                    switch (keyCode) {
                                        case 126:
                                        case 127:
                                            break;
                                        default:
                                            switch (keyCode) {
                                                case 804:
                                                case 805:
                                                    break;
                                                default:
                                                    return true;
                                            }
                                    }
                            }
                    }
                }
            }
            return this.mDockMode != 0;
        }
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        if ((policyFlags & 1) == 0 || !wakeUp(whenNanos / 1000000, this.mAllowTheaterModeWakeFromMotion, "android.policy:MOTION")) {
            if (shouldDispatchInputWhenNonInteractive(null)) {
                return 1;
            }
            if (isTheaterModeEnabled() && (policyFlags & 1) != 0) {
                wakeUp(whenNanos / 1000000, this.mAllowTheaterModeWakeFromMotionWhenNotDreaming, "android.policy:MOTION");
            }
            return 0;
        }
        return 0;
    }

    private boolean shouldDispatchInputWhenNonInteractive(KeyEvent event) {
        IDreamManager dreamManager;
        boolean displayOff = this.mDisplay == null || this.mDisplay.getState() == 1;
        if (!displayOff || this.mHasFeatureWatch) {
            if (!isKeyguardShowingAndNotOccluded() || displayOff) {
                if ((!this.mHasFeatureWatch || event == null || (event.getKeyCode() != 4 && event.getKeyCode() != 264)) && (dreamManager = getDreamManager()) != null) {
                    try {
                        if (dreamManager.isDreaming()) {
                            return true;
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "RemoteException when checking if dreaming", e);
                    }
                }
                return false;
            }
            return true;
        }
        return false;
    }

    private void dispatchDirectAudioEvent(KeyEvent event) {
        if (event.getAction() != 0) {
            return;
        }
        int keyCode = event.getKeyCode();
        if (this.mHandler == null) {
            return;
        }
        switch (keyCode) {
            case 24:
            case 804:
                try {
                    this.mHandler.sendMessage(this.mHandler.obtainMessage(101));
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Error dispatching volume up in dispatchTvAudioEvent.", e);
                    return;
                }
            case 25:
            case 805:
                try {
                    this.mHandler.sendMessage(this.mHandler.obtainMessage(102));
                    return;
                } catch (Exception e2) {
                    Log.e(TAG, "Error dispatching volume down in dispatchTvAudioEvent.", e2);
                    return;
                }
            case 164:
                try {
                    if (event.getRepeatCount() == 0) {
                        this.mHandler.sendMessage(this.mHandler.obtainMessage(103));
                        return;
                    }
                    return;
                } catch (Exception e3) {
                    Log.e(TAG, "Error dispatching mute in dispatchTvAudioEvent.", e3);
                    return;
                }
            default:
                return;
        }
    }

    void dispatchMediaKeyWithWakeLock(KeyEvent event) {
        if (this.mHavePendingMediaKeyRepeatWithWakeLock) {
            this.mHandler.removeMessages(4);
            this.mHavePendingMediaKeyRepeatWithWakeLock = false;
            this.mBroadcastWakeLock.release();
        }
        dispatchMediaKeyWithWakeLockToAudioService(event);
        if (event.getAction() == 0 && event.getRepeatCount() == 0) {
            this.mHavePendingMediaKeyRepeatWithWakeLock = true;
            Message msg = this.mHandler.obtainMessage(4, event);
            msg.setAsynchronous(true);
            this.mHandler.sendMessageDelayed(msg, ViewConfiguration.getKeyRepeatTimeout());
            return;
        }
        this.mBroadcastWakeLock.release();
    }

    void dispatchMediaKeyRepeatWithWakeLock(KeyEvent event) {
        this.mHavePendingMediaKeyRepeatWithWakeLock = false;
        KeyEvent repeatEvent = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 1, event.getFlags() | 128);
        dispatchMediaKeyWithWakeLockToAudioService(repeatEvent);
        this.mBroadcastWakeLock.release();
    }

    void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        if (this.mActivityManagerInternal.isSystemReady()) {
            MediaSessionLegacyHelper.getHelper(this.mContext).sendMediaButtonEvent(event, true);
        }
    }

    void launchVoiceAssistWithWakeLock() {
        Intent voiceIntent;
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
        if (!keyguardOn()) {
            voiceIntent = new Intent("android.speech.action.WEB_SEARCH");
        } else {
            IDeviceIdleController dic = IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"));
            if (dic != null) {
                try {
                    dic.exitIdle("voice-search");
                } catch (RemoteException e) {
                }
            }
            Intent voiceIntent2 = new Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE");
            voiceIntent2.putExtra("android.speech.extras.EXTRA_SECURE", true);
            voiceIntent = voiceIntent2;
        }
        startActivityAsUser(voiceIntent, UserHandle.CURRENT_OR_SELF);
        this.mBroadcastWakeLock.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestTransientBars(WindowManagerPolicy.WindowState swipeTarget) {
        synchronized (this.mWindowManagerFuncs.getWindowManagerLock()) {
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void startedGoingToSleep(int why) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started going to sleep... (why=" + why + ")");
        }
        this.mGoingToSleep = true;
        this.mRequestedOrGoingToSleep = true;
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.onStartedGoingToSleep(why);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void finishedGoingToSleep(int why) {
        EventLog.writeEvent(70000, 0);
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished going to sleep... (why=" + why + ")");
        }
        MetricsLogger.histogram(this.mContext, "screen_timeout", this.mLockScreenTimeout / 1000);
        this.mGoingToSleep = false;
        this.mRequestedOrGoingToSleep = false;
        synchronized (this.mLock) {
            this.mAwake = false;
            updateWakeGestureListenerLp();
            updateOrientationListenerLp();
            updateLockScreenTimeout();
        }
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.onFinishedGoingToSleep(why, this.mCameraGestureTriggeredDuringGoingToSleep);
        }
        this.mCameraGestureTriggeredDuringGoingToSleep = false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void startedWakingUp() {
        EventLog.writeEvent(70000, 1);
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started waking up...");
        }
        synchronized (this.mLock) {
            this.mAwake = true;
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "Started waking up & update state");
            }
            updateWakeGestureListenerLp();
            updateOrientationListenerLp();
            updateLockScreenTimeout();
        }
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.onStartedWakingUp();
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void finishedWakingUp() {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished waking up...");
        }
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.onFinishedWakingUp();
        }
    }

    private void wakeUpFromPowerKey(long eventTime) {
        wakeUp(eventTime, this.mAllowTheaterModeWakeFromPowerKey, "android.policy:POWER");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean wakeUp(long wakeTime, boolean wakeInTheaterMode, String reason) {
        boolean theaterModeEnabled = isTheaterModeEnabled();
        if (!wakeInTheaterMode && theaterModeEnabled) {
            return false;
        }
        if (theaterModeEnabled) {
            Settings.Global.putInt(this.mContext.getContentResolver(), "theater_mode_on", 0);
        }
        this.mPowerManager.wakeUp(wakeTime, reason);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void finishKeyguardDrawn() {
        synchronized (this.mLock) {
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "finishKeyguardDrawn mScreenOnEarly=" + this.mScreenOnEarly + " mKeyguardDrawComplete=" + this.mKeyguardDrawComplete);
            }
            if (this.mScreenOnEarly && !this.mKeyguardDrawComplete) {
                this.mKeyguardDrawComplete = true;
                if (this.mKeyguardDelegate != null) {
                    this.mHandler.removeMessages(6);
                }
                this.mWindowManagerDrawComplete = false;
                if (DEBUG_WAKEUP) {
                    Slog.i(TAG, "finishKeyguardDrawn waitForAllWindowsDrawn");
                }
                this.mWindowManagerInternal.waitForAllWindowsDrawn(this.mWindowManagerDrawCallback, 1000L);
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void screenTurnedOff() {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Screen turned off...");
        }
        updateScreenOffSleepToken(true);
        synchronized (this.mLock) {
            this.mScreenOnEarly = false;
            this.mScreenOnFully = false;
            this.mKeyguardDrawComplete = false;
            this.mWindowManagerDrawComplete = false;
            this.mScreenOnListener = null;
            if (DEBUG_WAKEUP) {
                Slog.d(TAG, "screenTurnedOff mScreenOnEarly=" + this.mScreenOnEarly + " mScreenOnFully=" + this.mScreenOnFully + " mKeyguardDrawComplete=" + this.mKeyguardDrawComplete + " mWindowManagerDrawComplete=" + this.mWindowManagerDrawComplete + " mScreenOnListener=" + this.mScreenOnListener);
            }
            updateOrientationListenerLp();
            if (this.mKeyguardDelegate != null) {
                this.mKeyguardDelegate.onScreenTurnedOff();
            }
        }
        reportScreenStateToVrManager(false);
    }

    private long getKeyguardDrawnTimeout() {
        boolean bootCompleted = ((SystemServiceManager) LocalServices.getService(SystemServiceManager.class)).isBootCompleted();
        return bootCompleted ? 1000L : 5000L;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void screenTurningOn(WindowManagerPolicy.ScreenOnListener screenOnListener) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Screen turning on... listener =" + screenOnListener);
        }
        updateScreenOffSleepToken(false);
        synchronized (this.mLock) {
            this.mScreenOnEarly = true;
            this.mScreenOnFully = false;
            this.mKeyguardDrawComplete = false;
            this.mWindowManagerDrawComplete = false;
            this.mScreenOnListener = screenOnListener;
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "screenTurningOn mScreenOnEarly=" + this.mScreenOnEarly + " mScreenOnFully=" + this.mScreenOnFully + " mKeyguardDrawComplete=" + this.mKeyguardDrawComplete + " mWindowManagerDrawComplete=" + this.mWindowManagerDrawComplete + " mScreenOnListener=" + this.mScreenOnListener);
            }
            if (this.mKeyguardDelegate != null && this.mKeyguardDelegate.hasKeyguard()) {
                this.mHandler.removeMessages(6);
                this.mHandler.sendEmptyMessageDelayed(6, getKeyguardDrawnTimeout());
                this.mKeyguardDelegate.onScreenTurningOn(this.mKeyguardDrawnCallback);
                return;
            }
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "null mKeyguardDelegate: setting mKeyguardDrawComplete.");
            }
            finishKeyguardDrawn();
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void screenTurnedOn() {
        synchronized (this.mLock) {
            if (this.mKeyguardDelegate != null) {
                this.mKeyguardDelegate.onScreenTurnedOn();
            }
        }
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "screenTurnedOn");
        }
        reportScreenStateToVrManager(true);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void screenTurningOff(WindowManagerPolicy.ScreenOffListener screenOffListener) {
        this.mWindowManagerFuncs.screenTurningOff(screenOffListener);
        synchronized (this.mLock) {
            if (this.mKeyguardDelegate != null) {
                this.mKeyguardDelegate.onScreenTurningOff();
            }
        }
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "screenTurningOff");
        }
    }

    private void reportScreenStateToVrManager(boolean isScreenOn) {
        if (this.mVrManagerInternal == null) {
            return;
        }
        this.mVrManagerInternal.onScreenStateChanged(isScreenOn);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void finishWindowsDrawn() {
        synchronized (this.mLock) {
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "finishWindowsDrawn mScreenOnEarly=" + this.mScreenOnEarly + " mWindowManagerDrawComplete=" + this.mWindowManagerDrawComplete);
            }
            if (this.mScreenOnEarly && !this.mWindowManagerDrawComplete) {
                this.mWindowManagerDrawComplete = true;
                if (DEBUG_WAKEUP) {
                    Slog.i(TAG, "finishWindowsDrawn");
                }
                finishScreenTurningOn();
            }
        }
    }

    private void finishScreenTurningOn() {
        boolean enableScreen;
        synchronized (this.mLock) {
            updateOrientationListenerLp();
        }
        synchronized (this.mLock) {
            if (DEBUG_WAKEUP) {
                Slog.i(TAG, "finishScreenTurningOn: mAwake=" + this.mAwake + ", mScreenOnEarly=" + this.mScreenOnEarly + ", mScreenOnFully=" + this.mScreenOnFully + ", mKeyguardDrawComplete=" + this.mKeyguardDrawComplete + ", mWindowManagerDrawComplete=" + this.mWindowManagerDrawComplete);
            }
            if (!this.mScreenOnFully && this.mScreenOnEarly && this.mWindowManagerDrawComplete && (!this.mAwake || this.mKeyguardDrawComplete)) {
                if (DEBUG_WAKEUP) {
                    Slog.i(TAG, "Finished screen turning on...");
                }
                WindowManagerPolicy.ScreenOnListener listener = this.mScreenOnListener;
                this.mScreenOnListener = null;
                this.mScreenOnFully = true;
                if (!this.mKeyguardDrawnOnce && this.mAwake) {
                    this.mKeyguardDrawnOnce = true;
                    enableScreen = true;
                    if (this.mBootMessageNeedsHiding) {
                        this.mBootMessageNeedsHiding = false;
                        hideBootMessages();
                    }
                } else {
                    enableScreen = false;
                }
                if (listener != null) {
                    listener.onScreenOn();
                    if (DEBUG_WAKEUP) {
                        Slog.i(TAG, "finishWindowsDrawn to listener onScreenOn");
                    }
                } else if (DEBUG_WAKEUP) {
                    Slog.i(TAG, "finishWindowsDrawn no listener onScreenOn");
                }
                if (enableScreen) {
                    try {
                        this.mWindowManager.enableScreenIfNeeded();
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleHideBootMessage() {
        synchronized (this.mLock) {
            if (!this.mKeyguardDrawnOnce) {
                this.mBootMessageNeedsHiding = true;
                return;
            }
            if (!allowFinishBootMessages()) {
                xpBootManagerPolicy.get(this.mContext).setBootMessagesDialog(this.mBootMsgDialog, true);
            }
            if (this.mBootMsgDialog != null && allowFinishBootMessages()) {
                if (DEBUG_WAKEUP) {
                    Slog.i(TAG, "handleHideBootMessage: dismissing");
                }
                this.mBootMsgDialog.dismiss();
                this.mBootMsgDialog = null;
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isScreenOn() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mScreenOnEarly;
        }
        return z;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean okToAnimate() {
        return this.mAwake && !this.mGoingToSleep;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void enableKeyguard(boolean enabled) {
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.setKeyguardEnabled(enabled);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void exitKeyguardSecurely(WindowManagerPolicy.OnKeyguardExitResult callback) {
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.verifyUnlock(callback);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardShowingAndNotOccluded() {
        return (this.mKeyguardDelegate == null || !this.mKeyguardDelegate.isShowing() || this.mKeyguardOccluded) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardTrustedLw() {
        if (this.mKeyguardDelegate == null) {
            return false;
        }
        return this.mKeyguardDelegate.isTrusted();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardLocked() {
        return keyguardOn();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardSecure(int userId) {
        if (this.mKeyguardDelegate == null) {
            return false;
        }
        return this.mKeyguardDelegate.isSecure(userId);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardOccluded() {
        if (this.mKeyguardDelegate == null) {
            return false;
        }
        return this.mKeyguardOccluded;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean inKeyguardRestrictedKeyInputMode() {
        if (this.mKeyguardDelegate == null) {
            return false;
        }
        return this.mKeyguardDelegate.isInputRestricted();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void dismissKeyguardLw(IKeyguardDismissCallback callback, CharSequence message) {
        if (this.mKeyguardDelegate != null && this.mKeyguardDelegate.isShowing()) {
            this.mKeyguardDelegate.dismiss(callback, message);
        } else if (callback != null) {
            try {
                callback.onDismissError();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call callback", e);
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isKeyguardDrawnLw() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mKeyguardDrawnOnce;
        }
        return z;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isShowingDreamLw() {
        return this.mShowingDream;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.startKeyguardExitAnimation(startTime, fadeoutDuration);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight, DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();
        getNonDecorInsetsLw(displayRotation, displayWidth, displayHeight, displayCutout, outInsets);
        outInsets.top = Math.max(outInsets.top, this.mStatusBarHeightForRotation[displayRotation]);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight, DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();
        if (this.mHasNavigationBar) {
            int position = navigationBarPosition(displayWidth, displayHeight, displayRotation);
            if (position == 4) {
                outInsets.bottom = getNavigationBarHeight(displayRotation, this.mUiMode);
            } else if (position == 2) {
                outInsets.right = getNavigationBarWidth(displayRotation, this.mUiMode);
            } else if (position == 1) {
                outInsets.left = getNavigationBarWidth(displayRotation, this.mUiMode);
            }
        }
        if (displayCutout != null) {
            outInsets.left += displayCutout.getSafeInsetLeft();
            outInsets.top += displayCutout.getSafeInsetTop();
            outInsets.right += displayCutout.getSafeInsetRight();
            outInsets.bottom += displayCutout.getSafeInsetBottom();
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isNavBarForcedShownLw(WindowManagerPolicy.WindowState windowState) {
        return this.mForceShowSystemBars;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getNavBarPosition() {
        return this.mNavigationBarPosition;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isDockSideAllowed(int dockSide, int originalDockSide, int displayWidth, int displayHeight, int displayRotation) {
        int barPosition = navigationBarPosition(displayWidth, displayHeight, displayRotation);
        return isDockSideAllowed(dockSide, originalDockSide, barPosition, this.mNavigationBarCanMove);
    }

    @VisibleForTesting
    static boolean isDockSideAllowed(int dockSide, int originalDockSide, int navBarPosition, boolean navigationBarCanMove) {
        if (dockSide == 2) {
            return true;
        }
        if (navigationBarCanMove) {
            if (dockSide == 1 && navBarPosition == 2) {
                return true;
            }
            return dockSide == 3 && navBarPosition == 1;
        } else if (dockSide == originalDockSide) {
            return true;
        } else {
            return dockSide == 1 && originalDockSide == 2;
        }
    }

    void sendCloseSystemWindows() {
        PhoneWindow.sendCloseSystemWindows(this.mContext, (String) null);
    }

    void sendCloseSystemWindows(String reason) {
        PhoneWindow.sendCloseSystemWindows(this.mContext, reason);
    }

    /* JADX WARN: Removed duplicated region for block: B:109:0x00ea  */
    /* JADX WARN: Removed duplicated region for block: B:112:0x00ee A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:119:0x00fa A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:126:0x0106 A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:137:0x011a A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:148:0x012e A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:155:0x013a A[Catch: all -> 0x0149, TryCatch #0 {, blocks: (B:7:0x0009, B:108:0x00e7, B:110:0x00ec, B:163:0x0147, B:112:0x00ee, B:114:0x00f4, B:116:0x00f6, B:117:0x00f8, B:119:0x00fa, B:121:0x0100, B:123:0x0102, B:124:0x0104, B:126:0x0106, B:128:0x010c, B:130:0x010e, B:132:0x0114, B:134:0x0116, B:135:0x0118, B:137:0x011a, B:139:0x0120, B:141:0x0122, B:143:0x0128, B:145:0x012a, B:146:0x012c, B:148:0x012e, B:150:0x0134, B:152:0x0136, B:153:0x0138, B:155:0x013a, B:157:0x0140, B:159:0x0142, B:160:0x0144, B:14:0x0018, B:16:0x001d, B:18:0x0021, B:19:0x0024, B:21:0x0029, B:23:0x002d, B:25:0x0031, B:28:0x0037, B:30:0x003a, B:32:0x003f, B:34:0x0044, B:41:0x0052, B:43:0x0056, B:45:0x005a, B:46:0x005d, B:48:0x0061, B:50:0x0065, B:52:0x0069, B:53:0x006c, B:55:0x0070, B:56:0x0073, B:58:0x0077, B:62:0x0080, B:65:0x0086, B:87:0x00b3, B:89:0x00b7, B:93:0x00c9, B:95:0x00cd, B:81:0x00a7, B:85:0x00ae, B:36:0x0048, B:38:0x004c, B:102:0x00dc, B:105:0x00e2), top: B:168:0x0009 }] */
    @Override // com.android.server.policy.WindowManagerPolicy
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int rotationForOrientationLw(int r11, int r12, boolean r13) {
        /*
            Method dump skipped, instructions count: 362
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.PhoneWindowManager.rotationForOrientationLw(int, int, boolean):int");
    }

    /* JADX WARN: Removed duplicated region for block: B:6:0x0008  */
    /* JADX WARN: Removed duplicated region for block: B:8:0x000d  */
    @Override // com.android.server.policy.WindowManagerPolicy
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean rotationHasCompatibleMetricsLw(int r2, int r3) {
        /*
            r1 = this;
            switch(r2) {
                case 0: goto Ld;
                case 1: goto L8;
                default: goto L3;
            }
        L3:
            switch(r2) {
                case 6: goto Ld;
                case 7: goto L8;
                case 8: goto Ld;
                case 9: goto L8;
                default: goto L6;
            }
        L6:
            r0 = 1
            return r0
        L8:
            boolean r0 = r1.isAnyPortrait(r3)
            return r0
        Ld:
            boolean r0 = r1.isLandscapeOrSeascape(r3)
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.PhoneWindowManager.rotationHasCompatibleMetricsLw(int, int):boolean");
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setRotationLw(int rotation) {
        this.mOrientationListener.setCurrentRotation(rotation);
    }

    public boolean isRotationChoicePossible(int orientation) {
        if (this.mUserRotationMode == 1 && !this.mForceDefaultOrientation) {
            if (this.mLidState != 1 || this.mLidOpenRotation < 0) {
                if (this.mDockMode != 2 || this.mCarDockEnablesAccelerometer) {
                    if ((this.mDockMode == 1 || this.mDockMode == 3 || this.mDockMode == 4) && !this.mDeskDockEnablesAccelerometer) {
                        return false;
                    }
                    if (this.mHdmiPlugged && this.mDemoHdmiRotationLock) {
                        return false;
                    }
                    if ((this.mHdmiPlugged && this.mDockMode == 0 && this.mUndockedHdmiRotation >= 0) || this.mDemoRotationLock || this.mPersistentVrModeEnabled || !this.mSupportAutoRotation) {
                        return false;
                    }
                    if (orientation != -1 && orientation != 2) {
                        switch (orientation) {
                            case 11:
                            case 12:
                            case 13:
                                break;
                            default:
                                return false;
                        }
                    }
                    return true;
                }
                return false;
            }
            return false;
        }
        return false;
    }

    public boolean isValidRotationChoice(int orientation, int preferredRotation) {
        if (orientation == -1 || orientation == 2) {
            return preferredRotation >= 0 && preferredRotation != this.mUpsideDownRotation;
        }
        switch (orientation) {
            case 11:
                return isLandscapeOrSeascape(preferredRotation);
            case 12:
                return preferredRotation == this.mPortraitRotation;
            case 13:
                return preferredRotation >= 0;
            default:
                return false;
        }
    }

    private boolean isLandscapeOrSeascape(int rotation) {
        return rotation == this.mLandscapeRotation || rotation == this.mSeascapeRotation;
    }

    private boolean isAnyPortrait(int rotation) {
        return rotation == this.mPortraitRotation || rotation == this.mUpsideDownRotation;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getUserRotationMode() {
        return Settings.System.getIntForUser(this.mContext.getContentResolver(), "accelerometer_rotation", 0, -2) != 0 ? 0 : 1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setUserRotationMode(int mode, int rot) {
        ContentResolver res = this.mContext.getContentResolver();
        if (mode != 1) {
            Settings.System.putIntForUser(res, "accelerometer_rotation", 1, -2);
            return;
        }
        Settings.System.putIntForUser(res, "user_rotation", rot, -2);
        Settings.System.putIntForUser(res, "accelerometer_rotation", 0, -2);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setSafeMode(boolean safeMode) {
        this.mSafeMode = safeMode;
        if (safeMode) {
            performHapticFeedbackLw(null, 10001, true);
        }
    }

    static long[] getLongIntArray(Resources r, int resid) {
        return ArrayUtils.convertToLongArray(r.getIntArray(resid));
    }

    private void bindKeyguard() {
        synchronized (this.mLock) {
            if (this.mKeyguardBound) {
                return;
            }
            this.mKeyguardBound = true;
            this.mKeyguardDelegate.bindService(this.mContext);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onSystemUiStarted() {
        bindKeyguard();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void systemReady() {
        this.mKeyguardDelegate.onSystemReady();
        this.mVrManagerInternal = (VrManagerInternal) LocalServices.getService(VrManagerInternal.class);
        if (this.mVrManagerInternal != null) {
            this.mVrManagerInternal.addPersistentVrModeStateListener(this.mPersistentVrModeListener);
        }
        readCameraLensCoverState();
        updateUiMode();
        synchronized (this.mLock) {
            updateOrientationListenerLp();
            this.mSystemReady = true;
            this.mHandler.post(new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.17
                @Override // java.lang.Runnable
                public void run() {
                    PhoneWindowManager.this.updateSettings();
                }
            });
            if (this.mSystemBooted) {
                this.mKeyguardDelegate.onBootCompleted();
            }
        }
        this.mSystemGestures.systemReady();
        this.mImmersiveModeConfirmation.systemReady();
        this.mAutofillManagerInternal = (AutofillManagerInternal) LocalServices.getService(AutofillManagerInternal.class);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void systemBooted() {
        bindKeyguard();
        synchronized (this.mLock) {
            this.mSystemBooted = true;
            if (this.mSystemReady) {
                this.mKeyguardDelegate.onBootCompleted();
            }
        }
        startedWakingUp();
        screenTurningOn(null);
        screenTurnedOn();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean canDismissBootAnimation() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mKeyguardDrawComplete;
        }
        return z;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void showBootMessage(final CharSequence msg, boolean always) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.18
            @Override // java.lang.Runnable
            public void run() {
                int theme;
                if (PhoneWindowManager.this.mBootMsgDialog == null) {
                    if (PhoneWindowManager.this.mContext.getPackageManager().hasSystemFeature("android.software.leanback")) {
                        theme = 16974839;
                    } else {
                        theme = 0;
                    }
                    PhoneWindowManager.this.mBootMsgDialog = new BootProgressDialog(PhoneWindowManager.this.mContext, theme) { // from class: com.android.server.policy.PhoneWindowManager.18.1
                        public boolean dispatchKeyEvent(KeyEvent event) {
                            return true;
                        }

                        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
                            return true;
                        }

                        public boolean dispatchTouchEvent(MotionEvent ev) {
                            return true;
                        }

                        public boolean dispatchTrackballEvent(MotionEvent ev) {
                            return true;
                        }

                        public boolean dispatchGenericMotionEvent(MotionEvent ev) {
                            return true;
                        }

                        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
                            return true;
                        }
                    };
                    if (PhoneWindowManager.this.mContext.getPackageManager().isUpgrade()) {
                        PhoneWindowManager.this.mBootMsgDialog.setTitle(17039479);
                    } else {
                        PhoneWindowManager.this.mBootMsgDialog.setTitle(17039472);
                    }
                    PhoneWindowManager.this.mBootMsgDialog.getWindow().setType(2021);
                    PhoneWindowManager.this.mBootMsgDialog.getWindow().addFlags(258);
                    PhoneWindowManager.this.mBootMsgDialog.getWindow().setDimAmount(1.0f);
                    WindowManager.LayoutParams lp = PhoneWindowManager.this.mBootMsgDialog.getWindow().getAttributes();
                    lp.screenOrientation = 5;
                    PhoneWindowManager.this.mBootMsgDialog.getWindow().setAttributes(lp);
                    PhoneWindowManager.this.mBootMsgDialog.setCancelable(false);
                    PhoneWindowManager.this.mBootMsgDialog.show();
                }
                PhoneWindowManager.this.mBootMsgDialog.setMessage(msg);
            }
        });
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void hideBootMessages() {
        this.mHandler.sendEmptyMessage(11);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void requestUserActivityNotification() {
        if (!this.mNotifyUserActivity && !this.mHandler.hasMessages(29)) {
            this.mNotifyUserActivity = true;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void userActivity() {
        synchronized (this.mScreenLockTimeout) {
            if (this.mLockScreenTimerActive) {
                this.mHandler.removeCallbacks(this.mScreenLockTimeout);
                this.mHandler.postDelayed(this.mScreenLockTimeout, this.mLockScreenTimeout);
            }
        }
        if (this.mAwake && this.mNotifyUserActivity) {
            this.mHandler.sendEmptyMessageDelayed(29, 200L);
            this.mNotifyUserActivity = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ScreenLockTimeout implements Runnable {
        Bundle options;

        ScreenLockTimeout() {
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (this) {
                if (PhoneWindowManager.this.mKeyguardDelegate != null) {
                    PhoneWindowManager.this.mKeyguardDelegate.doKeyguardTimeout(this.options);
                }
                PhoneWindowManager.this.mLockScreenTimerActive = false;
                this.options = null;
            }
        }

        public void setLockOptions(Bundle options) {
            this.options = options;
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void lockNow(Bundle options) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
        this.mHandler.removeCallbacks(this.mScreenLockTimeout);
        if (options != null) {
            this.mScreenLockTimeout.setLockOptions(options);
        }
        this.mHandler.post(this.mScreenLockTimeout);
    }

    private void updateLockScreenTimeout() {
        synchronized (this.mScreenLockTimeout) {
            boolean enable = this.mAllowLockscreenWhenOn && this.mAwake && this.mKeyguardDelegate != null && this.mKeyguardDelegate.isSecure(this.mCurrentUserId);
            if (this.mLockScreenTimerActive != enable) {
                if (enable) {
                    this.mHandler.removeCallbacks(this.mScreenLockTimeout);
                    this.mHandler.postDelayed(this.mScreenLockTimeout, this.mLockScreenTimeout);
                } else {
                    this.mHandler.removeCallbacks(this.mScreenLockTimeout);
                }
                this.mLockScreenTimerActive = enable;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateDreamingSleepToken(boolean acquire) {
        if (acquire) {
            if (this.mDreamingSleepToken == null) {
                this.mDreamingSleepToken = this.mActivityManagerInternal.acquireSleepToken("Dream", 0);
            }
        } else if (this.mDreamingSleepToken != null) {
            this.mDreamingSleepToken.release();
            this.mDreamingSleepToken = null;
        }
    }

    private void updateScreenOffSleepToken(boolean acquire) {
        xpLogger.i(TAG, "updateScreenOffSleepToken acquire=" + acquire + " token=" + this.mScreenOffSleepToken);
        synchronized (this.mScreenLock) {
            try {
                if (acquire) {
                    if (this.mScreenOffSleepToken == null) {
                        this.mScreenOffSleepToken = this.mActivityManagerInternal.acquireSleepToken("ScreenOff", 0);
                    }
                } else if (this.mScreenOffSleepToken != null) {
                    this.mScreenOffSleepToken.release();
                    this.mScreenOffSleepToken = null;
                }
                xpLogger.i(TAG, "updateScreenOffSleepToken complete");
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void enableScreenAfterBoot() {
        readLidState();
        applyLidSwitchState();
        updateRotation(true);
    }

    private void applyLidSwitchState() {
        if (this.mLidState == 0 && this.mLidControlsSleep) {
            goToSleep(SystemClock.uptimeMillis(), 3, 1);
        } else if (this.mLidState == 0 && this.mLidControlsScreenLock) {
            this.mWindowManagerFuncs.lockDeviceNow();
        }
        synchronized (this.mLock) {
            updateWakeGestureListenerLp();
        }
    }

    void updateUiMode() {
        if (this.mUiModeManager == null) {
            this.mUiModeManager = IUiModeManager.Stub.asInterface(ServiceManager.getService("uimode"));
        }
        try {
            this.mUiMode = this.mUiModeManager.getCurrentModeType();
        } catch (RemoteException e) {
        }
    }

    void updateRotation(boolean alwaysSendConfiguration) {
        try {
            this.mWindowManager.updateRotation(alwaysSendConfiguration, false);
        } catch (RemoteException e) {
        }
    }

    void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout) {
        try {
            this.mWindowManager.updateRotation(alwaysSendConfiguration, forceRelayout);
        } catch (RemoteException e) {
        }
    }

    Intent createHomeDockIntent() {
        Intent intent = null;
        if (this.mUiMode == 3) {
            if (this.mEnableCarDockHomeCapture) {
                intent = this.mCarDockIntent;
            }
        } else if (this.mUiMode != 2) {
            if (this.mUiMode == 6 && (this.mDockMode == 1 || this.mDockMode == 4 || this.mDockMode == 3)) {
                intent = this.mDeskDockIntent;
            } else if (this.mUiMode == 7) {
                intent = this.mVrHeadsetHomeIntent;
            }
        }
        if (intent == null) {
            return null;
        }
        ActivityInfo ai = null;
        ResolveInfo info = this.mContext.getPackageManager().resolveActivityAsUser(intent, 65664, this.mCurrentUserId);
        if (info != null) {
            ai = info.activityInfo;
        }
        if (ai == null || ai.metaData == null || !ai.metaData.getBoolean("android.dock_home")) {
            return null;
        }
        Intent intent2 = new Intent(intent);
        intent2.setClassName(ai.packageName, ai.name);
        return intent2;
    }

    void startDockOrHome(boolean fromHomeKey, boolean awakenFromDreams) {
        Intent intent;
        try {
            ActivityManager.getService().stopAppSwitches();
        } catch (RemoteException e) {
        }
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);
        if (awakenFromDreams) {
            awakenDreams();
        }
        Intent dock = createHomeDockIntent();
        if (dock != null) {
            if (fromHomeKey) {
                try {
                    dock.putExtra("android.intent.extra.FROM_HOME_KEY", fromHomeKey);
                } catch (ActivityNotFoundException e2) {
                }
            }
            startActivityAsUser(dock, UserHandle.CURRENT);
            return;
        }
        if (fromHomeKey) {
            intent = new Intent(this.mHomeIntent);
            intent.putExtra("android.intent.extra.FROM_HOME_KEY", fromHomeKey);
        } else {
            intent = this.mHomeIntent;
        }
        startActivityAsUser(intent, UserHandle.CURRENT);
    }

    boolean goHome() {
        int result;
        if (!isUserSetupComplete()) {
            Slog.i(TAG, "Not going home because user setup is in progress.");
            return false;
        }
        try {
            if (SystemProperties.getInt("persist.sys.uts-test-mode", 0) == 1) {
                Log.d(TAG, "UTS-TEST-MODE");
            } else {
                ActivityManager.getService().stopAppSwitches();
                sendCloseSystemWindows();
                Intent dock = createHomeDockIntent();
                if (dock != null) {
                    int result2 = ActivityManager.getService().startActivityAsUser((IApplicationThread) null, (String) null, dock, dock.resolveTypeIfNeeded(this.mContext.getContentResolver()), (IBinder) null, (String) null, 0, 1, (ProfilerInfo) null, (Bundle) null, -2);
                    if (result2 == 1) {
                        return false;
                    }
                }
            }
            result = ActivityManager.getService().startActivityAsUser((IApplicationThread) null, (String) null, this.mHomeIntent, this.mHomeIntent.resolveTypeIfNeeded(this.mContext.getContentResolver()), (IBinder) null, (String) null, 0, 1, (ProfilerInfo) null, (Bundle) null, -2);
        } catch (RemoteException e) {
        }
        return result != 1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setCurrentOrientationLw(int newOrientation) {
        synchronized (this.mLock) {
            if (newOrientation != this.mCurrentAppOrientation) {
                this.mCurrentAppOrientation = newOrientation;
                updateOrientationListenerLp();
            }
        }
    }

    private boolean isTheaterModeEnabled() {
        return Settings.Global.getInt(this.mContext.getContentResolver(), "theater_mode_on", 0) == 1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean performHapticFeedbackLw(WindowManagerPolicy.WindowState win, int effectId, boolean always) {
        VibrationEffect effect;
        int owningUid;
        String owningPackage;
        if (this.mVibrator.hasVibrator()) {
            boolean hapticsDisabled = Settings.System.getIntForUser(this.mContext.getContentResolver(), "haptic_feedback_enabled", 0, -2) == 0;
            if ((!hapticsDisabled || always) && (effect = getVibrationEffect(effectId)) != null) {
                if (win != null) {
                    owningUid = win.getOwningUid();
                    owningPackage = win.getOwningPackage();
                } else {
                    owningUid = Process.myUid();
                    owningPackage = this.mContext.getOpPackageName();
                }
                this.mVibrator.vibrate(owningUid, owningPackage, effect, VIBRATION_ATTRIBUTES);
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX WARN: Removed duplicated region for block: B:15:0x0021  */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0026  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private android.os.VibrationEffect getVibrationEffect(int r6) {
        /*
            r5 = this;
            r0 = 10001(0x2711, float:1.4014E-41)
            r1 = 1
            r2 = 0
            r3 = 0
            if (r6 == r0) goto L2c
            switch(r6) {
                case 0: goto L26;
                case 1: goto L21;
                default: goto La;
            }
        La:
            r0 = 2
            switch(r6) {
                case 3: goto L21;
                case 4: goto L1c;
                case 5: goto L19;
                case 6: goto L1c;
                case 7: goto L14;
                case 8: goto L14;
                case 9: goto L14;
                case 10: goto L14;
                case 11: goto L14;
                case 12: goto L21;
                case 13: goto L14;
                case 14: goto L26;
                case 15: goto L21;
                case 16: goto L21;
                case 17: goto Lf;
                default: goto Le;
            }
        Le:
            return r2
        Lf:
            android.os.VibrationEffect r0 = android.os.VibrationEffect.get(r1)
            return r0
        L14:
            android.os.VibrationEffect r0 = android.os.VibrationEffect.get(r0, r3)
            return r0
        L19:
            long[] r0 = r5.mCalendarDateVibePattern
            goto L2f
        L1c:
            android.os.VibrationEffect r0 = android.os.VibrationEffect.get(r0)
            return r0
        L21:
            android.os.VibrationEffect r0 = android.os.VibrationEffect.get(r3)
            return r0
        L26:
            r0 = 5
            android.os.VibrationEffect r0 = android.os.VibrationEffect.get(r0)
            return r0
        L2c:
            long[] r0 = r5.mSafeModeEnabledVibePattern
        L2f:
            int r4 = r0.length
            if (r4 != 0) goto L34
            return r2
        L34:
            int r2 = r0.length
            r4 = -1
            if (r2 != r1) goto L3f
            r1 = r0[r3]
            android.os.VibrationEffect r1 = android.os.VibrationEffect.createOneShot(r1, r4)
            return r1
        L3f:
            android.os.VibrationEffect r1 = android.os.VibrationEffect.createWaveform(r0, r4)
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.PhoneWindowManager.getVibrationEffect(int):android.os.VibrationEffect");
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void keepScreenOnStartedLw() {
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void keepScreenOnStoppedLw() {
        if (isKeyguardShowingAndNotOccluded()) {
            this.mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int updateSystemUiVisibilityLw() {
        WindowManagerPolicy.WindowState winCandidate;
        if (this.mFocusedWindow != null) {
            winCandidate = this.mFocusedWindow;
        } else {
            winCandidate = this.mTopFullscreenOpaqueWindowState;
        }
        if (winCandidate != null) {
            if (winCandidate.getAttrs().token == this.mImmersiveModeConfirmation.getWindowToken()) {
                winCandidate = isStatusBarKeyguard() ? this.mStatusBar : this.mTopFullscreenOpaqueWindowState;
                if (winCandidate == null) {
                    return 0;
                }
            }
            final WindowManagerPolicy.WindowState winCandidate2 = winCandidate;
            if ((winCandidate2.getAttrs().privateFlags & 1024) == 0 || !this.mKeyguardOccluded) {
                int tmpVisibility = PolicyControl.getSystemUiVisibility(winCandidate2, null) & (~this.mResettingSystemUiFlags) & (~this.mForceClearedSystemUiFlags);
                if (this.mForcingShowNavBar && winCandidate2.getSurfaceLayer() < this.mForcingShowNavBarLayer) {
                    tmpVisibility &= ~PolicyControl.adjustClearableFlags(winCandidate2, 7);
                }
                final int fullscreenVisibility = updateLightStatusBarLw(0, this.mTopFullscreenOpaqueWindowState, this.mTopFullscreenOpaqueOrDimmingWindowState);
                final int dockedVisibility = updateLightStatusBarLw(0, this.mTopDockedOpaqueWindowState, this.mTopDockedOpaqueOrDimmingWindowState);
                this.mWindowManagerFuncs.getStackBounds(0, 2, this.mNonDockedStackBounds);
                this.mWindowManagerFuncs.getStackBounds(3, 1, this.mDockedStackBounds);
                final int visibility = updateSystemBarsLw(winCandidate2, this.mLastSystemUiFlags, tmpVisibility);
                int diff = visibility ^ this.mLastSystemUiFlags;
                int fullscreenDiff = fullscreenVisibility ^ this.mLastFullscreenStackSysUiFlags;
                int dockedDiff = dockedVisibility ^ this.mLastDockedStackSysUiFlags;
                final boolean needsMenu = winCandidate2.getNeedsMenuLw(this.mTopFullscreenOpaqueWindowState);
                if (diff == 0 && fullscreenDiff == 0 && dockedDiff == 0 && this.mLastFocusNeedsMenu == needsMenu && this.mFocusedApp == winCandidate2.getAppToken() && this.mLastNonDockedStackBounds.equals(this.mNonDockedStackBounds) && this.mLastDockedStackBounds.equals(this.mDockedStackBounds)) {
                    return 0;
                }
                this.mLastSystemUiFlags = visibility;
                this.mLastFullscreenStackSysUiFlags = fullscreenVisibility;
                this.mLastDockedStackSysUiFlags = dockedVisibility;
                this.mLastFocusNeedsMenu = needsMenu;
                this.mFocusedApp = winCandidate2.getAppToken();
                final Rect fullscreenStackBounds = new Rect(this.mNonDockedStackBounds);
                final Rect dockedStackBounds = new Rect(this.mDockedStackBounds);
                this.mHandler.post(new Runnable() { // from class: com.android.server.policy.PhoneWindowManager.19
                    @Override // java.lang.Runnable
                    public void run() {
                        StatusBarManagerInternal statusbar = PhoneWindowManager.this.getStatusBarManagerInternal();
                        if (statusbar != null) {
                            statusbar.setSystemUiVisibility(visibility, fullscreenVisibility, dockedVisibility, -1, fullscreenStackBounds, dockedStackBounds, winCandidate2.toString());
                            statusbar.topAppWindowChanged(needsMenu);
                        }
                    }
                });
                return diff;
            }
            return 0;
        }
        return 0;
    }

    private int updateLightStatusBarLw(int vis, WindowManagerPolicy.WindowState opaque, WindowManagerPolicy.WindowState opaqueOrDimming) {
        boolean onKeyguard = isStatusBarKeyguard() && !this.mKeyguardOccluded;
        WindowManagerPolicy.WindowState statusColorWin = onKeyguard ? this.mStatusBar : opaqueOrDimming;
        if (statusColorWin != null && (statusColorWin == opaque || onKeyguard)) {
            return (vis & (-8193)) | (PolicyControl.getSystemUiVisibility(statusColorWin, null) & 8192);
        }
        if (statusColorWin != null && statusColorWin.isDimming()) {
            return vis & (-8193);
        }
        return vis;
    }

    @VisibleForTesting
    static WindowManagerPolicy.WindowState chooseNavigationColorWindowLw(WindowManagerPolicy.WindowState opaque, WindowManagerPolicy.WindowState opaqueOrDimming, WindowManagerPolicy.WindowState imeWindow, int navBarPosition) {
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
    static int updateLightNavigationBarLw(int vis, WindowManagerPolicy.WindowState opaque, WindowManagerPolicy.WindowState opaqueOrDimming, WindowManagerPolicy.WindowState imeWindow, WindowManagerPolicy.WindowState navColorWin) {
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

    private int updateSystemBarsLw(WindowManagerPolicy.WindowState win, int oldVis, int vis) {
        WindowManagerPolicy.WindowState fullscreenTransWin;
        boolean dockedStackVisible = this.mWindowManagerInternal.isStackVisible(3);
        boolean freeformStackVisible = this.mWindowManagerInternal.isStackVisible(5);
        boolean resizing = this.mWindowManagerInternal.isDockedDividerResizing();
        this.mForceShowSystemBars = dockedStackVisible || freeformStackVisible || resizing;
        boolean forceOpaqueStatusBar = this.mForceShowSystemBars && !this.mForceStatusBarFromKeyguard;
        if (isStatusBarKeyguard() && !this.mKeyguardOccluded) {
            fullscreenTransWin = this.mStatusBar;
        } else {
            fullscreenTransWin = this.mTopFullscreenOpaqueWindowState;
        }
        int vis2 = this.mNavigationBarController.applyTranslucentFlagLw(fullscreenTransWin, this.mStatusBarController.applyTranslucentFlagLw(fullscreenTransWin, vis, oldVis), oldVis);
        int dockedVis = this.mStatusBarController.applyTranslucentFlagLw(this.mTopDockedOpaqueWindowState, 0, 0);
        boolean fullscreenDrawsStatusBarBackground = drawsStatusBarBackground(vis2, this.mTopFullscreenOpaqueWindowState);
        boolean dockedDrawsStatusBarBackground = drawsStatusBarBackground(dockedVis, this.mTopDockedOpaqueWindowState);
        int type = win.getAttrs().type;
        boolean statusBarHasFocus = type == 2000;
        if (statusBarHasFocus && !isStatusBarKeyguard()) {
            int flags = this.mKeyguardOccluded ? 14342 | (-1073741824) : 14342;
            vis2 = ((~flags) & vis2) | (oldVis & flags);
        }
        if (fullscreenDrawsStatusBarBackground && dockedDrawsStatusBarBackground) {
            vis2 = (-1073741825) & (vis2 | 8);
        } else if ((!areTranslucentBarsAllowed() && fullscreenTransWin != this.mStatusBar) || forceOpaqueStatusBar) {
            vis2 &= -1073741833;
        }
        int vis3 = configureNavBarOpacity(vis2, dockedStackVisible, freeformStackVisible, resizing);
        boolean immersiveSticky = (vis3 & 4096) != 0;
        boolean hideStatusBarWM = (this.mTopFullscreenOpaqueWindowState == null || (PolicyControl.getWindowFlags(this.mTopFullscreenOpaqueWindowState, null) & 1024) == 0) ? false : true;
        boolean hideStatusBarSysui = (vis3 & 4) != 0;
        boolean hideNavBarSysui = (vis3 & 2) != 0;
        boolean transientStatusBarAllowed = this.mStatusBar != null && (statusBarHasFocus || (!this.mForceShowSystemBars && (hideStatusBarWM || (hideStatusBarSysui && immersiveSticky))));
        boolean transientNavBarAllowed = this.mNavigationBar != null && !this.mForceShowSystemBars && hideNavBarSysui && immersiveSticky;
        long now = SystemClock.uptimeMillis();
        boolean pendingPanic = this.mPendingPanicGestureUptime != 0 && now - this.mPendingPanicGestureUptime <= 30000;
        if (pendingPanic && hideNavBarSysui && !isStatusBarKeyguard() && this.mKeyguardDrawComplete) {
            this.mPendingPanicGestureUptime = 0L;
            this.mStatusBarController.showTransient();
            if (!isNavBarEmpty(vis3)) {
                this.mNavigationBarController.showTransient();
            }
        }
        boolean denyTransientStatus = this.mStatusBarController.isTransientShowRequested() && !transientStatusBarAllowed && hideStatusBarSysui;
        boolean denyTransientNav = this.mNavigationBarController.isTransientShowRequested() && !transientNavBarAllowed;
        if (denyTransientStatus || denyTransientNav || this.mForceShowSystemBars) {
            clearClearableFlagsLw();
            vis3 &= -8;
        }
        boolean immersive = (vis3 & 2048) != 0;
        boolean navAllowedHidden = immersive || ((vis3 & 4096) != 0);
        if (hideNavBarSysui && !navAllowedHidden) {
            if (getWindowLayerLw(win) > getWindowLayerFromTypeLw(2022)) {
                vis3 &= -3;
            }
        }
        int vis4 = this.mStatusBarController.updateVisibilityLw(transientStatusBarAllowed, oldVis, vis3);
        boolean oldImmersiveMode = isImmersiveMode(oldVis);
        boolean newImmersiveMode = isImmersiveMode(vis4);
        if (win != null && oldImmersiveMode != newImmersiveMode) {
            String pkg = win.getOwningPackage();
            ImmersiveModeConfirmation immersiveModeConfirmation = this.mImmersiveModeConfirmation;
            boolean denyTransientStatus2 = isUserSetupComplete();
            boolean denyTransientNav2 = isNavBarEmpty(win.getSystemUiVisibility());
            immersiveModeConfirmation.immersiveModeChangedLw(pkg, newImmersiveMode, denyTransientStatus2, denyTransientNav2);
        }
        int vis5 = this.mNavigationBarController.updateVisibilityLw(transientNavBarAllowed, oldVis, vis4);
        WindowManagerPolicy.WindowState navColorWin = chooseNavigationColorWindowLw(this.mTopFullscreenOpaqueWindowState, this.mTopFullscreenOpaqueOrDimmingWindowState, this.mWindowManagerFuncs.getInputMethodWindowLw(), this.mNavigationBarPosition);
        return updateLightNavigationBarLw(vis5, this.mTopFullscreenOpaqueWindowState, this.mTopFullscreenOpaqueOrDimmingWindowState, this.mWindowManagerFuncs.getInputMethodWindowLw(), navColorWin);
    }

    private boolean drawsStatusBarBackground(int vis, WindowManagerPolicy.WindowState win) {
        if (this.mStatusBarController.isTransparentAllowed(win)) {
            if (win == null) {
                return true;
            }
            boolean drawsSystemBars = (win.getAttrs().flags & Integer.MIN_VALUE) != 0;
            boolean forceDrawsSystemBars = (win.getAttrs().privateFlags & DumpState.DUMP_INTENT_FILTER_VERIFIERS) != 0;
            if (forceDrawsSystemBars) {
                return true;
            }
            return drawsSystemBars && (1073741824 & vis) == 0;
        }
        return false;
    }

    private int configureNavBarOpacity(int visibility, boolean dockedStackVisible, boolean freeformStackVisible, boolean isDockedDividerResizing) {
        if (this.mNavBarOpacityMode == 0) {
            if (dockedStackVisible || freeformStackVisible || isDockedDividerResizing) {
                visibility = setNavBarOpaqueFlag(visibility);
            }
        } else if (this.mNavBarOpacityMode == 1) {
            if (isDockedDividerResizing) {
                visibility = setNavBarOpaqueFlag(visibility);
            } else if (freeformStackVisible) {
                visibility = setNavBarTranslucentFlag(visibility);
            } else {
                visibility = setNavBarOpaqueFlag(visibility);
            }
        }
        if (!areTranslucentBarsAllowed()) {
            return visibility & Integer.MAX_VALUE;
        }
        return visibility;
    }

    private int setNavBarOpaqueFlag(int visibility) {
        return 2147450879 & visibility;
    }

    private int setNavBarTranslucentFlag(int visibility) {
        return Integer.MIN_VALUE | (visibility & (-32769));
    }

    private void clearClearableFlagsLw() {
        int newVal = this.mResettingSystemUiFlags | 7;
        if (newVal != this.mResettingSystemUiFlags) {
            this.mResettingSystemUiFlags = newVal;
            this.mWindowManagerFuncs.reevaluateStatusBarVisibility();
        }
    }

    private boolean isImmersiveMode(int vis) {
        return (this.mNavigationBar == null || (vis & 2) == 0 || (vis & 6144) == 0 || !canHideNavigationBar()) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isNavBarEmpty(int systemUiFlags) {
        return (systemUiFlags & 23068672) == 23068672;
    }

    private boolean areTranslucentBarsAllowed() {
        return this.mTranslucentDecorEnabled;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean hasNavigationBar() {
        return this.mHasNavigationBar;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setLastInputMethodWindowLw(WindowManagerPolicy.WindowState ime, WindowManagerPolicy.WindowState target) {
        this.mLastInputMethodWindow = ime;
        this.mLastInputMethodTargetWindow = target;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setDismissImeOnBackKeyPressed(boolean newValue) {
        this.mDismissImeOnBackKeyPressed = newValue;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setCurrentUserLw(int newUserId) {
        this.mCurrentUserId = newUserId;
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.setCurrentUser(newUserId);
        }
        if (this.mAccessibilityShortcutController != null) {
            this.mAccessibilityShortcutController.setCurrentUser(newUserId);
        }
        StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
        if (statusBar != null) {
            statusBar.setCurrentUser(newUserId);
        }
        setLastInputMethodWindowLw(null, null);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void setSwitchingUser(boolean switching) {
        this.mKeyguardDelegate.setSwitchingUser(switching);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean isTopLevelWindow(int windowType) {
        return windowType < 1000 || windowType > 1999 || windowType == 1003;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean shouldRotateSeamlessly(int oldRotation, int newRotation) {
        WindowManagerPolicy.WindowState w;
        if (oldRotation == this.mUpsideDownRotation || newRotation == this.mUpsideDownRotation || !this.mNavigationBarCanMove) {
            return false;
        }
        int delta = newRotation - oldRotation;
        if (delta < 0) {
            delta += 4;
        }
        return (delta == 2 || (w = this.mTopFullscreenOpaqueWindowState) != this.mFocusedWindow || w == null || w.isAnimatingLw() || (w.getAttrs().rotationAnimation != 2 && w.getAttrs().rotationAnimation != 3)) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, this.mLastSystemUiFlags);
        proto.write(1159641169922L, this.mUserRotationMode);
        proto.write(1159641169923L, this.mUserRotation);
        proto.write(1159641169924L, this.mCurrentAppOrientation);
        proto.write(1133871366149L, this.mScreenOnFully);
        proto.write(1133871366150L, this.mKeyguardDrawComplete);
        proto.write(1133871366151L, this.mWindowManagerDrawComplete);
        if (this.mFocusedApp != null) {
            proto.write(1138166333448L, this.mFocusedApp.toString());
        }
        if (this.mFocusedWindow != null) {
            this.mFocusedWindow.writeIdentifierToProto(proto, 1146756268041L);
        }
        if (this.mTopFullscreenOpaqueWindowState != null) {
            this.mTopFullscreenOpaqueWindowState.writeIdentifierToProto(proto, 1146756268042L);
        }
        if (this.mTopFullscreenOpaqueOrDimmingWindowState != null) {
            this.mTopFullscreenOpaqueOrDimmingWindowState.writeIdentifierToProto(proto, 1146756268043L);
        }
        proto.write(1133871366156L, this.mKeyguardOccluded);
        proto.write(1133871366157L, this.mKeyguardOccludedChanged);
        proto.write(1133871366158L, this.mPendingKeyguardOccluded);
        proto.write(1133871366159L, this.mForceStatusBar);
        proto.write(1133871366160L, this.mForceStatusBarFromKeyguard);
        this.mStatusBarController.writeToProto(proto, 1146756268049L);
        this.mNavigationBarController.writeToProto(proto, 1146756268050L);
        if (this.mOrientationListener != null) {
            this.mOrientationListener.writeToProto(proto, 1146756268051L);
        }
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.writeToProto(proto, 1146756268052L);
        }
        proto.end(token);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void dump(String prefix, PrintWriter pw, String[] args) {
        pw.print(prefix);
        pw.print("mSafeMode=");
        pw.print(this.mSafeMode);
        pw.print(" mSystemReady=");
        pw.print(this.mSystemReady);
        pw.print(" mSystemBooted=");
        pw.println(this.mSystemBooted);
        pw.print(prefix);
        pw.print("mLidState=");
        pw.print(WindowManagerPolicy.WindowManagerFuncs.lidStateToString(this.mLidState));
        pw.print(" mLidOpenRotation=");
        pw.println(Surface.rotationToString(this.mLidOpenRotation));
        pw.print(prefix);
        pw.print("mCameraLensCoverState=");
        pw.print(WindowManagerPolicy.WindowManagerFuncs.cameraLensStateToString(this.mCameraLensCoverState));
        pw.print(" mHdmiPlugged=");
        pw.println(this.mHdmiPlugged);
        if (this.mLastSystemUiFlags != 0 || this.mResettingSystemUiFlags != 0 || this.mForceClearedSystemUiFlags != 0) {
            pw.print(prefix);
            pw.print("mLastSystemUiFlags=0x");
            pw.print(Integer.toHexString(this.mLastSystemUiFlags));
            pw.print(" mResettingSystemUiFlags=0x");
            pw.print(Integer.toHexString(this.mResettingSystemUiFlags));
            pw.print(" mForceClearedSystemUiFlags=0x");
            pw.println(Integer.toHexString(this.mForceClearedSystemUiFlags));
        }
        if (this.mLastFocusNeedsMenu) {
            pw.print(prefix);
            pw.print("mLastFocusNeedsMenu=");
            pw.println(this.mLastFocusNeedsMenu);
        }
        pw.print(prefix);
        pw.print("mWakeGestureEnabledSetting=");
        pw.println(this.mWakeGestureEnabledSetting);
        pw.print(prefix);
        pw.print("mSupportAutoRotation=");
        pw.print(this.mSupportAutoRotation);
        pw.print(" mOrientationSensorEnabled=");
        pw.println(this.mOrientationSensorEnabled);
        pw.print(prefix);
        pw.print("mUiMode=");
        pw.print(Configuration.uiModeToString(this.mUiMode));
        pw.print(" mDockMode=");
        pw.println(Intent.dockStateToString(this.mDockMode));
        pw.print(prefix);
        pw.print("mEnableCarDockHomeCapture=");
        pw.print(this.mEnableCarDockHomeCapture);
        pw.print(" mCarDockRotation=");
        pw.print(Surface.rotationToString(this.mCarDockRotation));
        pw.print(" mDeskDockRotation=");
        pw.println(Surface.rotationToString(this.mDeskDockRotation));
        pw.print(prefix);
        pw.print("mUserRotationMode=");
        pw.print(WindowManagerPolicy.userRotationModeToString(this.mUserRotationMode));
        pw.print(" mUserRotation=");
        pw.print(Surface.rotationToString(this.mUserRotation));
        pw.print(" mAllowAllRotations=");
        pw.println(allowAllRotationsToString(this.mAllowAllRotations));
        pw.print(prefix);
        pw.print("mCurrentAppOrientation=");
        pw.println(ActivityInfo.screenOrientationToString(this.mCurrentAppOrientation));
        pw.print(prefix);
        pw.print("mCarDockEnablesAccelerometer=");
        pw.print(this.mCarDockEnablesAccelerometer);
        pw.print(" mDeskDockEnablesAccelerometer=");
        pw.println(this.mDeskDockEnablesAccelerometer);
        pw.print(prefix);
        pw.print("mLidKeyboardAccessibility=");
        pw.print(this.mLidKeyboardAccessibility);
        pw.print(" mLidNavigationAccessibility=");
        pw.print(this.mLidNavigationAccessibility);
        pw.print(" mLidControlsScreenLock=");
        pw.println(this.mLidControlsScreenLock);
        pw.print(prefix);
        pw.print("mLidControlsSleep=");
        pw.println(this.mLidControlsSleep);
        pw.print(prefix);
        pw.print("mLongPressOnBackBehavior=");
        pw.println(longPressOnBackBehaviorToString(this.mLongPressOnBackBehavior));
        pw.print(prefix);
        pw.print("mLongPressOnHomeBehavior=");
        pw.println(longPressOnHomeBehaviorToString(this.mLongPressOnHomeBehavior));
        pw.print(prefix);
        pw.print("mDoubleTapOnHomeBehavior=");
        pw.println(doubleTapOnHomeBehaviorToString(this.mDoubleTapOnHomeBehavior));
        pw.print(prefix);
        pw.print("mShortPressOnPowerBehavior=");
        pw.println(shortPressOnPowerBehaviorToString(this.mShortPressOnPowerBehavior));
        pw.print(prefix);
        pw.print("mLongPressOnPowerBehavior=");
        pw.println(longPressOnPowerBehaviorToString(this.mLongPressOnPowerBehavior));
        pw.print(prefix);
        pw.print("mVeryLongPressOnPowerBehavior=");
        pw.println(veryLongPressOnPowerBehaviorToString(this.mVeryLongPressOnPowerBehavior));
        pw.print(prefix);
        pw.print("mDoublePressOnPowerBehavior=");
        pw.println(multiPressOnPowerBehaviorToString(this.mDoublePressOnPowerBehavior));
        pw.print(prefix);
        pw.print("mTriplePressOnPowerBehavior=");
        pw.println(multiPressOnPowerBehaviorToString(this.mTriplePressOnPowerBehavior));
        pw.print(prefix);
        pw.print("mShortPressOnSleepBehavior=");
        pw.println(shortPressOnSleepBehaviorToString(this.mShortPressOnSleepBehavior));
        pw.print(prefix);
        pw.print("mShortPressOnWindowBehavior=");
        pw.println(shortPressOnWindowBehaviorToString(this.mShortPressOnWindowBehavior));
        pw.print(prefix);
        pw.print("mAllowStartActivityForLongPressOnPowerDuringSetup=");
        pw.println(this.mAllowStartActivityForLongPressOnPowerDuringSetup);
        pw.print(prefix);
        pw.print("mHasSoftInput=");
        pw.print(this.mHasSoftInput);
        pw.print(" mDismissImeOnBackKeyPressed=");
        pw.println(this.mDismissImeOnBackKeyPressed);
        pw.print(prefix);
        pw.print("mIncallPowerBehavior=");
        pw.print(incallPowerBehaviorToString(this.mIncallPowerBehavior));
        pw.print(" mIncallBackBehavior=");
        pw.print(incallBackBehaviorToString(this.mIncallBackBehavior));
        pw.print(" mEndcallBehavior=");
        pw.println(endcallBehaviorToString(this.mEndcallBehavior));
        pw.print(prefix);
        pw.print("mHomePressed=");
        pw.println(this.mHomePressed);
        pw.print(prefix);
        pw.print("mAwake=");
        pw.print(this.mAwake);
        pw.print("mScreenOnEarly=");
        pw.print(this.mScreenOnEarly);
        pw.print(" mScreenOnFully=");
        pw.println(this.mScreenOnFully);
        pw.print(prefix);
        pw.print("mKeyguardDrawComplete=");
        pw.print(this.mKeyguardDrawComplete);
        pw.print(" mWindowManagerDrawComplete=");
        pw.println(this.mWindowManagerDrawComplete);
        pw.print(prefix);
        pw.print("mDockLayer=");
        pw.print(this.mDockLayer);
        pw.print(" mStatusBarLayer=");
        pw.println(this.mStatusBarLayer);
        pw.print(prefix);
        pw.print("mShowingDream=");
        pw.print(this.mShowingDream);
        pw.print(" mDreamingLockscreen=");
        pw.print(this.mDreamingLockscreen);
        pw.print(" mDreamingSleepToken=");
        pw.println(this.mDreamingSleepToken);
        if (this.mLastInputMethodWindow != null) {
            pw.print(prefix);
            pw.print("mLastInputMethodWindow=");
            pw.println(this.mLastInputMethodWindow);
        }
        if (this.mLastInputMethodTargetWindow != null) {
            pw.print(prefix);
            pw.print("mLastInputMethodTargetWindow=");
            pw.println(this.mLastInputMethodTargetWindow);
        }
        if (this.mStatusBar != null) {
            pw.print(prefix);
            pw.print("mStatusBar=");
            pw.print(this.mStatusBar);
            pw.print(" isStatusBarKeyguard=");
            pw.println(isStatusBarKeyguard());
        }
        if (this.mNavigationBar != null) {
            pw.print(prefix);
            pw.print("mNavigationBar=");
            pw.println(this.mNavigationBar);
        }
        if (this.mFocusedWindow != null) {
            pw.print(prefix);
            pw.print("mFocusedWindow=");
            pw.println(this.mFocusedWindow);
        }
        if (this.mFocusedApp != null) {
            pw.print(prefix);
            pw.print("mFocusedApp=");
            pw.println(this.mFocusedApp);
        }
        if (this.mTopFullscreenOpaqueWindowState != null) {
            pw.print(prefix);
            pw.print("mTopFullscreenOpaqueWindowState=");
            pw.println(this.mTopFullscreenOpaqueWindowState);
        }
        if (this.mTopFullscreenOpaqueOrDimmingWindowState != null) {
            pw.print(prefix);
            pw.print("mTopFullscreenOpaqueOrDimmingWindowState=");
            pw.println(this.mTopFullscreenOpaqueOrDimmingWindowState);
        }
        if (this.mForcingShowNavBar) {
            pw.print(prefix);
            pw.print("mForcingShowNavBar=");
            pw.println(this.mForcingShowNavBar);
            pw.print("mForcingShowNavBarLayer=");
            pw.println(this.mForcingShowNavBarLayer);
        }
        pw.print(prefix);
        pw.print("mTopIsFullscreen=");
        pw.print(this.mTopIsFullscreen);
        pw.print(" mKeyguardOccluded=");
        pw.println(this.mKeyguardOccluded);
        pw.print(prefix);
        pw.print("mKeyguardOccludedChanged=");
        pw.print(this.mKeyguardOccludedChanged);
        pw.print(" mPendingKeyguardOccluded=");
        pw.println(this.mPendingKeyguardOccluded);
        pw.print(prefix);
        pw.print("mForceStatusBar=");
        pw.print(this.mForceStatusBar);
        pw.print(" mForceStatusBarFromKeyguard=");
        pw.println(this.mForceStatusBarFromKeyguard);
        pw.print(prefix);
        pw.print("mAllowLockscreenWhenOn=");
        pw.print(this.mAllowLockscreenWhenOn);
        pw.print(" mLockScreenTimeout=");
        pw.print(this.mLockScreenTimeout);
        pw.print(" mLockScreenTimerActive=");
        pw.println(this.mLockScreenTimerActive);
        pw.print(prefix);
        pw.print("mLandscapeRotation=");
        pw.print(Surface.rotationToString(this.mLandscapeRotation));
        pw.print(" mSeascapeRotation=");
        pw.println(Surface.rotationToString(this.mSeascapeRotation));
        pw.print(prefix);
        pw.print("mPortraitRotation=");
        pw.print(Surface.rotationToString(this.mPortraitRotation));
        pw.print(" mUpsideDownRotation=");
        pw.println(Surface.rotationToString(this.mUpsideDownRotation));
        pw.print(prefix);
        pw.print("mDemoHdmiRotation=");
        pw.print(Surface.rotationToString(this.mDemoHdmiRotation));
        pw.print(" mDemoHdmiRotationLock=");
        pw.println(this.mDemoHdmiRotationLock);
        pw.print(prefix);
        pw.print("mUndockedHdmiRotation=");
        pw.println(Surface.rotationToString(this.mUndockedHdmiRotation));
        if (this.mHasFeatureLeanback) {
            pw.print(prefix);
            pw.print("mAccessibilityTvKey1Pressed=");
            pw.println(this.mAccessibilityTvKey1Pressed);
            pw.print(prefix);
            pw.print("mAccessibilityTvKey2Pressed=");
            pw.println(this.mAccessibilityTvKey2Pressed);
            pw.print(prefix);
            pw.print("mAccessibilityTvScheduled=");
            pw.println(this.mAccessibilityTvScheduled);
        }
        this.mGlobalKeyManager.dump(prefix, pw);
        this.mStatusBarController.dump(pw, prefix);
        this.mNavigationBarController.dump(pw, prefix);
        PolicyControl.dump(prefix, pw);
        if (this.mWakeGestureListener != null) {
            this.mWakeGestureListener.dump(pw, prefix);
        }
        if (this.mOrientationListener != null) {
            this.mOrientationListener.dump(pw, prefix);
        }
        if (this.mBurnInProtectionHelper != null) {
            this.mBurnInProtectionHelper.dump(prefix, pw);
        }
        if (this.mKeyguardDelegate != null) {
            this.mKeyguardDelegate.dump(prefix, pw);
        }
        pw.print(prefix);
        pw.println("Looper state:");
        Looper looper = this.mHandler.getLooper();
        PrintWriterPrinter printWriterPrinter = new PrintWriterPrinter(pw);
        looper.dump(printWriterPrinter, prefix + "  ");
    }

    private static String allowAllRotationsToString(int allowAll) {
        switch (allowAll) {
            case -1:
                return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
            case 0:
                return "false";
            case 1:
                return "true";
            default:
                return Integer.toString(allowAll);
        }
    }

    private static String endcallBehaviorToString(int behavior) {
        StringBuilder sb = new StringBuilder();
        if ((behavior & 1) != 0) {
            sb.append("home|");
        }
        if ((behavior & 2) != 0) {
            sb.append("sleep|");
        }
        int N = sb.length();
        if (N == 0) {
            return "<nothing>";
        }
        return sb.substring(0, N - 1);
    }

    private static String incallPowerBehaviorToString(int behavior) {
        if ((behavior & 2) != 0) {
            return "hangup";
        }
        return "sleep";
    }

    private static String incallBackBehaviorToString(int behavior) {
        if ((behavior & 1) != 0) {
            return "hangup";
        }
        return "<nothing>";
    }

    private static String longPressOnBackBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "LONG_PRESS_BACK_NOTHING";
            case 1:
                return "LONG_PRESS_BACK_GO_TO_VOICE_ASSIST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String longPressOnHomeBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "LONG_PRESS_HOME_NOTHING";
            case 1:
                return "LONG_PRESS_HOME_ALL_APPS";
            case 2:
                return "LONG_PRESS_HOME_ASSIST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String doubleTapOnHomeBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "DOUBLE_TAP_HOME_NOTHING";
            case 1:
                return "DOUBLE_TAP_HOME_RECENT_SYSTEM_UI";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "SHORT_PRESS_POWER_NOTHING";
            case 1:
                return "SHORT_PRESS_POWER_GO_TO_SLEEP";
            case 2:
                return "SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP";
            case 3:
                return "SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME";
            case 4:
                return "SHORT_PRESS_POWER_GO_HOME";
            case 5:
                return "SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String longPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "LONG_PRESS_POWER_NOTHING";
            case 1:
                return "LONG_PRESS_POWER_GLOBAL_ACTIONS";
            case 2:
                return "LONG_PRESS_POWER_SHUT_OFF";
            case 3:
                return "LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String veryLongPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "VERY_LONG_PRESS_POWER_NOTHING";
            case 1:
                return "VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String multiPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "MULTI_PRESS_POWER_NOTHING";
            case 1:
                return "MULTI_PRESS_POWER_THEATER_MODE";
            case 2:
                return "MULTI_PRESS_POWER_BRIGHTNESS_BOOST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnSleepBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "SHORT_PRESS_SLEEP_GO_TO_SLEEP";
            case 1:
                return "SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnWindowBehaviorToString(int behavior) {
        switch (behavior) {
            case 0:
                return "SHORT_PRESS_WINDOW_NOTHING";
            case 1:
                return "SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE";
            default:
                return Integer.toString(behavior);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onLockTaskStateChangedLw(int lockTaskState) {
        this.mImmersiveModeConfirmation.onLockTaskModeChangedLw(lockTaskState);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean setAodShowing(boolean aodShowing) {
        if (this.mAodShowing != aodShowing) {
            this.mAodShowing = aodShowing;
            return true;
        }
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getWindowLayerLw(WindowManagerPolicy.WindowState win) {
        return getWindowLayerFromTypeLw(win.getBaseType(), win.canAddInternalSystemWindow());
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getWindowLayerFromTypeLw(int type) {
        if (WindowManager.LayoutParams.isSystemAlertWindowType(type)) {
            throw new IllegalArgumentException("Use getWindowLayerFromTypeLw() or getWindowLayerLw() for alert window types");
        }
        return getWindowLayerFromTypeLw(type, false);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow) {
        return xpPhoneWindowManager.getWindowLayerFromTypeLw(type, this.mWindowManager);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean allowFinishBootMessages() {
        return xpBootManagerPolicy.get(this.mContext).allowFinishBootMessages();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean allowFinishBootAnimation() {
        return xpBootManagerPolicy.get(this.mContext).allowFinishBootAnimation();
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public void onBootChanged(boolean systemBooted, boolean showingBootMessages) {
        xpBootManagerPolicy.get(this.mContext).onBootChanged(systemBooted, showingBootMessages);
    }

    @Override // com.android.server.policy.WindowManagerPolicy
    public boolean checkBootAnimationCompleted() {
        return xpBootManagerPolicy.get(this.mContext).checkBootAnimationCompleted();
    }
}
