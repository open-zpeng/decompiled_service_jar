package android.net.ip;

import android.content.Context;
import android.net.DhcpResults;
import android.net.INetd;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.net.apf.ApfFilter;
import android.net.dhcp.DhcpClient;
import android.net.ip.IpClient;
import android.net.ip.IpReachabilityMonitor;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.IpManagerEvent;
import android.net.util.InterfaceParams;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.NetdService;
import android.net.util.SharedLog;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IState;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.net.NetlinkTracker;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
/* loaded from: classes.dex */
public class IpClient extends StateMachine {
    private static final String CLAT_PREFIX = "v4-";
    private static final int CMD_CONFIRM = 4;
    private static final int CMD_SET_MULTICAST_FILTER = 9;
    private static final int CMD_START = 3;
    private static final int CMD_STOP = 2;
    private static final int CMD_TERMINATE_AFTER_STOP = 1;
    private static final int CMD_UPDATE_HTTP_PROXY = 8;
    private static final int CMD_UPDATE_TCP_BUFFER_SIZES = 7;
    private static final boolean DBG = false;
    public static final String DUMP_ARG = "ipclient";
    public static final String DUMP_ARG_CONFIRM = "confirm";
    private static final int EVENT_DHCPACTION_TIMEOUT = 11;
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 6;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE = 5;
    private static final int EVENT_PROVISIONING_TIMEOUT = 10;
    private static final int EVENT_READ_PACKET_FILTER_COMPLETE = 12;
    private static final int IMMEDIATE_FAILURE_DURATION = 0;
    private static final int MAX_LOG_RECORDS = 500;
    private static final int MAX_PACKET_RECORDS = 100;
    private static final boolean NO_CALLBACKS = false;
    private static final boolean SEND_CALLBACKS = true;
    private final ConditionVariable mApfDataSnapshotComplete;
    private ApfFilter mApfFilter;
    @VisibleForTesting
    protected final Callback mCallback;
    private final String mClatInterfaceName;
    private ProvisioningConfiguration mConfiguration;
    private final LocalLog mConnectivityPacketLog;
    private final Context mContext;
    private final Dependencies mDependencies;
    private final WakeupMessage mDhcpActionTimeoutAlarm;
    private DhcpClient mDhcpClient;
    private DhcpResults mDhcpResults;
    private ProxyInfo mHttpProxy;
    private final InterfaceController mInterfaceCtrl;
    private final String mInterfaceName;
    private InterfaceParams mInterfaceParams;
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private LinkProperties mLinkProperties;
    private final SharedLog mLog;
    private final IpConnectivityLog mMetricsLog;
    private final MessageHandlingLogger mMsgStateLogger;
    private boolean mMulticastFiltering;
    private MultinetworkPolicyTracker mMultinetworkPolicyTracker;
    private final NetlinkTracker mNetlinkTracker;
    private final INetworkManagementService mNwService;
    private final WakeupMessage mProvisioningTimeoutAlarm;
    private final State mRunningState;
    private final CountDownLatch mShutdownLatch;
    private long mStartTimeMillis;
    private final State mStartedState;
    private final State mStoppedState;
    private final State mStoppingState;
    private final String mTag;
    private String mTcpBufferSizes;
    private static final Class[] sMessageClasses = {IpClient.class, DhcpClient.class};
    private static final SparseArray<String> sWhatToString = MessageUtils.findMessageNames(sMessageClasses);
    private static final ConcurrentHashMap<String, SharedLog> sSmLogs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LocalLog> sPktLogs = new ConcurrentHashMap<>();

    public static void dumpAllLogs(PrintWriter writer, String[] args) {
        for (String ifname : sSmLogs.keySet()) {
            if (ArrayUtils.isEmpty(args) || ArrayUtils.contains(args, ifname)) {
                writer.println(String.format("--- BEGIN %s ---", ifname));
                SharedLog smLog = sSmLogs.get(ifname);
                if (smLog != null) {
                    writer.println("State machine log:");
                    smLog.dump(null, writer, null);
                }
                writer.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                LocalLog pktLog = sPktLogs.get(ifname);
                if (pktLog != null) {
                    writer.println("Connectivity packet log:");
                    pktLog.readOnlyLocalLog().dump((FileDescriptor) null, writer, (String[]) null);
                }
                writer.println(String.format("--- END %s ---", ifname));
            }
        }
    }

    /* loaded from: classes.dex */
    public static class Callback {
        public void onPreDhcpAction() {
        }

        public void onPostDhcpAction() {
        }

        public void onNewDhcpResults(DhcpResults dhcpResults) {
        }

        public void onProvisioningSuccess(LinkProperties newLp) {
        }

        public void onProvisioningFailure(LinkProperties newLp) {
        }

        public void onLinkPropertiesChange(LinkProperties newLp) {
        }

        public void onReachabilityLost(String logMsg) {
        }

        public void onQuit() {
        }

        public void installPacketFilter(byte[] filter) {
        }

        public void startReadPacketFilter() {
        }

        public void setFallbackMulticastFilter(boolean enabled) {
        }

        public void setNeighborDiscoveryOffload(boolean enable) {
        }
    }

    /* loaded from: classes.dex */
    public static class WaitForProvisioningCallback extends Callback {
        private final ConditionVariable mCV = new ConditionVariable();
        private LinkProperties mCallbackLinkProperties;

        public LinkProperties waitForProvisioning() {
            this.mCV.block();
            return this.mCallbackLinkProperties;
        }

        @Override // android.net.ip.IpClient.Callback
        public void onProvisioningSuccess(LinkProperties newLp) {
            this.mCallbackLinkProperties = newLp;
            this.mCV.open();
        }

        @Override // android.net.ip.IpClient.Callback
        public void onProvisioningFailure(LinkProperties newLp) {
            this.mCallbackLinkProperties = null;
            this.mCV.open();
        }
    }

    /* loaded from: classes.dex */
    private class LoggingCallbackWrapper extends Callback {
        private static final String PREFIX = "INVOKE ";
        private Callback mCallback;

        public LoggingCallbackWrapper(Callback callback) {
            this.mCallback = callback;
        }

        private void log(String msg) {
            SharedLog sharedLog = IpClient.this.mLog;
            sharedLog.log(PREFIX + msg);
        }

