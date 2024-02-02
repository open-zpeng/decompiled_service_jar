package com.android.server.power;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManagerInternal;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.view.inputmethod.InputMethodManagerInternal;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;
/* loaded from: classes.dex */
final class Notifier {
    private static final boolean DEBUG = false;
    private static final int INTERACTIVE_STATE_ASLEEP = 2;
    private static final int INTERACTIVE_STATE_AWAKE = 1;
    private static final int INTERACTIVE_STATE_UNKNOWN = 0;
    private static final int MSG_BROADCAST = 2;
    private static final int MSG_PROFILE_TIMED_OUT = 5;
    private static final int MSG_SCREEN_BRIGHTNESS_BOOST_CHANGED = 4;
    private static final int MSG_SEND_WAKEFULNESS_CHANGED_BROADCAST = 10;
    private static final int MSG_USER_ACTIVITY = 1;
    private static final int MSG_WIRED_CHARGING_STARTED = 6;
    private static final int MSG_WIRELESS_CHARGING_STARTED = 3;
    private static final String TAG = "PowerManagerNotifier";
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStats;
    private boolean mBroadcastInProgress;
    private long mBroadcastStartTime;
    private int mBroadcastedInteractiveState;
    private final Context mContext;
    private int mCurWakefulness;
    private final NotifierHandler mHandler;
    private boolean mInAwakeDisplayOff;
    private int mInteractiveChangeReason;
    private boolean mInteractiveChanging;
    private boolean mPendingGoToSleepBroadcast;
    private int mPendingInteractiveState;
    private boolean mPendingWakeUpBroadcast;
    private final WindowManagerPolicy mPolicy;
    private final Intent mScreenBrightnessBoostIntent;
    private final Intent mScreenOffIntent;
    private final SuspendBlocker mSuspendBlocker;
    private final boolean mSuspendWhenScreenOffDueToProximityConfig;
    private final TrustManager mTrustManager;
    private boolean mUserActivityPending;
    private final Vibrator mVibrator;
    private static final long[] WIRELESS_VIBRATION_TIME = {40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40};
    private static final int[] WIRELESS_VIBRATION_AMPLITUDE = {1, 4, 11, 25, 44, 67, 91, 114, 123, HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION, 79, 55, 34, 17, 7, 2};
    private static final VibrationEffect WIRELESS_CHARGING_VIBRATION_EFFECT = VibrationEffect.createWaveform(WIRELESS_VIBRATION_TIME, WIRELESS_VIBRATION_AMPLITUDE, -1);
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).build();
    private final Object mLock = new Object();
    private boolean mInteractive = true;
    private boolean mSleepState = false;
    private final BroadcastReceiver mScreeBrightnessBoostChangedDone = new BroadcastReceiver() { // from class: com.android.server.power.Notifier.6
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            Notifier.this.mSuspendBlocker.release();
        }
    };
    private final BroadcastReceiver mWakeUpBroadcastDone = new BroadcastReceiver() { // from class: com.android.server.power.Notifier.7
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1, Long.valueOf(SystemClock.uptimeMillis() - Notifier.this.mBroadcastStartTime), 1);
            Notifier.this.sendNextBroadcast();
        }
    };
    private final BroadcastReceiver mGoToSleepBroadcastDone = new BroadcastReceiver() { // from class: com.android.server.power.Notifier.8
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_DONE, 0, Long.valueOf(SystemClock.uptimeMillis() - Notifier.this.mBroadcastStartTime), 1);
            Notifier.this.sendNextBroadcast();
        }
    };
    private final BroadcastReceiver mWakefulnessChangedBroadcastDone = new BroadcastReceiver() { // from class: com.android.server.power.Notifier.9
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1, Long.valueOf(SystemClock.uptimeMillis() - Notifier.this.mBroadcastStartTime), 1);
        }
    };
    private final ActivityManagerInternal mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
    private final InputManagerInternal mInputManagerInternal = (InputManagerInternal) LocalServices.getService(InputManagerInternal.class);
    private final InputMethodManagerInternal mInputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
    private final StatusBarManagerInternal mStatusBarManagerInternal = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
    private final Intent mScreenOnIntent = new Intent("android.intent.action.SCREEN_ON");

    public Notifier(Looper looper, Context context, IBatteryStats batteryStats, SuspendBlocker suspendBlocker, WindowManagerPolicy policy) {
        this.mContext = context;
        this.mBatteryStats = batteryStats;
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mSuspendBlocker = suspendBlocker;
        this.mPolicy = policy;
        this.mTrustManager = (TrustManager) this.mContext.getSystemService(TrustManager.class);
        this.mVibrator = (Vibrator) this.mContext.getSystemService(Vibrator.class);
        this.mHandler = new NotifierHandler(looper);
        this.mScreenOnIntent.addFlags(1344274432);
        this.mScreenOffIntent = new Intent("android.intent.action.SCREEN_OFF");
        this.mScreenOffIntent.addFlags(1344274432);
        this.mScreenBrightnessBoostIntent = new Intent("android.os.action.SCREEN_BRIGHTNESS_BOOST_CHANGED");
        this.mScreenBrightnessBoostIntent.addFlags(1342177280);
        this.mSuspendWhenScreenOffDueToProximityConfig = context.getResources().getBoolean(17957051);
        this.mCurWakefulness = -1;
        try {
            this.mBatteryStats.noteInteractive(true);
        } catch (RemoteException e) {
        }
    }

    public void onWakeLockAcquired(int flags, String tag, String packageName, int ownerUid, int ownerPid, WorkSource workSource, String historyTag) {
        int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        if (monitorType >= 0) {
            boolean unimportantForLogging = ownerUid == 1000 && (flags & 1073741824) != 0;
            try {
                if (workSource != null) {
                    this.mBatteryStats.noteStartWakelockFromSource(workSource, ownerPid, tag, historyTag, monitorType, unimportantForLogging);
                } else {
                    this.mBatteryStats.noteStartWakelock(ownerUid, ownerPid, tag, historyTag, monitorType, unimportantForLogging);
                    try {
                        this.mAppOps.startOpNoThrow(40, ownerUid, packageName);
                    } catch (RemoteException e) {
                    }
                }
            } catch (RemoteException e2) {
            }
        }
    }

    public void onLongPartialWakeLockStart(String tag, int ownerUid, WorkSource workSource, String historyTag) {
        try {
            if (workSource != null) {
                this.mBatteryStats.noteLongPartialWakelockStartFromSource(tag, historyTag, workSource);
            } else {
                this.mBatteryStats.noteLongPartialWakelockStart(tag, historyTag, ownerUid);
            }
        } catch (RemoteException e) {
        }
    }

    public void onLongPartialWakeLockFinish(String tag, int ownerUid, WorkSource workSource, String historyTag) {
        try {
            if (workSource != null) {
                this.mBatteryStats.noteLongPartialWakelockFinishFromSource(tag, historyTag, workSource);
            } else {
                this.mBatteryStats.noteLongPartialWakelockFinish(tag, historyTag, ownerUid);
            }
        } catch (RemoteException e) {
        }
    }

    public void onWakeLockChanging(int flags, String tag, String packageName, int ownerUid, int ownerPid, WorkSource workSource, String historyTag, int newFlags, String newTag, String newPackageName, int newOwnerUid, int newOwnerPid, WorkSource newWorkSource, String newHistoryTag) {
        int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        int newMonitorType = getBatteryStatsWakeLockMonitorType(newFlags);
        if (workSource != null && newWorkSource != null && monitorType >= 0 && newMonitorType >= 0) {
            boolean unimportantForLogging = newOwnerUid == 1000 && (1073741824 & newFlags) != 0;
            try {
                this.mBatteryStats.noteChangeWakelockFromSource(workSource, ownerPid, tag, historyTag, monitorType, newWorkSource, newOwnerPid, newTag, newHistoryTag, newMonitorType, unimportantForLogging);
                return;
            } catch (RemoteException e) {
                return;
            }
        }
        onWakeLockReleased(flags, tag, packageName, ownerUid, ownerPid, workSource, historyTag);
        onWakeLockAcquired(newFlags, newTag, newPackageName, newOwnerUid, newOwnerPid, newWorkSource, newHistoryTag);
    }

    public void onWakeLockReleased(int flags, String tag, String packageName, int ownerUid, int ownerPid, WorkSource workSource, String historyTag) {
        int monitorType = getBatteryStatsWakeLockMonitorType(flags);
        if (monitorType >= 0) {
            try {
                if (workSource != null) {
                    this.mBatteryStats.noteStopWakelockFromSource(workSource, ownerPid, tag, historyTag, monitorType);
                } else {
                    this.mBatteryStats.noteStopWakelock(ownerUid, ownerPid, tag, historyTag, monitorType);
                    this.mAppOps.finishOp(40, ownerUid, packageName);
                }
            } catch (RemoteException e) {
            }
        }
    }

    private int getBatteryStatsWakeLockMonitorType(int flags) {
        int i = 65535 & flags;
        if (i != 1) {
            if (i == 6 || i == 10) {
                return 1;
            }
            return i != 32 ? (i == 64 || i != 128) ? -1 : 18 : this.mSuspendWhenScreenOffDueToProximityConfig ? -1 : 0;
        }
        return 0;
    }

    public void onWakefulnessChangeStarted(final int wakefulness, int reason) {
        boolean interactive = PowerManagerInternal.isInteractive(wakefulness);
        boolean sleepState = PowerManagerInternal.isInSleep(wakefulness);
        boolean inAwakeDisplayOff = PowerManagerInternal.isInAwakeDisplayOff(wakefulness);
        Slog.i(TAG, "onWakefulnessChangeStarted: wakefulness=" + wakefulness + ", reason=" + reason + ", interactive=" + interactive + ", mInteractive=" + this.mInteractive + ", inAwakeScreenOff=" + inAwakeDisplayOff + ", sleepState: " + sleepState);
        this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.1
            @Override // java.lang.Runnable
            public void run() {
                Notifier.this.mActivityManagerInternal.onWakefulnessChanged(wakefulness);
            }
        });
        Message msg = this.mHandler.obtainMessage(10);
        synchronized (this.mLock) {
            msg.arg1 = this.mCurWakefulness;
            this.mCurWakefulness = wakefulness;
        }
        msg.arg2 = wakefulness;
        msg.obj = Integer.valueOf(reason);
        msg.setAsynchronous(true);
        this.mHandler.sendMessage(msg);
        if (this.mInteractive != interactive || this.mInAwakeDisplayOff != inAwakeDisplayOff || this.mSleepState != sleepState) {
            if (this.mInteractiveChanging) {
                handleLateInteractiveChange();
            }
            if (inAwakeDisplayOff) {
                this.mInputManagerInternal.setInteractive(true);
                this.mInputMethodManagerInternal.setInteractive(true);
                try {
                    this.mBatteryStats.noteInteractive(true);
                } catch (RemoteException e) {
                }
            } else {
                this.mInputManagerInternal.setInteractive(interactive);
                this.mInputMethodManagerInternal.setInteractive(interactive);
                try {
                    this.mBatteryStats.noteInteractive(interactive);
                } catch (RemoteException e2) {
                }
            }
            this.mInAwakeDisplayOff = inAwakeDisplayOff;
            this.mSleepState = sleepState;
            this.mInteractive = interactive;
            this.mInteractiveChangeReason = reason;
            this.mInteractiveChanging = true;
            handleEarlyInteractiveChange();
        }
    }

    public void onWakefulnessChangeFinished() {
        Slog.i(TAG, "onWakefulnessChangeFinished, mInteractiveChaning: " + this.mInteractiveChanging + ", mInAwakeDisplayOff: " + this.mInAwakeDisplayOff + ", mInteractive: " + this.mInteractive + ", mSleepState: " + this.mSleepState);
        if (this.mInteractiveChanging) {
            this.mInteractiveChanging = false;
            handleLateInteractiveChange();
        }
    }

    private void handleEarlyInteractiveChange() {
        synchronized (this.mLock) {
            if (!this.mInteractive && !this.mInAwakeDisplayOff) {
                final int why = translateOffReason(this.mInteractiveChangeReason);
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.3
                    @Override // java.lang.Runnable
                    public void run() {
                        Notifier.this.mPolicy.startedGoingToSleep(why);
                    }
                });
            }
            this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.2
                @Override // java.lang.Runnable
                public void run() {
                    Notifier.this.mPolicy.startedWakingUp();
                }
            });
            if (this.mInAwakeDisplayOff) {
                return;
            }
            this.mPendingInteractiveState = 1;
            this.mPendingWakeUpBroadcast = true;
            updatePendingBroadcastLocked();
        }
    }

    private void handleLateInteractiveChange() {
        synchronized (this.mLock) {
            if (!this.mInteractive && !this.mInAwakeDisplayOff) {
                if (this.mUserActivityPending) {
                    this.mUserActivityPending = false;
                    this.mHandler.removeMessages(1);
                }
                final int why = translateOffReason(this.mInteractiveChangeReason);
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.5
                    @Override // java.lang.Runnable
                    public void run() {
                        LogMaker log = new LogMaker(198);
                        log.setType(2);
                        log.setSubtype(why);
                        MetricsLogger.action(log);
                        EventLogTags.writePowerScreenState(0, why, 0L, 0, 0);
                        Notifier.this.mPolicy.finishedGoingToSleep(why);
                    }
                });
                if (this.mSleepState) {
                    this.mPendingInteractiveState = 2;
                    this.mPendingGoToSleepBroadcast = true;
                    updatePendingBroadcastLocked();
                }
                return;
            }
            this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.4
                @Override // java.lang.Runnable
                public void run() {
                    Notifier.this.mPolicy.finishedWakingUp();
                }
            });
        }
    }

    private static int translateOffReason(int reason) {
        switch (reason) {
            case 1:
                return 1;
            case 2:
                return 3;
            default:
                return 2;
        }
    }

    public void onScreenBrightnessBoostChanged() {
        this.mSuspendBlocker.acquire();
        Message msg = this.mHandler.obtainMessage(4);
        msg.setAsynchronous(true);
        this.mHandler.sendMessage(msg);
    }

    public void onUserActivity(int event, int uid) {
        try {
            this.mBatteryStats.noteUserActivity(uid, event);
        } catch (RemoteException e) {
        }
        synchronized (this.mLock) {
            if (!this.mUserActivityPending) {
                this.mUserActivityPending = true;
                Message msg = this.mHandler.obtainMessage(1);
                msg.setAsynchronous(true);
                this.mHandler.sendMessage(msg);
            }
        }
    }

    public void onWakeUp(String reason, int reasonUid, String opPackageName, int opUid) {
        try {
            this.mBatteryStats.noteWakeUp(reason, reasonUid);
            if (opPackageName != null) {
                this.mAppOps.noteOpNoThrow(61, opUid, opPackageName);
            }
        } catch (RemoteException e) {
        }
    }

    public void onProfileTimeout(int userId) {
        Message msg = this.mHandler.obtainMessage(5);
        msg.setAsynchronous(true);
        msg.arg1 = userId;
        this.mHandler.sendMessage(msg);
    }

    public void onWirelessChargingStarted(int batteryLevel) {
        this.mSuspendBlocker.acquire();
        Message msg = this.mHandler.obtainMessage(3);
        msg.setAsynchronous(true);
        msg.arg1 = batteryLevel;
        this.mHandler.sendMessage(msg);
    }

    public void onWiredChargingStarted() {
        this.mSuspendBlocker.acquire();
        Message msg = this.mHandler.obtainMessage(6);
        msg.setAsynchronous(true);
        this.mHandler.sendMessage(msg);
    }

    private void updatePendingBroadcastLocked() {
        if (this.mBroadcastInProgress || this.mPendingInteractiveState == 0) {
            return;
        }
        if (this.mPendingWakeUpBroadcast || this.mPendingGoToSleepBroadcast || this.mPendingInteractiveState != this.mBroadcastedInteractiveState) {
            this.mBroadcastInProgress = true;
            this.mSuspendBlocker.acquire();
            Message msg = this.mHandler.obtainMessage(2);
            msg.setAsynchronous(true);
            this.mHandler.sendMessage(msg);
        }
    }

    private void finishPendingBroadcastLocked() {
        this.mBroadcastInProgress = false;
        this.mSuspendBlocker.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendUserActivity() {
        synchronized (this.mLock) {
            if (this.mUserActivityPending) {
                this.mUserActivityPending = false;
                this.mPolicy.userActivity();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendNextBroadcast() {
        synchronized (this.mLock) {
            if (this.mBroadcastedInteractiveState == 0) {
                if (!this.mPendingGoToSleepBroadcast && this.mPendingInteractiveState != 2) {
                    this.mPendingWakeUpBroadcast = false;
                    this.mBroadcastedInteractiveState = 1;
                }
                this.mPendingGoToSleepBroadcast = false;
                this.mBroadcastedInteractiveState = 2;
            } else if (this.mBroadcastedInteractiveState == 1) {
                if (this.mPendingGoToSleepBroadcast && this.mPendingInteractiveState == 2) {
                    this.mPendingGoToSleepBroadcast = false;
                    this.mBroadcastedInteractiveState = 2;
                } else if (this.mPendingWakeUpBroadcast && this.mPendingInteractiveState == 1) {
                    this.mPendingWakeUpBroadcast = false;
                    this.mBroadcastedInteractiveState = 1;
                } else {
                    finishPendingBroadcastLocked();
                    return;
                }
            } else if (this.mPendingWakeUpBroadcast && this.mPendingInteractiveState == 1) {
                this.mPendingWakeUpBroadcast = false;
                this.mBroadcastedInteractiveState = 1;
            } else if (this.mPendingGoToSleepBroadcast && this.mPendingInteractiveState == 2) {
                this.mPendingGoToSleepBroadcast = false;
                this.mBroadcastedInteractiveState = 2;
            } else {
                finishPendingBroadcastLocked();
                return;
            }
            this.mBroadcastStartTime = SystemClock.uptimeMillis();
            int powerState = this.mBroadcastedInteractiveState;
            EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_SEND, 1);
            if (powerState == 1) {
                sendWakeUpBroadcast();
            } else {
                sendGoToSleepBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBrightnessBoostChangedBroadcast() {
        this.mContext.sendOrderedBroadcastAsUser(this.mScreenBrightnessBoostIntent, UserHandle.ALL, null, this.mScreeBrightnessBoostChangedDone, this.mHandler, 0, null, null);
    }

    private void sendWakeUpBroadcast() {
        if (this.mActivityManagerInternal.isSystemReady()) {
            this.mContext.sendOrderedBroadcastAsUser(this.mScreenOnIntent, UserHandle.ALL, null, this.mWakeUpBroadcastDone, this.mHandler, 0, null, null);
            return;
        }
        EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2, 1);
        sendNextBroadcast();
    }

    private void sendGoToSleepBroadcast() {
        if (this.mActivityManagerInternal.isSystemReady()) {
            this.mContext.sendOrderedBroadcastAsUser(this.mScreenOffIntent, UserHandle.ALL, null, this.mGoToSleepBroadcastDone, this.mHandler, 0, null, null);
            return;
        }
        EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_STOP, 3, 1);
        sendNextBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendWakefulnessChangedBroadcast(int curState, int nextState, int reason) {
        if (this.mActivityManagerInternal.isSystemReady()) {
            Intent intent = new Intent("android.intent.action.WAKEFULNESS_CHANGED");
            this.mScreenOffIntent.addFlags(1344274432);
            intent.putExtra("CurState", curState);
            intent.putExtra("NextState", nextState);
            intent.putExtra("Reason", reason);
            this.mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, null, this.mWakefulnessChangedBroadcastDone, this.mHandler, 0, null, null);
            return;
        }
        EventLog.writeEvent((int) EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2, 1);
        Slog.d(TAG, "Sending Wakefulness Changed broadcast:system not ready!");
    }

    private void playChargingStartedSound() {
        Ringtone sfx;
        String soundPath = Settings.Global.getString(this.mContext.getContentResolver(), "wireless_charging_started_sound");
        if (isChargingFeedbackEnabled() && soundPath != null) {
            Uri soundUri = Uri.parse("file://" + soundPath);
            if (soundUri != null && (sfx = RingtoneManager.getRingtone(this.mContext, soundUri)) != null) {
                sfx.setStreamType(1);
                sfx.play();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showWirelessChargingStarted(int batteryLevel) {
        playWirelessChargingVibration();
        playChargingStartedSound();
        if (this.mStatusBarManagerInternal != null) {
            this.mStatusBarManagerInternal.showChargingAnimation(batteryLevel);
        }
        this.mSuspendBlocker.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showWiredChargingStarted() {
        playChargingStartedSound();
        this.mSuspendBlocker.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void lockProfile(int userId) {
        this.mTrustManager.setDeviceLockedForUser(userId, true);
    }

    private void playWirelessChargingVibration() {
        boolean vibrateEnabled = Settings.Global.getInt(this.mContext.getContentResolver(), "charging_vibration_enabled", 0) != 0;
        if (vibrateEnabled && isChargingFeedbackEnabled()) {
            this.mVibrator.vibrate(WIRELESS_CHARGING_VIBRATION_EFFECT, VIBRATION_ATTRIBUTES);
        }
    }

    private boolean isChargingFeedbackEnabled() {
        boolean enabled = Settings.Global.getInt(this.mContext.getContentResolver(), "charging_sounds_enabled", 1) != 0;
        boolean dndOff = Settings.Global.getInt(this.mContext.getContentResolver(), "zen_mode", 1) == 0;
        return enabled && dndOff;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class NotifierHandler extends Handler {
        public NotifierHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i != 10) {
                switch (i) {
                    case 1:
                        Notifier.this.sendUserActivity();
                        return;
                    case 2:
                        Notifier.this.sendNextBroadcast();
                        return;
                    case 3:
                        Notifier.this.showWirelessChargingStarted(msg.arg1);
                        return;
                    case 4:
                        Notifier.this.sendBrightnessBoostChangedBroadcast();
                        return;
                    case 5:
                        Notifier.this.lockProfile(msg.arg1);
                        return;
                    case 6:
                        Notifier.this.showWiredChargingStarted();
                        return;
                    default:
                        return;
                }
            }
            Notifier.this.sendWakefulnessChangedBroadcast(msg.arg1, msg.arg2, ((Integer) msg.obj).intValue());
        }
    }
}
