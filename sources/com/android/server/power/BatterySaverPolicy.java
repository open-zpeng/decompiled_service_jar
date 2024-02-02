package com.android.server.power;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerSaveState;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.power.batterysaver.BatterySavingStats;
import com.android.server.power.batterysaver.CpuFrequencies;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public class BatterySaverPolicy extends ContentObserver {
    public static final boolean DEBUG = false;
    private static final String KEY_ADJUST_BRIGHTNESS_DISABLED = "adjust_brightness_disabled";
    private static final String KEY_ADJUST_BRIGHTNESS_FACTOR = "adjust_brightness_factor";
    private static final String KEY_ANIMATION_DISABLED = "animation_disabled";
    private static final String KEY_AOD_DISABLED = "aod_disabled";
    private static final String KEY_CPU_FREQ_INTERACTIVE = "cpufreq-i";
    private static final String KEY_CPU_FREQ_NONINTERACTIVE = "cpufreq-n";
    private static final String KEY_DATASAVER_DISABLED = "datasaver_disabled";
    private static final String KEY_FIREWALL_DISABLED = "firewall_disabled";
    private static final String KEY_FORCE_ALL_APPS_STANDBY = "force_all_apps_standby";
    private static final String KEY_FORCE_BACKGROUND_CHECK = "force_background_check";
    private static final String KEY_FULLBACKUP_DEFERRED = "fullbackup_deferred";
    private static final String KEY_GPS_MODE = "gps_mode";
    private static final String KEY_KEYVALUE_DEFERRED = "keyvaluebackup_deferred";
    private static final String KEY_LAUNCH_BOOST_DISABLED = "launch_boost_disabled";
    private static final String KEY_OPTIONAL_SENSORS_DISABLED = "optional_sensors_disabled";
    private static final String KEY_SEND_TRON_LOG = "send_tron_log";
    private static final String KEY_SOUNDTRIGGER_DISABLED = "soundtrigger_disabled";
    private static final String KEY_VIBRATION_DISABLED = "vibration_disabled";
    public static final String SECURE_KEY_GPS_MODE = "batterySaverGpsMode";
    private static final String TAG = "BatterySaverPolicy";
    @GuardedBy("mLock")
    private boolean mAccessibilityEnabled;
    @GuardedBy("mLock")
    private boolean mAdjustBrightnessDisabled;
    @GuardedBy("mLock")
    private float mAdjustBrightnessFactor;
    @GuardedBy("mLock")
    private boolean mAnimationDisabled;
    @GuardedBy("mLock")
    private boolean mAodDisabled;
    private final BatterySavingStats mBatterySavingStats;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    @GuardedBy("mLock")
    private boolean mDataSaverDisabled;
    @GuardedBy("mLock")
    private String mDeviceSpecificSettings;
    @GuardedBy("mLock")
    private String mDeviceSpecificSettingsSource;
    @GuardedBy("mLock")
    private String mEventLogKeys;
    @GuardedBy("mLock")
    private ArrayMap<String, String> mFilesForInteractive;
    @GuardedBy("mLock")
    private ArrayMap<String, String> mFilesForNoninteractive;
    @GuardedBy("mLock")
    private boolean mFireWallDisabled;
    @GuardedBy("mLock")
    private boolean mForceAllAppsStandby;
    @GuardedBy("mLock")
    private boolean mForceBackgroundCheck;
    @GuardedBy("mLock")
    private boolean mFullBackupDeferred;
    @GuardedBy("mLock")
    private int mGpsMode;
    private final Handler mHandler;
    @GuardedBy("mLock")
    private boolean mKeyValueBackupDeferred;
    @GuardedBy("mLock")
    private boolean mLaunchBoostDisabled;
    @GuardedBy("mLock")
    private final List<BatterySaverPolicyListener> mListeners;
    private final Object mLock;
    @GuardedBy("mLock")
    private boolean mOptionalSensorsDisabled;
    @GuardedBy("mLock")
    private boolean mSendTronLog;
    @GuardedBy("mLock")
    private String mSettings;
    @GuardedBy("mLock")
    private boolean mSoundTriggerDisabled;
    @GuardedBy("mLock")
    private boolean mVibrationDisabledConfig;
    @GuardedBy("mLock")
    private boolean mVibrationDisabledEffective;

    /* loaded from: classes.dex */
    public interface BatterySaverPolicyListener {
        void onBatterySaverPolicyChanged(BatterySaverPolicy batterySaverPolicy);
    }

    public BatterySaverPolicy(Object lock, Context context, BatterySavingStats batterySavingStats) {
        super(BackgroundThread.getHandler());
        this.mListeners = new ArrayList();
        this.mLock = lock;
        this.mHandler = BackgroundThread.getHandler();
        this.mContext = context;
        this.mContentResolver = context.getContentResolver();
        this.mBatterySavingStats = batterySavingStats;
    }

    public void systemReady() {
        ConcurrentUtils.wtfIfLockHeld(TAG, this.mLock);
        this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("battery_saver_constants"), false, this);
        this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("battery_saver_device_specific_constants"), false, this);
        AccessibilityManager acm = (AccessibilityManager) this.mContext.getSystemService(AccessibilityManager.class);
        acm.addAccessibilityStateChangeListener(new AccessibilityManager.AccessibilityStateChangeListener() { // from class: com.android.server.power.-$$Lambda$BatterySaverPolicy$DPeh8xGdH0ye3BQJ8Ozaqeu6Y30
            @Override // android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener
            public final void onAccessibilityStateChanged(boolean z) {
                BatterySaverPolicy.lambda$systemReady$0(BatterySaverPolicy.this, z);
            }
        });
        boolean enabled = acm.isEnabled();
        synchronized (this.mLock) {
            this.mAccessibilityEnabled = enabled;
        }
        onChange(true, null);
    }

    public static /* synthetic */ void lambda$systemReady$0(BatterySaverPolicy batterySaverPolicy, boolean enabled) {
        synchronized (batterySaverPolicy.mLock) {
            batterySaverPolicy.mAccessibilityEnabled = enabled;
        }
        batterySaverPolicy.refreshSettings();
    }

    public void addListener(BatterySaverPolicyListener listener) {
        synchronized (this.mLock) {
            this.mListeners.add(listener);
        }
    }

    @VisibleForTesting
    String getGlobalSetting(String key) {
        return Settings.Global.getString(this.mContentResolver, key);
    }

    @VisibleForTesting
    int getDeviceSpecificConfigResId() {
        return 17039636;
    }

    @Override // android.database.ContentObserver
    public void onChange(boolean selfChange, Uri uri) {
        refreshSettings();
    }

    private void refreshSettings() {
        final BatterySaverPolicyListener[] listeners;
        synchronized (this.mLock) {
            String setting = getGlobalSetting("battery_saver_constants");
            String deviceSpecificSetting = getGlobalSetting("battery_saver_device_specific_constants");
            this.mDeviceSpecificSettingsSource = "battery_saver_device_specific_constants";
            if (TextUtils.isEmpty(deviceSpecificSetting) || "null".equals(deviceSpecificSetting)) {
                deviceSpecificSetting = this.mContext.getString(getDeviceSpecificConfigResId());
                this.mDeviceSpecificSettingsSource = "(overlay)";
            }
            updateConstantsLocked(setting, deviceSpecificSetting);
            listeners = (BatterySaverPolicyListener[]) this.mListeners.toArray(new BatterySaverPolicyListener[this.mListeners.size()]);
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.power.-$$Lambda$BatterySaverPolicy$9q6hxnTofoZqK_ebwl_HDCH8A4A
            @Override // java.lang.Runnable
            public final void run() {
                BatterySaverPolicy.lambda$refreshSettings$1(BatterySaverPolicy.this, listeners);
            }
        });
    }

    public static /* synthetic */ void lambda$refreshSettings$1(BatterySaverPolicy batterySaverPolicy, BatterySaverPolicyListener[] listeners) {
        for (BatterySaverPolicyListener listener : listeners) {
            listener.onBatterySaverPolicyChanged(batterySaverPolicy);
        }
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    void updateConstantsLocked(String setting, String deviceSpecificSetting) {
        this.mSettings = setting;
        this.mDeviceSpecificSettings = deviceSpecificSetting;
        KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(setting);
        } catch (IllegalArgumentException e) {
            Slog.wtf(TAG, "Bad battery saver constants: " + setting);
        }
        boolean z = true;
        this.mVibrationDisabledConfig = parser.getBoolean(KEY_VIBRATION_DISABLED, true);
        this.mAnimationDisabled = parser.getBoolean(KEY_ANIMATION_DISABLED, false);
        this.mSoundTriggerDisabled = parser.getBoolean(KEY_SOUNDTRIGGER_DISABLED, true);
        this.mFullBackupDeferred = parser.getBoolean(KEY_FULLBACKUP_DEFERRED, true);
        this.mKeyValueBackupDeferred = parser.getBoolean(KEY_KEYVALUE_DEFERRED, true);
        this.mFireWallDisabled = parser.getBoolean(KEY_FIREWALL_DISABLED, false);
        this.mAdjustBrightnessDisabled = parser.getBoolean(KEY_ADJUST_BRIGHTNESS_DISABLED, true);
        this.mAdjustBrightnessFactor = parser.getFloat(KEY_ADJUST_BRIGHTNESS_FACTOR, 0.5f);
        this.mDataSaverDisabled = parser.getBoolean(KEY_DATASAVER_DISABLED, true);
        this.mLaunchBoostDisabled = parser.getBoolean(KEY_LAUNCH_BOOST_DISABLED, true);
        this.mForceAllAppsStandby = parser.getBoolean(KEY_FORCE_ALL_APPS_STANDBY, true);
        this.mForceBackgroundCheck = parser.getBoolean(KEY_FORCE_BACKGROUND_CHECK, true);
        this.mOptionalSensorsDisabled = parser.getBoolean(KEY_OPTIONAL_SENSORS_DISABLED, true);
        this.mAodDisabled = parser.getBoolean(KEY_AOD_DISABLED, true);
        this.mSendTronLog = parser.getBoolean(KEY_SEND_TRON_LOG, false);
        int defaultGpsMode = Settings.Secure.getInt(this.mContentResolver, SECURE_KEY_GPS_MODE, 2);
        this.mGpsMode = parser.getInt(KEY_GPS_MODE, defaultGpsMode);
        try {
            parser.setString(deviceSpecificSetting);
        } catch (IllegalArgumentException e2) {
            Slog.wtf(TAG, "Bad device specific battery saver constants: " + deviceSpecificSetting);
        }
        this.mFilesForInteractive = new CpuFrequencies().parseString(parser.getString(KEY_CPU_FREQ_INTERACTIVE, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)).toSysFileMap();
        this.mFilesForNoninteractive = new CpuFrequencies().parseString(parser.getString(KEY_CPU_FREQ_NONINTERACTIVE, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)).toSysFileMap();
        if (!this.mVibrationDisabledConfig || this.mAccessibilityEnabled) {
            z = false;
        }
        this.mVibrationDisabledEffective = z;
        StringBuilder sb = new StringBuilder();
        if (this.mForceAllAppsStandby) {
            sb.append("A");
        }
        if (this.mForceBackgroundCheck) {
            sb.append("B");
        }
        if (this.mVibrationDisabledEffective) {
            sb.append("v");
        }
        if (this.mAnimationDisabled) {
            sb.append("a");
        }
        if (this.mSoundTriggerDisabled) {
            sb.append("s");
        }
        if (this.mFullBackupDeferred) {
            sb.append("F");
        }
        if (this.mKeyValueBackupDeferred) {
            sb.append("K");
        }
        if (!this.mFireWallDisabled) {
            sb.append("f");
        }
        if (!this.mDataSaverDisabled) {
            sb.append("d");
        }
        if (!this.mAdjustBrightnessDisabled) {
            sb.append("b");
        }
        if (this.mLaunchBoostDisabled) {
            sb.append("l");
        }
        if (this.mOptionalSensorsDisabled) {
            sb.append("S");
        }
        if (this.mAodDisabled) {
            sb.append("o");
        }
        if (this.mSendTronLog) {
            sb.append("t");
        }
        sb.append(this.mGpsMode);
        this.mEventLogKeys = sb.toString();
        this.mBatterySavingStats.setSendTronLog(this.mSendTronLog);
    }

    public PowerSaveState getBatterySaverPolicy(int type, boolean realMode) {
        synchronized (this.mLock) {
            PowerSaveState.Builder builder = new PowerSaveState.Builder().setGlobalBatterySaverEnabled(realMode);
            if (!realMode) {
                return builder.setBatterySaverEnabled(realMode).build();
            }
            switch (type) {
                case 1:
                    return builder.setBatterySaverEnabled(realMode).setGpsMode(this.mGpsMode).build();
                case 2:
                    return builder.setBatterySaverEnabled(this.mVibrationDisabledEffective).build();
                case 3:
                    return builder.setBatterySaverEnabled(this.mAnimationDisabled).build();
                case 4:
                    return builder.setBatterySaverEnabled(this.mFullBackupDeferred).build();
                case 5:
                    return builder.setBatterySaverEnabled(this.mKeyValueBackupDeferred).build();
                case 6:
                    return builder.setBatterySaverEnabled(!this.mFireWallDisabled).build();
                case 7:
                    return builder.setBatterySaverEnabled(!this.mAdjustBrightnessDisabled).setBrightnessFactor(this.mAdjustBrightnessFactor).build();
                case 8:
                    return builder.setBatterySaverEnabled(this.mSoundTriggerDisabled).build();
                case 9:
                default:
                    return builder.setBatterySaverEnabled(realMode).build();
                case 10:
                    return builder.setBatterySaverEnabled(!this.mDataSaverDisabled).build();
                case 11:
                    return builder.setBatterySaverEnabled(this.mForceAllAppsStandby).build();
                case 12:
                    return builder.setBatterySaverEnabled(this.mForceBackgroundCheck).build();
                case 13:
                    return builder.setBatterySaverEnabled(this.mOptionalSensorsDisabled).build();
                case 14:
                    return builder.setBatterySaverEnabled(this.mAodDisabled).build();
            }
        }
    }

    public int getGpsMode() {
        int i;
        synchronized (this.mLock) {
            i = this.mGpsMode;
        }
        return i;
    }

    public ArrayMap<String, String> getFileValues(boolean interactive) {
        ArrayMap<String, String> arrayMap;
        synchronized (this.mLock) {
            try {
                arrayMap = interactive ? this.mFilesForInteractive : this.mFilesForNoninteractive;
            } catch (Throwable th) {
                throw th;
            }
        }
        return arrayMap;
    }

    public boolean isLaunchBoostDisabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mLaunchBoostDisabled;
        }
        return z;
    }

    public String toEventLogString() {
        String str;
        synchronized (this.mLock) {
            str = this.mEventLogKeys;
        }
        return str;
    }

    public void dump(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println();
            this.mBatterySavingStats.dump(pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            pw.println();
            pw.println("Battery saver policy (*NOTE* they only apply when battery saver is ON):");
            pw.println("  Settings: battery_saver_constants");
            pw.println("    value: " + this.mSettings);
            pw.println("  Settings: " + this.mDeviceSpecificSettingsSource);
            pw.println("    value: " + this.mDeviceSpecificSettings);
            pw.println();
            pw.println("  mAccessibilityEnabled=" + this.mAccessibilityEnabled);
            pw.println("  vibration_disabled:config=" + this.mVibrationDisabledConfig);
            pw.println("  vibration_disabled:effective=" + this.mVibrationDisabledEffective);
            pw.println("  animation_disabled=" + this.mAnimationDisabled);
            pw.println("  fullbackup_deferred=" + this.mFullBackupDeferred);
            pw.println("  keyvaluebackup_deferred=" + this.mKeyValueBackupDeferred);
            pw.println("  firewall_disabled=" + this.mFireWallDisabled);
            pw.println("  datasaver_disabled=" + this.mDataSaverDisabled);
            pw.println("  launch_boost_disabled=" + this.mLaunchBoostDisabled);
            pw.println("  adjust_brightness_disabled=" + this.mAdjustBrightnessDisabled);
            pw.println("  adjust_brightness_factor=" + this.mAdjustBrightnessFactor);
            pw.println("  gps_mode=" + this.mGpsMode);
            pw.println("  force_all_apps_standby=" + this.mForceAllAppsStandby);
            pw.println("  force_background_check=" + this.mForceBackgroundCheck);
            pw.println("  optional_sensors_disabled=" + this.mOptionalSensorsDisabled);
            pw.println("  aod_disabled=" + this.mAodDisabled);
            pw.println("  send_tron_log=" + this.mSendTronLog);
            pw.println();
            pw.print("  Interactive File values:\n");
            dumpMap(pw, "    ", this.mFilesForInteractive);
            pw.println();
            pw.print("  Noninteractive File values:\n");
            dumpMap(pw, "    ", this.mFilesForNoninteractive);
        }
    }

    private void dumpMap(PrintWriter pw, String prefix, ArrayMap<String, String> map) {
        if (map == null) {
            return;
        }
        int size = map.size();
        for (int i = 0; i < size; i++) {
            pw.print(prefix);
            pw.print(map.keyAt(i));
            pw.print(": '");
            pw.print(map.valueAt(i));
            pw.println("'");
        }
    }

    @VisibleForTesting
    public void setAccessibilityEnabledForTest(boolean enabled) {
        synchronized (this.mLock) {
            this.mAccessibilityEnabled = enabled;
        }
    }
}
