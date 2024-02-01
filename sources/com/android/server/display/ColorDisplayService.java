package com.android.server.display;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.view.animation.AnimationUtils;
import com.android.internal.app.ColorDisplayController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
/* loaded from: classes.dex */
public final class ColorDisplayService extends SystemService implements ColorDisplayController.Callback {
    private static final ColorMatrixEvaluator COLOR_MATRIX_EVALUATOR;
    private static final float[] MATRIX_IDENTITY = new float[16];
    private static final String TAG = "ColorDisplayService";
    private static final long TRANSITION_DURATION = 3000;
    private AutoMode mAutoMode;
    private boolean mBootCompleted;
    private ValueAnimator mColorMatrixAnimator;
    private final float[] mColorTempCoefficients;
    private ColorDisplayController mController;
    private int mCurrentUser;
    private final Handler mHandler;
    private Boolean mIsActivated;
    private float[] mMatrixNight;
    private ContentObserver mUserSetupObserver;

    static {
        Matrix.setIdentityM(MATRIX_IDENTITY, 0);
        COLOR_MATRIX_EVALUATOR = new ColorMatrixEvaluator();
    }

    public ColorDisplayService(Context context) {
        super(context);
        this.mMatrixNight = new float[16];
        this.mColorTempCoefficients = new float[9];
        this.mCurrentUser = -10000;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase >= 1000) {
            this.mBootCompleted = true;
            if (this.mCurrentUser != -10000 && this.mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);
        if (this.mCurrentUser == -10000) {
            onUserChanged(userHandle);
        }
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);
        onUserChanged(userHandle);
    }

    @Override // com.android.server.SystemService
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);
        if (this.mCurrentUser == userHandle) {
            onUserChanged(-10000);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();
        if (this.mCurrentUser != -10000) {
            if (this.mUserSetupObserver != null) {
                cr.unregisterContentObserver(this.mUserSetupObserver);
                this.mUserSetupObserver = null;
            } else if (this.mBootCompleted) {
                tearDown();
            }
        }
        this.mCurrentUser = userHandle;
        if (this.mCurrentUser != -10000) {
            if (!isUserSetupCompleted(cr, this.mCurrentUser)) {
                this.mUserSetupObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.display.ColorDisplayService.1
                    @Override // android.database.ContentObserver
                    public void onChange(boolean selfChange, Uri uri) {
                        if (ColorDisplayService.isUserSetupCompleted(cr, ColorDisplayService.this.mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            ColorDisplayService.this.mUserSetupObserver = null;
                            if (ColorDisplayService.this.mBootCompleted) {
                                ColorDisplayService.this.setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Settings.Secure.getUriFor("user_setup_complete"), false, this.mUserSetupObserver, this.mCurrentUser);
            } else if (this.mBootCompleted) {
                setUp();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Settings.Secure.getIntForUser(cr, "user_setup_complete", 0, userHandle) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + this.mCurrentUser);
        this.mController = new ColorDisplayController(getContext(), this.mCurrentUser);
        this.mController.setListener(this);
        onDisplayColorModeChanged(this.mController.getColorMode());
        this.mIsActivated = null;
        setCoefficientMatrix(getContext(), DisplayTransformManager.needsLinearColorMatrix());
        setMatrix(this.mController.getColorTemperature(), this.mMatrixNight);
        onAutoModeChanged(this.mController.getAutoMode());
        if (this.mIsActivated == null) {
            onActivated(this.mController.isActivated());
        }
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + this.mCurrentUser);
        if (this.mController != null) {
            this.mController.setListener((ColorDisplayController.Callback) null);
            this.mController = null;
        }
        if (this.mAutoMode != null) {
            this.mAutoMode.onStop();
            this.mAutoMode = null;
        }
        if (this.mColorMatrixAnimator != null) {
            this.mColorMatrixAnimator.end();
            this.mColorMatrixAnimator = null;
        }
    }

    public void onActivated(boolean activated) {
        if (this.mIsActivated == null || this.mIsActivated.booleanValue() != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");
            this.mIsActivated = Boolean.valueOf(activated);
            if (this.mAutoMode != null) {
                this.mAutoMode.onActivated(activated);
            }
            applyTint(false);
        }
    }

    public void onAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onAutoModeChanged: autoMode=" + autoMode);
        if (this.mAutoMode != null) {
            this.mAutoMode.onStop();
            this.mAutoMode = null;
        }
        if (autoMode == 1) {
            this.mAutoMode = new CustomAutoMode();
        } else if (autoMode == 2) {
            this.mAutoMode = new TwilightAutoMode();
        }
        if (this.mAutoMode != null) {
            this.mAutoMode.onStart();
        }
    }

    public void onCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onCustomStartTimeChanged: startTime=" + startTime);
        if (this.mAutoMode != null) {
            this.mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    public void onCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onCustomEndTimeChanged: endTime=" + endTime);
        if (this.mAutoMode != null) {
            this.mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    public void onColorTemperatureChanged(int colorTemperature) {
        setMatrix(colorTemperature, this.mMatrixNight);
        applyTint(true);
    }

    public void onDisplayColorModeChanged(int mode) {
        if (mode == -1) {
            return;
        }
        if (this.mColorMatrixAnimator != null) {
            this.mColorMatrixAnimator.cancel();
        }
        setCoefficientMatrix(getContext(), DisplayTransformManager.needsLinearColorMatrix(mode));
        setMatrix(this.mController.getColorTemperature(), this.mMatrixNight);
        DisplayTransformManager dtm = (DisplayTransformManager) getLocalService(DisplayTransformManager.class);
        dtm.setColorMode(mode, (this.mIsActivated == null || !this.mIsActivated.booleanValue()) ? MATRIX_IDENTITY : this.mMatrixNight);
    }

    public void onAccessibilityTransformChanged(boolean state) {
        onDisplayColorModeChanged(this.mController.getColorMode());
    }

    private void setCoefficientMatrix(Context context, boolean needsLinear) {
        int i;
        Resources resources = context.getResources();
        if (needsLinear) {
            i = 17236024;
        } else {
            i = 17236025;
        }
        String[] coefficients = resources.getStringArray(i);
        for (int i2 = 0; i2 < 9 && i2 < coefficients.length; i2++) {
            this.mColorTempCoefficients[i2] = Float.parseFloat(coefficients[i2]);
        }
    }

    private void applyTint(boolean immediate) {
        if (this.mColorMatrixAnimator != null) {
            this.mColorMatrixAnimator.cancel();
        }
        final DisplayTransformManager dtm = (DisplayTransformManager) getLocalService(DisplayTransformManager.class);
        float[] from = dtm.getColorMatrix(100);
        final float[] to = this.mIsActivated.booleanValue() ? this.mMatrixNight : MATRIX_IDENTITY;
        if (immediate) {
            dtm.setColorMatrix(100, to);
            return;
        }
        ColorMatrixEvaluator colorMatrixEvaluator = COLOR_MATRIX_EVALUATOR;
        Object[] objArr = new Object[2];
        objArr[0] = from == null ? MATRIX_IDENTITY : from;
        objArr[1] = to;
        this.mColorMatrixAnimator = ValueAnimator.ofObject(colorMatrixEvaluator, objArr);
        this.mColorMatrixAnimator.setDuration(TRANSITION_DURATION);
        this.mColorMatrixAnimator.setInterpolator(AnimationUtils.loadInterpolator(getContext(), 17563661));
        this.mColorMatrixAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.display.ColorDisplayService.2
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public void onAnimationUpdate(ValueAnimator animator) {
                float[] value = (float[]) animator.getAnimatedValue();
                dtm.setColorMatrix(100, value);
            }
        });
        this.mColorMatrixAnimator.addListener(new AnimatorListenerAdapter() { // from class: com.android.server.display.ColorDisplayService.3
            private boolean mIsCancelled;

            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationCancel(Animator animator) {
                this.mIsCancelled = true;
            }

            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animator) {
                if (!this.mIsCancelled) {
                    dtm.setColorMatrix(100, to);
                }
                ColorDisplayService.this.mColorMatrixAnimator = null;
            }
        });
        this.mColorMatrixAnimator.start();
    }

    private void setMatrix(int colorTemperature, float[] outTemp) {
        if (outTemp.length != 16) {
            Slog.d(TAG, "The display transformation matrix must be 4x4");
            return;
        }
        Matrix.setIdentityM(this.mMatrixNight, 0);
        float squareTemperature = colorTemperature * colorTemperature;
        float red = (this.mColorTempCoefficients[0] * squareTemperature) + (colorTemperature * this.mColorTempCoefficients[1]) + this.mColorTempCoefficients[2];
        float green = (this.mColorTempCoefficients[3] * squareTemperature) + (colorTemperature * this.mColorTempCoefficients[4]) + this.mColorTempCoefficients[5];
        float blue = (this.mColorTempCoefficients[6] * squareTemperature) + (colorTemperature * this.mColorTempCoefficients[7]) + this.mColorTempCoefficients[8];
        outTemp[0] = red;
        outTemp[5] = green;
        outTemp[10] = blue;
    }

    public static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(), compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());
        return ldt.isAfter(compareTime) ? ldt.minusDays(1L) : ldt;
    }

    public static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(), compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());
        return ldt.isBefore(compareTime) ? ldt.plusDays(1L) : ldt;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class AutoMode implements ColorDisplayController.Callback {
        public abstract void onStart();

        public abstract void onStop();

        private AutoMode() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class CustomAutoMode extends AutoMode implements AlarmManager.OnAlarmListener {
        private final AlarmManager mAlarmManager;
        private LocalTime mEndTime;
        private LocalDateTime mLastActivatedTime;
        private LocalTime mStartTime;
        private final BroadcastReceiver mTimeChangedReceiver;

        CustomAutoMode() {
            super();
            this.mAlarmManager = (AlarmManager) ColorDisplayService.this.getContext().getSystemService("alarm");
            this.mTimeChangedReceiver = new BroadcastReceiver() { // from class: com.android.server.display.ColorDisplayService.CustomAutoMode.1
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    CustomAutoMode.this.updateActivated();
                }
            };
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateActivated() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime start = ColorDisplayService.getDateTimeBefore(this.mStartTime, now);
            LocalDateTime end = ColorDisplayService.getDateTimeAfter(this.mEndTime, start);
            boolean activate = now.isBefore(end);
            if (this.mLastActivatedTime != null && this.mLastActivatedTime.isBefore(now) && this.mLastActivatedTime.isAfter(start) && (this.mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                activate = ColorDisplayService.this.mController.isActivated();
            }
            if (ColorDisplayService.this.mIsActivated == null || ColorDisplayService.this.mIsActivated.booleanValue() != activate) {
                ColorDisplayService.this.mController.setActivated(activate);
            }
            updateNextAlarm(ColorDisplayService.this.mIsActivated, now);
        }

        private void updateNextAlarm(Boolean activated, LocalDateTime now) {
            if (activated != null) {
                LocalDateTime next = activated.booleanValue() ? ColorDisplayService.getDateTimeAfter(this.mEndTime, now) : ColorDisplayService.getDateTimeAfter(this.mStartTime, now);
                long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                this.mAlarmManager.setExact(1, millis, ColorDisplayService.TAG, this, null);
            }
        }

        @Override // com.android.server.display.ColorDisplayService.AutoMode
        public void onStart() {
            IntentFilter intentFilter = new IntentFilter("android.intent.action.TIME_SET");
            intentFilter.addAction("android.intent.action.TIMEZONE_CHANGED");
            ColorDisplayService.this.getContext().registerReceiver(this.mTimeChangedReceiver, intentFilter);
            this.mStartTime = ColorDisplayService.this.mController.getCustomStartTime();
            this.mEndTime = ColorDisplayService.this.mController.getCustomEndTime();
            this.mLastActivatedTime = ColorDisplayService.this.mController.getLastActivatedTime();
            updateActivated();
        }

        @Override // com.android.server.display.ColorDisplayService.AutoMode
        public void onStop() {
            ColorDisplayService.this.getContext().unregisterReceiver(this.mTimeChangedReceiver);
            this.mAlarmManager.cancel(this);
            this.mLastActivatedTime = null;
        }

        public void onActivated(boolean activated) {
            this.mLastActivatedTime = ColorDisplayService.this.mController.getLastActivatedTime();
            updateNextAlarm(Boolean.valueOf(activated), LocalDateTime.now());
        }

        public void onCustomStartTimeChanged(LocalTime startTime) {
            this.mStartTime = startTime;
            this.mLastActivatedTime = null;
            updateActivated();
        }

        public void onCustomEndTimeChanged(LocalTime endTime) {
            this.mEndTime = endTime;
            this.mLastActivatedTime = null;
            updateActivated();
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            Slog.d(ColorDisplayService.TAG, "onAlarm");
            updateActivated();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class TwilightAutoMode extends AutoMode implements TwilightListener {
        private final TwilightManager mTwilightManager;

        TwilightAutoMode() {
            super();
            this.mTwilightManager = (TwilightManager) ColorDisplayService.this.getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                return;
            }
            boolean activate = state.isNight();
            LocalDateTime lastActivatedTime = ColorDisplayService.this.mController.getLastActivatedTime();
            if (lastActivatedTime != null) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime sunrise = state.sunrise();
                LocalDateTime sunset = state.sunset();
                if (lastActivatedTime.isBefore(now) && (lastActivatedTime.isBefore(sunrise) ^ lastActivatedTime.isBefore(sunset))) {
                    activate = ColorDisplayService.this.mController.isActivated();
                }
            }
            if (ColorDisplayService.this.mIsActivated == null || ColorDisplayService.this.mIsActivated.booleanValue() != activate) {
                ColorDisplayService.this.mController.setActivated(activate);
            }
        }

        @Override // com.android.server.display.ColorDisplayService.AutoMode
        public void onStart() {
            this.mTwilightManager.registerListener(this, ColorDisplayService.this.mHandler);
            updateActivated(this.mTwilightManager.getLastTwilightState());
        }

        @Override // com.android.server.display.ColorDisplayService.AutoMode
        public void onStop() {
            this.mTwilightManager.unregisterListener(this);
        }

        public void onActivated(boolean activated) {
        }

        @Override // com.android.server.twilight.TwilightListener
        public void onTwilightStateChanged(TwilightState state) {
            StringBuilder sb = new StringBuilder();
            sb.append("onTwilightStateChanged: isNight=");
            sb.append(state == null ? null : Boolean.valueOf(state.isNight()));
            Slog.d(ColorDisplayService.TAG, sb.toString());
            updateActivated(state);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ColorMatrixEvaluator implements TypeEvaluator<float[]> {
        private final float[] mResultMatrix;

        private ColorMatrixEvaluator() {
            this.mResultMatrix = new float[16];
        }

        @Override // android.animation.TypeEvaluator
        public float[] evaluate(float fraction, float[] startValue, float[] endValue) {
            for (int i = 0; i < this.mResultMatrix.length; i++) {
                this.mResultMatrix[i] = MathUtils.lerp(startValue[i], endValue[i], fraction);
            }
            return this.mResultMatrix;
        }
    }
}
