package com.android.server;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.icu.text.DateFormat;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorService;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.DebugUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
/* loaded from: classes.dex */
public class VibratorService extends IVibratorService.Stub implements InputManager.InputDeviceListener {
    private static final boolean DEBUG = false;
    private static final long[] DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS = {0, 30, 100, 30};
    private static final long MAX_HAPTIC_FEEDBACK_DURATION = 5000;
    private static final int SCALE_HIGH = 1;
    private static final float SCALE_HIGH_GAMMA = 0.5f;
    private static final int SCALE_LOW = -1;
    private static final float SCALE_LOW_GAMMA = 1.5f;
    private static final int SCALE_LOW_MAX_AMPLITUDE = 192;
    private static final int SCALE_NONE = 0;
    private static final float SCALE_NONE_GAMMA = 1.0f;
    private static final int SCALE_VERY_HIGH = 2;
    private static final float SCALE_VERY_HIGH_GAMMA = 0.25f;
    private static final int SCALE_VERY_LOW = -2;
    private static final float SCALE_VERY_LOW_GAMMA = 2.0f;
    private static final int SCALE_VERY_LOW_MAX_AMPLITUDE = 168;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String TAG = "VibratorService";
    private final boolean mAllowPriorityVibrationsInLowPowerMode;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStatsService;
    private final Context mContext;
    @GuardedBy("mLock")
    private Vibration mCurrentVibration;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<VibrationEffect> mFallbackEffects;
    private int mHapticFeedbackIntensity;
    private InputManager mIm;
    private boolean mInputDeviceListenerRegistered;
    private boolean mLowPowerMode;
    private int mNotificationIntensity;
    private PowerManagerInternal mPowerManagerInternal;
    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private final SparseArray<ScaleLevel> mScaleLevels;
    private SettingsObserver mSettingObserver;
    private final boolean mSupportsAmplitudeControl;
    private volatile VibrateThread mThread;
    private boolean mVibrateInputDevicesSetting;
    private Vibrator mVibrator;
    private final PowerManager.WakeLock mWakeLock;
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH = new Handler();
    private final Object mLock = new Object();
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<>();
    private int mCurVibUid = -1;
    private final Runnable mVibrationEndRunnable = new Runnable() { // from class: com.android.server.VibratorService.3
        @Override // java.lang.Runnable
        public void run() {
            VibratorService.this.onVibrationFinished();
        }
    };
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.VibratorService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.intent.action.SCREEN_OFF")) {
                synchronized (VibratorService.this.mLock) {
                    if (VibratorService.this.mCurrentVibration != null && (!VibratorService.this.mCurrentVibration.isHapticFeedback() || !VibratorService.this.mCurrentVibration.isFromSystem())) {
                        VibratorService.this.doCancelVibrateLocked();
                    }
                }
            }
        }
    };

    static native boolean vibratorExists();

    static native void vibratorInit();

    static native void vibratorOff();

    static native void vibratorOn(long j);

    static native long vibratorPerformEffect(long j, long j2);

    static native void vibratorSetAmplitude(int i);

    static native boolean vibratorSupportsAmplitudeControl();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class Vibration implements IBinder.DeathRecipient {
        public VibrationEffect effect;
        public final String opPkg;
        public VibrationEffect originalEffect;
        public final long startTime;
        public final long startTimeDebug;
        public final IBinder token;
        public final int uid;
        public final int usageHint;

        private Vibration(IBinder token, VibrationEffect effect, int usageHint, int uid, String opPkg) {
            this.token = token;
            this.effect = effect;
            this.startTime = SystemClock.elapsedRealtime();
            this.startTimeDebug = System.currentTimeMillis();
            this.usageHint = usageHint;
            this.uid = uid;
            this.opPkg = opPkg;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (VibratorService.this.mLock) {
                if (this == VibratorService.this.mCurrentVibration) {
                    VibratorService.this.doCancelVibrateLocked();
                }
            }
        }

        public boolean hasTimeoutLongerThan(long millis) {
            long duration = this.effect.getDuration();
            return duration >= 0 && duration > millis;
        }

        public boolean isHapticFeedback() {
            if (this.effect instanceof VibrationEffect.Prebaked) {
                VibrationEffect.Prebaked prebaked = this.effect;
                switch (prebaked.getId()) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                        return true;
                    default:
                        Slog.w(VibratorService.TAG, "Unknown prebaked vibration effect, assuming it isn't haptic feedback.");
                        return false;
                }
            }
            long duration = this.effect.getDuration();
            return duration >= 0 && duration < VibratorService.MAX_HAPTIC_FEEDBACK_DURATION;
        }

        public boolean isNotification() {
            int i = this.usageHint;
            if (i != 5) {
                switch (i) {
                    case 7:
                    case 8:
                    case 9:
                        return true;
                    default:
                        return false;
                }
            }
            return true;
        }

        public boolean isRingtone() {
            return this.usageHint == 6;
        }

        public boolean isFromSystem() {
            return this.uid == 1000 || this.uid == 0 || "com.android.systemui".equals(this.opPkg);
        }

        public VibrationInfo toInfo() {
            return new VibrationInfo(this.startTimeDebug, this.effect, this.originalEffect, this.usageHint, this.uid, this.opPkg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class VibrationInfo {
        private final VibrationEffect mEffect;
        private final String mOpPkg;
        private final VibrationEffect mOriginalEffect;
        private final long mStartTimeDebug;
        private final int mUid;
        private final int mUsageHint;

        public VibrationInfo(long startTimeDebug, VibrationEffect effect, VibrationEffect originalEffect, int usageHint, int uid, String opPkg) {
            this.mStartTimeDebug = startTimeDebug;
            this.mEffect = effect;
            this.mOriginalEffect = originalEffect;
            this.mUsageHint = usageHint;
            this.mUid = uid;
            this.mOpPkg = opPkg;
        }

        public String toString() {
            return "startTime: " + DateFormat.getDateTimeInstance().format(new Date(this.mStartTimeDebug)) + ", effect: " + this.mEffect + ", originalEffect: " + this.mOriginalEffect + ", usageHint: " + this.mUsageHint + ", uid: " + this.mUid + ", opPkg: " + this.mOpPkg;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ScaleLevel {
        public final float gamma;
        public final int maxAmplitude;

        public ScaleLevel(float gamma) {
            this(gamma, 255);
        }

        public ScaleLevel(float gamma, int maxAmplitude) {
            this.gamma = gamma;
            this.maxAmplitude = maxAmplitude;
        }

        public String toString() {
            return "ScaleLevel{gamma=" + this.gamma + ", maxAmplitude=" + this.maxAmplitude + "}";
        }
    }

    VibratorService(Context context) {
        vibratorInit();
        vibratorOff();
        this.mSupportsAmplitudeControl = vibratorSupportsAmplitudeControl();
        this.mContext = context;
        PowerManager pm = (PowerManager) context.getSystemService("power");
        this.mWakeLock = pm.newWakeLock(1, "*vibrator*");
        this.mWakeLock.setReferenceCounted(true);
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService("batterystats"));
        this.mPreviousVibrationsLimit = this.mContext.getResources().getInteger(17694850);
        this.mDefaultVibrationAmplitude = this.mContext.getResources().getInteger(17694774);
        this.mAllowPriorityVibrationsInLowPowerMode = this.mContext.getResources().getBoolean(17956875);
        this.mPreviousVibrations = new LinkedList<>();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SCREEN_OFF");
        context.registerReceiver(this.mIntentReceiver, filter);
        VibrationEffect clickEffect = createEffectFromResource(17236057);
        VibrationEffect doubleClickEffect = VibrationEffect.createWaveform(DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS, -1);
        VibrationEffect heavyClickEffect = createEffectFromResource(17236017);
        VibrationEffect tickEffect = createEffectFromResource(17235999);
        this.mFallbackEffects = new SparseArray<>();
        this.mFallbackEffects.put(0, clickEffect);
        this.mFallbackEffects.put(1, doubleClickEffect);
        this.mFallbackEffects.put(2, tickEffect);
        this.mFallbackEffects.put(5, heavyClickEffect);
        this.mScaleLevels = new SparseArray<>();
        this.mScaleLevels.put(-2, new ScaleLevel(SCALE_VERY_LOW_GAMMA, SCALE_VERY_LOW_MAX_AMPLITUDE));
        this.mScaleLevels.put(-1, new ScaleLevel(SCALE_LOW_GAMMA, SCALE_LOW_MAX_AMPLITUDE));
        this.mScaleLevels.put(0, new ScaleLevel(1.0f));
        this.mScaleLevels.put(1, new ScaleLevel(0.5f));
        this.mScaleLevels.put(2, new ScaleLevel(SCALE_VERY_HIGH_GAMMA));
    }

    private VibrationEffect createEffectFromResource(int resId) {
        long[] timings = getLongIntArray(this.mContext.getResources(), resId);
        return createEffectFromTimings(timings);
    }

    private static VibrationEffect createEffectFromTimings(long[] timings) {
        if (timings == null || timings.length == 0) {
            return null;
        }
        if (timings.length == 1) {
            return VibrationEffect.createOneShot(timings[0], -1);
        }
        return VibrationEffect.createWaveform(timings, -1);
    }

    public void systemReady() {
        Trace.traceBegin(8388608L, "VibratorService#systemReady");
        try {
            this.mIm = (InputManager) this.mContext.getSystemService(InputManager.class);
            this.mVibrator = (Vibrator) this.mContext.getSystemService(Vibrator.class);
            this.mSettingObserver = new SettingsObserver(this.mH);
            this.mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
            this.mPowerManagerInternal.registerLowPowerModeObserver(new PowerManagerInternal.LowPowerModeListener() { // from class: com.android.server.VibratorService.1
                public int getServiceType() {
                    return 2;
                }

                public void onLowPowerModeChanged(PowerSaveState result) {
                    VibratorService.this.updateVibrators();
                }
            });
            this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("vibrate_input_devices"), true, this.mSettingObserver, -1);
            this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("haptic_feedback_intensity"), true, this.mSettingObserver, -1);
            this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("notification_vibration_intensity"), true, this.mSettingObserver, -1);
            this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.VibratorService.2
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    VibratorService.this.updateVibrators();
                }
            }, new IntentFilter("android.intent.action.USER_SWITCHED"), null, this.mH);
            updateVibrators();
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    /* loaded from: classes.dex */
    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean SelfChange) {
            VibratorService.this.updateVibrators();
        }
    }

    public boolean hasVibrator() {
        return doVibratorExists();
    }

    public boolean hasAmplitudeControl() {
        boolean z;
        synchronized (this.mInputDeviceVibrators) {
            z = this.mSupportsAmplitudeControl && this.mInputDeviceVibrators.isEmpty();
        }
        return z;
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid() || Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        this.mContext.enforcePermission("android.permission.UPDATE_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private static boolean verifyVibrationEffect(VibrationEffect effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
            return true;
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying VibrationEffect.", e);
            return false;
        }
    }

    private static long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i = 0; i < ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    public void vibrate(int uid, String opPkg, VibrationEffect effect, int usageHint, IBinder token) {
        Trace.traceBegin(8388608L, "vibrate");
        try {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.VIBRATE") != 0) {
                throw new SecurityException("Requires VIBRATE permission");
            }
            if (token == null) {
                Slog.e(TAG, "token must not be null");
                return;
            }
            verifyIncomingUid(uid);
            if (verifyVibrationEffect(effect)) {
                synchronized (this.mLock) {
                    if ((effect instanceof VibrationEffect.OneShot) && this.mCurrentVibration != null && (this.mCurrentVibration.effect instanceof VibrationEffect.OneShot)) {
                        VibrationEffect.OneShot newOneShot = (VibrationEffect.OneShot) effect;
                        VibrationEffect.OneShot currentOneShot = this.mCurrentVibration.effect;
                        if (this.mCurrentVibration.hasTimeoutLongerThan(newOneShot.getDuration()) && newOneShot.getAmplitude() == currentOneShot.getAmplitude()) {
                            return;
                        }
                    }
                    if (isRepeatingVibration(effect) || this.mCurrentVibration == null || !isRepeatingVibration(this.mCurrentVibration.effect)) {
                        Vibration vib = new Vibration(token, effect, usageHint, uid, opPkg);
                        linkVibration(vib);
                        long ident = Binder.clearCallingIdentity();
                        doCancelVibrateLocked();
                        startVibrationLocked(vib);
                        addToPreviousVibrationsLocked(vib);
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    private static boolean isRepeatingVibration(VibrationEffect effect) {
        return effect.getDuration() == JobStatus.NO_LATEST_RUNTIME;
    }

    private void addToPreviousVibrationsLocked(Vibration vib) {
        if (this.mPreviousVibrations.size() > this.mPreviousVibrationsLimit) {
            this.mPreviousVibrations.removeFirst();
        }
        this.mPreviousVibrations.addLast(vib.toInfo());
    }

    public void cancelVibrate(IBinder token) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.VIBRATE", "cancelVibrate");
        synchronized (this.mLock) {
            if (this.mCurrentVibration != null && this.mCurrentVibration.token == token) {
                long ident = Binder.clearCallingIdentity();
                doCancelVibrateLocked();
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void doCancelVibrateLocked() {
        Trace.asyncTraceEnd(8388608L, "vibration", 0);
        Trace.traceBegin(8388608L, "doCancelVibrateLocked");
        try {
            this.mH.removeCallbacks(this.mVibrationEndRunnable);
            if (this.mThread != null) {
                this.mThread.cancel();
                this.mThread = null;
            }
            doVibratorOff();
            reportFinishVibrationLocked();
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    public void onVibrationFinished() {
        synchronized (this.mLock) {
            doCancelVibrateLocked();
        }
    }

    @GuardedBy("mLock")
    private void startVibrationLocked(Vibration vib) {
        Trace.traceBegin(8388608L, "startVibrationLocked");
        try {
            if (isAllowedToVibrateLocked(vib)) {
                int intensity = getCurrentIntensityLocked(vib);
                if (intensity == 0) {
                    return;
                }
                if (!vib.isRingtone() || shouldVibrateForRingtone()) {
                    int mode = getAppOpMode(vib);
                    if (mode == 0) {
                        applyVibrationIntensityScalingLocked(vib, intensity);
                        startVibrationInnerLocked(vib);
                        return;
                    }
                    if (mode == 2) {
                        Slog.w(TAG, "Would be an error: vibrate from uid " + vib.uid);
                    }
                }
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    @GuardedBy("mLock")
    private void startVibrationInnerLocked(Vibration vib) {
        Trace.traceBegin(8388608L, "startVibrationInnerLocked");
        try {
            this.mCurrentVibration = vib;
            if (vib.effect instanceof VibrationEffect.OneShot) {
                Trace.asyncTraceBegin(8388608L, "vibration", 0);
                VibrationEffect.OneShot oneShot = vib.effect;
                doVibratorOn(oneShot.getDuration(), oneShot.getAmplitude(), vib.uid, vib.usageHint);
                this.mH.postDelayed(this.mVibrationEndRunnable, oneShot.getDuration());
            } else if (vib.effect instanceof VibrationEffect.Waveform) {
                Trace.asyncTraceBegin(8388608L, "vibration", 0);
                VibrationEffect.Waveform waveform = vib.effect;
                this.mThread = new VibrateThread(waveform, vib.uid, vib.usageHint);
                this.mThread.start();
            } else if (vib.effect instanceof VibrationEffect.Prebaked) {
                Trace.asyncTraceBegin(8388608L, "vibration", 0);
                long timeout = doVibratorPrebakedEffectLocked(vib);
                if (timeout > 0) {
                    this.mH.postDelayed(this.mVibrationEndRunnable, timeout);
                }
            } else {
                Slog.e(TAG, "Unknown vibration type, ignoring");
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    private boolean isAllowedToVibrateLocked(Vibration vib) {
        return !this.mLowPowerMode || vib.usageHint == 6 || vib.usageHint == 4 || vib.usageHint == 11 || vib.usageHint == 7;
    }

    private int getCurrentIntensityLocked(Vibration vib) {
        if (vib.isNotification() || vib.isRingtone()) {
            return this.mNotificationIntensity;
        }
        if (vib.isHapticFeedback()) {
            return this.mHapticFeedbackIntensity;
        }
        return 2;
    }

    private void applyVibrationIntensityScalingLocked(Vibration vib, int intensity) {
        int defaultIntensity;
        if (vib.effect instanceof VibrationEffect.Prebaked) {
            VibrationEffect.Prebaked prebaked = vib.effect;
            prebaked.setEffectStrength(intensityToEffectStrength(intensity));
            return;
        }
        if (vib.isNotification() || vib.isRingtone()) {
            defaultIntensity = this.mVibrator.getDefaultNotificationVibrationIntensity();
        } else if (vib.isHapticFeedback()) {
            defaultIntensity = this.mVibrator.getDefaultHapticFeedbackIntensity();
        } else {
            return;
        }
        ScaleLevel scale = this.mScaleLevels.get(intensity - defaultIntensity);
        if (scale == null) {
            Slog.e(TAG, "No configured scaling level! (current=" + intensity + ", default= " + defaultIntensity + ")");
            return;
        }
        VibrationEffect scaledEffect = null;
        if (vib.effect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot oneShot = vib.effect;
            scaledEffect = oneShot.resolve(this.mDefaultVibrationAmplitude).scale(scale.gamma, scale.maxAmplitude);
        } else if (vib.effect instanceof VibrationEffect.Waveform) {
            VibrationEffect.Waveform waveform = vib.effect;
            scaledEffect = waveform.resolve(this.mDefaultVibrationAmplitude).scale(scale.gamma, scale.maxAmplitude);
        } else {
            Slog.w(TAG, "Unable to apply intensity scaling, unknown VibrationEffect type");
        }
        if (scaledEffect != null) {
            vib.originalEffect = vib.effect;
            vib.effect = scaledEffect;
        }
    }

    private boolean shouldVibrateForRingtone() {
        AudioManager audioManager = (AudioManager) this.mContext.getSystemService(AudioManager.class);
        int ringerMode = audioManager.getRingerModeInternal();
        return Settings.System.getInt(this.mContext.getContentResolver(), "vibrate_when_ringing", 0) != 0 ? ringerMode != 0 : ringerMode == 1;
    }

    private int getAppOpMode(Vibration vib) {
        int mode = this.mAppOps.checkAudioOpNoThrow(3, vib.usageHint, vib.uid, vib.opPkg);
        if (mode == 0) {
            return this.mAppOps.startOpNoThrow(3, vib.uid, vib.opPkg);
        }
        return mode;
    }

    @GuardedBy("mLock")
    private void reportFinishVibrationLocked() {
        Trace.traceBegin(8388608L, "reportFinishVibrationLocked");
        try {
            if (this.mCurrentVibration != null) {
                this.mAppOps.finishOp(3, this.mCurrentVibration.uid, this.mCurrentVibration.opPkg);
                unlinkVibration(this.mCurrentVibration);
                this.mCurrentVibration = null;
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    private void linkVibration(Vibration vib) {
        if (vib.effect instanceof VibrationEffect.Waveform) {
            try {
                vib.token.linkToDeath(vib, 0);
            } catch (RemoteException e) {
            }
        }
    }

    private void unlinkVibration(Vibration vib) {
        if (vib.effect instanceof VibrationEffect.Waveform) {
            vib.token.unlinkToDeath(vib, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateVibrators() {
        synchronized (this.mLock) {
            boolean devicesUpdated = updateInputDeviceVibratorsLocked();
            boolean lowPowerModeUpdated = updateLowPowerModeLocked();
            updateVibrationIntensityLocked();
            if (devicesUpdated || lowPowerModeUpdated) {
                doCancelVibrateLocked();
            }
        }
    }

    private boolean updateInputDeviceVibratorsLocked() {
        boolean z;
        boolean changed = false;
        boolean vibrateInputDevices = false;
        try {
            if (Settings.System.getIntForUser(this.mContext.getContentResolver(), "vibrate_input_devices", -2) <= 0) {
                z = false;
            } else {
                z = true;
            }
            vibrateInputDevices = z;
        } catch (Settings.SettingNotFoundException e) {
        }
        if (vibrateInputDevices != this.mVibrateInputDevicesSetting) {
            changed = true;
            this.mVibrateInputDevicesSetting = vibrateInputDevices;
        }
        if (this.mVibrateInputDevicesSetting) {
            if (!this.mInputDeviceListenerRegistered) {
                this.mInputDeviceListenerRegistered = true;
                this.mIm.registerInputDeviceListener(this, this.mH);
            }
        } else if (this.mInputDeviceListenerRegistered) {
            this.mInputDeviceListenerRegistered = false;
            this.mIm.unregisterInputDeviceListener(this);
        }
        this.mInputDeviceVibrators.clear();
        if (this.mVibrateInputDevicesSetting) {
            int[] ids = this.mIm.getInputDeviceIds();
            for (int i : ids) {
                InputDevice device = this.mIm.getInputDevice(i);
                Vibrator vibrator = device.getVibrator();
                if (vibrator.hasVibrator()) {
                    this.mInputDeviceVibrators.add(vibrator);
                }
            }
            return true;
        }
        return changed;
    }

    private boolean updateLowPowerModeLocked() {
        boolean lowPowerMode = this.mPowerManagerInternal.getLowPowerState(2).batterySaverEnabled;
        if (lowPowerMode != this.mLowPowerMode) {
            this.mLowPowerMode = lowPowerMode;
            return true;
        }
        return false;
    }

    private void updateVibrationIntensityLocked() {
        this.mHapticFeedbackIntensity = Settings.System.getIntForUser(this.mContext.getContentResolver(), "haptic_feedback_intensity", this.mVibrator.getDefaultHapticFeedbackIntensity(), -2);
        this.mNotificationIntensity = Settings.System.getIntForUser(this.mContext.getContentResolver(), "notification_vibration_intensity", this.mVibrator.getDefaultNotificationVibrationIntensity(), -2);
    }

    @Override // android.hardware.input.InputManager.InputDeviceListener
    public void onInputDeviceAdded(int deviceId) {
        updateVibrators();
    }

    @Override // android.hardware.input.InputManager.InputDeviceListener
    public void onInputDeviceChanged(int deviceId) {
        updateVibrators();
    }

    @Override // android.hardware.input.InputManager.InputDeviceListener
    public void onInputDeviceRemoved(int deviceId) {
        updateVibrators();
    }

    private boolean doVibratorExists() {
        return vibratorExists();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doVibratorOn(long millis, int amplitude, int uid, int usageHint) {
        Trace.traceBegin(8388608L, "doVibratorOn");
        try {
            synchronized (this.mInputDeviceVibrators) {
                if (amplitude == -1) {
                    amplitude = this.mDefaultVibrationAmplitude;
                }
                noteVibratorOnLocked(uid, millis);
                int vibratorCount = this.mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usageHint).build();
                    for (int i = 0; i < vibratorCount; i++) {
                        this.mInputDeviceVibrators.get(i).vibrate(millis, attributes);
                    }
                } else {
                    vibratorOn(millis);
                    doVibratorSetAmplitude(amplitude);
                }
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doVibratorSetAmplitude(int amplitude) {
        if (this.mSupportsAmplitudeControl) {
            vibratorSetAmplitude(amplitude);
        }
    }

    private void doVibratorOff() {
        Trace.traceBegin(8388608L, "doVibratorOff");
        try {
            synchronized (this.mInputDeviceVibrators) {
                noteVibratorOffLocked();
                int vibratorCount = this.mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    for (int i = 0; i < vibratorCount; i++) {
                        this.mInputDeviceVibrators.get(i).cancel();
                    }
                } else {
                    vibratorOff();
                }
            }
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    @GuardedBy("mLock")
    private long doVibratorPrebakedEffectLocked(Vibration vib) {
        boolean usingInputDeviceVibrators;
        Trace.traceBegin(8388608L, "doVibratorPrebakedEffectLocked");
        try {
            VibrationEffect.Prebaked prebaked = vib.effect;
            synchronized (this.mInputDeviceVibrators) {
                usingInputDeviceVibrators = !this.mInputDeviceVibrators.isEmpty();
            }
            if (!usingInputDeviceVibrators) {
                long timeout = vibratorPerformEffect(prebaked.getId(), prebaked.getEffectStrength());
                if (timeout > 0) {
                    noteVibratorOnLocked(vib.uid, timeout);
                    return timeout;
                }
            }
            if (prebaked.shouldFallback()) {
                VibrationEffect effect = getFallbackEffect(prebaked.getId());
                if (effect == null) {
                    Slog.w(TAG, "Failed to play prebaked effect, no fallback");
                    return 0L;
                }
                Vibration fallbackVib = new Vibration(vib.token, effect, vib.usageHint, vib.uid, vib.opPkg);
                int intensity = getCurrentIntensityLocked(fallbackVib);
                linkVibration(fallbackVib);
                applyVibrationIntensityScalingLocked(fallbackVib, intensity);
                startVibrationInnerLocked(fallbackVib);
                return 0L;
            }
            return 0L;
        } finally {
            Trace.traceEnd(8388608L);
        }
    }

    private VibrationEffect getFallbackEffect(int effectId) {
        return this.mFallbackEffects.get(effectId);
    }

    private static int intensityToEffectStrength(int intensity) {
        switch (intensity) {
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            default:
                Slog.w(TAG, "Got unexpected vibration intensity: " + intensity);
                return 2;
        }
    }

    private void noteVibratorOnLocked(int uid, long millis) {
        try {
            this.mBatteryStatsService.noteVibratorOn(uid, millis);
            this.mCurVibUid = uid;
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOffLocked() {
        if (this.mCurVibUid >= 0) {
            try {
                this.mBatteryStatsService.noteVibratorOff(this.mCurVibUid);
            } catch (RemoteException e) {
            }
            this.mCurVibUid = -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class VibrateThread extends Thread {
        private boolean mForceStop;
        private final int mUid;
        private final int mUsageHint;
        private final VibrationEffect.Waveform mWaveform;

        VibrateThread(VibrationEffect.Waveform waveform, int uid, int usageHint) {
            this.mWaveform = waveform;
            this.mUid = uid;
            this.mUsageHint = usageHint;
            VibratorService.this.mTmpWorkSource.set(uid);
            VibratorService.this.mWakeLock.setWorkSource(VibratorService.this.mTmpWorkSource);
        }

        private long delayLocked(long duration) {
            Trace.traceBegin(8388608L, "delayLocked");
            long durationRemaining = duration;
            if (duration <= 0) {
                return 0L;
            }
            try {
                long bedtime = SystemClock.uptimeMillis() + duration;
                do {
                    try {
                        wait(durationRemaining);
                    } catch (InterruptedException e) {
                    }
                    if (this.mForceStop) {
                        break;
                    }
                    durationRemaining = bedtime - SystemClock.uptimeMillis();
                } while (durationRemaining > 0);
                return duration - durationRemaining;
            } finally {
                Trace.traceEnd(8388608L);
            }
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            Process.setThreadPriority(-8);
            VibratorService.this.mWakeLock.acquire();
            try {
                boolean finished = playWaveform();
                if (finished) {
                    VibratorService.this.onVibrationFinished();
                }
            } finally {
                VibratorService.this.mWakeLock.release();
            }
        }

        /* JADX WARN: Removed duplicated region for block: B:20:0x0064 A[Catch: all -> 0x0079, TryCatch #1 {all -> 0x007c, blocks: (B:3:0x000b, B:4:0x000c, B:5:0x0023, B:8:0x0029, B:14:0x003d, B:18:0x005e, B:20:0x0064, B:15:0x0055, B:26:0x0070, B:27:0x0074), top: B:36:0x000b }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public boolean playWaveform() {
            /*
                r21 = this;
                r1 = r21
                java.lang.String r0 = "playWaveform"
                r2 = 8388608(0x800000, double:4.144523E-317)
                android.os.Trace.traceBegin(r2, r0)
                monitor-enter(r21)     // Catch: java.lang.Throwable -> L7c
                android.os.VibrationEffect$Waveform r0 = r1.mWaveform     // Catch: java.lang.Throwable -> L79
                long[] r0 = r0.getTimings()     // Catch: java.lang.Throwable -> L79
                android.os.VibrationEffect$Waveform r4 = r1.mWaveform     // Catch: java.lang.Throwable -> L79
                int[] r4 = r4.getAmplitudes()     // Catch: java.lang.Throwable -> L79
                int r5 = r0.length     // Catch: java.lang.Throwable -> L79
                android.os.VibrationEffect$Waveform r6 = r1.mWaveform     // Catch: java.lang.Throwable -> L79
                int r6 = r6.getRepeatIndex()     // Catch: java.lang.Throwable -> L79
                r7 = 0
                r8 = 0
                r10 = r8
            L23:
                boolean r12 = r1.mForceStop     // Catch: java.lang.Throwable -> L79
                if (r12 != 0) goto L70
                if (r7 >= r5) goto L69
                r12 = r4[r7]     // Catch: java.lang.Throwable -> L79
                int r19 = r7 + 1
                r13 = r0[r7]     // Catch: java.lang.Throwable -> L79
                int r7 = (r13 > r8 ? 1 : (r13 == r8 ? 0 : -1))
                if (r7 > 0) goto L37
            L34:
                r7 = r19
                goto L23
            L37:
                if (r12 == 0) goto L5c
                int r7 = (r10 > r8 ? 1 : (r10 == r8 ? 0 : -1))
                if (r7 > 0) goto L55
                int r7 = r19 + (-1)
                long r15 = r1.getTotalOnDuration(r0, r4, r7, r6)     // Catch: java.lang.Throwable -> L79
                r8 = r13
                r14 = r15
                com.android.server.VibratorService r13 = com.android.server.VibratorService.this     // Catch: java.lang.Throwable -> L79
                int r7 = r1.mUid     // Catch: java.lang.Throwable -> L79
                int r10 = r1.mUsageHint     // Catch: java.lang.Throwable -> L79
                r16 = r12
                r17 = r7
                r18 = r10
                com.android.server.VibratorService.access$700(r13, r14, r16, r17, r18)     // Catch: java.lang.Throwable -> L79
                goto L5e
            L55:
                r8 = r13
                com.android.server.VibratorService r7 = com.android.server.VibratorService.this     // Catch: java.lang.Throwable -> L79
                com.android.server.VibratorService.access$800(r7, r12)     // Catch: java.lang.Throwable -> L79
                goto L5d
            L5c:
                r8 = r13
            L5d:
                r14 = r10
            L5e:
                long r10 = r1.delayLocked(r8)     // Catch: java.lang.Throwable -> L79
                if (r12 == 0) goto L65
                long r14 = r14 - r10
            L65:
                r10 = r14
                r7 = r19
                goto L6d
            L69:
                if (r6 >= 0) goto L6c
                goto L70
            L6c:
                r7 = r6
            L6d:
                r8 = 0
                goto L23
            L70:
                boolean r8 = r1.mForceStop     // Catch: java.lang.Throwable -> L79
                r8 = r8 ^ 1
                monitor-exit(r21)     // Catch: java.lang.Throwable -> L79
                android.os.Trace.traceEnd(r2)
                return r8
            L79:
                r0 = move-exception
                monitor-exit(r21)     // Catch: java.lang.Throwable -> L79
                throw r0     // Catch: java.lang.Throwable -> L7c
            L7c:
                r0 = move-exception
                android.os.Trace.traceEnd(r2)
                throw r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.VibratorService.VibrateThread.playWaveform():boolean");
        }

        public void cancel() {
            synchronized (this) {
                VibratorService.this.mThread.mForceStop = true;
                VibratorService.this.mThread.notify();
            }
        }

        private long getTotalOnDuration(long[] timings, int[] amplitudes, int startIndex, int repeatIndex) {
            int i = startIndex;
            long timing = 0;
            while (amplitudes[i] != 0) {
                int i2 = i + 1;
                timing += timings[i];
                if (i2 >= timings.length) {
                    if (repeatIndex < 0) {
                        break;
                    }
                    i = repeatIndex;
                    continue;
                } else {
                    i = i2;
                    continue;
                }
                if (i == startIndex) {
                    return 1000L;
                }
            }
            return timing;
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            pw.println("Vibrator Service:");
            synchronized (this.mLock) {
                pw.print("  mCurrentVibration=");
                if (this.mCurrentVibration != null) {
                    pw.println(this.mCurrentVibration.toInfo().toString());
                } else {
                    pw.println("null");
                }
                pw.println("  mLowPowerMode=" + this.mLowPowerMode);
                pw.println("  mHapticFeedbackIntensity=" + this.mHapticFeedbackIntensity);
                pw.println("  mNotificationIntensity=" + this.mNotificationIntensity);
                pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                pw.println("  Previous vibrations:");
                Iterator<VibrationInfo> it = this.mPreviousVibrations.iterator();
                while (it.hasNext()) {
                    VibrationInfo info = it.next();
                    pw.print("    ");
                    pw.println(info.toString());
                }
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
        new VibratorShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* loaded from: classes.dex */
    private final class VibratorShellCommand extends ShellCommand {
        private static final long MAX_VIBRATION_MS = 200;
        private final IBinder mToken;

        private VibratorShellCommand(IBinder token) {
            this.mToken = token;
        }

        public int onCommand(String cmd) {
            if ("vibrate".equals(cmd)) {
                return runVibrate();
            }
            return handleDefaultCommands(cmd);
        }

        private int runVibrate() {
            Trace.traceBegin(8388608L, "runVibrate");
            try {
                try {
                    int zenMode = Settings.Global.getInt(VibratorService.this.mContext.getContentResolver(), "zen_mode");
                    if (zenMode != 0) {
                        PrintWriter pw = getOutPrintWriter();
                        try {
                            pw.print("Ignoring because device is on DND mode ");
                            pw.println(DebugUtils.flagsToString(Settings.Global.class, "ZEN_MODE_", zenMode));
                            if (pw != null) {
                                $closeResource(null, pw);
                            }
                            return 0;
                        } finally {
                        }
                    }
                } catch (Settings.SettingNotFoundException e) {
                }
                long duration = Long.parseLong(getNextArgRequired());
                if (duration <= MAX_VIBRATION_MS) {
                    String description = getNextArg();
                    if (description == null) {
                        description = "Shell command";
                    }
                    VibrationEffect effect = VibrationEffect.createOneShot(duration, -1);
                    VibratorService.this.vibrate(Binder.getCallingUid(), description, effect, 0, this.mToken);
                    return 0;
                }
                throw new IllegalArgumentException("maximum duration is 200");
            } finally {
                Trace.traceEnd(8388608L);
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

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            try {
                pw.println("Vibrator commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                pw.println("  vibrate duration [description]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode.");
                pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                if (pw != null) {
                    $closeResource(null, pw);
                }
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    if (pw != null) {
                        $closeResource(th, pw);
                    }
                    throw th2;
                }
            }
        }
    }
}
