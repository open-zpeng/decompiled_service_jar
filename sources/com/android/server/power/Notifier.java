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
import android.util.StatsLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;

@VisibleForTesting
/* loaded from: classes.dex */
public class Notifier {
    private static final boolean DEBUG = false;
    private static final int INTERACTIVE_STATE_ASLEEP = 2;
    private static final int INTERACTIVE_STATE_AWAKE = 1;
    private static final int INTERACTIVE_STATE_UNKNOWN = 0;
    private static final int MSG_BROADCAST = 2;
    private static final int MSG_PROFILE_TIMED_OUT = 5;
    private static final int MSG_SCREEN_BRIGHTNESS_BOOST_CHANGED = 4;
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
    private final NotifierHandler mHandler;
    private int mInteractiveChangeReason;
    private long mInteractiveChangeStartTime;
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
    private static final int[] WIRELESS_VIBRATION_AMPLITUDE = {1, 4, 11, 25, 44, 67, 91, HdmiCecKeycode.CEC_KEYCODE_F2_RED, 123, HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION, 79, 55, 34, 17, 7, 2};
    private static final VibrationEffect WIRELESS_CHARGING_VIBRATION_EFFECT = VibrationEffect.createWaveform(WIRELESS_VIBRATION_TIME, WIRELESS_VIBRATION_AMPLITUDE, -1);
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).build();
    private final Object mLock = new Object();
    private boolean mInteractive = true;
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
        this.mSuspendWhenScreenOffDueToProximityConfig = context.getResources().getBoolean(17891547);
        try {
            this.mBatteryStats.noteInteractive(true);
        } catch (RemoteException e) {
        }
        StatsLog.write(33, 1);
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
                StatsLog.write(11, workSource, tag, historyTag, 1);
            } else {
                this.mBatteryStats.noteLongPartialWakelockStart(tag, historyTag, ownerUid);
                StatsLog.write_non_chained(11, ownerUid, null, tag, historyTag, 1);
            }
        } catch (RemoteException e) {
        }
    }

    public void onLongPartialWakeLockFinish(String tag, int ownerUid, WorkSource workSource, String historyTag) {
        try {
            if (workSource != null) {
                this.mBatteryStats.noteLongPartialWakelockFinishFromSource(tag, historyTag, workSource);
                StatsLog.write(11, workSource, tag, historyTag, 0);
            } else {
                this.mBatteryStats.noteLongPartialWakelockFinish(tag, historyTag, ownerUid);
                StatsLog.write_non_chained(11, ownerUid, null, tag, historyTag, 0);
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

    public void onWakefulnessChangeStarted(final int wakefulness, int reason, long eventTime) {
        int i;
        boolean interactive = PowerManagerInternal.isInteractive(wakefulness);
        this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.1
            @Override // java.lang.Runnable
            public void run() {
                Notifier.this.mActivityManagerInternal.onWakefulnessChanged(wakefulness);
            }
        });
        if (this.mInteractive != interactive) {
            if (this.mInteractiveChanging) {
                handleLateInteractiveChange();
            }
            this.mInputManagerInternal.setInteractive(interactive);
            this.mInputMethodManagerInternal.setInteractive(interactive);
            try {
                this.mBatteryStats.noteInteractive(interactive);
            } catch (RemoteException e) {
            }
            if (interactive) {
                i = 1;
            } else {
                i = 0;
            }
            StatsLog.write(33, i);
            this.mInteractive = interactive;
            this.mInteractiveChangeReason = reason;
            this.mInteractiveChangeStartTime = eventTime;
            this.mInteractiveChanging = true;
            handleEarlyInteractiveChange();
        }
    }

    public void onWakefulnessChangeFinished() {
        if (this.mInteractiveChanging) {
            this.mInteractiveChanging = false;
            handleLateInteractiveChange();
        }
    }

    private void handleEarlyInteractiveChange() {
        synchronized (this.mLock) {
            if (this.mInteractive) {
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.2
                    @Override // java.lang.Runnable
                    public void run() {
                        int why = Notifier.translateOnReason(Notifier.this.mInteractiveChangeReason);
                        Notifier.this.mPolicy.startedWakingUp(why);
                    }
                });
                this.mPendingInteractiveState = 1;
                this.mPendingWakeUpBroadcast = true;
                updatePendingBroadcastLocked();
            } else {
                final int why = translateOffReason(this.mInteractiveChangeReason);
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.3
                    @Override // java.lang.Runnable
                    public void run() {
                        Notifier.this.mPolicy.startedGoingToSleep(why);
                    }
                });
            }
        }
    }

    private void handleLateInteractiveChange() {
        synchronized (this.mLock) {
            final int interactiveChangeLatency = (int) (SystemClock.uptimeMillis() - this.mInteractiveChangeStartTime);
            if (this.mInteractive) {
                final int why = translateOnReason(this.mInteractiveChangeReason);
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.4
                    @Override // java.lang.Runnable
                    public void run() {
                        LogMaker log = new LogMaker(198);
                        log.setType(1);
                        log.setSubtype(why);
                        log.setLatency(interactiveChangeLatency);
                        log.addTaggedData(1694, Integer.valueOf(Notifier.this.mInteractiveChangeReason));
                        MetricsLogger.action(log);
                        EventLogTags.writePowerScreenState(1, 0, 0L, 0, interactiveChangeLatency);
                        Notifier.this.mPolicy.finishedWakingUp(why);
                    }
                });
            } else {
                if (this.mUserActivityPending) {
                    this.mUserActivityPending = false;
                    this.mHandler.removeMessages(1);
                }
                final int why2 = translateOffReason(this.mInteractiveChangeReason);
                this.mHandler.post(new Runnable() { // from class: com.android.server.power.Notifier.5
                    @Override // java.lang.Runnable
                    public void run() {
                        LogMaker log = new LogMaker(198);
                        log.setType(2);
                        log.setSubtype(why2);
                        log.setLatency(interactiveChangeLatency);
                        log.addTaggedData(1695, Integer.valueOf(Notifier.this.mInteractiveChangeReason));
                        MetricsLogger.action(log);
                        EventLogTags.writePowerScreenState(0, why2, 0L, 0, interactiveChangeLatency);
                        Notifier.this.mPolicy.finishedGoingToSleep(why2);
                    }
                });
                this.mPendingInteractiveState = 2;
                this.mPendingGoToSleepBroadcast = true;
                updatePendingBroadcastLocked();
            }
        }
    }

    private static int translateOffReason(int reason) {
        if (reason != 1) {
            return reason != 2 ? 2 : 3;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int translateOnReason(int reason) {
        switch (reason) {
            case 1:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 9:
                return 1;
            case 2:
                return 2;
            case 8:
            default:
                return 3;
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

    public void onWakeUp(int reason, String details, int reasonUid, String opPackageName, int opUid) {
        try {
            this.mBatteryStats.noteWakeUp(details, reasonUid);
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

    public void onWirelessChargingStarted(int batteryLevel, int userId) {
        this.mSuspendBlocker.acquire();
        Message msg = this.mHandler.obtainMessage(3);
        msg.setAsynchronous(true);
        msg.arg1 = batteryLevel;
        msg.arg2 = userId;
        this.mHandler.sendMessage(msg);
    }

    public void onWiredChargingStarted(int userId) {
        this.mSuspendBlocker.acquire();
        Message msg = this.mHandler.obtainMessage(6);
        msg.setAsynchronous(true);
        msg.arg1 = userId;
        this.mHandler.sendMessage(msg);
    }

    private void updatePendingBroadcastLocked() {
        int i;
        if (this.mBroadcastInProgress || (i = this.mPendingInteractiveState) == 0) {
            return;
        }
        if (this.mPendingWakeUpBroadcast || this.mPendingGoToSleepBroadcast || i != this.mBroadcastedInteractiveState) {
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
                this.mPendingWakeUpBroadcast = false;
                this.mBroadcastedInteractiveState = 1;
            } else if (this.mBroadcastedInteractiveState == 1) {
                if (!this.mPendingWakeUpBroadcast && !this.mPendingGoToSleepBroadcast && this.mPendingInteractiveState != 2) {
                    finishPendingBroadcastLocked();
                    return;
                }
                this.mPendingGoToSleepBroadcast = false;
                this.mBroadcastedInteractiveState = 2;
            } else {
                if (!this.mPendingWakeUpBroadcast && !this.mPendingGoToSleepBroadcast && this.mPendingInteractiveState != 1) {
                    finishPendingBroadcastLocked();
                    return;
                }
                this.mPendingWakeUpBroadcast = false;
                this.mBroadcastedInteractiveState = 1;
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

    private void playChargingStartedFeedback(int userId) {
        Ringtone sfx;
        playChargingStartedVibration(userId);
        String soundPath = Settings.Global.getString(this.mContext.getContentResolver(), "wireless_charging_started_sound");
        if (isChargingFeedbackEnabled(userId) && soundPath != null) {
            Uri soundUri = Uri.parse("file://" + soundPath);
            if (soundUri != null && (sfx = RingtoneManager.getRingtone(this.mContext, soundUri)) != null) {
                sfx.setStreamType(1);
                sfx.play();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showWirelessChargingStarted(int batteryLevel, int userId) {
        playChargingStartedFeedback(userId);
        StatusBarManagerInternal statusBarManagerInternal = this.mStatusBarManagerInternal;
        if (statusBarManagerInternal != null) {
            statusBarManagerInternal.showChargingAnimation(batteryLevel);
        }
        this.mSuspendBlocker.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showWiredChargingStarted(int userId) {
        playChargingStartedFeedback(userId);
        this.mSuspendBlocker.release();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void lockProfile(int userId) {
        this.mTrustManager.setDeviceLockedForUser(userId, true);
    }

    private void playChargingStartedVibration(int userId) {
        boolean vibrateEnabled = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "charging_vibration_enabled", 1, userId) != 0;
        if (vibrateEnabled && isChargingFeedbackEnabled(userId)) {
            this.mVibrator.vibrate(WIRELESS_CHARGING_VIBRATION_EFFECT, VIBRATION_ATTRIBUTES);
        }
    }

    private boolean isChargingFeedbackEnabled(int userId) {
        boolean enabled = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "charging_sounds_enabled", 1, userId) != 0;
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
            switch (msg.what) {
                case 1:
                    Notifier.this.sendUserActivity();
                    return;
                case 2:
                    Notifier.this.sendNextBroadcast();
                    return;
                case 3:
                    Notifier.this.showWirelessChargingStarted(msg.arg1, msg.arg2);
                    return;
                case 4:
                    Notifier.this.sendBrightnessBoostChangedBroadcast();
                    return;
                case 5:
                    Notifier.this.lockProfile(msg.arg1);
                    return;
                case 6:
                    Notifier.this.showWiredChargingStarted(msg.arg1);
                    return;
                default:
                    return;
            }
        }
    }
}
