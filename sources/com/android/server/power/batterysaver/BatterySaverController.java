package com.android.server.power.batterysaver;

import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import java.util.ArrayList;

/* loaded from: classes.dex */
public class BatterySaverController implements BatterySaverPolicy.BatterySaverPolicyListener {
    static final boolean DEBUG = false;
    public static final int REASON_ADAPTIVE_DYNAMIC_POWER_SAVINGS_CHANGED = 11;
    public static final int REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_OFF = 10;
    public static final int REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_ON = 9;
    public static final int REASON_INTERACTIVE_CHANGED = 5;
    public static final int REASON_MANUAL_OFF = 3;
    public static final int REASON_MANUAL_ON = 2;
    public static final int REASON_PERCENTAGE_AUTOMATIC_OFF = 1;
    public static final int REASON_PERCENTAGE_AUTOMATIC_ON = 0;
    public static final int REASON_PLUGGED_IN = 7;
    public static final int REASON_POLICY_CHANGED = 6;
    public static final int REASON_SETTING_CHANGED = 8;
    public static final int REASON_STICKY_RESTORE = 4;
    public static final int REASON_TIMEOUT = 12;
    static final String TAG = "BatterySaverController";
    @GuardedBy({"mLock"})
    private boolean mAdaptiveEnabled;
    private boolean mAdaptivePreviouslyEnabled;
    private final BatterySaverPolicy mBatterySaverPolicy;
    private final BatterySavingStats mBatterySavingStats;
    private final Context mContext;
    private final FileUpdater mFileUpdater;
    @GuardedBy({"mLock"})
    private boolean mFullEnabled;
    private boolean mFullPreviouslyEnabled;
    private final MyHandler mHandler;
    @GuardedBy({"mLock"})
    private boolean mIsInteractive;
    @GuardedBy({"mLock"})
    private boolean mIsPluggedIn;
    private final Object mLock;
    private final Plugin[] mPlugins;
    private PowerManager mPowerManager;
    @GuardedBy({"mLock"})
    private final ArrayList<PowerManagerInternal.LowPowerModeListener> mListeners = new ArrayList<>();
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.power.batterysaver.BatterySaverController.1
        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            char c;
            String action = intent.getAction();
            boolean z = true;
            switch (action.hashCode()) {
                case -2128145023:
                    if (action.equals("android.intent.action.SCREEN_OFF")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case -1538406691:
                    if (action.equals("android.intent.action.BATTERY_CHANGED")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case -1454123155:
                    if (action.equals("android.intent.action.SCREEN_ON")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 498807504:
                    if (action.equals("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case 870701415:
                    if (action.equals("android.os.action.DEVICE_IDLE_MODE_CHANGED")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            if (c == 0 || c == 1) {
                if (!BatterySaverController.this.isPolicyEnabled()) {
                    BatterySaverController.this.updateBatterySavingStats();
                    return;
                } else {
                    BatterySaverController.this.mHandler.postStateChanged(false, 5);
                    return;
                }
            }
            if (c == 2) {
                synchronized (BatterySaverController.this.mLock) {
                    BatterySaverController batterySaverController = BatterySaverController.this;
                    if (intent.getIntExtra("plugged", 0) == 0) {
                        z = false;
                    }
                    batterySaverController.mIsPluggedIn = z;
                }
            } else if (c != 3 && c != 4) {
                return;
            }
            BatterySaverController.this.updateBatterySavingStats();
        }
    };

    /* loaded from: classes.dex */
    public interface Plugin {
        void onBatterySaverChanged(BatterySaverController batterySaverController);

        void onSystemReady(BatterySaverController batterySaverController);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String reasonToString(int reason) {
        switch (reason) {
            case 0:
                return "Percentage Auto ON";
            case 1:
                return "Percentage Auto OFF";
            case 2:
                return "Manual ON";
            case 3:
                return "Manual OFF";
            case 4:
                return "Sticky restore";
            case 5:
                return "Interactivity changed";
            case 6:
                return "Policy changed";
            case 7:
                return "Plugged in";
            case 8:
                return "Setting changed";
            case 9:
                return "Dynamic Warning Auto ON";
            case 10:
                return "Dynamic Warning Auto OFF";
            case 11:
                return "Adaptive Power Savings changed";
            case 12:
                return "timeout";
            default:
                return "Unknown reason: " + reason;
        }
    }

    public BatterySaverController(Object lock, Context context, Looper looper, BatterySaverPolicy policy, BatterySavingStats batterySavingStats) {
        this.mLock = lock;
        this.mContext = context;
        this.mHandler = new MyHandler(looper);
        this.mBatterySaverPolicy = policy;
        this.mBatterySaverPolicy.addListener(this);
        this.mFileUpdater = new FileUpdater(context);
        this.mBatterySavingStats = batterySavingStats;
        this.mPlugins = new Plugin[]{new BatterySaverLocationPlugin(this.mContext)};
    }

    public void addListener(PowerManagerInternal.LowPowerModeListener listener) {
        synchronized (this.mLock) {
            this.mListeners.add(listener);
        }
    }

    public void systemReady() {
        IntentFilter filter = new IntentFilter("android.intent.action.SCREEN_ON");
        filter.addAction("android.intent.action.SCREEN_OFF");
        filter.addAction("android.intent.action.BATTERY_CHANGED");
        filter.addAction("android.os.action.DEVICE_IDLE_MODE_CHANGED");
        filter.addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED");
        this.mContext.registerReceiver(this.mReceiver, filter);
        this.mFileUpdater.systemReady(((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).isRuntimeRestarted());
        this.mHandler.postSystemReady();
    }

    private PowerManager getPowerManager() {
        if (this.mPowerManager == null) {
            this.mPowerManager = (PowerManager) Preconditions.checkNotNull((PowerManager) this.mContext.getSystemService(PowerManager.class));
        }
        return this.mPowerManager;
    }

    @Override // com.android.server.power.batterysaver.BatterySaverPolicy.BatterySaverPolicyListener
    public void onBatterySaverPolicyChanged(BatterySaverPolicy policy) {
        if (!isPolicyEnabled()) {
            return;
        }
        this.mHandler.postStateChanged(true, 6);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class MyHandler extends Handler {
        private static final int ARG_DONT_SEND_BROADCAST = 0;
        private static final int ARG_SEND_BROADCAST = 1;
        private static final int MSG_STATE_CHANGED = 1;
        private static final int MSG_SYSTEM_READY = 2;

        public MyHandler(Looper looper) {
            super(looper);
        }

        void postStateChanged(boolean sendBroadcast, int reason) {
            obtainMessage(1, sendBroadcast ? 1 : 0, reason).sendToTarget();
        }

        public void postSystemReady() {
            obtainMessage(2, 0, 0).sendToTarget();
        }

        @Override // android.os.Handler
        public void dispatchMessage(Message msg) {
            Plugin[] pluginArr;
            int i = msg.what;
            if (i == 1) {
                BatterySaverController.this.handleBatterySaverStateChanged(msg.arg1 == 1, msg.arg2);
            } else if (i == 2) {
                for (Plugin p : BatterySaverController.this.mPlugins) {
                    p.onSystemReady(BatterySaverController.this);
                }
            }
        }
    }

    @VisibleForTesting
    public void enableBatterySaver(boolean enable, int reason) {
        synchronized (this.mLock) {
            if (this.mFullEnabled == enable) {
                return;
            }
            this.mFullEnabled = enable;
            if (updatePolicyLevelLocked()) {
                this.mHandler.postStateChanged(true, reason);
            }
        }
    }

    private boolean updatePolicyLevelLocked() {
        if (this.mFullEnabled) {
            return this.mBatterySaverPolicy.setPolicyLevel(2);
        }
        if (this.mAdaptiveEnabled) {
            return this.mBatterySaverPolicy.setPolicyLevel(1);
        }
        return this.mBatterySaverPolicy.setPolicyLevel(0);
    }

    public boolean isEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mFullEnabled || (this.mAdaptiveEnabled && this.mBatterySaverPolicy.shouldAdvertiseIsEnabled());
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isPolicyEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mFullEnabled || this.mAdaptiveEnabled;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFullEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mFullEnabled;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAdaptiveEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mAdaptiveEnabled;
        }
        return z;
    }

    boolean setAdaptivePolicyLocked(String settings, String deviceSpecificSettings, int reason) {
        return setAdaptivePolicyLocked(BatterySaverPolicy.Policy.fromSettings(settings, deviceSpecificSettings), reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setAdaptivePolicyLocked(BatterySaverPolicyConfig config, int reason) {
        return setAdaptivePolicyLocked(BatterySaverPolicy.Policy.fromConfig(config), reason);
    }

    boolean setAdaptivePolicyLocked(BatterySaverPolicy.Policy policy, int reason) {
        if (this.mBatterySaverPolicy.setAdaptivePolicyLocked(policy)) {
            this.mHandler.postStateChanged(true, reason);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resetAdaptivePolicyLocked(int reason) {
        if (this.mBatterySaverPolicy.resetAdaptivePolicyLocked()) {
            this.mHandler.postStateChanged(true, reason);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setAdaptivePolicyEnabledLocked(boolean enabled, int reason) {
        if (this.mAdaptiveEnabled == enabled) {
            return false;
        }
        this.mAdaptiveEnabled = enabled;
        if (updatePolicyLevelLocked()) {
            this.mHandler.postStateChanged(true, reason);
            return true;
        }
        return false;
    }

    public boolean isInteractive() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mIsInteractive;
        }
        return z;
    }

    public BatterySaverPolicy getBatterySaverPolicy() {
        return this.mBatterySaverPolicy;
    }

    public boolean isLaunchBoostDisabled() {
        return isPolicyEnabled() && this.mBatterySaverPolicy.isLaunchBoostDisabled();
    }

    /* JADX WARN: Removed duplicated region for block: B:13:0x001e  */
    /* JADX WARN: Removed duplicated region for block: B:14:0x0020  */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0025  */
    /* JADX WARN: Removed duplicated region for block: B:18:0x0027  */
    /* JADX WARN: Removed duplicated region for block: B:21:0x002c  */
    /* JADX WARN: Removed duplicated region for block: B:22:0x002e  */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0034 A[Catch: all -> 0x00f6, TryCatch #0 {, blocks: (B:4:0x000b, B:6:0x0011, B:11:0x0019, B:15:0x0021, B:19:0x0028, B:23:0x002f, B:25:0x0034, B:27:0x003d, B:29:0x005c, B:31:0x0064), top: B:50:0x000b }] */
    /* JADX WARN: Removed duplicated region for block: B:26:0x003b  */
    /* JADX WARN: Removed duplicated region for block: B:29:0x005c A[Catch: all -> 0x00f6, TryCatch #0 {, blocks: (B:4:0x000b, B:6:0x0011, B:11:0x0019, B:15:0x0021, B:19:0x0028, B:23:0x002f, B:25:0x0034, B:27:0x003d, B:29:0x005c, B:31:0x0064), top: B:50:0x000b }] */
    /* JADX WARN: Removed duplicated region for block: B:30:0x0063  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void handleBatterySaverStateChanged(boolean r12, int r13) {
        /*
            Method dump skipped, instructions count: 249
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.power.batterysaver.BatterySaverController.handleBatterySaverStateChanged(boolean, int):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBatterySavingStats() {
        int dozeMode;
        PowerManager pm = getPowerManager();
        if (pm == null) {
            Slog.wtf(TAG, "PowerManager not initialized");
            return;
        }
        boolean isInteractive = pm.isInteractive();
        int i = 2;
        int i2 = 1;
        if (pm.isDeviceIdleMode()) {
            dozeMode = 2;
        } else {
            dozeMode = pm.isLightDeviceIdleMode() ? 1 : 0;
        }
        synchronized (this.mLock) {
            if (this.mIsPluggedIn) {
                this.mBatterySavingStats.startCharging();
                return;
            }
            BatterySavingStats batterySavingStats = this.mBatterySavingStats;
            if (this.mFullEnabled) {
                i = 1;
            } else if (!this.mAdaptiveEnabled) {
                i = 0;
            }
            if (!isInteractive) {
                i2 = 0;
            }
            batterySavingStats.transitionState(i, i2, dozeMode);
        }
    }
}
