package com.android.server;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkPolicyManager;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.NetworkWatchlistManager;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.Uri;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.util.MultinetworkPolicyTracker;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.WakeupMessage;
import com.android.server.am.BatteryStatsService;
import com.android.server.audio.AudioService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.DnsManager;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.KeepaliveTracker;
import com.android.server.connectivity.LingerMonitor;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.MultipathPolicyTracker;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkDiagnostics;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.connectivity.NetworkNotificationManager;
import com.android.server.connectivity.PacManager;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.net.BaseNetdEventCallback;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.DumpState;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.utils.PriorityDump;
import com.google.android.collect.Lists;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.input.xpInputActionHandler;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
/* loaded from: classes.dex */
public class ConnectivityService extends IConnectivityManager.Stub implements PendingIntent.OnFinished {
    private static final String ATTR_MCC = "mcc";
    private static final String ATTR_MNC = "mnc";
    private static final boolean DBG = true;
    private static final int DEFAULT_LINGER_DELAY_MS = 30000;
    private static final String DEFAULT_TCP_BUFFER_SIZES = "4096,87380,110208,4096,16384,110208";
    private static final String DEFAULT_TCP_RWND_KEY = "net.tcp.default_init_rwnd";
    public static final String DIAG_ARG = "--diag";
    private static final int DISABLED = 0;
    private static final int ENABLED = 1;
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY = 9;
    private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED = 2;
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK = 8;
    private static final int EVENT_CONFIGURE_MOBILE_DATA_ALWAYS_ON = 30;
    private static final int EVENT_EXPIRE_NET_TRANSITION_WAKELOCK = 24;
    private static final int EVENT_PRIVATE_DNS_SETTINGS_CHANGED = 37;
    private static final int EVENT_PRIVATE_DNS_VALIDATION_UPDATE = 38;
    private static final int EVENT_PROMPT_UNVALIDATED = 29;
    private static final int EVENT_PROXY_HAS_CHANGED = 16;
    private static final int EVENT_REGISTER_NETWORK_AGENT = 18;
    private static final int EVENT_REGISTER_NETWORK_FACTORY = 17;
    private static final int EVENT_REGISTER_NETWORK_LISTENER = 21;
    private static final int EVENT_REGISTER_NETWORK_LISTENER_WITH_INTENT = 31;
    private static final int EVENT_REGISTER_NETWORK_REQUEST = 19;
    private static final int EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT = 26;
    private static final int EVENT_RELEASE_NETWORK_REQUEST = 22;
    private static final int EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT = 27;
    private static final int EVENT_REVALIDATE_NETWORK = 36;
    private static final int EVENT_SET_ACCEPT_UNVALIDATED = 28;
    private static final int EVENT_SET_AVOID_UNVALIDATED = 35;
    private static final int EVENT_SYSTEM_READY = 25;
    private static final int EVENT_TIMEOUT_NETWORK_REQUEST = 20;
    private static final int EVENT_UNREGISTER_NETWORK_FACTORY = 23;
    private static final int GID_NAVI_APP = 10000;
    private static final int GID_SYSTEM = 1000;
    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    private static final boolean LOGD_BLOCKED_NETWORKINFO = true;
    private static final boolean LOGD_RULES = false;
    private static final int MAX_NETWORK_INFO_LOGS = 40;
    private static final int MAX_NETWORK_REQUESTS_PER_UID = 100;
    private static final int MAX_NETWORK_REQUEST_LOGS = 20;
    private static final int MAX_NET_ID = 64511;
    private static final int MAX_VALIDATION_LOGS = 10;
    private static final int MAX_WAKELOCK_LOGS = 20;
    private static final int MIN_NET_ID = 100;
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    private static final int POWER_STATE_SLEEP = 2;
    private static final int PROMPT_UNVALIDATED_DELAY_MS = 8000;
    private static final String PROVISIONING_URL_PATH = "/data/misc/radio/provisioning_urls.xml";
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 60000;
    public static final String SHORT_ARG = "--short";
    private static final int SYSTEM_APN_MARK = 10008;
    private static final String SYS_PROP_ACTIVE_NETWORK_IFACE_NAME = "sys.xiaopeng.active_network";
    private static final String SYS_PROP_CURRENT_DEVICE_IP = "sys.xiaopeng.device_ip";
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    private static final String TBOX_ETH_GATEWAY = "192.168.225.1";
    private static final String TBOX_ETH_SECOND_IP = "192.168.225.3";
    private static final int TBOX_ETH_SUBTYPE = 3;
    public static final String TETHERING_ARG = "tethering";
    private static final boolean VDBG = false;
    private static final String XP_POWER_STATE = "sys.xiaopeng.power_state";
    private static ConnectivityService sServiceInstance;
    @GuardedBy("mBlockedAppUids")
    private final HashSet<Integer> mBlockedAppUids;
    private final Context mContext;
    private String mCurrentTcpBufferSizes;
    private INetworkManagementEventObserver mDataActivityObserver;
    private DataConnectionStats mDataConnectionStats;
    private int mDefaultInetConditionPublished;
    private final NetworkRequest mDefaultMobileDataRequest;
    private volatile ProxyInfo mDefaultProxy;
    private boolean mDefaultProxyDisabled;
    private final NetworkRequest mDefaultRequest;
    private final Map<String, Boolean> mDefaultRouteState;
    private final DnsManager mDnsManager;
    private ProxyInfo mGlobalProxy;
    private final InternalHandler mHandler;
    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    private Intent mInitialBroadcast;
    private IIpConnectivityMetrics mIpConnectivityMetrics;
    private KeepaliveTracker mKeepaliveTracker;
    private KeyStore mKeyStore;
    private long mLastWakeLockAcquireTimestamp;
    private LegacyTypeTracker mLegacyTypeTracker;
    @VisibleForTesting
    protected int mLingerDelayMs;
    private LingerMonitor mLingerMonitor;
    @GuardedBy("mVpns")
    private boolean mLockdownEnabled;
    @GuardedBy("mVpns")
    private LockdownVpnTracker mLockdownTracker;
    private long mMaxWakelockDurationMs;
    private final IpConnectivityLog mMetricsLog;
    @VisibleForTesting
    final MultinetworkPolicyTracker mMultinetworkPolicyTracker;
    @VisibleForTesting
    final MultipathPolicyTracker mMultipathPolicyTracker;
    NetworkConfig[] mNetConfigs;
    @GuardedBy("mNetworkForNetId")
    private final SparseBooleanArray mNetIdInUse;
    private PowerManager.WakeLock mNetTransitionWakeLock;
    private int mNetTransitionWakeLockTimeout;
    private INetworkManagementService mNetd;
    @VisibleForTesting
    protected final INetdEventCallback mNetdEventCallback;
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos;
    private final HashMap<Messenger, NetworkFactoryInfo> mNetworkFactoryInfos;
    @GuardedBy("mNetworkForNetId")
    private final SparseArray<NetworkAgentInfo> mNetworkForNetId;
    @GuardedBy("mNetworkForRequestId")
    private final SparseArray<NetworkAgentInfo> mNetworkForRequestId;
    private final LocalLog mNetworkInfoBlockingLogs;
    private int mNetworkPreference;
    private final LocalLog mNetworkRequestInfoLogs;
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests;
    int mNetworksDefined;
    private int mNextNetId;
    private int mNextNetworkRequestId;
    private NetworkNotificationManager mNotifier;
    private PacManager mPacManager;
    private final PowerManager.WakeLock mPendingIntentWakeLock;
    private final PermissionMonitor mPermissionMonitor;
    private final INetworkPolicyListener mPolicyListener;
    private INetworkPolicyManager mPolicyManager;
    private NetworkPolicyManagerInternal mPolicyManagerInternal;
    private final PriorityDump.PriorityDumper mPriorityDumper;
    List mProtectedNetworks;
    private final File mProvisioningUrlFile;
    private Object mProxyLock;
    private final int mReleasePendingIntentDelayMs;
    private final SettingsObserver mSettingsObserver;
    private INetworkStatsService mStatsService;
    private final HashSet<Integer> mSystemApnGids;
    private final SystemApnManager mSystemApnMgr;
    private MockableSystemProperties mSystemProperties;
    private boolean mSystemReady;
    TelephonyManager mTelephonyManager;
    private boolean mTestMode;
    private Tethering mTethering;
    private int mTotalWakelockAcquisitions;
    private long mTotalWakelockDurationMs;
    private int mTotalWakelockReleases;
    private final NetworkStateTrackerHandler mTrackerHandler;
    @GuardedBy("mUidToNetworkRequestCount")
    private final SparseIntArray mUidToNetworkRequestCount;
    private BroadcastReceiver mUserIntentReceiver;
    private UserManager mUserManager;
    private BroadcastReceiver mUserPresentReceiver;
    private final ArrayDeque<ValidationLog> mValidationLogs;
    @GuardedBy("mVpns")
    @VisibleForTesting
    protected final SparseArray<Vpn> mVpns;
    private final LocalLog mWakelockLogs;
    private static final String TAG = ConnectivityService.class.getSimpleName();
    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(new Class[]{AsyncChannel.class, ConnectivityService.class, NetworkAgent.class, NetworkAgentInfo.class});

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public enum ReapUnvalidatedNetworks {
        REAP,
        DONT_REAP
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public enum UnneededFor {
        LINGER,
        TEARDOWN
    }

    private static String eventName(int what) {
        return sMagicDecoderRing.get(what, Integer.toString(what));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ValidationLog {
        final LocalLog.ReadOnlyLocalLog mLog;
        final String mName;
        final Network mNetwork;

        ValidationLog(Network network, String name, LocalLog.ReadOnlyLocalLog log) {
            this.mNetwork = network;
            this.mName = name;
            this.mLog = log;
        }
    }

    private void addValidationLogs(LocalLog.ReadOnlyLocalLog log, Network network, String name) {
        synchronized (this.mValidationLogs) {
            while (this.mValidationLogs.size() >= 10) {
                this.mValidationLogs.removeLast();
            }
            this.mValidationLogs.addFirst(new ValidationLog(network, name, log));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LegacyTypeTracker {
        private static final boolean DBG = true;
        private static final boolean VDBG = false;
        private final ArrayList<NetworkAgentInfo>[] mTypeLists = new ArrayList[18];

        public LegacyTypeTracker() {
        }

        public void addSupportedType(int type) {
            if (this.mTypeLists[type] != null) {
                throw new IllegalStateException("legacy list for type " + type + "already initialized");
            }
            this.mTypeLists[type] = new ArrayList<>();
        }

        public boolean isTypeSupported(int type) {
            return ConnectivityManager.isNetworkTypeValid(type) && this.mTypeLists[type] != null;
        }

        public NetworkAgentInfo getNetworkForType(int type) {
            synchronized (this.mTypeLists) {
                if (isTypeSupported(type) && !this.mTypeLists[type].isEmpty()) {
                    return this.mTypeLists[type].get(0);
                }
                return null;
            }
        }

        private void maybeLogBroadcast(NetworkAgentInfo nai, NetworkInfo.DetailedState state, int type, boolean isDefaultNetwork) {
            ConnectivityService.loge("Sending " + state + " broadcast for type " + type + " " + nai.name() + " isDefaultNetwork=" + isDefaultNetwork);
        }

        public void add(int type, NetworkAgentInfo nai) {
            if (!isTypeSupported(type)) {
                return;
            }
            ArrayList<NetworkAgentInfo> list = this.mTypeLists[type];
            if (list.contains(nai)) {
                return;
            }
            synchronized (this.mTypeLists) {
                list.add(nai);
            }
            boolean isDefaultNetwork = ConnectivityService.this.isDefaultNetwork(nai);
            if (list.size() == 1 || isDefaultNetwork) {
                maybeLogBroadcast(nai, NetworkInfo.DetailedState.CONNECTED, type, isDefaultNetwork);
                ConnectivityService.this.sendLegacyNetworkBroadcast(nai, NetworkInfo.DetailedState.CONNECTED, type);
            }
        }

        public void remove(int type, NetworkAgentInfo nai, boolean wasDefault) {
            ArrayList<NetworkAgentInfo> list = this.mTypeLists[type];
            if (list == null || list.isEmpty()) {
                return;
            }
            boolean wasFirstNetwork = list.get(0).equals(nai);
            synchronized (this.mTypeLists) {
                if (list.remove(nai)) {
                    NetworkInfo.DetailedState state = NetworkInfo.DetailedState.DISCONNECTED;
                    if (wasFirstNetwork || wasDefault) {
                        maybeLogBroadcast(nai, state, type, wasDefault);
                        ConnectivityService.this.sendLegacyNetworkBroadcast(nai, state, type);
                    }
                    if (!list.isEmpty() && wasFirstNetwork) {
                        ConnectivityService.log("Other network available for type " + type + ", sending connected broadcast");
                        NetworkAgentInfo replacement = list.get(0);
                        maybeLogBroadcast(replacement, state, type, ConnectivityService.this.isDefaultNetwork(replacement));
                        ConnectivityService.this.sendLegacyNetworkBroadcast(replacement, state, type);
                    }
                }
            }
        }

        public void remove(NetworkAgentInfo nai, boolean wasDefault) {
            for (int type = 0; type < this.mTypeLists.length; type++) {
                remove(type, nai, wasDefault);
            }
        }

        public void update(NetworkAgentInfo nai) {
            boolean contains;
            boolean isDefault = ConnectivityService.this.isDefaultNetwork(nai);
            NetworkInfo.DetailedState state = nai.networkInfo.getDetailedState();
            for (int type = 0; type < this.mTypeLists.length; type++) {
                ArrayList<NetworkAgentInfo> list = this.mTypeLists[type];
                boolean isFirst = true;
                if (list == null || !list.contains(nai)) {
                    contains = false;
                } else {
                    contains = true;
                }
                isFirst = (contains && nai == list.get(0)) ? false : false;
                if (isFirst || (contains && isDefault)) {
                    maybeLogBroadcast(nai, state, type, isDefault);
                    ConnectivityService.this.sendLegacyNetworkBroadcast(nai, state, type);
                }
            }
        }

        private String naiToString(NetworkAgentInfo nai) {
            String state;
            String name = nai != null ? nai.name() : "null";
            if (nai.networkInfo != null) {
                state = nai.networkInfo.getState() + SliceClientPermissions.SliceAuthority.DELIMITER + nai.networkInfo.getDetailedState();
            } else {
                state = "???/???";
            }
            return name + " " + state;
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("mLegacyTypeTracker:");
            pw.increaseIndent();
            pw.print("Supported types:");
            for (int type = 0; type < this.mTypeLists.length; type++) {
                if (this.mTypeLists[type] != null) {
                    pw.print(" " + type);
                }
            }
            pw.println();
            pw.println("Current state:");
            pw.increaseIndent();
            synchronized (this.mTypeLists) {
                for (int type2 = 0; type2 < this.mTypeLists.length; type2++) {
                    if (this.mTypeLists[type2] != null && !this.mTypeLists[type2].isEmpty()) {
                        Iterator<NetworkAgentInfo> it = this.mTypeLists[type2].iterator();
                        while (it.hasNext()) {
                            NetworkAgentInfo nai = it.next();
                            pw.println(type2 + " " + naiToString(nai));
                        }
                    }
                }
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
            pw.println();
        }
    }

    public ConnectivityService(Context context, INetworkManagementService netManager, INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        this(context, netManager, statsService, policyManager, new IpConnectivityLog());
    }

    @VisibleForTesting
    protected ConnectivityService(Context context, INetworkManagementService netManager, INetworkStatsService statsService, INetworkPolicyManager policyManager, IpConnectivityLog logger) {
        this.mVpns = new SparseArray<>();
        this.mDefaultInetConditionPublished = 0;
        this.mDefaultProxy = null;
        this.mProxyLock = new Object();
        this.mDefaultProxyDisabled = false;
        this.mGlobalProxy = null;
        this.mPacManager = null;
        this.mNextNetId = 100;
        this.mNextNetworkRequestId = 1;
        this.mNetworkRequestInfoLogs = new LocalLog(20);
        this.mNetworkInfoBlockingLogs = new LocalLog(40);
        this.mWakelockLogs = new LocalLog(20);
        this.mTotalWakelockAcquisitions = 0;
        this.mTotalWakelockReleases = 0;
        this.mTotalWakelockDurationMs = 0L;
        this.mMaxWakelockDurationMs = 0L;
        this.mLastWakeLockAcquireTimestamp = 0L;
        this.mValidationLogs = new ArrayDeque<>(10);
        this.mLegacyTypeTracker = new LegacyTypeTracker();
        this.mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.ConnectivityService.1
            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ConnectivityService.this.doDump(fd, pw, new String[]{ConnectivityService.DIAG_ARG}, asProto);
                ConnectivityService.this.doDump(fd, pw, new String[]{ConnectivityService.SHORT_ARG}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ConnectivityService.this.doDump(fd, pw, args, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ConnectivityService.this.doDump(fd, pw, args, asProto);
            }
        };
        this.mDataActivityObserver = new BaseNetworkObserver() { // from class: com.android.server.ConnectivityService.3
            public void interfaceClassDataActivityChanged(String label, boolean active, long tsNanos) {
                int deviceType = Integer.parseInt(label);
                ConnectivityService.this.sendDataActivityBroadcast(deviceType, active, tsNanos);
            }
        };
        this.mNetdEventCallback = new BaseNetdEventCallback() { // from class: com.android.server.ConnectivityService.4
            public void onPrivateDnsValidationEvent(int netId, String ipAddress, String hostname, boolean validated) {
                try {
                    ConnectivityService.this.mHandler.sendMessage(ConnectivityService.this.mHandler.obtainMessage(38, new DnsManager.PrivateDnsValidationUpdate(netId, InetAddress.parseNumericAddress(ipAddress), hostname, validated)));
                } catch (IllegalArgumentException e) {
                    ConnectivityService.loge("Error parsing ip address in validation event");
                }
            }
        };
        this.mPolicyListener = new NetworkPolicyManager.Listener() { // from class: com.android.server.ConnectivityService.5
            public void onUidRulesChanged(int uid, int uidRules) {
            }

            public void onRestrictBackgroundChanged(boolean restrictBackground) {
                if (restrictBackground) {
                    ConnectivityService.log("onRestrictBackgroundChanged(true): disabling tethering");
                    ConnectivityService.this.mTethering.untetherAll();
                }
            }
        };
        this.mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);
        this.mUserIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.ConnectivityService.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                ConnectivityService.this.ensureRunningOnConnectivityServiceThread();
                String action = intent.getAction();
                int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                if (userId == -10000) {
                    return;
                }
                if ("android.intent.action.USER_STARTED".equals(action)) {
                    ConnectivityService.this.onUserStart(userId);
                } else if ("android.intent.action.USER_STOPPED".equals(action)) {
                    ConnectivityService.this.onUserStop(userId);
                } else if ("android.intent.action.USER_ADDED".equals(action)) {
                    ConnectivityService.this.onUserAdded(userId);
                } else if ("android.intent.action.USER_REMOVED".equals(action)) {
                    ConnectivityService.this.onUserRemoved(userId);
                } else if ("android.intent.action.USER_UNLOCKED".equals(action)) {
                    ConnectivityService.this.onUserUnlocked(userId);
                }
            }
        };
        this.mUserPresentReceiver = new BroadcastReceiver() { // from class: com.android.server.ConnectivityService.7
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                ConnectivityService.this.updateLockdownVpn();
                ConnectivityService.this.mContext.unregisterReceiver(this);
            }
        };
        this.mNetworkFactoryInfos = new HashMap<>();
        this.mNetworkRequests = new HashMap<>();
        this.mUidToNetworkRequestCount = new SparseIntArray();
        this.mNetworkForRequestId = new SparseArray<>();
        this.mNetworkForNetId = new SparseArray<>();
        this.mNetIdInUse = new SparseBooleanArray();
        this.mNetworkAgentInfos = new HashMap<>();
        this.mBlockedAppUids = new HashSet<>();
        log("ConnectivityService starting up");
        this.mSystemProperties = getSystemProperties();
        this.mMetricsLog = logger;
        this.mDefaultRequest = createDefaultInternetRequestForTransport(-1, NetworkRequest.Type.REQUEST);
        NetworkRequestInfo defaultNRI = new NetworkRequestInfo(null, this.mDefaultRequest, new Binder());
        this.mNetworkRequests.put(this.mDefaultRequest, defaultNRI);
        this.mNetworkRequestInfoLogs.log("REGISTER " + defaultNRI);
        this.mDefaultMobileDataRequest = createDefaultInternetRequestForTransport(0, NetworkRequest.Type.BACKGROUND_REQUEST);
        this.mHandlerThread = new HandlerThread("ConnectivityServiceThread");
        this.mHandlerThread.start();
        this.mHandler = new InternalHandler(this.mHandlerThread.getLooper());
        this.mTrackerHandler = new NetworkStateTrackerHandler(this.mHandlerThread.getLooper());
        this.mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(), "connectivity_release_pending_intent_delay_ms", 5000);
        this.mLingerDelayMs = this.mSystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);
        this.mContext = (Context) Preconditions.checkNotNull(context, "missing Context");
        this.mNetd = (INetworkManagementService) Preconditions.checkNotNull(netManager, "missing INetworkManagementService");
        this.mStatsService = (INetworkStatsService) Preconditions.checkNotNull(statsService, "missing INetworkStatsService");
        this.mPolicyManager = (INetworkPolicyManager) Preconditions.checkNotNull(policyManager, "missing INetworkPolicyManager");
        this.mPolicyManagerInternal = (NetworkPolicyManagerInternal) Preconditions.checkNotNull((NetworkPolicyManagerInternal) LocalServices.getService(NetworkPolicyManagerInternal.class), "missing NetworkPolicyManagerInternal");
        this.mKeyStore = KeyStore.getInstance();
        this.mTelephonyManager = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        try {
            this.mPolicyManager.registerListener(this.mPolicyListener);
        } catch (RemoteException e) {
            loge("unable to register INetworkPolicyListener" + e);
        }
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mNetTransitionWakeLock = powerManager.newWakeLock(1, TAG);
        this.mNetTransitionWakeLockTimeout = this.mContext.getResources().getInteger(17694830);
        this.mPendingIntentWakeLock = powerManager.newWakeLock(1, TAG);
        this.mNetConfigs = new NetworkConfig[18];
        boolean wifiOnly = this.mSystemProperties.getBoolean("ro.radio.noril", false);
        log("wifiOnly=" + wifiOnly);
        String[] naStrings = context.getResources().getStringArray(17236069);
        int length = naStrings.length;
        for (int i = 0; i < length; i++) {
            String naString = naStrings[i];
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (n.type > 17) {
                    loge("Error in networkAttributes - ignoring attempt to define type " + n.type);
                } else if (wifiOnly && ConnectivityManager.isNetworkTypeMobile(n.type)) {
                    log("networkAttributes - ignoring mobile as this dev is wifiOnly " + n.type);
                } else if (this.mNetConfigs[n.type] != null) {
                    loge("Error in networkAttributes - ignoring attempt to redefine type " + n.type);
                } else {
                    this.mLegacyTypeTracker.addSupportedType(n.type);
                    this.mNetConfigs[n.type] = n;
                    this.mNetworksDefined++;
                }
            } catch (Exception e2) {
            }
        }
        if (this.mNetConfigs[17] == null) {
            this.mLegacyTypeTracker.addSupportedType(17);
            this.mNetworksDefined++;
        }
        if (this.mNetConfigs[9] == null && hasService("ethernet")) {
            this.mLegacyTypeTracker.addSupportedType(9);
            this.mNetworksDefined++;
        }
        this.mProtectedNetworks = new ArrayList();
        int[] protectedNetworks = context.getResources().getIntArray(17236030);
        for (int p : protectedNetworks) {
            if (this.mNetConfigs[p] == null || this.mProtectedNetworks.contains(Integer.valueOf(p))) {
                loge("Ignoring protectedNetwork " + p);
            } else {
                this.mProtectedNetworks.add(Integer.valueOf(p));
            }
        }
        this.mTestMode = this.mSystemProperties.get("cm.test.mode").equals("true") && this.mSystemProperties.get("ro.build.type").equals("eng");
        this.mTethering = makeTethering();
        this.mPermissionMonitor = new PermissionMonitor(this.mContext, this.mNetd);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_STARTED");
        intentFilter.addAction("android.intent.action.USER_STOPPED");
        intentFilter.addAction("android.intent.action.USER_ADDED");
        intentFilter.addAction("android.intent.action.USER_REMOVED");
        intentFilter.addAction("android.intent.action.USER_UNLOCKED");
        this.mContext.registerReceiverAsUser(this.mUserIntentReceiver, UserHandle.ALL, intentFilter, null, this.mHandler);
        this.mContext.registerReceiverAsUser(this.mUserPresentReceiver, UserHandle.SYSTEM, new IntentFilter("android.intent.action.USER_PRESENT"), null, null);
        try {
            this.mNetd.registerObserver(this.mTethering);
            this.mNetd.registerObserver(this.mDataActivityObserver);
        } catch (RemoteException e3) {
            loge("Error registering observer :" + e3);
        }
        this.mSettingsObserver = new SettingsObserver(this.mContext, this.mHandler);
        registerSettingsCallbacks();
        this.mDataConnectionStats = new DataConnectionStats(this.mContext);
        this.mDataConnectionStats.startMonitoring();
        this.mPacManager = new PacManager(this.mContext, this.mHandler, 16);
        this.mUserManager = (UserManager) context.getSystemService("user");
        this.mKeepaliveTracker = new KeepaliveTracker(this.mHandler);
        this.mNotifier = new NetworkNotificationManager(this.mContext, this.mTelephonyManager, (NotificationManager) this.mContext.getSystemService(NotificationManager.class));
        int dailyLimit = Settings.Global.getInt(this.mContext.getContentResolver(), "network_switch_notification_daily_limit", 3);
        long rateLimit = Settings.Global.getLong(this.mContext.getContentResolver(), "network_switch_notification_rate_limit_millis", 60000L);
        this.mLingerMonitor = new LingerMonitor(this.mContext, this.mNotifier, dailyLimit, rateLimit);
        this.mMultinetworkPolicyTracker = createMultinetworkPolicyTracker(this.mContext, this.mHandler, new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$SFqiR4Pfksb1C7csMC3uNxCllR8
            @Override // java.lang.Runnable
            public final void run() {
                ConnectivityService.this.rematchForAvoidBadWifiUpdate();
            }
        });
        this.mMultinetworkPolicyTracker.start();
        this.mMultipathPolicyTracker = new MultipathPolicyTracker(this.mContext, this.mHandler);
        this.mDnsManager = new DnsManager(this.mContext, this.mNetd, this.mSystemProperties);
        registerPrivateDnsSettingsCallbacks();
        this.mDefaultRouteState = new HashMap();
        this.mSystemApnMgr = new SystemApnManager(this.mContext, netManager);
        this.mSystemApnGids = new HashSet<>();
    }

    private Tethering makeTethering() {
        TetheringDependencies deps = new TetheringDependencies() { // from class: com.android.server.ConnectivityService.2
            @Override // com.android.server.connectivity.tethering.TetheringDependencies
            public boolean isTetheringSupported() {
                return ConnectivityService.this.isTetheringSupported();
            }

            @Override // com.android.server.connectivity.tethering.TetheringDependencies
            public NetworkRequest getDefaultNetworkRequest() {
                return ConnectivityService.this.mDefaultRequest;
            }
        };
        return new Tethering(this.mContext, this.mNetd, this.mStatsService, this.mPolicyManager, IoThread.get().getLooper(), new MockableSystemProperties(), deps);
    }

    private static NetworkCapabilities createDefaultNetworkCapabilitiesForUid(int uid) {
        NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(12);
        netCap.addCapability(13);
        netCap.removeCapability(15);
        netCap.setSingleUid(uid);
        return netCap;
    }

    private NetworkRequest createDefaultInternetRequestForTransport(int transportType, NetworkRequest.Type type) {
        NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(12);
        netCap.addCapability(13);
        if (transportType > -1) {
            netCap.addTransportType(transportType);
        }
        return new NetworkRequest(netCap, -1, nextNetworkRequestId(), type);
    }

    @VisibleForTesting
    void updateMobileDataAlwaysOn() {
        this.mHandler.sendEmptyMessage(30);
    }

    @VisibleForTesting
    void updatePrivateDnsSettings() {
        this.mHandler.sendEmptyMessage(37);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleMobileDataAlwaysOn() {
        boolean enable = toBool(Settings.Global.getInt(this.mContext.getContentResolver(), "mobile_data_always_on", 1));
        boolean isEnabled = this.mNetworkRequests.get(this.mDefaultMobileDataRequest) != null;
        if (enable == isEnabled) {
            return;
        }
        if (enable) {
            handleRegisterNetworkRequest(new NetworkRequestInfo(null, this.mDefaultMobileDataRequest, new Binder()));
        } else {
            handleReleaseNetworkRequest(this.mDefaultMobileDataRequest, 1000);
        }
    }

    private void registerSettingsCallbacks() {
        this.mSettingsObserver.observe(Settings.Global.getUriFor("http_proxy"), 9);
        this.mSettingsObserver.observe(Settings.Global.getUriFor("mobile_data_always_on"), 30);
    }

    private void registerPrivateDnsSettingsCallbacks() {
        Uri[] privateDnsSettingsUris;
        for (Uri uri : DnsManager.getPrivateDnsSettingsUris()) {
            this.mSettingsObserver.observe(uri, 37);
        }
    }

    private synchronized int nextNetworkRequestId() {
        int i;
        i = this.mNextNetworkRequestId;
        this.mNextNetworkRequestId = i + 1;
        return i;
    }

    @VisibleForTesting
    protected int reserveNetId() {
        synchronized (this.mNetworkForNetId) {
            for (int i = 100; i <= MAX_NET_ID; i++) {
                try {
                    int netId = this.mNextNetId;
                    int i2 = this.mNextNetId + 1;
                    this.mNextNetId = i2;
                    if (i2 > MAX_NET_ID) {
                        this.mNextNetId = 100;
                    }
                    if (!this.mNetIdInUse.get(netId)) {
                        this.mNetIdInUse.put(netId, true);
                        return netId;
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            throw new IllegalStateException("No free netIds");
        }
    }

    private NetworkState getFilteredNetworkState(int networkType, int uid, boolean ignoreBlocked) {
        NetworkState state;
        if (this.mLegacyTypeTracker.isTypeSupported(networkType)) {
            NetworkAgentInfo nai = this.mLegacyTypeTracker.getNetworkForType(networkType);
            if (nai != null) {
                state = nai.getNetworkState();
                state.networkInfo.setType(networkType);
            } else {
                NetworkInfo info = new NetworkInfo(networkType, 0, ConnectivityManager.getNetworkTypeName(networkType), BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
                info.setIsAvailable(true);
                NetworkCapabilities capabilities = new NetworkCapabilities();
                capabilities.setCapability(18, true ^ info.isRoaming());
                state = new NetworkState(info, new LinkProperties(), capabilities, (Network) null, (String) null, (String) null);
            }
            filterNetworkStateForUid(state, uid, ignoreBlocked);
            return state;
        }
        return NetworkState.EMPTY;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NetworkAgentInfo getNetworkAgentInfoForNetwork(Network network) {
        if (network == null) {
            return null;
        }
        return getNetworkAgentInfoForNetId(network.netId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NetworkAgentInfo getNetworkAgentInfoForNetId(int netId) {
        NetworkAgentInfo networkAgentInfo;
        synchronized (this.mNetworkForNetId) {
            networkAgentInfo = this.mNetworkForNetId.get(netId);
        }
        return networkAgentInfo;
    }

    private Network[] getVpnUnderlyingNetworks(int uid) {
        synchronized (this.mVpns) {
            if (!this.mLockdownEnabled) {
                int user = UserHandle.getUserId(uid);
                Vpn vpn = this.mVpns.get(user);
                if (vpn != null && vpn.appliesToUid(uid)) {
                    return vpn.getUnderlyingNetworks();
                }
            }
            return null;
        }
    }

    private NetworkState getUnfilteredActiveNetworkState(int uid) {
        NetworkAgentInfo nai = getDefaultNetwork();
        Network[] networks = getVpnUnderlyingNetworks(uid);
        if (networks != null) {
            if (networks.length > 0) {
                nai = getNetworkAgentInfoForNetwork(networks[0]);
            } else {
                nai = null;
            }
        }
        if (nai != null) {
            return nai.getNetworkState();
        }
        return NetworkState.EMPTY;
    }

    private boolean isNetworkWithLinkPropertiesBlocked(LinkProperties lp, int uid, boolean ignoreBlocked) {
        if (ignoreBlocked || isSystem(uid)) {
            return false;
        }
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(UserHandle.getUserId(uid));
            if (vpn != null && vpn.isBlockingUid(uid)) {
                return true;
            }
            String iface = lp == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : lp.getInterfaceName();
            return this.mPolicyManagerInternal.isUidNetworkingBlocked(uid, iface);
        }
    }

    private void maybeLogBlockedNetworkInfo(NetworkInfo ni, int uid) {
        boolean blocked;
        if (ni == null) {
            return;
        }
        synchronized (this.mBlockedAppUids) {
            if (ni.getDetailedState() == NetworkInfo.DetailedState.BLOCKED && this.mBlockedAppUids.add(Integer.valueOf(uid))) {
                blocked = true;
            } else {
                boolean blocked2 = ni.isConnected();
                if (!blocked2 || !this.mBlockedAppUids.remove(Integer.valueOf(uid))) {
                    return;
                }
                blocked = false;
            }
            String action = blocked ? "BLOCKED" : "UNBLOCKED";
            log(String.format("Returning %s NetworkInfo to uid=%d", action, Integer.valueOf(uid)));
            LocalLog localLog = this.mNetworkInfoBlockingLogs;
            localLog.log(action + " " + uid);
        }
    }

    private void filterNetworkStateForUid(NetworkState state, int uid, boolean ignoreBlocked) {
        if (state == null || state.networkInfo == null || state.linkProperties == null) {
            return;
        }
        if (isNetworkWithLinkPropertiesBlocked(state.linkProperties, uid, ignoreBlocked)) {
            state.networkInfo.setDetailedState(NetworkInfo.DetailedState.BLOCKED, null, null);
        }
        synchronized (this.mVpns) {
            if (this.mLockdownTracker != null) {
                this.mLockdownTracker.augmentNetworkInfo(state.networkInfo);
            }
        }
    }

    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        filterNetworkStateForUid(state, uid, false);
        maybeLogBlockedNetworkInfo(state.networkInfo, uid);
        if (state.networkInfo != null && state.networkInfo.getType() == 9 && is3rdApp(uid)) {
            NetworkInfo fakeNetWorkInfo = new NetworkInfo(state.networkInfo);
            fakeNetWorkInfo.setType(1);
            return fakeNetWorkInfo;
        }
        return state.networkInfo;
    }

    private boolean is3rdApp(int uid) {
        String packageName;
        String processName = null;
        try {
            Iterator it = ActivityManager.getService().getRunningAppProcesses().iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                ActivityManager.RunningAppProcessInfo proc = (ActivityManager.RunningAppProcessInfo) it.next();
                if (proc.uid == uid) {
                    processName = proc.processName;
                    break;
                }
            }
            if (processName != null) {
                int index = processName.indexOf(":");
                if (index > 0) {
                    packageName = processName.substring(0, index);
                } else {
                    packageName = processName;
                }
                xpPackageInfo info = AppGlobals.getPackageManager().getXpPackageInfo(packageName);
                if (info != null) {
                    return true;
                }
            }
        } catch (Exception e) {
            loge("Error get xpPackageInfo" + e);
        }
        return false;
    }

    public Network getActiveNetwork() {
        enforceAccessPermission();
        return getActiveNetworkForUidInternal(Binder.getCallingUid(), false);
    }

    public Network getActiveNetworkForUid(int uid, boolean ignoreBlocked) {
        enforceConnectivityInternalPermission();
        return getActiveNetworkForUidInternal(uid, ignoreBlocked);
    }

    private Network getActiveNetworkForUidInternal(int uid, boolean ignoreBlocked) {
        NetworkAgentInfo nai;
        int user = UserHandle.getUserId(uid);
        int vpnNetId = 0;
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(user);
            if (vpn != null && vpn.appliesToUid(uid)) {
                vpnNetId = vpn.getNetId();
            }
        }
        if (vpnNetId != 0 && (nai = getNetworkAgentInfoForNetId(vpnNetId)) != null) {
            NetworkCapabilities requiredCaps = createDefaultNetworkCapabilitiesForUid(uid);
            if (requiredCaps.satisfiedByNetworkCapabilities(nai.networkCapabilities)) {
                return nai.network;
            }
        }
        NetworkAgentInfo nai2 = getDefaultNetwork();
        if (nai2 != null && isNetworkWithLinkPropertiesBlocked(nai2.linkProperties, uid, ignoreBlocked)) {
            nai2 = null;
        }
        if (nai2 != null) {
            return nai2.network;
        }
        return null;
    }

    public NetworkInfo getActiveNetworkInfoUnfiltered() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.networkInfo;
    }

    public NetworkInfo getActiveNetworkInfoForUid(int uid, boolean ignoreBlocked) {
        enforceConnectivityInternalPermission();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        filterNetworkStateForUid(state, uid, ignoreBlocked);
        return state.networkInfo;
    }

    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        if (getVpnUnderlyingNetworks(uid) != null) {
            NetworkState state = getUnfilteredActiveNetworkState(uid);
            if (state.networkInfo != null && state.networkInfo.getType() == networkType) {
                filterNetworkStateForUid(state, uid, false);
                return state.networkInfo;
            }
        }
        NetworkState state2 = getFilteredNetworkState(networkType, uid, false);
        if (state2.networkInfo == null && networkType == 0 && is3rdApp(uid)) {
            NetworkState fakeState = getFilteredNetworkState(9, uid, false);
            return fakeState.networkInfo;
        }
        return state2.networkInfo;
    }

    public NetworkInfo getNetworkInfoForUid(Network network, int uid, boolean ignoreBlocked) {
        enforceAccessPermission();
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            NetworkState state = nai.getNetworkState();
            filterNetworkStateForUid(state, uid, ignoreBlocked);
            return state.networkInfo;
        }
        return null;
    }

    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        ArrayList<NetworkInfo> result = Lists.newArrayList();
        for (int networkType = 0; networkType <= 17; networkType++) {
            NetworkInfo info = getNetworkInfo(networkType);
            if (info != null) {
                result.add(info);
            }
        }
        int networkType2 = result.size();
        return (NetworkInfo[]) result.toArray(new NetworkInfo[networkType2]);
    }

    public Network getNetworkForType(int networkType) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        NetworkState state = getFilteredNetworkState(networkType, uid, false);
        if (!isNetworkWithLinkPropertiesBlocked(state.linkProperties, uid, false)) {
            return state.network;
        }
        return null;
    }

    public Network[] getAllNetworks() {
        Network[] result;
        enforceAccessPermission();
        synchronized (this.mNetworkForNetId) {
            result = new Network[this.mNetworkForNetId.size()];
            for (int i = 0; i < this.mNetworkForNetId.size(); i++) {
                result[i] = this.mNetworkForNetId.valueAt(i).network;
            }
        }
        return result;
    }

    public NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(int userId) {
        Vpn vpn;
        Network[] networks;
        enforceAccessPermission();
        HashMap<Network, NetworkCapabilities> result = new HashMap<>();
        NetworkAgentInfo nai = getDefaultNetwork();
        NetworkCapabilities nc = getNetworkCapabilitiesInternal(nai);
        if (nc != null) {
            result.put(nai.network, nc);
        }
        synchronized (this.mVpns) {
            if (!this.mLockdownEnabled && (vpn = this.mVpns.get(userId)) != null && (networks = vpn.getUnderlyingNetworks()) != null) {
                for (Network network : networks) {
                    NetworkCapabilities nc2 = getNetworkCapabilitiesInternal(getNetworkAgentInfoForNetwork(network));
                    if (nc2 != null) {
                        result.put(network, nc2);
                    }
                }
            }
        }
        NetworkCapabilities[] out = new NetworkCapabilities[result.size()];
        return (NetworkCapabilities[]) result.values().toArray(out);
    }

    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return this.mLegacyTypeTracker.isTypeSupported(networkType);
    }

    public LinkProperties getActiveLinkProperties() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.linkProperties;
    }

    public LinkProperties getLinkPropertiesForType(int networkType) {
        LinkProperties linkProperties;
        enforceAccessPermission();
        NetworkAgentInfo nai = this.mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            synchronized (nai) {
                linkProperties = new LinkProperties(nai.linkProperties);
            }
            return linkProperties;
        }
        return null;
    }

    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        return getLinkProperties(getNetworkAgentInfoForNetwork(network));
    }

    private LinkProperties getLinkProperties(NetworkAgentInfo nai) {
        LinkProperties linkProperties;
        if (nai == null) {
            return null;
        }
        synchronized (nai) {
            linkProperties = new LinkProperties(nai.linkProperties);
        }
        return linkProperties;
    }

    private NetworkCapabilities getNetworkCapabilitiesInternal(NetworkAgentInfo nai) {
        if (nai != null) {
            synchronized (nai) {
                if (nai.networkCapabilities != null) {
                    return networkCapabilitiesRestrictedForCallerPermissions(nai.networkCapabilities, Binder.getCallingPid(), Binder.getCallingUid());
                }
                return null;
            }
        }
        return null;
    }

    public NetworkCapabilities getNetworkCapabilities(Network network) {
        enforceAccessPermission();
        return getNetworkCapabilitiesInternal(getNetworkAgentInfoForNetwork(network));
    }

    private NetworkCapabilities networkCapabilitiesRestrictedForCallerPermissions(NetworkCapabilities nc, int callerPid, int callerUid) {
        NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (!checkSettingsPermission(callerPid, callerUid)) {
            newNc.setUids(null);
            newNc.setSSID(null);
        }
        return newNc;
    }

    private void restrictRequestUidsForCaller(NetworkCapabilities nc) {
        if (!checkSettingsPermission()) {
            nc.setSingleUid(Binder.getCallingUid());
        }
    }

    private void restrictBackgroundRequestForCaller(NetworkCapabilities nc) {
        if (!this.mPermissionMonitor.hasUseBackgroundNetworksPermission(Binder.getCallingUid())) {
            nc.addCapability(19);
        }
    }

    public NetworkState[] getAllNetworkState() {
        Network[] allNetworks;
        enforceConnectivityInternalPermission();
        ArrayList<NetworkState> result = Lists.newArrayList();
        for (Network network : getAllNetworks()) {
            NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai != null) {
                result.add(nai.getNetworkState());
            }
        }
        return (NetworkState[]) result.toArray(new NetworkState[result.size()]);
    }

    @Deprecated
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        String str = TAG;
        Log.w(str, "Shame on UID " + Binder.getCallingUid() + " for calling the hidden API getNetworkQuotaInfo(). Shame!");
        return new NetworkQuotaInfo();
    }

    public boolean isActiveNetworkMetered() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        NetworkCapabilities caps = getUnfilteredActiveNetworkState(uid).networkCapabilities;
        if (caps != null) {
            return true ^ caps.hasCapability(11);
        }
        return true;
    }

    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        NetworkInfo.DetailedState netState;
        LinkProperties lp;
        int netId;
        enforceChangePermission();
        if (this.mProtectedNetworks.contains(Integer.valueOf(networkType))) {
            enforceConnectivityInternalPermission();
        }
        try {
            InetAddress addr = InetAddress.getByAddress(hostAddress);
            if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
                log("requestRouteToHostAddress on invalid network: " + networkType);
                return false;
            }
            NetworkAgentInfo nai = this.mLegacyTypeTracker.getNetworkForType(networkType);
            if (nai == null) {
                if (!this.mLegacyTypeTracker.isTypeSupported(networkType)) {
                    log("requestRouteToHostAddress on unsupported network: " + networkType);
                } else {
                    log("requestRouteToHostAddress on down network: " + networkType);
                }
                return false;
            }
            synchronized (nai) {
                netState = nai.networkInfo.getDetailedState();
            }
            if (netState != NetworkInfo.DetailedState.CONNECTED && netState != NetworkInfo.DetailedState.CAPTIVE_PORTAL_CHECK) {
                return false;
            }
            int uid = Binder.getCallingUid();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (nai) {
                    lp = nai.linkProperties;
                    netId = nai.network.netId;
                }
                boolean ok = addLegacyRouteToHost(lp, addr, netId, uid);
                log("requestRouteToHostAddress ok=" + ok);
                return ok;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (UnknownHostException e) {
            log("requestRouteToHostAddress got " + e.toString());
            return false;
        }
    }

    private boolean addLegacyRouteToHost(LinkProperties lp, InetAddress addr, int netId, int uid) {
        RouteInfo bestRoute;
        RouteInfo bestRoute2 = RouteInfo.selectBestRoute(lp.getAllRoutes(), addr);
        if (bestRoute2 == null) {
            bestRoute = RouteInfo.makeHostRoute(addr, lp.getInterfaceName());
        } else {
            String iface = bestRoute2.getInterface();
            if (bestRoute2.getGateway().equals(addr)) {
                bestRoute = RouteInfo.makeHostRoute(addr, iface);
            } else {
                bestRoute = RouteInfo.makeHostRoute(addr, bestRoute2.getGateway(), iface);
            }
        }
        log("Adding legacy route " + bestRoute + " for UID/PID " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid());
        try {
            this.mNetd.addLegacyRouteForNetId(netId, bestRoute, uid);
            return true;
        } catch (Exception e) {
            loge("Exception trying to add a route: " + e);
            return false;
        }
    }

    @VisibleForTesting
    protected void registerNetdEventCallback() {
        this.mIpConnectivityMetrics = IIpConnectivityMetrics.Stub.asInterface(ServiceManager.getService("connmetrics"));
        if (this.mIpConnectivityMetrics == null) {
            Slog.wtf(TAG, "Missing IIpConnectivityMetrics");
        }
        try {
            this.mIpConnectivityMetrics.addNetdEventCallback(0, this.mNetdEventCallback);
        } catch (Exception e) {
            loge("Error registering netd callback: " + e);
        }
    }

    private void enforceCrossUserPermission(int userId) {
        if (userId == UserHandle.getCallingUserId()) {
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "ConnectivityService");
    }

    private void enforceInternetPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERNET", "ConnectivityService");
    }

    private void enforceAccessPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE", "ConnectivityService");
    }

    private void enforceChangePermission() {
        ConnectivityManager.enforceChangePermission(this.mContext);
    }

    private void enforceSettingsPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.NETWORK_SETTINGS", "ConnectivityService");
    }

    private boolean checkSettingsPermission() {
        return this.mContext.checkCallingOrSelfPermission("android.permission.NETWORK_SETTINGS") == 0;
    }

    private boolean checkSettingsPermission(int pid, int uid) {
        return this.mContext.checkPermission("android.permission.NETWORK_SETTINGS", pid, uid) == 0;
    }

    private void enforceTetherAccessPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE", "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CONNECTIVITY_INTERNAL", "ConnectivityService");
    }

    private void enforceConnectivityRestrictedNetworksPermission() {
        try {
            this.mContext.enforceCallingOrSelfPermission("android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS", "ConnectivityService");
        } catch (SecurityException e) {
            enforceConnectivityInternalPermission();
        }
    }

    private void enforceKeepalivePermission() {
        this.mContext.enforceCallingOrSelfPermission(KeepaliveTracker.PERMISSION, "ConnectivityService");
    }

    public void sendConnectedBroadcast(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        sendGeneralBroadcast(info, "android.net.conn.CONNECTIVITY_CHANGE");
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, "android.net.conn.INET_CONDITION_ACTION");
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        synchronized (this.mVpns) {
            if (this.mLockdownTracker != null) {
                info = new NetworkInfo(info);
                this.mLockdownTracker.augmentNetworkInfo(info);
            }
        }
        Intent intent = new Intent(bcastType);
        intent.putExtra("networkInfo", new NetworkInfo(info));
        intent.putExtra("networkType", info.getType());
        if (info.isFailover()) {
            intent.putExtra("isFailover", true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra("extraInfo", info.getExtraInfo());
        }
        intent.putExtra("inetCondition", this.mDefaultInetConditionPublished);
        return intent;
    }

    private void sendGeneralBroadcast(NetworkInfo info, String bcastType) {
        sendStickyBroadcast(makeGeneralIntent(info, bcastType));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendDataActivityBroadcast(int deviceType, boolean active, long tsNanos) {
        long ident;
        Intent intent = new Intent("android.net.conn.DATA_ACTIVITY_CHANGE");
        intent.putExtra("deviceType", deviceType);
        intent.putExtra("isActive", active);
        intent.putExtra("tsNanos", tsNanos);
        long ident2 = Binder.clearCallingIdentity();
        try {
            try {
                this.mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, "android.permission.RECEIVE_DATA_ACTIVITY_CHANGE", null, null, 0, null, null);
                Binder.restoreCallingIdentity(ident2);
            } catch (Throwable th) {
                th = th;
                ident = ident2;
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            ident = ident2;
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized (this) {
            if (!this.mSystemReady) {
                this.mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(67108864);
            Bundle options = null;
            long ident = Binder.clearCallingIdentity();
            if ("android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction())) {
                NetworkInfo ni = (NetworkInfo) intent.getParcelableExtra("networkInfo");
                if (ni.getType() == 3) {
                    intent.setAction("android.net.conn.CONNECTIVITY_CHANGE_SUPL");
                    intent.addFlags(1073741824);
                } else {
                    BroadcastOptions opts = BroadcastOptions.makeBasic();
                    opts.setMaxManifestReceiverApiLevel(23);
                    options = opts.toBundle();
                }
                IBatteryStats bs = BatteryStatsService.getService();
                try {
                    bs.noteConnectivityChanged(intent.getIntExtra("networkType", -1), ni != null ? ni.getState().toString() : "?");
                } catch (RemoteException e) {
                }
                intent.addFlags(DumpState.DUMP_COMPILER_STATS);
            }
            this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL, options);
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        loadGlobalProxy();
        registerNetdEventCallback();
        synchronized (this) {
            this.mSystemReady = true;
            if (this.mInitialBroadcast != null) {
                this.mContext.sendStickyBroadcastAsUser(this.mInitialBroadcast, UserHandle.ALL);
                this.mInitialBroadcast = null;
            }
        }
        this.mHandler.sendMessage(this.mHandler.obtainMessage(9));
        updateLockdownVpn();
        this.mHandler.sendMessage(this.mHandler.obtainMessage(25));
        this.mPermissionMonitor.startMonitoring();
    }

    private void setupDataActivityTracking(NetworkAgentInfo networkAgent) {
        String iface = networkAgent.linkProperties.getInterfaceName();
        int type = -1;
        int timeout = 0;
        if (networkAgent.networkCapabilities.hasTransport(0)) {
            timeout = Settings.Global.getInt(this.mContext.getContentResolver(), "data_activity_timeout_mobile", 10);
            type = 0;
        } else if (networkAgent.networkCapabilities.hasTransport(1)) {
            timeout = Settings.Global.getInt(this.mContext.getContentResolver(), "data_activity_timeout_wifi", 15);
            type = 1;
        }
        int timeout2 = timeout;
        if (timeout2 > 0 && iface != null && type != -1) {
            try {
                this.mNetd.addIdleTimer(iface, timeout2, type);
            } catch (Exception e) {
                loge("Exception in setupDataActivityTracking " + e);
            }
        }
    }

    private void removeDataActivityTracking(NetworkAgentInfo networkAgent) {
        String iface = networkAgent.linkProperties.getInterfaceName();
        NetworkCapabilities caps = networkAgent.networkCapabilities;
        if (iface != null) {
            if (caps.hasTransport(0) || caps.hasTransport(1)) {
                try {
                    this.mNetd.removeIdleTimer(iface);
                } catch (Exception e) {
                    loge("Exception in removeDataActivityTracking " + e);
                }
            }
        }
    }

    private void updateMtu(LinkProperties newLp, LinkProperties oldLp) {
        String iface = newLp.getInterfaceName();
        int mtu = newLp.getMtu();
        if (oldLp == null && mtu == 0) {
            return;
        }
        if (oldLp != null && newLp.isIdenticalMtu(oldLp)) {
            return;
        }
        if (!LinkProperties.isValidMtu(mtu, newLp.hasGlobalIPv6Address())) {
            if (mtu != 0) {
                loge("Unexpected mtu value: " + mtu + ", " + iface);
            }
        } else if (TextUtils.isEmpty(iface)) {
            loge("Setting MTU size with null iface.");
        } else {
            try {
                this.mNetd.setMtu(iface, mtu);
            } catch (Exception e) {
                String str = TAG;
                Slog.e(str, "exception in setMtu()" + e);
            }
        }
    }

    @VisibleForTesting
    protected MockableSystemProperties getSystemProperties() {
        return new MockableSystemProperties();
    }

    private void updateTcpBufferSizes(NetworkAgentInfo nai) {
        if (!isDefaultNetwork(nai)) {
            return;
        }
        String tcpBufferSizes = nai.linkProperties.getTcpBufferSizes();
        String[] values = null;
        if (tcpBufferSizes != null) {
            values = tcpBufferSizes.split(",");
        }
        if (values == null || values.length != 6) {
            log("Invalid tcpBufferSizes string: " + tcpBufferSizes + ", using defaults");
            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;
            values = DEFAULT_TCP_BUFFER_SIZES.split(",");
        }
        if (tcpBufferSizes.equals(this.mCurrentTcpBufferSizes)) {
            return;
        }
        try {
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_rmem_min", values[0]);
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_rmem_def", values[1]);
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_rmem_max", values[2]);
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_wmem_min", values[3]);
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_wmem_def", values[4]);
            FileUtils.stringToFile("/sys/kernel/ipv4/tcp_wmem_max", values[5]);
            this.mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (IOException e) {
            loge("Can't set TCP buffer sizes:" + e);
        }
        Integer rwndValue = Integer.valueOf(Settings.Global.getInt(this.mContext.getContentResolver(), "tcp_default_init_rwnd", this.mSystemProperties.getInt(DEFAULT_TCP_RWND_KEY, 0)));
        if (rwndValue.intValue() != 0) {
            this.mSystemProperties.set("sys.sysctl.tcp_def_init_rwnd", rwndValue.toString());
        }
    }

    public int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = this.mSystemProperties.get(NETWORK_RESTORE_DELAY_PROP_NAME);
        if (restoreDefaultNetworkDelayStr != null && restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.parseInt(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        if (networkType > 17 || this.mNetConfigs[networkType] == null) {
            return RESTORE_DEFAULT_NETWORK_DELAY;
        }
        int ret = this.mNetConfigs[networkType].restoreTime;
        return ret;
    }

    private void dumpNetworkDiagnostics(IndentingPrintWriter pw) {
        List<NetworkDiagnostics> netDiags = new ArrayList<>();
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            netDiags.add(new NetworkDiagnostics(nai.network, new LinkProperties(nai.linkProperties), 5000L));
        }
        for (NetworkDiagnostics netDiag : netDiags) {
            pw.println();
            netDiag.waitForMeasurements();
            netDiag.dump(pw);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        PriorityDump.dump(this.mPriorityDumper, fd, writer, args);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doDump(FileDescriptor fd, PrintWriter writer, String[] args, boolean asProto) {
        PrintWriter indentingPrintWriter = new IndentingPrintWriter(writer, "  ");
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, indentingPrintWriter) && !asProto) {
            if (ArrayUtils.contains(args, DIAG_ARG)) {
                dumpNetworkDiagnostics(indentingPrintWriter);
            } else if (ArrayUtils.contains(args, TETHERING_ARG)) {
                this.mTethering.dump(fd, indentingPrintWriter, args);
            } else {
                indentingPrintWriter.print("NetworkFactories for:");
                for (NetworkFactoryInfo nfi : this.mNetworkFactoryInfos.values()) {
                    indentingPrintWriter.print(" " + nfi.name);
                }
                indentingPrintWriter.println();
                indentingPrintWriter.println();
                NetworkAgentInfo defaultNai = getDefaultNetwork();
                indentingPrintWriter.print("Active default network: ");
                if (defaultNai == null) {
                    indentingPrintWriter.println("none");
                } else {
                    indentingPrintWriter.println(defaultNai.network.netId);
                }
                indentingPrintWriter.println();
                indentingPrintWriter.println("Current Networks:");
                indentingPrintWriter.increaseIndent();
                for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
                    indentingPrintWriter.println(nai.toString());
                    indentingPrintWriter.increaseIndent();
                    int i = 0;
                    indentingPrintWriter.println(String.format("Requests: REQUEST:%d LISTEN:%d BACKGROUND_REQUEST:%d total:%d", Integer.valueOf(nai.numForegroundNetworkRequests()), Integer.valueOf(nai.numNetworkRequests() - nai.numRequestNetworkRequests()), Integer.valueOf(nai.numBackgroundNetworkRequests()), Integer.valueOf(nai.numNetworkRequests())));
                    indentingPrintWriter.increaseIndent();
                    while (true) {
                        int i2 = i;
                        if (i2 < nai.numNetworkRequests()) {
                            indentingPrintWriter.println(nai.requestAt(i2).toString());
                            i = i2 + 1;
                        }
                    }
                    indentingPrintWriter.decreaseIndent();
                    indentingPrintWriter.println("Lingered:");
                    indentingPrintWriter.increaseIndent();
                    nai.dumpLingerTimers(indentingPrintWriter);
                    indentingPrintWriter.decreaseIndent();
                    indentingPrintWriter.decreaseIndent();
                }
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                indentingPrintWriter.println("Network Requests:");
                indentingPrintWriter.increaseIndent();
                for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
                    indentingPrintWriter.println(nri.toString());
                }
                indentingPrintWriter.println();
                indentingPrintWriter.decreaseIndent();
                this.mLegacyTypeTracker.dump(indentingPrintWriter);
                indentingPrintWriter.println();
                this.mTethering.dump(fd, indentingPrintWriter, args);
                indentingPrintWriter.println();
                this.mKeepaliveTracker.dump(indentingPrintWriter);
                indentingPrintWriter.println();
                dumpAvoidBadWifiSettings(indentingPrintWriter);
                indentingPrintWriter.println();
                this.mMultipathPolicyTracker.dump(indentingPrintWriter);
                if (ArrayUtils.contains(args, SHORT_ARG)) {
                    return;
                }
                indentingPrintWriter.println();
                synchronized (this.mValidationLogs) {
                    indentingPrintWriter.println("mValidationLogs (most recent first):");
                    Iterator<ValidationLog> it = this.mValidationLogs.iterator();
                    while (it.hasNext()) {
                        ValidationLog p = it.next();
                        indentingPrintWriter.println(p.mNetwork + " - " + p.mName);
                        indentingPrintWriter.increaseIndent();
                        p.mLog.dump(fd, indentingPrintWriter, args);
                        indentingPrintWriter.decreaseIndent();
                    }
                }
                indentingPrintWriter.println();
                indentingPrintWriter.println("mNetworkRequestInfoLogs (most recent first):");
                indentingPrintWriter.increaseIndent();
                this.mNetworkRequestInfoLogs.reverseDump(fd, indentingPrintWriter, args);
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                indentingPrintWriter.println("mNetworkInfoBlockingLogs (most recent first):");
                indentingPrintWriter.increaseIndent();
                this.mNetworkInfoBlockingLogs.reverseDump(fd, indentingPrintWriter, args);
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                indentingPrintWriter.println("NetTransition WakeLock activity (most recent first):");
                indentingPrintWriter.increaseIndent();
                indentingPrintWriter.println("total acquisitions: " + this.mTotalWakelockAcquisitions);
                indentingPrintWriter.println("total releases: " + this.mTotalWakelockReleases);
                indentingPrintWriter.println("cumulative duration: " + (this.mTotalWakelockDurationMs / 1000) + "s");
                indentingPrintWriter.println("longest duration: " + (this.mMaxWakelockDurationMs / 1000) + "s");
                if (this.mTotalWakelockAcquisitions > this.mTotalWakelockReleases) {
                    long duration = SystemClock.elapsedRealtime() - this.mLastWakeLockAcquireTimestamp;
                    indentingPrintWriter.println("currently holding WakeLock for: " + (duration / 1000) + "s");
                }
                this.mWakelockLogs.reverseDump(fd, indentingPrintWriter, args);
                indentingPrintWriter.decreaseIndent();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isLiveNetworkAgent(NetworkAgentInfo nai, int what) {
        if (nai.network == null) {
            return false;
        }
        NetworkAgentInfo officialNai = getNetworkAgentInfoForNetwork(nai.network);
        if (officialNai == null || !officialNai.equals(nai)) {
            if (officialNai != null) {
                loge(eventName(what) + " - isLiveNetworkAgent found mismatched netId: " + officialNai + " - " + nai);
            }
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NetworkStateTrackerHandler extends Handler {
        public NetworkStateTrackerHandler(Looper looper) {
            super(looper);
        }

        private boolean maybeHandleAsyncChannelMessage(Message msg) {
            int i = msg.what;
            if (i != 69632) {
                switch (i) {
                    case 69635:
                        NetworkAgentInfo nai = (NetworkAgentInfo) ConnectivityService.this.mNetworkAgentInfos.get(msg.replyTo);
                        if (nai != null) {
                            nai.asyncChannel.disconnect();
                            return true;
                        }
                        return true;
                    case 69636:
                        ConnectivityService.this.handleAsyncChannelDisconnected(msg);
                        return true;
                    default:
                        return false;
                }
            }
            ConnectivityService.this.handleAsyncChannelHalfConnect(msg);
            return true;
        }

        private void maybeHandleNetworkAgentMessage(Message msg) {
            NetworkAgentInfo nai = (NetworkAgentInfo) ConnectivityService.this.mNetworkAgentInfos.get(msg.replyTo);
            if (nai == null) {
                return;
            }
            int i = msg.what;
            if (i == 528392) {
                if (nai.everConnected && !nai.networkMisc.explicitlySelected) {
                    ConnectivityService.loge("ERROR: already-connected network explicitly selected.");
                }
                nai.networkMisc.explicitlySelected = true;
                nai.networkMisc.acceptUnvalidated = ((Boolean) msg.obj).booleanValue();
            } else if (i == 528397) {
                ConnectivityService.this.mKeepaliveTracker.handleEventPacketKeepalive(nai, msg);
            } else {
                switch (i) {
                    case 528385:
                        NetworkInfo info = (NetworkInfo) msg.obj;
                        ConnectivityService.this.updateNetworkInfo(nai, info);
                        return;
                    case 528386:
                        NetworkCapabilities networkCapabilities = (NetworkCapabilities) msg.obj;
                        if (networkCapabilities.hasCapability(17) || networkCapabilities.hasCapability(16) || networkCapabilities.hasCapability(19)) {
                            String str = ConnectivityService.TAG;
                            Slog.wtf(str, "BUG: " + nai + " has CS-managed capability.");
                        }
                        ConnectivityService.this.updateCapabilities(nai.getCurrentScore(), nai, networkCapabilities);
                        return;
                    case 528387:
                        ConnectivityService.this.handleUpdateLinkProperties(nai, (LinkProperties) msg.obj);
                        return;
                    case 528388:
                        Integer score = (Integer) msg.obj;
                        if (score != null) {
                            ConnectivityService.this.updateNetworkScore(nai, score.intValue());
                            return;
                        }
                        return;
                    default:
                        return;
                }
            }
        }

        private boolean maybeHandleNetworkMonitorMessage(Message msg) {
            String logMsg;
            int i = msg.what;
            if (i == 532482) {
                NetworkAgentInfo nai = ConnectivityService.this.getNetworkAgentInfoForNetId(msg.arg2);
                if (nai != null) {
                    boolean valid = msg.arg1 == 0;
                    boolean wasValidated = nai.lastValidated;
                    boolean wasDefault = ConnectivityService.this.isDefaultNetwork(nai);
                    String redirectUrl = msg.obj instanceof String ? (String) msg.obj : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    if (!TextUtils.isEmpty(redirectUrl)) {
                        logMsg = " with redirect to " + redirectUrl;
                    } else {
                        logMsg = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(nai.name());
                    sb.append(" validation ");
                    sb.append(valid ? "passed" : "failed");
                    sb.append(logMsg);
                    ConnectivityService.log(sb.toString());
                    if (valid != nai.lastValidated) {
                        if (wasDefault) {
                            ConnectivityService.this.metricsLogger().defaultNetworkMetrics().logDefaultNetworkValidity(SystemClock.elapsedRealtime(), valid);
                        }
                        int oldScore = nai.getCurrentScore();
                        nai.lastValidated = valid;
                        nai.everValidated |= valid;
                        ConnectivityService.this.updateCapabilities(oldScore, nai, nai.networkCapabilities);
                        if (oldScore != nai.getCurrentScore()) {
                            ConnectivityService.this.sendUpdatedScoreToFactories(nai);
                        }
                        if (valid) {
                            ConnectivityService.this.handleFreshlyValidatedNetwork(nai);
                        }
                    }
                    ConnectivityService.this.updateInetCondition(nai);
                    Bundle redirectUrlBundle = new Bundle();
                    redirectUrlBundle.putString(NetworkAgent.REDIRECT_URL_KEY, redirectUrl);
                    nai.asyncChannel.sendMessage(528391, valid ? 1 : 2, 0, redirectUrlBundle);
                    if (wasValidated && !nai.lastValidated) {
                        ConnectivityService.this.handleNetworkUnvalidated(nai);
                    }
                }
            } else if (i == 532490) {
                int netId = msg.arg2;
                boolean visible = ConnectivityService.toBool(msg.arg1);
                NetworkAgentInfo nai2 = ConnectivityService.this.getNetworkAgentInfoForNetId(netId);
                if (nai2 != null && visible != nai2.lastCaptivePortalDetected) {
                    int oldScore2 = nai2.getCurrentScore();
                    nai2.lastCaptivePortalDetected = visible;
                    nai2.everCaptivePortalDetected |= visible;
                    if (!nai2.lastCaptivePortalDetected || 2 != getCaptivePortalMode()) {
                        ConnectivityService.this.updateCapabilities(oldScore2, nai2, nai2.networkCapabilities);
                    } else {
                        ConnectivityService.log("Avoiding captive portal network: " + nai2.name());
                        nai2.asyncChannel.sendMessage(528399);
                        ConnectivityService.this.teardownUnneededNetwork(nai2);
                    }
                }
                if (!visible) {
                    ConnectivityService.this.mNotifier.clearNotification(netId);
                } else if (nai2 == null) {
                    ConnectivityService.loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                } else if (!nai2.networkMisc.provisioningNotificationDisabled) {
                    ConnectivityService.this.mNotifier.showNotification(netId, NetworkNotificationManager.NotificationType.SIGN_IN, nai2, null, (PendingIntent) msg.obj, nai2.networkMisc.explicitlySelected);
                }
            } else if (i != 532494) {
                return false;
            } else {
                NetworkAgentInfo nai3 = ConnectivityService.this.getNetworkAgentInfoForNetId(msg.arg2);
                if (nai3 != null) {
                    ConnectivityService.this.updatePrivateDns(nai3, (DnsManager.PrivateDnsConfig) msg.obj);
                }
            }
            return true;
        }

        private int getCaptivePortalMode() {
            return Settings.Global.getInt(ConnectivityService.this.mContext.getContentResolver(), "captive_portal_mode", 1);
        }

        private boolean maybeHandleNetworkAgentInfoMessage(Message msg) {
            if (msg.what != 1001) {
                return false;
            }
            NetworkAgentInfo nai = (NetworkAgentInfo) msg.obj;
            if (nai != null && ConnectivityService.this.isLiveNetworkAgent(nai, msg.what)) {
                ConnectivityService.this.handleLingerComplete(nai);
                return true;
            }
            return true;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (!maybeHandleAsyncChannelMessage(msg) && !maybeHandleNetworkMonitorMessage(msg) && !maybeHandleNetworkAgentInfoMessage(msg)) {
                maybeHandleNetworkAgentMessage(msg);
            }
        }
    }

    private boolean networkRequiresValidation(NetworkAgentInfo nai) {
        return NetworkMonitor.isValidationRequired(this.mDefaultRequest.networkCapabilities, nai.networkCapabilities);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleFreshlyValidatedNetwork(NetworkAgentInfo nai) {
        if (nai == null) {
            return;
        }
        DnsManager.PrivateDnsConfig cfg = this.mDnsManager.getPrivateDnsConfig();
        if (cfg.useTls && TextUtils.isEmpty(cfg.hostname)) {
            updateDnses(nai.linkProperties, null, nai.network.netId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePrivateDnsSettingsChanged() {
        DnsManager.PrivateDnsConfig cfg = this.mDnsManager.getPrivateDnsConfig();
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            handlePerNetworkPrivateDnsConfig(nai, cfg);
            if (networkRequiresValidation(nai)) {
                handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
            }
        }
    }

    private void handlePerNetworkPrivateDnsConfig(NetworkAgentInfo nai, DnsManager.PrivateDnsConfig cfg) {
        if (networkRequiresValidation(nai)) {
            nai.networkMonitor.notifyPrivateDnsSettingsChanged(cfg);
            updatePrivateDns(nai, cfg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePrivateDns(NetworkAgentInfo nai, DnsManager.PrivateDnsConfig newCfg) {
        this.mDnsManager.updatePrivateDns(nai.network, newCfg);
        updateDnses(nai.linkProperties, null, nai.network.netId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePrivateDnsValidationUpdate(DnsManager.PrivateDnsValidationUpdate update) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetId(update.netId);
        if (nai == null) {
            return;
        }
        this.mDnsManager.updatePrivateDnsValidation(update);
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    private void updateLingerState(NetworkAgentInfo nai, long now) {
        nai.updateLingerTimer();
        if (nai.isLingering() && nai.numForegroundNetworkRequests() > 0) {
            log("Unlingering " + nai.name());
            nai.unlinger();
            logNetworkEvent(nai, 6);
        } else if (unneeded(nai, UnneededFor.LINGER) && nai.getLingerExpiry() > 0) {
            int lingerTime = (int) (nai.getLingerExpiry() - now);
            log("Lingering " + nai.name() + " for " + lingerTime + "ms");
            nai.linger();
            logNetworkEvent(nai, 5);
            notifyNetworkCallbacks(nai, 524291, lingerTime);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAsyncChannelHalfConnect(Message msg) {
        AsyncChannel ac = (AsyncChannel) msg.obj;
        if (this.mNetworkFactoryInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == 0) {
                for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
                    if (!nri.request.isListen()) {
                        NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
                        ac.sendMessage(536576, nai != null ? nai.getCurrentScore() : 0, 0, nri.request);
                    }
                }
                return;
            }
            loge("Error connecting NetworkFactory");
            this.mNetworkFactoryInfos.remove(msg.obj);
        } else if (this.mNetworkAgentInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == 0) {
                this.mNetworkAgentInfos.get(msg.replyTo).asyncChannel.sendMessage(69633);
                return;
            }
            loge("Error connecting NetworkAgent");
            NetworkAgentInfo nai2 = this.mNetworkAgentInfos.remove(msg.replyTo);
            if (nai2 != null) {
                boolean wasDefault = isDefaultNetwork(nai2);
                synchronized (this.mNetworkForNetId) {
                    this.mNetworkForNetId.remove(nai2.network.netId);
                    this.mNetIdInUse.delete(nai2.network.netId);
                }
                this.mLegacyTypeTracker.remove(nai2, wasDefault);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAsyncChannelDisconnected(Message msg) {
        loge("handleAsyncChannelDisconnected()");
        NetworkAgentInfo nai = this.mNetworkAgentInfos.get(msg.replyTo);
        if (nai != null) {
            nai.networkInfo.setPersist(false);
            disconnectAndDestroyNetwork(nai);
            return;
        }
        NetworkFactoryInfo nfi = this.mNetworkFactoryInfos.remove(msg.replyTo);
        if (nfi != null) {
            log("unregisterNetworkFactory for " + nfi.name);
        }
    }

    private void disconnectAndDestroyNetwork(NetworkAgentInfo nai) {
        log(nai.name() + " got DISCONNECTED, was satisfying " + nai.numNetworkRequests());
        NetworkInfo ni = nai.networkInfo;
        LinkProperties lp = nai.linkProperties;
        if (ni.isPersist() && ni.getSubtype() == 3) {
            removeSystemApnNatRules(lp.getInterfaceName());
        }
        if (nai.networkInfo.isConnected()) {
            nai.networkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
        }
        boolean wasDefault = isDefaultNetwork(nai);
        if (wasDefault) {
            this.mDefaultInetConditionPublished = 0;
            long now = SystemClock.elapsedRealtime();
            metricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(now, null, nai);
        }
        notifyIfacesChangedForNetworkStats();
        notifyNetworkCallbacks(nai, 524292);
        this.mKeepaliveTracker.handleStopAllKeepalives(nai, -20);
        for (String iface : nai.linkProperties.getAllInterfaceNames()) {
            wakeupModifyInterface(iface, nai.networkCapabilities, false);
        }
        nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_DISCONNECTED);
        this.mNetworkAgentInfos.remove(nai.messenger);
        nai.maybeStopClat();
        synchronized (this.mNetworkForNetId) {
            this.mNetworkForNetId.remove(nai.network.netId);
        }
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest request = nai.requestAt(i);
            NetworkAgentInfo currentNetwork = getNetworkForRequest(request.requestId);
            if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                clearNetworkForRequest(request.requestId);
                sendUpdatedScoreToFactories(request, 0);
            }
        }
        nai.clearLingerState();
        if (nai.isSatisfyingRequest(this.mDefaultRequest.requestId)) {
            removeDataActivityTracking(nai);
            notifyLockdownVpn(nai);
            int state = this.mSystemProperties.getInt(XP_POWER_STATE, 0);
            if (2 == state) {
                log("go to sleep, try to relase network transition wakelock");
                scheduleReleaseNetworkTransitionWakelock();
            } else {
                ensureNetworkTransitionWakelock(nai.name());
            }
        }
        this.mLegacyTypeTracker.remove(nai, wasDefault);
        if (!nai.networkCapabilities.hasTransport(4)) {
            updateAllVpnsCapabilities();
        }
        rematchAllNetworksAndRequests(null, 0);
        this.mLingerMonitor.noteDisconnect(nai);
        if (nai.created) {
            try {
                this.mNetd.removeNetwork(nai.network.netId);
            } catch (Exception e) {
                loge("Exception removing network: " + e);
            }
            this.mDnsManager.removeNetwork(nai.network);
        }
        synchronized (this.mNetworkForNetId) {
            this.mNetIdInUse.delete(nai.network.netId);
        }
    }

    private NetworkRequestInfo findExistingNetworkRequestInfo(PendingIntent pendingIntent) {
        Intent intent = pendingIntent.getIntent();
        for (Map.Entry<NetworkRequest, NetworkRequestInfo> entry : this.mNetworkRequests.entrySet()) {
            PendingIntent existingPendingIntent = entry.getValue().mPendingIntent;
            if (existingPendingIntent != null && existingPendingIntent.getIntent().filterEquals(intent)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkRequestWithIntent(Message msg) {
        NetworkRequestInfo nri = (NetworkRequestInfo) msg.obj;
        NetworkRequestInfo existingRequest = findExistingNetworkRequestInfo(nri.mPendingIntent);
        if (existingRequest != null) {
            log("Replacing " + existingRequest.request + " with " + nri.request + " because their intents matched.");
            handleReleaseNetworkRequest(existingRequest.request, getCallingUid());
        }
        handleRegisterNetworkRequest(nri);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkRequest(NetworkRequestInfo nri) {
        this.mNetworkRequests.put(nri.request, nri);
        LocalLog localLog = this.mNetworkRequestInfoLogs;
        localLog.log("REGISTER " + nri);
        if (nri.request.isListen()) {
            for (NetworkAgentInfo network : this.mNetworkAgentInfos.values()) {
                if (nri.request.networkCapabilities.hasSignalStrength() && network.satisfiesImmutableCapabilitiesOf(nri.request)) {
                    updateSignalStrengthThresholds(network, "REGISTER", nri.request);
                }
            }
        }
        rematchAllNetworksAndRequests(null, 0);
        if (nri.request.isRequest() && getNetworkForRequest(nri.request.requestId) == null) {
            sendUpdatedScoreToFactories(nri.request, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseNetworkRequestWithIntent(PendingIntent pendingIntent, int callingUid) {
        NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            handleReleaseNetworkRequest(nri.request, callingUid);
        }
    }

    private boolean unneeded(NetworkAgentInfo nai, UnneededFor reason) {
        int numRequests;
        switch (reason) {
            case TEARDOWN:
                numRequests = nai.numRequestNetworkRequests();
                break;
            case LINGER:
                numRequests = nai.numForegroundNetworkRequests();
                break;
            default:
                Slog.wtf(TAG, "Invalid reason. Cannot happen.");
                return true;
        }
        if (!nai.everConnected || nai.isVPN() || nai.isLingering() || numRequests > 0) {
            return false;
        }
        if (nai.networkInfo.isPersist()) {
            log("This Ethernet network " + nai.name() + " is needed!");
            return false;
        }
        for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
            if (reason != UnneededFor.LINGER || !nri.request.isBackgroundRequest()) {
                if (nri.request.isRequest() && nai.satisfies(nri.request) && (nai.isSatisfyingRequest(nri.request.requestId) || getNetworkForRequest(nri.request.requestId).getCurrentScore() < nai.getCurrentScoreAsValidated())) {
                    return false;
                }
            }
        }
        return true;
    }

    private NetworkRequestInfo getNriForAppRequest(NetworkRequest request, int callingUid, String requestedOperation) {
        NetworkRequestInfo nri = this.mNetworkRequests.get(request);
        if (nri != null && 1000 != callingUid && nri.mUid != callingUid) {
            log(String.format("UID %d attempted to %s for unowned request %s", Integer.valueOf(callingUid), requestedOperation, nri));
            return null;
        }
        return nri;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleTimedOutNetworkRequest(NetworkRequestInfo nri) {
        if (this.mNetworkRequests.get(nri.request) == null || getNetworkForRequest(nri.request.requestId) != null) {
            return;
        }
        if (nri.request.isRequest()) {
            log("releasing " + nri.request + " (timeout)");
        }
        handleRemoveNetworkRequest(nri);
        callCallbackForRequest(nri, null, 524293, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseNetworkRequest(NetworkRequest request, int callingUid) {
        NetworkRequestInfo nri = getNriForAppRequest(request, callingUid, "release NetworkRequest");
        if (nri == null) {
            return;
        }
        if (nri.request.isRequest()) {
            log("releasing " + nri.request + " (release request)");
        }
        handleRemoveNetworkRequest(nri);
    }

    private void handleRemoveNetworkRequest(NetworkRequestInfo nri) {
        nri.unlinkDeathRecipient();
        this.mNetworkRequests.remove(nri.request);
        synchronized (this.mUidToNetworkRequestCount) {
            int requests = this.mUidToNetworkRequestCount.get(nri.mUid, 0);
            if (requests < 1) {
                Slog.wtf(TAG, "BUG: too small request count " + requests + " for UID " + nri.mUid);
            } else if (requests == 1) {
                this.mUidToNetworkRequestCount.removeAt(this.mUidToNetworkRequestCount.indexOfKey(nri.mUid));
            } else {
                this.mUidToNetworkRequestCount.put(nri.mUid, requests - 1);
            }
        }
        this.mNetworkRequestInfoLogs.log("RELEASE " + nri);
        if (nri.request.isRequest()) {
            boolean wasKept = false;
            NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
            if (nai != null) {
                boolean wasBackgroundNetwork = nai.isBackgroundNetwork();
                nai.removeRequest(nri.request.requestId);
                updateLingerState(nai, SystemClock.elapsedRealtime());
                if (unneeded(nai, UnneededFor.TEARDOWN)) {
                    log("no live requests for " + nai.name() + "; disconnecting");
                    teardownUnneededNetwork(nai);
                } else {
                    wasKept = true;
                }
                clearNetworkForRequest(nri.request.requestId);
                if (!wasBackgroundNetwork && nai.isBackgroundNetwork()) {
                    updateCapabilities(nai.getCurrentScore(), nai, nai.networkCapabilities);
                }
            }
            for (NetworkAgentInfo otherNai : this.mNetworkAgentInfos.values()) {
                if (otherNai.isSatisfyingRequest(nri.request.requestId) && otherNai != nai) {
                    String str = TAG;
                    StringBuilder sb = new StringBuilder();
                    sb.append("Request ");
                    sb.append(nri.request);
                    sb.append(" satisfied by ");
                    sb.append(otherNai.name());
                    sb.append(", but mNetworkAgentInfos says ");
                    sb.append(nai != null ? nai.name() : "null");
                    Slog.wtf(str, sb.toString());
                }
            }
            if (nri.request.legacyType != -1 && nai != null) {
                boolean i = true;
                if (wasKept) {
                    boolean doRemove = true;
                    for (int i2 = 0; i2 < nai.numNetworkRequests(); i2++) {
                        NetworkRequest otherRequest = nai.requestAt(i2);
                        if (otherRequest.legacyType == nri.request.legacyType && otherRequest.isRequest()) {
                            log(" still have other legacy request - leaving");
                            doRemove = false;
                        }
                    }
                    i = doRemove;
                }
                if (i) {
                    this.mLegacyTypeTracker.remove(nri.request.legacyType, nai, false);
                }
            }
            for (NetworkFactoryInfo nfi : this.mNetworkFactoryInfos.values()) {
                nfi.asyncChannel.sendMessage(536577, nri.request);
            }
            return;
        }
        for (NetworkAgentInfo nai2 : this.mNetworkAgentInfos.values()) {
            nai2.removeRequest(nri.request.requestId);
            if (nri.request.networkCapabilities.hasSignalStrength() && nai2.satisfiesImmutableCapabilitiesOf(nri.request)) {
                updateSignalStrengthThresholds(nai2, "RELEASE", nri.request);
            }
        }
    }

    public void setAcceptUnvalidated(Network network, boolean accept, boolean always) {
        enforceConnectivityInternalPermission();
        this.mHandler.sendMessage(this.mHandler.obtainMessage(28, encodeBool(accept), encodeBool(always), network));
    }

    public void setAvoidUnvalidated(Network network) {
        enforceConnectivityInternalPermission();
        this.mHandler.sendMessage(this.mHandler.obtainMessage(35, network));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSetAcceptUnvalidated(Network network, boolean accept, boolean always) {
        log("handleSetAcceptUnvalidated network=" + network + " accept=" + accept + " always=" + always);
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.everValidated) {
            return;
        }
        if (!nai.networkMisc.explicitlySelected) {
            Slog.wtf(TAG, "BUG: setAcceptUnvalidated non non-explicitly selected network");
        }
        if (accept != nai.networkMisc.acceptUnvalidated) {
            int oldScore = nai.getCurrentScore();
            nai.networkMisc.acceptUnvalidated = accept;
            rematchAllNetworksAndRequests(nai, oldScore);
            sendUpdatedScoreToFactories(nai);
        }
        if (always) {
            nai.asyncChannel.sendMessage(528393, encodeBool(accept));
        }
        if (!accept) {
            nai.asyncChannel.sendMessage(528399);
            teardownUnneededNetwork(nai);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSetAvoidUnvalidated(Network network) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null && !nai.lastValidated && !nai.avoidUnvalidated) {
            int oldScore = nai.getCurrentScore();
            nai.avoidUnvalidated = true;
            rematchAllNetworksAndRequests(nai, oldScore);
            sendUpdatedScoreToFactories(nai);
        }
    }

    private void scheduleUnvalidatedPrompt(NetworkAgentInfo nai) {
        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(29, nai.network), 8000L);
    }

    public void startCaptivePortalApp(final Network network) {
        enforceConnectivityInternalPermission();
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$_3z0y84PR2_gdaCr6y5PLFvhcHo
            @Override // java.lang.Runnable
            public final void run() {
                ConnectivityService.lambda$startCaptivePortalApp$1(ConnectivityService.this, network);
            }
        });
    }

    public static /* synthetic */ void lambda$startCaptivePortalApp$1(ConnectivityService connectivityService, Network network) {
        NetworkAgentInfo nai = connectivityService.getNetworkAgentInfoForNetwork(network);
        if (nai != null && nai.networkCapabilities.hasCapability(17)) {
            nai.networkMonitor.sendMessage(NetworkMonitor.CMD_LAUNCH_CAPTIVE_PORTAL_APP);
        }
    }

    public boolean avoidBadWifi() {
        return this.mMultinetworkPolicyTracker.getAvoidBadWifi();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void rematchForAvoidBadWifiUpdate() {
        rematchAllNetworksAndRequests(null, 0);
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            if (nai.networkCapabilities.hasTransport(1)) {
                sendUpdatedScoreToFactories(nai);
            }
        }
    }

    private void dumpAvoidBadWifiSettings(IndentingPrintWriter pw) {
        String description;
        boolean configRestrict = this.mMultinetworkPolicyTracker.configRestrictsAvoidBadWifi();
        if (!configRestrict) {
            pw.println("Bad Wi-Fi avoidance: unrestricted");
            return;
        }
        pw.println("Bad Wi-Fi avoidance: " + avoidBadWifi());
        pw.increaseIndent();
        pw.println("Config restrict:   " + configRestrict);
        String value = this.mMultinetworkPolicyTracker.getAvoidBadWifiSetting();
        if ("0".equals(value)) {
            description = "get stuck";
        } else if (value == null) {
            description = "prompt";
        } else if ("1".equals(value)) {
            description = "avoid";
        } else {
            description = value + " (?)";
        }
        pw.println("User setting:      " + description);
        pw.println("Network overrides:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            if (nai.avoidUnvalidated) {
                pw.println(nai.name());
            }
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    private void showValidationNotification(NetworkAgentInfo nai, NetworkNotificationManager.NotificationType type) {
        String action;
        switch (type) {
            case NO_INTERNET:
                action = "android.net.conn.PROMPT_UNVALIDATED";
                break;
            case LOST_INTERNET:
                action = "android.net.conn.PROMPT_LOST_VALIDATION";
                break;
            default:
                String str = TAG;
                Slog.wtf(str, "Unknown notification type " + type);
                return;
        }
        Intent intent = new Intent(action);
        intent.setData(Uri.fromParts("netId", Integer.toString(nai.network.netId), null));
        intent.addFlags(268435456);
        intent.setClassName("com.android.settings", "com.android.settings.wifi.WifiNoInternetDialog");
        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 268435456, null, UserHandle.CURRENT);
        this.mNotifier.showNotification(nai.network.netId, type, nai, null, pendingIntent, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePromptUnvalidated(Network network) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.everValidated || nai.everCaptivePortalDetected || !nai.networkMisc.explicitlySelected || nai.networkMisc.acceptUnvalidated) {
            return;
        }
        showValidationNotification(nai, NetworkNotificationManager.NotificationType.NO_INTERNET);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNetworkUnvalidated(NetworkAgentInfo nai) {
        NetworkCapabilities nc = nai.networkCapabilities;
        log("handleNetworkUnvalidated " + nai.name() + " cap=" + nc);
        if (nc.hasTransport(1) && this.mMultinetworkPolicyTracker.shouldNotifyWifiUnvalidated()) {
            showValidationNotification(nai, NetworkNotificationManager.NotificationType.LOST_INTERNET);
        }
    }

    public int getMultipathPreference(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null && nai.networkCapabilities.hasCapability(11)) {
            return 7;
        }
        Integer networkPreference = this.mMultipathPolicyTracker.getMultipathPreference(network);
        if (networkPreference != null) {
            return networkPreference.intValue();
        }
        return this.mMultinetworkPolicyTracker.getMeteredMultipathPreference();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            switch (i) {
                case 8:
                    break;
                case 9:
                    ConnectivityService.this.handleDeprecatedGlobalHttpProxy();
                    return;
                default:
                    switch (i) {
                        case 16:
                            ConnectivityService.this.handleApplyDefaultProxy((ProxyInfo) msg.obj);
                            return;
                        case 17:
                            ConnectivityService.this.handleRegisterNetworkFactory((NetworkFactoryInfo) msg.obj);
                            return;
                        case 18:
                            ConnectivityService.this.handleRegisterNetworkAgent((NetworkAgentInfo) msg.obj);
                            return;
                        case 19:
                        case 21:
                            ConnectivityService.this.handleRegisterNetworkRequest((NetworkRequestInfo) msg.obj);
                            return;
                        case 20:
                            NetworkRequestInfo nri = (NetworkRequestInfo) msg.obj;
                            ConnectivityService.this.handleTimedOutNetworkRequest(nri);
                            return;
                        case 22:
                            ConnectivityService.this.handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1);
                            return;
                        case 23:
                            ConnectivityService.this.handleUnregisterNetworkFactory((Messenger) msg.obj);
                            return;
                        case 24:
                            break;
                        case 25:
                            for (NetworkAgentInfo nai : ConnectivityService.this.mNetworkAgentInfos.values()) {
                                nai.networkMonitor.systemReady = true;
                            }
                            ConnectivityService.this.mMultipathPolicyTracker.start();
                            return;
                        case 26:
                        case 31:
                            ConnectivityService.this.handleRegisterNetworkRequestWithIntent(msg);
                            return;
                        case ConnectivityService.EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT /* 27 */:
                            ConnectivityService.this.handleReleaseNetworkRequestWithIntent((PendingIntent) msg.obj, msg.arg1);
                            return;
                        case 28:
                            Network network = (Network) msg.obj;
                            ConnectivityService.this.handleSetAcceptUnvalidated(network, ConnectivityService.toBool(msg.arg1), ConnectivityService.toBool(msg.arg2));
                            return;
                        case 29:
                            ConnectivityService.this.handlePromptUnvalidated((Network) msg.obj);
                            return;
                        case 30:
                            ConnectivityService.this.handleMobileDataAlwaysOn();
                            return;
                        default:
                            switch (i) {
                                case 35:
                                    ConnectivityService.this.handleSetAvoidUnvalidated((Network) msg.obj);
                                    return;
                                case 36:
                                    ConnectivityService.this.handleReportNetworkConnectivity((Network) msg.obj, msg.arg1, ConnectivityService.toBool(msg.arg2));
                                    return;
                                case 37:
                                    ConnectivityService.this.handlePrivateDnsSettingsChanged();
                                    return;
                                case 38:
                                    ConnectivityService.this.handlePrivateDnsValidationUpdate((DnsManager.PrivateDnsValidationUpdate) msg.obj);
                                    return;
                                default:
                                    switch (i) {
                                        case 528395:
                                            ConnectivityService.this.mKeepaliveTracker.handleStartKeepalive(msg);
                                            return;
                                        case 528396:
                                            NetworkAgentInfo nai2 = ConnectivityService.this.getNetworkAgentInfoForNetwork((Network) msg.obj);
                                            int slot = msg.arg1;
                                            int reason = msg.arg2;
                                            ConnectivityService.this.mKeepaliveTracker.handleStopKeepalive(nai2, slot, reason);
                                            return;
                                        default:
                                            return;
                                    }
                            }
                    }
            }
            ConnectivityService.this.handleReleaseNetworkTransitionWakelock(msg.what);
        }
    }

    public int tether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (isTetheringSupported()) {
            int status = this.mTethering.tether(iface);
            return status;
        }
        return 3;
    }

    public int untether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (isTetheringSupported()) {
            int status = this.mTethering.untether(iface);
            return status;
        }
        return 3;
    }

    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return this.mTethering.getLastTetherError(iface);
        }
        return 3;
    }

    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return this.mTethering.getTetherableUsbRegexs();
        }
        return new String[0];
    }

    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return this.mTethering.getTetherableWifiRegexs();
        }
        return new String[0];
    }

    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return this.mTethering.getTetherableBluetoothRegexs();
        }
        return new String[0];
    }

    public int setUsbTethering(boolean enable, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (isTetheringSupported()) {
            return this.mTethering.setUsbTethering(enable);
        }
        return 3;
    }

    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return this.mTethering.getTetherableIfaces();
    }

    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return this.mTethering.getTetheredIfaces();
    }

    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return this.mTethering.getErroredIfaces();
    }

    public String[] getTetheredDhcpRanges() {
        enforceConnectivityInternalPermission();
        return this.mTethering.getTetheredDhcpRanges();
    }

    public boolean isTetheringSupported(String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        return isTetheringSupported();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isTetheringSupported() {
        int defaultVal = encodeBool(!this.mSystemProperties.get("ro.tether.denied").equals("true"));
        boolean tetherSupported = toBool(Settings.Global.getInt(this.mContext.getContentResolver(), "tether_supported", defaultVal));
        boolean tetherEnabledInSettings = tetherSupported && !this.mUserManager.hasUserRestriction("no_config_tethering");
        long token = Binder.clearCallingIdentity();
        try {
            boolean adminUser = this.mUserManager.isAdminUser();
            return tetherEnabledInSettings && adminUser && this.mTethering.hasTetherableConfiguration();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void startTethering(int type, ResultReceiver receiver, boolean showProvisioningUi, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (!isTetheringSupported()) {
            receiver.send(3, null);
        } else {
            this.mTethering.startTethering(type, receiver, showProvisioningUi);
        }
    }

    public void stopTethering(int type, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        this.mTethering.stopTethering(type);
    }

    private void ensureNetworkTransitionWakelock(String forWhom) {
        synchronized (this) {
            if (this.mNetTransitionWakeLock.isHeld()) {
                return;
            }
            this.mNetTransitionWakeLock.acquire();
            this.mLastWakeLockAcquireTimestamp = SystemClock.elapsedRealtime();
            this.mTotalWakelockAcquisitions++;
            this.mWakelockLogs.log("ACQUIRE for " + forWhom);
            Message msg = this.mHandler.obtainMessage(24);
            this.mHandler.sendMessageDelayed(msg, (long) this.mNetTransitionWakeLockTimeout);
        }
    }

    private void scheduleReleaseNetworkTransitionWakelock() {
        synchronized (this) {
            if (this.mNetTransitionWakeLock.isHeld()) {
                this.mHandler.removeMessages(24);
                Message msg = this.mHandler.obtainMessage(8);
                this.mHandler.sendMessageDelayed(msg, 1000L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseNetworkTransitionWakelock(int eventId) {
        String event = eventName(eventId);
        synchronized (this) {
            if (!this.mNetTransitionWakeLock.isHeld()) {
                this.mWakelockLogs.log(String.format("RELEASE: already released (%s)", event));
                Slog.w(TAG, "expected Net Transition WakeLock to be held");
                return;
            }
            this.mNetTransitionWakeLock.release();
            long lockDuration = SystemClock.elapsedRealtime() - this.mLastWakeLockAcquireTimestamp;
            this.mTotalWakelockDurationMs += lockDuration;
            this.mMaxWakelockDurationMs = Math.max(this.mMaxWakelockDurationMs, lockDuration);
            this.mTotalWakelockReleases++;
            this.mWakelockLogs.log(String.format("RELEASE (%s)", event));
        }
    }

    public void reportInetCondition(int networkType, int percentage) {
        NetworkAgentInfo nai = this.mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) {
            return;
        }
        reportNetworkConnectivity(nai.network, percentage > 50);
    }

    public void reportNetworkConnectivity(Network network, boolean hasConnectivity) {
        enforceAccessPermission();
        enforceInternetPermission();
        int uid = Binder.getCallingUid();
        int connectivityInfo = encodeBool(hasConnectivity);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(36, uid, connectivityInfo, network));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReportNetworkConnectivity(Network network, int uid, boolean hasConnectivity) {
        NetworkAgentInfo nai;
        if (network == null) {
            nai = getDefaultNetwork();
        } else {
            nai = getNetworkAgentInfoForNetwork(network);
        }
        if (nai == null || nai.networkInfo.getState() == NetworkInfo.State.DISCONNECTING || nai.networkInfo.getState() == NetworkInfo.State.DISCONNECTED || hasConnectivity == nai.lastValidated) {
            return;
        }
        int netid = nai.network.netId;
        log("reportNetworkConnectivity(" + netid + ", " + hasConnectivity + ") by " + uid);
        if (!nai.everConnected) {
            return;
        }
        LinkProperties lp = getLinkProperties(nai);
        if (isNetworkWithLinkPropertiesBlocked(lp, uid, false)) {
            return;
        }
        nai.networkMonitor.forceReevaluation(uid);
    }

    private ProxyInfo getDefaultProxy() {
        ProxyInfo ret;
        synchronized (this.mProxyLock) {
            ret = this.mGlobalProxy;
            if (ret == null && !this.mDefaultProxyDisabled) {
                ret = this.mDefaultProxy;
            }
        }
        return ret;
    }

    public ProxyInfo getProxyForNetwork(Network network) {
        NetworkAgentInfo nai;
        if (network == null) {
            return getDefaultProxy();
        }
        ProxyInfo globalProxy = getGlobalProxy();
        if (globalProxy != null) {
            return globalProxy;
        }
        if (NetworkUtils.queryUserAccess(Binder.getCallingUid(), network.netId) && (nai = getNetworkAgentInfoForNetwork(network)) != null) {
            synchronized (nai) {
                ProxyInfo proxyInfo = nai.linkProperties.getHttpProxy();
                if (proxyInfo == null) {
                    return null;
                }
                return new ProxyInfo(proxyInfo);
            }
        }
        return null;
    }

    private ProxyInfo canonicalizeProxyInfo(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())) {
            if (proxy.getPacFileUrl() == null || Uri.EMPTY.equals(proxy.getPacFileUrl())) {
                return null;
            }
            return proxy;
        }
        return proxy;
    }

    private boolean proxyInfoEqual(ProxyInfo a, ProxyInfo b) {
        ProxyInfo a2 = canonicalizeProxyInfo(a);
        ProxyInfo b2 = canonicalizeProxyInfo(b);
        return Objects.equals(a2, b2) && (a2 == null || Objects.equals(a2.getHost(), b2.getHost()));
    }

    public void setGlobalProxy(ProxyInfo proxyProperties) {
        enforceConnectivityInternalPermission();
        synchronized (this.mProxyLock) {
            if (proxyProperties == this.mGlobalProxy) {
                return;
            }
            if (proxyProperties == null || !proxyProperties.equals(this.mGlobalProxy)) {
                if (this.mGlobalProxy == null || !this.mGlobalProxy.equals(proxyProperties)) {
                    String host = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    int port = 0;
                    String exclList = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    String pacFileUrl = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    if (proxyProperties != null && (!TextUtils.isEmpty(proxyProperties.getHost()) || !Uri.EMPTY.equals(proxyProperties.getPacFileUrl()))) {
                        if (!proxyProperties.isValid()) {
                            log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                            return;
                        }
                        this.mGlobalProxy = new ProxyInfo(proxyProperties);
                        host = this.mGlobalProxy.getHost();
                        port = this.mGlobalProxy.getPort();
                        exclList = this.mGlobalProxy.getExclusionListAsString();
                        if (!Uri.EMPTY.equals(proxyProperties.getPacFileUrl())) {
                            pacFileUrl = proxyProperties.getPacFileUrl().toString();
                        }
                    } else {
                        this.mGlobalProxy = null;
                    }
                    ContentResolver res = this.mContext.getContentResolver();
                    long token = Binder.clearCallingIdentity();
                    Settings.Global.putString(res, "global_http_proxy_host", host);
                    Settings.Global.putInt(res, "global_http_proxy_port", port);
                    Settings.Global.putString(res, "global_http_proxy_exclusion_list", exclList);
                    Settings.Global.putString(res, "global_proxy_pac_url", pacFileUrl);
                    Binder.restoreCallingIdentity(token);
                    if (this.mGlobalProxy == null) {
                        proxyProperties = this.mDefaultProxy;
                    }
                    sendProxyBroadcast(proxyProperties);
                }
            }
        }
    }

    private void loadGlobalProxy() {
        ProxyInfo proxyProperties;
        ContentResolver res = this.mContext.getContentResolver();
        String host = Settings.Global.getString(res, "global_http_proxy_host");
        int port = Settings.Global.getInt(res, "global_http_proxy_port", 0);
        String exclList = Settings.Global.getString(res, "global_http_proxy_exclusion_list");
        String pacFileUrl = Settings.Global.getString(res, "global_proxy_pac_url");
        if (!TextUtils.isEmpty(host) || !TextUtils.isEmpty(pacFileUrl)) {
            if (!TextUtils.isEmpty(pacFileUrl)) {
                proxyProperties = new ProxyInfo(pacFileUrl);
            } else {
                proxyProperties = new ProxyInfo(host, port, exclList);
            }
            if (!proxyProperties.isValid()) {
                log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                return;
            }
            synchronized (this.mProxyLock) {
                this.mGlobalProxy = proxyProperties;
            }
        }
    }

    public ProxyInfo getGlobalProxy() {
        ProxyInfo proxyInfo;
        synchronized (this.mProxyLock) {
            proxyInfo = this.mGlobalProxy;
        }
        return proxyInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleApplyDefaultProxy(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost()) && Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            proxy = null;
        }
        synchronized (this.mProxyLock) {
            if (this.mDefaultProxy == null || !this.mDefaultProxy.equals(proxy)) {
                if (this.mDefaultProxy == proxy) {
                    return;
                }
                if (proxy != null && !proxy.isValid()) {
                    log("Invalid proxy properties, ignoring: " + proxy.toString());
                } else if (this.mGlobalProxy != null && proxy != null && !Uri.EMPTY.equals(proxy.getPacFileUrl()) && proxy.getPacFileUrl().equals(this.mGlobalProxy.getPacFileUrl())) {
                    this.mGlobalProxy = proxy;
                    sendProxyBroadcast(this.mGlobalProxy);
                } else {
                    this.mDefaultProxy = proxy;
                    if (this.mGlobalProxy != null) {
                        return;
                    }
                    if (!this.mDefaultProxyDisabled) {
                        sendProxyBroadcast(proxy);
                    }
                }
            }
        }
    }

    private void updateProxy(LinkProperties newLp, LinkProperties oldLp, NetworkAgentInfo nai) {
        ProxyInfo newProxyInfo = newLp == null ? null : newLp.getHttpProxy();
        ProxyInfo oldProxyInfo = oldLp != null ? oldLp.getHttpProxy() : null;
        if (!proxyInfoEqual(newProxyInfo, oldProxyInfo)) {
            sendProxyBroadcast(getDefaultProxy());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDeprecatedGlobalHttpProxy() {
        String proxy = Settings.Global.getString(this.mContext.getContentResolver(), "http_proxy");
        if (!TextUtils.isEmpty(proxy)) {
            String[] data = proxy.split(":");
            if (data.length == 0) {
                return;
            }
            String str = data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            ProxyInfo p = new ProxyInfo(data[0], proxyPort, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            setGlobalProxy(p);
        }
    }

    private void sendProxyBroadcast(ProxyInfo proxy) {
        if (proxy == null) {
            proxy = new ProxyInfo(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, 0, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        }
        if (this.mPacManager.setCurrentProxyScriptUrl(proxy)) {
            return;
        }
        log("sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent("android.intent.action.PROXY_CHANGE");
        intent.addFlags(603979776);
        intent.putExtra("android.intent.extra.PROXY_INFO", proxy);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class SettingsObserver extends ContentObserver {
        private final Context mContext;
        private final Handler mHandler;
        private final HashMap<Uri, Integer> mUriEventMap;

        SettingsObserver(Context context, Handler handler) {
            super(null);
            this.mUriEventMap = new HashMap<>();
            this.mContext = context;
            this.mHandler = handler;
        }

        void observe(Uri uri, int what) {
            this.mUriEventMap.put(uri, Integer.valueOf(what));
            ContentResolver resolver = this.mContext.getContentResolver();
            resolver.registerContentObserver(uri, false, this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            Slog.wtf(ConnectivityService.TAG, "Should never be reached.");
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            Integer what = this.mUriEventMap.get(uri);
            if (what != null) {
                this.mHandler.obtainMessage(what.intValue()).sendToTarget();
                return;
            }
            ConnectivityService.loge("No matching event to send for URI=" + uri);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String s) {
        Slog.d(TAG, s);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void loge(String s) {
        Slog.e(TAG, s);
    }

    private static void loge(String s, Throwable t) {
        Slog.e(TAG, s, t);
    }

    public boolean prepareVpn(String oldPackage, String newPackage, int userId) {
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            Vpn vpn = this.mVpns.get(userId);
            if (vpn != null) {
                return vpn.prepare(oldPackage, newPackage);
            }
            return false;
        }
    }

    public void setVpnPackageAuthorization(String packageName, int userId, boolean authorized) {
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn != null) {
                vpn.setPackageAuthorization(packageName, authorized);
            }
        }
    }

    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        ParcelFileDescriptor establish;
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            establish = this.mVpns.get(user).establish(config);
        }
        return establish;
    }

    public void startLegacyVpn(VpnProfile profile) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        LinkProperties egress = getActiveLinkProperties();
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            this.mVpns.get(user).startLegacyVpn(profile, this.mKeyStore, egress);
        }
    }

    public LegacyVpnInfo getLegacyVpnInfo(int userId) {
        LegacyVpnInfo legacyVpnInfo;
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            legacyVpnInfo = this.mVpns.get(userId).getLegacyVpnInfo();
        }
        return legacyVpnInfo;
    }

    private VpnInfo[] getAllVpnInfo() {
        ensureRunningOnConnectivityServiceThread();
        synchronized (this.mVpns) {
            if (this.mLockdownEnabled) {
                return new VpnInfo[0];
            }
            List<VpnInfo> infoList = new ArrayList<>();
            for (int i = 0; i < this.mVpns.size(); i++) {
                VpnInfo info = createVpnInfo(this.mVpns.valueAt(i));
                if (info != null) {
                    infoList.add(info);
                }
            }
            int i2 = infoList.size();
            return (VpnInfo[]) infoList.toArray(new VpnInfo[i2]);
        }
    }

    private VpnInfo createVpnInfo(Vpn vpn) {
        LinkProperties linkProperties;
        VpnInfo info = vpn.getVpnInfo();
        if (info == null) {
            return null;
        }
        Network[] underlyingNetworks = vpn.getUnderlyingNetworks();
        if (underlyingNetworks == null) {
            NetworkAgentInfo defaultNetwork = getDefaultNetwork();
            if (defaultNetwork != null && defaultNetwork.linkProperties != null) {
                info.primaryUnderlyingIface = getDefaultNetwork().linkProperties.getInterfaceName();
            }
        } else if (underlyingNetworks.length > 0 && (linkProperties = getLinkProperties(underlyingNetworks[0])) != null) {
            info.primaryUnderlyingIface = linkProperties.getInterfaceName();
        }
        if (info.primaryUnderlyingIface == null) {
            return null;
        }
        return info;
    }

    public VpnConfig getVpnConfig(int userId) {
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn != null) {
                return vpn.getVpnConfig();
            }
            return null;
        }
    }

    private void updateAllVpnsCapabilities() {
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (this.mVpns) {
            for (int i = 0; i < this.mVpns.size(); i++) {
                Vpn vpn = this.mVpns.valueAt(i);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
            }
        }
    }

    private void updateVpnCapabilities(Vpn vpn, NetworkCapabilities nc) {
        ensureRunningOnConnectivityServiceThread();
        NetworkAgentInfo vpnNai = getNetworkAgentInfoForNetId(vpn.getNetId());
        if (vpnNai == null || nc == null) {
            return;
        }
        updateCapabilities(vpnNai.getCurrentScore(), vpnNai, nc);
    }

    public boolean updateLockdownVpn() {
        if (Binder.getCallingUid() != 1000) {
            Slog.w(TAG, "Lockdown VPN only available to AID_SYSTEM");
            return false;
        }
        synchronized (this.mVpns) {
            this.mLockdownEnabled = LockdownVpnTracker.isEnabled();
            if (this.mLockdownEnabled) {
                byte[] profileTag = this.mKeyStore.get("LOCKDOWN_VPN");
                if (profileTag == null) {
                    Slog.e(TAG, "Lockdown VPN configured but cannot be read from keystore");
                    return false;
                }
                String profileName = new String(profileTag);
                KeyStore keyStore = this.mKeyStore;
                VpnProfile profile = VpnProfile.decode(profileName, keyStore.get("VPN_" + profileName));
                if (profile == null) {
                    String str = TAG;
                    Slog.e(str, "Lockdown VPN configured invalid profile " + profileName);
                    setLockdownTracker(null);
                    return true;
                }
                int user = UserHandle.getUserId(Binder.getCallingUid());
                Vpn vpn = this.mVpns.get(user);
                if (vpn == null) {
                    String str2 = TAG;
                    Slog.w(str2, "VPN for user " + user + " not ready yet. Skipping lockdown");
                    return false;
                }
                setLockdownTracker(new LockdownVpnTracker(this.mContext, this.mNetd, this, vpn, profile));
            } else {
                setLockdownTracker(null);
            }
            return true;
        }
    }

    @GuardedBy("mVpns")
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        LockdownVpnTracker existing = this.mLockdownTracker;
        this.mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }
        if (tracker != null) {
            this.mLockdownTracker = tracker;
            this.mLockdownTracker.init();
        }
    }

    @GuardedBy("mVpns")
    private void throwIfLockdownEnabled() {
        if (this.mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    private boolean startAlwaysOnVpn(int userId) {
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.wtf(str, "User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.startAlwaysOnVpn();
        }
    }

    public boolean isAlwaysOnVpnPackageSupported(int userId, String packageName) {
        enforceSettingsPermission();
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.w(str, "User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.isAlwaysOnPackageSupported(packageName);
        }
    }

    public boolean setAlwaysOnVpnPackage(int userId, String packageName, boolean lockdown) {
        enforceConnectivityInternalPermission();
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            if (LockdownVpnTracker.isEnabled()) {
                return false;
            }
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.w(str, "User " + userId + " has no Vpn configuration");
                return false;
            } else if (vpn.setAlwaysOnPackage(packageName, lockdown)) {
                if (!startAlwaysOnVpn(userId)) {
                    vpn.setAlwaysOnPackage(null, false);
                    return false;
                }
                return true;
            } else {
                return false;
            }
        }
    }

    public String getAlwaysOnVpnPackage(int userId) {
        enforceConnectivityInternalPermission();
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.w(str, "User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getAlwaysOnPackage();
        }
    }

    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        return -1;
    }

    /* JADX WARN: Code restructure failed: missing block: B:36:0x00a3, code lost:
        if (r0 == null) goto L58;
     */
    /* JADX WARN: Code restructure failed: missing block: B:47:0x00c9, code lost:
        if (r0 == null) goto L58;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.String getProvisioningUrlBaseFromFile() {
        /*
            r9 = this;
            r0 = 0
            r1 = 0
            android.content.Context r2 = r9.mContext
            android.content.res.Resources r2 = r2.getResources()
            android.content.res.Configuration r2 = r2.getConfiguration()
            r3 = 0
            java.io.FileReader r4 = new java.io.FileReader     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            java.io.File r5 = r9.mProvisioningUrlFile     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r4.<init>(r5)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r0 = r4
            org.xmlpull.v1.XmlPullParser r4 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r1 = r4
            r1.setInput(r0)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            java.lang.String r4 = "provisioningUrls"
            com.android.internal.util.XmlUtils.beginDocument(r1, r4)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
        L23:
            com.android.internal.util.XmlUtils.nextElement(r1)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            java.lang.String r4 = r1.getName()     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r4 != 0) goto L34
        L2e:
            r0.close()     // Catch: java.io.IOException -> L32
            goto L33
        L32:
            r4 = move-exception
        L33:
            return r3
        L34:
            java.lang.String r5 = "provisioningUrl"
            boolean r5 = r4.equals(r5)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r5 == 0) goto L8b
            java.lang.String r5 = "mcc"
            java.lang.String r5 = r1.getAttributeValue(r3, r5)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r5 == 0) goto L8a
            int r6 = java.lang.Integer.parseInt(r5)     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            int r7 = r2.mcc     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r6 != r7) goto L8a
            java.lang.String r6 = "mnc"
            java.lang.String r6 = r1.getAttributeValue(r3, r6)     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r6 == 0) goto L8a
            int r7 = java.lang.Integer.parseInt(r6)     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            int r8 = r2.mnc     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            if (r7 != r8) goto L8a
            r1.next()     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            int r7 = r1.getEventType()     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r8 = 4
            if (r7 != r8) goto L8a
            java.lang.String r7 = r1.getText()     // Catch: java.lang.NumberFormatException -> L74 java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r0.close()     // Catch: java.io.IOException -> L72
            goto L73
        L72:
            r3 = move-exception
        L73:
            return r7
        L74:
            r6 = move-exception
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r7.<init>()     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            java.lang.String r8 = "NumberFormatException in getProvisioningUrlBaseFromFile: "
            r7.append(r8)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            r7.append(r6)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            java.lang.String r7 = r7.toString()     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            loge(r7)     // Catch: java.lang.Throwable -> L8c java.io.IOException -> L8e org.xmlpull.v1.XmlPullParserException -> Lab java.io.FileNotFoundException -> Lc3
            goto L8b
        L8a:
        L8b:
            goto L23
        L8c:
            r3 = move-exception
            goto Lcd
        L8e:
            r4 = move-exception
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8c
            r5.<init>()     // Catch: java.lang.Throwable -> L8c
            java.lang.String r6 = "I/O exception reading Carrier Provisioning Urls file: "
            r5.append(r6)     // Catch: java.lang.Throwable -> L8c
            r5.append(r4)     // Catch: java.lang.Throwable -> L8c
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L8c
            loge(r5)     // Catch: java.lang.Throwable -> L8c
            if (r0 == 0) goto Lcc
        La5:
            r0.close()     // Catch: java.io.IOException -> La9
            goto Lcc
        La9:
            r4 = move-exception
            goto Lcc
        Lab:
            r4 = move-exception
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8c
            r5.<init>()     // Catch: java.lang.Throwable -> L8c
            java.lang.String r6 = "Xml parser exception reading Carrier Provisioning Urls file: "
            r5.append(r6)     // Catch: java.lang.Throwable -> L8c
            r5.append(r4)     // Catch: java.lang.Throwable -> L8c
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L8c
            loge(r5)     // Catch: java.lang.Throwable -> L8c
            if (r0 == 0) goto Lcc
            goto La5
        Lc3:
            r4 = move-exception
            java.lang.String r5 = "Carrier Provisioning Urls file not found"
            loge(r5)     // Catch: java.lang.Throwable -> L8c
            if (r0 == 0) goto Lcc
            goto La5
        Lcc:
            return r3
        Lcd:
            if (r0 == 0) goto Ld4
            r0.close()     // Catch: java.io.IOException -> Ld3
            goto Ld4
        Ld3:
            r4 = move-exception
        Ld4:
            throw r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.ConnectivityService.getProvisioningUrlBaseFromFile():java.lang.String");
    }

    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile();
        if (TextUtils.isEmpty(url)) {
            url = this.mContext.getResources().getString(17040338);
            log("getMobileProvisioningUrl: mobile_provisioining_url from resource =" + url);
        } else {
            log("getMobileProvisioningUrl: mobile_provisioning_url from File =" + url);
        }
        if (!TextUtils.isEmpty(url)) {
            String phoneNumber = this.mTelephonyManager.getLine1Number();
            if (TextUtils.isEmpty(phoneNumber)) {
                phoneNumber = "0000000000";
            }
            return String.format(url, this.mTelephonyManager.getSimSerialNumber(), this.mTelephonyManager.getDeviceId(), phoneNumber);
        }
        return url;
    }

    public void setProvisioningNotificationVisible(boolean visible, int networkType, String action) {
        enforceConnectivityInternalPermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        int id = 64512 + networkType + 1;
        try {
            this.mNotifier.setProvNotificationVisible(visible, id, action);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setAirplaneMode(boolean enable) {
        enforceConnectivityInternalPermission();
        long ident = Binder.clearCallingIdentity();
        try {
            ContentResolver cr = this.mContext.getContentResolver();
            Settings.Global.putInt(cr, "airplane_mode_on", encodeBool(enable));
            Intent intent = new Intent("android.intent.action.AIRPLANE_MODE");
            intent.putExtra(AudioService.CONNECT_INTENT_KEY_STATE, enable);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserStart(int userId) {
        synchronized (this.mVpns) {
            Vpn userVpn = this.mVpns.get(userId);
            if (userVpn != null) {
                loge("Starting user already has a VPN");
                return;
            }
            Vpn userVpn2 = new Vpn(this.mHandler.getLooper(), this.mContext, this.mNetd, userId);
            this.mVpns.put(userId, userVpn2);
            if (this.mUserManager.getUserInfo(userId).isPrimary() && LockdownVpnTracker.isEnabled()) {
                updateLockdownVpn();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserStop(int userId) {
        synchronized (this.mVpns) {
            Vpn userVpn = this.mVpns.get(userId);
            if (userVpn == null) {
                loge("Stopped user has no VPN");
                return;
            }
            userVpn.onUserStopped();
            this.mVpns.delete(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserAdded(int userId) {
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (this.mVpns) {
            int vpnsSize = this.mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = this.mVpns.valueAt(i);
                vpn.onUserAdded(userId);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserRemoved(int userId) {
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (this.mVpns) {
            int vpnsSize = this.mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = this.mVpns.valueAt(i);
                vpn.onUserRemoved(userId);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserUnlocked(int userId) {
        synchronized (this.mVpns) {
            if (this.mUserManager.getUserInfo(userId).isPrimary() && LockdownVpnTracker.isEnabled()) {
                updateLockdownVpn();
            } else {
                startAlwaysOnVpn(userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class NetworkFactoryInfo {
        public final AsyncChannel asyncChannel;
        public final Messenger messenger;
        public final String name;

        public NetworkFactoryInfo(String name, Messenger messenger, AsyncChannel asyncChannel) {
            this.name = name;
            this.messenger = messenger;
            this.asyncChannel = asyncChannel;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureNetworkRequestHasType(NetworkRequest request) {
        if (request.type == NetworkRequest.Type.NONE) {
            throw new IllegalArgumentException("All NetworkRequests in ConnectivityService must have a type");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NetworkRequestInfo implements IBinder.DeathRecipient {
        private final IBinder mBinder;
        final PendingIntent mPendingIntent;
        boolean mPendingIntentSent;
        final int mPid;
        final int mUid;
        final Messenger messenger;
        final NetworkRequest request;

        NetworkRequestInfo(NetworkRequest r, PendingIntent pi) {
            this.request = r;
            ConnectivityService.this.ensureNetworkRequestHasType(this.request);
            this.mPendingIntent = pi;
            this.messenger = null;
            this.mBinder = null;
            this.mPid = Binder.getCallingPid();
            this.mUid = Binder.getCallingUid();
            enforceRequestCountLimit();
        }

        NetworkRequestInfo(Messenger m, NetworkRequest r, IBinder binder) {
            this.messenger = m;
            this.request = r;
            ConnectivityService.this.ensureNetworkRequestHasType(this.request);
            this.mBinder = binder;
            this.mPid = Binder.getCallingPid();
            this.mUid = Binder.getCallingUid();
            this.mPendingIntent = null;
            enforceRequestCountLimit();
            try {
                this.mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        private void enforceRequestCountLimit() {
            synchronized (ConnectivityService.this.mUidToNetworkRequestCount) {
                int networkRequests = ConnectivityService.this.mUidToNetworkRequestCount.get(this.mUid, 0) + 1;
                if (networkRequests < 100) {
                    ConnectivityService.this.mUidToNetworkRequestCount.put(this.mUid, networkRequests);
                } else {
                    throw new ServiceSpecificException(1);
                }
            }
        }

        void unlinkDeathRecipient() {
            if (this.mBinder != null) {
                this.mBinder.unlinkToDeath(this, 0);
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            ConnectivityService.log("ConnectivityService NetworkRequestInfo binderDied(" + this.request + ", " + this.mBinder + ")");
            ConnectivityService.this.releaseNetworkRequest(this.request);
        }

        public String toString() {
            String str;
            StringBuilder sb = new StringBuilder();
            sb.append("uid/pid:");
            sb.append(this.mUid);
            sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
            sb.append(this.mPid);
            sb.append(" ");
            sb.append(this.request);
            if (this.mPendingIntent == null) {
                str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            } else {
                str = " to trigger " + this.mPendingIntent;
            }
            sb.append(str);
            return sb.toString();
        }
    }

    private void ensureRequestableCapabilities(NetworkCapabilities networkCapabilities) {
        String badCapability = networkCapabilities.describeFirstNonRequestableCapability();
        if (badCapability != null) {
            throw new IllegalArgumentException("Cannot request network with " + badCapability);
        }
    }

    private void ensureSufficientPermissionsForRequest(NetworkCapabilities nc, int callerPid, int callerUid) {
        if (nc.getSSID() != null && !checkSettingsPermission(callerPid, callerUid)) {
            throw new SecurityException("Insufficient permissions to request a specific SSID");
        }
    }

    private ArrayList<Integer> getSignalStrengthThresholds(NetworkAgentInfo nai) {
        SortedSet<Integer> thresholds = new TreeSet<>();
        synchronized (nai) {
            for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
                if (nri.request.networkCapabilities.hasSignalStrength() && nai.satisfiesImmutableCapabilitiesOf(nri.request)) {
                    thresholds.add(Integer.valueOf(nri.request.networkCapabilities.getSignalStrength()));
                }
            }
        }
        return new ArrayList<>(thresholds);
    }

    private void updateSignalStrengthThresholds(NetworkAgentInfo nai, String reason, NetworkRequest request) {
        String detail;
        ArrayList<Integer> thresholdsArray = getSignalStrengthThresholds(nai);
        Bundle thresholds = new Bundle();
        thresholds.putIntegerArrayList("thresholds", thresholdsArray);
        if (!"CONNECT".equals(reason)) {
            if (request != null && request.networkCapabilities.hasSignalStrength()) {
                detail = reason + " " + request.networkCapabilities.getSignalStrength();
            } else {
                detail = reason;
            }
            log(String.format("updateSignalStrengthThresholds: %s, sending %s to %s", detail, Arrays.toString(thresholdsArray.toArray()), nai.name()));
        }
        nai.asyncChannel.sendMessage(528398, 0, 0, thresholds);
    }

    private void ensureValidNetworkSpecifier(NetworkCapabilities nc) {
        NetworkSpecifier ns;
        if (nc == null || (ns = nc.getNetworkSpecifier()) == null) {
            return;
        }
        MatchAllNetworkSpecifier.checkNotMatchAllNetworkSpecifier(ns);
        ns.assertValidFromUid(Binder.getCallingUid());
    }

    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities, Messenger messenger, int timeoutMs, IBinder binder, int legacyType) {
        NetworkRequest.Type type;
        NetworkCapabilities networkCapabilities2;
        if (networkCapabilities == null) {
            type = NetworkRequest.Type.TRACK_DEFAULT;
        } else {
            type = NetworkRequest.Type.REQUEST;
        }
        if (type == NetworkRequest.Type.TRACK_DEFAULT) {
            networkCapabilities2 = createDefaultNetworkCapabilitiesForUid(Binder.getCallingUid());
            enforceAccessPermission();
        } else {
            networkCapabilities2 = new NetworkCapabilities(networkCapabilities);
            enforceNetworkRequestPermissions(networkCapabilities2);
            enforceMeteredApnPolicy(networkCapabilities2);
        }
        ensureRequestableCapabilities(networkCapabilities2);
        ensureSufficientPermissionsForRequest(networkCapabilities2, Binder.getCallingPid(), Binder.getCallingUid());
        restrictRequestUidsForCaller(networkCapabilities2);
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Bad timeout specified");
        }
        ensureValidNetworkSpecifier(networkCapabilities2);
        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities2, legacyType, nextNetworkRequestId(), type);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder);
        log("requestNetwork for " + nri);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(19, nri));
        if (timeoutMs > 0) {
            this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(20, nri), timeoutMs);
        }
        return networkRequest;
    }

    private void enforceNetworkRequestPermissions(NetworkCapabilities networkCapabilities) {
        if (!networkCapabilities.hasCapability(13)) {
            enforceConnectivityRestrictedNetworksPermission();
        } else {
            enforceChangePermission();
        }
    }

    public boolean requestBandwidthUpdate(Network network) {
        NetworkAgentInfo nai;
        enforceAccessPermission();
        if (network == null) {
            return false;
        }
        synchronized (this.mNetworkForNetId) {
            nai = this.mNetworkForNetId.get(network.netId);
        }
        if (nai == null) {
            return false;
        }
        nai.asyncChannel.sendMessage(528394);
        return true;
    }

    private boolean isSystem(int uid) {
        return uid < 10000;
    }

    private void enforceMeteredApnPolicy(NetworkCapabilities networkCapabilities) {
        int uid = Binder.getCallingUid();
        if (!isSystem(uid) && !networkCapabilities.hasCapability(11) && this.mPolicyManagerInternal.isUidRestrictedOnMeteredNetworks(uid)) {
            networkCapabilities.addCapability(11);
        }
    }

    public NetworkRequest pendingRequestForNetwork(NetworkCapabilities networkCapabilities, PendingIntent operation) {
        Preconditions.checkNotNull(operation, "PendingIntent cannot be null.");
        NetworkCapabilities networkCapabilities2 = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities2);
        enforceMeteredApnPolicy(networkCapabilities2);
        ensureRequestableCapabilities(networkCapabilities2);
        ensureSufficientPermissionsForRequest(networkCapabilities2, Binder.getCallingPid(), Binder.getCallingUid());
        ensureValidNetworkSpecifier(networkCapabilities2);
        restrictRequestUidsForCaller(networkCapabilities2);
        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities2, -1, nextNetworkRequestId(), NetworkRequest.Type.REQUEST);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation);
        log("pendingRequest for " + nri);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(26, nri));
        return networkRequest;
    }

    private void releasePendingNetworkRequestWithDelay(PendingIntent operation) {
        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT, getCallingUid(), 0, operation), this.mReleasePendingIntentDelayMs);
    }

    public void releasePendingNetworkRequest(PendingIntent operation) {
        Preconditions.checkNotNull(operation, "PendingIntent cannot be null.");
        this.mHandler.sendMessage(this.mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT, getCallingUid(), 0, operation));
    }

    private boolean hasWifiNetworkListenPermission(NetworkCapabilities nc) {
        if (nc == null) {
            return false;
        }
        int[] transportTypes = nc.getTransportTypes();
        if (transportTypes.length != 1 || transportTypes[0] != 1) {
            return false;
        }
        try {
            this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_WIFI_STATE", "ConnectivityService");
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public NetworkRequest listenForNetwork(NetworkCapabilities networkCapabilities, Messenger messenger, IBinder binder) {
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }
        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities, Binder.getCallingPid(), Binder.getCallingUid());
        restrictRequestUidsForCaller(nc);
        restrictBackgroundRequestForCaller(nc);
        ensureValidNetworkSpecifier(nc);
        NetworkRequest networkRequest = new NetworkRequest(nc, -1, nextNetworkRequestId(), NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(21, nri));
        return networkRequest;
    }

    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities, PendingIntent operation) {
        Preconditions.checkNotNull(operation, "PendingIntent cannot be null.");
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }
        ensureValidNetworkSpecifier(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities, Binder.getCallingPid(), Binder.getCallingUid());
        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        restrictRequestUidsForCaller(nc);
        NetworkRequest networkRequest = new NetworkRequest(nc, -1, nextNetworkRequestId(), NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(21, nri));
    }

    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        ensureNetworkRequestHasType(networkRequest);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(22, getCallingUid(), 0, networkRequest));
    }

    public void registerNetworkFactory(Messenger messenger, String name) {
        enforceConnectivityInternalPermission();
        NetworkFactoryInfo nfi = new NetworkFactoryInfo(name, messenger, new AsyncChannel());
        this.mHandler.sendMessage(this.mHandler.obtainMessage(17, nfi));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkFactory(NetworkFactoryInfo nfi) {
        log("Got NetworkFactory Messenger for " + nfi.name);
        this.mNetworkFactoryInfos.put(nfi.messenger, nfi);
        nfi.asyncChannel.connect(this.mContext, this.mTrackerHandler, nfi.messenger);
    }

    public void unregisterNetworkFactory(Messenger messenger) {
        enforceConnectivityInternalPermission();
        this.mHandler.sendMessage(this.mHandler.obtainMessage(23, messenger));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUnregisterNetworkFactory(Messenger messenger) {
        NetworkFactoryInfo nfi = this.mNetworkFactoryInfos.remove(messenger);
        if (nfi == null) {
            loge("Failed to find Messenger in unregisterNetworkFactory");
            return;
        }
        log("unregisterNetworkFactory for " + nfi.name);
    }

    private NetworkAgentInfo getNetworkForRequest(int requestId) {
        NetworkAgentInfo networkAgentInfo;
        synchronized (this.mNetworkForRequestId) {
            networkAgentInfo = this.mNetworkForRequestId.get(requestId);
        }
        return networkAgentInfo;
    }

    private void clearNetworkForRequest(int requestId) {
        synchronized (this.mNetworkForRequestId) {
            this.mNetworkForRequestId.remove(requestId);
        }
    }

    private void setNetworkForRequest(int requestId, NetworkAgentInfo nai) {
        synchronized (this.mNetworkForRequestId) {
            this.mNetworkForRequestId.put(requestId, nai);
        }
    }

    private NetworkAgentInfo getDefaultNetwork() {
        return getNetworkForRequest(this.mDefaultRequest.requestId);
    }

    private Network getNetwork(NetworkAgentInfo nai) {
        if (nai != null) {
            return nai.network;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureRunningOnConnectivityServiceThread() {
        if (this.mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException("Not running on ConnectivityService thread: " + Thread.currentThread().getName());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return nai == getDefaultNetwork();
    }

    private boolean isDefaultRequest(NetworkRequestInfo nri) {
        return nri.request.requestId == this.mDefaultRequest.requestId;
    }

    public int registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, int currentScore, NetworkMisc networkMisc) {
        enforceConnectivityInternalPermission();
        LinkProperties lp = new LinkProperties(linkProperties);
        lp.ensureDirectlyConnectedRoutes();
        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(), new Network(reserveNetId()), new NetworkInfo(networkInfo), lp, nc, currentScore, this.mContext, this.mTrackerHandler, new NetworkMisc(networkMisc), this.mDefaultRequest, this);
        nai.networkCapabilities = mixInCapabilities(nai, nc);
        synchronized (this) {
            nai.networkMonitor.systemReady = this.mSystemReady;
        }
        String extraInfo = networkInfo.getExtraInfo();
        String name = TextUtils.isEmpty(extraInfo) ? nai.networkCapabilities.getSSID() : extraInfo;
        addValidationLogs(nai.networkMonitor.getValidationLogs(), nai.network, name);
        loge("registerNetworkAgent " + nai);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(18, nai));
        return nai.network.netId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkAgent(NetworkAgentInfo nai) {
        this.mNetworkAgentInfos.put(nai.messenger, nai);
        synchronized (this.mNetworkForNetId) {
            this.mNetworkForNetId.put(nai.network.netId, nai);
        }
        nai.asyncChannel.connect(this.mContext, this.mTrackerHandler, nai.messenger);
        NetworkInfo networkInfo = nai.networkInfo;
        nai.networkInfo = null;
        updateNetworkInfo(nai, networkInfo);
        updateUids(nai, null, nai.networkCapabilities);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties oldLp) {
        LinkProperties newLp = new LinkProperties(networkAgent.linkProperties);
        int netId = networkAgent.network.netId;
        if (networkAgent.clatd != null) {
            networkAgent.clatd.fixupLinkProperties(oldLp, newLp);
        }
        updateInterfaces(newLp, oldLp, netId, networkAgent.networkCapabilities);
        updateMtu(newLp, oldLp);
        updateTcpBufferSizes(networkAgent);
        updateRoutes(newLp, oldLp, netId);
        updateDnses(newLp, oldLp, netId);
        this.mDnsManager.updatePrivateDnsStatus(netId, newLp);
        networkAgent.updateClat(this.mNetd);
        if (isDefaultNetwork(networkAgent)) {
            handleApplyDefaultProxy(newLp.getHttpProxy());
        } else {
            updateProxy(newLp, oldLp, networkAgent);
        }
        synchronized (networkAgent) {
            networkAgent.linkProperties = newLp;
        }
        if (!Objects.equals(newLp, oldLp)) {
            notifyIfacesChangedForNetworkStats();
            notifyNetworkCallbacks(networkAgent, 524295);
        }
        this.mKeepaliveTracker.handleCheckKeepalivesStillValid(networkAgent);
    }

    private void wakeupModifyInterface(String iface, NetworkCapabilities caps, boolean add) {
        if (!caps.hasTransport(1)) {
            return;
        }
        int mark = this.mContext.getResources().getInteger(17694831);
        int mask = this.mContext.getResources().getInteger(17694832);
        if (mark == 0 || mask == 0) {
            return;
        }
        String prefix = "iface:" + iface;
        try {
            if (add) {
                this.mNetd.getNetdService().wakeupAddInterface(iface, prefix, mark, mask);
            } else {
                this.mNetd.getNetdService().wakeupDelInterface(iface, prefix, mark, mask);
            }
        } catch (Exception e) {
            loge("Exception modifying wakeup packet monitoring: " + e);
        }
    }

    private void updateInterfaces(LinkProperties newLp, LinkProperties oldLp, int netId, NetworkCapabilities caps) {
        LinkProperties.CompareResult<String> interfaceDiff = new LinkProperties.CompareResult<>(oldLp != null ? oldLp.getAllInterfaceNames() : null, newLp != null ? newLp.getAllInterfaceNames() : null);
        for (String iface : interfaceDiff.added) {
            try {
                log("Adding iface " + iface + " to network " + netId);
                this.mNetd.addInterfaceToNetwork(iface, netId);
                wakeupModifyInterface(iface, caps, true);
            } catch (Exception e) {
                loge("Exception adding interface: " + e);
            }
        }
        for (String iface2 : interfaceDiff.removed) {
            try {
                log("Removing iface " + iface2 + " from network " + netId);
                wakeupModifyInterface(iface2, caps, false);
                this.mNetd.removeInterfaceFromNetwork(iface2, netId);
            } catch (Exception e2) {
                loge("Exception removing interface: " + e2);
            }
        }
    }

    private boolean updateRoutes(LinkProperties newLp, LinkProperties oldLp, int netId) {
        LinkProperties.CompareResult<RouteInfo> routeDiff = new LinkProperties.CompareResult<>(oldLp != null ? oldLp.getAllRoutes() : null, newLp != null ? newLp.getAllRoutes() : null);
        for (RouteInfo route : routeDiff.added) {
            if (!route.hasGateway()) {
                try {
                    this.mNetd.addRoute(netId, route);
                } catch (Exception e) {
                    if (route.getDestination().getAddress() instanceof Inet4Address) {
                        loge("Exception in addRoute for non-gateway: " + e);
                    }
                }
            }
        }
        for (RouteInfo route2 : routeDiff.added) {
            if (route2.hasGateway()) {
                try {
                    this.mNetd.addRoute(netId, route2);
                } catch (Exception e2) {
                    if (route2.getGateway() instanceof Inet4Address) {
                        loge("Exception in addRoute for gateway: " + e2);
                    }
                }
            }
        }
        for (RouteInfo route3 : routeDiff.removed) {
            try {
                this.mNetd.removeRoute(netId, route3);
            } catch (Exception e3) {
                loge("Exception in removeRoute: " + e3);
            }
        }
        return (routeDiff.added.isEmpty() && routeDiff.removed.isEmpty()) ? false : true;
    }

    private void updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId) {
        if (oldLp != null && newLp.isIdenticalDnses(oldLp)) {
            return;
        }
        NetworkAgentInfo defaultNai = getDefaultNetwork();
        boolean isDefaultNetwork = defaultNai != null && defaultNai.network.netId == netId;
        Collection<InetAddress> dnses = newLp.getDnsServers();
        log("Setting DNS servers for network " + netId + " to " + dnses);
        try {
            this.mDnsManager.setDnsConfigurationForNetwork(netId, newLp, isDefaultNetwork);
        } catch (Exception e) {
            loge("Exception in setDnsConfigurationForNetwork: " + e);
        }
    }

    private String getNetworkPermission(NetworkCapabilities nc) {
        if (!nc.hasCapability(13)) {
            return "SYSTEM";
        }
        if (!nc.hasCapability(19)) {
            return "NETWORK";
        }
        return null;
    }

    private void removeDefaultRoute(NetworkAgentInfo nai) {
        LinkProperties lp = nai.linkProperties;
        if (nai != null && lp != null) {
            String iface = lp.getInterfaceName();
            if (Boolean.FALSE.equals(this.mDefaultRouteState.get(iface))) {
                log("removeDefaultRoute(): " + iface);
                try {
                    for (RouteInfo ri : lp.getRoutes()) {
                        if (ri.isDefaultRoute()) {
                            log("remove route " + ri);
                            this.mNetd.removeRoute(nai.network.netId, ri);
                            this.mDefaultRouteState.put(iface, true);
                        }
                    }
                } catch (Exception e) {
                    loge("fail to remove default route");
                }
            }
        }
    }

    private NetworkCapabilities mixInCapabilities(NetworkAgentInfo nai, NetworkCapabilities nc) {
        if (nai.everConnected && !nai.isVPN() && !nai.networkCapabilities.satisfiedByImmutableNetworkCapabilities(nc)) {
            String diff = nai.networkCapabilities.describeImmutableDifferences(nc);
            if (!TextUtils.isEmpty(diff)) {
                String str = TAG;
                Slog.wtf(str, "BUG: " + nai + " lost immutable capabilities:" + diff);
            }
        }
        NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (nai.lastValidated) {
            newNc.addCapability(16);
        } else {
            newNc.removeCapability(16);
        }
        if (nai.lastCaptivePortalDetected) {
            newNc.addCapability(17);
        } else {
            newNc.removeCapability(17);
        }
        if (nai.isBackgroundNetwork()) {
            newNc.removeCapability(19);
        } else {
            newNc.addCapability(19);
        }
        if (nai.isSuspended()) {
            newNc.removeCapability(21);
        } else {
            newNc.addCapability(21);
        }
        return newNc;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateCapabilities(int oldScore, NetworkAgentInfo nai, NetworkCapabilities nc) {
        NetworkCapabilities prevNc;
        NetworkCapabilities newNc = mixInCapabilities(nai, nc);
        if (Objects.equals(nai.networkCapabilities, newNc)) {
            return;
        }
        String oldPermission = getNetworkPermission(nai.networkCapabilities);
        String newPermission = getNetworkPermission(newNc);
        if (!Objects.equals(oldPermission, newPermission) && nai.created && !nai.isVPN()) {
            try {
                this.mNetd.setNetworkPermission(nai.network.netId, newPermission);
            } catch (RemoteException e) {
                loge("Exception in setNetworkPermission: " + e);
            }
        }
        synchronized (nai) {
            prevNc = nai.networkCapabilities;
            nai.networkCapabilities = newNc;
        }
        updateUids(nai, prevNc, newNc);
        if (nai.getCurrentScore() == oldScore && newNc.equalRequestableCapabilities(prevNc)) {
            processListenRequests(nai, true);
        } else {
            rematchAllNetworksAndRequests(nai, oldScore);
            notifyNetworkCallbacks(nai, 524294);
        }
        if (prevNc != null) {
            boolean meteredChanged = prevNc.hasCapability(11) != newNc.hasCapability(11);
            boolean roamingChanged = prevNc.hasCapability(18) != newNc.hasCapability(18);
            if (meteredChanged || roamingChanged) {
                notifyIfacesChangedForNetworkStats();
            }
        }
        if (!newNc.hasTransport(4)) {
            updateAllVpnsCapabilities();
        }
        if (newNc.hasTransport(1) && !newNc.hasCapability(12)) {
            removeDefaultRoute(nai);
        }
    }

    private void updateUids(NetworkAgentInfo nai, NetworkCapabilities prevNc, NetworkCapabilities newNc) {
        Set<UidRange> prevRanges = prevNc == null ? null : prevNc.getUids();
        Set<UidRange> newRanges = newNc != null ? newNc.getUids() : null;
        if (prevRanges == null) {
            prevRanges = new ArraySet<>();
        }
        if (newRanges == null) {
            newRanges = new ArraySet<>();
        }
        Set<UidRange> prevRangesCopy = new ArraySet<>(prevRanges);
        prevRanges.removeAll(newRanges);
        newRanges.removeAll(prevRangesCopy);
        try {
            if (!newRanges.isEmpty()) {
                UidRange[] addedRangesArray = new UidRange[newRanges.size()];
                newRanges.toArray(addedRangesArray);
                this.mNetd.addVpnUidRanges(nai.network.netId, addedRangesArray);
            }
            if (!prevRanges.isEmpty()) {
                UidRange[] removedRangesArray = new UidRange[prevRanges.size()];
                prevRanges.toArray(removedRangesArray);
                this.mNetd.removeVpnUidRanges(nai.network.netId, removedRangesArray);
            }
        } catch (Exception e) {
            loge("Exception in updateUids: " + e);
        }
    }

    public void handleUpdateLinkProperties(NetworkAgentInfo nai, LinkProperties newLp) {
        if (getNetworkAgentInfoForNetId(nai.network.netId) != nai) {
            return;
        }
        newLp.ensureDirectlyConnectedRoutes();
        LinkProperties oldLp = nai.linkProperties;
        synchronized (nai) {
            nai.linkProperties = newLp;
        }
        if (nai.everConnected) {
            updateLinkProperties(nai, oldLp);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            if (!nr.isListen()) {
                sendUpdatedScoreToFactories(nr, nai.getCurrentScore());
            }
        }
    }

    private void sendUpdatedScoreToFactories(NetworkRequest networkRequest, int score) {
        for (NetworkFactoryInfo nfi : this.mNetworkFactoryInfos.values()) {
            nfi.asyncChannel.sendMessage(536576, score, 0, networkRequest);
        }
    }

    private void sendPendingIntentForRequest(NetworkRequestInfo nri, NetworkAgentInfo networkAgent, int notificationType) {
        if (notificationType == 524290 && !nri.mPendingIntentSent) {
            Intent intent = new Intent();
            intent.putExtra("android.net.extra.NETWORK", networkAgent.network);
            intent.putExtra("android.net.extra.NETWORK_REQUEST", nri.request);
            nri.mPendingIntentSent = true;
            sendIntent(nri.mPendingIntent, intent);
        }
    }

    private void sendIntent(PendingIntent pendingIntent, Intent intent) {
        this.mPendingIntentWakeLock.acquire();
        try {
            log("Sending " + pendingIntent);
            pendingIntent.send(this.mContext, 0, intent, this, null);
        } catch (PendingIntent.CanceledException e) {
            log(pendingIntent + " was not sent, it had been canceled.");
            this.mPendingIntentWakeLock.release();
            releasePendingNetworkRequest(pendingIntent);
        }
    }

    @Override // android.app.PendingIntent.OnFinished
    public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
        log("Finished sending " + pendingIntent);
        this.mPendingIntentWakeLock.release();
        releasePendingNetworkRequestWithDelay(pendingIntent);
    }

    private void callCallbackForRequest(NetworkRequestInfo nri, NetworkAgentInfo networkAgent, int notificationType, int arg1) {
        if (nri.messenger == null) {
            return;
        }
        Bundle bundle = new Bundle();
        putParcelable(bundle, new NetworkRequest(nri.request));
        Message msg = Message.obtain();
        if (notificationType != 524293) {
            putParcelable(bundle, networkAgent.network);
        }
        switch (notificationType) {
            case 524290:
                putParcelable(bundle, new NetworkCapabilities(networkAgent.networkCapabilities));
                putParcelable(bundle, new LinkProperties(networkAgent.linkProperties));
                break;
            case 524291:
                msg.arg1 = arg1;
                break;
            case 524294:
                NetworkCapabilities nc = networkCapabilitiesRestrictedForCallerPermissions(networkAgent.networkCapabilities, nri.mPid, nri.mUid);
                putParcelable(bundle, nc);
                break;
            case 524295:
                putParcelable(bundle, new LinkProperties(networkAgent.linkProperties));
                break;
        }
        msg.what = notificationType;
        msg.setData(bundle);
        try {
            nri.messenger.send(msg);
        } catch (RemoteException e) {
            loge("RemoteException caught trying to send a callback msg for " + nri.request);
        }
    }

    private static <T extends Parcelable> void putParcelable(Bundle bundle, T t) {
        bundle.putParcelable(t.getClass().getSimpleName(), t);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void teardownUnneededNetwork(NetworkAgentInfo nai) {
        if (nai.numRequestNetworkRequests() != 0) {
            int i = 0;
            while (true) {
                if (i >= nai.numNetworkRequests()) {
                    break;
                }
                NetworkRequest nr = nai.requestAt(i);
                if (nr.isListen()) {
                    i++;
                } else {
                    loge("Dead network still had at least " + nr);
                    break;
                }
            }
        }
        nai.asyncChannel.disconnect();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLingerComplete(NetworkAgentInfo oldNetwork) {
        if (oldNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleLingerComplete");
            return;
        }
        log("handleLingerComplete for " + oldNetwork.name());
        oldNetwork.clearLingerState();
        if (unneeded(oldNetwork, UnneededFor.TEARDOWN)) {
            teardownUnneededNetwork(oldNetwork);
        } else {
            updateCapabilities(oldNetwork.getCurrentScore(), oldNetwork, oldNetwork.networkCapabilities);
        }
    }

    private void makeDefault(NetworkAgentInfo newNetwork) {
        LinkAddress la;
        String str;
        String hostAddress;
        LinkAddress la2;
        InterfaceConfiguration ic;
        LinkAddress la3;
        log("Switching to new default network: " + newNetwork);
        setupDataActivityTracking(newNetwork);
        try {
            this.mNetd.setDefaultNetId(newNetwork.network.netId);
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }
        notifyLockdownVpn(newNetwork);
        handleApplyDefaultProxy(newNetwork.linkProperties.getHttpProxy());
        updateTcpBufferSizes(newNetwork);
        this.mDnsManager.setDefaultDnsSystemProperties(newNetwork.linkProperties.getDnsServers());
        notifyIfacesChangedForNetworkStats();
        updateAllVpnsCapabilities();
        String ifaceName = newNetwork.linkProperties.getInterfaceName();
        SystemProperties.set(SYS_PROP_ACTIVE_NETWORK_IFACE_NAME, ifaceName);
        InterfaceConfiguration ic2 = null;
        try {
            try {
                ic = this.mNetd.getInterfaceConfig(ifaceName);
            } catch (Exception e2) {
                loge("fail to get interface config for " + ifaceName + ": " + e2);
                if (0 == 0 || (la = ic2.getLinkAddress()) == null) {
                    return;
                }
                InetAddress ia = la.getAddress();
                str = SYS_PROP_CURRENT_DEVICE_IP;
                hostAddress = ia != null ? ia.getHostAddress() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            }
            if (ic == null || (la3 = ic.getLinkAddress()) == null) {
                return;
            }
            InetAddress ia2 = la3.getAddress();
            str = SYS_PROP_CURRENT_DEVICE_IP;
            hostAddress = ia2 != null ? ia2.getHostAddress() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            SystemProperties.set(str, hostAddress);
        } catch (Throwable th) {
            if (0 != 0 && (la2 = ic2.getLinkAddress()) != null) {
                InetAddress ia3 = la2.getAddress();
                SystemProperties.set(SYS_PROP_CURRENT_DEVICE_IP, ia3 != null ? ia3.getHostAddress() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            throw th;
        }
    }

    private void processListenRequests(NetworkAgentInfo nai, boolean capabilitiesChanged) {
        for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
            NetworkRequest nr = nri.request;
            if (nr.isListen() && nai.isSatisfyingRequest(nr.requestId) && !nai.satisfies(nr)) {
                nai.removeRequest(nri.request.requestId);
                callCallbackForRequest(nri, nai, 524292, 0);
            }
        }
        if (capabilitiesChanged) {
            notifyNetworkCallbacks(nai, 524294);
        }
        for (NetworkRequestInfo nri2 : this.mNetworkRequests.values()) {
            NetworkRequest nr2 = nri2.request;
            if (nr2.isListen() && nai.satisfies(nr2) && !nai.isSatisfyingRequest(nr2.requestId)) {
                nai.addRequest(nr2);
                notifyNetworkAvailable(nai, nri2);
            }
        }
    }

    /* JADX WARN: Incorrect condition in loop: B:7:0x003b */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void rematchNetworkAndRequests(com.android.server.connectivity.NetworkAgentInfo r26, com.android.server.ConnectivityService.ReapUnvalidatedNetworks r27, long r28) {
        /*
            Method dump skipped, instructions count: 825
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.ConnectivityService.rematchNetworkAndRequests(com.android.server.connectivity.NetworkAgentInfo, com.android.server.ConnectivityService$ReapUnvalidatedNetworks, long):void");
    }

    private void rematchAllNetworksAndRequests(NetworkAgentInfo changed, int oldScore) {
        long now = SystemClock.elapsedRealtime();
        if (changed != null && oldScore < changed.getCurrentScore()) {
            rematchNetworkAndRequests(changed, ReapUnvalidatedNetworks.REAP, now);
            return;
        }
        NetworkAgentInfo[] nais = (NetworkAgentInfo[]) this.mNetworkAgentInfos.values().toArray(new NetworkAgentInfo[this.mNetworkAgentInfos.size()]);
        Arrays.sort(nais);
        int length = nais.length;
        for (int i = 0; i < length; i++) {
            NetworkAgentInfo nai = nais[i];
            rematchNetworkAndRequests(nai, nai != nais[nais.length + (-1)] ? ReapUnvalidatedNetworks.DONT_REAP : ReapUnvalidatedNetworks.REAP, now);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateInetCondition(NetworkAgentInfo nai) {
        if (nai.everValidated && isDefaultNetwork(nai)) {
            int newInetCondition = nai.lastValidated ? 100 : 0;
            if (newInetCondition == this.mDefaultInetConditionPublished) {
                return;
            }
            this.mDefaultInetConditionPublished = newInetCondition;
            sendInetConditionBroadcast(nai.networkInfo);
        }
    }

    private void notifyLockdownVpn(NetworkAgentInfo nai) {
        synchronized (this.mVpns) {
            if (this.mLockdownTracker != null) {
                if (nai != null && nai.isVPN()) {
                    this.mLockdownTracker.onVpnStateChanged(nai.networkInfo);
                } else {
                    this.mLockdownTracker.onNetworkInfoChanged();
                }
            }
        }
    }

    private void addSystemApnNatRules(String ifaceName) {
        this.mSystemApnGids.clear();
        this.mSystemApnGids.add(1000);
        if (SystemApnManager.isSystemApnConfigExist()) {
            this.mSystemApnGids.addAll(this.mSystemApnMgr.getSystemApnAppGids());
        } else {
            this.mSystemApnGids.add(10000);
        }
        this.mSystemApnMgr.addSystemApnRoute(ifaceName, TBOX_ETH_GATEWAY, TBOX_ETH_SECOND_IP);
        Iterator<Integer> it = this.mSystemApnGids.iterator();
        while (it.hasNext()) {
            int gid = it.next().intValue();
            this.mSystemApnMgr.addSystemApnNatRule(ifaceName, TBOX_ETH_SECOND_IP, SYSTEM_APN_MARK, gid);
        }
        String str = TAG;
        Slog.i(str, "addSystemApnRules(): success for " + ifaceName);
    }

    private void removeSystemApnNatRules(String ifaceName) {
        Iterator<Integer> it = this.mSystemApnGids.iterator();
        while (it.hasNext()) {
            int gid = it.next().intValue();
            this.mSystemApnMgr.removeSystemApnNatRule(ifaceName, TBOX_ETH_SECOND_IP, SYSTEM_APN_MARK, gid);
        }
        this.mSystemApnMgr.removeSystemApnRoute(ifaceName, TBOX_ETH_GATEWAY, TBOX_ETH_SECOND_IP);
        String str = TAG;
        Slog.i(str, "removeSystemApnRule(): success for " + ifaceName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo newInfo) {
        NetworkInfo oldInfo;
        int i;
        boolean z;
        NetworkInfo.State state = newInfo.getState();
        int oldScore = networkAgent.getCurrentScore();
        synchronized (networkAgent) {
            oldInfo = networkAgent.networkInfo;
            networkAgent.networkInfo = newInfo;
        }
        notifyLockdownVpn(networkAgent);
        StringBuilder sb = new StringBuilder();
        sb.append(networkAgent.name());
        sb.append(" EVENT_NETWORK_INFO_CHANGED, going from ");
        sb.append(oldInfo == null ? "null" : oldInfo.getState());
        sb.append(" to ");
        sb.append(state);
        log(sb.toString());
        if (!networkAgent.created && (state == NetworkInfo.State.CONNECTED || (state == NetworkInfo.State.CONNECTING && networkAgent.isVPN()))) {
            networkAgent.networkCapabilities.addCapability(19);
            try {
                if (networkAgent.isVPN()) {
                    INetworkManagementService iNetworkManagementService = this.mNetd;
                    int i2 = networkAgent.network.netId;
                    boolean z2 = !networkAgent.linkProperties.getDnsServers().isEmpty();
                    if (networkAgent.networkMisc != null && networkAgent.networkMisc.allowBypass) {
                        z = false;
                        iNetworkManagementService.createVirtualNetwork(i2, z2, z);
                    }
                    z = true;
                    iNetworkManagementService.createVirtualNetwork(i2, z2, z);
                } else {
                    this.mNetd.createPhysicalNetwork(networkAgent.network.netId, getNetworkPermission(networkAgent.networkCapabilities));
                }
                networkAgent.created = true;
            } catch (Exception e) {
                loge("Error creating network " + networkAgent.network.netId + ": " + e.getMessage());
                return;
            }
        }
        if (networkAgent.everConnected || state != NetworkInfo.State.CONNECTED) {
            if (state == NetworkInfo.State.DISCONNECTED) {
                networkAgent.asyncChannel.disconnect();
                if (networkAgent.isVPN()) {
                    synchronized (this.mProxyLock) {
                        if (this.mDefaultProxyDisabled) {
                            this.mDefaultProxyDisabled = false;
                            if (this.mGlobalProxy == null && this.mDefaultProxy != null) {
                                sendProxyBroadcast(this.mDefaultProxy);
                            }
                        }
                    }
                    updateUids(networkAgent, networkAgent.networkCapabilities, null);
                }
                disconnectAndDestroyNetwork(networkAgent);
                return;
            } else if ((oldInfo != null && oldInfo.getState() == NetworkInfo.State.SUSPENDED) || state == NetworkInfo.State.SUSPENDED) {
                if (networkAgent.getCurrentScore() != oldScore) {
                    rematchAllNetworksAndRequests(networkAgent, oldScore);
                }
                updateCapabilities(networkAgent.getCurrentScore(), networkAgent, networkAgent.networkCapabilities);
                if (state == NetworkInfo.State.SUSPENDED) {
                    i = 524297;
                } else {
                    i = 524298;
                }
                notifyNetworkCallbacks(networkAgent, i);
                this.mLegacyTypeTracker.update(networkAgent);
                return;
            } else {
                return;
            }
        }
        networkAgent.everConnected = true;
        handlePerNetworkPrivateDnsConfig(networkAgent, this.mDnsManager.getPrivateDnsConfig());
        updateLinkProperties(networkAgent, null);
        notifyIfacesChangedForNetworkStats();
        networkAgent.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
        scheduleUnvalidatedPrompt(networkAgent);
        if (networkAgent.isVPN()) {
            synchronized (this.mProxyLock) {
                if (!this.mDefaultProxyDisabled) {
                    this.mDefaultProxyDisabled = true;
                    if (this.mGlobalProxy == null && this.mDefaultProxy != null) {
                        sendProxyBroadcast(null);
                    }
                }
            }
        }
        LinkProperties lp = networkAgent.linkProperties;
        if (newInfo.isPersist() && newInfo.getSubtype() == 3) {
            addSystemApnNatRules(lp.getInterfaceName());
        }
        updateSignalStrengthThresholds(networkAgent, "CONNECT", null);
        if (networkAgent.isVPN()) {
            updateAllVpnsCapabilities();
        }
        this.mDefaultRouteState.put(networkAgent.linkProperties.getInterfaceName(), false);
        long now = SystemClock.elapsedRealtime();
        rematchNetworkAndRequests(networkAgent, ReapUnvalidatedNetworks.REAP, now);
        notifyNetworkCallbacks(networkAgent, 524289);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNetworkScore(NetworkAgentInfo nai, int score) {
        if (score < 0) {
            loge("updateNetworkScore for " + nai.name() + " got a negative score (" + score + ").  Bumping score to min of 0");
            score = 0;
        }
        int oldScore = nai.getCurrentScore();
        nai.setCurrentScore(score);
        rematchAllNetworksAndRequests(nai, oldScore);
        sendUpdatedScoreToFactories(nai);
    }

    protected void notifyNetworkAvailable(NetworkAgentInfo nai, NetworkRequestInfo nri) {
        this.mHandler.removeMessages(20, nri);
        if (nri.mPendingIntent != null) {
            sendPendingIntentForRequest(nri, nai, 524290);
        } else {
            callCallbackForRequest(nri, nai, 524290, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, NetworkInfo.DetailedState state, int type) {
        NetworkInfo info = new NetworkInfo(nai.networkInfo);
        info.setType(type);
        if (state != NetworkInfo.DetailedState.DISCONNECTED) {
            info.setDetailedState(state, null, info.getExtraInfo());
            sendConnectedBroadcast(info);
            return;
        }
        info.setDetailedState(state, info.getReason(), info.getExtraInfo());
        Intent intent = new Intent("android.net.conn.CONNECTIVITY_CHANGE");
        intent.putExtra("networkInfo", info);
        intent.putExtra("networkType", info.getType());
        if (info.isFailover()) {
            intent.putExtra("isFailover", true);
            nai.networkInfo.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra("extraInfo", info.getExtraInfo());
        }
        NetworkAgentInfo newDefaultAgent = null;
        if (nai.isSatisfyingRequest(this.mDefaultRequest.requestId)) {
            newDefaultAgent = getDefaultNetwork();
            if (newDefaultAgent != null) {
                intent.putExtra("otherNetwork", newDefaultAgent.networkInfo);
            } else {
                intent.putExtra("noConnectivity", true);
            }
        }
        intent.putExtra("inetCondition", this.mDefaultInetConditionPublished);
        sendStickyBroadcast(intent);
        if (newDefaultAgent != null) {
            sendConnectedBroadcast(newDefaultAgent.networkInfo);
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType, int arg1) {
        for (int i = 0; i < networkAgent.numNetworkRequests(); i++) {
            NetworkRequest nr = networkAgent.requestAt(i);
            NetworkRequestInfo nri = this.mNetworkRequests.get(nr);
            if (nri.mPendingIntent == null) {
                callCallbackForRequest(nri, networkAgent, notifyType, arg1);
            } else {
                sendPendingIntentForRequest(nri, networkAgent, notifyType);
            }
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType) {
        notifyNetworkCallbacks(networkAgent, notifyType, 0);
    }

    private Network[] getDefaultNetworks() {
        ensureRunningOnConnectivityServiceThread();
        ArrayList<Network> defaultNetworks = new ArrayList<>();
        NetworkAgentInfo defaultNetwork = getDefaultNetwork();
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            if (nai.everConnected && (nai == defaultNetwork || nai.isVPN())) {
                defaultNetworks.add(nai.network);
            }
        }
        return (Network[]) defaultNetworks.toArray(new Network[0]);
    }

    private void notifyIfacesChangedForNetworkStats() {
        ensureRunningOnConnectivityServiceThread();
        String activeIface = null;
        LinkProperties activeLinkProperties = getActiveLinkProperties();
        if (activeLinkProperties != null) {
            activeIface = activeLinkProperties.getInterfaceName();
        }
        try {
            this.mStatsService.forceUpdateIfaces(getDefaultNetworks(), getAllVpnInfo(), getAllNetworkState(), activeIface);
        } catch (Exception e) {
        }
    }

    public boolean addVpnAddress(String address, int prefixLength) {
        boolean addAddress;
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            addAddress = this.mVpns.get(user).addAddress(address, prefixLength);
        }
        return addAddress;
    }

    public boolean removeVpnAddress(String address, int prefixLength) {
        boolean removeAddress;
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            removeAddress = this.mVpns.get(user).removeAddress(address, prefixLength);
        }
        return removeAddress;
    }

    public boolean setUnderlyingNetworksForVpn(Network[] networks) {
        boolean success;
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this.mVpns) {
            throwIfLockdownEnabled();
            success = this.mVpns.get(user).setUnderlyingNetworks(networks);
        }
        if (success) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$HR6p9H95BgebyI-3AFU2mC38SI0
                @Override // java.lang.Runnable
                public final void run() {
                    ConnectivityService.lambda$setUnderlyingNetworksForVpn$2(ConnectivityService.this);
                }
            });
        }
        return success;
    }

    public static /* synthetic */ void lambda$setUnderlyingNetworksForVpn$2(ConnectivityService connectivityService) {
        connectivityService.updateAllVpnsCapabilities();
        connectivityService.notifyIfacesChangedForNetworkStats();
    }

    public String getCaptivePortalServerUrl() {
        enforceConnectivityInternalPermission();
        return NetworkMonitor.getCaptivePortalServerHttpUrl(this.mContext);
    }

    public void startNattKeepalive(Network network, int intervalSeconds, Messenger messenger, IBinder binder, String srcAddr, int srcPort, String dstAddr) {
        enforceKeepalivePermission();
        this.mKeepaliveTracker.startNattKeepalive(getNetworkAgentInfoForNetwork(network), intervalSeconds, messenger, binder, srcAddr, srcPort, dstAddr, 4500);
    }

    public void stopKeepalive(Network network, int slot) {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(528396, slot, 0, network));
    }

    public void factoryReset() {
        String[] tetheredIfaces;
        enforceConnectivityInternalPermission();
        if (this.mUserManager.hasUserRestriction("no_network_reset")) {
            return;
        }
        int userId = UserHandle.getCallingUserId();
        setAirplaneMode(false);
        if (!this.mUserManager.hasUserRestriction("no_config_tethering")) {
            String pkgName = this.mContext.getOpPackageName();
            for (String tether : getTetheredIfaces()) {
                untether(tether, pkgName);
            }
        }
        if (!this.mUserManager.hasUserRestriction("no_config_vpn")) {
            synchronized (this.mVpns) {
                String alwaysOnPackage = getAlwaysOnVpnPackage(userId);
                if (alwaysOnPackage != null) {
                    setAlwaysOnVpnPackage(userId, null, false);
                    setVpnPackageAuthorization(alwaysOnPackage, userId, false);
                }
                if (this.mLockdownEnabled && userId == 0) {
                    long ident = Binder.clearCallingIdentity();
                    this.mKeyStore.delete("LOCKDOWN_VPN");
                    this.mLockdownEnabled = false;
                    setLockdownTracker(null);
                    Binder.restoreCallingIdentity(ident);
                }
                VpnConfig vpnConfig = getVpnConfig(userId);
                if (vpnConfig != null) {
                    if (!vpnConfig.legacy) {
                        setVpnPackageAuthorization(vpnConfig.user, userId, false);
                        prepareVpn(null, "[Legacy VPN]", userId);
                    } else {
                        prepareVpn("[Legacy VPN]", "[Legacy VPN]", userId);
                    }
                }
            }
        }
        Settings.Global.putString(this.mContext.getContentResolver(), "network_avoid_bad_wifi", null);
    }

    public byte[] getNetworkWatchlistConfigHash() {
        NetworkWatchlistManager nwm = (NetworkWatchlistManager) this.mContext.getSystemService(NetworkWatchlistManager.class);
        if (nwm == null) {
            loge("Unable to get NetworkWatchlistManager");
            return null;
        }
        return nwm.getWatchlistConfigHash();
    }

    @VisibleForTesting
    public NetworkMonitor createNetworkMonitor(Context context, Handler handler, NetworkAgentInfo nai, NetworkRequest defaultRequest) {
        return new NetworkMonitor(context, handler, nai, defaultRequest);
    }

    @VisibleForTesting
    MultinetworkPolicyTracker createMultinetworkPolicyTracker(Context c, Handler h, Runnable r) {
        return new MultinetworkPolicyTracker(c, h, r);
    }

    @VisibleForTesting
    public WakeupMessage makeWakeupMessage(Context c, Handler h, String s, int cmd, Object obj) {
        return new WakeupMessage(c, h, s, cmd, 0, 0, obj);
    }

    @VisibleForTesting
    public boolean hasService(String name) {
        return ServiceManager.checkService(name) != null;
    }

    @VisibleForTesting
    protected IpConnectivityMetrics.Logger metricsLogger() {
        return (IpConnectivityMetrics.Logger) Preconditions.checkNotNull((IpConnectivityMetrics.Logger) LocalServices.getService(IpConnectivityMetrics.Logger.class), "no IpConnectivityMetrics service");
    }

    private void logNetworkEvent(NetworkAgentInfo nai, int evtype) {
        int[] transports = nai.networkCapabilities.getTransportTypes();
        this.mMetricsLog.log(nai.network.netId, transports, new NetworkEvent(evtype));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean toBool(int encodedBoolean) {
        return encodedBoolean != 0;
    }

    private static int encodeBool(boolean b) {
        return b ? 1 : 0;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* loaded from: classes.dex */
    private class ShellCmd extends ShellCommand {
        private ShellCmd() {
        }

        /* JADX WARN: Removed duplicated region for block: B:14:0x0024 A[Catch: Exception -> 0x006b, TryCatch #0 {Exception -> 0x006b, blocks: (B:6:0x000c, B:14:0x0024, B:16:0x0029, B:18:0x0035, B:20:0x003c, B:22:0x0044, B:25:0x004c, B:29:0x0063, B:31:0x0067, B:9:0x0017), top: B:36:0x000c }] */
        /* JADX WARN: Removed duplicated region for block: B:16:0x0029 A[Catch: Exception -> 0x006b, TryCatch #0 {Exception -> 0x006b, blocks: (B:6:0x000c, B:14:0x0024, B:16:0x0029, B:18:0x0035, B:20:0x003c, B:22:0x0044, B:25:0x004c, B:29:0x0063, B:31:0x0067, B:9:0x0017), top: B:36:0x000c }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public int onCommand(java.lang.String r8) {
            /*
                r7 = this;
                if (r8 != 0) goto L7
                int r0 = r7.handleDefaultCommands(r8)
                return r0
            L7:
                java.io.PrintWriter r0 = r7.getOutPrintWriter()
                r1 = -1
                int r2 = r8.hashCode()     // Catch: java.lang.Exception -> L6b
                r3 = 144736062(0x8a07f3e, float:9.659564E-34)
                r4 = 0
                if (r2 == r3) goto L17
                goto L21
            L17:
                java.lang.String r2 = "airplane-mode"
                boolean r2 = r8.equals(r2)     // Catch: java.lang.Exception -> L6b
                if (r2 == 0) goto L21
                r2 = r4
                goto L22
            L21:
                r2 = r1
            L22:
                if (r2 == 0) goto L29
                int r2 = r7.handleDefaultCommands(r8)     // Catch: java.lang.Exception -> L6b
                return r2
            L29:
                java.lang.String r2 = r7.getNextArg()     // Catch: java.lang.Exception -> L6b
                java.lang.String r3 = "enable"
                boolean r3 = r3.equals(r2)     // Catch: java.lang.Exception -> L6b
                if (r3 == 0) goto L3c
                com.android.server.ConnectivityService r3 = com.android.server.ConnectivityService.this     // Catch: java.lang.Exception -> L6b
                r5 = 1
                r3.setAirplaneMode(r5)     // Catch: java.lang.Exception -> L6b
                return r4
            L3c:
                java.lang.String r3 = "disable"
                boolean r3 = r3.equals(r2)     // Catch: java.lang.Exception -> L6b
                if (r3 == 0) goto L4a
                com.android.server.ConnectivityService r3 = com.android.server.ConnectivityService.this     // Catch: java.lang.Exception -> L6b
                r3.setAirplaneMode(r4)     // Catch: java.lang.Exception -> L6b
                return r4
            L4a:
                if (r2 != 0) goto L67
                com.android.server.ConnectivityService r3 = com.android.server.ConnectivityService.this     // Catch: java.lang.Exception -> L6b
                android.content.Context r3 = com.android.server.ConnectivityService.access$2700(r3)     // Catch: java.lang.Exception -> L6b
                android.content.ContentResolver r3 = r3.getContentResolver()     // Catch: java.lang.Exception -> L6b
                java.lang.String r5 = "airplane_mode_on"
                int r5 = android.provider.Settings.Global.getInt(r3, r5)     // Catch: java.lang.Exception -> L6b
                if (r5 != 0) goto L61
                java.lang.String r6 = "disabled"
                goto L63
            L61:
                java.lang.String r6 = "enabled"
            L63:
                r0.println(r6)     // Catch: java.lang.Exception -> L6b
                return r4
            L67:
                r7.onHelp()     // Catch: java.lang.Exception -> L6b
                return r1
            L6b:
                r2 = move-exception
                r0.println(r2)
                return r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.ConnectivityService.ShellCmd.onCommand(java.lang.String):int");
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Connectivity service commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  airplane-mode [enable|disable]");
            pw.println("    Turn airplane mode on or off.");
            pw.println("  airplane-mode");
            pw.println("    Get airplane mode.");
        }
    }
}
