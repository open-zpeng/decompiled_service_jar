package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.INetworkManagementEventObserver;
import android.os.Binder;
import android.os.CommonTimeConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.util.DumpUtils;
import com.android.server.UiModeManagerService;
import com.android.server.net.BaseNetworkObserver;
import java.io.FileDescriptor;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class CommonTimeManagementService extends Binder {
    private static final boolean ALLOW_WIFI;
    private static final String ALLOW_WIFI_PROP = "ro.common_time.allow_wifi";
    private static final boolean AUTO_DISABLE;
    private static final String AUTO_DISABLE_PROP = "ro.common_time.auto_disable";
    private static final byte BASE_SERVER_PRIO;
    private static final InterfaceScoreRule[] IFACE_SCORE_RULES;
    private static final int NATIVE_SERVICE_RECONNECT_TIMEOUT = 5000;
    private static final int NO_INTERFACE_TIMEOUT;
    private static final String NO_INTERFACE_TIMEOUT_PROP = "ro.common_time.no_iface_timeout";
    private static final String SERVER_PRIO_PROP = "ro.common_time.server_prio";
    private static final String TAG = CommonTimeManagementService.class.getSimpleName();
    private CommonTimeConfig mCTConfig;
    private final Context mContext;
    private String mCurIface;
    private INetworkManagementService mNetMgr;
    private final Object mLock = new Object();
    private Handler mReconnectHandler = new Handler();
    private Handler mNoInterfaceHandler = new Handler();
    private boolean mDetectedAtStartup = false;
    private byte mEffectivePrio = BASE_SERVER_PRIO;
    private INetworkManagementEventObserver mIfaceObserver = new BaseNetworkObserver() { // from class: com.android.server.CommonTimeManagementService.1
        public void interfaceStatusChanged(String iface, boolean up) {
            CommonTimeManagementService.this.reevaluateServiceState();
        }

        public void interfaceLinkStateChanged(String iface, boolean up) {
            CommonTimeManagementService.this.reevaluateServiceState();
        }

        public void interfaceAdded(String iface) {
            CommonTimeManagementService.this.reevaluateServiceState();
        }

        public void interfaceRemoved(String iface) {
            CommonTimeManagementService.this.reevaluateServiceState();
        }
    };
    private BroadcastReceiver mConnectivityMangerObserver = new BroadcastReceiver() { // from class: com.android.server.CommonTimeManagementService.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            CommonTimeManagementService.this.reevaluateServiceState();
        }
    };
    private CommonTimeConfig.OnServerDiedListener mCTServerDiedListener = new CommonTimeConfig.OnServerDiedListener() { // from class: com.android.server.-$$Lambda$CommonTimeManagementService$2pDf0xdhqutmGymQBZY0XdP5zLg
        public final void onServerDied() {
            CommonTimeManagementService.this.scheduleTimeConfigReconnect();
        }
    };
    private Runnable mReconnectRunnable = new Runnable() { // from class: com.android.server.-$$Lambda$CommonTimeManagementService$o7NVT2DAE8gGyUPocEDzMJMp3rY
        @Override // java.lang.Runnable
        public final void run() {
            CommonTimeManagementService.this.connectToTimeConfig();
        }
    };
    private Runnable mNoInterfaceRunnable = new Runnable() { // from class: com.android.server.-$$Lambda$CommonTimeManagementService$G4hdVfmKjXahO1EZQGCi66JNtFI
        @Override // java.lang.Runnable
        public final void run() {
            CommonTimeManagementService.this.handleNoInterfaceTimeout();
        }
    };

    static {
        AUTO_DISABLE = SystemProperties.getInt(AUTO_DISABLE_PROP, 1) != 0;
        ALLOW_WIFI = SystemProperties.getInt(ALLOW_WIFI_PROP, 0) != 0;
        int tmp = SystemProperties.getInt(SERVER_PRIO_PROP, 1);
        NO_INTERFACE_TIMEOUT = SystemProperties.getInt(NO_INTERFACE_TIMEOUT_PROP, 60000);
        if (tmp < 1) {
            BASE_SERVER_PRIO = (byte) 1;
        } else if (tmp > 30) {
            BASE_SERVER_PRIO = (byte) 30;
        } else {
            BASE_SERVER_PRIO = (byte) tmp;
        }
        if (ALLOW_WIFI) {
            IFACE_SCORE_RULES = new InterfaceScoreRule[]{new InterfaceScoreRule("wlan", (byte) 1), new InterfaceScoreRule("eth", (byte) 2)};
        } else {
            IFACE_SCORE_RULES = new InterfaceScoreRule[]{new InterfaceScoreRule("eth", (byte) 2)};
        }
    }

    public CommonTimeManagementService(Context context) {
        this.mContext = context;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemRunning() {
        if (ServiceManager.checkService("common_time.config") == null) {
            Log.i(TAG, "No common time service detected on this platform.  Common time services will be unavailable.");
            return;
        }
        this.mDetectedAtStartup = true;
        IBinder b = ServiceManager.getService("network_management");
        this.mNetMgr = INetworkManagementService.Stub.asInterface(b);
        try {
            this.mNetMgr.registerObserver(this.mIfaceObserver);
        } catch (RemoteException e) {
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        this.mContext.registerReceiver(this.mConnectivityMangerObserver, filter);
        connectToTimeConfig();
    }

    @Override // android.os.Binder
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            if (!this.mDetectedAtStartup) {
                pw.println("Native Common Time service was not detected at startup.  Service is unavailable");
                return;
            }
            synchronized (this.mLock) {
                pw.println("Current Common Time Management Service Config:");
                Object[] objArr = new Object[1];
                objArr[0] = this.mCTConfig == null ? "reconnecting" : "alive";
                pw.println(String.format("  Native service     : %s", objArr));
                Object[] objArr2 = new Object[1];
                objArr2[0] = this.mCurIface == null ? "unbound" : this.mCurIface;
                pw.println(String.format("  Bound interface    : %s", objArr2));
                Object[] objArr3 = new Object[1];
                objArr3[0] = ALLOW_WIFI ? UiModeManagerService.Shell.NIGHT_MODE_STR_YES : UiModeManagerService.Shell.NIGHT_MODE_STR_NO;
                pw.println(String.format("  Allow WiFi         : %s", objArr3));
                Object[] objArr4 = new Object[1];
                objArr4[0] = AUTO_DISABLE ? UiModeManagerService.Shell.NIGHT_MODE_STR_YES : UiModeManagerService.Shell.NIGHT_MODE_STR_NO;
                pw.println(String.format("  Allow Auto Disable : %s", objArr4));
                pw.println(String.format("  Server Priority    : %d", Byte.valueOf(this.mEffectivePrio)));
                pw.println(String.format("  No iface timeout   : %d", Integer.valueOf(NO_INTERFACE_TIMEOUT)));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class InterfaceScoreRule {
        public final String mPrefix;
        public final byte mScore;

        public InterfaceScoreRule(String prefix, byte score) {
            this.mPrefix = prefix;
            this.mScore = score;
        }
    }

    private void cleanupTimeConfig() {
        this.mReconnectHandler.removeCallbacks(this.mReconnectRunnable);
        this.mNoInterfaceHandler.removeCallbacks(this.mNoInterfaceRunnable);
        if (this.mCTConfig != null) {
            this.mCTConfig.release();
            this.mCTConfig = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void connectToTimeConfig() {
        cleanupTimeConfig();
        try {
            synchronized (this.mLock) {
                this.mCTConfig = new CommonTimeConfig();
                this.mCTConfig.setServerDiedListener(this.mCTServerDiedListener);
                this.mCurIface = this.mCTConfig.getInterfaceBinding();
                this.mCTConfig.setAutoDisable(AUTO_DISABLE);
                this.mCTConfig.setMasterElectionPriority(this.mEffectivePrio);
            }
            if (NO_INTERFACE_TIMEOUT >= 0) {
                this.mNoInterfaceHandler.postDelayed(this.mNoInterfaceRunnable, NO_INTERFACE_TIMEOUT);
            }
            reevaluateServiceState();
        } catch (RemoteException e) {
            scheduleTimeConfigReconnect();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleTimeConfigReconnect() {
        cleanupTimeConfig();
        Log.w(TAG, String.format("Native service died, will reconnect in %d mSec", Integer.valueOf((int) NATIVE_SERVICE_RECONNECT_TIMEOUT)));
        this.mReconnectHandler.postDelayed(this.mReconnectRunnable, 5000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNoInterfaceTimeout() {
        if (this.mCTConfig != null) {
            Log.i(TAG, "Timeout waiting for interface to come up.  Forcing networkless master mode.");
            if (-7 == this.mCTConfig.forceNetworklessMasterMode()) {
                scheduleTimeConfigReconnect();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:32:0x0051  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void reevaluateServiceState() {
        /*
            Method dump skipped, instructions count: 236
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.CommonTimeManagementService.reevaluateServiceState():void");
    }
}
