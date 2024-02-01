package com.android.server.power.batterysaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import java.io.PrintWriter;
import java.text.NumberFormat;

/* loaded from: classes.dex */
public class BatterySaverStateMachine {
    private static final int ADAPTIVE_AUTO_DISABLE_BATTERY_LEVEL = 80;
    private static final long ADAPTIVE_CHANGE_TIMEOUT_MS = 86400000;
    private static final String BATTERY_SAVER_NOTIF_CHANNEL_ID = "battery_saver_channel";
    private static final boolean DEBUG = false;
    private static final int DYNAMIC_MODE_NOTIFICATION_ID = 1992;
    private static final String DYNAMIC_MODE_NOTIF_CHANNEL_ID = "dynamic_mode_notification";
    private static final int STATE_AUTOMATIC_ON = 3;
    private static final int STATE_MANUAL_ON = 2;
    private static final int STATE_OFF = 1;
    private static final int STATE_OFF_AUTOMATIC_SNOOZED = 4;
    private static final int STATE_PENDING_STICKY_ON = 5;
    private static final int STICKY_AUTO_DISABLED_NOTIFICATION_ID = 1993;
    private static final String TAG = "BatterySaverStateMachine";
    @GuardedBy({"mLock"})
    private int mBatteryLevel;
    private final BatterySaverController mBatterySaverController;
    private final boolean mBatterySaverStickyBehaviourDisabled;
    @GuardedBy({"mLock"})
    private boolean mBatteryStatusSet;
    @GuardedBy({"mLock"})
    private boolean mBootCompleted;
    private final Context mContext;
    @GuardedBy({"mLock"})
    private boolean mDynamicPowerSavingsBatterySaver;
    @GuardedBy({"mLock"})
    private final int mDynamicPowerSavingsDefaultDisableThreshold;
    @GuardedBy({"mLock"})
    private int mDynamicPowerSavingsDisableThreshold;
    @GuardedBy({"mLock"})
    private boolean mIsBatteryLevelLow;
    @GuardedBy({"mLock"})
    private boolean mIsPowered;
    @GuardedBy({"mLock"})
    private long mLastAdaptiveBatterySaverChangedExternallyElapsed;
    @GuardedBy({"mLock"})
    private int mLastChangedIntReason;
    @GuardedBy({"mLock"})
    private String mLastChangedStrReason;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private int mSettingAutomaticBatterySaver;
    @GuardedBy({"mLock"})
    private boolean mSettingBatterySaverEnabled;
    @GuardedBy({"mLock"})
    private boolean mSettingBatterySaverEnabledSticky;
    @GuardedBy({"mLock"})
    private boolean mSettingBatterySaverStickyAutoDisableEnabled;
    @GuardedBy({"mLock"})
    private int mSettingBatterySaverStickyAutoDisableThreshold;
    @GuardedBy({"mLock"})
    private int mSettingBatterySaverTriggerThreshold;
    @GuardedBy({"mLock"})
    private boolean mSettingsLoaded;
    private final ContentObserver mSettingsObserver = new ContentObserver(null) { // from class: com.android.server.power.batterysaver.BatterySaverStateMachine.1
        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            synchronized (BatterySaverStateMachine.this.mLock) {
                BatterySaverStateMachine.this.refreshSettingsLocked();
            }
        }
    };
    private final Runnable mThresholdChangeLogger = new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$BatterySaverStateMachine$SSfmWJrD4RBoVg8A8loZrS-jhAo
        @Override // java.lang.Runnable
        public final void run() {
            BatterySaverStateMachine.this.lambda$new$1$BatterySaverStateMachine();
        }
    };
    @GuardedBy({"mLock"})
    private int mState = 1;

    public BatterySaverStateMachine(Object lock, Context context, BatterySaverController batterySaverController) {
        this.mLock = lock;
        this.mContext = context;
        this.mBatterySaverController = batterySaverController;
        this.mBatterySaverStickyBehaviourDisabled = this.mContext.getResources().getBoolean(17891371);
        this.mDynamicPowerSavingsDefaultDisableThreshold = this.mContext.getResources().getInteger(17694807);
    }

    private boolean isAutomaticModeActiveLocked() {
        return this.mSettingAutomaticBatterySaver == 0 && this.mSettingBatterySaverTriggerThreshold > 0;
    }

    private boolean isInAutomaticLowZoneLocked() {
        return this.mIsBatteryLevelLow;
    }

    private boolean isDynamicModeActiveLocked() {
        return this.mSettingAutomaticBatterySaver == 1 && this.mDynamicPowerSavingsBatterySaver;
    }

    private boolean isInDynamicLowZoneLocked() {
        return this.mBatteryLevel <= this.mDynamicPowerSavingsDisableThreshold;
    }

    public void onBootCompleted() {
        putGlobalSetting("low_power", 0);
        runOnBgThread(new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$BatterySaverStateMachine$fEidyt_9TXlXBpF6D2lhOOrfOC4
            @Override // java.lang.Runnable
            public final void run() {
                BatterySaverStateMachine.this.lambda$onBootCompleted$0$BatterySaverStateMachine();
            }
        });
    }

    public /* synthetic */ void lambda$onBootCompleted$0$BatterySaverStateMachine() {
        ContentResolver cr = this.mContext.getContentResolver();
        cr.registerContentObserver(Settings.Global.getUriFor("low_power"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("low_power_sticky"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("low_power_trigger_level"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("automatic_power_save_mode"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("dynamic_power_savings_enabled"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("dynamic_power_savings_disable_threshold"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("low_power_sticky_auto_disable_enabled"), false, this.mSettingsObserver, 0);
        cr.registerContentObserver(Settings.Global.getUriFor("low_power_sticky_auto_disable_level"), false, this.mSettingsObserver, 0);
        synchronized (this.mLock) {
            boolean lowPowerModeEnabledSticky = getGlobalSetting("low_power_sticky", 0) != 0;
            if (lowPowerModeEnabledSticky) {
                this.mState = 5;
            }
            this.mBootCompleted = true;
            refreshSettingsLocked();
            doAutoBatterySaverLocked();
        }
    }

    @VisibleForTesting
    void runOnBgThread(Runnable r) {
        BackgroundThread.getHandler().post(r);
    }

    @VisibleForTesting
    void runOnBgThreadLazy(Runnable r, int delayMillis) {
        Handler h = BackgroundThread.getHandler();
        h.removeCallbacks(r);
        h.postDelayed(r, delayMillis);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void refreshSettingsLocked() {
        boolean lowPowerModeEnabled = getGlobalSetting("low_power", 0) != 0;
        boolean lowPowerModeEnabledSticky = getGlobalSetting("low_power_sticky", 0) != 0;
        boolean dynamicPowerSavingsBatterySaver = getGlobalSetting("dynamic_power_savings_enabled", 0) != 0;
        int lowPowerModeTriggerLevel = getGlobalSetting("low_power_trigger_level", 0);
        int automaticBatterySaverMode = getGlobalSetting("automatic_power_save_mode", 0);
        int dynamicPowerSavingsDisableThreshold = getGlobalSetting("dynamic_power_savings_disable_threshold", this.mDynamicPowerSavingsDefaultDisableThreshold);
        boolean isStickyAutoDisableEnabled = getGlobalSetting("low_power_sticky_auto_disable_enabled", 1) != 0;
        int stickyAutoDisableThreshold = getGlobalSetting("low_power_sticky_auto_disable_level", 90);
        setSettingsLocked(lowPowerModeEnabled, lowPowerModeEnabledSticky, lowPowerModeTriggerLevel, isStickyAutoDisableEnabled, stickyAutoDisableThreshold, automaticBatterySaverMode, dynamicPowerSavingsBatterySaver, dynamicPowerSavingsDisableThreshold);
    }

    @GuardedBy({"mLock"})
    @VisibleForTesting
    void setSettingsLocked(boolean batterySaverEnabled, boolean batterySaverEnabledSticky, int batterySaverTriggerThreshold, boolean isStickyAutoDisableEnabled, int stickyAutoDisableThreshold, int automaticBatterySaver, boolean dynamicPowerSavingsBatterySaver, int dynamicPowerSavingsDisableThreshold) {
        this.mSettingsLoaded = true;
        int stickyAutoDisableThreshold2 = Math.max(stickyAutoDisableThreshold, batterySaverTriggerThreshold);
        boolean enabledChanged = this.mSettingBatterySaverEnabled != batterySaverEnabled;
        boolean stickyChanged = this.mSettingBatterySaverEnabledSticky != batterySaverEnabledSticky;
        boolean thresholdChanged = this.mSettingBatterySaverTriggerThreshold != batterySaverTriggerThreshold;
        boolean stickyAutoDisableEnabledChanged = this.mSettingBatterySaverStickyAutoDisableEnabled != isStickyAutoDisableEnabled;
        boolean stickyAutoDisableThresholdChanged = this.mSettingBatterySaverStickyAutoDisableThreshold != stickyAutoDisableThreshold2;
        boolean automaticModeChanged = this.mSettingAutomaticBatterySaver != automaticBatterySaver;
        boolean dynamicPowerSavingsThresholdChanged = this.mDynamicPowerSavingsDisableThreshold != dynamicPowerSavingsDisableThreshold;
        boolean dynamicPowerSavingsBatterySaverChanged = this.mDynamicPowerSavingsBatterySaver != dynamicPowerSavingsBatterySaver;
        if (!enabledChanged && !stickyChanged && !thresholdChanged && !automaticModeChanged && !stickyAutoDisableEnabledChanged && !stickyAutoDisableThresholdChanged && !dynamicPowerSavingsThresholdChanged && !dynamicPowerSavingsBatterySaverChanged) {
            return;
        }
        this.mSettingBatterySaverEnabled = batterySaverEnabled;
        this.mSettingBatterySaverEnabledSticky = batterySaverEnabledSticky;
        this.mSettingBatterySaverTriggerThreshold = batterySaverTriggerThreshold;
        this.mSettingBatterySaverStickyAutoDisableEnabled = isStickyAutoDisableEnabled;
        this.mSettingBatterySaverStickyAutoDisableThreshold = stickyAutoDisableThreshold2;
        this.mSettingAutomaticBatterySaver = automaticBatterySaver;
        this.mDynamicPowerSavingsDisableThreshold = dynamicPowerSavingsDisableThreshold;
        this.mDynamicPowerSavingsBatterySaver = dynamicPowerSavingsBatterySaver;
        if (thresholdChanged) {
            runOnBgThreadLazy(this.mThresholdChangeLogger, 2000);
        }
        if (!this.mSettingBatterySaverStickyAutoDisableEnabled) {
            hideStickyDisabledNotification();
        }
        if (enabledChanged) {
            String reason = batterySaverEnabled ? "Global.low_power changed to 1" : "Global.low_power changed to 0";
            enableBatterySaverLocked(batterySaverEnabled, true, 8, reason);
            return;
        }
        doAutoBatterySaverLocked();
    }

    public /* synthetic */ void lambda$new$1$BatterySaverStateMachine() {
        EventLogTags.writeBatterySaverSetting(this.mSettingBatterySaverTriggerThreshold);
    }

    public void setBatteryStatus(boolean newPowered, int newLevel, boolean newBatteryLevelLow) {
        synchronized (this.mLock) {
            boolean lowChanged = true;
            this.mBatteryStatusSet = true;
            boolean poweredChanged = this.mIsPowered != newPowered;
            boolean levelChanged = this.mBatteryLevel != newLevel;
            if (this.mIsBatteryLevelLow == newBatteryLevelLow) {
                lowChanged = false;
            }
            if (poweredChanged || levelChanged || lowChanged) {
                this.mIsPowered = newPowered;
                this.mBatteryLevel = newLevel;
                this.mIsBatteryLevelLow = newBatteryLevelLow;
                doAutoBatterySaverLocked();
            }
        }
    }

    public boolean setAdaptiveBatterySaverEnabled(boolean enabled) {
        boolean adaptivePolicyEnabledLocked;
        synchronized (this.mLock) {
            this.mLastAdaptiveBatterySaverChangedExternallyElapsed = SystemClock.elapsedRealtime();
            adaptivePolicyEnabledLocked = this.mBatterySaverController.setAdaptivePolicyEnabledLocked(enabled, 11);
        }
        return adaptivePolicyEnabledLocked;
    }

    public boolean setAdaptiveBatterySaverPolicy(BatterySaverPolicyConfig config) {
        boolean adaptivePolicyLocked;
        synchronized (this.mLock) {
            this.mLastAdaptiveBatterySaverChangedExternallyElapsed = SystemClock.elapsedRealtime();
            adaptivePolicyLocked = this.mBatterySaverController.setAdaptivePolicyLocked(config, 11);
        }
        return adaptivePolicyLocked;
    }

    @GuardedBy({"mLock"})
    private void doAutoBatterySaverLocked() {
        if (!this.mBootCompleted || !this.mSettingsLoaded || !this.mBatteryStatusSet) {
            return;
        }
        updateStateLocked(false, false);
        if (SystemClock.elapsedRealtime() - this.mLastAdaptiveBatterySaverChangedExternallyElapsed > 86400000) {
            this.mBatterySaverController.setAdaptivePolicyEnabledLocked(false, 12);
            this.mBatterySaverController.resetAdaptivePolicyLocked(12);
        } else if (this.mIsPowered && this.mBatteryLevel >= 80) {
            this.mBatterySaverController.setAdaptivePolicyEnabledLocked(false, 7);
        }
    }

    @GuardedBy({"mLock"})
    private void updateStateLocked(boolean manual, boolean enable) {
        if (!manual && (!this.mBootCompleted || !this.mSettingsLoaded || !this.mBatteryStatusSet)) {
            return;
        }
        int i = this.mState;
        if (i == 1) {
            if (!this.mIsPowered) {
                if (manual) {
                    if (!enable) {
                        Slog.e(TAG, "Tried to disable BS when it's already OFF");
                        return;
                    }
                    enableBatterySaverLocked(true, true, 2);
                    hideStickyDisabledNotification();
                    this.mState = 2;
                } else if (isAutomaticModeActiveLocked() && isInAutomaticLowZoneLocked()) {
                    enableBatterySaverLocked(true, false, 0);
                    hideStickyDisabledNotification();
                    this.mState = 3;
                } else if (isDynamicModeActiveLocked() && isInDynamicLowZoneLocked()) {
                    enableBatterySaverLocked(true, false, 9);
                    hideStickyDisabledNotification();
                    this.mState = 3;
                }
            }
        } else if (i == 2) {
            if (manual) {
                if (enable) {
                    Slog.e(TAG, "Tried to enable BS when it's already MANUAL_ON");
                    return;
                }
                enableBatterySaverLocked(false, true, 3);
                this.mState = 1;
            } else if (this.mIsPowered) {
                enableBatterySaverLocked(false, false, 7);
                if (this.mSettingBatterySaverEnabledSticky && !this.mBatterySaverStickyBehaviourDisabled) {
                    this.mState = 5;
                } else {
                    this.mState = 1;
                }
            }
        } else if (i == 3) {
            if (this.mIsPowered) {
                enableBatterySaverLocked(false, false, 7);
                this.mState = 1;
            } else if (manual) {
                if (enable) {
                    Slog.e(TAG, "Tried to enable BS when it's already AUTO_ON");
                    return;
                }
                enableBatterySaverLocked(false, true, 3);
                this.mState = 4;
            } else if (isAutomaticModeActiveLocked() && !isInAutomaticLowZoneLocked()) {
                enableBatterySaverLocked(false, false, 1);
                this.mState = 1;
            } else if (isDynamicModeActiveLocked() && !isInDynamicLowZoneLocked()) {
                enableBatterySaverLocked(false, false, 10);
                this.mState = 1;
            } else if (!isAutomaticModeActiveLocked() && !isDynamicModeActiveLocked()) {
                enableBatterySaverLocked(false, false, 8);
                this.mState = 1;
            }
        } else if (i == 4) {
            if (manual) {
                if (!enable) {
                    Slog.e(TAG, "Tried to disable BS when it's already AUTO_SNOOZED");
                    return;
                }
                enableBatterySaverLocked(true, true, 2);
                this.mState = 2;
            } else if (this.mIsPowered || ((isAutomaticModeActiveLocked() && !isInAutomaticLowZoneLocked()) || ((isDynamicModeActiveLocked() && !isInDynamicLowZoneLocked()) || (!isAutomaticModeActiveLocked() && !isDynamicModeActiveLocked())))) {
                this.mState = 1;
            }
        } else if (i == 5) {
            if (manual) {
                Slog.e(TAG, "Tried to manually change BS state from PENDING_STICKY_ON");
                return;
            }
            boolean shouldTurnOffSticky = this.mSettingBatterySaverStickyAutoDisableEnabled && this.mBatteryLevel >= this.mSettingBatterySaverStickyAutoDisableThreshold;
            boolean isStickyDisabled = this.mBatterySaverStickyBehaviourDisabled || !this.mSettingBatterySaverEnabledSticky;
            if (isStickyDisabled || shouldTurnOffSticky) {
                this.mState = 1;
                setStickyActive(false);
                triggerStickyDisabledNotification();
            } else if (!this.mIsPowered) {
                enableBatterySaverLocked(true, true, 4);
                this.mState = 2;
            }
        } else {
            Slog.wtf(TAG, "Unknown state: " + this.mState);
        }
    }

    @VisibleForTesting
    int getState() {
        int i;
        synchronized (this.mLock) {
            i = this.mState;
        }
        return i;
    }

    public void setBatterySaverEnabledManually(boolean enabled) {
        synchronized (this.mLock) {
            updateStateLocked(true, enabled);
        }
    }

    @GuardedBy({"mLock"})
    private void enableBatterySaverLocked(boolean enable, boolean manual, int intReason) {
        enableBatterySaverLocked(enable, manual, intReason, BatterySaverController.reasonToString(intReason));
    }

    @GuardedBy({"mLock"})
    private void enableBatterySaverLocked(boolean enable, boolean manual, int intReason, String strReason) {
        boolean wasEnabled = this.mBatterySaverController.isFullEnabled();
        if (wasEnabled == enable) {
            return;
        }
        if (enable && this.mIsPowered) {
            return;
        }
        this.mLastChangedIntReason = intReason;
        this.mLastChangedStrReason = strReason;
        this.mSettingBatterySaverEnabled = enable;
        putGlobalSetting("low_power", enable ? 1 : 0);
        if (manual) {
            setStickyActive(!this.mBatterySaverStickyBehaviourDisabled && enable);
        }
        this.mBatterySaverController.enableBatterySaver(enable, intReason);
        if (intReason == 9) {
            triggerDynamicModeNotification();
        } else if (!enable) {
            hideDynamicModeNotification();
        }
    }

    @VisibleForTesting
    void triggerDynamicModeNotification() {
        runOnBgThread(new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$BatterySaverStateMachine$KPPqeIS8QIZneCCBkN31dB4SR6U
            @Override // java.lang.Runnable
            public final void run() {
                BatterySaverStateMachine.this.lambda$triggerDynamicModeNotification$2$BatterySaverStateMachine();
            }
        });
    }

    public /* synthetic */ void lambda$triggerDynamicModeNotification$2$BatterySaverStateMachine() {
        NotificationManager manager = (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
        ensureNotificationChannelExists(manager, DYNAMIC_MODE_NOTIF_CHANNEL_ID, 17039893);
        manager.notifyAsUser(TAG, DYNAMIC_MODE_NOTIFICATION_ID, buildNotification(DYNAMIC_MODE_NOTIF_CHANNEL_ID, this.mContext.getResources().getString(17039895), 17039894, "android.intent.action.POWER_USAGE_SUMMARY"), UserHandle.ALL);
    }

    @VisibleForTesting
    void triggerStickyDisabledNotification() {
        runOnBgThread(new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$BatterySaverStateMachine$QXh4KHnoFaNqEkr199qR1vIeCpo
            @Override // java.lang.Runnable
            public final void run() {
                BatterySaverStateMachine.this.lambda$triggerStickyDisabledNotification$3$BatterySaverStateMachine();
            }
        });
    }

    public /* synthetic */ void lambda$triggerStickyDisabledNotification$3$BatterySaverStateMachine() {
        NotificationManager manager = (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
        ensureNotificationChannelExists(manager, BATTERY_SAVER_NOTIF_CHANNEL_ID, 17039603);
        String percentage = NumberFormat.getPercentInstance().format(this.mBatteryLevel / 100.0d);
        manager.notifyAsUser(TAG, STICKY_AUTO_DISABLED_NOTIFICATION_ID, buildNotification(BATTERY_SAVER_NOTIF_CHANNEL_ID, this.mContext.getResources().getString(17039600, percentage), 17039605, "android.settings.BATTERY_SAVER_SETTINGS"), UserHandle.ALL);
    }

    private void ensureNotificationChannelExists(NotificationManager manager, String channelId, int nameId) {
        NotificationChannel channel = new NotificationChannel(channelId, this.mContext.getText(nameId), 3);
        channel.setSound(null, null);
        channel.setBlockableSystem(true);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String channelId, String title, int summaryId, String intentAction) {
        Resources res = this.mContext.getResources();
        Intent intent = new Intent(intentAction);
        intent.setFlags(268468224);
        PendingIntent batterySaverIntent = PendingIntent.getActivity(this.mContext, 0, intent, 134217728);
        String summary = res.getString(summaryId);
        return new Notification.Builder(this.mContext, channelId).setSmallIcon(17302305).setContentTitle(title).setContentText(summary).setContentIntent(batterySaverIntent).setStyle(new Notification.BigTextStyle().bigText(summary)).setOnlyAlertOnce(true).setAutoCancel(true).build();
    }

    private void hideDynamicModeNotification() {
        hideNotification(DYNAMIC_MODE_NOTIFICATION_ID);
    }

    private void hideStickyDisabledNotification() {
        hideNotification(STICKY_AUTO_DISABLED_NOTIFICATION_ID);
    }

    private void hideNotification(final int notificationId) {
        runOnBgThread(new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$BatterySaverStateMachine$66yeetZVz7IbzEr9gw2J77hoMVI
            @Override // java.lang.Runnable
            public final void run() {
                BatterySaverStateMachine.this.lambda$hideNotification$4$BatterySaverStateMachine(notificationId);
            }
        });
    }

    public /* synthetic */ void lambda$hideNotification$4$BatterySaverStateMachine(int notificationId) {
        NotificationManager manager = (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
        manager.cancelAsUser(TAG, notificationId, UserHandle.ALL);
    }

    private void setStickyActive(boolean active) {
        this.mSettingBatterySaverEnabledSticky = active;
        putGlobalSetting("low_power_sticky", this.mSettingBatterySaverEnabledSticky ? 1 : 0);
    }

    @VisibleForTesting
    protected void putGlobalSetting(String key, int value) {
        Settings.Global.putInt(this.mContext.getContentResolver(), key, value);
    }

    @VisibleForTesting
    protected int getGlobalSetting(String key, int defValue) {
        return Settings.Global.getInt(this.mContext.getContentResolver(), key, defValue);
    }

    public void dump(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println();
            pw.println("Battery saver state machine:");
            pw.print("  Enabled=");
            pw.println(this.mBatterySaverController.isEnabled());
            pw.print("    full=");
            pw.println(this.mBatterySaverController.isFullEnabled());
            pw.print("    adaptive=");
            pw.print(this.mBatterySaverController.isAdaptiveEnabled());
            if (this.mBatterySaverController.isAdaptiveEnabled()) {
                pw.print(" (advertise=");
                pw.print(this.mBatterySaverController.getBatterySaverPolicy().shouldAdvertiseIsEnabled());
                pw.print(")");
            }
            pw.println();
            pw.print("  mState=");
            pw.println(this.mState);
            pw.print("  mLastChangedIntReason=");
            pw.println(this.mLastChangedIntReason);
            pw.print("  mLastChangedStrReason=");
            pw.println(this.mLastChangedStrReason);
            pw.print("  mBootCompleted=");
            pw.println(this.mBootCompleted);
            pw.print("  mSettingsLoaded=");
            pw.println(this.mSettingsLoaded);
            pw.print("  mBatteryStatusSet=");
            pw.println(this.mBatteryStatusSet);
            pw.print("  mIsPowered=");
            pw.println(this.mIsPowered);
            pw.print("  mBatteryLevel=");
            pw.println(this.mBatteryLevel);
            pw.print("  mIsBatteryLevelLow=");
            pw.println(this.mIsBatteryLevelLow);
            pw.print("  mSettingBatterySaverEnabled=");
            pw.println(this.mSettingBatterySaverEnabled);
            pw.print("  mSettingBatterySaverEnabledSticky=");
            pw.println(this.mSettingBatterySaverEnabledSticky);
            pw.print("  mSettingBatterySaverStickyAutoDisableEnabled=");
            pw.println(this.mSettingBatterySaverStickyAutoDisableEnabled);
            pw.print("  mSettingBatterySaverStickyAutoDisableThreshold=");
            pw.println(this.mSettingBatterySaverStickyAutoDisableThreshold);
            pw.print("  mSettingBatterySaverTriggerThreshold=");
            pw.println(this.mSettingBatterySaverTriggerThreshold);
            pw.print("  mBatterySaverStickyBehaviourDisabled=");
            pw.println(this.mBatterySaverStickyBehaviourDisabled);
            pw.print("  mLastAdaptiveBatterySaverChangedExternallyElapsed=");
            pw.println(this.mLastAdaptiveBatterySaverChangedExternallyElapsed);
        }
    }

    public void dumpProto(ProtoOutputStream proto, long tag) {
        synchronized (this.mLock) {
            long token = proto.start(tag);
            proto.write(1133871366145L, this.mBatterySaverController.isEnabled());
            proto.write(1159641169938L, this.mState);
            proto.write(1133871366158L, this.mBatterySaverController.isFullEnabled());
            proto.write(1133871366159L, this.mBatterySaverController.isAdaptiveEnabled());
            proto.write(1133871366160L, this.mBatterySaverController.getBatterySaverPolicy().shouldAdvertiseIsEnabled());
            proto.write(1133871366146L, this.mBootCompleted);
            proto.write(1133871366147L, this.mSettingsLoaded);
            proto.write(1133871366148L, this.mBatteryStatusSet);
            proto.write(1133871366150L, this.mIsPowered);
            proto.write(1120986464263L, this.mBatteryLevel);
            proto.write(1133871366152L, this.mIsBatteryLevelLow);
            proto.write(1133871366153L, this.mSettingBatterySaverEnabled);
            proto.write(1133871366154L, this.mSettingBatterySaverEnabledSticky);
            proto.write(1120986464267L, this.mSettingBatterySaverTriggerThreshold);
            proto.write(1133871366156L, this.mSettingBatterySaverStickyAutoDisableEnabled);
            proto.write(1120986464269L, this.mSettingBatterySaverStickyAutoDisableThreshold);
            proto.write(1112396529681L, this.mLastAdaptiveBatterySaverChangedExternallyElapsed);
            proto.end(token);
        }
    }
}
