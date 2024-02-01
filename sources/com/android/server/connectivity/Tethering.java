package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.ITetheringEventCallback;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.ip.IpServer;
import android.net.util.InterfaceSet;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.net.util.VersionedBroadcastListener;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
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
import com.android.server.connectivity.tethering.EntitlementManager;
import com.android.server.connectivity.tethering.IPv6TetheringCoordinator;
import com.android.server.connectivity.tethering.OffloadController;
import com.android.server.connectivity.tethering.TetheringConfiguration;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.connectivity.tethering.TetheringInterfaceUtils;
import com.android.server.connectivity.tethering.UpstreamNetworkMonitor;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.pm.DumpState;
import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private static final boolean VDBG = false;
    private final VersionedBroadcastListener mCarrierConfigChange;
    private volatile TetheringConfiguration mConfig;
    private final Context mContext;
    private InterfaceSet mCurrentUpstreamIfaceSet;
    private final TetheringDependencies mDeps;
    private final EntitlementManager mEntitlementMgr;
    private final HashSet<IpServer> mForwardedDownstreams;
    private final Handler mHandler;
    private int mLastNotificationId;
    private final Looper mLooper;
    private final INetworkManagementService mNMService;
    private final OffloadController mOffloadController;
    private WifiP2pManager.Channel mP2pChannel;
    private final PhoneStateListener mPhoneStateListener;
    private final INetworkPolicyManager mPolicyManager;
    private final Object mPublicSync;
    private boolean mRndisEnabled;
    private final BroadcastReceiver mStateReceiver;
    private final INetworkStatsService mStatsService;
    private final StateMachine mTetherMasterSM;
    private final ArrayMap<String, TetherState> mTetherStates;
    private Network mTetherUpstream;
    private Notification.Builder mTetheredNotificationBuilder;
    private final UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    private boolean mWifiTetherRequested;
    private static final String TAG = Tethering.class.getSimpleName();
    private static final Class[] messageClasses = {Tethering.class, TetherMasterSM.class, IpServer.class};
    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(messageClasses);
    private final SharedLog mLog = new SharedLog(TAG);
    private final RemoteCallbackList<ITetheringEventCallback> mTetheringEventCallbacks = new RemoteCallbackList<>();
    private int mActiveDataSubId = -1;
    private boolean mP2pTethered = false;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class TetherState {
        public final IpServer ipServer;
        public int lastState = 1;
        public int lastError = 0;

        public TetherState(IpServer ipServer) {
            this.ipServer = ipServer;
        }

        public boolean isCurrentlyServing() {
            int i = this.lastState;
            if (i == 2 || i == 3) {
                return true;
            }
            return false;
        }
    }

    public Tethering(Context context, INetworkManagementService nmService, INetworkStatsService statsService, INetworkPolicyManager policyManager, Looper looper, MockableSystemProperties systemProperties, TetheringDependencies deps) {
        this.mLog.mark("constructed");
        this.mContext = context;
        this.mNMService = nmService;
        this.mStatsService = statsService;
        this.mPolicyManager = policyManager;
        this.mLooper = looper;
        this.mDeps = deps;
        this.mPublicSync = new Object();
        this.mTetherStates = new ArrayMap<>();
        this.mTetherMasterSM = new TetherMasterSM("TetherMaster", this.mLooper, deps);
        this.mTetherMasterSM.start();
        this.mHandler = this.mTetherMasterSM.getHandler();
        Handler handler = this.mHandler;
        this.mOffloadController = new OffloadController(handler, this.mDeps.getOffloadHardwareInterface(handler, this.mLog), this.mContext.getContentResolver(), this.mNMService, this.mLog);
        this.mUpstreamNetworkMonitor = deps.getUpstreamNetworkMonitor(this.mContext, this.mTetherMasterSM, this.mLog, 327685);
        this.mForwardedDownstreams = new HashSet<>();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.telephony.action.CARRIER_CONFIG_CHANGED");
        this.mEntitlementMgr = this.mDeps.getEntitlementManager(this.mContext, this.mTetherMasterSM, this.mLog, 327688, systemProperties);
        this.mEntitlementMgr.setOnUiEntitlementFailedListener(new EntitlementManager.OnUiEntitlementFailedListener() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$3zIH-fISJxjng2YhMI1EBDdSKsk
            @Override // com.android.server.connectivity.tethering.EntitlementManager.OnUiEntitlementFailedListener
            public final void onUiEntitlementFailed(int i) {
                Tethering.this.lambda$new$0$Tethering(i);
            }
        });
        this.mEntitlementMgr.setTetheringConfigurationFetcher(new EntitlementManager.TetheringConfigurationFetcher() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$n3LtFaPEJryBHWNNaGBvLgh7QQk
            @Override // com.android.server.connectivity.tethering.EntitlementManager.TetheringConfigurationFetcher
            public final TetheringConfiguration fetchTetheringConfiguration() {
                return Tethering.this.lambda$new$1$Tethering();
            }
        });
        this.mCarrierConfigChange = new VersionedBroadcastListener("CarrierConfigChangeListener", this.mContext, this.mHandler, filter, new Consumer() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$a_wqxo60onQxTR27G2Ub5703PoY
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                Tethering.this.lambda$new$2$Tethering((Intent) obj);
            }
        });
        this.mPhoneStateListener = new PhoneStateListener(this.mLooper) { // from class: com.android.server.connectivity.Tethering.1
            @Override // android.telephony.PhoneStateListener
            public void onActiveDataSubscriptionIdChanged(int subId) {
                SharedLog sharedLog = Tethering.this.mLog;
                sharedLog.log("OBSERVED active data subscription change, from " + Tethering.this.mActiveDataSubId + " to " + subId);
                if (subId == Tethering.this.mActiveDataSubId) {
                    return;
                }
                Tethering.this.mActiveDataSubId = subId;
                Tethering.this.updateConfiguration();
                if (Tethering.this.mEntitlementMgr.getCarrierConfig(Tethering.this.mConfig) != null) {
                    Tethering.this.mEntitlementMgr.reevaluateSimCardProvisioning(Tethering.this.mConfig);
                } else {
                    Tethering.this.mLog.log("IGNORED reevaluate provisioning due to no carrier config loaded");
                }
            }
        };
        this.mStateReceiver = new StateReceiver();
        updateConfiguration();
        startStateMachineUpdaters(this.mHandler);
    }

    public /* synthetic */ void lambda$new$0$Tethering(int downstream) {
        this.mLog.log("OBSERVED UiEnitlementFailed");
        stopTethering(downstream);
    }

    public /* synthetic */ TetheringConfiguration lambda$new$1$Tethering() {
        return this.mConfig;
    }

    public /* synthetic */ void lambda$new$2$Tethering(Intent ignored) {
        this.mLog.log("OBSERVED carrier config change");
        updateConfiguration();
        this.mEntitlementMgr.reevaluateSimCardProvisioning(this.mConfig);
    }

    private void startStateMachineUpdaters(Handler handler) {
        this.mCarrierConfigChange.startListening();
        TelephonyManager.from(this.mContext).listen(this.mPhoneStateListener, DumpState.DUMP_CHANGES);
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_STATE");
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        filter.addAction("android.net.wifi.p2p.CONNECTION_STATE_CHANGE");
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

    private WifiP2pManager getWifiP2pManager() {
        return (WifiP2pManager) this.mContext.getSystemService("wifip2p");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateConfiguration() {
        this.mConfig = this.mDeps.generateTetheringConfiguration(this.mContext, this.mLog, this.mActiveDataSubId);
        this.mUpstreamNetworkMonitor.updateMobileRequiresDun(this.mConfig.isDunRequired);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeDunSettingChanged() {
        boolean isDunRequired = TetheringConfiguration.checkDunRequired(this.mContext, this.mActiveDataSubId);
        if (isDunRequired == this.mConfig.isDunRequired) {
            return;
        }
        updateConfiguration();
    }

    public void interfaceStatusChanged(String iface, boolean up) {
        synchronized (this.mPublicSync) {
            if (up) {
                maybeTrackNewInterfaceLocked(iface);
            } else if (ifaceNameToType(iface) == 2) {
                stopTrackingInterfaceLocked(iface);
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
        String str = TAG;
        Log.d(str, "interfaceAdded " + iface);
        synchronized (this.mPublicSync) {
            maybeTrackNewInterfaceLocked(iface);
        }
    }

    public void interfaceRemoved(String iface) {
        String str = TAG;
        Log.d(str, "interfaceRemoved " + iface);
        synchronized (this.mPublicSync) {
            stopTrackingInterfaceLocked(iface);
        }
    }

    public void startTethering(int type, ResultReceiver receiver, boolean showProvisioningUi) {
        this.mEntitlementMgr.startProvisioningIfNeeded(type, showProvisioningUi);
        enableTetheringInternal(type, true, receiver);
    }

    public void stopTethering(int type) {
        enableTetheringInternal(type, false, null);
        this.mEntitlementMgr.stopProvisioningIfNeeded(type);
    }

    private void enableTetheringInternal(int type, boolean enable, ResultReceiver receiver) {
        if (type == 0) {
            int result = setWifiTethering(enable);
            sendTetherResult(receiver, result);
        } else if (type == 1) {
            int result2 = setUsbTethering(enable);
            sendTetherResult(receiver, result2);
        } else if (type == 2) {
            setBluetoothTethering(enable, receiver);
        } else if (type == 3) {
            int result3 = setP2pTethering(enable);
            sendTetherResult(receiver, result3);
        } else {
            Log.w(TAG, "Invalid tether type.");
            sendTetherResult(receiver, 1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendTetherResult(ResultReceiver receiver, int result) {
        if (receiver != null) {
            receiver.send(result, null);
        }
    }

    private int setWifiTethering(boolean enable) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mPublicSync) {
                WifiManager mgr = getWifiManager();
                if (mgr == null) {
                    this.mLog.e("setWifiTethering: failed to get WifiManager!");
                    return 2;
                } else if (!(enable && mgr.startSoftAp(null)) && (enable || !mgr.stopSoftAp())) {
                    Binder.restoreCallingIdentity(ident);
                    return 5;
                } else {
                    this.mWifiTetherRequested = enable;
                    return 0;
                }
            }
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
        adapter.getProfileProxy(this.mContext, new BluetoothProfile.ServiceListener() { // from class: com.android.server.connectivity.Tethering.2
            @Override // android.bluetooth.BluetoothProfile.ServiceListener
            public void onServiceDisconnected(int profile) {
            }

            @Override // android.bluetooth.BluetoothProfile.ServiceListener
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                int result;
                long identityToken = Binder.clearCallingIdentity();
                try {
                    ((BluetoothPan) proxy).setBluetoothTethering(enable);
                    Binder.restoreCallingIdentity(identityToken);
                    if (((BluetoothPan) proxy).isTetheringOn() == enable) {
                        result = 0;
                    } else {
                        result = 5;
                    }
                    Tethering.this.sendTetherResult(receiver, result);
                    adapter.closeProfileProxy(5, proxy);
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(identityToken);
                    throw th;
                }
            }
        }, 5);
    }

    private int setP2pTethering(boolean enable) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mPublicSync) {
                WifiP2pManager mgr = getWifiP2pManager();
                if (mgr == null) {
                    String str = TAG;
                    Log.e(str, "SetP2pTethering(" + enable + ") failed to get WifiP2pManager!");
                    return 2;
                } else if (enable || this.mP2pChannel != null) {
                    if (enable && this.mP2pChannel == null) {
                        this.mP2pChannel = mgr.initialize(this.mContext, this.mLooper, null);
                    }
                    if (!mgr.setP2pTetherEnabled(this.mP2pChannel, enable)) {
                        Binder.restoreCallingIdentity(ident);
                        String str2 = TAG;
                        Log.w(str2, "setP2pTethering(" + enable + ") failed");
                        return 5;
                    }
                    String str3 = TAG;
                    Log.e(str3, "SetP2pTethering(" + enable + ") success");
                    if (!enable) {
                        this.mP2pChannel = null;
                    }
                    return 0;
                } else {
                    return 0;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public int tether(String iface) {
        return tether(iface, 2);
    }

    private int tether(String iface, int requestedState) {
        String str = TAG;
        Log.d(str, "Tethering " + iface);
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null) {
                if (tetherState.lastState != 1) {
                    String str2 = TAG;
                    Log.e(str2, "Tried to Tether an unavailable iface: " + iface + ", ignoring");
                    return 4;
                }
                tetherState.ipServer.sendMessage(IpServer.CMD_TETHER_REQUESTED, requestedState);
                return 0;
            }
            String str3 = TAG;
            Log.e(str3, "Tried to Tether an unknown iface: " + iface + ", ignoring");
            return 1;
        }
    }

    public int untether(String iface) {
        String str = TAG;
        Log.d(str, "Untethering " + iface);
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState == null) {
                String str2 = TAG;
                Log.e(str2, "Tried to Untether an unknown iface :" + iface + ", ignoring");
                return 1;
            } else if (!tetherState.isCurrentlyServing()) {
                String str3 = TAG;
                Log.e(str3, "Tried to untether an inactive iface :" + iface + ", ignoring");
                return 4;
            } else {
                tetherState.ipServer.sendMessage(IpServer.CMD_TETHER_UNREQUESTED);
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
        boolean p2pTethered;
        boolean p2pTethered2;
        boolean bluetoothTethered;
        if (this.mDeps.isTetheringSupported()) {
            ArrayList<String> availableList = new ArrayList<>();
            ArrayList<String> tetherList = new ArrayList<>();
            ArrayList<String> localOnlyList = new ArrayList<>();
            ArrayList<String> erroredList = new ArrayList<>();
            boolean usbTethered = false;
            TetheringConfiguration cfg = this.mConfig;
            synchronized (this.mPublicSync) {
                p2pTethered = false;
                p2pTethered2 = false;
                bluetoothTethered = false;
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
                            bluetoothTethered = true;
                        } else if (cfg.isBluetooth(iface)) {
                            p2pTethered2 = true;
                        } else {
                            IpServer ipServer = this.mTetherStates.valueAt(i).ipServer;
                            if (ipServer.interfaceType() == 3) {
                                p2pTethered = true;
                            }
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
            Log.d(TAG, String.format("sendTetherStateChangedBroadcast %s=[%s] %s=[%s] %s=[%s] %s=[%s]", "avail", TextUtils.join(",", availableList), "local_only", TextUtils.join(",", localOnlyList), "tether", TextUtils.join(",", tetherList), "error", TextUtils.join(",", erroredList)));
            if (usbTethered) {
                if (!bluetoothTethered && !p2pTethered2 && !p2pTethered) {
                    showTetheredNotification(15);
                } else {
                    showTetheredNotification(14);
                }
            } else if (bluetoothTethered) {
                if (p2pTethered2 || p2pTethered) {
                    showTetheredNotification(14);
                } else {
                    clearTetheredNotification();
                }
            } else if (p2pTethered2) {
                showTetheredNotification(16);
            } else if (p2pTethered) {
                showTetheredNotification(14);
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
        if (id == 15) {
            icon = 17303590;
        } else if (id == 16) {
            icon = 17303588;
        } else {
            icon = 17303589;
        }
        int i = this.mLastNotificationId;
        if (i != 0) {
            if (i == icon) {
                return;
            }
            notificationManager.cancelAsUser(null, i, UserHandle.ALL);
            this.mLastNotificationId = 0;
        }
        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        intent.setFlags(1073741824);
        PendingIntent pi = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 0, null, UserHandle.CURRENT);
        Resources r = Resources.getSystem();
        if (tetheringOn) {
            title = r.getText(17041141);
            message = r.getText(17041140);
        } else {
            title = r.getText(17039877);
            message = r.getText(17039876);
        }
        if (this.mTetheredNotificationBuilder == null) {
            this.mTetheredNotificationBuilder = new Notification.Builder(this.mContext, SystemNotificationChannels.NETWORK_STATUS);
            this.mTetheredNotificationBuilder.setWhen(0L).setOngoing(true).setColor(this.mContext.getColor(17170460)).setVisibility(1).setCategory("status");
        }
        this.mTetheredNotificationBuilder.setSmallIcon(icon).setContentTitle(title).setContentText(message).setContentIntent(pi);
        this.mLastNotificationId = id;
        notificationManager.notifyAsUser(null, this.mLastNotificationId, this.mTetheredNotificationBuilder.buildInto(new Notification()), UserHandle.ALL);
    }

    @VisibleForTesting
    protected void clearTetheredNotification() {
        int i;
        NotificationManager notificationManager = (NotificationManager) this.mContext.getSystemService("notification");
        if (notificationManager != null && (i = this.mLastNotificationId) != 0) {
            notificationManager.cancelAsUser(null, i, UserHandle.ALL);
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
            } else if (action.equals("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")) {
                handleWifiP2pAction(intent);
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
                            Tethering.this.mEntitlementMgr.stopProvisioningIfNeeded(1);
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
                try {
                    if (curState != 12) {
                        if (curState != 13) {
                            Tethering.this.disableWifiIpServingLocked(ifname, curState);
                        } else {
                            Tethering.this.enableWifiIpServingLocked(ifname, ipmode);
                        }
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }

        private void maybeReleaseInterfaceLocked(String ifname, int type) {
            TetherState ts;
            Log.e(Tethering.TAG, "Canceling tethering request - ifname=" + ifname + " type=" + type);
            if (!TextUtils.isEmpty(ifname) && (ts = (TetherState) Tethering.this.mTetherStates.get(ifname)) != null) {
                ts.ipServer.unwanted();
                return;
            }
            for (int i = 0; i < Tethering.this.mTetherStates.size(); i++) {
                IpServer ipServer = ((TetherState) Tethering.this.mTetherStates.valueAt(i)).ipServer;
                if (ipServer.interfaceType() == type) {
                    ipServer.unwanted();
                    return;
                }
            }
            String str = Tethering.TAG;
            StringBuilder sb = new StringBuilder();
            sb.append("Error disabling tethering request; ");
            sb.append(TextUtils.isEmpty(ifname) ? "no interface name specified" : "specified interface: " + ifname);
            Log.i(str, sb.toString());
        }

        private void handleWifiP2pAction(Intent intent) {
            String ifname = intent.getStringExtra("wifiP2pIface");
            WifiP2pInfo p2pInfo = (WifiP2pInfo) intent.getParcelableExtra("wifiP2pInfo");
            String str = Tethering.TAG;
            Log.e(str, "handleWifiP2pAction() ifname=" + ifname + " tetherable=" + p2pInfo.tetherable + " isGroupOwner=" + p2pInfo.isGroupOwner + " groupFormed=" + p2pInfo.groupFormed);
            synchronized (Tethering.this.mPublicSync) {
                if (!p2pInfo.groupFormed || !p2pInfo.isGroupOwner || !p2pInfo.tetherable) {
                    Tethering.this.mP2pTethered = false;
                    maybeReleaseInterfaceLocked(ifname, 3);
                } else if (TextUtils.isEmpty(ifname)) {
                } else {
                    if (Tethering.this.mP2pTethered) {
                        return;
                    }
                    Tethering.this.maybeTrackNewInterfaceLocked(ifname, 3);
                    Tethering.this.changeInterfaceState(ifname, 2);
                    Tethering.this.mP2pTethered = true;
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
                this.mWrapper.showTetheredNotification(17303589, false);
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
            ts.ipServer.unwanted();
            return;
        }
        for (int i = 0; i < this.mTetherStates.size(); i++) {
            IpServer ipServer = this.mTetherStates.valueAt(i).ipServer;
            if (ipServer.interfaceType() == 0) {
                ipServer.unwanted();
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
        if (wifiIpMode == 1) {
            ipServingMode = 2;
        } else if (wifiIpMode == 2) {
            ipServingMode = 3;
        } else {
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
        String str = TAG;
        Log.d(str, "tetherMatchingInterfaces(" + requestedState + ", " + interfaceType + ")");
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
                String str2 = TAG;
                Log.e(str2, "could not find iface of type " + interfaceType);
                return;
            }
            changeInterfaceState(chosenIface, requestedState);
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void changeInterfaceState(String ifname, int requestedState) {
        int result;
        if (requestedState == 0 || requestedState == 1) {
            result = untether(ifname);
        } else if (requestedState == 2 || requestedState == 3) {
            result = tether(ifname, requestedState);
        } else {
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
        boolean hasUpstreamConfiguration = !cfg.preferredUpstreamIfaceTypes.isEmpty() || cfg.chooseUpstreamAutomatically;
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
        if (usbManager == null) {
            this.mLog.e("setUsbTethering: failed to get UsbManager!");
            return 2;
        }
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
        return this.mConfig.legacyDhcpRanges;
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
        boolean z = true;
        if (this.mForwardedDownstreams.isEmpty()) {
            synchronized (this.mPublicSync) {
                if (!this.mWifiTetherRequested && !this.mP2pTethered) {
                    z = false;
                }
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
        static final int EVENT_UPSTREAM_PERMISSION_CHANGED = 327688;
        private static final int UPSTREAM_SETTLE_TIME_MS = 10000;
        private final IPv6TetheringCoordinator mIPv6TetheringCoordinator;
        private final State mInitialState;
        private final ArrayList<IpServer> mNotifyList;
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
                switch (message.what) {
                    case TetherMasterSM.EVENT_IFACE_SERVING_STATE_ACTIVE /* 327681 */:
                        IpServer who = (IpServer) message.obj;
                        String str = Tethering.TAG;
                        Log.d(str, "InitialState: Tether Mode requested by " + who);
                        TetherMasterSM.this.handleInterfaceServingStateActive(message.arg1, who);
                        TetherMasterSM tetherMasterSM = TetherMasterSM.this;
                        tetherMasterSM.transitionTo(tetherMasterSM.mTetherModeAliveState);
                        return true;
                    case TetherMasterSM.EVENT_IFACE_SERVING_STATE_INACTIVE /* 327682 */:
                        IpServer who2 = (IpServer) message.obj;
                        String str2 = Tethering.TAG;
                        Log.d(str2, "InitialState: Tether Mode unrequested by " + who2);
                        TetherMasterSM.this.handleInterfaceServingStateInactive(who2);
                        return true;
                    case TetherMasterSM.EVENT_IFACE_UPDATE_LINKPROPERTIES /* 327687 */:
                        return true;
                    default:
                        return false;
                }
            }
        }

        protected boolean turnOnMasterTetherSettings() {
            String[] dhcpRanges;
            TetheringConfiguration cfg = Tethering.this.mConfig;
            try {
                Tethering.this.mNMService.setIpForwardingEnabled(true);
                if (cfg.enableLegacyDhcpServer) {
                    dhcpRanges = cfg.legacyDhcpRanges;
                } else {
                    dhcpRanges = new String[0];
                }
                try {
                    Tethering.this.mNMService.startTethering(dhcpRanges);
                } catch (Exception e) {
                    try {
                        Tethering.this.mNMService.stopTethering();
                        Tethering.this.mNMService.startTethering(dhcpRanges);
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
            Tethering.this.maybeDunSettingChanged();
            String str = Tethering.TAG;
            Log.i(str, "chooseUpstreamType, mConfig = " + Tethering.this.mConfig);
            TetheringConfiguration config = Tethering.this.mConfig;
            NetworkState ns = config.chooseUpstreamAutomatically ? Tethering.this.mUpstreamNetworkMonitor.getCurrentPreferredUpstream() : Tethering.this.mUpstreamNetworkMonitor.selectPreferredUpstreamType(config.preferredUpstreamIfaceTypes);
            if (ns == null) {
                if (tryCell) {
                    Tethering.this.mUpstreamNetworkMonitor.registerMobileNetworkRequest();
                } else {
                    sendMessageDelayed(CMD_RETRY_UPSTREAM, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
                }
            }
            setUpstreamNetwork(ns);
            Network newUpstream = ns != null ? ns.network : null;
            if (Tethering.this.mTetherUpstream != newUpstream) {
                Tethering.this.mTetherUpstream = newUpstream;
                Tethering.this.mUpstreamNetworkMonitor.setCurrentUpstream(Tethering.this.mTetherUpstream);
                Tethering tethering = Tethering.this;
                tethering.reportUpstreamChanged(tethering.mTetherUpstream);
            }
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
            Iterator<IpServer> it = this.mNotifyList.iterator();
            while (it.hasNext()) {
                IpServer ipServer = it.next();
                ipServer.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED, ifaces);
            }
        }

        protected void handleNewUpstreamNetworkState(NetworkState ns) {
            this.mIPv6TetheringCoordinator.updateUpstreamNetworkState(ns);
            this.mOffload.updateUpstreamNetworkState(ns);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleInterfaceServingStateActive(int mode, IpServer who) {
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
                if (mode == 2) {
                    mgr.updateInterfaceIpState(iface, 1);
                } else if (mode != 3) {
                    String str = Tethering.TAG;
                    Log.wtf(str, "Unknown active serving mode: " + mode);
                } else {
                    mgr.updateInterfaceIpState(iface, 2);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleInterfaceServingStateInactive(IpServer who) {
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
                }
            } else if (arg1 == 1) {
                handleNewUpstreamNetworkState(ns);
            } else if (arg1 == 2) {
                chooseUpstreamType(false);
            } else if (arg1 != 3) {
                SharedLog sharedLog = Tethering.this.mLog;
                sharedLog.e("Unknown arg1 value: " + arg1);
            } else {
                handleNewUpstreamNetworkState(null);
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
                    Tethering.this.mUpstreamNetworkMonitor.startObserveAllNetworks();
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
                TetherMasterSM.this.notifyDownstreamsOfNewUpstreamIface(null);
                TetherMasterSM.this.handleNewUpstreamNetworkState(null);
                if (Tethering.this.mTetherUpstream != null) {
                    Tethering.this.mTetherUpstream = null;
                    Tethering.this.reportUpstreamChanged(null);
                }
            }

            private boolean updateUpstreamWanted() {
                boolean previousUpstreamWanted = this.mUpstreamWanted;
                this.mUpstreamWanted = Tethering.this.upstreamWanted();
                boolean z = this.mUpstreamWanted;
                if (z != previousUpstreamWanted) {
                    if (z) {
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
                        IpServer who = (IpServer) message.obj;
                        String str = Tethering.TAG;
                        Log.d(str, "TetherModeAliveState: Tether Mode requested by " + who);
                        TetherMasterSM.this.handleInterfaceServingStateActive(message.arg1, who);
                        who.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED, Tethering.this.mCurrentUpstreamIfaceSet);
                        boolean previousUpstreamWanted = updateUpstreamWanted();
                        if (previousUpstreamWanted || !this.mUpstreamWanted) {
                            return true;
                        }
                        TetherMasterSM.this.chooseUpstreamType(true);
                        return true;
                    case TetherMasterSM.EVENT_IFACE_SERVING_STATE_INACTIVE /* 327682 */:
                        IpServer who2 = (IpServer) message.obj;
                        String str2 = Tethering.TAG;
                        Log.d(str2, "TetherModeAliveState: Tether Mode unrequested by " + who2);
                        TetherMasterSM.this.handleInterfaceServingStateInactive(who2);
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
                    case TetherMasterSM.EVENT_UPSTREAM_PERMISSION_CHANGED /* 327688 */:
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
                    IpServer who = (IpServer) message.obj;
                    who.sendMessage(this.mErrorNotification);
                    return true;
                } else if (i == TetherMasterSM.CMD_CLEAR_ERROR) {
                    this.mErrorNotification = 0;
                    TetherMasterSM tetherMasterSM = TetherMasterSM.this;
                    tetherMasterSM.transitionTo(tetherMasterSM.mInitialState);
                    return true;
                } else {
                    return false;
                }
            }

            void notify(int msgType) {
                this.mErrorNotification = msgType;
                Iterator it = TetherMasterSM.this.mNotifyList.iterator();
                while (it.hasNext()) {
                    IpServer ipServer = (IpServer) it.next();
                    ipServer.sendMessage(msgType);
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
                notify(IpServer.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        /* loaded from: classes.dex */
        class SetIpForwardingDisabledErrorState extends ErrorState {
            SetIpForwardingDisabledErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in setIpForwardingDisabled");
                notify(IpServer.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        /* loaded from: classes.dex */
        class StartTetheringErrorState extends ErrorState {
            StartTetheringErrorState() {
                super();
            }

            public void enter() {
                Log.e(Tethering.TAG, "Error in startTethering");
                notify(IpServer.CMD_START_TETHERING_ERROR);
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
                notify(IpServer.CMD_STOP_TETHERING_ERROR);
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
                notify(IpServer.CMD_SET_DNS_FORWARDERS_ERROR);
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
                    IpServer ipServer = (IpServer) it.next();
                    LinkProperties lp = ipServer.linkProperties();
                    int servingMode = ipServer.servingMode();
                    if (servingMode != 0 && servingMode != 1) {
                        if (servingMode == 2) {
                            for (LinkAddress addr : lp.getAllLinkAddresses()) {
                                InetAddress ip = addr.getAddress();
                                if (!ip.isLinkLocalAddress()) {
                                    localPrefixes.add(PrefixUtils.ipAddressAsPrefix(ip));
                                }
                            }
                        } else if (servingMode == 3) {
                            localPrefixes.addAll(PrefixUtils.localPrefixesFrom(lp));
                        }
                    }
                }
                Tethering.this.mOffloadController.setLocalPrefixes(localPrefixes);
            }
        }
    }

    public void systemReady() {
        this.mUpstreamNetworkMonitor.startTrackDefaultNetwork(this.mDeps.getDefaultNetworkRequest(), this.mEntitlementMgr);
    }

    public void getLatestTetheringEntitlementResult(int type, ResultReceiver receiver, boolean showEntitlementUi) {
        if (receiver != null) {
            this.mEntitlementMgr.getLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
        }
    }

    public void registerTetheringEventCallback(final ITetheringEventCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$-5WeutLnGVKFlJUG298UCM4G4Wk
            @Override // java.lang.Runnable
            public final void run() {
                Tethering.this.lambda$registerTetheringEventCallback$3$Tethering(callback);
            }
        });
    }

    public /* synthetic */ void lambda$registerTetheringEventCallback$3$Tethering(ITetheringEventCallback callback) {
        try {
            callback.onUpstreamChanged(this.mTetherUpstream);
        } catch (RemoteException e) {
        }
        this.mTetheringEventCallbacks.register(callback);
    }

    public void unregisterTetheringEventCallback(final ITetheringEventCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.connectivity.-$$Lambda$Tethering$ejzAj9HzzUH3vxPx7BPSoYluGzM
            @Override // java.lang.Runnable
            public final void run() {
                Tethering.this.lambda$unregisterTetheringEventCallback$4$Tethering(callback);
            }
        });
    }

    public /* synthetic */ void lambda$unregisterTetheringEventCallback$4$Tethering(ITetheringEventCallback callback) {
        this.mTetheringEventCallbacks.unregister(callback);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reportUpstreamChanged(Network network) {
        int length = this.mTetheringEventCallbacks.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                this.mTetheringEventCallbacks.getBroadcastItem(i).onUpstreamChanged(network);
            } catch (RemoteException e) {
            } catch (Throwable th) {
                this.mTetheringEventCallbacks.finishBroadcast();
                throw th;
            }
        }
        this.mTetheringEventCallbacks.finishBroadcast();
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
            indentingPrintWriter.println("Entitlement:");
            indentingPrintWriter.increaseIndent();
            this.mEntitlementMgr.dump(indentingPrintWriter);
            indentingPrintWriter.decreaseIndent();
            synchronized (this.mPublicSync) {
                indentingPrintWriter.println("Tether state:");
                indentingPrintWriter.increaseIndent();
                for (int i = 0; i < this.mTetherStates.size(); i++) {
                    String iface = this.mTetherStates.keyAt(i);
                    TetherState tetherState = this.mTetherStates.valueAt(i);
                    indentingPrintWriter.print(iface + " - ");
                    int i2 = tetherState.lastState;
                    if (i2 == 0) {
                        indentingPrintWriter.print("UnavailableState");
                    } else if (i2 == 1) {
                        indentingPrintWriter.print("AvailableState");
                    } else if (i2 == 2) {
                        indentingPrintWriter.print("TetheredState");
                    } else if (i2 == 3) {
                        indentingPrintWriter.print("LocalHotspotState");
                    } else {
                        indentingPrintWriter.print("UnknownState");
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

    private IpServer.Callback makeControlCallback() {
        return new IpServer.Callback() { // from class: com.android.server.connectivity.Tethering.3
            @Override // android.net.ip.IpServer.Callback
            public void updateInterfaceState(IpServer who, int state, int lastError) {
                Tethering.this.notifyInterfaceStateChange(who, state, lastError);
            }

            @Override // android.net.ip.IpServer.Callback
            public void updateLinkProperties(IpServer who, LinkProperties newLp) {
                Tethering.this.notifyLinkPropertiesChanged(who, newLp);
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyInterfaceStateChange(IpServer who, int state, int error) {
        int which;
        String iface = who.interfaceName();
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null && tetherState.ipServer.equals(who)) {
                tetherState.lastState = state;
                tetherState.lastError = error;
            } else {
                String str = TAG;
                Log.d(str, "got notification from stale iface " + iface);
            }
        }
        Log.d(TAG, String.format("OBSERVED iface=%s state=%s error=%s", iface, Integer.valueOf(state), Integer.valueOf(error)));
        try {
            this.mPolicyManager.onTetheringChanged(iface, state == 2);
        } catch (RemoteException e) {
        }
        if (error == 5) {
            this.mTetherMasterSM.sendMessage(327686, who);
        }
        if (state == 0 || state == 1) {
            which = 327682;
        } else if (state == 2 || state == 3) {
            which = 327681;
        } else {
            String str2 = TAG;
            Log.wtf(str2, "Unknown interface state: " + state);
            return;
        }
        this.mTetherMasterSM.sendMessage(which, state, 0, who);
        sendTetherStateChangedBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyLinkPropertiesChanged(IpServer who, LinkProperties newLp) {
        String iface = who.interfaceName();
        synchronized (this.mPublicSync) {
            TetherState tetherState = this.mTetherStates.get(iface);
            if (tetherState != null && tetherState.ipServer.equals(who)) {
                int state = tetherState.lastState;
                Log.d(TAG, String.format("OBSERVED LinkProperties update iface=%s state=%s lp=%s", iface, IpServer.getStateString(state), newLp));
                this.mTetherMasterSM.sendMessage(327687, state, 0, newLp);
                return;
            }
            String str = TAG;
            Log.d(str, "got notification from stale iface " + iface);
        }
    }

    private void maybeTrackNewInterfaceLocked(String iface) {
        int interfaceType = ifaceNameToType(iface);
        if (interfaceType == -1) {
            String str = TAG;
            Log.d(str, iface + " is not a tetherable iface, ignoring");
            return;
        }
        maybeTrackNewInterfaceLocked(iface, interfaceType);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeTrackNewInterfaceLocked(String iface, int interfaceType) {
        if (this.mTetherStates.containsKey(iface)) {
            String str = TAG;
            Log.d(str, "active iface (" + iface + ") reported as added, ignoring");
            return;
        }
        String str2 = TAG;
        Log.d(str2, "adding TetheringInterfaceStateMachine for: " + iface);
        TetherState tetherState = new TetherState(new IpServer(iface, this.mLooper, interfaceType, this.mLog, this.mNMService, this.mStatsService, makeControlCallback(), this.mConfig.enableLegacyDhcpServer, this.mDeps.getIpServerDependencies()));
        this.mTetherStates.put(iface, tetherState);
        tetherState.ipServer.start();
    }

    private void stopTrackingInterfaceLocked(String iface) {
        TetherState tetherState = this.mTetherStates.get(iface);
        if (tetherState == null) {
            String str = TAG;
            Log.d(str, "attempting to remove unknown iface (" + iface + "), ignoring");
            return;
        }
        tetherState.ipServer.stop();
        String str2 = TAG;
        Log.d(str2, "removing TetheringInterfaceStateMachine for: " + iface);
        this.mTetherStates.remove(iface);
    }

    private static String[] copy(String[] strarray) {
        return (String[]) Arrays.copyOf(strarray, strarray.length);
    }
}
