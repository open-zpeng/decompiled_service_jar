package com.android.server.display;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;
import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.RampAnimator;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.server.display.DisplayBrightnessController;
import com.xpeng.server.display.ResumeProfEvent;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class DisplayPowerController implements AutomaticBrightnessController.Callbacks {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;
    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;
    private static final int MSG_CONFIGURE_BRIGHTNESS = 5;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_SCREEN_OFF_UNBLOCKED = 4;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 3;
    private static final int MSG_SET_SCREEN_BLACK_STATE = 8;
    private static final int MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT = 7;
    private static final int MSG_SET_TEMPORARY_BRIGHTNESS = 6;
    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int PROXIMITY_NEGATIVE = 0;
    private static final int PROXIMITY_POSITIVE = 1;
    private static final int PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY = 250;
    private static final int PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY = 0;
    private static final int PROXIMITY_UNKNOWN = -1;
    private static final int RAMP_STATE_SKIP_AUTOBRIGHT = 2;
    private static final int RAMP_STATE_SKIP_INITIAL = 1;
    private static final int RAMP_STATE_SKIP_NONE = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_OFF = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_ON = 2;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_OFF = 3;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_ON = 1;
    private static final int SCREEN_DIM_MINIMUM_REDUCTION = 10;
    private static final String SCREEN_OFF_BLOCKED_TRACE_NAME = "Screen off blocked";
    private static final String SCREEN_ON_BLOCKED_TRACE_NAME = "Screen on blocked";
    private static final String TAG = "DisplayPowerController";
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;
    private static final boolean USE_COLOR_FADE_ON_ANIMATION = false;
    private final boolean mAllowAutoBrightnessWhileDozingConfig;
    private boolean mAppliedAutoBrightness;
    private boolean mAppliedBrightnessBoost;
    private boolean mAppliedDimming;
    private boolean mAppliedLowPower;
    private boolean mAppliedScreenBrightnessOverride;
    private boolean mAppliedTemporaryAutoBrightnessAdjustment;
    private boolean mAppliedTemporaryBrightness;
    private float mAutoBrightnessAdjustment;
    private AutomaticBrightnessController mAutomaticBrightnessController;
    private final DisplayBlanker mBlanker;
    private boolean mBrightnessBucketsInDozeConfig;
    private BrightnessConfiguration mBrightnessConfiguration;
    private BrightnessMappingStrategy mBrightnessMapper;
    private final int mBrightnessRampRateFast;
    private final int mBrightnessRampRateSlow;
    private final BrightnessTracker mBrightnessTracker;
    private final DisplayManagerInternal.DisplayPowerCallbacks mCallbacks;
    private final boolean mColorFadeEnabled;
    private boolean mColorFadeFadesConfig;
    private ObjectAnimator mColorFadeOffAnimator;
    private ObjectAnimator mColorFadeOnAnimator;
    private final Context mContext;
    private int mCurrentScreenBrightnessSetting;
    private boolean mDisplayBlanksAfterDozeConfig;
    private boolean mDisplayReadyLocked;
    private boolean mDozing;
    private final DisplayControllerHandler mHandler;
    private int mInitialAutoBrightness;
    private int mLastUserSetScreenBrightness;
    private float mPendingAutoBrightnessAdjustment;
    private boolean mPendingRequestChangedLocked;
    private DisplayManagerInternal.DisplayPowerRequest mPendingRequestLocked;
    private int mPendingScreenBrightnessSetting;
    private boolean mPendingScreenOff;
    private ScreenOffUnblocker mPendingScreenOffUnblocker;
    private ScreenOnUnblocker mPendingScreenOnUnblocker;
    private boolean mPendingUpdatePowerStateLocked;
    private boolean mPendingWaitForNegativeProximityLocked;
    private DisplayManagerInternal.DisplayPowerRequest mPowerRequest;
    private DisplayPowerState mPowerState;
    private Sensor mProximitySensor;
    private boolean mProximitySensorEnabled;
    private float mProximityThreshold;
    private int mReportedScreenStateToPolicy;
    private final int mScreenBrightnessDefault;
    private final int mScreenBrightnessDimConfig;
    private final int mScreenBrightnessDozeConfig;
    private int mScreenBrightnessForVr;
    private final int mScreenBrightnessForVrDefault;
    private final int mScreenBrightnessForVrRangeMaximum;
    private final int mScreenBrightnessForVrRangeMinimum;
    private RampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;
    private final int mScreenBrightnessRangeMaximum;
    private final int mScreenBrightnessRangeMinimum;
    private boolean mScreenOffBecauseOfProximity;
    private long mScreenOffBlockStartRealTime;
    private long mScreenOnBlockStartRealTime;
    private final SensorManager mSensorManager;
    private final SettingsObserver mSettingsObserver;
    private final boolean mSkipScreenOnBrightnessRamp;
    private float mTemporaryAutoBrightnessAdjustment;
    private int mTemporaryScreenBrightness;
    private boolean mUnfinishedBusiness;
    private boolean mUseSoftwareAutoBrightnessConfig;
    private boolean mWaitingForNegativeProximity;
    private XpColorFade mXpColorFade;
    private final Object mLock = new Object();
    private int mProximity = -1;
    private int mPendingProximity = -1;
    private long mPendingProximityDebounceTime = -1;
    private int mSkipRampState = 0;
    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() { // from class: com.android.server.display.DisplayPowerController.2
        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationStart(Animator animation) {
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationEnd(Animator animation) {
            DisplayPowerController.this.sendUpdatePowerState();
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationRepeat(Animator animation) {
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationCancel(Animator animation) {
        }
    };
    private final RampAnimator.Listener mRampAnimatorListener = new RampAnimator.Listener() { // from class: com.android.server.display.DisplayPowerController.3
        @Override // com.android.server.display.RampAnimator.Listener
        public void onAnimationEnd() {
            DisplayPowerController.this.sendUpdatePowerState();
        }
    };
    private final Runnable mCleanListener = new Runnable() { // from class: com.android.server.display.DisplayPowerController.4
        @Override // java.lang.Runnable
        public void run() {
            DisplayPowerController.this.sendUpdatePowerState();
        }
    };
    private final Runnable mOnStateChangedRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerController.5
        @Override // java.lang.Runnable
        public void run() {
            DisplayPowerController.this.mCallbacks.onStateChanged();
            DisplayPowerController.this.mCallbacks.releaseSuspendBlocker();
        }
    };
    private final Runnable mOnProximityPositiveRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerController.6
        @Override // java.lang.Runnable
        public void run() {
            DisplayPowerController.this.mCallbacks.onProximityPositive();
            DisplayPowerController.this.mCallbacks.releaseSuspendBlocker();
        }
    };
    private final Runnable mOnProximityNegativeRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerController.7
        @Override // java.lang.Runnable
        public void run() {
            DisplayPowerController.this.mCallbacks.onProximityNegative();
            DisplayPowerController.this.mCallbacks.releaseSuspendBlocker();
        }
    };
    private final SensorEventListener mProximitySensorListener = new SensorEventListener() { // from class: com.android.server.display.DisplayPowerController.9
        @Override // android.hardware.SensorEventListener
        public void onSensorChanged(SensorEvent event) {
            if (DisplayPowerController.this.mProximitySensorEnabled) {
                long time = SystemClock.uptimeMillis();
                boolean positive = false;
                float distance = event.values[0];
                if (distance >= 0.0f && distance < DisplayPowerController.this.mProximityThreshold) {
                    positive = true;
                }
                DisplayPowerController.this.handleProximitySensorEvent(time, positive);
            }
        }

        @Override // android.hardware.SensorEventListener
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
    private final IBatteryStats mBatteryStats = BatteryStatsService.getService();
    private final WindowManagerPolicy mWindowManagerPolicy = (WindowManagerPolicy) LocalServices.getService(WindowManagerPolicy.class);

    public DisplayPowerController(Context context, DisplayManagerInternal.DisplayPowerCallbacks callbacks, Handler handler, SensorManager sensorManager, DisplayBlanker blanker) {
        Resources resources;
        DisplayPowerController displayPowerController;
        this.mHandler = new DisplayControllerHandler(handler.getLooper());
        this.mBrightnessTracker = new BrightnessTracker(context, null);
        this.mSettingsObserver = new SettingsObserver(this.mHandler);
        this.mCallbacks = callbacks;
        this.mSensorManager = sensorManager;
        this.mBlanker = blanker;
        this.mContext = context;
        Resources resources2 = context.getResources();
        int screenBrightnessSettingMinimum = clampAbsoluteBrightness(resources2.getInteger(17694862));
        this.mScreenBrightnessDozeConfig = clampAbsoluteBrightness(resources2.getInteger(17694856));
        this.mScreenBrightnessDimConfig = clampAbsoluteBrightness(resources2.getInteger(17694855));
        this.mScreenBrightnessRangeMinimum = Math.min(screenBrightnessSettingMinimum, this.mScreenBrightnessDimConfig);
        this.mScreenBrightnessRangeMaximum = clampAbsoluteBrightness(resources2.getInteger(17694861));
        this.mScreenBrightnessDefault = clampAbsoluteBrightness(resources2.getInteger(17694860));
        this.mScreenBrightnessForVrRangeMinimum = clampAbsoluteBrightness(resources2.getInteger(17694859));
        this.mScreenBrightnessForVrRangeMaximum = clampAbsoluteBrightness(resources2.getInteger(17694858));
        this.mScreenBrightnessForVrDefault = clampAbsoluteBrightness(resources2.getInteger(17694857));
        this.mUseSoftwareAutoBrightnessConfig = resources2.getBoolean(17956895);
        this.mAllowAutoBrightnessWhileDozingConfig = resources2.getBoolean(17956872);
        this.mBrightnessRampRateFast = resources2.getInteger(17694745);
        this.mBrightnessRampRateSlow = resources2.getInteger(17694746);
        this.mSkipScreenOnBrightnessRamp = resources2.getBoolean(17957030);
        if (this.mUseSoftwareAutoBrightnessConfig) {
            float dozeScaleFactor = resources2.getFraction(18022403, 1, 1);
            int[] ambientBrighteningThresholds = resources2.getIntArray(17235980);
            int[] ambientDarkeningThresholds = resources2.getIntArray(17235981);
            int[] ambientThresholdLevels = resources2.getIntArray(17235982);
            HysteresisLevels ambientBrightnessThresholds = new HysteresisLevels(ambientBrighteningThresholds, ambientDarkeningThresholds, ambientThresholdLevels);
            int[] screenBrighteningThresholds = resources2.getIntArray(17236033);
            int[] screenDarkeningThresholds = resources2.getIntArray(17236036);
            int[] screenThresholdLevels = resources2.getIntArray(17236037);
            HysteresisLevels screenBrightnessThresholds = new HysteresisLevels(screenBrighteningThresholds, screenDarkeningThresholds, screenThresholdLevels);
            long brighteningLightDebounce = resources2.getInteger(17694732);
            long darkeningLightDebounce = resources2.getInteger(17694733);
            boolean autoBrightnessResetAmbientLuxAfterWarmUp = resources2.getBoolean(17956891);
            int lightSensorWarmUpTimeConfig = resources2.getInteger(17694798);
            int lightSensorRate = resources2.getInteger(17694735);
            int initialLightSensorRate = resources2.getInteger(17694734);
            if (initialLightSensorRate == -1) {
                initialLightSensorRate = lightSensorRate;
            } else if (initialLightSensorRate > lightSensorRate) {
                Slog.w(TAG, "Expected config_autoBrightnessInitialLightSensorRate (" + initialLightSensorRate + ") to be less than or equal to config_autoBrightnessLightSensorRate (" + lightSensorRate + ").");
            }
            int initialLightSensorRate2 = initialLightSensorRate;
            this.mBrightnessMapper = BrightnessMappingStrategy.create(resources2);
            if (this.mBrightnessMapper != null) {
                Looper looper = handler.getLooper();
                BrightnessMappingStrategy brightnessMappingStrategy = this.mBrightnessMapper;
                int i = this.mScreenBrightnessRangeMinimum;
                int screenBrightnessSettingMinimum2 = this.mScreenBrightnessRangeMaximum;
                resources = resources2;
                AutomaticBrightnessController automaticBrightnessController = new AutomaticBrightnessController(this, looper, sensorManager, brightnessMappingStrategy, lightSensorWarmUpTimeConfig, i, screenBrightnessSettingMinimum2, dozeScaleFactor, lightSensorRate, initialLightSensorRate2, brighteningLightDebounce, darkeningLightDebounce, autoBrightnessResetAmbientLuxAfterWarmUp, ambientBrightnessThresholds, screenBrightnessThresholds, context);
                displayPowerController = this;
                displayPowerController.mAutomaticBrightnessController = automaticBrightnessController;
            } else {
                resources = resources2;
                displayPowerController = this;
                displayPowerController.mUseSoftwareAutoBrightnessConfig = false;
            }
        } else {
            resources = resources2;
            displayPowerController = this;
        }
        displayPowerController.mColorFadeEnabled = !ActivityManager.isLowRamDeviceStatic();
        Resources resources3 = resources;
        displayPowerController.mColorFadeFadesConfig = resources3.getBoolean(17956888);
        displayPowerController.mDisplayBlanksAfterDozeConfig = resources3.getBoolean(17956933);
        displayPowerController.mBrightnessBucketsInDozeConfig = resources3.getBoolean(17956934);
        displayPowerController.mProximitySensor = displayPowerController.mSensorManager.getDefaultSensor(8);
        if (displayPowerController.mProximitySensor != null) {
            displayPowerController.mProximityThreshold = Math.min(displayPowerController.mProximitySensor.getMaximumRange(), 5.0f);
        }
        displayPowerController.mCurrentScreenBrightnessSetting = getScreenBrightnessSetting();
        displayPowerController.mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        displayPowerController.mAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        displayPowerController.mTemporaryScreenBrightness = -1;
        displayPowerController.mPendingScreenBrightnessSetting = -1;
        displayPowerController.mTemporaryAutoBrightnessAdjustment = Float.NaN;
        displayPowerController.mPendingAutoBrightnessAdjustment = Float.NaN;
    }

    public boolean isProximitySensorAvailable() {
        return this.mProximitySensor != null;
    }

    public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(int userId, boolean includePackage) {
        return this.mBrightnessTracker.getEvents(userId, includePackage);
    }

    public void onSwitchUser(int newUserId) {
        handleSettingsChange(true);
        this.mBrightnessTracker.onSwitchUser(newUserId);
    }

    public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(int userId) {
        return this.mBrightnessTracker.getAmbientBrightnessStats(userId);
    }

    public void persistBrightnessTrackerState() {
        this.mBrightnessTracker.persistBrightnessTrackerState();
    }

    public boolean requestPowerState(DisplayManagerInternal.DisplayPowerRequest request, boolean waitForNegativeProximity) {
        boolean z;
        synchronized (this.mLock) {
            boolean changed = false;
            if (waitForNegativeProximity) {
                try {
                    if (!this.mPendingWaitForNegativeProximityLocked) {
                        this.mPendingWaitForNegativeProximityLocked = true;
                        changed = true;
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (this.mPendingRequestLocked == null) {
                this.mPendingRequestLocked = new DisplayManagerInternal.DisplayPowerRequest(request);
                changed = true;
            } else if (!this.mPendingRequestLocked.equals(request)) {
                this.mPendingRequestLocked.copyFrom(request);
                changed = true;
            }
            if (changed) {
                this.mDisplayReadyLocked = false;
            }
            if (changed && !this.mPendingRequestChangedLocked) {
                this.mPendingRequestChangedLocked = true;
                sendUpdatePowerStateLocked();
            }
            z = this.mDisplayReadyLocked;
        }
        return z;
    }

    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        return this.mAutomaticBrightnessController.getDefaultConfig();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendUpdatePowerState() {
        synchronized (this.mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    private void sendUpdatePowerStateLocked() {
        if (!this.mPendingUpdatePowerStateLocked) {
            this.mPendingUpdatePowerStateLocked = true;
            Message msg = this.mHandler.obtainMessage(1);
            this.mHandler.sendMessage(msg);
        }
    }

    private void initialize() {
        Settings.System.putFloatForUser(this.mContext.getContentResolver(), "screen_dutycycle", 1.0f, -2);
        this.mPowerState = new DisplayPowerState(this.mBlanker, this.mColorFadeEnabled ? new ColorFade(0) : null);
        if (this.mColorFadeEnabled) {
            this.mColorFadeOnAnimator = ObjectAnimator.ofFloat(this.mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 0.0f, 1.0f);
            this.mColorFadeOnAnimator.setDuration(250L);
            this.mColorFadeOnAnimator.addListener(this.mAnimatorListener);
            this.mColorFadeOffAnimator = ObjectAnimator.ofFloat(this.mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 1.0f, 0.0f);
            this.mColorFadeOffAnimator.setDuration(400L);
            this.mColorFadeOffAnimator.addListener(this.mAnimatorListener);
        }
        this.mScreenBrightnessRampAnimator = new RampAnimator<>(this.mPowerState, DisplayPowerState.SCREEN_BRIGHTNESS);
        this.mScreenBrightnessRampAnimator.setListener(this.mRampAnimatorListener);
        try {
            this.mBatteryStats.noteScreenState(this.mPowerState.getScreenState());
            this.mBatteryStats.noteScreenBrightness(this.mPowerState.getScreenBrightness());
        } catch (RemoteException e) {
        }
        float brightness = convertToNits(this.mPowerState.getScreenBrightness());
        if (brightness >= 0.0f) {
            this.mBrightnessTracker.start(brightness);
        }
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_brightness"), false, this.mSettingsObserver, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_dutycycle"), false, this.mSettingsObserver, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_brightness_for_vr"), false, this.mSettingsObserver, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_auto_brightness_adj"), false, this.mSettingsObserver, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("AutoBrightnessLevel"), true, new ContentObserver(new Handler()) { // from class: com.android.server.display.DisplayPowerController.1
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                Settings.System.getInt(DisplayPowerController.this.mContext.getContentResolver(), "AutoBrightnessLevel", 255);
                DisplayPowerController.this.updatePowerState();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePowerState() {
        int state;
        float autoBrightnessAdjustment;
        boolean userInitiatedChange;
        boolean z;
        boolean z2;
        int oldState;
        boolean mustInitialize = false;
        synchronized (this.mLock) {
            try {
                this.mPendingUpdatePowerStateLocked = false;
                if (this.mPendingRequestLocked == null) {
                    return;
                }
                if (this.mPowerRequest == null) {
                    this.mPowerRequest = new DisplayManagerInternal.DisplayPowerRequest(this.mPendingRequestLocked);
                    this.mWaitingForNegativeProximity = this.mPendingWaitForNegativeProximityLocked;
                    this.mPendingWaitForNegativeProximityLocked = false;
                    this.mPendingRequestChangedLocked = false;
                    mustInitialize = true;
                } else if (this.mPendingRequestChangedLocked) {
                    this.mPowerRequest.copyFrom(this.mPendingRequestLocked);
                    this.mWaitingForNegativeProximity |= this.mPendingWaitForNegativeProximityLocked;
                    this.mPendingWaitForNegativeProximityLocked = false;
                    this.mPendingRequestChangedLocked = false;
                    this.mDisplayReadyLocked = false;
                }
                try {
                    boolean mustNotify = !this.mDisplayReadyLocked;
                    if (mustInitialize) {
                        initialize();
                    }
                    int brightness = -1;
                    boolean performScreenOffTransition = false;
                    int i = this.mPowerRequest.policy;
                    if (i != 4) {
                        switch (i) {
                            case 0:
                                state = 1;
                                performScreenOffTransition = true;
                                break;
                            case 1:
                                if (this.mPowerRequest.dozeScreenState != 0) {
                                    state = this.mPowerRequest.dozeScreenState;
                                } else {
                                    state = 3;
                                }
                                if (!this.mAllowAutoBrightnessWhileDozingConfig) {
                                    brightness = this.mPowerRequest.dozeScreenBrightness;
                                    break;
                                }
                                break;
                            default:
                                state = 2;
                                break;
                        }
                    } else {
                        state = 5;
                    }
                    if (this.mProximitySensor != null) {
                        if (this.mPowerRequest.useProximitySensor && state != 1) {
                            setProximitySensorEnabled(true);
                            if (!this.mScreenOffBecauseOfProximity && this.mProximity == 1) {
                                this.mScreenOffBecauseOfProximity = true;
                                sendOnProximityPositiveWithWakelock();
                            }
                        } else if (this.mWaitingForNegativeProximity && this.mScreenOffBecauseOfProximity && this.mProximity == 1 && state != 1) {
                            setProximitySensorEnabled(true);
                        } else {
                            setProximitySensorEnabled(false);
                            this.mWaitingForNegativeProximity = false;
                        }
                        if (this.mScreenOffBecauseOfProximity && this.mProximity != 1) {
                            this.mScreenOffBecauseOfProximity = false;
                            sendOnProximityNegativeWithWakelock();
                        }
                    } else {
                        this.mWaitingForNegativeProximity = false;
                    }
                    if (this.mScreenOffBecauseOfProximity) {
                        state = 1;
                    }
                    int oldState2 = this.mPowerState.getScreenState();
                    animateScreenStateChange(state, performScreenOffTransition);
                    int state2 = this.mPowerState.getScreenState();
                    if (state2 == 1) {
                        brightness = 0;
                    }
                    if (state2 == 5) {
                        brightness = this.mScreenBrightnessForVr;
                    }
                    if (brightness < 0 && this.mPowerRequest.screenBrightnessOverride > 0) {
                        brightness = this.mPowerRequest.screenBrightnessOverride;
                        this.mAppliedScreenBrightnessOverride = true;
                    } else {
                        this.mAppliedScreenBrightnessOverride = false;
                    }
                    if (!this.mAllowAutoBrightnessWhileDozingConfig || !Display.isDozeState(state2)) {
                    }
                    if (!this.mPowerRequest.useAutoBrightness || state2 != 2) {
                    }
                    boolean userSetBrightnessChanged = updateUserSetScreenBrightness();
                    if (this.mTemporaryScreenBrightness > 0) {
                        brightness = this.mTemporaryScreenBrightness;
                        this.mAppliedTemporaryBrightness = true;
                    } else {
                        this.mAppliedTemporaryBrightness = false;
                    }
                    boolean autoBrightnessAdjustmentChanged = updateAutoBrightnessAdjustment();
                    if (autoBrightnessAdjustmentChanged) {
                        this.mTemporaryAutoBrightnessAdjustment = Float.NaN;
                    }
                    if (!Float.isNaN(this.mTemporaryAutoBrightnessAdjustment)) {
                        autoBrightnessAdjustment = this.mTemporaryAutoBrightnessAdjustment;
                        this.mAppliedTemporaryAutoBrightnessAdjustment = true;
                    } else {
                        autoBrightnessAdjustment = this.mAutoBrightnessAdjustment;
                        this.mAppliedTemporaryAutoBrightnessAdjustment = false;
                    }
                    float autoBrightnessAdjustment2 = autoBrightnessAdjustment;
                    if (this.mPowerRequest.boostScreenBrightness && brightness != 0) {
                        brightness = 255;
                        this.mAppliedBrightnessBoost = true;
                    } else {
                        this.mAppliedBrightnessBoost = false;
                    }
                    boolean userInitiatedChange2 = brightness < 0 && (autoBrightnessAdjustmentChanged || userSetBrightnessChanged);
                    boolean hadUserBrightnessPoint = false;
                    if (this.mAutomaticBrightnessController != null) {
                        boolean hadUserBrightnessPoint2 = this.mAutomaticBrightnessController.hasUserDataPoints();
                        userInitiatedChange = userInitiatedChange2;
                        this.mAutomaticBrightnessController.configure(false, this.mBrightnessConfiguration, this.mLastUserSetScreenBrightness / 255.0f, userSetBrightnessChanged, autoBrightnessAdjustment2, autoBrightnessAdjustmentChanged, this.mPowerRequest.policy);
                        hadUserBrightnessPoint = hadUserBrightnessPoint2;
                    } else {
                        userInitiatedChange = userInitiatedChange2;
                    }
                    boolean slowChange = false;
                    if (brightness >= 0) {
                        this.mAppliedAutoBrightness = false;
                    } else {
                        float newAutoBrightnessAdjustment = autoBrightnessAdjustment2;
                        if (0 != 0) {
                            brightness = this.mAutomaticBrightnessController.getAutomaticScreenBrightness();
                            newAutoBrightnessAdjustment = this.mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment();
                        }
                        if (brightness < 0) {
                            this.mAppliedAutoBrightness = false;
                        } else {
                            brightness = clampScreenBrightness(brightness);
                            if (this.mAppliedAutoBrightness && !autoBrightnessAdjustmentChanged) {
                                slowChange = true;
                            }
                            putScreenBrightnessSetting(brightness);
                            this.mAppliedAutoBrightness = true;
                        }
                        if (autoBrightnessAdjustment2 != newAutoBrightnessAdjustment) {
                            putAutoBrightnessAdjustmentSetting(newAutoBrightnessAdjustment);
                        }
                    }
                    if (brightness < 0 && Display.isDozeState(state2)) {
                        brightness = this.mScreenBrightnessDozeConfig;
                    }
                    if (brightness < 0) {
                        brightness = clampScreenBrightness(this.mCurrentScreenBrightnessSetting);
                    }
                    if (this.mPowerRequest.policy == 2) {
                        if (brightness > this.mScreenBrightnessRangeMinimum) {
                            brightness = Math.max(Math.min(brightness - 10, this.mScreenBrightnessDimConfig), this.mScreenBrightnessRangeMinimum);
                        }
                        if (!this.mAppliedDimming) {
                            slowChange = false;
                        }
                        this.mAppliedDimming = true;
                    } else if (this.mAppliedDimming) {
                        slowChange = false;
                        this.mAppliedDimming = false;
                    }
                    if (this.mPowerRequest.lowPowerMode) {
                        if (brightness > this.mScreenBrightnessRangeMinimum) {
                            float brightnessFactor = Math.min(this.mPowerRequest.screenLowPowerBrightnessFactor, 1.0f);
                            int lowPowerBrightness = (int) (brightness * brightnessFactor);
                            brightness = Math.max(lowPowerBrightness, this.mScreenBrightnessRangeMinimum);
                        }
                        if (!this.mAppliedLowPower) {
                            slowChange = false;
                        }
                        this.mAppliedLowPower = true;
                    } else if (this.mAppliedLowPower) {
                        slowChange = false;
                        this.mAppliedLowPower = false;
                    }
                    boolean slowChange2 = slowChange;
                    if (!this.mPendingScreenOff) {
                        if (this.mSkipScreenOnBrightnessRamp) {
                            if (state2 != 2) {
                                this.mSkipRampState = 0;
                            } else if (this.mSkipRampState == 0 && this.mDozing) {
                                this.mInitialAutoBrightness = brightness;
                                this.mSkipRampState = 1;
                            } else if (this.mSkipRampState == 1 && this.mUseSoftwareAutoBrightnessConfig && brightness != this.mInitialAutoBrightness) {
                                this.mSkipRampState = 2;
                            } else if (this.mSkipRampState == 2) {
                                this.mSkipRampState = 0;
                            }
                        }
                        boolean wasOrWillBeInVr = state2 == 5 || oldState2 == 5;
                        boolean initialRampSkip = state2 == 2 && this.mSkipRampState != 0;
                        boolean hasBrightnessBuckets = Display.isDozeState(state2) && this.mBrightnessBucketsInDozeConfig;
                        boolean isDisplayContentVisible = this.mColorFadeEnabled && this.mPowerState.getColorFadeLevel() == 1.0f;
                        boolean brightnessIsTemporary = this.mAppliedTemporaryBrightness || this.mAppliedTemporaryAutoBrightnessAdjustment;
                        boolean useOverrideAnimator = DisplayBrightnessController.useOverrideAnimator(this.mContext);
                        if (useOverrideAnimator) {
                            oldState = 100;
                        } else {
                            oldState = slowChange2 ? this.mBrightnessRampRateSlow : this.mBrightnessRampRateFast;
                        }
                        this.mScreenBrightnessRampAnimator.setOverrideAnimator(useOverrideAnimator);
                        if (initialRampSkip || hasBrightnessBuckets || wasOrWillBeInVr || !isDisplayContentVisible || brightnessIsTemporary) {
                            animateScreenBrightness(brightness, 0);
                        } else {
                            animateScreenBrightness(brightness, oldState);
                        }
                        if (!brightnessIsTemporary) {
                            notifyBrightnessChanged(brightness, userInitiatedChange, hadUserBrightnessPoint);
                        }
                    }
                    boolean ready = this.mPendingScreenOnUnblocker == null && !(this.mColorFadeEnabled && (this.mColorFadeOnAnimator.isStarted() || this.mColorFadeOffAnimator.isStarted())) && this.mPowerState.waitUntilClean(this.mCleanListener);
                    boolean finished = ready && !this.mScreenBrightnessRampAnimator.isAnimating();
                    if (ready && state2 != 1 && this.mReportedScreenStateToPolicy == 1) {
                        setReportedScreenState(2);
                        this.mWindowManagerPolicy.screenTurnedOn();
                    }
                    if (!finished && !this.mUnfinishedBusiness) {
                        this.mCallbacks.acquireSuspendBlocker();
                        this.mUnfinishedBusiness = true;
                    }
                    if (ready && mustNotify) {
                        synchronized (this.mLock) {
                            if (!this.mPendingRequestChangedLocked) {
                                z = true;
                                this.mDisplayReadyLocked = true;
                            } else {
                                z = true;
                            }
                        }
                        sendOnStateChangedWithWakelock();
                    } else {
                        z = true;
                    }
                    if (finished && this.mUnfinishedBusiness) {
                        z2 = false;
                        this.mUnfinishedBusiness = false;
                        this.mCallbacks.releaseSuspendBlocker();
                    } else {
                        z2 = false;
                    }
                    if (state2 == 2) {
                        z = z2;
                    }
                    this.mDozing = z;
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    @Override // com.android.server.display.AutomaticBrightnessController.Callbacks
    public void updateBrightness() {
        sendUpdatePowerState();
    }

    public void setBrightnessConfiguration(BrightnessConfiguration c) {
        Message msg = this.mHandler.obtainMessage(5, c);
        msg.sendToTarget();
    }

    public void setTemporaryBrightness(int brightness) {
        Message msg = this.mHandler.obtainMessage(6, brightness, 0);
        msg.sendToTarget();
    }

    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        Message msg = this.mHandler.obtainMessage(7, Float.floatToIntBits(adjustment), 0);
        msg.sendToTarget();
    }

    private void blockScreenOn() {
        if (this.mPendingScreenOnUnblocker == null) {
            Trace.asyncTraceBegin(131072L, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
            this.mPendingScreenOnUnblocker = new ScreenOnUnblocker();
            this.mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            if (!Build.IS_USER) {
                ResumeProfEvent.addResumeProfEvent("DisplayPowerController: Blocking screen on.");
            }
            Slog.i(TAG, "Blocking screen on until initial contents have been drawn.");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unblockScreenOn() {
        if (this.mPendingScreenOnUnblocker != null) {
            this.mPendingScreenOnUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - this.mScreenOnBlockStartRealTime;
            if (!Build.IS_USER) {
                ResumeProfEvent.addResumeProfEvent("DisplayPowerController: Unblocked screen on after " + delay + " ms");
            }
            Slog.i(TAG, "Unblocked screen on after " + delay + " ms");
            Trace.asyncTraceEnd(131072L, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
        }
    }

    private void blockScreenOff() {
        if (this.mPendingScreenOffUnblocker == null) {
            Trace.asyncTraceBegin(131072L, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
            this.mPendingScreenOffUnblocker = new ScreenOffUnblocker();
            this.mScreenOffBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(TAG, "Blocking screen off");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unblockScreenOff() {
        if (this.mPendingScreenOffUnblocker != null) {
            this.mPendingScreenOffUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - this.mScreenOffBlockStartRealTime;
            Slog.i(TAG, "Unblocked screen off after " + delay + " ms");
            Trace.asyncTraceEnd(131072L, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
        }
    }

    private boolean setScreenState(int state) {
        return setScreenState(state, false);
    }

    private boolean setScreenState(int state, boolean reportOnly) {
        boolean isOff = state == 1;
        if (this.mPowerState.getScreenState() != state) {
            if (isOff && !this.mScreenOffBecauseOfProximity) {
                if (this.mReportedScreenStateToPolicy == 2) {
                    setReportedScreenState(3);
                    blockScreenOff();
                    this.mWindowManagerPolicy.screenTurningOff(this.mPendingScreenOffUnblocker);
                    unblockScreenOff();
                } else if (this.mPendingScreenOffUnblocker != null) {
                    return false;
                }
            }
            if (!reportOnly) {
                Trace.traceCounter(131072L, "ScreenState", state);
                this.mPowerState.setScreenState(state);
                try {
                    this.mBatteryStats.noteScreenState(state);
                } catch (RemoteException e) {
                }
            }
        }
        if (isOff && this.mReportedScreenStateToPolicy != 0 && !this.mScreenOffBecauseOfProximity) {
            setReportedScreenState(0);
            unblockScreenOn();
            this.mWindowManagerPolicy.screenTurnedOff();
        } else if (!isOff && this.mReportedScreenStateToPolicy == 3) {
            unblockScreenOff();
            this.mWindowManagerPolicy.screenTurnedOff();
            setReportedScreenState(0);
        }
        if (!isOff && this.mReportedScreenStateToPolicy == 0) {
            setReportedScreenState(1);
            if (this.mPowerState.getColorFadeLevel() == 0.0f) {
                blockScreenOn();
            } else {
                unblockScreenOn();
            }
            this.mWindowManagerPolicy.screenTurningOn(this.mPendingScreenOnUnblocker);
        }
        return this.mPendingScreenOnUnblocker == null;
    }

    private void setReportedScreenState(int state) {
        Trace.traceCounter(131072L, "ReportedScreenStateToPolicy", state);
        this.mReportedScreenStateToPolicy = state;
    }

    private int clampScreenBrightnessForVr(int value) {
        return MathUtils.constrain(value, this.mScreenBrightnessForVrRangeMinimum, this.mScreenBrightnessForVrRangeMaximum);
    }

    private int clampScreenBrightness(int value) {
        return MathUtils.constrain(value, this.mScreenBrightnessRangeMinimum, this.mScreenBrightnessRangeMaximum);
    }

    private void animateScreenBrightness(int target, int rate) {
        if (this.mScreenBrightnessRampAnimator.animateTo(target, rate)) {
            Trace.traceCounter(131072L, "TargetScreenBrightness", target);
            try {
                this.mBatteryStats.noteScreenBrightness(target);
            } catch (RemoteException e) {
            }
        }
    }

    private void animateScreenStateChange(int target, boolean performScreenOffTransition) {
        if (this.mColorFadeEnabled && (this.mColorFadeOnAnimator.isStarted() || this.mColorFadeOffAnimator.isStarted())) {
            if (target != 2) {
                return;
            }
            this.mPendingScreenOff = false;
        }
        if (this.mDisplayBlanksAfterDozeConfig && Display.isDozeState(this.mPowerState.getScreenState()) && !Display.isDozeState(target)) {
            this.mPowerState.prepareColorFade(this.mContext, this.mColorFadeFadesConfig ? 2 : 0);
            if (this.mColorFadeOffAnimator != null) {
                this.mColorFadeOffAnimator.end();
            }
            setScreenState(1, target != 1);
        }
        if (this.mPendingScreenOff && target != 1) {
            setScreenState(1);
            this.mPendingScreenOff = false;
            this.mPowerState.dismissColorFadeResources();
        }
        if (target == 2) {
            if (setScreenState(2)) {
                this.mPowerState.setColorFadeLevel(1.0f);
                this.mPowerState.dismissColorFade();
            }
        } else if (target == 5) {
            if ((!this.mScreenBrightnessRampAnimator.isAnimating() || this.mPowerState.getScreenState() != 2) && setScreenState(5)) {
                this.mPowerState.setColorFadeLevel(1.0f);
                this.mPowerState.dismissColorFade();
            }
        } else if (target == 3) {
            if ((!this.mScreenBrightnessRampAnimator.isAnimating() || this.mPowerState.getScreenState() != 2) && setScreenState(3)) {
                this.mPowerState.setColorFadeLevel(1.0f);
                this.mPowerState.dismissColorFade();
            }
        } else if (target == 4) {
            if (!this.mScreenBrightnessRampAnimator.isAnimating() || this.mPowerState.getScreenState() == 4) {
                if (this.mPowerState.getScreenState() != 4) {
                    if (!setScreenState(3)) {
                        return;
                    }
                    setScreenState(4);
                }
                this.mPowerState.setColorFadeLevel(1.0f);
                this.mPowerState.dismissColorFade();
            }
        } else if (target == 6) {
            if (!this.mScreenBrightnessRampAnimator.isAnimating() || this.mPowerState.getScreenState() == 6) {
                if (this.mPowerState.getScreenState() != 6) {
                    if (!setScreenState(2)) {
                        return;
                    }
                    setScreenState(6);
                }
                this.mPowerState.setColorFadeLevel(1.0f);
                this.mPowerState.dismissColorFade();
            }
        } else {
            this.mPendingScreenOff = true;
            if (!this.mColorFadeEnabled) {
                this.mPowerState.setColorFadeLevel(0.0f);
            }
            if (this.mPowerState.getColorFadeLevel() == 0.0f) {
                setScreenState(1);
                this.mPendingScreenOff = false;
                this.mPowerState.dismissColorFadeResources();
                return;
            }
            if (performScreenOffTransition) {
                if (this.mPowerState.prepareColorFade(this.mContext, this.mColorFadeFadesConfig ? 2 : 1) && this.mPowerState.getScreenState() != 1) {
                    this.mColorFadeOffAnimator.start();
                    return;
                }
            }
            this.mColorFadeOffAnimator.end();
        }
    }

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!this.mProximitySensorEnabled) {
                this.mProximitySensorEnabled = true;
                this.mSensorManager.registerListener(this.mProximitySensorListener, this.mProximitySensor, 3, this.mHandler);
            }
        } else if (this.mProximitySensorEnabled) {
            this.mProximitySensorEnabled = false;
            this.mProximity = -1;
            this.mPendingProximity = -1;
            this.mHandler.removeMessages(2);
            this.mSensorManager.unregisterListener(this.mProximitySensorListener);
            clearPendingProximityDebounceTime();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleProximitySensorEvent(long time, boolean positive) {
        if (this.mProximitySensorEnabled) {
            if (this.mPendingProximity == 0 && !positive) {
                return;
            }
            if (this.mPendingProximity == 1 && positive) {
                return;
            }
            this.mHandler.removeMessages(2);
            if (positive) {
                this.mPendingProximity = 1;
                setPendingProximityDebounceTime(0 + time);
            } else {
                this.mPendingProximity = 0;
                setPendingProximityDebounceTime(250 + time);
            }
            debounceProximitySensor();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void debounceProximitySensor() {
        if (this.mProximitySensorEnabled && this.mPendingProximity != -1 && this.mPendingProximityDebounceTime >= 0) {
            long now = SystemClock.uptimeMillis();
            if (this.mPendingProximityDebounceTime <= now) {
                this.mProximity = this.mPendingProximity;
                updatePowerState();
                clearPendingProximityDebounceTime();
                return;
            }
            Message msg = this.mHandler.obtainMessage(2);
            this.mHandler.sendMessageAtTime(msg, this.mPendingProximityDebounceTime);
        }
    }

    private void clearPendingProximityDebounceTime() {
        if (this.mPendingProximityDebounceTime >= 0) {
            this.mPendingProximityDebounceTime = -1L;
            this.mCallbacks.releaseSuspendBlocker();
        }
    }

    private void setPendingProximityDebounceTime(long debounceTime) {
        if (this.mPendingProximityDebounceTime < 0) {
            this.mCallbacks.acquireSuspendBlocker();
        }
        this.mPendingProximityDebounceTime = debounceTime;
    }

    private void sendOnStateChangedWithWakelock() {
        this.mCallbacks.acquireSuspendBlocker();
        this.mHandler.post(this.mOnStateChangedRunnable);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSettingsChange(boolean userSwitch) {
        this.mPendingScreenBrightnessSetting = getScreenBrightnessSetting();
        if (userSwitch) {
            this.mCurrentScreenBrightnessSetting = this.mPendingScreenBrightnessSetting;
            if (this.mAutomaticBrightnessController != null) {
                this.mAutomaticBrightnessController.resetShortTermModel();
            }
        }
        this.mPendingAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        this.mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        sendUpdatePowerState();
    }

    private float getAutoBrightnessAdjustmentSetting() {
        float adj = Settings.System.getFloatForUser(this.mContext.getContentResolver(), "screen_auto_brightness_adj", 0.0f, -2);
        if (Float.isNaN(adj)) {
            return 0.0f;
        }
        return clampAutoBrightnessAdjustment(adj);
    }

    private int getScreenBrightnessSetting() {
        int limitedBrightness = (int) (Settings.System.getFloatForUser(this.mContext.getContentResolver(), "screen_dutycycle", 1.0f, -2) * 255.0f);
        int brightness = Settings.System.getIntForUser(this.mContext.getContentResolver(), "screen_brightness", this.mScreenBrightnessDefault, -2);
        return clampAbsoluteBrightness(Math.min(brightness, limitedBrightness));
    }

    private int getScreenBrightnessForVrSetting() {
        int brightness = Settings.System.getIntForUser(this.mContext.getContentResolver(), "screen_brightness_for_vr", this.mScreenBrightnessForVrDefault, -2);
        return clampScreenBrightnessForVr(brightness);
    }

    private void putScreenBrightnessSetting(int brightness) {
        this.mCurrentScreenBrightnessSetting = brightness;
        Settings.System.putIntForUser(this.mContext.getContentResolver(), "screen_brightness", brightness, -2);
    }

    private void putAutoBrightnessAdjustmentSetting(float adjustment) {
        this.mAutoBrightnessAdjustment = adjustment;
        Settings.System.putFloatForUser(this.mContext.getContentResolver(), "screen_auto_brightness_adj", adjustment, -2);
    }

    private boolean updateAutoBrightnessAdjustment() {
        if (Float.isNaN(this.mPendingAutoBrightnessAdjustment)) {
            return false;
        }
        if (this.mAutoBrightnessAdjustment == this.mPendingAutoBrightnessAdjustment) {
            this.mPendingAutoBrightnessAdjustment = Float.NaN;
            return false;
        }
        this.mAutoBrightnessAdjustment = this.mPendingAutoBrightnessAdjustment;
        this.mPendingAutoBrightnessAdjustment = Float.NaN;
        return true;
    }

    private boolean updateUserSetScreenBrightness() {
        if (this.mPendingScreenBrightnessSetting < 0) {
            return false;
        }
        if (this.mCurrentScreenBrightnessSetting == this.mPendingScreenBrightnessSetting) {
            this.mPendingScreenBrightnessSetting = -1;
            this.mTemporaryScreenBrightness = -1;
            return false;
        }
        this.mCurrentScreenBrightnessSetting = this.mPendingScreenBrightnessSetting;
        this.mLastUserSetScreenBrightness = this.mPendingScreenBrightnessSetting;
        this.mPendingScreenBrightnessSetting = -1;
        this.mTemporaryScreenBrightness = -1;
        return true;
    }

    private void notifyBrightnessChanged(int brightness, boolean userInitiated, boolean hadUserDataPoint) {
        float brightnessInNits = convertToNits(brightness);
        if (this.mPowerRequest.useAutoBrightness && brightnessInNits >= 0.0f && this.mAutomaticBrightnessController != null) {
            float powerFactor = this.mPowerRequest.lowPowerMode ? this.mPowerRequest.screenLowPowerBrightnessFactor : 1.0f;
            this.mBrightnessTracker.notifyBrightnessChanged(brightnessInNits, userInitiated, powerFactor, hadUserDataPoint, this.mAutomaticBrightnessController.isDefaultConfig());
        }
    }

    private float convertToNits(int backlight) {
        if (this.mBrightnessMapper != null) {
            return this.mBrightnessMapper.convertToNits(backlight);
        }
        return -1.0f;
    }

    private void sendOnProximityPositiveWithWakelock() {
        this.mCallbacks.acquireSuspendBlocker();
        this.mHandler.post(this.mOnProximityPositiveRunnable);
    }

    private void sendOnProximityNegativeWithWakelock() {
        this.mCallbacks.acquireSuspendBlocker();
        this.mHandler.post(this.mOnProximityNegativeRunnable);
    }

    public void dump(final PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println();
            pw.println("Display Power Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + this.mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + this.mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + this.mPendingRequestChangedLocked);
            pw.println("  mPendingWaitForNegativeProximityLocked=" + this.mPendingWaitForNegativeProximityLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + this.mPendingUpdatePowerStateLocked);
        }
        pw.println();
        pw.println("Display Power Controller Configuration:");
        pw.println("  mScreenBrightnessDozeConfig=" + this.mScreenBrightnessDozeConfig);
        pw.println("  mScreenBrightnessDimConfig=" + this.mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessRangeMinimum=" + this.mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + this.mScreenBrightnessRangeMaximum);
        pw.println("  mScreenBrightnessDefault=" + this.mScreenBrightnessDefault);
        pw.println("  mScreenBrightnessForVrRangeMinimum=" + this.mScreenBrightnessForVrRangeMinimum);
        pw.println("  mScreenBrightnessForVrRangeMaximum=" + this.mScreenBrightnessForVrRangeMaximum);
        pw.println("  mScreenBrightnessForVrDefault=" + this.mScreenBrightnessForVrDefault);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + this.mUseSoftwareAutoBrightnessConfig);
        pw.println("  mAllowAutoBrightnessWhileDozingConfig=" + this.mAllowAutoBrightnessWhileDozingConfig);
        pw.println("  mBrightnessRampRateFast=" + this.mBrightnessRampRateFast);
        pw.println("  mBrightnessRampRateSlow=" + this.mBrightnessRampRateSlow);
        pw.println("  mSkipScreenOnBrightnessRamp=" + this.mSkipScreenOnBrightnessRamp);
        pw.println("  mColorFadeFadesConfig=" + this.mColorFadeFadesConfig);
        pw.println("  mColorFadeEnabled=" + this.mColorFadeEnabled);
        pw.println("  mDisplayBlanksAfterDozeConfig=" + this.mDisplayBlanksAfterDozeConfig);
        pw.println("  mBrightnessBucketsInDozeConfig=" + this.mBrightnessBucketsInDozeConfig);
        this.mHandler.runWithScissors(new Runnable() { // from class: com.android.server.display.DisplayPowerController.8
            @Override // java.lang.Runnable
            public void run() {
                DisplayPowerController.this.dumpLocal(pw);
            }
        }, 1000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Power Controller Thread State:");
        pw.println("  mPowerRequest=" + this.mPowerRequest);
        pw.println("  mUnfinishedBusiness=" + this.mUnfinishedBusiness);
        pw.println("  mWaitingForNegativeProximity=" + this.mWaitingForNegativeProximity);
        pw.println("  mProximitySensor=" + this.mProximitySensor);
        pw.println("  mProximitySensorEnabled=" + this.mProximitySensorEnabled);
        pw.println("  mProximityThreshold=" + this.mProximityThreshold);
        pw.println("  mProximity=" + proximityToString(this.mProximity));
        pw.println("  mPendingProximity=" + proximityToString(this.mPendingProximity));
        pw.println("  mPendingProximityDebounceTime=" + TimeUtils.formatUptime(this.mPendingProximityDebounceTime));
        pw.println("  mScreenOffBecauseOfProximity=" + this.mScreenOffBecauseOfProximity);
        pw.println("  mLastUserSetScreenBrightness=" + this.mLastUserSetScreenBrightness);
        pw.println("  mCurrentScreenBrightnessSetting=" + this.mCurrentScreenBrightnessSetting);
        pw.println("  mPendingScreenBrightnessSetting=" + this.mPendingScreenBrightnessSetting);
        pw.println("  mTemporaryScreenBrightness=" + this.mTemporaryScreenBrightness);
        pw.println("  mAutoBrightnessAdjustment=" + this.mAutoBrightnessAdjustment);
        pw.println("  mTemporaryAutoBrightnessAdjustment=" + this.mTemporaryAutoBrightnessAdjustment);
        pw.println("  mPendingAutoBrightnessAdjustment=" + this.mPendingAutoBrightnessAdjustment);
        pw.println("  mScreenBrightnessForVr=" + this.mScreenBrightnessForVr);
        pw.println("  mAppliedAutoBrightness=" + this.mAppliedAutoBrightness);
        pw.println("  mAppliedDimming=" + this.mAppliedDimming);
        pw.println("  mAppliedLowPower=" + this.mAppliedLowPower);
        pw.println("  mAppliedScreenBrightnessOverride=" + this.mAppliedScreenBrightnessOverride);
        pw.println("  mAppliedTemporaryBrightness=" + this.mAppliedTemporaryBrightness);
        pw.println("  mDozing=" + this.mDozing);
        pw.println("  mSkipRampState=" + skipRampStateToString(this.mSkipRampState));
        pw.println("  mInitialAutoBrightness=" + this.mInitialAutoBrightness);
        pw.println("  mScreenOnBlockStartRealTime=" + this.mScreenOnBlockStartRealTime);
        pw.println("  mScreenOffBlockStartRealTime=" + this.mScreenOffBlockStartRealTime);
        pw.println("  mPendingScreenOnUnblocker=" + this.mPendingScreenOnUnblocker);
        pw.println("  mPendingScreenOffUnblocker=" + this.mPendingScreenOffUnblocker);
        pw.println("  mPendingScreenOff=" + this.mPendingScreenOff);
        pw.println("  mReportedToPolicy=" + reportedToPolicyToString(this.mReportedScreenStateToPolicy));
        pw.println("  mScreenBrightnessRampAnimator.isAnimating()=" + this.mScreenBrightnessRampAnimator.isAnimating());
        if (this.mColorFadeOnAnimator != null) {
            pw.println("  mColorFadeOnAnimator.isStarted()=" + this.mColorFadeOnAnimator.isStarted());
        }
        if (this.mColorFadeOffAnimator != null) {
            pw.println("  mColorFadeOffAnimator.isStarted()=" + this.mColorFadeOffAnimator.isStarted());
        }
        if (this.mPowerState != null) {
            this.mPowerState.dump(pw);
        }
        if (this.mAutomaticBrightnessController != null) {
            this.mAutomaticBrightnessController.dump(pw);
        }
        if (this.mBrightnessTracker != null) {
            pw.println();
            this.mBrightnessTracker.dump(pw);
        }
    }

    private static String proximityToString(int state) {
        switch (state) {
            case -1:
                return "Unknown";
            case 0:
                return "Negative";
            case 1:
                return "Positive";
            default:
                return Integer.toString(state);
        }
    }

    private static String reportedToPolicyToString(int state) {
        switch (state) {
            case 0:
                return "REPORTED_TO_POLICY_SCREEN_OFF";
            case 1:
                return "REPORTED_TO_POLICY_SCREEN_TURNING_ON";
            case 2:
                return "REPORTED_TO_POLICY_SCREEN_ON";
            default:
                return Integer.toString(state);
        }
    }

    private static String skipRampStateToString(int state) {
        switch (state) {
            case 0:
                return "RAMP_STATE_SKIP_NONE";
            case 1:
                return "RAMP_STATE_SKIP_INITIAL";
            case 2:
                return "RAMP_STATE_SKIP_AUTOBRIGHT";
            default:
                return Integer.toString(state);
        }
    }

    private static int clampAbsoluteBrightness(int value) {
        return MathUtils.constrain(value, 0, 255);
    }

    private static float clampAutoBrightnessAdjustment(float value) {
        return MathUtils.constrain(value, -1.0f, 1.0f);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DisplayControllerHandler extends Handler {
        public DisplayControllerHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    DisplayPowerController.this.updatePowerState();
                    return;
                case 2:
                    DisplayPowerController.this.debounceProximitySensor();
                    return;
                case 3:
                    if (DisplayPowerController.this.mPendingScreenOnUnblocker == msg.obj) {
                        DisplayPowerController.this.unblockScreenOn();
                        DisplayPowerController.this.updatePowerState();
                        return;
                    }
                    return;
                case 4:
                    if (DisplayPowerController.this.mPendingScreenOffUnblocker == msg.obj) {
                        DisplayPowerController.this.unblockScreenOff();
                        DisplayPowerController.this.updatePowerState();
                        return;
                    }
                    return;
                case 5:
                    DisplayPowerController.this.mBrightnessConfiguration = (BrightnessConfiguration) msg.obj;
                    DisplayPowerController.this.updatePowerState();
                    return;
                case 6:
                    DisplayPowerController.this.mTemporaryScreenBrightness = msg.arg1;
                    DisplayPowerController.this.updatePowerState();
                    return;
                case 7:
                    DisplayPowerController.this.mTemporaryAutoBrightnessAdjustment = Float.intBitsToFloat(msg.arg1);
                    DisplayPowerController.this.updatePowerState();
                    return;
                case 8:
                    boolean screenBlack = ((Boolean) msg.obj).booleanValue();
                    if (DisplayPowerController.this.mXpColorFade == null) {
                        DisplayPowerController.this.mXpColorFade = new XpColorFade(0);
                    }
                    if (screenBlack) {
                        if (DisplayPowerController.this.mXpColorFade.getColorFadeLevel() != 0.0f) {
                            DisplayPowerController.this.mXpColorFade.prepare(2);
                            DisplayPowerController.this.mXpColorFade.setColorFadeLevel(0.0f);
                            return;
                        }
                        return;
                    }
                    DisplayPowerController.this.mXpColorFade.setColorFadeLevel(1.0f);
                    DisplayPowerController.this.mXpColorFade.dismiss();
                    return;
                default:
                    return;
            }
        }
    }

    public void setScreenBlackState(boolean state) {
        Message message = this.mHandler.obtainMessage(8, Boolean.valueOf(state));
        this.mHandler.sendMessage(message);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            DisplayPowerController.this.handleSettingsChange(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ScreenOnUnblocker implements WindowManagerPolicy.ScreenOnListener {
        private ScreenOnUnblocker() {
        }

        @Override // com.android.server.policy.WindowManagerPolicy.ScreenOnListener
        public void onScreenOn() {
            Message msg = DisplayPowerController.this.mHandler.obtainMessage(3, this);
            DisplayPowerController.this.mHandler.sendMessage(msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ScreenOffUnblocker implements WindowManagerPolicy.ScreenOffListener {
        private ScreenOffUnblocker() {
        }

        @Override // com.android.server.policy.WindowManagerPolicy.ScreenOffListener
        public void onScreenOff() {
            Message msg = DisplayPowerController.this.mHandler.obtainMessage(4, this);
            DisplayPowerController.this.mHandler.sendMessage(msg);
        }
    }
}
