package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.util.InterfaceSet;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.net.util.VersionedBroadcastListener;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.telephony.CarrierConfigManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.ConnectivityService;
import com.android.server.LocalServices;
import com.android.server.connectivity.tethering.IControlsTethering;
import com.android.server.connectivity.tethering.IPv6TetheringCoordinator;
import com.android.server.connectivity.tethering.OffloadController;
import com.android.server.connectivity.tethering.SimChangeListener;
import com.android.server.connectivity.tethering.TetherInterfaceStateMachine;
import com.android.server.connectivity.tethering.TetheringConfiguration;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.connectivity.tethering.TetheringInterfaceUtils;
import com.android.server.connectivity.tethering.UpstreamNetworkMonitor;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.BaseNetworkObserver;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class Tethering extends BaseNetworkObserver {
    private static final boolean DBG = false;
    protected static final String DISABLE_PROVISIONING_SYSPROP_KEY = "net.tethering.noprovisioning";
    private static final boolean VDBG = false;
    private final VersionedBroadcastListener mCarrierConfigChange;
    private volatile TetheringConfiguration mConfig;
    private final Context mContext;
    private InterfaceSet mCurrentUpstreamIfaceSet;
    private final TetheringDependencies mDeps;
    private final HashSet<TetherInterfaceStateMachine> mForwardedDownstreams;
    private int mLastNotificationId;
    private final SharedLog mLog = new SharedLog(TAG);
    private final Looper mLooper;
    private final INetworkManagementService mNMService;
    private final OffloadController mOffloadController;
    private final INetworkPolicyManager mPolicyManager;
    private final Object mPublicSync;
    private boolean mRndisEnabled;
    private final SimChangeListener mSimChange;
    private final BroadcastReceiver mStateReceiver;
    private final INetworkStatsService mStatsService;
    private final MockableSystemProperties mSystemProperties;
    private final StateMachine mTetherMasterSM;
    private final ArrayMap<String, TetherState> mTetherStates;
    private Notification.Builder mTetheredNotificationBuilder;
    private final UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    private boolean mWifiTetherRequested;
    private static final String TAG = Tethering.class.getSimpleName();
    private static final Class[] messageClasses = {Tethering.class, TetherMasterSM.class, TetherInterfaceStateMachine.class};
    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(messageClasses);
    private static final ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(Resources.getSystem().getString(17039733));

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class TetherState {
        public final TetherInterfaceStateMachine stateMachine;
        public int lastState = 1;
        public int lastError = 0;

        public TetherState(TetherInterfaceStateMachine sm) {
            this.stateMachine = sm;
        }

        public boolean isCurrentlyServing() {
            switch (this.lastState) {
                case 2:
                case 3:
                    return true;
                default:
                    return false;
            }
        }
    }

    public Tethering(Context context, INetworkManagementService nmService, INetworkStatsService statsService, INetworkPolicyManager policyManager, Looper looper, MockableSystemProperties systemProperties, TetheringDependencies deps) {
        this.mLog.mark("constructed");
        this.mContext = context;
        this.mNMService = nmService;
        this.mStatsService = statsService;
        this.mPolicyManager = policyManager;
        this.mLooper = looper;
        this.mSystemProperties = systemProperties;
        this.mDeps = deps;
        this.mPublicSync = new Object();
        this.mTetherStates = new ArrayMap<>();
        this.mTetherMasterSM = new TetherMasterSM("TetherMaster", this.mLooper, deps);
        this.mTetherMasterSM.start();
        Handler smHandler = this.mTetherMasterSM.getHandler();
        this.mOffloadController = new OffloadController(smHandler, this.mDeps.getOffloadHardwareInterface(smHandler, this.mLog), this.mContext.getContentResolver(), this.mNMService, this.mLog);
        this.mUpstreamNetworkMonitor = deps.getUpstreamNetworkMonitor(this.mContext, this.mTetherMasterSM, this.mLog, 327685);
        this.mForwardedDownstreams = new HashSet<>();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.telephony.action.CARRIER_CONFIG_CHANGED");
        this.mCarrierConfigChange = new VersionedBroadcastListener("CarrierConfigChangeListener", this.mContext, smHandler, filter, new Consumer() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$5JkghhOVq1MW7iK03DMZUSuLdFM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                Tethering.lambda$new$0(Tethering.this, (Intent) obj);
            }
        });
        this.mSimChange = new SimChangeListener(this.mContext, smHandler, new Runnable() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$G9TtPVJE34-mHCiIrkFoFBxZRf8
            @Override // java.lang.Runnable
            public final void run() {
                Tethering.this.mLog.log("OBSERVED SIM card change");
            }
        });
        this.mStateReceiver = new StateReceiver();
        updateConfiguration();
        startStateMachineUpdaters();
    }

    public static /* synthetic */ void lambda$new$0(Tethering tethering, Intent ignored) {
        tethering.mLog.log("OBSERVED carrier config change");
        tethering.updateConfiguration();
        tethering.reevaluateSimCardProvisioning();
    }

    private void startStateMachineUpdaters() {
        this.mCarrierConfigChange.startListening();
        Handler handler = this.mTetherMasterSM.getHandler();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_STATE");
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        filter.addAction("android.intent.action.CONFIGURATION_CHANGED");
        this.mContext.registerReceiver(this.mStateReceiver, filter, null, handler);
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction("android.intent.action.MEDIA_SHARED");
        filter2.addAction("android.intent.action.MEDIA_UNSHARED");
        filter2.addDataScheme("file");
        this.mContext.registerReceiver(this.mStateReceiver, filter2, null, handler);
        UserManagerInternal umi = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        if (umi != null) {
            umi.addUserRestrictionsListener(new TetheringUserRestrictionListener(this));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WifiManager getWifiManager() {
        return (WifiManager) this.mContext.getSystemService("wifi");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateConfiguration() {
        this.mConfig = new TetheringConfiguration(this.mContext, this.mLog);
        this.mUpstreamNetworkMonitor.updateMobileRequiresDun(this.mConfig.isDunRequired);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeUpdateConfiguration() {
        int dunCheck = TetheringConfiguration.checkDunRequired(this.mContext);
        if (dunCheck == this.mConfig.dunCheck) {
            return;
        }
        updateConfiguration();
    }

    public void interfaceStatusChanged(String iface, boolean up) {
        synchronized (this.mPublicSync) {
            try {
                if (up) {
                    maybeTrackNewInterfaceLocked(iface);
                } else if (ifaceNameToType(iface) == 2) {
                    stopTrackingInterfaceLocked(iface);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public void interfaceLinkStateChanged(String iface, boolean up) {
        interfaceStatusChanged(iface, up);
    }

    private int ifaceNameToType(String iface) {
        TetheringConfiguration cfg = this.mConfig;
        if (cfg.isWifi(iface)) {
            return 0;
        }
        if (cfg.isUsb(iface)) {
            return 1;
        }
        if (cfg.isBluetooth(iface)) {
            return 2;
        }
        return -1;
    }

    public void interfaceAdded(String iface) {
        synchronized (this.mPublicSync) {
            maybeTrackNewInterfaceLocked(iface);
        }
    }

    public void interfaceRemoved(String iface) {
        synchronized (this.mPublicSync) {
            stopTrackingInterfaceLocked(iface);
        }
    }

    public void startTethering(int type, ResultReceiver receiver, boolean showProvisioningUi) {
        if (!isTetherProvisioningRequired()) {
            enableTetheringInternal(type, true, receiver);
        } else if (showProvisioningUi) {
            runUiTetherProvisioningAndEnable(type, receiver);
        } else {
            runSilentTetherProvisioningAndEnable(type, receiver);
        }
    }

    public void stopTethering(int type) {
        enableTetheringInternal(type, false, null);
        if (isTetherProvisioningRequired()) {
            cancelTetherProvisioningRechecks(type);
        }
    }

    @VisibleForTesting
    protected boolean isTetherProvisioningRequired() {
        TetheringConfiguration cfg = this.mConfig;
        return (this.mSystemProperties.getBoolean(DISABLE_PROVISIONING_SYSPROP_KEY, false) || cfg.provisioningApp.length == 0 || carrierConfigAffirmsEntitlementCheckNotRequired() || cfg.provisioningApp.length != 2) ? false : true;
    }

    private boolean carrierConfigAffirmsEntitlementCheckNotRequired() {
        PersistableBundle carrierConfig;
        CarrierConfigManager configManager = (CarrierConfigManager) this.mContext.getSystemService("carrier_config");
        if (configManager == null || (carrierConfig = configManager.getConfig()) == null) {
            return false;
        }
        boolean isEntitlementCheckRequired = carrierConfig.getBoolean("require_entitlement_checks_bool");
        return !isEntitlementCheckRequired;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enableTetheringInternal(int type, boolean enable, ResultReceiver receiver) {
        boolean isProvisioningRequired = enable && isTetherProvisioningRequired();
        switch (type) {
            case 0:
                int result = setWifiTethering(enable);
                if (isProvisioningRequired && result == 0) {
                    scheduleProvisioningRechecks(type);
                }
                sendTetherResult(receiver, result);
                return;
            case 1:
                int result2 = setUsbTethering(enable);
                if (isProvisioningRequired && result2 == 0) {
                    scheduleProvisioningRechecks(type);
                }
                sendTetherResult(receiver, result2);
                return;
            case 2:
                setBluetoothTethering(enable, receiver);
                return;
            default:
                Log.w(TAG, "Invalid tether type.");
                sendTetherResult(receiver, 1);
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendTetherResult(ResultReceiver receiver, int result) {
        if (receiver != null) {
            receiver.send(result, null);
        }
    }

    private int setWifiTethering(boolean enable) {
        int rval = 5;
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mPublicSync) {
                this.mWifiTetherRequested = enable;
                WifiManager mgr = getWifiManager();
                if ((enable && mgr.startSoftAp(null)) || (!enable && mgr.stopSoftAp())) {
                    rval = 0;
                }
            }
            return rval;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setBluetoothTethering(final boolean enable, final ResultReceiver receiver) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            String str = TAG;
            StringBuilder sb = new StringBuilder();
            sb.append("Tried to enable bluetooth tethering with null or disabled adapter. null: ");
            sb.append(adapter == null);
            Log.w(str, sb.toString());
            sendTetherResult(receiver, 2);
            return;
        }
        adapter.getProfileProxy(this.mContext, new BluetoothProfile.ServiceListener() { // from class: com.android.server.connectivity.Tethering.1
            @Override // android.bluetooth.BluetoothProfile.ServiceListener
            public void onServiceDisconnected(int profile) {
            }

            @Override // android.bluetooth.BluetoothProfile.ServiceListener
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                int result;
                ((BluetoothPan) proxy).setBluetoothTethering(enable);
                if (((BluetoothPan) proxy).isTetheringOn() == enable) {
                    result = 0;
                } else {
                    result = 5;
                }
                Tethering.this.sendTetherResult(receiver, result);
                if (enable && Tethering.this.isTetherProvisioningRequired()) {
                    Tethering.this.scheduleProvisioningRechecks(2);
                }
                adapter.closeProfileProxy(5, proxy);
            }
        }, 5);
    }

    private void runUiTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        ResultReceiver proxyReceiver = getProxyReceiver(type, receiver);
        sendUiTetherProvisionIntent(type, proxyReceiver);
    }

    private void sendUiTetherProvisionIntent(int type, ResultReceiver receiver) {
        Intent intent = new Intent("android.settings.TETHER_PROVISIONING_UI");
        intent.putExtra("extraAddTetherType", type);
        intent.putExtra("extraProvisionCallback", receiver);
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private ResultReceiver getProxyReceiver(final int type, final ResultReceiver receiver) {
        ResultReceiver rr = new ResultReceiver(null) { // from class: com.android.server.connectivity.Tethering.2
            @Override // android.os.ResultReceiver
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == 0) {
                    Tethering.this.enableTetheringInternal(type, true, receiver);
                } else {
                    Tethering.this.sendTetherResult(receiver, resultCode);
                }
            }
        };
        Parcel parcel = Parcel.obtain();
        rr.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ResultReceiver receiverForSending = (ResultReceiver) ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return receiverForSending;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleProvisioningRechecks(int type) {
        Intent intent = new Intent();
        intent.putExtra("extraAddTetherType", type);
        intent.putExtra("extraSetAlarm", true);
        intent.setComponent(TETHER_SERVICE);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void runSilentTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        ResultReceiver proxyReceiver = getProxyReceiver(type, receiver);
        sendSilentTetherProvisionIntent(type, proxyReceiver);
    }

    private void sendSilentTetherProvisionIntent(int type, ResultReceiver receiver) {
        Intent intent = new Intent();
        intent.putExtra("extraAddTetherType", type);
        intent.putExtra("extraRunProvision", true);
        intent.putExtra("extraProvisionCallback", receiver);
        intent.setComponent(TETHER_SERVICE);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void cancelTetherProvisioningRechecks(int type) {
        if (this.mDeps.isTetheringSupported()) {
            Intent intent = new Intent();
            intent.putExtra("extraRemTetherType", type);
            intent.setComponent(TETHER_SERVICE);
            long ident = Binder.clearCallingIdentity();
            try {
                this.mContext.startServiceAsUser(intent, UserHandle.CURRENT);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void startProvisionIntent(int tetherType) {
        Intent startProvIntent = new Intent();
        startProvIntent.putExtra("extraAddTetherType", tetherType);
        startProvIntent.putExtra("extraRunProvision", true);
        startProvIntent.setComponent(TETHER_SERVICE);
        this.mContext.startServiceAsUser(startProvIntent, UserHandle.CURRENT);
    }

    public int tether(String iface) {
        return tether(iface, 2);
    }

    private int tether(String iface, int requestedState) {
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null) {
                if (tetherState.lastState != 1) {
                    String str = TAG;
                    Log.e(str, "Tried to Tether an unavailable iface: " + iface + ", ignoring");
                    return 4;
                }
                tetherState.stateMachine.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED, requestedState);
                return 0;
            }
            String str2 = TAG;
            Log.e(str2, "Tried to Tether an unknown iface: " + iface + ", ignoring");
            return 1;
        }
    }

    public int untether(String iface) {
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState == null) {
                String str = TAG;
                Log.e(str, "Tried to Untether an unknown iface :" + iface + ", ignoring");
                return 1;
            } else if (!tetherState.isCurrentlyServing()) {
                String str2 = TAG;
                Log.e(str2, "Tried to untether an inactive iface :" + iface + ", ignoring");
                return 4;
            } else {
                tetherState.stateMachine.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
                return 0;
            }
        }
    }

    public void untetherAll() {
        stopTethering(0);
        stopTethering(1);
        stopTethering(2);
    }

    public int getLastTetherError(String iface) {
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState == null) {
                String str = TAG;
                Log.e(str, "Tried to getLastTetherError on an unknown iface :" + iface + ", ignoring");
                return 1;
            }
            return tetherState.lastError;
        }
    }

    private void sendTetherStateChangedBroadcast() {
        if (this.mDeps.isTetheringSupported()) {
            ArrayList<String> availableList = new ArrayList<>();
            ArrayList<String> tetherList = new ArrayList<>();
            ArrayList<String> localOnlyList = new ArrayList<>();
            ArrayList<String> erroredList = new ArrayList<>();
            boolean wifiTethered = false;
            boolean usbTethered = false;
            boolean bluetoothTethered = false;
            TetheringConfiguration cfg = this.mConfig;
            synchronized (this.mPublicSync) {
                for (int i = 0; i < this.mTetherStates.size(); i++) {
                    TetherState tetherState = this.mTetherStates.valueAt(i);
                    String iface = this.mTetherStates.keyAt(i);
                    if (tetherState.lastError != 0) {
                        erroredList.add(iface);
                    } else if (tetherState.lastState == 1) {
                        availableList.add(iface);
                    } else if (tetherState.lastState == 3) {
                        localOnlyList.add(iface);
                    } else if (tetherState.lastState == 2) {
                        if (cfg.isUsb(iface)) {
                            usbTethered = true;
                        } else if (cfg.isWifi(iface)) {
                            wifiTethered = true;
                        } else if (cfg.isBluetooth(iface)) {
                            bluetoothTethered = true;
                        }
                        tetherList.add(iface);
                    }
                }
            }
            Intent bcast = new Intent("android.net.conn.TETHER_STATE_CHANGED");
            bcast.addFlags(603979776);
            bcast.putStringArrayListExtra("availableArray", availableList);
            bcast.putStringArrayListExtra("localOnlyArray", localOnlyList);
            bcast.putStringArrayListExtra("tetherArray", tetherList);
            bcast.putStringArrayListExtra("erroredArray", erroredList);
            this.mContext.sendStickyBroadcastAsUser(bcast, UserHandle.ALL);
            if (usbTethered) {
                if (wifiTethered || bluetoothTethered) {
                    showTetheredNotification(14);
                } else {
                    showTetheredNotification(15);
                }
            } else if (wifiTethered) {
                if (bluetoothTethered) {
                    showTetheredNotification(14);
                } else {
                    clearTetheredNotification();
                }
            } else if (bluetoothTethered) {
                showTetheredNotification(16);
            } else {
                clearTetheredNotification();
            }
        }
    }

    private void showTetheredNotification(int id) {
        showTetheredNotification(id, true);
    }

    @VisibleForTesting
    protected void showTetheredNotification(int id, boolean tetheringOn) {
        int icon;
        CharSequence title;
        CharSequence message;
        NotificationManager notificationManager = (NotificationManager) this.mContext.getSystemService("notification");
        if (notificationManager == null) {
            return;
        }
        switch (id) {
            case 15:
                icon = 17303523;
                break;
            case 16:
                icon = 17303521;
                break;
            default:
                icon = 17303522;
                break;
        }
        if (this.mLastNotificationId != 0) {
            if (this.mLastNotificationId == icon) {
                return;
            }
            notificationManager.cancelAsUser(null, this.mLastNotificationId, UserHandle.ALL);
            this.mLastNotificationId = 0;
        }
        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        intent.setFlags(1073741824);
        PendingIntent pi = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 0, null, UserHandle.CURRENT);
        Resources r = Resources.getSystem();
        if (tetheringOn) {
            title = r.getText(17040979);
            message = r.getText(17040978);
        } else {
            title = r.getText(17039814);
            message = r.getText(17039813);
        }
        if (this.mTetheredNotificationBuilder == null) {
            this.mTetheredNotificationBuilder = new Notification.Builder(this.mContext, SystemNotificationChannels.NETWORK_STATUS);
            this.mTetheredNotificationBuilder.setWhen(0L).setOngoing(true).setColor(this.mContext.getColor(17170861)).setVisibility(1).setCategory("status");
        }
        this.mTetheredNotificationBuilder.setSmallIcon(icon).setContentTitle(title).setContentText(message).setContentIntent(pi);
        this.mLastNotificationId = id;
        notificationManager.notifyAsUser(null, this.mLastNotificationId, this.mTetheredNotificationBuilder.buildInto(new Notification()), UserHandle.ALL);
    }

    @VisibleForTesting
    protected void clearTetheredNotification() {
        NotificationManager notificationManager = (NotificationManager) this.mContext.getSystemService("notification");
        if (notificationManager != null && this.mLastNotificationId != 0) {
            notificationManager.cancelAsUser(null, this.mLastNotificationId, UserHandle.ALL);
            this.mLastNotificationId = 0;
        }
    }

    /* loaded from: classes.dex */
    private class StateReceiver extends BroadcastReceiver {
        private StateReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (action.equals("android.hardware.usb.action.USB_STATE")) {
                handleUsbAction(intent);
            } else if (action.equals("android.net.conn.CONNECTIVITY_CHANGE")) {
                handleConnectivityAction(intent);
            } else if (action.equals("android.net.wifi.WIFI_AP_STATE_CHANGED")) {
                handleWifiApAction(intent);
            } else if (action.equals("android.intent.action.CONFIGURATION_CHANGED")) {
                Tethering.this.mLog.log("OBSERVED configuration changed");
                Tethering.this.updateConfiguration();
            }
        }

        private void handleConnectivityAction(Intent intent) {
            NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra("networkInfo");
            if (networkInfo != null && networkInfo.getDetailedState() != NetworkInfo.DetailedState.FAILED) {
                Tethering.this.mTetherMasterSM.sendMessage(327683);
            }
        }

        private void handleUsbAction(Intent intent) {
            boolean z = false;
            boolean usbConnected = intent.getBooleanExtra("connected", false);
            boolean usbConfigured = intent.getBooleanExtra("configured", false);
            boolean rndisEnabled = intent.getBooleanExtra("rndis", false);
            Tethering.this.mLog.log(String.format("USB bcast connected:%s configured:%s rndis:%s", Boolean.valueOf(usbConnected), Boolean.valueOf(usbConfigured), Boolean.valueOf(rndisEnabled)));
            synchronized (Tethering.this.mPublicSync) {
                if (!usbConnected) {
                    try {
                        if (Tethering.this.mRndisEnabled) {
                            Tethering.this.tetherMatchingInterfaces(1, 1);
                            Tethering tethering = Tethering.this;
                            if (usbConfigured && rndisEnabled) {
                                z = true;
                            }
                            tethering.mRndisEnabled = z;
                        }
                    } catch (Throwable th) {
                        throw th;
                    }
                }
                if (usbConfigured && rndisEnabled) {
                    Tethering.this.tetherMatchingInterfaces(2, 1);
                }
                Tethering tethering2 = Tethering.this;
                if (usbConfigured) {
                    z = true;
                }
                tethering2.mRndisEnabled = z;
            }
        }

        private void handleWifiApAction(Intent intent) {
            int curState = intent.getIntExtra("wifi_state", 11);
            String ifname = intent.getStringExtra("wifi_ap_interface_name");
            int ipmode = intent.getIntExtra("wifi_ap_mode", -1);
            synchronized (Tethering.this.mPublicSync) {
                switch (curState) {
                    case 12:
                        break;
                    case 13:
                        Tethering.this.enableWifiIpServingLocked(ifname, ipmode);
                        break;
                    default:
                        Tethering.this.disableWifiIpServingLocked(ifname, curState);
                        break;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class TetheringUserRestrictionListener implements UserManagerInternal.UserRestrictionsListener {
        private final Tethering mWrapper;

        public TetheringUserRestrictionListener(Tethering wrapper) {
            this.mWrapper = wrapper;
        }

        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions, Bundle prevRestrictions) {
            boolean newlyDisallowed = newRestrictions.getBoolean("no_config_tethering");
            boolean previouslyDisallowed = prevRestrictions.getBoolean("no_config_tethering");
            boolean tetheringDisallowedChanged = newlyDisallowed != previouslyDisallowed;
            if (!tetheringDisallowedChanged) {
                return;
            }
            this.mWrapper.clearTetheredNotification();
            boolean isTetheringActiveOnDevice = this.mWrapper.getTetheredIfaces().length != 0;
            if (newlyDisallowed && isTetheringActiveOnDevice) {
                this.mWrapper.showTetheredNotification(17303522, false);
                this.mWrapper.untetherAll();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disableWifiIpServingLocked(String ifname, int apState) {
        TetherState ts;
        this.mLog.log("Canceling WiFi tethering request - AP_STATE=" + apState);
        this.mWifiTetherRequested = false;
        if (!TextUtils.isEmpty(ifname) && (ts = this.mTetherStates.get(ifname)) != null) {
            ts.stateMachine.unwanted();
            return;
        }
        for (int i = 0; i < this.mTetherStates.size(); i++) {
            TetherInterfaceStateMachine tism = this.mTetherStates.valueAt(i).stateMachine;
            if (tism.interfaceType() == 0) {
                tism.unwanted();
                return;
            }
        }
        SharedLog sharedLog = this.mLog;
        StringBuilder sb = new StringBuilder();
        sb.append("Error disabling Wi-Fi IP serving; ");
        sb.append(TextUtils.isEmpty(ifname) ? "no interface name specified" : "specified interface: " + ifname);
        sharedLog.log(sb.toString());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enableWifiIpServingLocked(String ifname, int wifiIpMode) {
        int ipServingMode;
        switch (wifiIpMode) {
            case 1:
                ipServingMode = 2;
                break;
            case 2:
                ipServingMode = 3;
                break;
            default:
                SharedLog sharedLog = this.mLog;
                sharedLog.e("Cannot enable IP serving in unknown WiFi mode: " + wifiIpMode);
                return;
        }
        if (!TextUtils.isEmpty(ifname)) {
            maybeTrackNewInterfaceLocked(ifname, 0);
            changeInterfaceState(ifname, ipServingMode);
            return;
        }
        this.mLog.e(String.format("Cannot enable IP serving in mode %s on missing interface name", Integer.valueOf(ipServingMode)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tetherMatchingInterfaces(int requestedState, int interfaceType) {
        try {
            String[] ifaces = this.mNMService.listInterfaces();
            String chosenIface = null;
            if (ifaces != null) {
                int length = ifaces.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        break;
                    }
                    String iface = ifaces[i];
                    if (ifaceNameToType(iface) != interfaceType) {
                        i++;
                    } else {
                        chosenIface = iface;
                        break;
                    }
                }
            }
            if (chosenIface == null) {
                String str = TAG;
                Log.e(str, "could not find iface of type " + interfaceType);
                return;
            }
            changeInterfaceState(chosenIface, requestedState);
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces", e);
        }
    }

    private void changeInterfaceState(String ifname, int requestedState) {
        int result;
        switch (requestedState) {
            case 0:
            case 1:
                result = untether(ifname);
                break;
            case 2:
            case 3:
                result = tether(ifname, requestedState);
                break;
            default:
                String str = TAG;
                Log.wtf(str, "Unknown interface state: " + requestedState);
                return;
        }
        if (result != 0) {
            String str2 = TAG;
            Log.e(str2, "unable start or stop tethering on iface " + ifname);
        }
    }

    public TetheringConfiguration getTetheringConfiguration() {
        return this.mConfig;
    }

    public boolean hasTetherableConfiguration() {
        TetheringConfiguration cfg = this.mConfig;
        boolean hasDownstreamConfiguration = (cfg.tetherableUsbRegexs.length == 0 && cfg.tetherableWifiRegexs.length == 0 && cfg.tetherableBluetoothRegexs.length == 0) ? false : true;
        boolean hasUpstreamConfiguration = !cfg.preferredUpstreamIfaceTypes.isEmpty();
        return hasDownstreamConfiguration && hasUpstreamConfiguration;
    }

    public String[] getTetherableUsbRegexs() {
        return copy(this.mConfig.tetherableUsbRegexs);
    }

    public String[] getTetherableWifiRegexs() {
        return copy(this.mConfig.tetherableWifiRegexs);
    }

    public String[] getTetherableBluetoothRegexs() {
        return copy(this.mConfig.tetherableBluetoothRegexs);
    }

    public int setUsbTethering(boolean enable) {
        UsbManager usbManager = (UsbManager) this.mContext.getSystemService("usb");
        synchronized (this.mPublicSync) {
            usbManager.setCurrentFunctions(enable ? 32L : 0L);
        }
        return 0;
    }

    public String[] getTetheredIfaces() {
        ArrayList<String> list = new ArrayList<>();
        synchronized (this.mPublicSync) {
            for (int i = 0; i < this.mTetherStates.size(); i++) {
                TetherState tetherState = this.mTetherStates.valueAt(i);
                if (tetherState.lastState == 2) {
                    list.add(this.mTetherStates.keyAt(i));
                }
            }
        }
        return (String[]) list.toArray(new String[list.size()]);
    }

    public String[] getTetherableIfaces() {
        ArrayList<String> list = new ArrayList<>();
        synchronized (this.mPublicSync) {
            for (int i = 0; i < this.mTetherStates.size(); i++) {
                TetherState tetherState = this.mTetherStates.valueAt(i);
                if (tetherState.lastState == 1) {
                    list.add(this.mTetherStates.keyAt(i));
                }
            }
        }
        return (String[]) list.toArray(new String[list.size()]);
    }

    public String[] getTetheredDhcpRanges() {
        return this.mConfig.dhcpRanges;
    }

    public String[] getErroredIfaces() {
        ArrayList<String> list = new ArrayList<>();
        synchronized (this.mPublicSync) {
            for (int i = 0; i < this.mTetherStates.size(); i++) {
                TetherState tetherState = this.mTetherStates.valueAt(i);
                if (tetherState.lastError != 0) {
                    list.add(this.mTetherStates.keyAt(i));
                }
            }
        }
        return (String[]) list.toArray(new String[list.size()]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logMessage(State state, int what) {
        SharedLog sharedLog = this.mLog;
        sharedLog.log(state.getName() + " got " + sMagicDecoderRing.get(what, Integer.toString(what)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean upstreamWanted() {
        boolean z;
        if (this.mForwardedDownstreams.isEmpty()) {
            synchronized (this.mPublicSync) {
                z = this.mWifiTetherRequested;
            }
            return z;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean pertainsToCurrentUpstream(NetworkState ns) {
        if (ns != null && ns.linkProperties != null && this.mCurrentUpstreamIfaceSet != null) {
            for (String ifname : ns.linkProperties.getAllInterfaceNames()) {
                if (this.mCurrentUpstreamIfaceSet.ifnames.contains(ifname)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private void reevaluateSimCardProvisioning() {
        if (this.mConfig.hasMobileHotspotProvisionApp() && !carrierConfigAffirmsEntitlementCheckNotRequired()) {
            ArrayList<Integer> tethered = new ArrayList<>();
            synchronized (this.mPublicSync) {
                for (int i = 0; i < this.mTetherStates.size(); i++) {
                    TetherState tetherState = this.mTetherStates.valueAt(i);
                    if (tetherState.lastState == 2) {
                        String iface = this.mTetherStates.keyAt(i);
                        int interfaceType = ifaceNameToType(iface);
                        if (interfaceType != -1) {
                            tethered.add(Integer.valueOf(interfaceType));
                        }
                    }
                }
            }
            Iterator<Integer> it = tethered.iterator();
            while (it.hasNext()) {
                int tetherType = it.next().intValue();
                startProvisionIntent(tetherType);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class TetherMasterSM extends StateMachine {
        private static final int BASE_MASTER = 327680;
        static final int CMD_CLEAR_ERROR = 327686;
        static final int CMD_RETRY_UPSTREAM = 327684;
        static final int CMD_UPSTREAM_CHANGED = 327683;
        static final int EVENT_IFACE_SERVING_STATE_ACTIVE = 327681;
        static final int EVENT_IFACE_SERVING_STATE_INACTIVE = 327682;
        static final int EVENT_IFACE_UPDATE_LINKPROPERTIES = 327687;
        static final int EVENT_UPSTREAM_CALLBACK = 327685;
        private static final int UPSTREAM_SETTLE_TIME_MS = 10000;
        private final IPv6TetheringCoordinator mIPv6TetheringCoordinator;
        private final State mInitialState;
        private final ArrayList<TetherInterfaceStateMachine> mNotifyList;
        private final OffloadWrapper mOffload;
        private final State mSetDnsForwardersErrorState;
        private final State mSetIpForwardingDisabledErrorState;
        private final State mSetIpForwardingEnabledErrorState;
        private final State mStartTetheringErrorState;
        private final State mStopTetheringErrorState;
        private final State mTetherModeAliveState;

        TetherMasterSM(String name, Looper looper, TetheringDependencies deps) {
            super(name, looper);
            this.mInitialState = new InitialState();
            this.mTetherModeAliveState = new TetherModeAliveState();
            this.mSetIpForwardingEnabledErrorState = new SetIpForwardingEnabledErrorState();
            this.mSetIpForwardingDisabledErrorState = new SetIpForwardingDisabledErrorState();
            this.mStartTetheringErrorState = new StartTetheringErrorState();
            this.mStopTetheringErrorState = new StopTetheringErrorState();
            this.mSetDnsForwardersErrorState = new SetDnsForwardersErrorState();
            addState(this.mInitialState);
            addState(this.mTetherModeAliveState);
            addState(this.mSetIpForwardingEnabledErrorState);
            addState(this.mSetIpForwardingDisabledErrorState);
            addState(this.mStartTetheringErrorState);
            addState(this.mStopTetheringErrorState);
            addState(this.mSetDnsForwardersErrorState);
            this.mNotifyList = new ArrayList<>();
            this.mIPv6TetheringCoordinator = deps.getIPv6TetheringCoordinator(this.mNotifyList, Tethering.this.mLog);
            this.mOffload = new OffloadWrapper();
            setInitialState(this.mInitialState);
        }

        /* loaded from: classes.dex */
        class InitialState extends State {
            InitialState() {
            }

            public boolean processMessage(Message message) {
                Tethering.this.logMessage(this, message.what);
                int i = message.what;
                if (i != TetherMasterSM.EVENT_IFACE_UPDATE_LINKPROPERTIES) {
                    switch (i) {
                        case TetherMasterSM.EVENT_IFACE_SERVING_STATE_ACTIVE /* 327681 */:
                            TetherInterfaceStateMachine who = (TetherInterfaceStateMachine) message.obj;
                            TetherMasterSM.this.handleInterfaceServingStateActive(message.arg1, who);
                            TetherMasterSM.this.transitionTo(TetherMasterSM.this.mTetherModeAliveState);
                            return true;
                        case TetherMasterSM.EVENT_IFACE_SERVING_STATE_INACTIVE /* 327682 */:
                            TetherInterfaceStateMachine who2 = (TetherInterfaceStateMachine) message.obj;
                            TetherMasterSM.this.handleInterfaceServingStateInactive(who2);
                            return true;
                        default:
                            return false;
                    }
                }
                return true;
            }
        }

        protected boolean turnOnMasterTetherSettings() {
            TetheringConfiguration cfg = Tethering.this.mConfig;
            try {
                Tethering.this.mNMService.setIpForwardingEnabled(true);
                try {
                    Tethering.this.mNMService.startTethering(cfg.dhcpRanges);
                } catch (Exception e) {
                    try {
                        Tethering.this.mNMService.stopTethering();
                        Tethering.this.mNMService.startTethering(cfg.dhcpRanges);
                    } catch (Exception ee) {
                        Tethering.this.mLog.e(ee);
                        transitionTo(this.mStartTetheringErrorState);
                        return false;
                    }
                }
                Tethering.this.mLog.log("SET master tether settings: ON");
                return true;
            } catch (Exception e2) {
                Tethering.this.mLog.e(e2);
                transitionTo(this.mSetIpForwardingEnabledErrorState);
                return false;
            }
        }

        protected boolean turnOffMasterTetherSettings() {
            try {
                Tethering.this.mNMService.stopTethering();
                try {
                    Tethering.this.mNMService.setIpForwardingEnabled(false);
                    transitionTo(this.mInitialState);
                    Tethering.this.mLog.log("SET master tether settings: OFF");
                    return true;
                } catch (Exception e) {
                    Tethering.this.mLog.e(e);
                    transitionTo(this.mSetIpForwardingDisabledErrorState);
                    return false;
                }
            } catch (Exception e2) {
                Tethering.this.mLog.e(e2);
                transitionTo(this.mStopTetheringErrorState);
                return false;
            }
        }

        protected void chooseUpstreamType(boolean tryCell) {
            Tethering.this.maybeUpdateConfiguration();
            TetheringConfiguration config = Tethering.this.mConfig;
            NetworkState ns = config.chooseUpstreamAutomatically ? Tethering.this.mUpstreamNetworkMonitor.getCurrentPreferredUpstream() : Tethering.this.mUpstreamNetworkMonitor.selectPreferredUpstreamType(config.preferredUpstreamIfaceTypes);
            if (ns == null) {
                if (tryCell) {
                    Tethering.this.mUpstreamNetworkMonitor.registerMobileNetworkRequest();
                } else {
                    sendMessageDelayed(CMD_RETRY_UPSTREAM, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
                }
            }
            Tethering.this.mUpstreamNetworkMonitor.setCurrentUpstream(ns != null ? ns.network : null);
            setUpstreamNetwork(ns);
        }

        protected void setUpstreamNetwork(NetworkState ns) {
            InterfaceSet ifaces = null;
            if (ns != null) {
                SharedLog sharedLog = Tethering.this.mLog;
                sharedLog.i("Looking for default routes on: " + ns.linkProperties);
                ifaces = TetheringInterfaceUtils.getTetheringInterfaces(ns);
                SharedLog sharedLog2 = Tethering.this.mLog;
                sharedLog2.i("Found upstream interface(s): " + ifaces);
            }
            if (ifaces != null) {
                setDnsForwarders(ns.network, ns.linkProperties);
            }
            notifyDownstreamsOfNewUpstreamIface(ifaces);
            if (ns == null || !Tethering.this.pertainsToCurrentUpstream(ns)) {
                if (Tethering.this.mCurrentUpstreamIfaceSet == null) {
                    handleNewUpstreamNetworkState(null);
                    return;
                }
                return;
            }
            handleNewUpstreamNetworkState(ns);
        }

        protected void setDnsForwarders(Network network, LinkProperties lp) {
            String[] dnsServers = Tethering.this.mConfig.defaultIPv4DNS;
            Collection<InetAddress> dnses = lp.getDnsServers();
            if (dnses != null && !dnses.isEmpty()) {
                dnsServers = NetworkUtils.makeStrings(dnses);
            }
            try {
                Tethering.this.mNMService.setDnsForwarders(network, dnsServers);
                Tethering.this.mLog.log(String.format("SET DNS forwarders: network=%s dnsServers=%s", network, Arrays.toString(dnsServers)));
            } catch (Exception e) {
                SharedLog sharedLog = Tethering.this.mLog;
                sharedLog.e("setting DNS forwarders failed, " + e);
                transitionTo(this.mSetDnsForwardersErrorState);
            }
        }

        protected void notifyDownstreamsOfNewUpstreamIface(InterfaceSet ifaces) {
            Tethering.this.mCurrentUpstreamIfaceSet = ifaces;
            Iterator<TetherInterfaceStateMachine> it = this.mNotifyList.iterator();
            while (it.hasNext()) {
                TetherInterfaceStateMachine sm = it.next();
                sm.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED, ifaces);
            }
        }

        protected void handleNewUpstreamNetworkState(NetworkState ns) {
            this.mIPv6TetheringCoordinator.updateUpstreamNetworkState(ns);
            this.mOffload.updateUpstreamNetworkState(ns);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleInterfaceServingStateActive(int mode, TetherInterfaceStateMachine who) {
            if (this.mNotifyList.indexOf(who) < 0) {
                this.mNotifyList.add(who);
                this.mIPv6TetheringCoordinator.addActiveDownstream(who, mode);
            }
            if (mode == 2) {
                Tethering.this.mForwardedDownstreams.add(who);
            } else {
                this.mOffload.excludeDownstreamInterface(who.interfaceName());
                Tethering.this.mForwardedDownstreams.remove(who);
            }
            if (who.interfaceType() == 0) {
                WifiManager mgr = Tethering.this.getWifiManager();
                String iface = who.interfaceName();
                switch (mode) {
                    case 2:
                        mgr.updateInterfaceIpState(iface, 1);
                        return;
                    case 3:
                        mgr.updateInterfaceIpState(iface, 2);
                        return;
                    default:
                        String str = Tethering.TAG;
                        Log.wtf(str, "Unknown active serving mode: " + mode);
                        return;
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleInterfaceServingStateInactive(TetherInterfaceStateMachine who) {
            this.mNotifyList.remove(who);
            this.mIPv6TetheringCoordinator.removeActiveDownstream(who);
            this.mOffload.excludeDownstreamInterface(who.interfaceName());
            Tethering.this.mForwardedDownstreams.remove(who);
            if (who.interfaceType() == 0 && who.lastError() != 0) {
                Tethering.this.getWifiManager().updateInterfaceIpState(who.interfaceName(), 0);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleUpstreamNetworkMonitorCallback(int arg1, Object o) {
            if (arg1 == 10) {
                this.mOffload.sendOffloadExemptPrefixes((Set) o);
                return;
            }
            NetworkState ns = (NetworkState) o;
            if (ns == null || !Tethering.this.pertainsToCurrentUpstream(ns)) {
                if (Tethering.this.mCurrentUpstreamIfaceSet == null) {
                    chooseUpstreamType(false);
                    return;
                }
                return;
            }
            switch (arg1) {
                case 1:
                    handleNewUpstreamNetworkState(ns);
                    return;
                case 2:
                    chooseUpstreamType(false);
                    return;
                case 3:
                    handleNewUpstreamNetworkState(null);
                    return;
                default:
                    SharedLog sharedLog = Tethering.this.mLog;
                    sharedLog.e("Unknown arg1 value: " + arg1);
                    return;
            }
        }

        /* loaded from: classes.dex */
        class TetherModeAliveState extends State {
            boolean mUpstreamWanted = false;
            boolean mTryCell = true;

            TetherModeAliveState() {
            }

            public void enter() {
                if (TetherMasterSM.this.turnOnMasterTetherSettings()) {
                    Tethering.this.mSimChange.startListening();
                    Tethering.this.mUpstreamNetworkMonitor.start(Tethering.this.mDeps.getDefaultNetworkRequest());
                    if (Tethering.this.upstreamWanted()) {
                        this.mUpstreamWanted = true;
                        TetherMasterSM.this.mOffload.start();
                        TetherMasterSM.this.chooseUpstreamType(true);
                        this.mTryCell = false;
                    }
                }
            }

            public void exit() {
                TetherMasterSM.this.mOffload.stop();
                Tethering.this.mUpstreamNetworkMonitor.stop();
                Tethering.this.mSimChange.stopListening();
                TetherMasterSM.this.notifyDownstreamsOfNewUpstreamIface(null);
                TetherMasterSM.this.handleNewUpstreamNetworkState(null);
            }

            private boolean updateUpstreamWanted() {
                boolean previousUpstreamWanted = this.mUpstreamWanted;
                this.mUpstreamWanted = Tethering.this.upstreamWanted();
                if (this.mUpstreamWanted != previousUpstreamWanted) {
                    if (this.mUpstreamWanted) {
                        TetherMasterSM.this.mOffload.start();
                    } else {
                        TetherMasterSM.this.mOffload.stop();
                    }
                }
                return previousUpstreamWanted;
            }

            public boolean processMessage(Message message) {
                Tethering.this.logMessage(this, message.what);
                switch (message.what) {
                    case TetherMasterSM.EVENT_IFACE_SERVING_STATE_ACTIVE /* 327681 */:
                        TetherInterfaceStateMachine who = (TetherInterfaceStateMachine) message.obj;
                        TetherMasterSM.this.handleInterfaceServingStateActive(message.arg1, who);
                        who.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED, Tethering.this.mCurrentUpstreamIfaceSet);
                        boolean previousUpstreamWanted = updateUpstreamWanted();
                        if (previousUpstreamWanted || !this.mUpstreamWanted) {
                            return true;
                        }
                        TetherMasterSM.this.chooseUpstreamType(true);
                        return true;
                    case TetherMasterSM.EVENT_IFACE_SERVING_STATE_INACTIVE /* 327682 */:
                        TetherMasterSM.this.handleInterfaceServingStateInactive((TetherInterfaceStateMachine) message.obj);
                        if (TetherMasterSM.this.mNotifyList.isEmpty()) {
                            TetherMasterSM.this.turnOffMasterTetherSettings();
                            return true;
                        }
                        boolean previousUpstreamWanted2 = updateUpstreamWanted();
                        if (previousUpstreamWanted2 && !this.mUpstreamWanted) {
                            Tethering.this.mUpstreamNetworkMonitor.releaseMobileNetworkRequest();
                            return true;
                        }
                        return true;
                    case TetherMasterSM.CMD_UPSTREAM_CHANGED /* 327683 */:
                        updateUpstreamWanted();
                        if (!this.mUpstreamWanted) {
                            return true;
                        }
                        TetherMasterSM.this.chooseUpstreamType(true);
                        this.mTryCell = false;
                        return true;
                    case TetherMasterSM.CMD_RETRY_UPSTREAM /* 327684 */:
                        updateUpstreamWanted();
                        if (!this.mUpstreamWanted) {
                            return true;
                        }
                        TetherMasterSM.this.chooseUpstreamType(this.mTryCell);
                        this.mTryCell = !this.mTryCell;
                        return true;
                    case TetherMasterSM.EVENT_UPSTREAM_CALLBACK /* 327685 */:
                        updateUpstreamWanted();
                        if (this.mUpstreamWanted) {
                            TetherMasterSM.this.handleUpstreamNetworkMonitorCallback(message.arg1, message.obj);
                            return true;
                        }
                        return true;
                    case TetherMasterSM.CMD_CLEAR_ERROR /* 327686 */:
                    default:
                        return false;
                    case TetherMasterSM.EVENT_IFACE_UPDATE_LINKPROPERTIES /* 327687 */:
                        LinkProperties newLp = (LinkProperties) message.obj;
                        if (message.arg1 == 2) {
                            TetherMasterSM.this.mOffload.updateDownstreamLinkProperties(newLp);
                            return true;
                        }
                        TetherMasterSM.this.mOffload.excludeDownstreamInterface(newLp.getInterfaceName());
                        return true;
                }
            }
        }

        /* loaded from: classes.dex */
        class ErrorState extends State {
            private int mErrorNotification;

            ErrorState() {
            }

            public boolean processMessage(Message message) {
                int i = message.what;
                if (i == TetherMasterSM.EVENT_IFACE_SERVING_STATE_ACTIVE) {
                    TetherInterfaceStateMachine who = (TetherInterfaceStateMachine) message.obj;
                    who.sendMessage(this.mErrorNotification);
                    return true;
                } else if (i == TetherMasterSM.CMD_CLEAR_ERROR) {
                    this.mErrorNotification = 0;
                    TetherMasterSM.this.transitionTo(TetherMasterSM.this.mInitialState);
                    return true;
                } else {
                    return false;
                }
            }

            void notify(int msgType) {
                this.mErrorNotification = msgType;
                Iterator it = TetherMasterSM.this.mNotifyList.iterator();
                while (it.hasNext()) {
                    TetherInterfaceStateMachine sm = (TetherInterfaceStateMachine) it.next();
                    sm.sendMessage(msgType);
                }
            }
        }

        /* loaded from: classes.dex */
        class SetIpForwardingEnabledErrorState extends ErrorState {
            SetIpForwardingEnabledErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in setIpForwardingEnabled");
                notify(TetherInterfaceStateMachine.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        /* loaded from: classes.dex */
        class SetIpForwardingDisabledErrorState extends ErrorState {
            SetIpForwardingDisabledErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in setIpForwardingDisabled");
                notify(TetherInterfaceStateMachine.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        /* loaded from: classes.dex */
        class StartTetheringErrorState extends ErrorState {
            StartTetheringErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in startTethering");
                notify(TetherInterfaceStateMachine.CMD_START_TETHERING_ERROR);
                try {
                    Tethering.this.mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {
                }
            }
        }

        /* loaded from: classes.dex */
        class StopTetheringErrorState extends ErrorState {
            StopTetheringErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in stopTethering");
                notify(TetherInterfaceStateMachine.CMD_STOP_TETHERING_ERROR);
                try {
                    Tethering.this.mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {
                }
            }
        }

        /* loaded from: classes.dex */
        class SetDnsForwardersErrorState extends ErrorState {
            SetDnsForwardersErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in setDnsForwarders");
                notify(TetherInterfaceStateMachine.CMD_SET_DNS_FORWARDERS_ERROR);
                try {
                    Tethering.this.mNMService.stopTethering();
                } catch (Exception e) {
                }
                try {
                    Tethering.this.mNMService.setIpForwardingEnabled(false);
                } catch (Exception e2) {
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes.dex */
        public class OffloadWrapper {
            OffloadWrapper() {
            }

            public void start() {
                Tethering.this.mOffloadController.start();
                sendOffloadExemptPrefixes();
            }

            public void stop() {
                Tethering.this.mOffloadController.stop();
            }

            public void updateUpstreamNetworkState(NetworkState ns) {
                Tethering.this.mOffloadController.setUpstreamLinkProperties(ns != null ? ns.linkProperties : null);
            }

            public void updateDownstreamLinkProperties(LinkProperties newLp) {
                sendOffloadExemptPrefixes();
                Tethering.this.mOffloadController.notifyDownstreamLinkProperties(newLp);
            }

            public void excludeDownstreamInterface(String ifname) {
                sendOffloadExemptPrefixes();
                Tethering.this.mOffloadController.removeDownstreamInterface(ifname);
            }

            public void sendOffloadExemptPrefixes() {
                sendOffloadExemptPrefixes(Tethering.this.mUpstreamNetworkMonitor.getLocalPrefixes());
            }

            public void sendOffloadExemptPrefixes(Set<IpPrefix> localPrefixes) {
                PrefixUtils.addNonForwardablePrefixes(localPrefixes);
                localPrefixes.add(PrefixUtils.DEFAULT_WIFI_P2P_PREFIX);
                Iterator it = TetherMasterSM.this.mNotifyList.iterator();
                while (it.hasNext()) {
                    TetherInterfaceStateMachine tism = (TetherInterfaceStateMachine) it.next();
                    LinkProperties lp = tism.linkProperties();
                    switch (tism.servingMode()) {
                        case 2:
                            for (LinkAddress addr : lp.getAllLinkAddresses()) {
                                InetAddress ip = addr.getAddress();
                                if (!ip.isLinkLocalAddress()) {
                                    localPrefixes.add(PrefixUtils.ipAddressAsPrefix(ip));
                                }
                            }
                            break;
                        case 3:
                            localPrefixes.addAll(PrefixUtils.localPrefixesFrom(lp));
                            break;
                    }
                }
                Tethering.this.mOffloadController.setLocalPrefixes(localPrefixes);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        PrintWriter indentingPrintWriter = new IndentingPrintWriter(writer, "  ");
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, indentingPrintWriter)) {
            indentingPrintWriter.println("Tethering:");
            indentingPrintWriter.increaseIndent();
            indentingPrintWriter.println("Configuration:");
            indentingPrintWriter.increaseIndent();
            TetheringConfiguration cfg = this.mConfig;
            cfg.dump(indentingPrintWriter);
            indentingPrintWriter.decreaseIndent();
            synchronized (this.mPublicSync) {
                indentingPrintWriter.println("Tether state:");
                indentingPrintWriter.increaseIndent();
                for (int i = 0; i < this.mTetherStates.size(); i++) {
                    String iface = this.mTetherStates.keyAt(i);
                    TetherState tetherState = this.mTetherStates.valueAt(i);
                    indentingPrintWriter.print(iface + " - ");
                    switch (tetherState.lastState) {
                        case 0:
                            indentingPrintWriter.print("UnavailableState");
                            break;
                        case 1:
                            indentingPrintWriter.print("AvailableState");
                            break;
                        case 2:
                            indentingPrintWriter.print("TetheredState");
                            break;
                        case 3:
                            indentingPrintWriter.print("LocalHotspotState");
                            break;
                        default:
                            indentingPrintWriter.print("UnknownState");
                            break;
                    }
                    indentingPrintWriter.println(" - lastError = " + tetherState.lastError);
                }
                indentingPrintWriter.println("Upstream wanted: " + upstreamWanted());
                indentingPrintWriter.println("Current upstream interface(s): " + this.mCurrentUpstreamIfaceSet);
                indentingPrintWriter.decreaseIndent();
            }
            indentingPrintWriter.println("Hardware offload:");
            indentingPrintWriter.increaseIndent();
            this.mOffloadController.dump(indentingPrintWriter);
            indentingPrintWriter.decreaseIndent();
            indentingPrintWriter.println("Log:");
            indentingPrintWriter.increaseIndent();
            if (argsContain(args, ConnectivityService.SHORT_ARG)) {
                indentingPrintWriter.println("<log removed for brevity>");
            } else {
                this.mLog.dump(fd, indentingPrintWriter, args);
            }
            indentingPrintWriter.decreaseIndent();
            indentingPrintWriter.decreaseIndent();
        }
    }

    private static boolean argsContain(String[] args, String target) {
        for (String arg : args) {
            if (target.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private IControlsTethering makeControlCallback(final String ifname) {
        return new IControlsTethering() { // from class: com.android.server.connectivity.Tethering.3
            @Override // com.android.server.connectivity.tethering.IControlsTethering
            public void updateInterfaceState(TetherInterfaceStateMachine who, int state, int lastError) {
                Tethering.this.notifyInterfaceStateChange(ifname, who, state, lastError);
            }

            @Override // com.android.server.connectivity.tethering.IControlsTethering
            public void updateLinkProperties(TetherInterfaceStateMachine who, LinkProperties newLp) {
                Tethering.this.notifyLinkPropertiesChanged(ifname, who, newLp);
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyInterfaceStateChange(String iface, TetherInterfaceStateMachine who, int state, int error) {
        int which;
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null && tetherState.stateMachine.equals(who)) {
                tetherState.lastState = state;
                tetherState.lastError = error;
            }
        }
        boolean z = true;
        this.mLog.log(String.format("OBSERVED iface=%s state=%s error=%s", iface, Integer.valueOf(state), Integer.valueOf(error)));
        try {
            INetworkPolicyManager iNetworkPolicyManager = this.mPolicyManager;
            if (state != 2) {
                z = false;
            }
            iNetworkPolicyManager.onTetheringChanged(iface, z);
        } catch (RemoteException e) {
        }
        if (error == 5) {
            this.mTetherMasterSM.sendMessage(327686, who);
        }
        switch (state) {
            case 0:
            case 1:
                which = 327682;
                break;
            case 2:
            case 3:
                which = 327681;
                break;
            default:
                Log.wtf(TAG, "Unknown interface state: " + state);
                return;
        }
        this.mTetherMasterSM.sendMessage(which, state, 0, who);
        sendTetherStateChangedBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyLinkPropertiesChanged(String iface, TetherInterfaceStateMachine who, LinkProperties newLp) {
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null && tetherState.stateMachine.equals(who)) {
                int state = tetherState.lastState;
                this.mLog.log(String.format("OBSERVED LinkProperties update iface=%s state=%s lp=%s", iface, IControlsTethering.getStateString(state), newLp));
                this.mTetherMasterSM.sendMessage(327687, state, 0, newLp);
                return;
            }
            SharedLog sharedLog = this.mLog;
            sharedLog.log("got notification from stale iface " + iface);
        }
    }

    private void maybeTrackNewInterfaceLocked(String iface) {
        int interfaceType = ifaceNameToType(iface);
        if (interfaceType == -1) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log(iface + " is not a tetherable iface, ignoring");
            return;
        }
        maybeTrackNewInterfaceLocked(iface, interfaceType);
    }

    private void maybeTrackNewInterfaceLocked(String iface, int interfaceType) {
        if (this.mTetherStates.containsKey(iface)) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log("active iface (" + iface + ") reported as added, ignoring");
            return;
        }
        SharedLog sharedLog2 = this.mLog;
        sharedLog2.log("adding TetheringInterfaceStateMachine for: " + iface);
        TetherState tetherState = new TetherState(new TetherInterfaceStateMachine(iface, this.mLooper, interfaceType, this.mLog, this.mNMService, this.mStatsService, makeControlCallback(iface), this.mDeps));
        this.mTetherStates.put(iface, tetherState);
        tetherState.stateMachine.start();
    }

    private void stopTrackingInterfaceLocked(String iface) {
        TetherState tetherState = this.mTetherStates.get(iface);
        if (tetherState == null) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log("attempting to remove unknown iface (" + iface + "), ignoring");
            return;
        }
        tetherState.stateMachine.stop();
        SharedLog sharedLog2 = this.mLog;
        sharedLog2.log("removing TetheringInterfaceStateMachine for: " + iface);
        this.mTetherStates.remove(iface);
    }

    private static String getIPv4DefaultRouteInterface(NetworkState ns) {
        if (ns == null) {
            return null;
        }
        return getInterfaceForDestination(ns.linkProperties, Inet4Address.ANY);
    }

    private static String getIPv6DefaultRouteInterface(NetworkState ns) {
        if (ns == null || ns.networkCapabilities == null || !ns.networkCapabilities.hasTransport(0)) {
            return null;
        }
        return getInterfaceForDestination(ns.linkProperties, Inet6Address.ANY);
    }

    private static String getInterfaceForDestination(LinkProperties lp, InetAddress dst) {
        RouteInfo ri;
        if (lp != null) {
            ri = RouteInfo.selectBestRoute(lp.getAllRoutes(), dst);
        } else {
            ri = null;
        }
        if (ri != null) {
            return ri.getInterface();
        }
        return null;
    }

    private static String[] copy(String[] strarray) {
        return (String[]) Arrays.copyOf(strarray, strarray.length);
    }
}
