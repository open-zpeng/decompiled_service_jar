package com.android.server;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IUidObserver;
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
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
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
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DebugUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;
import android.view.InputDevice;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.job.controllers.JobStatus;
import com.android.server.notification.NotificationShellCmd;
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
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";
    private static final long MAX_HAPTIC_FEEDBACK_DURATION = 5000;
    private static final String RAMPING_RINGER_ENABLED = "ramping_ringer_enabled";
    private static final int SCALE_HIGH = 1;
    private static final float SCALE_HIGH_GAMMA = 0.5f;
    private static final int SCALE_LOW = -1;
    private static final float SCALE_LOW_GAMMA = 1.5f;
    private static final int SCALE_LOW_MAX_AMPLITUDE = 192;
    private static final int SCALE_MUTE = -100;
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
    private ExternalVibration mCurrentExternalVibration;
    @GuardedBy({"mLock"})
    private Vibration mCurrentVibration;
    private final int mDefaultVibrationAmplitude;
    private final SparseArray<VibrationEffect> mFallbackEffects;
    private int mHapticFeedbackIntensity;
    private InputManager mIm;
    private boolean mInputDeviceListenerRegistered;
    private boolean mLowPowerMode;
    private int mNotificationIntensity;
    private PowerManagerInternal mPowerManagerInternal;
    private final LinkedList<VibrationInfo> mPreviousAlarmVibrations;
    private final LinkedList<ExternalVibration> mPreviousExternalVibrations;
    private final LinkedList<VibrationInfo> mPreviousNotificationVibrations;
    private final LinkedList<VibrationInfo> mPreviousRingVibrations;
    private final LinkedList<VibrationInfo> mPreviousVibrations;
    private final int mPreviousVibrationsLimit;
    private int mRingIntensity;
    private final SparseArray<ScaleLevel> mScaleLevels;
    private SettingsObserver mSettingObserver;
    private final boolean mSupportsAmplitudeControl;
    private final boolean mSupportsExternalControl;
    private volatile VibrateThread mThread;
    private boolean mVibrateInputDevicesSetting;
    private Vibrator mVibrator;
    private boolean mVibratorUnderExternalControl;
    private final PowerManager.WakeLock mWakeLock;
    private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();
    private final WorkSource mTmpWorkSource = new WorkSource();
    private final Handler mH = new Handler();
    private final Object mLock = new Object();
    private final ArrayList<Vibrator> mInputDeviceVibrators = new ArrayList<>();
    private int mCurVibUid = -1;
    private final IUidObserver mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.VibratorService.1
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            VibratorService.this.mProcStatesCache.put(uid, Integer.valueOf(procState));
        }

        public void onUidGone(int uid, boolean disabled) {
            VibratorService.this.mProcStatesCache.delete(uid);
        }

        public void onUidActive(int uid) {
        }

        public void onUidIdle(int uid, boolean disabled) {
        }

        public void onUidCachedChanged(int uid, boolean cached) {
        }
    };
    private final Runnable mVibrationEndRunnable = new Runnable() { // from class: com.android.server.VibratorService.4
        @Override // java.lang.Runnable
        public void run() {
            VibratorService.this.onVibrationFinished();
        }
    };
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.VibratorService.5
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

    static native void vibratorSetExternalControl(boolean z);

    static native boolean vibratorSupportsAmplitudeControl();

    static native boolean vibratorSupportsExternalControl();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class Vibration implements IBinder.DeathRecipient {
        public final AudioAttributes attrs;
        public VibrationEffect effect;
        public final String opPkg;
        public VibrationEffect originalEffect;
        public final String reason;
        public final long startTime;
        public final long startTimeDebug;
        public final IBinder token;
        public final int uid;

        private Vibration(IBinder token, VibrationEffect effect, AudioAttributes attrs, int uid, String opPkg, String reason) {
            this.token = token;
            this.effect = effect;
            this.startTime = SystemClock.elapsedRealtime();
            this.startTimeDebug = System.currentTimeMillis();
            this.attrs = attrs;
            this.uid = uid;
            this.opPkg = opPkg;
            this.reason = reason;
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
            VibratorService vibratorService = VibratorService.this;
            if (VibratorService.isHapticFeedback(this.attrs.getUsage())) {
                return true;
            }
            VibrationEffect.Prebaked prebaked = this.effect;
            if (prebaked instanceof VibrationEffect.Prebaked) {
                int id = prebaked.getId();
                if (id == 0 || id == 1 || id == 2 || id == 3 || id == 4 || id == 5 || id == 21) {
                    return true;
                }
                Slog.w(VibratorService.TAG, "Unknown prebaked vibration effect, assuming it isn't haptic feedback.");
                return false;
            }
            long duration = prebaked.getDuration();
            return duration >= 0 && duration < VibratorService.MAX_HAPTIC_FEEDBACK_DURATION;
        }

        public boolean isNotification() {
            VibratorService vibratorService = VibratorService.this;
            return VibratorService.isNotification(this.attrs.getUsage());
        }

        public boolean isRingtone() {
            VibratorService vibratorService = VibratorService.this;
            return VibratorService.isRingtone(this.attrs.getUsage());
        }

        public boolean isAlarm() {
            VibratorService vibratorService = VibratorService.this;
            return VibratorService.isAlarm(this.attrs.getUsage());
        }

        public boolean isFromSystem() {
            int i = this.uid;
            return i == 1000 || i == 0 || VibratorService.SYSTEM_UI_PACKAGE.equals(this.opPkg);
        }

        public VibrationInfo toInfo() {
            return new VibrationInfo(this.startTimeDebug, this.effect, this.originalEffect, this.attrs, this.uid, this.opPkg, this.reason);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class VibrationInfo {
        private final AudioAttributes mAttrs;
        private final VibrationEffect mEffect;
        private final String mOpPkg;
        private final VibrationEffect mOriginalEffect;
        private final String mReason;
        private final long mStartTimeDebug;
        private final int mUid;

        public VibrationInfo(long startTimeDebug, VibrationEffect effect, VibrationEffect originalEffect, AudioAttributes attrs, int uid, String opPkg, String reason) {
            this.mStartTimeDebug = startTimeDebug;
            this.mEffect = effect;
            this.mOriginalEffect = originalEffect;
            this.mAttrs = attrs;
            this.mUid = uid;
            this.mOpPkg = opPkg;
            this.mReason = reason;
        }

        public String toString() {
            return "startTime: " + DateFormat.getDateTimeInstance().format(new Date(this.mStartTimeDebug)) + ", effect: " + this.mEffect + ", originalEffect: " + this.mOriginalEffect + ", attrs: " + this.mAttrs + ", uid: " + this.mUid + ", opPkg: " + this.mOpPkg + ", reason: " + this.mReason;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public VibratorService(Context context) {
        vibratorInit();
        vibratorOff();
        this.mSupportsAmplitudeControl = vibratorSupportsAmplitudeControl();
        this.mSupportsExternalControl = vibratorSupportsExternalControl();
        this.mContext = context;
        PowerManager pm = (PowerManager) context.getSystemService("power");
        this.mWakeLock = pm.newWakeLock(1, "*vibrator*");
        this.mWakeLock.setReferenceCounted(true);
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mBatteryStatsService = IBatteryStats.Stub.asInterface(ServiceManager.getService("batterystats"));
        this.mPreviousVibrationsLimit = this.mContext.getResources().getInteger(17694875);
        this.mDefaultVibrationAmplitude = this.mContext.getResources().getInteger(17694784);
        this.mAllowPriorityVibrationsInLowPowerMode = this.mContext.getResources().getBoolean(17891345);
        this.mPreviousRingVibrations = new LinkedList<>();
        this.mPreviousNotificationVibrations = new LinkedList<>();
        this.mPreviousAlarmVibrations = new LinkedList<>();
        this.mPreviousVibrations = new LinkedList<>();
        this.mPreviousExternalVibrations = new LinkedList<>();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SCREEN_OFF");
        context.registerReceiver(this.mIntentReceiver, filter);
        VibrationEffect clickEffect = createEffectFromResource(17236086);
        VibrationEffect doubleClickEffect = VibrationEffect.createWaveform(DOUBLE_CLICK_EFFECT_FALLBACK_TIMINGS, -1);
        VibrationEffect heavyClickEffect = createEffectFromResource(17236044);
        VibrationEffect tickEffect = createEffectFromResource(17236002);
        this.mFallbackEffects = new SparseArray<>();
        this.mFallbackEffects.put(0, clickEffect);
        this.mFallbackEffects.put(1, doubleClickEffect);
        this.mFallbackEffects.put(2, tickEffect);
        this.mFallbackEffects.put(5, heavyClickEffect);
        this.mFallbackEffects.put(21, VibrationEffect.get(2, false));
        this.mScaleLevels = new SparseArray<>();
        this.mScaleLevels.put(-2, new ScaleLevel(SCALE_VERY_LOW_GAMMA, SCALE_VERY_LOW_MAX_AMPLITUDE));
        this.mScaleLevels.put(-1, new ScaleLevel(SCALE_LOW_GAMMA, SCALE_LOW_MAX_AMPLITUDE));
        this.mScaleLevels.put(0, new ScaleLevel(1.0f));
        this.mScaleLevels.put(1, new ScaleLevel(0.5f));
        this.mScaleLevels.put(2, new ScaleLevel(SCALE_VERY_HIGH_GAMMA));
        ServiceManager.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
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
            this.mPowerManagerInternal.registerLowPowerModeObserver(new PowerManagerInternal.LowPowerModeListener() { // from class: com.android.server.VibratorService.2
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
            this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("ring_vibration_intensity"), true, this.mSettingObserver, -1);
            this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.VibratorService.3
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    VibratorService.this.updateVibrators();
                }
            }, new IntentFilter("android.intent.action.USER_SWITCHED"), null, this.mH);
            try {
                ActivityManager.getService().registerUidObserver(this.mUidObserver, 3, -1, (String) null);
            } catch (RemoteException e) {
            }
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

    public void vibrate(int uid, String opPkg, VibrationEffect effect, AudioAttributes attrs, String reason, IBinder token) {
        AudioAttributes attrs2;
        Trace.traceBegin(8388608L, "vibrate, reason = " + reason);
        try {
            if (!hasPermission("android.permission.VIBRATE")) {
                throw new SecurityException("Requires VIBRATE permission");
            }
            if (token == null) {
                Slog.e(TAG, "token must not be null");
                Trace.traceEnd(8388608L);
                return;
            }
            verifyIncomingUid(uid);
            if (!verifyVibrationEffect(effect)) {
                Trace.traceEnd(8388608L);
                return;
            }
            AudioAttributes attrs3 = attrs == null ? new AudioAttributes.Builder().setUsage(0).build() : attrs;
            try {
                if (!shouldBypassDnd(attrs3) || hasPermission("android.permission.WRITE_SECURE_SETTINGS") || hasPermission("android.permission.MODIFY_PHONE_STATE") || hasPermission("android.permission.MODIFY_AUDIO_ROUTING")) {
                    attrs2 = attrs3;
                } else {
                    int flags = attrs3.getAllFlags() & (-65);
                    attrs2 = new AudioAttributes.Builder(attrs3).replaceFlags(flags).build();
                }
            } catch (Throwable th) {
                th = th;
            }
            try {
                try {
                    synchronized (this.mLock) {
                        try {
                            if ((effect instanceof VibrationEffect.OneShot) && this.mCurrentVibration != null && (this.mCurrentVibration.effect instanceof VibrationEffect.OneShot)) {
                                VibrationEffect.OneShot newOneShot = (VibrationEffect.OneShot) effect;
                                VibrationEffect.OneShot currentOneShot = this.mCurrentVibration.effect;
                                if (this.mCurrentVibration.hasTimeoutLongerThan(newOneShot.getDuration()) && newOneShot.getAmplitude() == currentOneShot.getAmplitude()) {
                                    Trace.traceEnd(8388608L);
                                    return;
                                }
                            }
                            if (this.mCurrentExternalVibration != null) {
                                Trace.traceEnd(8388608L);
                            } else if (!isRepeatingVibration(effect) && this.mCurrentVibration != null && isRepeatingVibration(this.mCurrentVibration.effect)) {
                                Trace.traceEnd(8388608L);
                            } else {
                                Vibration vib = new Vibration(token, effect, attrs2, uid, opPkg, reason);
                                if (this.mProcStatesCache.get(uid, 7).intValue() <= 7 || vib.isNotification() || vib.isRingtone() || vib.isAlarm()) {
                                    linkVibration(vib);
                                    long ident = Binder.clearCallingIdentity();
                                    doCancelVibrateLocked();
                                    startVibrationLocked(vib);
                                    addToPreviousVibrationsLocked(vib);
                                    Binder.restoreCallingIdentity(ident);
                                    Trace.traceEnd(8388608L);
                                    return;
                                }
                                Slog.e(TAG, "Ignoring incoming vibration as process with uid= " + uid + " is background, attrs= " + vib.attrs);
                                Trace.traceEnd(8388608L);
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            } catch (Throwable th4) {
                th = th4;
                Trace.traceEnd(8388608L);
                throw th;
            }
        } catch (Throwable th5) {
            th = th5;
        }
    }

    private boolean hasPermission(String permission) {
        return this.mContext.checkCallingOrSelfPermission(permission) == 0;
    }

    private static boolean isRepeatingVibration(VibrationEffect effect) {
        return effect.getDuration() == JobStatus.NO_LATEST_RUNTIME;
    }

    private void addToPreviousVibrationsLocked(Vibration vib) {
        LinkedList<VibrationInfo> previousVibrations;
        if (vib.isRingtone()) {
            previousVibrations = this.mPreviousRingVibrations;
        } else if (vib.isNotification()) {
            previousVibrations = this.mPreviousNotificationVibrations;
        } else if (vib.isAlarm()) {
            previousVibrations = this.mPreviousAlarmVibrations;
        } else {
            previousVibrations = this.mPreviousVibrations;
        }
        if (previousVibrations.size() > this.mPreviousVibrationsLimit) {
            previousVibrations.removeFirst();
        }
        previousVibrations.addLast(vib.toInfo());
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
    @GuardedBy({"mLock"})
    public void doCancelVibrateLocked() {
        Trace.asyncTraceEnd(8388608L, "vibration", 0);
        Trace.traceBegin(8388608L, "doCancelVibrateLocked");
        try {
            this.mH.removeCallbacks(this.mVibrationEndRunnable);
            if (this.mThread != null) {
                this.mThread.cancel();
                this.mThread = null;
            }
            if (this.mCurrentExternalVibration != null) {
                this.mCurrentExternalVibration.mute();
                this.mCurrentExternalVibration = null;
                setVibratorUnderExternalControl(false);
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

    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
    private void startVibrationInnerLocked(Vibration vib) {
        Trace.traceBegin(8388608L, "startVibrationInnerLocked");
        try {
            this.mCurrentVibration = vib;
            if (vib.effect instanceof VibrationEffect.OneShot) {
                Trace.asyncTraceBegin(8388608L, "vibration", 0);
                VibrationEffect.OneShot oneShot = vib.effect;
                doVibratorOn(oneShot.getDuration(), oneShot.getAmplitude(), vib.uid, vib.attrs);
                this.mH.postDelayed(this.mVibrationEndRunnable, oneShot.getDuration());
            } else if (vib.effect instanceof VibrationEffect.Waveform) {
                Trace.asyncTraceBegin(8388608L, "vibration", 0);
                VibrationEffect.Waveform waveform = vib.effect;
                this.mThread = new VibrateThread(waveform, vib.uid, vib.attrs);
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
        return !this.mLowPowerMode || vib.attrs.getUsage() == 6 || vib.attrs.getUsage() == 4 || vib.attrs.getUsage() == 11 || vib.attrs.getUsage() == 7;
    }

    private int getCurrentIntensityLocked(Vibration vib) {
        if (vib.isRingtone()) {
            return this.mRingIntensity;
        }
        if (vib.isNotification()) {
            return this.mNotificationIntensity;
        }
        if (vib.isHapticFeedback()) {
            return this.mHapticFeedbackIntensity;
        }
        if (vib.isAlarm()) {
            return 3;
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
        if (vib.isRingtone()) {
            defaultIntensity = this.mVibrator.getDefaultRingVibrationIntensity();
        } else if (vib.isNotification()) {
            defaultIntensity = this.mVibrator.getDefaultNotificationVibrationIntensity();
        } else if (vib.isHapticFeedback()) {
            defaultIntensity = this.mVibrator.getDefaultHapticFeedbackIntensity();
        } else if (vib.isAlarm()) {
            defaultIntensity = 3;
        } else {
            return;
        }
        ScaleLevel scale = this.mScaleLevels.get(intensity - defaultIntensity);
        if (scale == null) {
            Slog.e(TAG, "No configured scaling level! (current=" + intensity + ", default= " + defaultIntensity + ")");
            return;
        }
        VibrationEffect.OneShot oneShot = null;
        if (vib.effect instanceof VibrationEffect.OneShot) {
            VibrationEffect.OneShot oneShot2 = vib.effect;
            oneShot = oneShot2.resolve(this.mDefaultVibrationAmplitude).scale(scale.gamma, scale.maxAmplitude);
        } else if (!(vib.effect instanceof VibrationEffect.Waveform)) {
            Slog.w(TAG, "Unable to apply intensity scaling, unknown VibrationEffect type");
        } else {
            VibrationEffect.Waveform waveform = vib.effect;
            oneShot = waveform.resolve(this.mDefaultVibrationAmplitude).scale(scale.gamma, scale.maxAmplitude);
        }
        if (oneShot != null) {
            vib.originalEffect = vib.effect;
            vib.effect = oneShot;
        }
    }

    private boolean shouldVibrateForRingtone() {
        AudioManager audioManager = (AudioManager) this.mContext.getSystemService(AudioManager.class);
        int ringerMode = audioManager.getRingerModeInternal();
        return Settings.System.getInt(this.mContext.getContentResolver(), "vibrate_when_ringing", 0) != 0 ? ringerMode != 0 : (Settings.Global.getInt(this.mContext.getContentResolver(), "apply_ramping_ringer", 0) == 0 || !DeviceConfig.getBoolean("telephony", RAMPING_RINGER_ENABLED, false)) ? ringerMode == 1 : ringerMode != 0;
    }

    private static boolean shouldBypassDnd(AudioAttributes attrs) {
        return (attrs.getAllFlags() & 64) != 0;
    }

    private int getAppOpMode(Vibration vib) {
        int mode = this.mAppOps.checkAudioOpNoThrow(3, vib.attrs.getUsage(), vib.uid, vib.opPkg);
        if (mode == 0) {
            mode = this.mAppOps.startOpNoThrow(3, vib.uid, vib.opPkg);
        }
        if (mode == 1 && shouldBypassDnd(vib.attrs)) {
            Slog.d(TAG, "Bypassing DND for vibration: " + vib);
            return 0;
        }
        return mode;
    }

    @GuardedBy({"mLock"})
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
        boolean changed = false;
        boolean vibrateInputDevices = false;
        try {
            vibrateInputDevices = Settings.System.getIntForUser(this.mContext.getContentResolver(), "vibrate_input_devices", -2) > 0;
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
        this.mRingIntensity = Settings.System.getIntForUser(this.mContext.getContentResolver(), "ring_vibration_intensity", this.mVibrator.getDefaultRingVibrationIntensity(), -2);
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
    public void doVibratorOn(long millis, int amplitude, int uid, AudioAttributes attrs) {
        Trace.traceBegin(8388608L, "doVibratorOn");
        try {
            synchronized (this.mInputDeviceVibrators) {
                if (amplitude == -1) {
                    amplitude = this.mDefaultVibrationAmplitude;
                }
                noteVibratorOnLocked(uid, millis);
                int vibratorCount = this.mInputDeviceVibrators.size();
                if (vibratorCount != 0) {
                    for (int i = 0; i < vibratorCount; i++) {
                        this.mInputDeviceVibrators.get(i).vibrate(millis, attrs);
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

    @GuardedBy({"mLock"})
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
                IBinder iBinder = vib.token;
                AudioAttributes audioAttributes = vib.attrs;
                int i = vib.uid;
                String str = vib.opPkg;
                Vibration fallbackVib = new Vibration(iBinder, effect, audioAttributes, i, str, vib.reason + " (fallback)");
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
        if (intensity != 1) {
            if (intensity != 2) {
                if (intensity != 3) {
                    Slog.w(TAG, "Got unexpected vibration intensity: " + intensity);
                    return 2;
                }
                return 2;
            }
            return 1;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isNotification(int usageHint) {
        switch (usageHint) {
            case 5:
            case 7:
            case 8:
            case 9:
            case 10:
                return true;
            case 6:
            default:
                return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isRingtone(int usageHint) {
        return usageHint == 6;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isHapticFeedback(int usageHint) {
        return usageHint == 13;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isAlarm(int usageHint) {
        return usageHint == 4;
    }

    private void noteVibratorOnLocked(int uid, long millis) {
        try {
            this.mBatteryStatsService.noteVibratorOn(uid, millis);
            StatsLog.write_non_chained(84, uid, null, 1, millis);
            this.mCurVibUid = uid;
        } catch (RemoteException e) {
        }
    }

    private void noteVibratorOffLocked() {
        int i = this.mCurVibUid;
        if (i >= 0) {
            try {
                this.mBatteryStatsService.noteVibratorOff(i);
                StatsLog.write_non_chained(84, this.mCurVibUid, null, 0, 0);
            } catch (RemoteException e) {
            }
            this.mCurVibUid = -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setVibratorUnderExternalControl(boolean externalControl) {
        this.mVibratorUnderExternalControl = externalControl;
        vibratorSetExternalControl(externalControl);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class VibrateThread extends Thread {
        private final AudioAttributes mAttrs;
        private boolean mForceStop;
        private final int mUid;
        private final VibrationEffect.Waveform mWaveform;

        VibrateThread(VibrationEffect.Waveform waveform, int uid, AudioAttributes attrs) {
            this.mWaveform = waveform;
            this.mUid = uid;
            this.mAttrs = attrs;
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

        public boolean playWaveform() {
            boolean z;
            long duration;
            Trace.traceBegin(8388608L, "playWaveform");
            try {
                synchronized (this) {
                    long[] timings = this.mWaveform.getTimings();
                    int[] amplitudes = this.mWaveform.getAmplitudes();
                    int len = timings.length;
                    int repeat = this.mWaveform.getRepeatIndex();
                    int index = 0;
                    long j = 0;
                    long onDuration = 0;
                    while (!this.mForceStop) {
                        if (index < len) {
                            int amplitude = amplitudes[index];
                            int index2 = index + 1;
                            long duration2 = timings[index];
                            if (duration2 <= j) {
                                index = index2;
                            } else {
                                if (amplitude == 0) {
                                    duration = duration2;
                                } else if (onDuration <= j) {
                                    long onDuration2 = getTotalOnDuration(timings, amplitudes, index2 - 1, repeat);
                                    duration = duration2;
                                    VibratorService.this.doVibratorOn(onDuration2, amplitude, this.mUid, this.mAttrs);
                                    onDuration = onDuration2;
                                } else {
                                    duration = duration2;
                                    VibratorService.this.doVibratorSetAmplitude(amplitude);
                                }
                                long waitTime = delayLocked(duration);
                                if (amplitude != 0) {
                                    onDuration -= waitTime;
                                }
                                index = index2;
                                j = 0;
                            }
                        } else if (repeat < 0) {
                            break;
                        } else {
                            index = repeat;
                            j = 0;
                        }
                    }
                    z = !this.mForceStop;
                }
                return z;
            } finally {
                Trace.traceEnd(8388608L);
            }
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
                if (i2 < timings.length) {
                    i = i2;
                    continue;
                } else if (repeatIndex < 0) {
                    break;
                } else {
                    i = repeatIndex;
                    repeatIndex = -1;
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
                pw.print("  mCurrentExternalVibration=");
                if (this.mCurrentExternalVibration != null) {
                    pw.println(this.mCurrentExternalVibration.toString());
                } else {
                    pw.println("null");
                }
                pw.println("  mVibratorUnderExternalControl=" + this.mVibratorUnderExternalControl);
                pw.println("  mLowPowerMode=" + this.mLowPowerMode);
                pw.println("  mHapticFeedbackIntensity=" + this.mHapticFeedbackIntensity);
                pw.println("  mNotificationIntensity=" + this.mNotificationIntensity);
                pw.println("  mRingIntensity=" + this.mRingIntensity);
                pw.println("");
                pw.println("  Previous ring vibrations:");
                Iterator<VibrationInfo> it = this.mPreviousRingVibrations.iterator();
                while (it.hasNext()) {
                    VibrationInfo info = it.next();
                    pw.print("    ");
                    pw.println(info.toString());
                }
                pw.println("  Previous notification vibrations:");
                Iterator<VibrationInfo> it2 = this.mPreviousNotificationVibrations.iterator();
                while (it2.hasNext()) {
                    VibrationInfo info2 = it2.next();
                    pw.print("    ");
                    pw.println(info2.toString());
                }
                pw.println("  Previous alarm vibrations:");
                Iterator<VibrationInfo> it3 = this.mPreviousAlarmVibrations.iterator();
                while (it3.hasNext()) {
                    VibrationInfo info3 = it3.next();
                    pw.print("    ");
                    pw.println(info3.toString());
                }
                pw.println("  Previous vibrations:");
                Iterator<VibrationInfo> it4 = this.mPreviousVibrations.iterator();
                while (it4.hasNext()) {
                    VibrationInfo info4 = it4.next();
                    pw.print("    ");
                    pw.println(info4.toString());
                }
                pw.println("  Previous external vibrations:");
                Iterator<ExternalVibration> it5 = this.mPreviousExternalVibrations.iterator();
                while (it5.hasNext()) {
                    ExternalVibration vib = it5.next();
                    pw.print("    ");
                    pw.println(vib.toString());
                }
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
        new VibratorShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ExternalVibratorService extends IExternalVibratorService.Stub {
        ExternalVibrationDeathRecipient mCurrentExternalDeathRecipient;

        ExternalVibratorService() {
        }

        public int onExternalVibrationStart(ExternalVibration vib) {
            int defaultIntensity;
            int currentIntensity;
            int scaleLevel;
            if (VibratorService.this.mSupportsExternalControl) {
                if (ActivityManager.checkComponentPermission("android.permission.VIBRATE", vib.getUid(), -1, true) == 0) {
                    synchronized (VibratorService.this.mLock) {
                        if (!vib.equals(VibratorService.this.mCurrentExternalVibration)) {
                            if (VibratorService.this.mCurrentExternalVibration == null) {
                                VibratorService.this.doCancelVibrateLocked();
                                VibratorService.this.setVibratorUnderExternalControl(true);
                            }
                            VibratorService.this.mCurrentExternalVibration = vib;
                            this.mCurrentExternalDeathRecipient = new ExternalVibrationDeathRecipient();
                            VibratorService.this.mCurrentExternalVibration.linkToDeath(this.mCurrentExternalDeathRecipient);
                            if (VibratorService.this.mPreviousExternalVibrations.size() > VibratorService.this.mPreviousVibrationsLimit) {
                                VibratorService.this.mPreviousExternalVibrations.removeFirst();
                            }
                            VibratorService.this.mPreviousExternalVibrations.addLast(vib);
                        }
                        int usage = vib.getAudioAttributes().getUsage();
                        if (VibratorService.isRingtone(usage)) {
                            defaultIntensity = VibratorService.this.mVibrator.getDefaultRingVibrationIntensity();
                            currentIntensity = VibratorService.this.mRingIntensity;
                        } else if (VibratorService.isNotification(usage)) {
                            defaultIntensity = VibratorService.this.mVibrator.getDefaultNotificationVibrationIntensity();
                            currentIntensity = VibratorService.this.mNotificationIntensity;
                        } else if (VibratorService.isHapticFeedback(usage)) {
                            defaultIntensity = VibratorService.this.mVibrator.getDefaultHapticFeedbackIntensity();
                            currentIntensity = VibratorService.this.mHapticFeedbackIntensity;
                        } else if (VibratorService.isAlarm(usage)) {
                            defaultIntensity = 3;
                            currentIntensity = 3;
                        } else {
                            defaultIntensity = 0;
                            currentIntensity = 0;
                        }
                        scaleLevel = currentIntensity - defaultIntensity;
                    }
                    if (scaleLevel >= -2 && scaleLevel <= 2) {
                        return scaleLevel;
                    }
                    Slog.w(VibratorService.TAG, "Error in scaling calculations, ended up with invalid scale level " + scaleLevel + " for vibration " + vib);
                    return 0;
                }
                Slog.w(VibratorService.TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid() + " tried to play externally controlled vibration without VIBRATE permission, ignoring.");
                return VibratorService.SCALE_MUTE;
            }
            return VibratorService.SCALE_MUTE;
        }

        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (VibratorService.this.mLock) {
                if (vib.equals(VibratorService.this.mCurrentExternalVibration)) {
                    VibratorService.this.mCurrentExternalVibration.unlinkToDeath(this.mCurrentExternalDeathRecipient);
                    this.mCurrentExternalDeathRecipient = null;
                    VibratorService.this.mCurrentExternalVibration = null;
                    VibratorService.this.setVibratorUnderExternalControl(false);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public class ExternalVibrationDeathRecipient implements IBinder.DeathRecipient {
            private ExternalVibrationDeathRecipient() {
            }

            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                synchronized (VibratorService.this.mLock) {
                    ExternalVibratorService.this.onExternalVibrationStop(VibratorService.this.mCurrentExternalVibration);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class VibratorShellCommand extends ShellCommand {
        private final IBinder mToken;

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public final class CommonOptions {
            public boolean force;

            private CommonOptions() {
                this.force = false;
            }

            public void check(String opt) {
                if (((opt.hashCode() == 1497 && opt.equals("-f")) ? (char) 0 : (char) 65535) == 0) {
                    this.force = true;
                }
            }
        }

        private VibratorShellCommand(IBinder token) {
            this.mToken = token;
        }

        public int onCommand(String cmd) {
            if ("vibrate".equals(cmd)) {
                return runVibrate();
            }
            if ("waveform".equals(cmd)) {
                return runWaveform();
            }
            if ("prebaked".equals(cmd)) {
                return runPrebaked();
            }
            if ("cancel".equals(cmd)) {
                VibratorService.this.cancelVibrate(this.mToken);
                return 0;
            }
            return handleDefaultCommands(cmd);
        }

        private boolean checkDoNotDisturb(CommonOptions opts) {
            try {
                int zenMode = Settings.Global.getInt(VibratorService.this.mContext.getContentResolver(), "zen_mode");
                if (zenMode != 0 && !opts.force) {
                    PrintWriter pw = getOutPrintWriter();
                    pw.print("Ignoring because device is on DND mode ");
                    pw.println(DebugUtils.flagsToString(Settings.Global.class, "ZEN_MODE_", zenMode));
                    $closeResource(null, pw);
                    return true;
                }
                return false;
            } catch (Settings.SettingNotFoundException e) {
                return false;
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

        private int runVibrate() {
            Trace.traceBegin(8388608L, "runVibrate");
            try {
                CommonOptions commonOptions = new CommonOptions();
                while (true) {
                    String opt = getNextOption();
                    if (opt == null) {
                        break;
                    }
                    commonOptions.check(opt);
                }
                if (checkDoNotDisturb(commonOptions)) {
                    return 0;
                }
                long duration = Long.parseLong(getNextArgRequired());
                String description = getNextArg();
                if (description == null) {
                    description = NotificationShellCmd.CHANNEL_NAME;
                }
                VibrationEffect effect = VibrationEffect.createOneShot(duration, -1);
                AudioAttributes attrs = createAudioAttributes(commonOptions);
                VibratorService.this.vibrate(Binder.getCallingUid(), description, effect, attrs, "Shell Command", this.mToken);
                return 0;
            } finally {
                Trace.traceEnd(8388608L);
            }
        }

        /* JADX WARN: Removed duplicated region for block: B:24:0x0051  */
        /* JADX WARN: Removed duplicated region for block: B:30:0x006c A[Catch: all -> 0x0101, TryCatch #0 {all -> 0x0101, blocks: (B:3:0x000b, B:4:0x0015, B:6:0x001d, B:26:0x0055, B:28:0x005b, B:29:0x0062, B:30:0x006c, B:13:0x0031, B:16:0x003b, B:19:0x0044, B:32:0x0073, B:36:0x007e, B:37:0x0084, B:40:0x008e, B:42:0x0098, B:43:0x00a4, B:44:0x00b0, B:46:0x00c1, B:48:0x00de, B:47:0x00c8), top: B:54:0x000b }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        private int runWaveform() {
            /*
                Method dump skipped, instructions count: 262
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.VibratorService.VibratorShellCommand.runWaveform():int");
        }

        private int runPrebaked() {
            String description;
            Trace.traceBegin(8388608L, "runPrebaked");
            try {
                CommonOptions commonOptions = new CommonOptions();
                while (true) {
                    String opt = getNextOption();
                    if (opt == null) {
                        break;
                    }
                    commonOptions.check(opt);
                }
                if (checkDoNotDisturb(commonOptions)) {
                    return 0;
                }
                int id = Integer.parseInt(getNextArgRequired());
                String description2 = getNextArg();
                if (description2 != null) {
                    description = description2;
                } else {
                    description = NotificationShellCmd.CHANNEL_NAME;
                }
                VibrationEffect effect = VibrationEffect.get(id, false);
                AudioAttributes attrs = createAudioAttributes(commonOptions);
                VibratorService.this.vibrate(Binder.getCallingUid(), description, effect, attrs, "Shell Command", this.mToken);
                return 0;
            } finally {
                Trace.traceEnd(8388608L);
            }
        }

        private AudioAttributes createAudioAttributes(CommonOptions commonOptions) {
            int flags;
            if (commonOptions.force) {
                flags = 64;
            } else {
                flags = 0;
            }
            return new AudioAttributes.Builder().setUsage(0).setFlags(flags).build();
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            try {
                pw.println("Vibrator commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  vibrate duration [description]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode.");
                pw.println("  waveform [-d description] [-r index] [-a] duration [amplitude] ...");
                pw.println("    Vibrates for durations and amplitudes in list;");
                pw.println("    ignored when device is on DND (Do Not Disturb) mode.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command accepts duration-amplitude pairs;");
                pw.println("    otherwise, it accepts durations only and alternates off/on");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255.");
                pw.println("  prebaked effect-id [description]");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("Common Options:");
                pw.println("  -f - Force. Ignore Do Not Disturb setting.");
                pw.println("");
                $closeResource(null, pw);
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
