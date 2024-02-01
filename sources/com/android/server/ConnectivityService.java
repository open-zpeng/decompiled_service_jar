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
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.CaptivePortal;
import android.net.ConnectionInfo;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.IConnectivityManager;
import android.net.IDnsResolver;
import android.net.IIpConnectivityMetrics;
import android.net.INetd;
import android.net.INetdEventCallback;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.ISocketKeepaliveCallback;
import android.net.ITestNetworkManager;
import android.net.ITetheringEventCallback;
import android.net.InetAddresses;
import android.net.IpMemoryStore;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkMonitorManager;
import android.net.NetworkPolicyManager;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStackClient;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.NetworkWatchlistManager;
import android.net.PrivateDnsConfigParcel;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.Uri;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.netlink.InetDiagMessage;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Bundle;
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
import android.system.OsConstants;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.WakeupMessage;
import com.android.internal.util.XmlUtils;
import com.android.server.ConnectivityService;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.AutodestructReference;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.DnsManager;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.KeepaliveTracker;
import com.android.server.connectivity.LingerMonitor;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.MultipathPolicyTracker;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkDiagnostics;
import com.android.server.connectivity.NetworkNotificationManager;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.net.BaseNetdEventCallback;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkStatsFactory;
import com.android.server.pm.DumpState;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.ActivityTaskManagerService;
import com.google.android.collect.Lists;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.input.xpInputActionHandler;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public class ConnectivityService extends IConnectivityManager.Stub implements PendingIntent.OnFinished {
    private static final String ATTR_MCC = "mcc";
    private static final String ATTR_MNC = "mnc";
    private static final boolean DBG = true;
    private static final String DEFAULT_CAPTIVE_PORTAL_HTTP_URL = "http://connectivitycheck.gstatic.com/generate_204";
    private static final int DEFAULT_LINGER_DELAY_MS = 30000;
    @VisibleForTesting
    protected static final String DEFAULT_TCP_BUFFER_SIZES = "4096,87380,110208,4096,16384,110208";
    private static final String DEFAULT_TCP_RWND_KEY = "net.tcp.default_init_rwnd";
    private static final String DIAG_ARG = "--diag";
    private static final String ETH_GATEWAY = "172.20.1.44";
    private static final String ETH_SECONDARY_IP = "172.20.1.3";
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY = 9;
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK = 8;
    private static final int EVENT_CONFIGURE_ALWAYS_ON_NETWORKS = 30;
    private static final int EVENT_DATA_SAVER_CHANGED = 40;
    private static final int EVENT_EXPIRE_NET_TRANSITION_WAKELOCK = 24;
    public static final int EVENT_NETWORK_TESTED = 41;
    public static final int EVENT_PRIVATE_DNS_CONFIG_RESOLVED = 42;
    private static final int EVENT_PRIVATE_DNS_SETTINGS_CHANGED = 37;
    private static final int EVENT_PRIVATE_DNS_VALIDATION_UPDATE = 38;
    private static final int EVENT_PROMPT_UNVALIDATED = 29;
    public static final int EVENT_PROVISIONING_NOTIFICATION = 43;
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
    private static final int EVENT_SET_ACCEPT_PARTIAL_CONNECTIVITY = 45;
    private static final int EVENT_SET_ACCEPT_UNVALIDATED = 28;
    private static final int EVENT_SET_AVOID_UNVALIDATED = 35;
    private static final int EVENT_SYSTEM_READY = 25;
    private static final int EVENT_TIMEOUT_NETWORK_REQUEST = 20;
    public static final int EVENT_TIMEOUT_NOTIFICATION = 44;
    private static final int EVENT_UID_RULES_CHANGED = 39;
    private static final int EVENT_UNREGISTER_NETWORK_FACTORY = 23;
    private static final String FILE_IP_WHITELIST_CONFIG = "/system/etc/ip_whitelist.conf";
    public static final int GID_MONTECARLO = 10000;
    public static final int GID_SYSTEM = 1000;
    private static final String IP_ADDR_MCU = "172.20.1.33";
    private static final String IP_ADDR_TBOX = "172.20.1.44";
    private static final String IP_ADDR_VIRT = "172.20.1.3";
    private static final String IP_ADDR_XPU = "172.20.1.22";
    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    private static final boolean LOGD_BLOCKED_NETWORKINFO = true;
    private static final int MAX_NETWORK_INFO_LOGS = 40;
    private static final int MAX_NETWORK_REQUESTS_PER_UID = 100;
    private static final int MAX_NETWORK_REQUEST_LOGS = 20;
    private static final int MAX_NET_ID = 64511;
    private static final int MAX_WAKELOCK_LOGS = 20;
    private static final int MIN_NET_ID = 100;
    private static final String NETWORK_ARG = "networks";
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    private static final int NETWORK_SUBTYPE_ETH = 1;
    private static final int NETWORK_SUBTYPE_TBOX = 3;
    private static final int PROMPT_UNVALIDATED_DELAY_MS = 8000;
    private static final String PROP_DEFAULT_NETWORK_TYPE = "persist.sys.xp.default_network";
    public static final int PROVISIONING_NOTIFICATION_HIDE = 0;
    public static final int PROVISIONING_NOTIFICATION_SHOW = 1;
    private static final String PROVISIONING_URL_PATH = "/data/misc/radio/provisioning_urls.xml";
    private static final String REQUEST_ARG = "requests";
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 60000;
    public static final String SHORT_ARG = "--short";
    private static final int SYSTEM_APN_MARK = 10008;
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    private static final String TBOX_GATEWAY = "192.168.225.1";
    private static final String TBOX_SECONDARY_IP = "192.168.225.3";
    private static final String TETHERING_ARG = "tethering";
    private static final int TIMEOUT_NOTIFICATION_DELAY_MS = 20000;
    @GuardedBy({"mBandwidthRequests"})
    private final SparseArray<Integer> mBandwidthRequests;
    @GuardedBy({"mBlockedAppUids"})
    private final HashSet<Integer> mBlockedAppUids;
    private final Context mContext;
    private String mCurrentTcpBufferSizes;
    private INetworkManagementEventObserver mDataActivityObserver;
    private int mDefaultInetConditionPublished;
    private final NetworkRequest mDefaultMobileDataRequest;
    private int mDefaultNetSubtype;
    private final NetworkRequest mDefaultRequest;
    private final Map<String, Boolean> mDefaultRouteState;
    private final NetworkRequest mDefaultWifiRequest;
    private final DnsManager mDnsManager;
    @VisibleForTesting
    protected IDnsResolver mDnsResolver;
    private Set<Integer> mGidSet;
    private final InternalHandler mHandler;
    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    private Intent mInitialBroadcast;
    private BroadcastReceiver mIntentReceiver;
    private long mIpConfigFileModifiedTime;
    private final ArrayList<String> mIpWhitelist;
    private KeepaliveTracker mKeepaliveTracker;
    private KeyStore mKeyStore;
    private long mLastWakeLockAcquireTimestamp;
    private final LegacyTypeTracker mLegacyTypeTracker;
    @VisibleForTesting
    protected int mLingerDelayMs;
    private LingerMonitor mLingerMonitor;
    @GuardedBy({"mVpns"})
    private boolean mLockdownEnabled;
    @GuardedBy({"mVpns"})
    private LockdownVpnTracker mLockdownTracker;
    private long mMaxWakelockDurationMs;
    private final IpConnectivityLog mMetricsLog;
    @VisibleForTesting
    final MultinetworkPolicyTracker mMultinetworkPolicyTracker;
    @VisibleForTesting
    final MultipathPolicyTracker mMultipathPolicyTracker;
    private INetworkManagementService mNMS;
    private NetworkConfig[] mNetConfigs;
    @GuardedBy({"mNetworkForNetId"})
    private final SparseBooleanArray mNetIdInUse;
    private PowerManager.WakeLock mNetTransitionWakeLock;
    private int mNetTransitionWakeLockTimeout;
    @VisibleForTesting
    protected INetd mNetd;
    @VisibleForTesting
    protected final INetdEventCallback mNetdEventCallback;
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos;
    private final HashMap<Messenger, NetworkFactoryInfo> mNetworkFactoryInfos;
    @GuardedBy({"mNetworkForNetId"})
    private final SparseArray<NetworkAgentInfo> mNetworkForNetId;
    @GuardedBy({"mNetworkForRequestId"})
    private final SparseArray<NetworkAgentInfo> mNetworkForRequestId;
    private final LocalLog mNetworkInfoBlockingLogs;
    private final LocalLog mNetworkRequestInfoLogs;
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests;
    private int mNetworksDefined;
    private int mNextNetId;
    private int mNextNetworkRequestId;
    private NetworkNotificationManager mNotifier;
    private final PowerManager.WakeLock mPendingIntentWakeLock;
    @VisibleForTesting
    protected final PermissionMonitor mPermissionMonitor;
    private final INetworkPolicyListener mPolicyListener;
    private INetworkPolicyManager mPolicyManager;
    private NetworkPolicyManagerInternal mPolicyManagerInternal;
    private final PriorityDump.PriorityDumper mPriorityDumper;
    private List mProtectedNetworks;
    private final File mProvisioningUrlFile;
    @VisibleForTesting
    protected final ProxyTracker mProxyTracker;
    private final int mReleasePendingIntentDelayMs;
    private boolean mRestrictBackground;
    private final SettingsObserver mSettingsObserver;
    private INetworkStatsService mStatsService;
    private SystemApnManager mSystemApnMgr;
    private MockableSystemProperties mSystemProperties;
    private boolean mSystemReady;
    @GuardedBy({"mTNSLock"})
    private TestNetworkService mTNS;
    private final Object mTNSLock;
    private TelephonyManager mTelephonyManager;
    private Tethering mTethering;
    private int mTotalWakelockAcquisitions;
    private long mTotalWakelockDurationMs;
    private int mTotalWakelockReleases;
    private final NetworkStateTrackerHandler mTrackerHandler;
    private final ConcurrentHashMap<Integer, Boolean> mUidMap;
    private SparseIntArray mUidRules;
    @GuardedBy({"mUidToNetworkRequestCount"})
    private final SparseIntArray mUidToNetworkRequestCount;
    private UserManager mUserManager;
    private BroadcastReceiver mUserPresentReceiver;
    @GuardedBy({"mVpns"})
    @VisibleForTesting
    protected final SparseArray<Vpn> mVpns;
    private final LocalLog mWakelockLogs;
    private static final String TAG = ConnectivityService.class.getSimpleName();
    private static final boolean DDBG = Log.isLoggable(TAG, 3);
    private static final boolean VDBG = Log.isLoggable(TAG, 2);
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

    /* JADX INFO: Access modifiers changed from: private */
    public static String eventName(int what) {
        return sMagicDecoderRing.get(what, Integer.toString(what));
    }

    private static IDnsResolver getDnsResolver() {
        return IDnsResolver.Stub.asInterface(ServiceManager.getService("dnsresolver"));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class LegacyTypeTracker {
        private static final boolean DBG = true;
        private static final boolean VDBG = false;
        private final ConnectivityService mService;
        private final ArrayList<NetworkAgentInfo>[] mTypeLists = new ArrayList[19];

        LegacyTypeTracker(ConnectivityService service) {
            this.mService = service;
        }

        public void addSupportedType(int type) {
            ArrayList<NetworkAgentInfo>[] arrayListArr = this.mTypeLists;
            if (arrayListArr[type] != null) {
                throw new IllegalStateException("legacy list for type " + type + "already initialized");
            }
            arrayListArr[type] = new ArrayList<>();
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
            ConnectivityService.log("Sending " + state + " broadcast for type " + type + " " + nai.name() + " isDefaultNetwork=" + isDefaultNetwork);
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
            boolean isDefaultNetwork = this.mService.isDefaultNetwork(nai);
            if (list.size() == 1 || isDefaultNetwork) {
                maybeLogBroadcast(nai, NetworkInfo.DetailedState.CONNECTED, type, isDefaultNetwork);
                this.mService.sendLegacyNetworkBroadcast(nai, NetworkInfo.DetailedState.CONNECTED, type);
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
                    if (wasFirstNetwork || wasDefault) {
                        maybeLogBroadcast(nai, NetworkInfo.DetailedState.DISCONNECTED, type, wasDefault);
                        this.mService.sendLegacyNetworkBroadcast(nai, NetworkInfo.DetailedState.DISCONNECTED, type);
                    }
                    if (!list.isEmpty() && wasFirstNetwork) {
                        ConnectivityService.log("Other network available for type " + type + ", sending connected broadcast");
                        NetworkAgentInfo replacement = list.get(0);
                        maybeLogBroadcast(replacement, NetworkInfo.DetailedState.CONNECTED, type, this.mService.isDefaultNetwork(replacement));
                        this.mService.sendLegacyNetworkBroadcast(replacement, NetworkInfo.DetailedState.CONNECTED, type);
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
            boolean isDefault = this.mService.isDefaultNetwork(nai);
            NetworkInfo.DetailedState state = nai.networkInfo.getDetailedState();
            int type = 0;
            while (true) {
                ArrayList<NetworkAgentInfo>[] arrayListArr = this.mTypeLists;
                if (type < arrayListArr.length) {
                    ArrayList<NetworkAgentInfo> list = arrayListArr[type];
                    boolean isFirst = true;
                    boolean contains = list != null && list.contains(nai);
                    if (!contains || nai != list.get(0)) {
                        isFirst = false;
                    }
                    if (isFirst || (contains && isDefault)) {
                        maybeLogBroadcast(nai, state, type, isDefault);
                        this.mService.sendLegacyNetworkBroadcast(nai, state, type);
                    }
                    type++;
                } else {
                    return;
                }
            }
        }

        private String naiToString(NetworkAgentInfo nai) {
            String state;
            String name = nai.name();
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
            int type = 0;
            while (true) {
                ArrayList<NetworkAgentInfo>[] arrayListArr = this.mTypeLists;
                if (type >= arrayListArr.length) {
                    break;
                }
                if (arrayListArr[type] != null) {
                    pw.print(" " + type);
                }
                type++;
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
        this(context, netManager, statsService, policyManager, getDnsResolver(), new IpConnectivityLog(), NetdService.getInstance());
    }

    @VisibleForTesting
    protected ConnectivityService(Context context, INetworkManagementService netManager, INetworkStatsService statsService, INetworkPolicyManager policyManager, IDnsResolver dnsresolver, IpConnectivityLog logger, INetd netd) {
        int i;
        String[] naStrings;
        int i2;
        this.mVpns = new SparseArray<>();
        this.mUidRules = new SparseIntArray();
        this.mDefaultInetConditionPublished = 0;
        this.mTNSLock = new Object();
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
        this.mBandwidthRequests = new SparseArray<>(10);
        this.mUidMap = new ConcurrentHashMap<>(100);
        this.mLegacyTypeTracker = new LegacyTypeTracker(this);
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
        this.mNetdEventCallback = new AnonymousClass4();
        this.mPolicyListener = new NetworkPolicyManager.Listener() { // from class: com.android.server.ConnectivityService.5
            public void onUidRulesChanged(int uid, int uidRules) {
                ConnectivityService.this.mHandler.sendMessage(ConnectivityService.this.mHandler.obtainMessage(39, uid, uidRules));
            }

            public void onRestrictBackgroundChanged(boolean restrictBackground) {
                ConnectivityService.log("onRestrictBackgroundChanged(restrictBackground=" + restrictBackground + ")");
                ConnectivityService.this.mHandler.sendMessage(ConnectivityService.this.mHandler.obtainMessage(40, restrictBackground ? 1 : 0, 0));
                if (restrictBackground) {
                    ConnectivityService.log("onRestrictBackgroundChanged(true): disabling tethering");
                    ConnectivityService.this.mTethering.untetherAll();
                }
            }
        };
        this.mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);
        this.mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.ConnectivityService.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                ConnectivityService.this.ensureRunningOnConnectivityServiceThread();
                String action = intent.getAction();
                int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                int uid = intent.getIntExtra("android.intent.extra.UID", -1);
                Uri packageData = intent.getData();
                String packageName = packageData != null ? packageData.getSchemeSpecificPart() : null;
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
                } else if ("android.intent.action.PACKAGE_ADDED".equals(action)) {
                    ConnectivityService.this.onPackageAdded(packageName, uid);
                } else if ("android.intent.action.PACKAGE_REPLACED".equals(action)) {
                    ConnectivityService.this.onPackageReplaced(packageName, uid);
                } else if ("android.intent.action.PACKAGE_REMOVED".equals(action)) {
                    boolean isReplacing = intent.getBooleanExtra("android.intent.extra.REPLACING", false);
                    ConnectivityService.this.onPackageRemoved(packageName, uid, isReplacing);
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
        this.mDefaultWifiRequest = createDefaultInternetRequestForTransport(1, NetworkRequest.Type.BACKGROUND_REQUEST);
        this.mHandlerThread = new HandlerThread("ConnectivityServiceThread");
        this.mHandlerThread.start();
        this.mHandler = new InternalHandler(this.mHandlerThread.getLooper());
        this.mTrackerHandler = new NetworkStateTrackerHandler(this.mHandlerThread.getLooper());
        this.mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(), "connectivity_release_pending_intent_delay_ms", ActivityTaskManagerService.KEY_DISPATCHING_TIMEOUT_MS);
        this.mLingerDelayMs = this.mSystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);
        this.mContext = (Context) Preconditions.checkNotNull(context, "missing Context");
        this.mNMS = (INetworkManagementService) Preconditions.checkNotNull(netManager, "missing INetworkManagementService");
        this.mStatsService = (INetworkStatsService) Preconditions.checkNotNull(statsService, "missing INetworkStatsService");
        this.mPolicyManager = (INetworkPolicyManager) Preconditions.checkNotNull(policyManager, "missing INetworkPolicyManager");
        this.mPolicyManagerInternal = (NetworkPolicyManagerInternal) Preconditions.checkNotNull((NetworkPolicyManagerInternal) LocalServices.getService(NetworkPolicyManagerInternal.class), "missing NetworkPolicyManagerInternal");
        this.mDnsResolver = (IDnsResolver) Preconditions.checkNotNull(dnsresolver, "missing IDnsResolver");
        this.mProxyTracker = makeProxyTracker();
        this.mNetd = netd;
        this.mKeyStore = KeyStore.getInstance();
        this.mTelephonyManager = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        try {
            this.mPolicyManager.registerListener(this.mPolicyListener);
        } catch (RemoteException e) {
            loge("unable to register INetworkPolicyListener" + e);
        }
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mNetTransitionWakeLock = powerManager.newWakeLock(1, TAG);
        this.mNetTransitionWakeLockTimeout = this.mContext.getResources().getInteger(17694855);
        this.mPendingIntentWakeLock = powerManager.newWakeLock(1, TAG);
        this.mNetConfigs = new NetworkConfig[19];
        boolean wifiOnly = this.mSystemProperties.getBoolean("ro.radio.noril", false);
        log("wifiOnly=" + wifiOnly);
        String[] naStrings2 = context.getResources().getStringArray(17236101);
        int i3 = 0;
        for (int length = naStrings2.length; i3 < length; length = i2) {
            String naString = naStrings2[i3];
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (VDBG) {
                    naStrings = naStrings2;
                    try {
                        StringBuilder sb = new StringBuilder();
                        i2 = length;
                        try {
                            sb.append("naString=");
                            sb.append(naString);
                            sb.append(" config=");
                            sb.append(n);
                            log(sb.toString());
                        } catch (Exception e2) {
                        }
                    } catch (Exception e3) {
                        i2 = length;
                    }
                } else {
                    naStrings = naStrings2;
                    i2 = length;
                }
                if (n.type > 18) {
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
            } catch (Exception e4) {
                naStrings = naStrings2;
                i2 = length;
            }
            i3++;
            naStrings2 = naStrings;
        }
        if (this.mNetConfigs[17] == null) {
            this.mLegacyTypeTracker.addSupportedType(17);
            this.mNetworksDefined++;
        }
        if (this.mNetConfigs[9] == null && hasService("ethernet")) {
            this.mLegacyTypeTracker.addSupportedType(9);
            this.mNetworksDefined++;
        }
        if (VDBG) {
            log("mNetworksDefined=" + this.mNetworksDefined);
        }
        this.mProtectedNetworks = new ArrayList();
        int[] protectedNetworks = context.getResources().getIntArray(17236059);
        int length2 = protectedNetworks.length;
        int i4 = 0;
        while (i4 < length2) {
            int p = protectedNetworks[i4];
            if (this.mNetConfigs[p] != null) {
                i = length2;
                if (!this.mProtectedNetworks.contains(Integer.valueOf(p))) {
                    this.mProtectedNetworks.add(Integer.valueOf(p));
                    i4++;
                    length2 = i;
                }
            } else {
                i = length2;
            }
            loge("Ignoring protectedNetwork " + p);
            i4++;
            length2 = i;
        }
        this.mTethering = makeTethering();
        this.mPermissionMonitor = new PermissionMonitor(this.mContext, this.mNetd);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_STARTED");
        intentFilter.addAction("android.intent.action.USER_STOPPED");
        intentFilter.addAction("android.intent.action.USER_ADDED");
        intentFilter.addAction("android.intent.action.USER_REMOVED");
        intentFilter.addAction("android.intent.action.USER_UNLOCKED");
        this.mContext.registerReceiverAsUser(this.mIntentReceiver, UserHandle.ALL, intentFilter, null, this.mHandler);
        this.mContext.registerReceiverAsUser(this.mUserPresentReceiver, UserHandle.SYSTEM, new IntentFilter("android.intent.action.USER_PRESENT"), null, null);
        IntentFilter intentFilter2 = new IntentFilter();
        intentFilter2.addAction("android.intent.action.PACKAGE_ADDED");
        intentFilter2.addAction("android.intent.action.PACKAGE_REPLACED");
        intentFilter2.addAction("android.intent.action.PACKAGE_REMOVED");
        intentFilter2.addDataScheme("package");
        this.mContext.registerReceiverAsUser(this.mIntentReceiver, UserHandle.ALL, intentFilter2, null, this.mHandler);
        try {
            this.mNMS.registerObserver(this.mTethering);
            this.mNMS.registerObserver(this.mDataActivityObserver);
        } catch (RemoteException e5) {
            loge("Error registering observer :" + e5);
        }
        this.mSettingsObserver = new SettingsObserver(this.mContext, this.mHandler);
        registerSettingsCallbacks();
        DataConnectionStats dataConnectionStats = new DataConnectionStats(this.mContext);
        dataConnectionStats.startMonitoring();
        this.mUserManager = (UserManager) context.getSystemService("user");
        this.mKeepaliveTracker = new KeepaliveTracker(this.mContext, this.mHandler);
        Context context2 = this.mContext;
        this.mNotifier = new NetworkNotificationManager(context2, this.mTelephonyManager, (NotificationManager) context2.getSystemService(NotificationManager.class));
        int dailyLimit = Settings.Global.getInt(this.mContext.getContentResolver(), "network_switch_notification_daily_limit", 3);
        long rateLimit = Settings.Global.getLong(this.mContext.getContentResolver(), "network_switch_notification_rate_limit_millis", 60000L);
        this.mLingerMonitor = new LingerMonitor(this.mContext, this.mNotifier, dailyLimit, rateLimit);
        this.mMultinetworkPolicyTracker = createMultinetworkPolicyTracker(this.mContext, this.mHandler, new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$SFqiR4Pfksb1C7csMC3uNxCllR8
            @Override // java.lang.Runnable
            public final void run() {
                ConnectivityService.this.lambda$new$0$ConnectivityService();
            }
        });
        this.mMultinetworkPolicyTracker.start();
        this.mMultipathPolicyTracker = new MultipathPolicyTracker(this.mContext, this.mHandler);
        this.mDnsManager = new DnsManager(this.mContext, this.mDnsResolver, this.mSystemProperties);
        registerPrivateDnsSettingsCallbacks();
        this.mDefaultNetSubtype = SystemProperties.getInt(PROP_DEFAULT_NETWORK_TYPE, 3);
        this.mDefaultRouteState = new HashMap();
        this.mGidSet = new HashSet();
        this.mSystemApnMgr = new SystemApnManager(context);
        this.mIpWhitelist = new ArrayList<>();
        this.mIpWhitelist.add("172.20.1.44");
        this.mIpWhitelist.add(IP_ADDR_MCU);
        this.mIpWhitelist.add(IP_ADDR_XPU);
        this.mIpWhitelist.add("172.20.1.3");
        this.mIpConfigFileModifiedTime = 0L;
        if (isIpConfigFileExists()) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$r0bbC3p3szQ3H6uw5vpGkFCJa80
                @Override // java.lang.Runnable
                public final void run() {
                    ConnectivityService.this.lambda$new$1$ConnectivityService();
                }
            });
        }
    }

    public /* synthetic */ void lambda$new$1$ConnectivityService() {
        readIpWhitelist(FILE_IP_WHITELIST_CONFIG);
    }

    @VisibleForTesting
    protected Tethering makeTethering() {
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
        return new Tethering(this.mContext, this.mNMS, this.mStatsService, this.mPolicyManager, IoThread.get().getLooper(), new MockableSystemProperties(), deps);
    }

    @VisibleForTesting
    protected ProxyTracker makeProxyTracker() {
        return new ProxyTracker(this.mContext, this.mHandler, 16);
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
    void updateAlwaysOnNetworks() {
        this.mHandler.sendEmptyMessage(30);
    }

    @VisibleForTesting
    void updatePrivateDnsSettings() {
        this.mHandler.sendEmptyMessage(37);
    }

    private void handleAlwaysOnNetworkRequest(NetworkRequest networkRequest, String settingName, boolean defaultValue) {
        boolean enable = toBool(Settings.Global.getInt(this.mContext.getContentResolver(), settingName, encodeBool(defaultValue)));
        boolean isEnabled = this.mNetworkRequests.get(networkRequest) != null;
        if (enable == isEnabled) {
            return;
        }
        if (enable) {
            handleRegisterNetworkRequest(new NetworkRequestInfo(null, networkRequest, new Binder()));
        } else {
            handleReleaseNetworkRequest(networkRequest, 1000, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleConfigureAlwaysOnNetworks() {
        handleAlwaysOnNetworkRequest(this.mDefaultMobileDataRequest, "mobile_data_always_on", true);
        handleAlwaysOnNetworkRequest(this.mDefaultWifiRequest, "wifi_always_requested", false);
    }

    private void registerSettingsCallbacks() {
        this.mSettingsObserver.observe(Settings.Global.getUriFor("http_proxy"), 9);
        this.mSettingsObserver.observe(Settings.Global.getUriFor("mobile_data_always_on"), 30);
        this.mSettingsObserver.observe(Settings.Global.getUriFor("wifi_always_requested"), 30);
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
            }
            throw new IllegalStateException("No free netIds");
        }
    }

    private NetworkState getFilteredNetworkState(int networkType, int uid) {
        NetworkState state;
        if (this.mLegacyTypeTracker.isTypeSupported(networkType)) {
            NetworkAgentInfo nai = this.mLegacyTypeTracker.getNetworkForType(networkType);
            if (nai != null) {
                state = nai.getNetworkState();
                state.networkInfo.setType(networkType);
            } else {
                NetworkInfo info = new NetworkInfo(networkType, 0, ConnectivityManager.getNetworkTypeName(networkType), "");
                info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
                info.setIsAvailable(true);
                NetworkCapabilities capabilities = new NetworkCapabilities();
                capabilities.setCapability(18, true ^ info.isRoaming());
                state = new NetworkState(info, new LinkProperties(), capabilities, (Network) null, (String) null, (String) null);
            }
            filterNetworkStateForUid(state, uid, false);
            return state;
        }
        return NetworkState.EMPTY;
    }

    @VisibleForTesting
    protected NetworkAgentInfo getNetworkAgentInfoForNetwork(Network network) {
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
        if (ignoreBlocked) {
            return false;
        }
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(UserHandle.getUserId(uid));
            if (vpn != null && vpn.getLockdown() && vpn.isBlockingUid(uid)) {
                return true;
            }
            String iface = lp == null ? "" : lp.getInterfaceName();
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

    private void maybeLogBlockedStatusChanged(NetworkRequestInfo nri, Network net, boolean blocked) {
        if (nri == null || net == null) {
            return;
        }
        String action = blocked ? "BLOCKED" : "UNBLOCKED";
        log(String.format("Blocked status changed to %s for %d(%d) on netId %d", Boolean.valueOf(blocked), Integer.valueOf(nri.mUid), Integer.valueOf(nri.request.requestId), Integer.valueOf(net.netId)));
        LocalLog localLog = this.mNetworkInfoBlockingLogs;
        localLog.log(action + " " + nri.mUid);
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
        boolean ret = false;
        Boolean cached = this.mUidMap.get(Integer.valueOf(uid));
        if (cached != null) {
            return cached.booleanValue();
        }
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
                    ret = true;
                }
            }
        } catch (Exception e) {
            loge("Error get xpPackageInfo" + e);
        }
        this.mUidMap.put(Integer.valueOf(uid), Boolean.valueOf(ret));
        return ret;
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
        NetworkState state2 = getFilteredNetworkState(networkType, uid);
        if (state2.networkInfo == null && networkType == 0 && is3rdApp(uid)) {
            NetworkState fakeState = getFilteredNetworkState(9, uid);
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
        for (int networkType = 0; networkType <= 18; networkType++) {
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
        NetworkState state = getFilteredNetworkState(networkType, uid);
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
        if (newNc.getNetworkSpecifier() != null) {
            newNc.setNetworkSpecifier(newNc.getNetworkSpecifier().redact());
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
        NetworkCapabilities caps = getNetworkCapabilities(getActiveNetwork());
        if (caps != null) {
            return true ^ caps.hasCapability(11);
        }
        return true;
    }

    private boolean disallowedBecauseSystemCaller() {
        if (!isSystem(Binder.getCallingUid()) || SystemProperties.getInt("ro.product.first_api_level", 0) <= EVENT_SET_ACCEPT_UNVALIDATED) {
            return false;
        }
        log("This method exists only for app backwards compatibility and must not be called by system services.");
        return true;
    }

    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        NetworkInfo.DetailedState netState;
        LinkProperties lp;
        int netId;
        if (disallowedBecauseSystemCaller()) {
            return false;
        }
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
                if (VDBG) {
                    log("requestRouteToHostAddress on down network (" + networkType + ") - dropped netState=" + netState);
                }
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
            this.mNMS.addLegacyRouteForNetId(netId, bestRoute, uid);
            return true;
        } catch (Exception e) {
            loge("Exception trying to add a route: " + e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.ConnectivityService$4  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass4 extends BaseNetdEventCallback {
        AnonymousClass4() {
        }

        public void onPrivateDnsValidationEvent(int netId, String ipAddress, String hostname, boolean validated) {
            try {
                ConnectivityService.this.mHandler.sendMessage(ConnectivityService.this.mHandler.obtainMessage(38, new DnsManager.PrivateDnsValidationUpdate(netId, InetAddress.parseNumericAddress(ipAddress), hostname, validated)));
            } catch (IllegalArgumentException e) {
                ConnectivityService.loge("Error parsing ip address in validation event");
            }
        }

        public void onDnsEvent(int netId, int eventType, int returnCode, String hostname, String[] ipAddresses, int ipAddressesCount, long timestamp, int uid) {
            NetworkAgentInfo nai = ConnectivityService.this.getNetworkAgentInfoForNetId(netId);
            if (nai != null && nai.satisfies(ConnectivityService.this.mDefaultRequest)) {
                nai.networkMonitor().notifyDnsResponse(returnCode);
            }
        }

        public /* synthetic */ void lambda$onNat64PrefixEvent$0$ConnectivityService$4(int netId, boolean added, String prefixString, int prefixLength) {
            ConnectivityService.this.handleNat64PrefixEvent(netId, added, prefixString, prefixLength);
        }

        public void onNat64PrefixEvent(final int netId, final boolean added, final String prefixString, final int prefixLength) {
            ConnectivityService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$4$kjr9gauOtOpxwsI0DG7Gt6Wd1hI
                @Override // java.lang.Runnable
                public final void run() {
                    ConnectivityService.AnonymousClass4.this.lambda$onNat64PrefixEvent$0$ConnectivityService$4(netId, added, prefixString, prefixLength);
                }
            });
        }
    }

    @VisibleForTesting
    protected void registerNetdEventCallback() {
        IIpConnectivityMetrics ipConnectivityMetrics = IIpConnectivityMetrics.Stub.asInterface(ServiceManager.getService("connmetrics"));
        if (ipConnectivityMetrics == null) {
            Slog.wtf(TAG, "Missing IIpConnectivityMetrics");
            return;
        }
        try {
            ipConnectivityMetrics.addNetdEventCallback(0, this.mNetdEventCallback);
        } catch (Exception e) {
            loge("Error registering netd callback: " + e);
        }
    }

    void handleUidRulesChanged(int uid, int newRules) {
        int oldRules = this.mUidRules.get(uid, 0);
        if (oldRules == newRules) {
            return;
        }
        maybeNotifyNetworkBlockedForNewUidRules(uid, newRules);
        if (newRules == 0) {
            this.mUidRules.delete(uid);
        } else {
            this.mUidRules.put(uid, newRules);
        }
    }

    void handleRestrictBackgroundChanged(boolean restrictBackground) {
        if (this.mRestrictBackground == restrictBackground) {
            return;
        }
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            boolean curMetered = nai.networkCapabilities.isMetered();
            maybeNotifyNetworkBlocked(nai, curMetered, curMetered, this.mRestrictBackground, restrictBackground);
        }
        this.mRestrictBackground = restrictBackground;
    }

    private boolean isUidNetworkingWithVpnBlocked(int uid, int uidRules, boolean isNetworkMetered, boolean isBackgroundRestricted) {
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(UserHandle.getUserId(uid));
            if (vpn != null && vpn.getLockdown() && vpn.isBlockingUid(uid)) {
                return true;
            }
            NetworkPolicyManagerInternal networkPolicyManagerInternal = this.mPolicyManagerInternal;
            return NetworkPolicyManagerInternal.isUidNetworkingBlocked(uid, uidRules, isNetworkMetered, isBackgroundRestricted);
        }
    }

    private void enforceCrossUserPermission(int userId) {
        if (userId == UserHandle.getCallingUserId()) {
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "ConnectivityService");
    }

    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (this.mContext.checkCallingOrSelfPermission(permission) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean checkAnyPermissionOf(int pid, int uid, String... permissions) {
        for (String permission : permissions) {
            if (this.mContext.checkPermission(permission, pid, uid) == 0) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: " + String.join(", ", permissions) + ".");
        }
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

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceSettingsPermission() {
        enforceAnyPermissionOf("android.permission.NETWORK_SETTINGS", "android.permission.MAINLINE_NETWORK_STACK");
    }

    private boolean checkSettingsPermission() {
        return checkAnyPermissionOf("android.permission.NETWORK_SETTINGS", "android.permission.MAINLINE_NETWORK_STACK");
    }

    private boolean checkSettingsPermission(int pid, int uid) {
        return this.mContext.checkPermission("android.permission.NETWORK_SETTINGS", pid, uid) == 0 || this.mContext.checkPermission("android.permission.MAINLINE_NETWORK_STACK", pid, uid) == 0;
    }

    private void enforceTetherAccessPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE", "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        enforceAnyPermissionOf("android.permission.CONNECTIVITY_INTERNAL", "android.permission.MAINLINE_NETWORK_STACK");
    }

    private void enforceControlAlwaysOnVpnPermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CONTROL_ALWAYS_ON_VPN", "ConnectivityService");
    }

    private void enforceNetworkStackSettingsOrSetup() {
        enforceAnyPermissionOf("android.permission.NETWORK_SETTINGS", "android.permission.NETWORK_SETUP_WIZARD", "android.permission.NETWORK_STACK", "android.permission.MAINLINE_NETWORK_STACK");
    }

    private boolean checkNetworkStackPermission() {
        return checkAnyPermissionOf("android.permission.NETWORK_STACK", "android.permission.MAINLINE_NETWORK_STACK");
    }

    private boolean checkNetworkSignalStrengthWakeupPermission(int pid, int uid) {
        return checkAnyPermissionOf(pid, uid, "android.permission.NETWORK_SIGNAL_STRENGTH_WAKEUP", "android.permission.MAINLINE_NETWORK_STACK");
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
        Intent intent = new Intent("android.net.conn.DATA_ACTIVITY_CHANGE");
        intent.putExtra("deviceType", deviceType);
        intent.putExtra("isActive", active);
        intent.putExtra("tsNanos", tsNanos);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, "android.permission.RECEIVE_DATA_ACTIVITY_CHANGE", null, null, 0, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized (this) {
            if (!this.mSystemReady && intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE")) {
                this.mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(67108864);
            if (VDBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }
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
                    bs.noteConnectivityChanged(intent.getIntExtra("networkType", -1), ni.getState().toString());
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
        this.mProxyTracker.loadGlobalProxy();
        registerNetdEventCallback();
        this.mTethering.systemReady();
        synchronized (this) {
            this.mSystemReady = true;
            if (this.mInitialBroadcast != null) {
                this.mContext.sendStickyBroadcastAsUser(this.mInitialBroadcast, UserHandle.ALL);
                this.mInitialBroadcast = null;
            }
        }
        updateLockdownVpn();
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(30));
        InternalHandler internalHandler2 = this.mHandler;
        internalHandler2.sendMessage(internalHandler2.obtainMessage(25));
        this.mPermissionMonitor.startMonitoring();
    }

    private void setupDataActivityTracking(NetworkAgentInfo networkAgent) {
        int timeout;
        String iface = networkAgent.linkProperties.getInterfaceName();
        int type = -1;
        if (networkAgent.networkCapabilities.hasTransport(0)) {
            timeout = Settings.Global.getInt(this.mContext.getContentResolver(), "data_activity_timeout_mobile", 10);
            type = 0;
        } else if (networkAgent.networkCapabilities.hasTransport(1)) {
            timeout = Settings.Global.getInt(this.mContext.getContentResolver(), "data_activity_timeout_wifi", 15);
            type = 1;
        } else {
            timeout = 0;
        }
        if (timeout > 0 && iface != null && type != -1) {
            try {
                this.mNMS.addIdleTimer(iface, timeout, type);
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
                    this.mNMS.removeIdleTimer(iface);
                } catch (Exception e) {
                    loge("Exception in removeDataActivityTracking " + e);
                }
            }
        }
    }

    private void updateDataActivityTracking(NetworkAgentInfo newNetwork, NetworkAgentInfo oldNetwork) {
        if (newNetwork != null) {
            setupDataActivityTracking(newNetwork);
        }
        if (oldNetwork != null) {
            removeDataActivityTracking(oldNetwork);
        }
    }

    private void updateMtu(LinkProperties newLp, LinkProperties oldLp) {
        String iface = newLp.getInterfaceName();
        int mtu = newLp.getMtu();
        if (oldLp == null && mtu == 0) {
            return;
        }
        if (oldLp != null && newLp.isIdenticalMtu(oldLp)) {
            if (VDBG) {
                log("identical MTU - not setting");
            }
        } else if (!LinkProperties.isValidMtu(mtu, newLp.hasGlobalIpv6Address())) {
            if (mtu != 0) {
                loge("Unexpected mtu value: " + mtu + ", " + iface);
            }
        } else if (TextUtils.isEmpty(iface)) {
            loge("Setting MTU size with null iface.");
        } else {
            try {
                if (VDBG || DDBG) {
                    log("Setting MTU size: " + iface + ", " + mtu);
                }
                this.mNMS.setMtu(iface, mtu);
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

    private void updateTcpBufferSizes(String tcpBufferSizes) {
        String[] values = tcpBufferSizes != null ? tcpBufferSizes.split(",") : null;
        if (values == null || values.length != 6) {
            log("Invalid tcpBufferSizes string: " + tcpBufferSizes + ", using defaults");
            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;
            values = DEFAULT_TCP_BUFFER_SIZES.split(",");
        }
        if (tcpBufferSizes.equals(this.mCurrentTcpBufferSizes)) {
            return;
        }
        try {
            if (VDBG || DDBG) {
                String str = TAG;
                Slog.d(str, "Setting tx/rx TCP buffers to " + tcpBufferSizes);
            }
            String rmemValues = String.join(" ", values[0], values[1], values[2]);
            String wmemValues = String.join(" ", values[3], values[4], values[5]);
            this.mNetd.setTcpRWmemorySize(rmemValues, wmemValues);
            this.mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (RemoteException | ServiceSpecificException e) {
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
        if (networkType <= 18) {
            NetworkConfig[] networkConfigArr = this.mNetConfigs;
            if (networkConfigArr[networkType] == null) {
                return RESTORE_DEFAULT_NETWORK_DELAY;
            }
            int ret = networkConfigArr[networkType].restoreTime;
            return ret;
        }
        return RESTORE_DEFAULT_NETWORK_DELAY;
    }

    private void dumpNetworkDiagnostics(IndentingPrintWriter pw) {
        NetworkAgentInfo[] networksSortedById;
        List<NetworkDiagnostics> netDiags = new ArrayList<>();
        for (NetworkAgentInfo nai : networksSortedById()) {
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
        int i;
        PrintWriter indentingPrintWriter = new IndentingPrintWriter(writer, "  ");
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, indentingPrintWriter) && !asProto) {
            if (ArrayUtils.contains(args, DIAG_ARG)) {
                dumpNetworkDiagnostics(indentingPrintWriter);
            } else if (ArrayUtils.contains(args, TETHERING_ARG)) {
                this.mTethering.dump(fd, indentingPrintWriter, args);
            } else if (ArrayUtils.contains(args, NETWORK_ARG)) {
                dumpNetworks(indentingPrintWriter);
            } else if (ArrayUtils.contains(args, REQUEST_ARG)) {
                dumpNetworkRequests(indentingPrintWriter);
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
                dumpNetworks(indentingPrintWriter);
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                indentingPrintWriter.print("Restrict background: ");
                indentingPrintWriter.println(this.mRestrictBackground);
                indentingPrintWriter.println();
                indentingPrintWriter.println("Status for known UIDs:");
                indentingPrintWriter.increaseIndent();
                int size = this.mUidRules.size();
                int i2 = 0;
                while (true) {
                    if (i2 >= size) {
                        break;
                    }
                    try {
                        int uid = this.mUidRules.keyAt(i2);
                        int uidRules = this.mUidRules.get(uid, 0);
                        indentingPrintWriter.println("UID=" + uid + " rules=" + NetworkPolicyManager.uidRulesToString(uidRules));
                    } catch (ArrayIndexOutOfBoundsException e) {
                        indentingPrintWriter.println("  ArrayIndexOutOfBoundsException");
                    } catch (ConcurrentModificationException e2) {
                        indentingPrintWriter.println("  ConcurrentModificationException");
                    }
                    i2++;
                }
                indentingPrintWriter.println();
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println("Network Requests:");
                indentingPrintWriter.increaseIndent();
                dumpNetworkRequests(indentingPrintWriter);
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                this.mLegacyTypeTracker.dump(indentingPrintWriter);
                indentingPrintWriter.println();
                this.mTethering.dump(fd, indentingPrintWriter, args);
                indentingPrintWriter.println();
                this.mKeepaliveTracker.dump(indentingPrintWriter);
                indentingPrintWriter.println();
                dumpAvoidBadWifiSettings(indentingPrintWriter);
                indentingPrintWriter.println();
                this.mMultipathPolicyTracker.dump(indentingPrintWriter);
                if (!ArrayUtils.contains(args, SHORT_ARG)) {
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
                    indentingPrintWriter.println();
                    indentingPrintWriter.println("bandwidth update requests (by uid):");
                    indentingPrintWriter.increaseIndent();
                    synchronized (this.mBandwidthRequests) {
                        for (i = 0; i < this.mBandwidthRequests.size(); i++) {
                            indentingPrintWriter.println("[" + this.mBandwidthRequests.keyAt(i) + "]: " + this.mBandwidthRequests.valueAt(i));
                        }
                    }
                    indentingPrintWriter.decreaseIndent();
                    indentingPrintWriter.decreaseIndent();
                }
                indentingPrintWriter.println();
                indentingPrintWriter.println("NetworkStackClient logs:");
                indentingPrintWriter.increaseIndent();
                NetworkStackClient.getInstance().dump(indentingPrintWriter);
                indentingPrintWriter.decreaseIndent();
                indentingPrintWriter.println();
                indentingPrintWriter.println("Permission Monitor:");
                indentingPrintWriter.increaseIndent();
                this.mPermissionMonitor.dump(indentingPrintWriter);
                indentingPrintWriter.decreaseIndent();
            }
        }
    }

    private void dumpNetworks(IndentingPrintWriter pw) {
        NetworkAgentInfo[] networksSortedById;
        for (NetworkAgentInfo nai : networksSortedById()) {
            pw.println(nai.toString());
            pw.increaseIndent();
            pw.println(String.format("Requests: REQUEST:%d LISTEN:%d BACKGROUND_REQUEST:%d total:%d", Integer.valueOf(nai.numForegroundNetworkRequests()), Integer.valueOf(nai.numNetworkRequests() - nai.numRequestNetworkRequests()), Integer.valueOf(nai.numBackgroundNetworkRequests()), Integer.valueOf(nai.numNetworkRequests())));
            pw.increaseIndent();
            for (int i = 0; i < nai.numNetworkRequests(); i++) {
                pw.println(nai.requestAt(i).toString());
            }
            pw.decreaseIndent();
            pw.println("Lingered:");
            pw.increaseIndent();
            nai.dumpLingerTimers(pw);
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
    }

    private void dumpNetworkRequests(IndentingPrintWriter pw) {
        NetworkRequestInfo[] requestsSortedById;
        for (NetworkRequestInfo nri : requestsSortedById()) {
            pw.println(nri.toString());
        }
    }

    private NetworkAgentInfo[] networksSortedById() {
        NetworkAgentInfo[] networks = (NetworkAgentInfo[]) this.mNetworkAgentInfos.values().toArray(new NetworkAgentInfo[0]);
        Arrays.sort(networks, Comparator.comparingInt(new ToIntFunction() { // from class: com.android.server.-$$Lambda$ConnectivityService$H7LYLEpmjJnE6rkiTAMKiNF7tsA
            @Override // java.util.function.ToIntFunction
            public final int applyAsInt(Object obj) {
                int i;
                i = ((NetworkAgentInfo) obj).network.netId;
                return i;
            }
        }));
        return networks;
    }

    private NetworkRequestInfo[] requestsSortedById() {
        NetworkRequestInfo[] requests = (NetworkRequestInfo[]) this.mNetworkRequests.values().toArray(new NetworkRequestInfo[0]);
        Arrays.sort(requests, Comparator.comparingInt(new ToIntFunction() { // from class: com.android.server.-$$Lambda$ConnectivityService$GX97FVWNZr22L2SZWTK3UYHOOe0
            @Override // java.util.function.ToIntFunction
            public final int applyAsInt(Object obj) {
                int i;
                i = ((ConnectivityService.NetworkRequestInfo) obj).request.requestId;
                return i;
            }
        }));
        return requests;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isLiveNetworkAgent(NetworkAgentInfo nai, int what) {
        if (nai.network == null) {
            return false;
        }
        NetworkAgentInfo officialNai = getNetworkAgentInfoForNetwork(nai.network);
        if (officialNai == null || !officialNai.equals(nai)) {
            if (officialNai != null || VDBG) {
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
            switch (msg.what) {
                case 69632:
                    ConnectivityService.this.handleAsyncChannelHalfConnect(msg);
                    return true;
                case 69633:
                case 69634:
                default:
                    return false;
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
            }
        }

        private void maybeHandleNetworkAgentMessage(Message msg) {
            NetworkAgentInfo nai = (NetworkAgentInfo) ConnectivityService.this.mNetworkAgentInfos.get(msg.replyTo);
            if (nai == null) {
                if (ConnectivityService.VDBG) {
                    ConnectivityService.log(String.format("%s from unknown NetworkAgent", ConnectivityService.eventName(msg.what)));
                    return;
                }
                return;
            }
            int i = msg.what;
            if (i == 528392) {
                if (nai.everConnected) {
                    ConnectivityService.loge("ERROR: cannot call explicitlySelected on already-connected network");
                }
                nai.networkMisc.explicitlySelected = msg.arg1 == 1;
                nai.networkMisc.acceptUnvalidated = msg.arg1 == 1 && msg.arg2 == 1;
                nai.networkMisc.acceptPartialConnectivity = msg.arg2 == 1;
            } else if (i == 528397) {
                ConnectivityService.this.mKeepaliveTracker.handleEventSocketKeepalive(nai, msg);
            } else {
                switch (i) {
                    case 528385:
                        NetworkInfo info = (NetworkInfo) msg.obj;
                        ConnectivityService.this.updateNetworkInfo(nai, info);
                        return;
                    case 528386:
                        NetworkCapabilities networkCapabilities = (NetworkCapabilities) msg.obj;
                        if (networkCapabilities.hasConnectivityManagedCapability()) {
                            String str = ConnectivityService.TAG;
                            Slog.wtf(str, "BUG: " + nai + " has CS-managed capability.");
                        }
                        ConnectivityService.this.updateCapabilities(nai.getCurrentScore(), nai, networkCapabilities);
                        return;
                    case 528387:
                        ConnectivityService.this.handleUpdateLinkProperties(nai, (LinkProperties) msg.obj);
                        return;
                    case 528388:
                        ConnectivityService.this.updateNetworkScore(nai, msg.arg1);
                        return;
                    default:
                        return;
                }
            }
        }

        private boolean maybeHandleNetworkMonitorMessage(Message msg) {
            switch (msg.what) {
                case 41:
                    NetworkAgentInfo nai = ConnectivityService.this.getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai != null) {
                        boolean wasPartial = nai.partialConnectivity;
                        nai.partialConnectivity = (msg.arg1 & 2) != 0;
                        boolean partialConnectivityChanged = wasPartial != nai.partialConnectivity;
                        boolean valid = (msg.arg1 & 1) != 0;
                        boolean wasValidated = nai.lastValidated;
                        boolean wasDefault = ConnectivityService.this.isDefaultNetwork(nai);
                        if (nai.captivePortalValidationPending && valid) {
                            nai.captivePortalValidationPending = false;
                            ConnectivityService.this.showNetworkNotification(nai, NetworkNotificationManager.NotificationType.LOGGED_IN);
                        }
                        String logMsg = "";
                        String redirectUrl = msg.obj instanceof String ? (String) msg.obj : "";
                        if (!TextUtils.isEmpty(redirectUrl)) {
                            logMsg = " with redirect to " + redirectUrl;
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
                                ConnectivityService.this.mNotifier.clearNotification(nai.network.netId, NetworkNotificationManager.NotificationType.NO_INTERNET);
                                ConnectivityService.this.mNotifier.clearNotification(nai.network.netId, NetworkNotificationManager.NotificationType.LOST_INTERNET);
                                ConnectivityService.this.mNotifier.clearNotification(nai.network.netId, NetworkNotificationManager.NotificationType.PARTIAL_CONNECTIVITY);
                            }
                        } else if (partialConnectivityChanged) {
                            ConnectivityService.this.updateCapabilities(nai.getCurrentScore(), nai, nai.networkCapabilities);
                        }
                        ConnectivityService.this.updateInetCondition(nai);
                        Bundle redirectUrlBundle = new Bundle();
                        redirectUrlBundle.putString(NetworkAgent.REDIRECT_URL_KEY, redirectUrl);
                        nai.asyncChannel.sendMessage(528391, valid ? 1 : 2, 0, redirectUrlBundle);
                        if (!wasPartial && nai.partialConnectivity) {
                            ConnectivityService.this.mHandler.removeMessages(29, nai.network);
                            ConnectivityService.this.handlePromptUnvalidated(nai.network);
                        }
                        if (wasValidated && !nai.lastValidated) {
                            ConnectivityService.this.handleNetworkUnvalidated(nai);
                            break;
                        }
                    }
                    break;
                case 42:
                    NetworkAgentInfo nai2 = ConnectivityService.this.getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai2 != null) {
                        ConnectivityService.this.updatePrivateDns(nai2, (PrivateDnsConfig) msg.obj);
                        break;
                    }
                    break;
                case 43:
                    int netId = msg.arg2;
                    boolean visible = ConnectivityService.toBool(msg.arg1);
                    NetworkAgentInfo nai3 = ConnectivityService.this.getNetworkAgentInfoForNetId(netId);
                    if (nai3 != null && visible != nai3.lastCaptivePortalDetected) {
                        int oldScore2 = nai3.getCurrentScore();
                        nai3.lastCaptivePortalDetected = visible;
                        nai3.everCaptivePortalDetected |= visible;
                        if (!nai3.lastCaptivePortalDetected || 2 != getCaptivePortalMode()) {
                            ConnectivityService.this.updateCapabilities(oldScore2, nai3, nai3.networkCapabilities);
                        } else {
                            ConnectivityService.log("Avoiding captive portal network: " + nai3.name());
                            nai3.asyncChannel.sendMessage(528399);
                            ConnectivityService.this.teardownUnneededNetwork(nai3);
                            break;
                        }
                    }
                    if (!visible) {
                        ConnectivityService.this.mNotifier.clearNotification(netId, NetworkNotificationManager.NotificationType.SIGN_IN);
                        ConnectivityService.this.mNotifier.clearNotification(netId, NetworkNotificationManager.NotificationType.NETWORK_SWITCH);
                        break;
                    } else if (nai3 == null) {
                        ConnectivityService.loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                        break;
                    } else if (!nai3.networkMisc.provisioningNotificationDisabled) {
                        ConnectivityService.this.mNotifier.showNotification(netId, NetworkNotificationManager.NotificationType.SIGN_IN, nai3, null, (PendingIntent) msg.obj, nai3.networkMisc.explicitlySelected);
                        break;
                    }
                    break;
                default:
                    return false;
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

        private boolean maybeHandleNetworkFactoryMessage(Message msg) {
            if (msg.what == 536580) {
                ConnectivityService.this.handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.sendingUid, true);
                return true;
            }
            return false;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (!maybeHandleAsyncChannelMessage(msg) && !maybeHandleNetworkMonitorMessage(msg) && !maybeHandleNetworkAgentInfoMessage(msg) && !maybeHandleNetworkFactoryMessage(msg)) {
                maybeHandleNetworkAgentMessage(msg);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NetworkMonitorCallbacks extends INetworkMonitorCallbacks.Stub {
        private final AutodestructReference<NetworkAgentInfo> mNai;
        private final int mNetId;

        private NetworkMonitorCallbacks(NetworkAgentInfo nai) {
            this.mNetId = nai.network.netId;
            this.mNai = new AutodestructReference<>(nai);
        }

        @Override // android.net.INetworkMonitorCallbacks
        public void onNetworkMonitorCreated(INetworkMonitor networkMonitor) {
            ConnectivityService.this.mHandler.sendMessage(ConnectivityService.this.mHandler.obtainMessage(18, new Pair(this.mNai.getAndDestroy(), networkMonitor)));
        }

        @Override // android.net.INetworkMonitorCallbacks
        public void notifyNetworkTested(int testResult, String redirectUrl) {
            ConnectivityService.this.mTrackerHandler.sendMessage(ConnectivityService.this.mTrackerHandler.obtainMessage(41, testResult, this.mNetId, redirectUrl));
        }

        @Override // android.net.INetworkMonitorCallbacks
        public void notifyPrivateDnsConfigResolved(PrivateDnsConfigParcel config) {
            ConnectivityService.this.mTrackerHandler.sendMessage(ConnectivityService.this.mTrackerHandler.obtainMessage(42, 0, this.mNetId, PrivateDnsConfig.fromParcel(config)));
        }

        @Override // android.net.INetworkMonitorCallbacks
        public void showProvisioningNotification(String action, String packageName) {
            Intent intent = new Intent(action);
            intent.setPackage(packageName);
            long token = Binder.clearCallingIdentity();
            try {
                PendingIntent pendingIntent = PendingIntent.getBroadcast(ConnectivityService.this.mContext, 0, intent, 0);
                Binder.restoreCallingIdentity(token);
                ConnectivityService.this.mTrackerHandler.sendMessage(ConnectivityService.this.mTrackerHandler.obtainMessage(43, 1, this.mNetId, pendingIntent));
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(token);
                throw th;
            }
        }

        @Override // android.net.INetworkMonitorCallbacks
        public void hideProvisioningNotification() {
            ConnectivityService.this.mTrackerHandler.sendMessage(ConnectivityService.this.mTrackerHandler.obtainMessage(43, 0, this.mNetId));
        }

        @Override // android.net.INetworkMonitorCallbacks
        public int getInterfaceVersion() {
            return 3;
        }
    }

    private boolean networkRequiresPrivateDnsValidation(NetworkAgentInfo nai) {
        return NetworkMonitorUtils.isPrivateDnsValidationRequired(nai.networkCapabilities);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleFreshlyValidatedNetwork(NetworkAgentInfo nai) {
        if (nai == null) {
            return;
        }
        PrivateDnsConfig cfg = this.mDnsManager.getPrivateDnsConfig();
        if (cfg.useTls && TextUtils.isEmpty(cfg.hostname)) {
            updateDnses(nai.linkProperties, null, nai.network.netId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePrivateDnsSettingsChanged() {
        PrivateDnsConfig cfg = this.mDnsManager.getPrivateDnsConfig();
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            handlePerNetworkPrivateDnsConfig(nai, cfg);
            if (networkRequiresPrivateDnsValidation(nai)) {
                handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
            }
        }
    }

    private void handlePerNetworkPrivateDnsConfig(NetworkAgentInfo nai, PrivateDnsConfig cfg) {
        if (networkRequiresPrivateDnsValidation(nai)) {
            nai.networkMonitor().notifyPrivateDnsChanged(cfg.toParcel());
            updatePrivateDns(nai, cfg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePrivateDns(NetworkAgentInfo nai, PrivateDnsConfig newCfg) {
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

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNat64PrefixEvent(int netId, boolean added, String prefixString, int prefixLength) {
        NetworkAgentInfo nai = this.mNetworkForNetId.get(netId);
        if (nai == null) {
            return;
        }
        Object[] objArr = new Object[4];
        objArr[0] = added ? "added" : "removed";
        objArr[1] = Integer.valueOf(netId);
        objArr[2] = prefixString;
        objArr[3] = Integer.valueOf(prefixLength);
        log(String.format("NAT64 prefix %s on netId %d: %s/%d", objArr));
        IpPrefix prefix = null;
        if (added) {
            try {
                prefix = new IpPrefix(InetAddresses.parseNumericAddress(prefixString), prefixLength);
            } catch (IllegalArgumentException e) {
                loge("Invalid NAT64 prefix " + prefixString + SliceClientPermissions.SliceAuthority.DELIMITER + prefixLength);
                return;
            }
        }
        nai.clatd.setNat64Prefix(prefix);
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
        int score;
        int serial;
        AsyncChannel ac = (AsyncChannel) msg.obj;
        if (this.mNetworkFactoryInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == 0) {
                if (VDBG) {
                    log("NetworkFactory connected");
                }
                this.mNetworkFactoryInfos.get(msg.replyTo).asyncChannel.sendMessage(69633);
                for (NetworkRequestInfo nri : this.mNetworkRequests.values()) {
                    if (!nri.request.isListen()) {
                        NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
                        if (nai != null) {
                            score = nai.getCurrentScore();
                            serial = nai.factorySerialNumber;
                        } else {
                            score = 0;
                            serial = -1;
                        }
                        ac.sendMessage(536576, score, serial, nri.request);
                    }
                }
                return;
            }
            loge("Error connecting NetworkFactory");
            this.mNetworkFactoryInfos.remove(msg.obj);
        } else if (this.mNetworkAgentInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == 0) {
                if (VDBG) {
                    log("NetworkAgent connected");
                }
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
        NetworkAgentInfo nai = this.mNetworkAgentInfos.get(msg.replyTo);
        if (nai != null) {
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
        if (ni.isPersist() && ni.getSubtype() == this.mDefaultNetSubtype) {
            removeSystemApnRule(lp.getInterfaceName());
        }
        if (ni.isPersist() && ni.getSubtype() == 1) {
            disableFirewallRule(lp.getInterfaceName());
        }
        this.mNotifier.clearNotification(nai.network.netId);
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
        nai.networkMonitor().notifyNetworkDisconnected();
        this.mNetworkAgentInfos.remove(nai.messenger);
        nai.clatd.update();
        synchronized (this.mNetworkForNetId) {
            this.mNetworkForNetId.remove(nai.network.netId);
        }
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest request = nai.requestAt(i);
            NetworkAgentInfo currentNetwork = getNetworkForRequest(request.requestId);
            if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                clearNetworkForRequest(request.requestId);
                sendUpdatedScoreToFactories(request, null);
            }
        }
        nai.clearLingerState();
        if (nai.isSatisfyingRequest(this.mDefaultRequest.requestId)) {
            updateDataActivityTracking(null, nai);
            notifyLockdownVpn(nai);
            int powerState = SystemProperties.getInt("sys.xiaopeng.power_state", 0);
            if (powerState == 2) {
                log("going to sleep, try to relase network transition wakelock");
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
            destroyNativeNetwork(nai);
            this.mDnsManager.removeNetwork(nai.network);
        }
        synchronized (this.mNetworkForNetId) {
            this.mNetIdInUse.delete(nai.network.netId);
        }
    }

    private boolean createNativeNetwork(NetworkAgentInfo networkAgent) {
        boolean z;
        try {
            if (networkAgent.isVPN()) {
                INetd iNetd = this.mNetd;
                int i = networkAgent.network.netId;
                if (networkAgent.networkMisc != null && networkAgent.networkMisc.allowBypass) {
                    z = false;
                    iNetd.networkCreateVpn(i, z);
                }
                z = true;
                iNetd.networkCreateVpn(i, z);
            } else {
                this.mNetd.networkCreatePhysical(networkAgent.network.netId, getNetworkPermission(networkAgent.networkCapabilities));
            }
            this.mDnsResolver.createNetworkCache(networkAgent.network.netId);
            return true;
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Error creating network " + networkAgent.network.netId + ": " + e.getMessage());
            return false;
        }
    }

    private void destroyNativeNetwork(NetworkAgentInfo networkAgent) {
        try {
            this.mNetd.networkDestroy(networkAgent.network.netId);
            this.mDnsResolver.destroyNetworkCache(networkAgent.network.netId);
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Exception destroying network: " + e);
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
            handleReleaseNetworkRequest(existingRequest.request, getCallingUid(), false);
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
            sendUpdatedScoreToFactories(nri.request, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseNetworkRequestWithIntent(PendingIntent pendingIntent, int callingUid) {
        NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            handleReleaseNetworkRequest(nri.request, callingUid, false);
        }
    }

    private boolean unneeded(NetworkAgentInfo nai, UnneededFor reason) {
        int numRequests;
        int i = AnonymousClass8.$SwitchMap$com$android$server$ConnectivityService$UnneededFor[reason.ordinal()];
        if (i == 1) {
            numRequests = nai.numRequestNetworkRequests();
        } else if (i == 2) {
            numRequests = nai.numForegroundNetworkRequests();
        } else {
            Slog.wtf(TAG, "Invalid reason. Cannot happen.");
            return true;
        }
        if (!nai.everConnected || nai.isVPN() || nai.isLingering() || numRequests > 0) {
            return false;
        }
        if (nai.networkInfo.isPersist()) {
            log(nai.name() + "is needed");
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
        if (VDBG || nri.request.isRequest()) {
            log("releasing " + nri.request + " (timeout)");
        }
        handleRemoveNetworkRequest(nri);
        callCallbackForRequest(nri, null, 524293, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseNetworkRequest(NetworkRequest request, int callingUid, boolean callOnUnavailable) {
        NetworkRequestInfo nri = getNriForAppRequest(request, callingUid, "release NetworkRequest");
        if (nri == null) {
            return;
        }
        if (VDBG || nri.request.isRequest()) {
            log("releasing " + nri.request + " (release request)");
        }
        handleRemoveNetworkRequest(nri);
        if (callOnUnavailable) {
            callCallbackForRequest(nri, null, 524293, 0);
        }
    }

    private void handleRemoveNetworkRequest(NetworkRequestInfo nri) {
        nri.unlinkDeathRecipient();
        this.mNetworkRequests.remove(nri.request);
        synchronized (this.mUidToNetworkRequestCount) {
            int requests = this.mUidToNetworkRequestCount.get(nri.mUid, 0);
            if (requests < 1) {
                String str = TAG;
                Slog.wtf(str, "BUG: too small request count " + requests + " for UID " + nri.mUid);
            } else if (requests == 1) {
                this.mUidToNetworkRequestCount.removeAt(this.mUidToNetworkRequestCount.indexOfKey(nri.mUid));
            } else {
                this.mUidToNetworkRequestCount.put(nri.mUid, requests - 1);
            }
        }
        LocalLog localLog = this.mNetworkRequestInfoLogs;
        localLog.log("RELEASE " + nri);
        if (nri.request.isRequest()) {
            boolean wasKept = false;
            NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
            if (nai != null) {
                boolean wasBackgroundNetwork = nai.isBackgroundNetwork();
                nai.removeRequest(nri.request.requestId);
                if (VDBG || DDBG) {
                    log(" Removing from current network " + nai.name() + ", leaving " + nai.numNetworkRequests() + " requests.");
                }
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
            if (nri.request.legacyType != -1 && nai != null) {
                boolean doRemove = true;
                if (wasKept) {
                    for (int i = 0; i < nai.numNetworkRequests(); i++) {
                        NetworkRequest otherRequest = nai.requestAt(i);
                        if (otherRequest.legacyType == nri.request.legacyType && otherRequest.isRequest()) {
                            log(" still have other legacy request - leaving");
                            doRemove = false;
                        }
                    }
                }
                if (doRemove) {
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
        enforceNetworkStackSettingsOrSetup();
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(EVENT_SET_ACCEPT_UNVALIDATED, encodeBool(accept), encodeBool(always), network));
    }

    public void setAcceptPartialConnectivity(Network network, boolean accept, boolean always) {
        enforceNetworkStackSettingsOrSetup();
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(45, encodeBool(accept), encodeBool(always), network));
    }

    public void setAvoidUnvalidated(Network network) {
        enforceNetworkStackSettingsOrSetup();
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(35, network));
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
            nai.networkMisc.acceptPartialConnectivity = accept;
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
    public void handleSetAcceptPartialConnectivity(Network network, boolean accept, boolean always) {
        log("handleSetAcceptPartialConnectivity network=" + network + " accept=" + accept + " always=" + always);
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.lastValidated) {
            return;
        }
        if (accept != nai.networkMisc.acceptPartialConnectivity) {
            nai.networkMisc.acceptPartialConnectivity = accept;
        }
        if (always) {
            nai.asyncChannel.sendMessage(528393, encodeBool(accept));
        }
        if (!accept) {
            nai.asyncChannel.sendMessage(528399);
            teardownUnneededNetwork(nai);
            return;
        }
        nai.networkMonitor().setAcceptPartialConnectivity();
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
        if (VDBG) {
            log("scheduleUnvalidatedPrompt " + nai.network);
        }
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessageDelayed(internalHandler.obtainMessage(29, nai.network), 8000L);
    }

    public void startCaptivePortalApp(final Network network) {
        enforceConnectivityInternalPermission();
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$nuaE_gOVb4npt3obpt7AoWH3OBo
            @Override // java.lang.Runnable
            public final void run() {
                ConnectivityService.this.lambda$startCaptivePortalApp$4$ConnectivityService(network);
            }
        });
    }

    public /* synthetic */ void lambda$startCaptivePortalApp$4$ConnectivityService(Network network) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null && nai.networkCapabilities.hasCapability(17)) {
            nai.networkMonitor().launchCaptivePortalApp();
        }
    }

    public void startCaptivePortalAppInternal(Network network, Bundle appExtras) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MAINLINE_NETWORK_STACK", "ConnectivityService");
        final Intent appIntent = new Intent("android.net.conn.CAPTIVE_PORTAL");
        appIntent.putExtras(appExtras);
        appIntent.putExtra("android.net.extra.CAPTIVE_PORTAL", new CaptivePortal(new CaptivePortalImpl(network).asBinder()));
        appIntent.setFlags(272629760);
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            nai.captivePortalValidationPending = true;
        }
        Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$fBQzRY85gy75jpL8zm68U3BxgdA
            public final void runOrThrow() {
                ConnectivityService.this.lambda$startCaptivePortalAppInternal$5$ConnectivityService(appIntent);
            }
        });
    }

    public /* synthetic */ void lambda$startCaptivePortalAppInternal$5$ConnectivityService(Intent appIntent) throws Exception {
        this.mContext.startActivityAsUser(appIntent, UserHandle.CURRENT);
    }

    /* loaded from: classes.dex */
    private class CaptivePortalImpl extends ICaptivePortal.Stub {
        private final Network mNetwork;

        private CaptivePortalImpl(Network network) {
            this.mNetwork = network;
        }

        public void appResponse(int response) {
            NetworkMonitorManager nm;
            if (response == 2) {
                ConnectivityService.this.enforceSettingsPermission();
            }
            NetworkAgentInfo nai = ConnectivityService.this.getNetworkAgentInfoForNetwork(this.mNetwork);
            if (nai == null || (nm = nai.networkMonitor()) == null) {
                return;
            }
            nm.notifyCaptivePortalAppFinished(response);
        }

        public void logEvent(int eventId, String packageName) {
            ConnectivityService.this.enforceSettingsPermission();
            new MetricsLogger().action(eventId, packageName);
        }
    }

    public boolean avoidBadWifi() {
        return this.mMultinetworkPolicyTracker.getAvoidBadWifi();
    }

    public boolean shouldAvoidBadWifi() {
        if (!checkNetworkStackPermission()) {
            throw new SecurityException("avoidBadWifi requires NETWORK_STACK permission");
        }
        return avoidBadWifi();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: rematchForAvoidBadWifiUpdate */
    public void lambda$new$0$ConnectivityService() {
        rematchAllNetworksAndRequests(null, 0);
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            if (nai.networkCapabilities.hasTransport(1)) {
                sendUpdatedScoreToFactories(nai);
            }
        }
    }

    private void dumpAvoidBadWifiSettings(IndentingPrintWriter pw) {
        String description;
        NetworkAgentInfo[] networksSortedById;
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
        for (NetworkAgentInfo nai : networksSortedById()) {
            if (nai.avoidUnvalidated) {
                pw.println(nai.name());
            }
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.ConnectivityService$8  reason: invalid class name */
    /* loaded from: classes.dex */
    public static /* synthetic */ class AnonymousClass8 {
        static final /* synthetic */ int[] $SwitchMap$com$android$server$ConnectivityService$UnneededFor;
        static final /* synthetic */ int[] $SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType = new int[NetworkNotificationManager.NotificationType.values().length];

        static {
            try {
                $SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType[NetworkNotificationManager.NotificationType.LOGGED_IN.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType[NetworkNotificationManager.NotificationType.NO_INTERNET.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType[NetworkNotificationManager.NotificationType.LOST_INTERNET.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
            try {
                $SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType[NetworkNotificationManager.NotificationType.PARTIAL_CONNECTIVITY.ordinal()] = 4;
            } catch (NoSuchFieldError e4) {
            }
            $SwitchMap$com$android$server$ConnectivityService$UnneededFor = new int[UnneededFor.values().length];
            try {
                $SwitchMap$com$android$server$ConnectivityService$UnneededFor[UnneededFor.TEARDOWN.ordinal()] = 1;
            } catch (NoSuchFieldError e5) {
            }
            try {
                $SwitchMap$com$android$server$ConnectivityService$UnneededFor[UnneededFor.LINGER.ordinal()] = 2;
            } catch (NoSuchFieldError e6) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showNetworkNotification(NetworkAgentInfo nai, NetworkNotificationManager.NotificationType type) {
        String action;
        boolean highPriority;
        int i = AnonymousClass8.$SwitchMap$com$android$server$connectivity$NetworkNotificationManager$NotificationType[type.ordinal()];
        if (i == 1) {
            action = "android.settings.WIFI_SETTINGS";
            this.mHandler.removeMessages(44);
            InternalHandler internalHandler = this.mHandler;
            internalHandler.sendMessageDelayed(internalHandler.obtainMessage(44, nai.network.netId, 0), 20000L);
            highPriority = true;
        } else if (i == 2) {
            action = "android.net.conn.PROMPT_UNVALIDATED";
            highPriority = true;
        } else if (i == 3) {
            action = "android.net.conn.PROMPT_LOST_VALIDATION";
            highPriority = true;
        } else if (i == 4) {
            action = "android.net.conn.PROMPT_PARTIAL_CONNECTIVITY";
            boolean highPriority2 = nai.networkMisc.explicitlySelected;
            highPriority = highPriority2;
        } else {
            Slog.wtf(TAG, "Unknown notification type " + type);
            return;
        }
        Intent intent = new Intent(action);
        if (type != NetworkNotificationManager.NotificationType.LOGGED_IN) {
            intent.setData(Uri.fromParts("netId", Integer.toString(nai.network.netId), null));
            intent.addFlags(268435456);
            intent.setClassName("com.android.settings", "com.android.settings.wifi.WifiNoInternetDialog");
        }
        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 268435456, null, UserHandle.CURRENT);
        this.mNotifier.showNotification(nai.network.netId, type, nai, null, pendingIntent, highPriority);
    }

    private boolean shouldPromptUnvalidated(NetworkAgentInfo nai) {
        if (nai.everValidated || nai.everCaptivePortalDetected) {
            return false;
        }
        if (!nai.partialConnectivity || nai.networkMisc.acceptPartialConnectivity) {
            return nai.networkMisc.explicitlySelected && !nai.networkMisc.acceptUnvalidated;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePromptUnvalidated(Network network) {
        if (VDBG || DDBG) {
            log("handlePromptUnvalidated " + network);
        }
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || !shouldPromptUnvalidated(nai)) {
            return;
        }
        nai.asyncChannel.sendMessage(528399);
        if (nai.partialConnectivity) {
            showNetworkNotification(nai, NetworkNotificationManager.NotificationType.PARTIAL_CONNECTIVITY);
        } else {
            showNetworkNotification(nai, NetworkNotificationManager.NotificationType.NO_INTERNET);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNetworkUnvalidated(NetworkAgentInfo nai) {
        NetworkCapabilities nc = nai.networkCapabilities;
        log("handleNetworkUnvalidated " + nai.name() + " cap=" + nc);
        if (nc.hasTransport(1) && this.mMultinetworkPolicyTracker.shouldNotifyWifiUnvalidated()) {
            showNetworkNotification(nai, NetworkNotificationManager.NotificationType.LOST_INTERNET);
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

    public NetworkRequest getDefaultRequest() {
        return this.mDefaultRequest;
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
            if (i != 8) {
                if (i == 9) {
                    ConnectivityService.this.mProxyTracker.loadDeprecatedGlobalHttpProxy();
                    return;
                } else if (i == 44) {
                    ConnectivityService.this.mNotifier.clearNotification(msg.arg1, NetworkNotificationManager.NotificationType.LOGGED_IN);
                    return;
                } else if (i != 45) {
                    switch (i) {
                        case 16:
                            ConnectivityService.this.handleApplyDefaultProxy((ProxyInfo) msg.obj);
                            return;
                        case 17:
                            ConnectivityService.this.handleRegisterNetworkFactory((NetworkFactoryInfo) msg.obj);
                            return;
                        case 18:
                            Pair<NetworkAgentInfo, INetworkMonitor> arg = (Pair) msg.obj;
                            ConnectivityService.this.handleRegisterNetworkAgent((NetworkAgentInfo) arg.first, (INetworkMonitor) arg.second);
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
                            ConnectivityService.this.handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1, false);
                            return;
                        case 23:
                            ConnectivityService.this.handleUnregisterNetworkFactory((Messenger) msg.obj);
                            return;
                        case 24:
                            break;
                        case 25:
                            ConnectivityService.this.mMultipathPolicyTracker.start();
                            return;
                        case ConnectivityService.EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT /* 26 */:
                        case 31:
                            ConnectivityService.this.handleRegisterNetworkRequestWithIntent(msg);
                            return;
                        case ConnectivityService.EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT /* 27 */:
                            ConnectivityService.this.handleReleaseNetworkRequestWithIntent((PendingIntent) msg.obj, msg.arg1);
                            return;
                        case ConnectivityService.EVENT_SET_ACCEPT_UNVALIDATED /* 28 */:
                            Network network = (Network) msg.obj;
                            ConnectivityService.this.handleSetAcceptUnvalidated(network, ConnectivityService.toBool(msg.arg1), ConnectivityService.toBool(msg.arg2));
                            return;
                        case 29:
                            ConnectivityService.this.handlePromptUnvalidated((Network) msg.obj);
                            return;
                        case 30:
                            ConnectivityService.this.handleConfigureAlwaysOnNetworks();
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
                                case 39:
                                    ConnectivityService.this.handleUidRulesChanged(msg.arg1, msg.arg2);
                                    return;
                                case 40:
                                    ConnectivityService.this.handleRestrictBackgroundChanged(ConnectivityService.toBool(msg.arg1));
                                    return;
                                default:
                                    switch (i) {
                                        case 528395:
                                            ConnectivityService.this.mKeepaliveTracker.handleStartKeepalive(msg);
                                            return;
                                        case 528396:
                                            NetworkAgentInfo nai = ConnectivityService.this.getNetworkAgentInfoForNetwork((Network) msg.obj);
                                            int slot = msg.arg1;
                                            int reason = msg.arg2;
                                            ConnectivityService.this.mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
                                            return;
                                        default:
                                            return;
                                    }
                            }
                    }
                } else {
                    Network network2 = (Network) msg.obj;
                    ConnectivityService.this.handleSetAcceptPartialConnectivity(network2, ConnectivityService.toBool(msg.arg1), ConnectivityService.toBool(msg.arg2));
                    return;
                }
            }
            ConnectivityService.this.handleReleaseNetworkTransitionWakelock(msg.what);
        }
    }

    public int tether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (isTetheringSupported()) {
            return this.mTethering.tether(iface);
        }
        return 3;
    }

    public int untether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        if (isTetheringSupported()) {
            return this.mTethering.untether(iface);
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

    public void getLatestTetheringEntitlementResult(int type, ResultReceiver receiver, boolean showEntitlementUi, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        this.mTethering.getLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
    }

    public void registerTetheringEventCallback(ITetheringEventCallback callback, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        this.mTethering.registerTetheringEventCallback(callback);
    }

    public void unregisterTetheringEventCallback(ITetheringEventCallback callback, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(this.mContext, callerPkg);
        this.mTethering.unregisterTetheringEventCallback(callback);
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
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(36, uid, connectivityInfo, network));
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
        nai.networkMonitor().forceReevaluation(uid);
    }

    public ProxyInfo getProxyForNetwork(Network network) {
        ProxyInfo globalProxy = this.mProxyTracker.getGlobalProxy();
        if (globalProxy != null) {
            return globalProxy;
        }
        if (network == null) {
            Network activeNetwork = getActiveNetworkForUidInternal(Binder.getCallingUid(), true);
            if (activeNetwork == null) {
                return null;
            }
            return getLinkPropertiesProxyInfo(activeNetwork);
        } else if (!queryUserAccess(Binder.getCallingUid(), network.netId)) {
            return null;
        } else {
            return getLinkPropertiesProxyInfo(network);
        }
    }

    @VisibleForTesting
    protected boolean queryUserAccess(int uid, int netId) {
        return NetworkUtils.queryUserAccess(uid, netId);
    }

    private ProxyInfo getLinkPropertiesProxyInfo(Network network) {
        ProxyInfo proxyInfo;
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) {
            return null;
        }
        synchronized (nai) {
            ProxyInfo linkHttpProxy = nai.linkProperties.getHttpProxy();
            proxyInfo = linkHttpProxy != null ? new ProxyInfo(linkHttpProxy) : null;
        }
        return proxyInfo;
    }

    public void setGlobalProxy(ProxyInfo proxyProperties) {
        enforceConnectivityInternalPermission();
        this.mProxyTracker.setGlobalProxy(proxyProperties);
    }

    public ProxyInfo getGlobalProxy() {
        return this.mProxyTracker.getGlobalProxy();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleApplyDefaultProxy(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost()) && Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            proxy = null;
        }
        this.mProxyTracker.setDefaultProxy(proxy);
    }

    private void updateProxy(LinkProperties newLp, LinkProperties oldLp) {
        ProxyInfo newProxyInfo = newLp == null ? null : newLp.getHttpProxy();
        ProxyInfo oldProxyInfo = oldLp != null ? oldLp.getHttpProxy() : null;
        if (!ProxyTracker.proxyInfoEqual(newProxyInfo, oldProxyInfo)) {
            this.mProxyTracker.sendProxyBroadcast();
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
        NetworkAgentInfo defaultNai;
        VpnInfo info = vpn.getVpnInfo();
        if (info == null) {
            return null;
        }
        Network[] underlyingNetworks = vpn.getUnderlyingNetworks();
        if (underlyingNetworks == null && (defaultNai = getDefaultNetwork()) != null) {
            underlyingNetworks = new Network[]{defaultNai.network};
        }
        if (underlyingNetworks != null && underlyingNetworks.length > 0) {
            List<String> interfaces = new ArrayList<>();
            for (Network network : underlyingNetworks) {
                LinkProperties lp = getLinkProperties(network);
                if (lp != null) {
                    for (String iface : lp.getAllInterfaceNames()) {
                        if (!TextUtils.isEmpty(iface)) {
                            interfaces.add(iface);
                        }
                    }
                }
            }
            if (!interfaces.isEmpty()) {
                info.underlyingIfaces = (String[]) interfaces.toArray(new String[interfaces.size()]);
            }
        }
        if (info.underlyingIfaces == null) {
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
                setLockdownTracker(new LockdownVpnTracker(this.mContext, this.mNMS, this, vpn, profile));
            } else {
                setLockdownTracker(null);
            }
            return true;
        }
    }

    @GuardedBy({"mVpns"})
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

    @GuardedBy({"mVpns"})
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

    public boolean setAlwaysOnVpnPackage(int userId, String packageName, boolean lockdown, List<String> lockdownWhitelist) {
        enforceControlAlwaysOnVpnPermission();
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
            } else if (vpn.setAlwaysOnPackage(packageName, lockdown, lockdownWhitelist)) {
                if (!startAlwaysOnVpn(userId)) {
                    vpn.setAlwaysOnPackage(null, false, null);
                    return false;
                }
                return true;
            } else {
                return false;
            }
        }
    }

    public String getAlwaysOnVpnPackage(int userId) {
        enforceControlAlwaysOnVpnPermission();
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

    public boolean isVpnLockdownEnabled(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.w(str, "User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.getLockdown();
        }
    }

    public List<String> getVpnLockdownWhitelist(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                String str = TAG;
                Slog.w(str, "User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getLockdownWhitelist();
        }
    }

    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        return -1;
    }

    private String getProvisioningUrlBaseFromFile() {
        String mcc;
        String mnc;
        FileReader fileReader = null;
        Configuration config = this.mContext.getResources().getConfiguration();
        String str = null;
        try {
            try {
                try {
                    try {
                        try {
                            fileReader = new FileReader(this.mProvisioningUrlFile);
                            XmlPullParser parser = Xml.newPullParser();
                            parser.setInput(fileReader);
                            XmlUtils.beginDocument(parser, TAG_PROVISIONING_URLS);
                            while (true) {
                                XmlUtils.nextElement(parser);
                                String element = parser.getName();
                                if (element == null) {
                                    try {
                                        fileReader.close();
                                    } catch (IOException e) {
                                    }
                                    return str;
                                } else if (element.equals(TAG_PROVISIONING_URL) && (mcc = parser.getAttributeValue(str, ATTR_MCC)) != null) {
                                    try {
                                        if (Integer.parseInt(mcc) == config.mcc && (mnc = parser.getAttributeValue(str, ATTR_MNC)) != null && Integer.parseInt(mnc) == config.mnc) {
                                            parser.next();
                                            if (parser.getEventType() == 4) {
                                                String text = parser.getText();
                                                try {
                                                    fileReader.close();
                                                } catch (IOException e2) {
                                                }
                                                return text;
                                            }
                                            continue;
                                        }
                                    } catch (NumberFormatException e3) {
                                        loge("NumberFormatException in getProvisioningUrlBaseFromFile: " + e3);
                                    }
                                }
                            }
                        } catch (Throwable th) {
                            if (0 != 0) {
                                try {
                                    fileReader.close();
                                } catch (IOException e4) {
                                }
                            }
                            throw th;
                        }
                    } catch (FileNotFoundException e5) {
                        loge("Carrier Provisioning Urls file not found");
                        if (fileReader != null) {
                            fileReader.close();
                        }
                        return str;
                    }
                } catch (IOException e6) {
                    loge("I/O exception reading Carrier Provisioning Urls file: " + e6);
                    if (fileReader != null) {
                        fileReader.close();
                    }
                    return str;
                }
            } catch (XmlPullParserException e7) {
                loge("Xml parser exception reading Carrier Provisioning Urls file: " + e7);
                if (fileReader != null) {
                    fileReader.close();
                }
                return str;
            }
        } catch (IOException e8) {
            return str;
        }
    }

    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile();
        if (TextUtils.isEmpty(url)) {
            url = this.mContext.getResources().getString(17040460);
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
        int id = networkType + 1 + 64512;
        try {
            this.mNotifier.setProvNotificationVisible(visible, id, action);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setAirplaneMode(boolean enable) {
        enforceNetworkStackSettingsOrSetup();
        long ident = Binder.clearCallingIdentity();
        try {
            ContentResolver cr = this.mContext.getContentResolver();
            Settings.Global.putInt(cr, "airplane_mode_on", encodeBool(enable));
            Intent intent = new Intent("android.intent.action.AIRPLANE_MODE");
            intent.putExtra("state", enable);
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
            Vpn userVpn2 = new Vpn(this.mHandler.getLooper(), this.mContext, this.mNMS, userId);
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
        this.mPermissionMonitor.onUserAdded(userId);
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
        this.mPermissionMonitor.onUserRemoved(userId);
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
    public void onPackageAdded(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            String str = TAG;
            Slog.wtf(str, "Invalid package in onPackageAdded: " + packageName + " | " + uid);
            return;
        }
        this.mPermissionMonitor.onPackageAdded(packageName, uid);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPackageReplaced(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            String str = TAG;
            Slog.wtf(str, "Invalid package in onPackageReplaced: " + packageName + " | " + uid);
            return;
        }
        int userId = UserHandle.getUserId(uid);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName)) {
                String str2 = TAG;
                Slog.d(str2, "Restarting always-on VPN package " + packageName + " for user " + userId);
                vpn.startAlwaysOnVpn();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPackageRemoved(String packageName, int uid, boolean isReplacing) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            String str = TAG;
            Slog.wtf(str, "Invalid package in onPackageRemoved: " + packageName + " | " + uid);
            return;
        }
        this.mPermissionMonitor.onPackageRemoved(uid);
        int userId = UserHandle.getUserId(uid);
        synchronized (this.mVpns) {
            Vpn vpn = this.mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName) && !isReplacing) {
                String str2 = TAG;
                Slog.d(str2, "Removing always-on VPN package " + packageName + " for user " + userId);
                vpn.setAlwaysOnPackage(null, false, null);
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
        public final int factorySerialNumber;
        public final Messenger messenger;
        public final String name;

        NetworkFactoryInfo(String name, Messenger messenger, AsyncChannel asyncChannel, int factorySerialNumber) {
            this.name = name;
            this.messenger = messenger;
            this.asyncChannel = asyncChannel;
            this.factorySerialNumber = factorySerialNumber;
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
            IBinder iBinder = this.mBinder;
            if (iBinder != null) {
                iBinder.unlinkToDeath(this, 0);
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
                str = "";
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
        if (nc.hasSignalStrength() && !checkNetworkSignalStrengthWakeupPermission(callerPid, callerUid)) {
            throw new SecurityException("Insufficient permissions to request a specific signal strength");
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
        if (VDBG || !"CONNECT".equals(reason)) {
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
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(19, nri));
        if (timeoutMs > 0) {
            InternalHandler internalHandler2 = this.mHandler;
            internalHandler2.sendMessageDelayed(internalHandler2.obtainMessage(20, nri), timeoutMs);
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
        synchronized (this.mBandwidthRequests) {
            int uid = Binder.getCallingUid();
            Integer uidReqs = this.mBandwidthRequests.get(uid);
            if (uidReqs == null) {
                uidReqs = new Integer(0);
            }
            this.mBandwidthRequests.put(uid, Integer.valueOf(uidReqs.intValue() + 1));
        }
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
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT, nri));
        return networkRequest;
    }

    private void releasePendingNetworkRequestWithDelay(PendingIntent operation) {
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessageDelayed(internalHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT, getCallingUid(), 0, operation), this.mReleasePendingIntentDelayMs);
    }

    public void releasePendingNetworkRequest(PendingIntent operation) {
        Preconditions.checkNotNull(operation, "PendingIntent cannot be null.");
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT, getCallingUid(), 0, operation));
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
        if (VDBG) {
            log("listenForNetwork for " + nri);
        }
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(21, nri));
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
        if (VDBG) {
            log("pendingListenForNetwork for " + nri);
        }
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(21, nri));
    }

    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        ensureNetworkRequestHasType(networkRequest);
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(22, getCallingUid(), 0, networkRequest));
    }

    public int registerNetworkFactory(Messenger messenger, String name) {
        enforceConnectivityInternalPermission();
        NetworkFactoryInfo nfi = new NetworkFactoryInfo(name, messenger, new AsyncChannel(), NetworkFactory.SerialNumber.nextSerialNumber());
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(17, nfi));
        return nfi.factorySerialNumber;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkFactory(NetworkFactoryInfo nfi) {
        log("Got NetworkFactory Messenger for " + nfi.name);
        this.mNetworkFactoryInfos.put(nfi.messenger, nfi);
        nfi.asyncChannel.connect(this.mContext, this.mTrackerHandler, nfi.messenger);
    }

    public void unregisterNetworkFactory(Messenger messenger) {
        enforceConnectivityInternalPermission();
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(23, messenger));
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

    @VisibleForTesting
    protected boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return nai == getDefaultNetwork();
    }

    private boolean isDefaultRequest(NetworkRequestInfo nri) {
        return nri.request.requestId == this.mDefaultRequest.requestId;
    }

    public int registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, int currentScore, NetworkMisc networkMisc) {
        return registerNetworkAgent(messenger, networkInfo, linkProperties, networkCapabilities, currentScore, networkMisc, -1);
    }

    public int registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, int currentScore, NetworkMisc networkMisc, int factorySerialNumber) {
        enforceConnectivityInternalPermission();
        LinkProperties lp = new LinkProperties(linkProperties);
        lp.ensureDirectlyConnectedRoutes();
        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(), new Network(reserveNetId()), new NetworkInfo(networkInfo), lp, nc, currentScore, this.mContext, this.mTrackerHandler, new NetworkMisc(networkMisc), this, this.mNetd, this.mDnsResolver, this.mNMS, factorySerialNumber);
        nai.setNetworkCapabilities(mixInCapabilities(nai, nc));
        String extraInfo = networkInfo.getExtraInfo();
        String name = TextUtils.isEmpty(extraInfo) ? nai.networkCapabilities.getSSID() : extraInfo;
        log("registerNetworkAgent " + nai);
        long token = Binder.clearCallingIdentity();
        try {
            getNetworkStack().makeNetworkMonitor(nai.network, name, new NetworkMonitorCallbacks(nai));
            Binder.restoreCallingIdentity(token);
            return nai.network.netId;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(token);
            throw th;
        }
    }

    @VisibleForTesting
    protected NetworkStackClient getNetworkStack() {
        return NetworkStackClient.getInstance();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterNetworkAgent(NetworkAgentInfo nai, INetworkMonitor networkMonitor) {
        nai.onNetworkMonitorCreated(networkMonitor);
        if (VDBG) {
            log("Got NetworkAgent Messenger");
        }
        this.mNetworkAgentInfos.put(nai.messenger, nai);
        synchronized (this.mNetworkForNetId) {
            this.mNetworkForNetId.put(nai.network.netId, nai);
        }
        try {
            networkMonitor.start();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        nai.asyncChannel.connect(this.mContext, this.mTrackerHandler, nai.messenger);
        NetworkInfo networkInfo = nai.networkInfo;
        nai.networkInfo = null;
        updateNetworkInfo(nai, networkInfo);
        updateUids(nai, null, nai.networkCapabilities);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties newLp, LinkProperties oldLp) {
        int netId = networkAgent.network.netId;
        networkAgent.clatd.fixupLinkProperties(oldLp, newLp);
        updateInterfaces(newLp, oldLp, netId, networkAgent.networkCapabilities);
        updateVpnFiltering(newLp, oldLp, networkAgent);
        updateMtu(newLp, oldLp);
        if (isDefaultNetwork(networkAgent)) {
            updateTcpBufferSizes(newLp.getTcpBufferSizes());
        }
        updateRoutes(newLp, oldLp, netId);
        updateDnses(newLp, oldLp, netId);
        this.mDnsManager.updatePrivateDnsStatus(netId, newLp);
        if (isDefaultNetwork(networkAgent)) {
            handleApplyDefaultProxy(newLp.getHttpProxy());
        } else {
            updateProxy(newLp, oldLp);
        }
        if (!Objects.equals(newLp, oldLp)) {
            synchronized (networkAgent) {
                networkAgent.linkProperties = newLp;
            }
            networkAgent.clatd.update();
            notifyIfacesChangedForNetworkStats();
            networkAgent.networkMonitor().notifyLinkPropertiesChanged(newLp);
            if (networkAgent.everConnected) {
                notifyNetworkCallbacks(networkAgent, 524295);
            }
        }
        this.mKeepaliveTracker.handleCheckKeepalivesStillValid(networkAgent);
    }

    private void wakeupModifyInterface(String iface, NetworkCapabilities caps, boolean add) {
        if (!caps.hasTransport(1)) {
            return;
        }
        int mark = this.mContext.getResources().getInteger(17694856);
        int mask = this.mContext.getResources().getInteger(17694857);
        if (mark == 0 || mask == 0) {
            return;
        }
        String prefix = "iface:" + iface;
        try {
            if (add) {
                this.mNetd.wakeupAddInterface(iface, prefix, mark, mask);
            } else {
                this.mNetd.wakeupDelInterface(iface, prefix, mark, mask);
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
                this.mNMS.addInterfaceToNetwork(iface, netId);
                wakeupModifyInterface(iface, caps, true);
            } catch (Exception e) {
                loge("Exception adding interface: " + e);
            }
        }
        for (String iface2 : interfaceDiff.removed) {
            try {
                log("Removing iface " + iface2 + " from network " + netId);
                wakeupModifyInterface(iface2, caps, false);
                this.mNMS.removeInterfaceFromNetwork(iface2, netId);
            } catch (Exception e2) {
                loge("Exception removing interface: " + e2);
            }
        }
    }

    private boolean updateRoutes(LinkProperties newLp, LinkProperties oldLp, int netId) {
        LinkProperties.CompareResult<RouteInfo> routeDiff = new LinkProperties.CompareResult<>(oldLp != null ? oldLp.getAllRoutes() : null, newLp != null ? newLp.getAllRoutes() : null);
        for (RouteInfo route : routeDiff.added) {
            if (!route.hasGateway()) {
                if (VDBG || DDBG) {
                    log("Adding Route [" + route + "] to network " + netId);
                }
                try {
                    this.mNMS.addRoute(netId, route);
                } catch (Exception e) {
                    if ((route.getDestination().getAddress() instanceof Inet4Address) || VDBG) {
                        loge("Exception in addRoute for non-gateway: " + e);
                    }
                }
            }
        }
        for (RouteInfo route2 : routeDiff.added) {
            if (route2.hasGateway()) {
                if (VDBG || DDBG) {
                    log("Adding Route [" + route2 + "] to network " + netId);
                }
                try {
                    this.mNMS.addRoute(netId, route2);
                } catch (Exception e2) {
                    if ((route2.getGateway() instanceof Inet4Address) || VDBG) {
                        loge("Exception in addRoute for gateway: " + e2);
                    }
                }
            }
        }
        for (RouteInfo route3 : routeDiff.removed) {
            if (VDBG || DDBG) {
                log("Removing Route [" + route3 + "] from network " + netId);
            }
            try {
                this.mNMS.removeRoute(netId, route3);
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

    private void updateVpnFiltering(LinkProperties newLp, LinkProperties oldLp, NetworkAgentInfo nai) {
        String oldIface = oldLp != null ? oldLp.getInterfaceName() : null;
        String newIface = newLp != null ? newLp.getInterfaceName() : null;
        boolean wasFiltering = requiresVpnIsolation(nai, nai.networkCapabilities, oldLp);
        boolean needsFiltering = requiresVpnIsolation(nai, nai.networkCapabilities, newLp);
        if (!wasFiltering && !needsFiltering) {
            return;
        }
        if (Objects.equals(oldIface, newIface) && wasFiltering == needsFiltering) {
            return;
        }
        Set<UidRange> ranges = nai.networkCapabilities.getUids();
        int vpnAppUid = nai.networkCapabilities.getEstablishingVpnAppUid();
        if (wasFiltering) {
            this.mPermissionMonitor.onVpnUidRangesRemoved(oldIface, ranges, vpnAppUid);
        }
        if (needsFiltering) {
            this.mPermissionMonitor.onVpnUidRangesAdded(newIface, ranges, vpnAppUid);
        }
    }

    private int getNetworkPermission(NetworkCapabilities nc) {
        if (!nc.hasCapability(13)) {
            return 2;
        }
        if (!nc.hasCapability(19)) {
            return 1;
        }
        return 0;
    }

    private void removeDefaultRoute(NetworkAgentInfo nai) {
        LinkProperties lp = nai.linkProperties;
        if (lp != null) {
            String iface = lp.getInterfaceName();
            if (Boolean.FALSE.equals(this.mDefaultRouteState.get(iface))) {
                log("removeDefaultRoute(): " + iface);
                try {
                    for (RouteInfo ri : lp.getRoutes()) {
                        if (ri.isDefaultRoute()) {
                            log("remove route " + ri);
                            this.mNMS.removeRoute(nai.network.netId, ri);
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
        if (nai.partialConnectivity) {
            newNc.addCapability(24);
        } else {
            newNc.removeCapability(24);
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
        int oldPermission = getNetworkPermission(nai.networkCapabilities);
        int newPermission = getNetworkPermission(newNc);
        if (oldPermission != newPermission && nai.created && !nai.isVPN()) {
            try {
                this.mNMS.setNetworkPermission(nai.network.netId, newPermission);
            } catch (RemoteException e) {
                loge("Exception in setNetworkPermission: " + e);
            }
        }
        synchronized (nai) {
            prevNc = nai.networkCapabilities;
            nai.setNetworkCapabilities(newNc);
        }
        updateUids(nai, prevNc, newNc);
        if (nai.getCurrentScore() == oldScore && newNc.equalRequestableCapabilities(prevNc)) {
            processListenRequests(nai, true);
        } else {
            rematchAllNetworksAndRequests(nai, oldScore);
            notifyNetworkCallbacks(nai, 524294);
        }
        if (prevNc != null) {
            boolean oldMetered = prevNc.isMetered();
            boolean newMetered = newNc.isMetered();
            boolean meteredChanged = oldMetered != newMetered;
            if (meteredChanged) {
                boolean z = this.mRestrictBackground;
                maybeNotifyNetworkBlocked(nai, oldMetered, newMetered, z, z);
            }
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

    private boolean requiresVpnIsolation(NetworkAgentInfo nai, NetworkCapabilities nc, LinkProperties lp) {
        if (nc == null || lp == null || !nai.isVPN() || nai.networkMisc.allowBypass || nc.getEstablishingVpnAppUid() == 1000 || lp.getInterfaceName() == null) {
            return false;
        }
        return lp.hasIPv4DefaultRoute() || lp.hasIPv6DefaultRoute();
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
                this.mNMS.addVpnUidRanges(nai.network.netId, addedRangesArray);
            }
            if (!prevRanges.isEmpty()) {
                UidRange[] removedRangesArray = new UidRange[prevRanges.size()];
                prevRanges.toArray(removedRangesArray);
                this.mNMS.removeVpnUidRanges(nai.network.netId, removedRangesArray);
            }
            boolean wasFiltering = requiresVpnIsolation(nai, prevNc, nai.linkProperties);
            boolean shouldFilter = requiresVpnIsolation(nai, newNc, nai.linkProperties);
            String iface = nai.linkProperties.getInterfaceName();
            if (wasFiltering && !prevRanges.isEmpty()) {
                this.mPermissionMonitor.onVpnUidRangesRemoved(iface, prevRanges, prevNc.getEstablishingVpnAppUid());
            }
            if (shouldFilter && !newRanges.isEmpty()) {
                this.mPermissionMonitor.onVpnUidRangesAdded(iface, newRanges, newNc.getEstablishingVpnAppUid());
            }
        } catch (Exception e) {
            loge("Exception in updateUids: ", e);
        }
    }

    public void handleUpdateLinkProperties(NetworkAgentInfo nai, LinkProperties newLp) {
        ensureRunningOnConnectivityServiceThread();
        if (getNetworkAgentInfoForNetId(nai.network.netId) != nai) {
            return;
        }
        newLp.ensureDirectlyConnectedRoutes();
        if (VDBG || DDBG) {
            log("Update of LinkProperties for " + nai.name() + "; created=" + nai.created + "; everConnected=" + nai.everConnected);
        }
        updateLinkProperties(nai, newLp, new LinkProperties(nai.linkProperties));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            if (!nr.isListen()) {
                sendUpdatedScoreToFactories(nr, nai);
            }
        }
    }

    private void sendUpdatedScoreToFactories(NetworkRequest networkRequest, NetworkAgentInfo nai) {
        int score = 0;
        int serial = 0;
        if (nai != null) {
            score = nai.getCurrentScore();
            serial = nai.factorySerialNumber;
        }
        if (VDBG || DDBG) {
            log("sending new Min Network Score(" + score + "): " + networkRequest.toString());
        }
        for (NetworkFactoryInfo nfi : this.mNetworkFactoryInfos.values()) {
            nfi.asyncChannel.sendMessage(536576, score, serial, networkRequest);
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
                putParcelable(bundle, networkCapabilitiesRestrictedForCallerPermissions(networkAgent.networkCapabilities, nri.mPid, nri.mUid));
                putParcelable(bundle, new LinkProperties(networkAgent.linkProperties));
                msg.arg1 = arg1;
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
            case 524299:
                maybeLogBlockedStatusChanged(nri, networkAgent.network, arg1 != 0);
                msg.arg1 = arg1;
                break;
        }
        msg.what = notificationType;
        msg.setData(bundle);
        try {
            if (VDBG) {
                String notification = ConnectivityManager.getCallbackName(notificationType);
                log("sending notification " + notification + " for " + nri.request);
            }
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

    private void addSystemApnRule(String ifaceName) {
        boolean isTbox = this.mDefaultNetSubtype == 3;
        String secondIp = "172.20.1.3";
        String gateway = "172.20.1.44";
        if (isTbox) {
            secondIp = TBOX_SECONDARY_IP;
            gateway = TBOX_GATEWAY;
        }
        this.mSystemApnMgr.addSystemApnRoute(ifaceName, gateway, secondIp);
        this.mGidSet.clear();
        this.mGidSet.add(1000);
        if (SystemApnManager.isSystemApnConfigExist()) {
            Set<Integer> gids = this.mSystemApnMgr.getSystemApnAppGids();
            this.mGidSet.addAll(gids);
        } else {
            this.mGidSet.add(10000);
        }
        for (Integer num : this.mGidSet) {
            int gid = num.intValue();
            this.mSystemApnMgr.addSystemApnNatRule(ifaceName, secondIp, SYSTEM_APN_MARK, gid);
        }
        String str = TAG;
        Slog.i(str, "addSystemApnRule(): success for " + ifaceName);
    }

    private void removeSystemApnRule(String ifaceName) {
        boolean isTbox = this.mDefaultNetSubtype == 3;
        String secondIp = "172.20.1.3";
        String gateway = "172.20.1.44";
        if (isTbox) {
            secondIp = TBOX_SECONDARY_IP;
            gateway = TBOX_GATEWAY;
        }
        for (Integer num : this.mGidSet) {
            int gid = num.intValue();
            this.mSystemApnMgr.removeSystemApnNatRule(ifaceName, secondIp, SYSTEM_APN_MARK, gid);
        }
        this.mSystemApnMgr.removeSystemApnRoute(ifaceName, gateway, secondIp);
        String str = TAG;
        Slog.i(str, "removeSystemApnRule(): done for " + ifaceName);
    }

    private boolean isIpConfigFileModified() {
        File file = new File(FILE_IP_WHITELIST_CONFIG);
        long lastModified = this.mIpConfigFileModifiedTime;
        this.mIpConfigFileModifiedTime = file.lastModified();
        return lastModified != this.mIpConfigFileModifiedTime;
    }

    private boolean isIpConfigFileExists() {
        File file = new File(FILE_IP_WHITELIST_CONFIG);
        return file.exists();
    }

    private void readIpWhitelist(String fileName) {
        BufferedReader br = null;
        try {
            try {
                br = new BufferedReader(new FileReader(fileName));
                List<String> ipList = new ArrayList<>();
                while (true) {
                    String line = br.readLine();
                    if (line == null) {
                        break;
                    } else if (!line.startsWith("#") && !TextUtils.isEmpty(line)) {
                        ipList.add(line);
                    }
                }
                log("readIpWhitelist(): ip size = " + this.mIpWhitelist.size());
                for (String ipAddr : ipList) {
                    if (!this.mIpWhitelist.contains(ipAddr)) {
                        this.mIpWhitelist.add(ipAddr);
                    }
                }
            } catch (Exception e) {
                loge("fail to read file: " + fileName);
                if (br == null) {
                    return;
                }
            }
            IoUtils.closeQuietly(br);
        } catch (Throwable th) {
            if (br != null) {
                IoUtils.closeQuietly(br);
            }
            throw th;
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:15:0x0053 -> B:19:0x005e). Please submit an issue!!! */
    private void enableFirewallRule(String iface) {
        if (isIpConfigFileExists()) {
            log("enableFirewallRule(): " + iface);
            if (isIpConfigFileModified()) {
                readIpWhitelist(FILE_IP_WHITELIST_CONFIG);
            }
            try {
                if (this.mIpWhitelist.size() > 0) {
                    String[] ipAddrs = new String[this.mIpWhitelist.size()];
                    boolean res = this.mNetd.addIpFilterRule(iface, (String[]) this.mIpWhitelist.toArray(ipAddrs));
                    if (!res) {
                        loge("addIpFilterRule(): failed");
                    } else {
                        log("addIpFilterRule(): success");
                    }
                }
            } catch (Exception e) {
                loge("fail to add ip filter rule");
            }
            return;
        }
        log("ip firewall is not enabled");
    }

    private void disableFirewallRule(String iface) {
        if (isIpConfigFileExists()) {
            log("disableFirewallRule(): " + iface);
            try {
                if (this.mIpWhitelist.size() > 0) {
                    String[] ipAddrs = new String[this.mIpWhitelist.size()];
                    this.mNetd.removeIpFilterRule(iface, (String[]) this.mIpWhitelist.toArray(ipAddrs));
                    return;
                }
                return;
            } catch (Exception e) {
                loge("fail to remove ip filter rule");
                return;
            }
        }
        log("ip firewall is not enabled");
    }

    private void makeDefault(NetworkAgentInfo newNetwork) {
        log("Switching to new default network: " + newNetwork);
        try {
            this.mNMS.setDefaultNetId(newNetwork.network.netId);
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }
        notifyLockdownVpn(newNetwork);
        handleApplyDefaultProxy(newNetwork.linkProperties.getHttpProxy());
        updateTcpBufferSizes(newNetwork.linkProperties.getTcpBufferSizes());
        this.mDnsManager.setDefaultDnsSystemProperties(newNetwork.linkProperties.getDnsServers());
        notifyIfacesChangedForNetworkStats();
        updateAllVpnsCapabilities();
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

    private void rematchNetworkAndRequests(NetworkAgentInfo newNetwork, ReapUnvalidatedNetworks reapUnvalidatedNetworks, long now) {
        boolean wasBackgroundNetwork;
        int score;
        NetworkAgentInfo oldDefaultNetwork;
        NetworkAgentInfo oldDefaultNetwork2;
        NetworkAgentInfo currentNetwork;
        if (newNetwork.everConnected) {
            boolean keep = newNetwork.isVPN();
            boolean wasBackgroundNetwork2 = newNetwork.isBackgroundNetwork();
            int score2 = newNetwork.getCurrentScore();
            if (VDBG || DDBG) {
                log("rematching " + newNetwork.name());
            }
            ArrayList<NetworkAgentInfo> affectedNetworks = new ArrayList<>();
            ArrayList<NetworkRequestInfo> addedRequests = new ArrayList<>();
            NetworkCapabilities nc = newNetwork.networkCapabilities;
            if (VDBG) {
                log(" network has: " + nc);
            }
            Iterator<NetworkRequestInfo> it = this.mNetworkRequests.values().iterator();
            boolean keep2 = keep;
            boolean isNewDefault = false;
            NetworkAgentInfo oldDefaultNetwork3 = null;
            while (true) {
                boolean keep3 = it.hasNext();
                if (!keep3) {
                    break;
                }
                NetworkRequestInfo nri = it.next();
                if (!nri.request.isListen()) {
                    NetworkAgentInfo currentNetwork2 = getNetworkForRequest(nri.request.requestId);
                    boolean satisfies = newNetwork.satisfies(nri.request);
                    if (newNetwork == currentNetwork2 && satisfies) {
                        if (VDBG) {
                            log("Network " + newNetwork.name() + " was already satisfying request " + nri.request.requestId + ". No change.");
                        }
                        keep2 = true;
                    } else {
                        if (VDBG) {
                            log("  checking if request is satisfied: " + nri.request);
                        }
                        if (satisfies) {
                            if (VDBG || DDBG) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("currentScore = ");
                                sb.append(currentNetwork2 != null ? currentNetwork2.getCurrentScore() : 0);
                                sb.append(", newScore = ");
                                sb.append(score2);
                                log(sb.toString());
                            }
                            if (currentNetwork2 == null || currentNetwork2.getCurrentScore() < score2) {
                                if (VDBG) {
                                    log("rematch for " + newNetwork.name());
                                }
                                if (currentNetwork2 != null) {
                                    if (VDBG || DDBG) {
                                        log("   accepting network in place of " + currentNetwork2.name());
                                    }
                                    currentNetwork2.removeRequest(nri.request.requestId);
                                    wasBackgroundNetwork = wasBackgroundNetwork2;
                                    score = score2;
                                    oldDefaultNetwork2 = oldDefaultNetwork3;
                                    currentNetwork = currentNetwork2;
                                    currentNetwork2.lingerRequest(nri.request, now, this.mLingerDelayMs);
                                    affectedNetworks.add(currentNetwork);
                                } else {
                                    wasBackgroundNetwork = wasBackgroundNetwork2;
                                    score = score2;
                                    oldDefaultNetwork2 = oldDefaultNetwork3;
                                    currentNetwork = currentNetwork2;
                                    if (VDBG || DDBG) {
                                        log("   accepting network in place of null");
                                    }
                                }
                                newNetwork.unlingerRequest(nri.request);
                                setNetworkForRequest(nri.request.requestId, newNetwork);
                                if (!newNetwork.addRequest(nri.request)) {
                                    Slog.wtf(TAG, "BUG: " + newNetwork.name() + " already has " + nri.request);
                                }
                                addedRequests.add(nri);
                                sendUpdatedScoreToFactories(nri.request, newNetwork);
                                if (!isDefaultRequest(nri)) {
                                    keep2 = true;
                                    oldDefaultNetwork3 = oldDefaultNetwork2;
                                } else {
                                    NetworkAgentInfo oldDefaultNetwork4 = currentNetwork;
                                    if (currentNetwork != null) {
                                        this.mLingerMonitor.noteLingerDefaultNetwork(currentNetwork, newNetwork);
                                    }
                                    keep2 = true;
                                    isNewDefault = true;
                                    oldDefaultNetwork3 = oldDefaultNetwork4;
                                }
                                wasBackgroundNetwork2 = wasBackgroundNetwork;
                                score2 = score;
                            } else {
                                wasBackgroundNetwork = wasBackgroundNetwork2;
                                score = score2;
                                oldDefaultNetwork = oldDefaultNetwork3;
                            }
                        } else {
                            wasBackgroundNetwork = wasBackgroundNetwork2;
                            score = score2;
                            oldDefaultNetwork = oldDefaultNetwork3;
                            if (newNetwork.isSatisfyingRequest(nri.request.requestId)) {
                                log("Network " + newNetwork.name() + " stopped satisfying request " + nri.request.requestId);
                                newNetwork.removeRequest(nri.request.requestId);
                                if (currentNetwork2 == newNetwork) {
                                    clearNetworkForRequest(nri.request.requestId);
                                    sendUpdatedScoreToFactories(nri.request, null);
                                } else {
                                    Slog.wtf(TAG, "BUG: Removing request " + nri.request.requestId + " from " + newNetwork.name() + " without updating mNetworkForRequestId or factories!");
                                }
                                callCallbackForRequest(nri, newNetwork, 524292, 0);
                            }
                        }
                        oldDefaultNetwork3 = oldDefaultNetwork;
                        wasBackgroundNetwork2 = wasBackgroundNetwork;
                        score2 = score;
                    }
                }
            }
            boolean wasBackgroundNetwork3 = wasBackgroundNetwork2;
            int score3 = score2;
            NetworkAgentInfo oldDefaultNetwork5 = oldDefaultNetwork3;
            if (isNewDefault) {
                updateDataActivityTracking(newNetwork, oldDefaultNetwork5);
                makeDefault(newNetwork);
                metricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(now, newNetwork, oldDefaultNetwork5);
                scheduleReleaseNetworkTransitionWakelock();
            }
            if (!newNetwork.networkCapabilities.equalRequestableCapabilities(nc)) {
                Slog.wtf(TAG, String.format("BUG: %s changed requestable capabilities during rematch: %s -> %s", newNetwork.name(), nc, newNetwork.networkCapabilities));
            }
            if (newNetwork.getCurrentScore() != score3) {
                Slog.wtf(TAG, String.format("BUG: %s changed score during rematch: %d -> %d", newNetwork.name(), Integer.valueOf(score3), Integer.valueOf(newNetwork.getCurrentScore())));
            }
            if (wasBackgroundNetwork3 != newNetwork.isBackgroundNetwork()) {
                updateCapabilities(score3, newNetwork, newNetwork.networkCapabilities);
            } else {
                processListenRequests(newNetwork, false);
            }
            Iterator<NetworkRequestInfo> it2 = addedRequests.iterator();
            while (it2.hasNext()) {
                notifyNetworkAvailable(newNetwork, it2.next());
            }
            Iterator<NetworkAgentInfo> it3 = affectedNetworks.iterator();
            while (it3.hasNext()) {
                updateLingerState(it3.next(), now);
            }
            updateLingerState(newNetwork, now);
            if (isNewDefault) {
                if (oldDefaultNetwork5 != null && !oldDefaultNetwork5.networkInfo.isPersist()) {
                    this.mLegacyTypeTracker.remove(oldDefaultNetwork5.networkInfo.getType(), oldDefaultNetwork5, true);
                } else {
                    Slog.w(TAG, "persistent network should not be removed");
                }
                this.mDefaultInetConditionPublished = newNetwork.lastValidated ? 100 : 0;
                if (newNetwork == this.mLegacyTypeTracker.getNetworkForType(newNetwork.networkInfo.getType()) && newNetwork.networkInfo.isPersist()) {
                    sendLegacyNetworkBroadcast(newNetwork, NetworkInfo.DetailedState.CONNECTED, newNetwork.networkInfo.getType());
                } else {
                    this.mLegacyTypeTracker.add(newNetwork.networkInfo.getType(), newNetwork);
                }
                notifyLockdownVpn(newNetwork);
            }
            if (keep2) {
                try {
                    IBatteryStats bs = BatteryStatsService.getService();
                    int type = newNetwork.networkInfo.getType();
                    String baseIface = newNetwork.linkProperties.getInterfaceName();
                    bs.noteNetworkInterfaceType(baseIface, type);
                    for (LinkProperties stacked : newNetwork.linkProperties.getStackedLinks()) {
                        String stackedIface = stacked.getInterfaceName();
                        bs.noteNetworkInterfaceType(stackedIface, type);
                    }
                } catch (RemoteException e) {
                }
                for (int i = 0; i < newNetwork.numNetworkRequests(); i++) {
                    NetworkRequest nr = newNetwork.requestAt(i);
                    if (nr.legacyType != -1 && nr.isRequest()) {
                        this.mLegacyTypeTracker.add(nr.legacyType, newNetwork);
                    }
                }
                if (newNetwork.isVPN()) {
                    this.mLegacyTypeTracker.add(17, newNetwork);
                }
            }
            if (reapUnvalidatedNetworks == ReapUnvalidatedNetworks.REAP) {
                for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
                    if (unneeded(nai, UnneededFor.TEARDOWN)) {
                        if (nai.getLingerExpiry() > 0) {
                            updateLingerState(nai, now);
                        } else {
                            log("Reaping " + nai.name());
                            teardownUnneededNetwork(nai);
                        }
                    }
                }
            }
        }
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

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo newInfo) {
        NetworkInfo oldInfo;
        int i;
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
            if (!createNativeNetwork(networkAgent)) {
                return;
            }
            networkAgent.created = true;
        }
        if (!networkAgent.everConnected && state == NetworkInfo.State.CONNECTED) {
            networkAgent.everConnected = true;
            if (networkAgent.linkProperties == null) {
                String str = TAG;
                Slog.wtf(str, networkAgent.name() + " connected with null LinkProperties");
            }
            synchronized (networkAgent) {
                networkAgent.setNetworkCapabilities(networkAgent.networkCapabilities);
            }
            handlePerNetworkPrivateDnsConfig(networkAgent, this.mDnsManager.getPrivateDnsConfig());
            updateLinkProperties(networkAgent, new LinkProperties(networkAgent.linkProperties), null);
            if (networkAgent.networkMisc.acceptPartialConnectivity) {
                networkAgent.networkMonitor().setAcceptPartialConnectivity();
            }
            networkAgent.networkMonitor().notifyNetworkConnected(networkAgent.linkProperties, networkAgent.networkCapabilities);
            scheduleUnvalidatedPrompt(networkAgent);
            updateSignalStrengthThresholds(networkAgent, "CONNECT", null);
            if (networkAgent.isVPN()) {
                updateAllVpnsCapabilities();
            }
            LinkProperties lp = networkAgent.linkProperties;
            if (newInfo.isPersist() && newInfo.getSubtype() == this.mDefaultNetSubtype) {
                addSystemApnRule(lp.getInterfaceName());
            }
            if (newInfo.isPersist() && newInfo.getSubtype() == 1) {
                enableFirewallRule(lp.getInterfaceName());
            }
            this.mDefaultRouteState.put(networkAgent.linkProperties.getInterfaceName(), false);
            long now = SystemClock.elapsedRealtime();
            rematchNetworkAndRequests(networkAgent, ReapUnvalidatedNetworks.REAP, now);
            notifyNetworkCallbacks(networkAgent, 524289);
        } else if (state == NetworkInfo.State.DISCONNECTED) {
            networkAgent.asyncChannel.disconnect();
            if (networkAgent.isVPN()) {
                updateUids(networkAgent, networkAgent.networkCapabilities, null);
            }
            disconnectAndDestroyNetwork(networkAgent);
            if (networkAgent.isVPN()) {
                this.mProxyTracker.sendProxyBroadcast();
            }
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
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNetworkScore(NetworkAgentInfo nai, int score) {
        if (VDBG || DDBG) {
            log("updateNetworkScore for " + nai.name() + " to " + score);
        }
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
            return;
        }
        boolean metered = nai.networkCapabilities.isMetered();
        callCallbackForRequest(nri, nai, 524290, isUidNetworkingWithVpnBlocked(nri.mUid, this.mUidRules.get(nri.mUid), metered, this.mRestrictBackground) ? 1 : 0);
    }

    private void maybeNotifyNetworkBlocked(NetworkAgentInfo nai, boolean oldMetered, boolean newMetered, boolean oldRestrictBackground, boolean newRestrictBackground) {
        boolean oldBlocked;
        boolean newBlocked;
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            NetworkRequestInfo nri = this.mNetworkRequests.get(nr);
            int uidRules = this.mUidRules.get(nri.mUid);
            synchronized (this.mVpns) {
                oldBlocked = isUidNetworkingWithVpnBlocked(nri.mUid, uidRules, oldMetered, oldRestrictBackground);
                newBlocked = isUidNetworkingWithVpnBlocked(nri.mUid, uidRules, newMetered, newRestrictBackground);
            }
            if (oldBlocked != newBlocked) {
                callCallbackForRequest(nri, nai, 524299, encodeBool(newBlocked));
            }
        }
    }

    private void maybeNotifyNetworkBlockedForNewUidRules(int uid, int newRules) {
        boolean oldBlocked;
        boolean newBlocked;
        for (NetworkAgentInfo nai : this.mNetworkAgentInfos.values()) {
            boolean metered = nai.networkCapabilities.isMetered();
            synchronized (this.mVpns) {
                oldBlocked = isUidNetworkingWithVpnBlocked(uid, this.mUidRules.get(uid), metered, this.mRestrictBackground);
                newBlocked = isUidNetworkingWithVpnBlocked(uid, newRules, metered, this.mRestrictBackground);
            }
            if (oldBlocked != newBlocked) {
                int arg = encodeBool(newBlocked);
                for (int i = 0; i < nai.numNetworkRequests(); i++) {
                    NetworkRequest nr = nai.requestAt(i);
                    NetworkRequestInfo nri = this.mNetworkRequests.get(nr);
                    if (nri != null && nri.mUid == uid) {
                        callCallbackForRequest(nri, nai, 524299, arg);
                    }
                }
            }
        }
    }

    @VisibleForTesting
    protected void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, NetworkInfo.DetailedState state, int type) {
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
        if (VDBG || DDBG) {
            String notification = ConnectivityManager.getCallbackName(notifyType);
            log("notifyType " + notification + " for " + networkAgent.name());
        }
        for (int i = 0; i < networkAgent.numNetworkRequests(); i++) {
            NetworkRequest nr = networkAgent.requestAt(i);
            NetworkRequestInfo nri = this.mNetworkRequests.get(nr);
            if (VDBG) {
                log(" sending notification for " + nr);
            }
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
            this.mStatsService.forceUpdateIfaces(getDefaultNetworks(), getAllNetworkState(), activeIface);
        } catch (Exception e) {
        }
        NetworkStatsFactory.updateVpnInfos(getAllVpnInfo());
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
            this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$wEKoEHzzx8c8LHcdMnzMC51eHgc
                @Override // java.lang.Runnable
                public final void run() {
                    ConnectivityService.this.lambda$setUnderlyingNetworksForVpn$6$ConnectivityService();
                }
            });
        }
        return success;
    }

    public /* synthetic */ void lambda$setUnderlyingNetworksForVpn$6$ConnectivityService() {
        updateAllVpnsCapabilities();
        notifyIfacesChangedForNetworkStats();
    }

    public String getCaptivePortalServerUrl() {
        enforceConnectivityInternalPermission();
        String settingUrl = this.mContext.getResources().getString(17039764);
        if (!TextUtils.isEmpty(settingUrl)) {
            return settingUrl;
        }
        String settingUrl2 = Settings.Global.getString(this.mContext.getContentResolver(), "captive_portal_http_url");
        if (!TextUtils.isEmpty(settingUrl2)) {
            return settingUrl2;
        }
        return DEFAULT_CAPTIVE_PORTAL_HTTP_URL;
    }

    public void startNattKeepalive(Network network, int intervalSeconds, ISocketKeepaliveCallback cb, String srcAddr, int srcPort, String dstAddr) {
        enforceKeepalivePermission();
        this.mKeepaliveTracker.startNattKeepalive(getNetworkAgentInfoForNetwork(network), (FileDescriptor) null, intervalSeconds, cb, srcAddr, srcPort, dstAddr, 4500);
    }

    public void startNattKeepaliveWithFd(Network network, FileDescriptor fd, int resourceId, int intervalSeconds, ISocketKeepaliveCallback cb, String srcAddr, String dstAddr) {
        this.mKeepaliveTracker.startNattKeepalive(getNetworkAgentInfoForNetwork(network), fd, resourceId, intervalSeconds, cb, srcAddr, dstAddr, 4500);
    }

    public void startTcpKeepalive(Network network, FileDescriptor fd, int intervalSeconds, ISocketKeepaliveCallback cb) {
        enforceKeepalivePermission();
        this.mKeepaliveTracker.startTcpKeepalive(getNetworkAgentInfoForNetwork(network), fd, intervalSeconds, cb);
    }

    public void stopKeepalive(Network network, int slot) {
        InternalHandler internalHandler = this.mHandler;
        internalHandler.sendMessage(internalHandler.obtainMessage(528396, slot, 0, network));
    }

    public void factoryReset() {
        String[] tetheredIfaces;
        enforceConnectivityInternalPermission();
        if (this.mUserManager.hasUserRestriction("no_network_reset")) {
            return;
        }
        int userId = UserHandle.getCallingUserId();
        Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.-$$Lambda$ConnectivityService$xryV-W-aAQV-ZmIyomcgBWdXuZI
            public final void runOrThrow() {
                ConnectivityService.this.lambda$factoryReset$7$ConnectivityService();
            }
        });
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
                    setAlwaysOnVpnPackage(userId, null, false, null);
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
        if (!this.mUserManager.hasUserRestriction("disallow_config_private_dns")) {
            Settings.Global.putString(this.mContext.getContentResolver(), "private_dns_mode", "opportunistic");
        }
        Settings.Global.putString(this.mContext.getContentResolver(), "network_avoid_bad_wifi", null);
    }

    public /* synthetic */ void lambda$factoryReset$7$ConnectivityService() throws Exception {
        IpMemoryStore ipMemoryStore = IpMemoryStore.getMemoryStore(this.mContext);
        ipMemoryStore.factoryReset();
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

        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            PrintWriter pw = getOutPrintWriter();
            try {
                if (!((cmd.hashCode() == 144736062 && cmd.equals("airplane-mode")) ? false : true)) {
                    String action = getNextArg();
                    if (xpInputManagerService.InputPolicyKey.KEY_ENABLE.equals(action)) {
                        ConnectivityService.this.setAirplaneMode(true);
                        return 0;
                    } else if ("disable".equals(action)) {
                        ConnectivityService.this.setAirplaneMode(false);
                        return 0;
                    } else if (action == null) {
                        ContentResolver cr = ConnectivityService.this.mContext.getContentResolver();
                        int enabled = Settings.Global.getInt(cr, "airplane_mode_on");
                        pw.println(enabled == 0 ? "disabled" : "enabled");
                        return 0;
                    } else {
                        onHelp();
                        return -1;
                    }
                }
                return handleDefaultCommands(cmd);
            } catch (Exception e) {
                pw.println(e);
                return -1;
            }
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

    @GuardedBy({"mVpns"})
    private Vpn getVpnIfOwner() {
        VpnInfo info;
        int uid = Binder.getCallingUid();
        int user = UserHandle.getUserId(uid);
        Vpn vpn = this.mVpns.get(user);
        if (vpn == null || (info = vpn.getVpnInfo()) == null || info.ownerUid != uid) {
            return null;
        }
        return vpn;
    }

    private Vpn enforceActiveVpnOrNetworkStackPermission() {
        if (checkNetworkStackPermission()) {
            return null;
        }
        synchronized (this.mVpns) {
            Vpn vpn = getVpnIfOwner();
            if (vpn != null) {
                return vpn;
            }
            throw new SecurityException("App must either be an active VPN or have the NETWORK_STACK permission");
        }
    }

    public int getConnectionOwnerUid(ConnectionInfo connectionInfo) {
        Vpn vpn = enforceActiveVpnOrNetworkStackPermission();
        if (connectionInfo.protocol != OsConstants.IPPROTO_TCP && connectionInfo.protocol != OsConstants.IPPROTO_UDP) {
            throw new IllegalArgumentException("Unsupported protocol " + connectionInfo.protocol);
        }
        int uid = InetDiagMessage.getConnectionOwnerUid(connectionInfo.protocol, connectionInfo.local, connectionInfo.remote);
        if (vpn != null && !vpn.appliesToUid(uid)) {
            return -1;
        }
        return uid;
    }

    public boolean isCallerCurrentAlwaysOnVpnApp() {
        boolean z;
        synchronized (this.mVpns) {
            Vpn vpn = getVpnIfOwner();
            z = vpn != null && vpn.getAlwaysOn();
        }
        return z;
    }

    public boolean isCallerCurrentAlwaysOnVpnLockdownApp() {
        boolean z;
        synchronized (this.mVpns) {
            Vpn vpn = getVpnIfOwner();
            z = vpn != null && vpn.getLockdown();
        }
        return z;
    }

    public IBinder startOrGetTestNetworkService() {
        ITestNetworkManager.Stub stub;
        synchronized (this.mTNSLock) {
            TestNetworkService.enforceTestNetworkPermissions(this.mContext);
            if (this.mTNS == null) {
                this.mTNS = new TestNetworkService(this.mContext, this.mNMS);
            }
            stub = this.mTNS;
        }
        return stub;
    }
}