        @Override // android.net.ip.IpClient.Callback
        public void onPreDhcpAction() {
            this.mCallback.onPreDhcpAction();
            log("onPreDhcpAction()");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onPostDhcpAction() {
            this.mCallback.onPostDhcpAction();
            log("onPostDhcpAction()");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onNewDhcpResults(DhcpResults dhcpResults) {
            this.mCallback.onNewDhcpResults(dhcpResults);
            log("onNewDhcpResults({" + dhcpResults + "})");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onProvisioningSuccess(LinkProperties newLp) {
            this.mCallback.onProvisioningSuccess(newLp);
            log("onProvisioningSuccess({" + newLp + "})");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onProvisioningFailure(LinkProperties newLp) {
            this.mCallback.onProvisioningFailure(newLp);
            log("onProvisioningFailure({" + newLp + "})");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onLinkPropertiesChange(LinkProperties newLp) {
            this.mCallback.onLinkPropertiesChange(newLp);
            log("onLinkPropertiesChange({" + newLp + "})");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onReachabilityLost(String logMsg) {
            this.mCallback.onReachabilityLost(logMsg);
            log("onReachabilityLost(" + logMsg + ")");
        }

        @Override // android.net.ip.IpClient.Callback
        public void onQuit() {
            this.mCallback.onQuit();
            log("onQuit()");
        }

        @Override // android.net.ip.IpClient.Callback
        public void installPacketFilter(byte[] filter) {
            this.mCallback.installPacketFilter(filter);
            log("installPacketFilter(byte[" + filter.length + "])");
        }

        @Override // android.net.ip.IpClient.Callback
        public void startReadPacketFilter() {
            this.mCallback.startReadPacketFilter();
            log("startReadPacketFilter()");
        }

        @Override // android.net.ip.IpClient.Callback
        public void setFallbackMulticastFilter(boolean enabled) {
            this.mCallback.setFallbackMulticastFilter(enabled);
            log("setFallbackMulticastFilter(" + enabled + ")");
        }

        @Override // android.net.ip.IpClient.Callback
        public void setNeighborDiscoveryOffload(boolean enable) {
            this.mCallback.setNeighborDiscoveryOffload(enable);
            log("setNeighborDiscoveryOffload(" + enable + ")");
        }
    }

    /* loaded from: classes.dex */
    public static class ProvisioningConfiguration {
        private static final int DEFAULT_TIMEOUT_MS = 36000;
        ApfCapabilities mApfCapabilities;
        String mDisplayName;
        boolean mEnableIPv4;
        boolean mEnableIPv6;
        int mIPv6AddrGenMode;
        InitialConfiguration mInitialConfig;
        Network mNetwork;
        int mProvisioningTimeoutMs;
        int mRequestedPreDhcpActionMs;
        StaticIpConfiguration mStaticIpConfig;
        boolean mUsingIpReachabilityMonitor;
        boolean mUsingMultinetworkPolicyTracker;

        /* loaded from: classes.dex */
        public static class Builder {
            private ProvisioningConfiguration mConfig = new ProvisioningConfiguration();

            public Builder withoutIPv4() {
                this.mConfig.mEnableIPv4 = false;
                return this;
            }

            public Builder withoutIPv6() {
                this.mConfig.mEnableIPv6 = false;
                return this;
            }

            public Builder withoutMultinetworkPolicyTracker() {
                this.mConfig.mUsingMultinetworkPolicyTracker = false;
                return this;
            }

            public Builder withoutIpReachabilityMonitor() {
                this.mConfig.mUsingIpReachabilityMonitor = false;
                return this;
            }

            public Builder withPreDhcpAction() {
                this.mConfig.mRequestedPreDhcpActionMs = ProvisioningConfiguration.DEFAULT_TIMEOUT_MS;
                return this;
            }

            public Builder withPreDhcpAction(int dhcpActionTimeoutMs) {
                this.mConfig.mRequestedPreDhcpActionMs = dhcpActionTimeoutMs;
                return this;
            }

            public Builder withInitialConfiguration(InitialConfiguration initialConfig) {
                this.mConfig.mInitialConfig = initialConfig;
                return this;
            }

            public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
                this.mConfig.mStaticIpConfig = staticConfig;
                return this;
            }

            public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
                this.mConfig.mApfCapabilities = apfCapabilities;
                return this;
            }

            public Builder withProvisioningTimeoutMs(int timeoutMs) {
                this.mConfig.mProvisioningTimeoutMs = timeoutMs;
                return this;
            }

            public Builder withRandomMacAddress() {
                this.mConfig.mIPv6AddrGenMode = 0;
                return this;
            }

            public Builder withStableMacAddress() {
                this.mConfig.mIPv6AddrGenMode = 2;
                return this;
            }

            public Builder withNetwork(Network network) {
                this.mConfig.mNetwork = network;
                return this;
            }

            public Builder withDisplayName(String displayName) {
                this.mConfig.mDisplayName = displayName;
                return this;
            }

            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(this.mConfig);
            }
        }

        public ProvisioningConfiguration() {
            this.mEnableIPv4 = true;
            this.mEnableIPv6 = true;
            this.mUsingMultinetworkPolicyTracker = true;
            this.mUsingIpReachabilityMonitor = true;
            this.mProvisioningTimeoutMs = DEFAULT_TIMEOUT_MS;
            this.mIPv6AddrGenMode = 2;
            this.mNetwork = null;
            this.mDisplayName = null;
        }

        public ProvisioningConfiguration(ProvisioningConfiguration other) {
            this.mEnableIPv4 = true;
            this.mEnableIPv6 = true;
            this.mUsingMultinetworkPolicyTracker = true;
            this.mUsingIpReachabilityMonitor = true;
            this.mProvisioningTimeoutMs = DEFAULT_TIMEOUT_MS;
            this.mIPv6AddrGenMode = 2;
            this.mNetwork = null;
            this.mDisplayName = null;
            this.mEnableIPv4 = other.mEnableIPv4;
            this.mEnableIPv6 = other.mEnableIPv6;
            this.mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
            this.mRequestedPreDhcpActionMs = other.mRequestedPreDhcpActionMs;
            this.mInitialConfig = InitialConfiguration.copy(other.mInitialConfig);
            this.mStaticIpConfig = other.mStaticIpConfig;
            this.mApfCapabilities = other.mApfCapabilities;
            this.mProvisioningTimeoutMs = other.mProvisioningTimeoutMs;
            this.mIPv6AddrGenMode = other.mIPv6AddrGenMode;
            this.mNetwork = other.mNetwork;
            this.mDisplayName = other.mDisplayName;
        }

        public String toString() {
            StringJoiner stringJoiner = new StringJoiner(", ", getClass().getSimpleName() + "{", "}");
            StringJoiner add = stringJoiner.add("mEnableIPv4: " + this.mEnableIPv4);
            StringJoiner add2 = add.add("mEnableIPv6: " + this.mEnableIPv6);
            StringJoiner add3 = add2.add("mUsingMultinetworkPolicyTracker: " + this.mUsingMultinetworkPolicyTracker);
            StringJoiner add4 = add3.add("mUsingIpReachabilityMonitor: " + this.mUsingIpReachabilityMonitor);
            StringJoiner add5 = add4.add("mRequestedPreDhcpActionMs: " + this.mRequestedPreDhcpActionMs);
            StringJoiner add6 = add5.add("mInitialConfig: " + this.mInitialConfig);
            StringJoiner add7 = add6.add("mStaticIpConfig: " + this.mStaticIpConfig);
            StringJoiner add8 = add7.add("mApfCapabilities: " + this.mApfCapabilities);
            StringJoiner add9 = add8.add("mProvisioningTimeoutMs: " + this.mProvisioningTimeoutMs);
            StringJoiner add10 = add9.add("mIPv6AddrGenMode: " + this.mIPv6AddrGenMode);
            StringJoiner add11 = add10.add("mNetwork: " + this.mNetwork);
            return add11.add("mDisplayName: " + this.mDisplayName).toString();
        }

        public boolean isValid() {
            return this.mInitialConfig == null || this.mInitialConfig.isValid();
        }
    }

    /* loaded from: classes.dex */
    public static class InitialConfiguration {
        public Inet4Address gateway;
        public final Set<LinkAddress> ipAddresses = new HashSet();
        public final Set<IpPrefix> directlyConnectedRoutes = new HashSet();
        public final Set<InetAddress> dnsServers = new HashSet();

        public static InitialConfiguration copy(InitialConfiguration config) {
            if (config == null) {
                return null;
            }
            InitialConfiguration configCopy = new InitialConfiguration();
            configCopy.ipAddresses.addAll(config.ipAddresses);
            configCopy.directlyConnectedRoutes.addAll(config.directlyConnectedRoutes);
            configCopy.dnsServers.addAll(config.dnsServers);
            return configCopy;
        }

        public String toString() {
            return String.format("InitialConfiguration(IPs: {%s}, prefixes: {%s}, DNS: {%s}, v4 gateway: %s)", IpClient.join(", ", this.ipAddresses), IpClient.join(", ", this.directlyConnectedRoutes), IpClient.join(", ", this.dnsServers), this.gateway);
        }

        public boolean isValid() {
            if (this.ipAddresses.isEmpty()) {
                return false;
            }
            for (final LinkAddress addr : this.ipAddresses) {
                if (!IpClient.any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$UHB1S125oVRRJ33BRbWLXqGk32M
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        boolean contains;
                        contains = ((IpPrefix) obj).contains(addr.getAddress());
                        return contains;
                    }
                })) {
                    return false;
                }
            }
            for (final InetAddress addr2 : this.dnsServers) {
                if (!IpClient.any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$Vb5KM9_T-c7Jv1Lqbet5CRNEyjU
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        boolean contains;
                        contains = ((IpPrefix) obj).contains(addr2);
                        return contains;
                    }
                })) {
                    return false;
                }
            }
            if (IpClient.any(this.ipAddresses, IpClient.not(new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$YwpJbnxCjWZ5CZ7ycLj8DIoOSd8
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isPrefixLengthCompliant;
                    isPrefixLengthCompliant = IpClient.InitialConfiguration.isPrefixLengthCompliant((LinkAddress) obj);
                    return isPrefixLengthCompliant;
                }
            }))) {
                return false;
            }
            if ((IpClient.any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$gxc5co5uJUOrlWJ8HYqcngxR5gI
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isIPv6DefaultRoute;
                    isIPv6DefaultRoute = IpClient.InitialConfiguration.isIPv6DefaultRoute((IpPrefix) obj);
                    return isIPv6DefaultRoute;
                }
            }) && IpClient.all(this.ipAddresses, IpClient.not(new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$WB134Aq_hrEPp-6UsNJgWvtMzBM
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isIPv6GUA;
                    isIPv6GUA = IpClient.InitialConfiguration.isIPv6GUA((LinkAddress) obj);
                    return isIPv6GUA;
                }
            }))) || IpClient.any(this.directlyConnectedRoutes, IpClient.not(new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$-qxDAAo5wjq2G7x-F8gQeNSxIxY
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isPrefixLengthCompliant;
                    isPrefixLengthCompliant = IpClient.InitialConfiguration.isPrefixLengthCompliant((IpPrefix) obj);
                    return isPrefixLengthCompliant;
                }
            }))) {
                return false;
            }
            Stream<LinkAddress> stream = this.ipAddresses.stream();
            Objects.requireNonNull(Inet4Address.class);
            return stream.filter(new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$guZQ276VyezHQuIEE0CG9osvUes
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isInstance;
                    isInstance = r1.isInstance((LinkAddress) obj);
                    return isInstance;
                }
            }).count() <= 1;
        }

        public boolean isProvisionedBy(List<LinkAddress> addresses, List<RouteInfo> routes) {
            if (this.ipAddresses.isEmpty()) {
                return false;
            }
            for (final LinkAddress addr : this.ipAddresses) {
                if (!IpClient.any(addresses, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$Yj_oETEniyWPwMk9O1x-JZIJoNo
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        boolean isSameAddressAs;
                        isSameAddressAs = addr.isSameAddressAs((LinkAddress) obj);
                        return isSameAddressAs;
                    }
                })) {
                    return false;
                }
            }
            if (routes != null) {
                for (final IpPrefix prefix : this.directlyConnectedRoutes) {
                    if (!IpClient.any(routes, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$InitialConfiguration$Id7yPLmMAQz0Sm1dnrJVkXkUQNQ
                        @Override // java.util.function.Predicate
                        public final boolean test(Object obj) {
                            boolean isDirectlyConnectedRoute;
                            isDirectlyConnectedRoute = IpClient.InitialConfiguration.isDirectlyConnectedRoute((RouteInfo) obj, prefix);
                            return isDirectlyConnectedRoute;
                        }
                    })) {
                        return false;
                    }
                }
                return true;
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isDirectlyConnectedRoute(RouteInfo route, IpPrefix prefix) {
            return !route.hasGateway() && prefix.equals(route.getDestination());
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isPrefixLengthCompliant(LinkAddress addr) {
            return addr.isIPv4() || isCompliantIPv6PrefixLength(addr.getPrefixLength());
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isPrefixLengthCompliant(IpPrefix prefix) {
            return prefix.isIPv4() || isCompliantIPv6PrefixLength(prefix.getPrefixLength());
        }

        private static boolean isCompliantIPv6PrefixLength(int prefixLength) {
            return 48 <= prefixLength && prefixLength <= 64;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isIPv6DefaultRoute(IpPrefix prefix) {
            return prefix.getAddress().equals(Inet6Address.ANY);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isIPv6GUA(LinkAddress addr) {
            return addr.isIPv6() && addr.isGlobalPreferred();
        }
    }

    /* loaded from: classes.dex */
    public static class Dependencies {
        public INetworkManagementService getNMS() {
            return INetworkManagementService.Stub.asInterface(ServiceManager.getService("network_management"));
        }

        public INetd getNetd() {
            return NetdService.getInstance();
        }

        public InterfaceParams getInterfaceParams(String ifname) {
            return InterfaceParams.getByName(ifname);
        }
    }

    public IpClient(Context context, String ifName, Callback callback) {
        this(context, ifName, callback, new Dependencies());
    }

    public IpClient(Context context, String ifName, Callback callback, final INetworkManagementService nwService) {
        this(context, ifName, callback, new Dependencies() { // from class: android.net.ip.IpClient.1
            @Override // android.net.ip.IpClient.Dependencies
            public INetworkManagementService getNMS() {
                return nwService;
            }
        });
    }

    @VisibleForTesting
    IpClient(Context context, String ifName, Callback callback, Dependencies deps) {
        super(IpClient.class.getSimpleName() + "." + ifName);
        this.mStoppedState = new StoppedState();
        this.mStoppingState = new StoppingState();
        this.mStartedState = new StartedState();
        this.mRunningState = new RunningState();
        this.mMetricsLog = new IpConnectivityLog();
        this.mApfDataSnapshotComplete = new ConditionVariable();
        Preconditions.checkNotNull(ifName);
        Preconditions.checkNotNull(callback);
        this.mTag = getName();
        this.mContext = context;
        this.mInterfaceName = ifName;
        this.mClatInterfaceName = CLAT_PREFIX + ifName;
        this.mCallback = new LoggingCallbackWrapper(callback);
        this.mDependencies = deps;
        this.mShutdownLatch = new CountDownLatch(1);
        this.mNwService = deps.getNMS();
        sSmLogs.putIfAbsent(this.mInterfaceName, new SharedLog(500, this.mTag));
        this.mLog = sSmLogs.get(this.mInterfaceName);
        sPktLogs.putIfAbsent(this.mInterfaceName, new LocalLog(100));
        this.mConnectivityPacketLog = sPktLogs.get(this.mInterfaceName);
        this.mMsgStateLogger = new MessageHandlingLogger();
        this.mInterfaceCtrl = new InterfaceController(this.mInterfaceName, this.mNwService, deps.getNetd(), this.mLog);
        this.mNetlinkTracker = new AnonymousClass3(this.mInterfaceName, new NetlinkTracker.Callback() { // from class: android.net.ip.IpClient.2
            public void update() {
                IpClient.this.sendMessage(6);
            }
        });
        this.mLinkProperties = new LinkProperties();
        this.mLinkProperties.setInterfaceName(this.mInterfaceName);
        Context context2 = this.mContext;
        Handler handler = getHandler();
        this.mProvisioningTimeoutAlarm = new WakeupMessage(context2, handler, this.mTag + ".EVENT_PROVISIONING_TIMEOUT", 10);
        Context context3 = this.mContext;
        Handler handler2 = getHandler();
        this.mDhcpActionTimeoutAlarm = new WakeupMessage(context3, handler2, this.mTag + ".EVENT_DHCPACTION_TIMEOUT", 11);
        configureAndStartStateMachine();
        startStateMachineUpdaters();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: android.net.ip.IpClient$3  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass3 extends NetlinkTracker {
        AnonymousClass3(String x0, NetlinkTracker.Callback x1) {
            super(x0, x1);
        }

        public void interfaceAdded(String iface) {
            super.interfaceAdded(iface);
            if (!IpClient.this.mClatInterfaceName.equals(iface)) {
                if (!IpClient.this.mInterfaceName.equals(iface)) {
                    return;
                }
            } else {
                IpClient.this.mCallback.setNeighborDiscoveryOffload(false);
            }
            String msg = "interfaceAdded(" + iface + ")";
            logMsg(msg);
        }

        public void interfaceRemoved(String iface) {
            super.interfaceRemoved(iface);
            if (!IpClient.this.mInterfaceName.equals(iface) || IpClient.this.mConfiguration.mStaticIpConfig != null) {
                if (!IpClient.this.mClatInterfaceName.equals(iface)) {
                    if (!IpClient.this.mInterfaceName.equals(iface)) {
                        return;
                    }
                } else {
                    IpClient.this.mCallback.setNeighborDiscoveryOffload(true);
                }
            } else {
                IpClient.this.doImmediateProvisioningFailure(8);
                IpClient.this.sendMessage(2);
            }
            String msg = "interfaceRemoved(" + iface + ")";
            logMsg(msg);
        }

        private void logMsg(final String msg) {
            Log.d(IpClient.this.mTag, msg);
            IpClient.this.getHandler().post(new Runnable() { // from class: android.net.ip.-$$Lambda$IpClient$3$97DqfCdvF_DK1fYMrSYfnetT_aU
                @Override // java.lang.Runnable
                public final void run() {
                    IpClient.AnonymousClass3.lambda$logMsg$0(IpClient.AnonymousClass3.this, msg);
                }
            });
        }

        public static /* synthetic */ void lambda$logMsg$0(AnonymousClass3 anonymousClass3, String msg) {
            SharedLog sharedLog = IpClient.this.mLog;
            sharedLog.log("OBSERVED " + msg);
        }
    }

    private void configureAndStartStateMachine() {
        addState(this.mStoppedState);
        addState(this.mStartedState);
        addState(this.mRunningState, this.mStartedState);
        addState(this.mStoppingState);
        setInitialState(this.mStoppedState);
        super.start();
    }

    private void startStateMachineUpdaters() {
        try {
            this.mNwService.registerObserver(this.mNetlinkTracker);
        } catch (RemoteException e) {
            logError("Couldn't register NetlinkTracker: %s", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopStateMachineUpdaters() {
        try {
            this.mNwService.unregisterObserver(this.mNetlinkTracker);
        } catch (RemoteException e) {
            logError("Couldn't unregister NetlinkTracker: %s", e);
        }
    }

    protected void onQuitting() {
        this.mCallback.onQuit();
        this.mShutdownLatch.countDown();
    }

    public void shutdown() {
        stop();
        sendMessage(1);
    }

    public void awaitShutdown() {
        try {
            this.mShutdownLatch.await();
        } catch (InterruptedException e) {
            SharedLog sharedLog = this.mLog;
            sharedLog.e("Interrupted while awaiting shutdown: " + e);
        }
    }

    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    public void startProvisioning(ProvisioningConfiguration req) {
        if (!req.isValid()) {
            doImmediateProvisioningFailure(7);
            return;
        }
        this.mInterfaceParams = this.mDependencies.getInterfaceParams(this.mInterfaceName);
        if (this.mInterfaceParams == null) {
            logError("Failed to find InterfaceParams for " + this.mInterfaceName, new Object[0]);
            doImmediateProvisioningFailure(8);
            return;
        }
        this.mCallback.setNeighborDiscoveryOffload(true);
        sendMessage(3, new ProvisioningConfiguration(req));
    }

    public void startProvisioning(StaticIpConfiguration staticIpConfig) {
        startProvisioning(buildProvisioningConfiguration().withStaticConfiguration(staticIpConfig).build());
    }

    public void startProvisioning() {
        startProvisioning(new ProvisioningConfiguration());
    }

    public void stop() {
        sendMessage(2);
    }

    public void confirmConfiguration() {
        sendMessage(4);
    }

    public void completedPreDhcpAction() {
        sendMessage(5);
    }

    public void readPacketFilterComplete(byte[] data) {
        sendMessage(12, data);
    }

    public void setTcpBufferSizes(String tcpBufferSizes) {
        sendMessage(7, tcpBufferSizes);
    }

    public void setHttpProxy(ProxyInfo proxyInfo) {
        sendMessage(8, proxyInfo);
    }

    public void setMulticastFilter(boolean enabled) {
        sendMessage(9, Boolean.valueOf(enabled));
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (args != null && args.length > 0 && DUMP_ARG_CONFIRM.equals(args[0])) {
            confirmConfiguration();
            return;
        }
        ApfFilter apfFilter = this.mApfFilter;
        ProvisioningConfiguration provisioningConfig = this.mConfiguration;
        ApfCapabilities apfCapabilities = provisioningConfig != null ? provisioningConfig.mApfCapabilities : null;
        PrintWriter indentingPrintWriter = new IndentingPrintWriter(writer, "  ");
        indentingPrintWriter.println(this.mTag + " APF dump:");
        indentingPrintWriter.increaseIndent();
        if (apfFilter != null) {
            if (apfCapabilities.hasDataAccess()) {
                this.mApfDataSnapshotComplete.close();
                this.mCallback.startReadPacketFilter();
                if (!this.mApfDataSnapshotComplete.block(1000L)) {
                    indentingPrintWriter.print("TIMEOUT: DUMPING STALE APF SNAPSHOT");
                }
            }
            apfFilter.dump(indentingPrintWriter);
        } else {
            indentingPrintWriter.print("No active ApfFilter; ");
            if (provisioningConfig == null) {
                indentingPrintWriter.println("IpClient not yet started.");
            } else if (apfCapabilities == null || apfCapabilities.apfVersionSupported == 0) {
                indentingPrintWriter.println("Hardware does not support APF.");
            } else {
                indentingPrintWriter.println("ApfFilter not yet started, APF capabilities: " + apfCapabilities);
            }
        }
        indentingPrintWriter.decreaseIndent();
        indentingPrintWriter.println();
        indentingPrintWriter.println(this.mTag + " current ProvisioningConfiguration:");
        indentingPrintWriter.increaseIndent();
        indentingPrintWriter.println(Objects.toString(provisioningConfig, "N/A"));
        indentingPrintWriter.decreaseIndent();
        IpReachabilityMonitor iprm = this.mIpReachabilityMonitor;
        if (iprm != null) {
            indentingPrintWriter.println();
            indentingPrintWriter.println(this.mTag + " current IpReachabilityMonitor state:");
            indentingPrintWriter.increaseIndent();
            iprm.dump(indentingPrintWriter);
            indentingPrintWriter.decreaseIndent();
        }
        indentingPrintWriter.println();
        indentingPrintWriter.println(this.mTag + " StateMachine dump:");
        indentingPrintWriter.increaseIndent();
        this.mLog.dump(fd, indentingPrintWriter, args);
        indentingPrintWriter.decreaseIndent();
        indentingPrintWriter.println();
        indentingPrintWriter.println(this.mTag + " connectivity packet log:");
        indentingPrintWriter.println();
        indentingPrintWriter.println("Debug with python and scapy via:");
        indentingPrintWriter.println("shell$ python");
        indentingPrintWriter.println(">>> from scapy import all as scapy");
        indentingPrintWriter.println(">>> scapy.Ether(\"<paste_hex_string>\".decode(\"hex\")).show2()");
        indentingPrintWriter.println();
        indentingPrintWriter.increaseIndent();
        this.mConnectivityPacketLog.readOnlyLocalLog().dump(fd, indentingPrintWriter, args);
        indentingPrintWriter.decreaseIndent();
    }

    protected String getWhatToString(int what) {
        SparseArray<String> sparseArray = sWhatToString;
        return sparseArray.get(what, "UNKNOWN: " + Integer.toString(what));
    }

    protected String getLogRecString(Message msg) {
        Object[] objArr = new Object[6];
        objArr[0] = this.mInterfaceName;
        objArr[1] = Integer.valueOf(this.mInterfaceParams == null ? -1 : this.mInterfaceParams.index);
        objArr[2] = Integer.valueOf(msg.arg1);
        objArr[3] = Integer.valueOf(msg.arg2);
        objArr[4] = Objects.toString(msg.obj);
        objArr[5] = this.mMsgStateLogger;
        String logLine = String.format("%s/%d %d %d %s [%s]", objArr);
        String richerLogLine = getWhatToString(msg.what) + " " + logLine;
        this.mLog.log(richerLogLine);
        this.mMsgStateLogger.reset();
        return logLine;
    }

    protected boolean recordLogRec(Message msg) {
        boolean shouldLog = msg.what != 6;
        if (!shouldLog) {
            this.mMsgStateLogger.reset();
        }
        return shouldLog;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logError(String fmt, Object... args) {
        String msg = "ERROR " + String.format(fmt, args);
        Log.e(this.mTag, msg);
        this.mLog.log(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetLinkProperties() {
        this.mNetlinkTracker.clearLinkProperties();
        this.mConfiguration = null;
        this.mDhcpResults = null;
        this.mTcpBufferSizes = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        this.mHttpProxy = null;
        this.mLinkProperties = new LinkProperties();
        this.mLinkProperties.setInterfaceName(this.mInterfaceName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void recordMetric(int type) {
        long duration = this.mStartTimeMillis > 0 ? SystemClock.elapsedRealtime() - this.mStartTimeMillis : 0L;
        this.mMetricsLog.log(this.mInterfaceName, new IpManagerEvent(type, duration));
    }

    @VisibleForTesting
    static boolean isProvisioned(LinkProperties lp, InitialConfiguration config) {
        if (lp.hasIPv4Address() || lp.isProvisioned()) {
            return true;
        }
        if (config == null) {
            return false;
        }
        return config.isProvisionedBy(lp.getLinkAddresses(), lp.getRoutes());
    }

    private LinkProperties.ProvisioningChange compareProvisioning(LinkProperties oldLp, LinkProperties newLp) {
        LinkProperties.ProvisioningChange delta;
        InitialConfiguration config = this.mConfiguration != null ? this.mConfiguration.mInitialConfig : null;
        boolean wasProvisioned = isProvisioned(oldLp, config);
        boolean isProvisioned = isProvisioned(newLp, config);
        if (!wasProvisioned && isProvisioned) {
            delta = LinkProperties.ProvisioningChange.GAINED_PROVISIONING;
        } else if (wasProvisioned && isProvisioned) {
            delta = LinkProperties.ProvisioningChange.STILL_PROVISIONED;
        } else if (!wasProvisioned && !isProvisioned) {
            delta = LinkProperties.ProvisioningChange.STILL_NOT_PROVISIONED;
        } else {
            delta = LinkProperties.ProvisioningChange.LOST_PROVISIONING;
        }
        boolean ignoreIPv6ProvisioningLoss = false;
        boolean lostIPv6 = oldLp.isIPv6Provisioned() && !newLp.isIPv6Provisioned();
        boolean lostIPv4Address = oldLp.hasIPv4Address() && !newLp.hasIPv4Address();
        boolean lostIPv6Router = oldLp.hasIPv6DefaultRoute() && !newLp.hasIPv6DefaultRoute();
        if (this.mMultinetworkPolicyTracker != null && !this.mMultinetworkPolicyTracker.getAvoidBadWifi()) {
            ignoreIPv6ProvisioningLoss = true;
        }
        if (lostIPv4Address || (lostIPv6 && !ignoreIPv6ProvisioningLoss)) {
            delta = LinkProperties.ProvisioningChange.LOST_PROVISIONING;
        }
        if (oldLp.hasGlobalIPv6Address() && lostIPv6Router && !ignoreIPv6ProvisioningLoss) {
            LinkProperties.ProvisioningChange delta2 = LinkProperties.ProvisioningChange.LOST_PROVISIONING;
            return delta2;
        }
        return delta;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: android.net.ip.IpClient$5  reason: invalid class name */
    /* loaded from: classes.dex */
    public static /* synthetic */ class AnonymousClass5 {
        static final /* synthetic */ int[] $SwitchMap$android$net$LinkProperties$ProvisioningChange = new int[LinkProperties.ProvisioningChange.values().length];

        static {
            try {
                $SwitchMap$android$net$LinkProperties$ProvisioningChange[LinkProperties.ProvisioningChange.GAINED_PROVISIONING.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$android$net$LinkProperties$ProvisioningChange[LinkProperties.ProvisioningChange.LOST_PROVISIONING.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchCallback(LinkProperties.ProvisioningChange delta, LinkProperties newLp) {
        switch (AnonymousClass5.$SwitchMap$android$net$LinkProperties$ProvisioningChange[delta.ordinal()]) {
            case 1:
                recordMetric(1);
                this.mCallback.onProvisioningSuccess(newLp);
                return;
            case 2:
                recordMetric(2);
                this.mCallback.onProvisioningFailure(newLp);
                return;
            default:
                this.mCallback.onLinkPropertiesChange(newLp);
                return;
        }
    }

    private LinkProperties.ProvisioningChange setLinkProperties(LinkProperties newLp) {
        if (this.mApfFilter != null) {
            this.mApfFilter.setLinkProperties(newLp);
        }
        if (this.mIpReachabilityMonitor != null) {
            this.mIpReachabilityMonitor.updateLinkProperties(newLp);
        }
        LinkProperties.ProvisioningChange delta = compareProvisioning(this.mLinkProperties, newLp);
        this.mLinkProperties = new LinkProperties(newLp);
        if (delta == LinkProperties.ProvisioningChange.GAINED_PROVISIONING) {
            this.mProvisioningTimeoutAlarm.cancel();
        }
        return delta;
    }

    private LinkProperties assembleLinkProperties() {
        LinkProperties newLp = new LinkProperties();
        newLp.setInterfaceName(this.mInterfaceName);
        LinkProperties netlinkLinkProperties = this.mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        addAllReachableDnsServers(newLp, netlinkLinkProperties.getDnsServers());
        if (this.mDhcpResults != null) {
            for (RouteInfo route2 : this.mDhcpResults.getRoutes(this.mInterfaceName)) {
                newLp.addRoute(route2);
            }
            addAllReachableDnsServers(newLp, this.mDhcpResults.dnsServers);
            newLp.setDomains(this.mDhcpResults.domains);
            if (this.mDhcpResults.mtu != 0) {
                newLp.setMtu(this.mDhcpResults.mtu);
            }
        }
        if (!TextUtils.isEmpty(this.mTcpBufferSizes)) {
            newLp.setTcpBufferSizes(this.mTcpBufferSizes);
        }
        if (this.mHttpProxy != null) {
            newLp.setHttpProxy(this.mHttpProxy);
        }
        if (this.mConfiguration != null && this.mConfiguration.mInitialConfig != null) {
            InitialConfiguration config = this.mConfiguration.mInitialConfig;
            if (config.isProvisionedBy(newLp.getLinkAddresses(), null)) {
                for (IpPrefix prefix : config.directlyConnectedRoutes) {
                    newLp.addRoute(new RouteInfo(prefix, null, this.mInterfaceName));
                }
            }
            addAllReachableDnsServers(newLp, config.dnsServers);
        }
        LinkProperties linkProperties = this.mLinkProperties;
        return newLp;
    }

    private static void addAllReachableDnsServers(LinkProperties lp, Iterable<InetAddress> dnses) {
        for (InetAddress dns : dnses) {
            if (!dns.isAnyLocalAddress() && lp.isReachable(dns)) {
                lp.addDnsServer(dns);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean handleLinkPropertiesUpdate(boolean sendCallbacks) {
        LinkProperties newLp = assembleLinkProperties();
        if (Objects.equals(newLp, this.mLinkProperties)) {
            return true;
        }
        LinkProperties.ProvisioningChange delta = setLinkProperties(newLp);
        if (sendCallbacks) {
            dispatchCallback(delta, newLp);
        }
        return delta != LinkProperties.ProvisioningChange.LOST_PROVISIONING;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleIPv4Success(DhcpResults dhcpResults) {
        this.mDhcpResults = new DhcpResults(dhcpResults);
        LinkProperties newLp = assembleLinkProperties();
        LinkProperties.ProvisioningChange delta = setLinkProperties(newLp);
        this.mCallback.onNewDhcpResults(dhcpResults);
        dispatchCallback(delta, newLp);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleIPv4Failure() {
        this.mInterfaceCtrl.clearIPv4Address();
        this.mDhcpResults = null;
        this.mCallback.onNewDhcpResults(null);
        handleProvisioningFailure();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleProvisioningFailure() {
        LinkProperties newLp = assembleLinkProperties();
        LinkProperties.ProvisioningChange delta = setLinkProperties(newLp);
        if (delta == LinkProperties.ProvisioningChange.STILL_NOT_PROVISIONED) {
            delta = LinkProperties.ProvisioningChange.LOST_PROVISIONING;
        }
        dispatchCallback(delta, newLp);
        if (delta == LinkProperties.ProvisioningChange.LOST_PROVISIONING) {
            transitionTo(this.mStoppingState);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doImmediateProvisioningFailure(int failureType) {
        logError("onProvisioningFailure(): %s", Integer.valueOf(failureType));
        recordMetric(failureType);
        this.mCallback.onProvisioningFailure(new LinkProperties(this.mLinkProperties));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startIPv4() {
        if (this.mConfiguration.mStaticIpConfig != null) {
            if (this.mInterfaceCtrl.setIPv4Address(this.mConfiguration.mStaticIpConfig.ipAddress)) {
                handleIPv4Success(new DhcpResults(this.mConfiguration.mStaticIpConfig));
                return true;
            }
            return false;
        }
        this.mDhcpClient = DhcpClient.makeDhcpClient(this.mContext, this, this.mInterfaceParams);
        this.mDhcpClient.registerForPreDhcpNotification();
        this.mDhcpClient.sendMessage(DhcpClient.CMD_START_DHCP);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startIPv6() {
        return this.mInterfaceCtrl.setIPv6PrivacyExtensions(true) && this.mInterfaceCtrl.setIPv6AddrGenModeIfSupported(this.mConfiguration.mIPv6AddrGenMode) && this.mInterfaceCtrl.enableIPv6();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean applyInitialConfig(InitialConfiguration config) {
        for (LinkAddress addr : findAll(config.ipAddresses, new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$GdLECAc1sQEo2Jjde3Y4ykVjDBg
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isIPv6;
                isIPv6 = ((LinkAddress) obj).isIPv6();
                return isIPv6;
            }
        })) {
            if (!this.mInterfaceCtrl.addAddress(addr)) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startIpReachabilityMonitor() {
        try {
            this.mIpReachabilityMonitor = new IpReachabilityMonitor(this.mContext, this.mInterfaceParams, getHandler(), this.mLog, new IpReachabilityMonitor.Callback() { // from class: android.net.ip.IpClient.4
                @Override // android.net.ip.IpReachabilityMonitor.Callback
                public void notifyLost(InetAddress ip, String logMsg) {
                    IpClient.this.mCallback.onReachabilityLost(logMsg);
                }
            }, this.mMultinetworkPolicyTracker);
        } catch (IllegalArgumentException iae) {
            logError("IpReachabilityMonitor failure: %s", iae);
            this.mIpReachabilityMonitor = null;
        }
        return this.mIpReachabilityMonitor != null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopAllIP() {
        this.mInterfaceCtrl.disableIPv6();
        this.mInterfaceCtrl.clearAllAddresses();
    }

    /* loaded from: classes.dex */
    class StoppedState extends State {
        StoppedState() {
        }

        public void enter() {
            IpClient.this.stopAllIP();
            IpClient.this.resetLinkProperties();
            if (IpClient.this.mStartTimeMillis > 0) {
                IpClient.this.recordMetric(3);
                IpClient.this.mStartTimeMillis = 0L;
            }
        }

        public boolean processMessage(Message msg) {
            int i = msg.what;
            if (i != 196613) {
                switch (i) {
                    case 1:
                        IpClient.this.stopStateMachineUpdaters();
                        IpClient.this.quit();
                        break;
                    case 2:
                        break;
                    case 3:
                        IpClient.this.mConfiguration = (ProvisioningConfiguration) msg.obj;
                        IpClient.this.transitionTo(IpClient.this.mStartedState);
                        break;
                    default:
                        switch (i) {
                            case 6:
                                IpClient.this.handleLinkPropertiesUpdate(false);
                                break;
                            case 7:
                                IpClient.this.mTcpBufferSizes = (String) msg.obj;
                                IpClient.this.handleLinkPropertiesUpdate(false);
                                break;
                            case 8:
                                IpClient.this.mHttpProxy = (ProxyInfo) msg.obj;
                                IpClient.this.handleLinkPropertiesUpdate(false);
                                break;
                            case 9:
                                IpClient.this.mMulticastFiltering = ((Boolean) msg.obj).booleanValue();
                                break;
                            default:
                                return false;
                        }
                }
            } else {
                IpClient.this.logError("Unexpected CMD_ON_QUIT (already stopped).", new Object[0]);
            }
            IpClient.this.mMsgStateLogger.handled(this, IpClient.this.getCurrentState());
            return true;
        }
    }

    /* loaded from: classes.dex */
    class StoppingState extends State {
        StoppingState() {
        }

        public void enter() {
            if (IpClient.this.mDhcpClient == null) {
                IpClient.this.transitionTo(IpClient.this.mStoppedState);
            }
        }

        public boolean processMessage(Message msg) {
            int i = msg.what;
            if (i != 2) {
                if (i == 196613) {
                    IpClient.this.mDhcpClient = null;
                    IpClient.this.transitionTo(IpClient.this.mStoppedState);
                } else if (i == 196615) {
                    IpClient.this.mInterfaceCtrl.clearIPv4Address();
                } else {
                    IpClient.this.deferMessage(msg);
                }
            }
            IpClient.this.mMsgStateLogger.handled(this, IpClient.this.getCurrentState());
            return true;
        }
    }

    /* loaded from: classes.dex */
    class StartedState extends State {
        StartedState() {
        }

        public void enter() {
            IpClient.this.mStartTimeMillis = SystemClock.elapsedRealtime();
            if (IpClient.this.mConfiguration.mProvisioningTimeoutMs > 0) {
                long alarmTime = SystemClock.elapsedRealtime() + IpClient.this.mConfiguration.mProvisioningTimeoutMs;
                IpClient.this.mProvisioningTimeoutAlarm.schedule(alarmTime);
            }
            if (readyToProceed()) {
                IpClient.this.transitionTo(IpClient.this.mRunningState);
            } else {
                IpClient.this.stopAllIP();
            }
        }

        public void exit() {
            IpClient.this.mProvisioningTimeoutAlarm.cancel();
        }

        public boolean processMessage(Message msg) {
            int i = msg.what;
            if (i == 2) {
                IpClient.this.transitionTo(IpClient.this.mStoppingState);
            } else if (i == 6) {
                IpClient.this.handleLinkPropertiesUpdate(false);
                if (readyToProceed()) {
                    IpClient.this.transitionTo(IpClient.this.mRunningState);
                }
            } else if (i == 10) {
                IpClient.this.handleProvisioningFailure();
            } else {
                IpClient.this.deferMessage(msg);
            }
            IpClient.this.mMsgStateLogger.handled(this, IpClient.this.getCurrentState());
            return true;
        }

        boolean readyToProceed() {
            return (IpClient.this.mLinkProperties.hasIPv4Address() || IpClient.this.mLinkProperties.hasGlobalIPv6Address()) ? false : true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class RunningState extends State {
        private boolean mDhcpActionInFlight;
        private ConnectivityPacketTracker mPacketTracker;

        RunningState() {
        }

        public void enter() {
            ApfFilter.ApfConfiguration apfConfig = new ApfFilter.ApfConfiguration();
            apfConfig.apfCapabilities = IpClient.this.mConfiguration.mApfCapabilities;
            apfConfig.multicastFilter = IpClient.this.mMulticastFiltering;
            apfConfig.ieee802_3Filter = IpClient.this.mContext.getResources().getBoolean(17956890);
            apfConfig.ethTypeBlackList = IpClient.this.mContext.getResources().getIntArray(17235983);
            IpClient.this.mApfFilter = ApfFilter.maybeCreate(IpClient.this.mContext, apfConfig, IpClient.this.mInterfaceParams, IpClient.this.mCallback);
            if (IpClient.this.mApfFilter == null) {
                IpClient.this.mCallback.setFallbackMulticastFilter(IpClient.this.mMulticastFiltering);
            }
            this.mPacketTracker = createPacketTracker();
            if (this.mPacketTracker != null) {
                this.mPacketTracker.start(IpClient.this.mConfiguration.mDisplayName);
            }
            if (!IpClient.this.mConfiguration.mEnableIPv6 || IpClient.this.startIPv6()) {
                if (!IpClient.this.mConfiguration.mEnableIPv4 || IpClient.this.startIPv4()) {
                    InitialConfiguration config = IpClient.this.mConfiguration.mInitialConfig;
                    if (config == null || IpClient.this.applyInitialConfig(config)) {
                        if (IpClient.this.mConfiguration.mUsingMultinetworkPolicyTracker) {
                            IpClient.this.mMultinetworkPolicyTracker = new MultinetworkPolicyTracker(IpClient.this.mContext, IpClient.this.getHandler(), new Runnable() { // from class: android.net.ip.-$$Lambda$IpClient$RunningState$62CnAIrZ9p4JQ9DgmmpMjXifdaw
                                @Override // java.lang.Runnable
                                public final void run() {
                                    IpClient.this.mLog.log("OBSERVED AvoidBadWifi changed");
                                }
                            });
                            IpClient.this.mMultinetworkPolicyTracker.start();
                        }
                        if (IpClient.this.mConfiguration.mUsingIpReachabilityMonitor && !IpClient.this.startIpReachabilityMonitor()) {
                            IpClient.this.doImmediateProvisioningFailure(6);
                            IpClient.this.transitionTo(IpClient.this.mStoppingState);
                            return;
                        }
                        return;
                    }
                    IpClient.this.doImmediateProvisioningFailure(7);
                    IpClient.this.transitionTo(IpClient.this.mStoppingState);
                    return;
                }
                IpClient.this.doImmediateProvisioningFailure(4);
                IpClient.this.transitionTo(IpClient.this.mStoppingState);
                return;
            }
            IpClient.this.doImmediateProvisioningFailure(5);
            IpClient.this.transitionTo(IpClient.this.mStoppingState);
        }

        public void exit() {
            stopDhcpAction();
            if (IpClient.this.mIpReachabilityMonitor != null) {
                IpClient.this.mIpReachabilityMonitor.stop();
                IpClient.this.mIpReachabilityMonitor = null;
            }
            if (IpClient.this.mMultinetworkPolicyTracker != null) {
                IpClient.this.mMultinetworkPolicyTracker.shutdown();
                IpClient.this.mMultinetworkPolicyTracker = null;
            }
            if (IpClient.this.mDhcpClient != null) {
                IpClient.this.mDhcpClient.sendMessage(DhcpClient.CMD_STOP_DHCP);
                IpClient.this.mDhcpClient.doQuit();
            }
            if (this.mPacketTracker != null) {
                this.mPacketTracker.stop();
                this.mPacketTracker = null;
            }
            if (IpClient.this.mApfFilter != null) {
                IpClient.this.mApfFilter.shutdown();
                IpClient.this.mApfFilter = null;
            }
            IpClient.this.resetLinkProperties();
        }

        private ConnectivityPacketTracker createPacketTracker() {
            try {
                return new ConnectivityPacketTracker(IpClient.this.getHandler(), IpClient.this.mInterfaceParams, IpClient.this.mConnectivityPacketLog);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private void ensureDhcpAction() {
            if (!this.mDhcpActionInFlight) {
                IpClient.this.mCallback.onPreDhcpAction();
                this.mDhcpActionInFlight = true;
                long alarmTime = SystemClock.elapsedRealtime() + IpClient.this.mConfiguration.mRequestedPreDhcpActionMs;
                IpClient.this.mDhcpActionTimeoutAlarm.schedule(alarmTime);
            }
        }

        private void stopDhcpAction() {
            IpClient.this.mDhcpActionTimeoutAlarm.cancel();
            if (this.mDhcpActionInFlight) {
                IpClient.this.mCallback.onPostDhcpAction();
                this.mDhcpActionInFlight = false;
            }
        }

        public boolean processMessage(Message msg) {
            int i = msg.what;
            switch (i) {
                case 2:
                    IpClient.this.transitionTo(IpClient.this.mStoppingState);
                    break;
                case 3:
                    IpClient.this.logError("ALERT: START received in StartedState. Please fix caller.", new Object[0]);
                    break;
                case 4:
                    if (IpClient.this.mIpReachabilityMonitor != null) {
                        IpClient.this.mIpReachabilityMonitor.probeAll();
                        break;
                    }
                    break;
                case 5:
                    if (IpClient.this.mDhcpClient != null) {
                        IpClient.this.mDhcpClient.sendMessage(DhcpClient.CMD_PRE_DHCP_ACTION_COMPLETE);
                        break;
                    }
                    break;
                case 6:
                    if (!IpClient.this.handleLinkPropertiesUpdate(true)) {
                        IpClient.this.transitionTo(IpClient.this.mStoppingState);
                        break;
                    }
                    break;
                case 7:
                    IpClient.this.mTcpBufferSizes = (String) msg.obj;
                    IpClient.this.handleLinkPropertiesUpdate(true);
                    break;
                case 8:
                    IpClient.this.mHttpProxy = (ProxyInfo) msg.obj;
                    IpClient.this.handleLinkPropertiesUpdate(true);
                    break;
                case 9:
                    IpClient.this.mMulticastFiltering = ((Boolean) msg.obj).booleanValue();
                    if (IpClient.this.mApfFilter != null) {
                        IpClient.this.mApfFilter.setMulticastFilter(IpClient.this.mMulticastFiltering);
                        break;
                    } else {
                        IpClient.this.mCallback.setFallbackMulticastFilter(IpClient.this.mMulticastFiltering);
                        break;
                    }
                default:
                    switch (i) {
                        case 11:
                            stopDhcpAction();
                            break;
                        case 12:
                            if (IpClient.this.mApfFilter != null) {
                                IpClient.this.mApfFilter.setDataSnapshot((byte[]) msg.obj);
                            }
                            IpClient.this.mApfDataSnapshotComplete.open();
                            break;
                        default:
                            switch (i) {
                                case DhcpClient.CMD_PRE_DHCP_ACTION /* 196611 */:
                                    if (IpClient.this.mConfiguration.mRequestedPreDhcpActionMs > 0) {
                                        ensureDhcpAction();
                                        break;
                                    } else {
                                        IpClient.this.sendMessage(5);
                                        break;
                                    }
                                case DhcpClient.CMD_POST_DHCP_ACTION /* 196612 */:
                                    stopDhcpAction();
                                    switch (msg.arg1) {
                                        case 1:
                                            IpClient.this.handleIPv4Success((DhcpResults) msg.obj);
                                            break;
                                        case 2:
                                            IpClient.this.handleIPv4Failure();
                                            break;
                                        default:
                                            IpClient.this.logError("Unknown CMD_POST_DHCP_ACTION status: %s", Integer.valueOf(msg.arg1));
                                            break;
                                    }
                                case DhcpClient.CMD_ON_QUIT /* 196613 */:
                                    IpClient.this.logError("Unexpected CMD_ON_QUIT.", new Object[0]);
                                    IpClient.this.mDhcpClient = null;
                                    break;
                                default:
                                    switch (i) {
                                        case DhcpClient.CMD_CLEAR_LINKADDRESS /* 196615 */:
                                            IpClient.this.mInterfaceCtrl.clearIPv4Address();
                                            break;
                                        case DhcpClient.CMD_CONFIGURE_LINKADDRESS /* 196616 */:
                                            LinkAddress ipAddress = (LinkAddress) msg.obj;
                                            if (IpClient.this.mInterfaceCtrl.setIPv4Address(ipAddress)) {
                                                IpClient.this.mDhcpClient.sendMessage(DhcpClient.EVENT_LINKADDRESS_CONFIGURED);
                                                break;
                                            } else {
                                                IpClient.this.logError("Failed to set IPv4 address.", new Object[0]);
                                                IpClient.this.dispatchCallback(LinkProperties.ProvisioningChange.LOST_PROVISIONING, new LinkProperties(IpClient.this.mLinkProperties));
                                                IpClient.this.transitionTo(IpClient.this.mStoppingState);
                                                break;
                                            }
                                        default:
                                            return false;
                                    }
                            }
                    }
            }
            IpClient.this.mMsgStateLogger.handled(this, IpClient.this.getCurrentState());
            return true;
        }
    }

    /* loaded from: classes.dex */
    private static class MessageHandlingLogger {
        public String processedInState;
        public String receivedInState;

        private MessageHandlingLogger() {
        }

        public void reset() {
            this.processedInState = null;
            this.receivedInState = null;
        }

        public void handled(State processedIn, IState receivedIn) {
            this.processedInState = processedIn.getClass().getSimpleName();
            this.receivedInState = receivedIn.getName();
        }

        public String toString() {
            return String.format("rcvd_in=%s, proc_in=%s", this.receivedInState, this.processedInState);
        }
    }

    static <T> boolean any(Iterable<T> coll, Predicate<T> fn) {
        for (T t : coll) {
            if (fn.test(t)) {
                return true;
            }
        }
        return false;
    }

    static <T> boolean all(Iterable<T> coll, Predicate<T> fn) {
        return !any(coll, not(fn));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$not$0(Predicate fn, Object t) {
        return !fn.test(t);
    }

    static <T> Predicate<T> not(final Predicate<T> fn) {
        return new Predicate() { // from class: android.net.ip.-$$Lambda$IpClient$avmS8r0wlmaRuIXDbDPiQFTUPlA
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return IpClient.lambda$not$0(fn, obj);
            }
        };
    }

    static <T> String join(String delimiter, Collection<T> coll) {
        return (String) coll.stream().map(new Function() { // from class: android.net.ip.-$$Lambda$IpClient$JsVbJ5mpbRjwJuW_A3bDJMqYpF0
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                String obj2;
                obj2 = obj.toString();
                return obj2;
            }
        }).collect(Collectors.joining(delimiter));
    }

    static <T> T find(Iterable<T> coll, Predicate<T> fn) {
        for (T t : coll) {
            if (fn.test(t)) {
                return t;
            }
        }
        return null;
    }

    /* JADX WARN: Multi-variable type inference failed */
    static <T> List<T> findAll(Collection<T> coll, Predicate<T> fn) {
        return (List) coll.stream().filter(fn).collect(Collectors.toList());
    }
}
