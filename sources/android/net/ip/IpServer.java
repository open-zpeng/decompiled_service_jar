package android.net.ip;

import android.net.INetd;
import android.net.INetworkStackStatusCallback;
import android.net.INetworkStatsService;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkStackClient;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.DhcpServingParamsParcelExt;
import android.net.dhcp.IDhcpServer;
import android.net.ip.IpServer;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.util.InterfaceParams;
import android.net.util.InterfaceSet;
import android.net.util.NetdService;
import android.net.util.NetworkConstants;
import android.net.util.SharedLog;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/* loaded from: classes.dex */
public class IpServer extends StateMachine {
    private static final int BASE_IFACE = 327780;
    private static final int BLUETOOTH_DHCP_PREFIX_LENGTH = 24;
    private static final String BLUETOOTH_IFACE_ADDR = "192.168.44.1";
    public static final int CMD_INTERFACE_DOWN = 327784;
    public static final int CMD_IPV6_TETHER_UPDATE = 327793;
    public static final int CMD_IP_FORWARDING_DISABLE_ERROR = 327788;
    public static final int CMD_IP_FORWARDING_ENABLE_ERROR = 327787;
    public static final int CMD_SET_DNS_FORWARDERS_ERROR = 327791;
    public static final int CMD_START_TETHERING_ERROR = 327789;
    public static final int CMD_STOP_TETHERING_ERROR = 327790;
    public static final int CMD_TETHER_CONNECTION_CHANGED = 327792;
    public static final int CMD_TETHER_REQUESTED = 327782;
    public static final int CMD_TETHER_UNREQUESTED = 327783;
    private static final boolean DBG = true;
    private static final int DHCP_LEASE_TIME_SECS = 3600;
    private static final byte DOUG_ADAMS = 42;
    private static final String P2P_HOST_IFACE_ADDR = "192.168.49.1";
    private static final int P2P_HOST_IFACE_PREFIX_LENGTH = 24;
    public static final int STATE_AVAILABLE = 1;
    public static final int STATE_LOCAL_ONLY = 3;
    public static final int STATE_TETHERED = 2;
    public static final int STATE_UNAVAILABLE = 0;
    private static final String TAG = "IpServer";
    private static final String USB_NEAR_IFACE_ADDR = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH = 24;
    private static final boolean VDBG = true;
    private static final String WIFI_HOST_IFACE_ADDR = "192.168.43.1";
    private static final int WIFI_HOST_IFACE_PREFIX_LENGTH = 24;
    private static final Class[] messageClasses = {IpServer.class};
    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(messageClasses);
    private final Callback mCallback;
    private final Dependencies mDeps;
    private IDhcpServer mDhcpServer;
    private int mDhcpServerStartIndex;
    private final String mIfaceName;
    private final State mInitialState;
    private final InterfaceController mInterfaceCtrl;
    private InterfaceParams mInterfaceParams;
    private final int mInterfaceType;
    private int mLastError;
    private LinkProperties mLastIPv6LinkProperties;
    private RouterAdvertisementDaemon.RaParams mLastRaParams;
    private final LinkProperties mLinkProperties;
    private final State mLocalHotspotState;
    private final SharedLog mLog;
    private final INetworkManagementService mNMService;
    private final INetd mNetd;
    private RouterAdvertisementDaemon mRaDaemon;
    private int mServingMode;
    private final INetworkStatsService mStatsService;
    private final State mTetheredState;
    private final State mUnavailableState;
    private InterfaceSet mUpstreamIfaceSet;
    private final boolean mUsingLegacyDhcp;

    public static String getStateString(int state) {
        if (state != 0) {
            if (state != 1) {
                if (state != 2) {
                    if (state == 3) {
                        return "LOCAL_ONLY";
                    }
                    return "UNKNOWN: " + state;
                }
                return "TETHERED";
            }
            return "AVAILABLE";
        }
        return "UNAVAILABLE";
    }

    /* loaded from: classes.dex */
    public static class Callback {
        public void updateInterfaceState(IpServer who, int state, int lastError) {
        }

        public void updateLinkProperties(IpServer who, LinkProperties newLp) {
        }
    }

    /* loaded from: classes.dex */
    public static class Dependencies {
        public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
            return new RouterAdvertisementDaemon(ifParams);
        }

        public InterfaceParams getInterfaceParams(String ifName) {
            return InterfaceParams.getByName(ifName);
        }

        public INetd getNetdService() {
            return NetdService.getInstance();
        }

        public void makeDhcpServer(String ifName, DhcpServingParamsParcel params, DhcpServerCallbacks cb) {
            NetworkStackClient.getInstance().makeDhcpServer(ifName, params, cb);
        }
    }

    public IpServer(String ifaceName, Looper looper, int interfaceType, SharedLog log, INetworkManagementService nMService, INetworkStatsService statsService, Callback callback, boolean usingLegacyDhcp, Dependencies deps) {
        super(ifaceName, looper);
        this.mDhcpServerStartIndex = 0;
        this.mLog = log.forSubComponent(ifaceName);
        this.mNMService = nMService;
        this.mNetd = deps.getNetdService();
        this.mStatsService = statsService;
        this.mCallback = callback;
        this.mInterfaceCtrl = new InterfaceController(ifaceName, this.mNetd, this.mLog);
        this.mIfaceName = ifaceName;
        this.mInterfaceType = interfaceType;
        this.mLinkProperties = new LinkProperties();
        this.mUsingLegacyDhcp = usingLegacyDhcp;
        this.mDeps = deps;
        resetLinkProperties();
        this.mLastError = 0;
        this.mServingMode = 1;
        this.mInitialState = new InitialState();
        this.mLocalHotspotState = new LocalHotspotState();
        this.mTetheredState = new TetheredState();
        this.mUnavailableState = new UnavailableState();
        addState(this.mInitialState);
        addState(this.mLocalHotspotState);
        addState(this.mTetheredState);
        addState(this.mUnavailableState);
        setInitialState(this.mInitialState);
    }

    public String interfaceName() {
        return this.mIfaceName;
    }

    public int interfaceType() {
        return this.mInterfaceType;
    }

    public int lastError() {
        return this.mLastError;
    }

    public int servingMode() {
        return this.mServingMode;
    }

    public LinkProperties linkProperties() {
        return new LinkProperties(this.mLinkProperties);
    }

    public void stop() {
        sendMessage(CMD_INTERFACE_DOWN);
    }

    public void unwanted() {
        sendMessage(CMD_TETHER_UNREQUESTED);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startIPv4() {
        return configureIPv4(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class OnHandlerStatusCallback extends INetworkStackStatusCallback.Stub {
        /* renamed from: callback */
        public abstract void lambda$onStatusAvailable$0$IpServer$OnHandlerStatusCallback(int i);

        private OnHandlerStatusCallback() {
        }

        @Override // android.net.INetworkStackStatusCallback
        public void onStatusAvailable(final int statusCode) {
            IpServer.this.getHandler().post(new Runnable() { // from class: android.net.ip.-$$Lambda$IpServer$OnHandlerStatusCallback$czoKoFz-ZQJY8J5O14qT9czTIoo
                @Override // java.lang.Runnable
                public final void run() {
                    IpServer.OnHandlerStatusCallback.this.lambda$onStatusAvailable$0$IpServer$OnHandlerStatusCallback(statusCode);
                }
            });
        }

        @Override // android.net.INetworkStackStatusCallback
        public int getInterfaceVersion() {
            return 3;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DhcpServerCallbacksImpl extends DhcpServerCallbacks {
        private final int mStartIndex;

        private DhcpServerCallbacksImpl(int startIndex) {
            this.mStartIndex = startIndex;
        }

        @Override // android.net.dhcp.IDhcpServerCallbacks
        public void onDhcpServerCreated(final int statusCode, final IDhcpServer server) throws RemoteException {
            IpServer.this.getHandler().post(new Runnable() { // from class: android.net.ip.-$$Lambda$IpServer$DhcpServerCallbacksImpl$nBlfeyPZEu2j0KBs4BJklDJTve4
                @Override // java.lang.Runnable
                public final void run() {
                    IpServer.DhcpServerCallbacksImpl.this.lambda$onDhcpServerCreated$0$IpServer$DhcpServerCallbacksImpl(statusCode, server);
                }
            });
        }

        public /* synthetic */ void lambda$onDhcpServerCreated$0$IpServer$DhcpServerCallbacksImpl(int statusCode, IDhcpServer server) {
            if (this.mStartIndex != IpServer.this.mDhcpServerStartIndex) {
                return;
            }
            if (statusCode == 1) {
                IpServer.this.mDhcpServer = server;
                try {
                    IpServer.this.mDhcpServer.start(new OnHandlerStatusCallback() { // from class: android.net.ip.IpServer.DhcpServerCallbacksImpl.1
                        {
                            IpServer ipServer = IpServer.this;
                        }

                        @Override // android.net.ip.IpServer.OnHandlerStatusCallback
                        public void callback(int startStatusCode) {
                            if (startStatusCode != 1) {
                                Log.e(IpServer.TAG, "Error starting DHCP server: " + startStatusCode);
                                DhcpServerCallbacksImpl.this.handleError();
                            }
                        }
                    });
                    return;
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                    return;
                }
            }
            Log.e(IpServer.TAG, "Error obtaining DHCP server: " + statusCode);
            handleError();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleError() {
            IpServer.this.mLastError = 12;
            IpServer ipServer = IpServer.this;
            ipServer.transitionTo(ipServer.mInitialState);
        }
    }

    private boolean startDhcp(Inet4Address addr, int prefixLen) {
        if (this.mUsingLegacyDhcp) {
            return true;
        }
        DhcpServingParamsParcel params = new DhcpServingParamsParcelExt().setDefaultRouters(addr).setDhcpLeaseTimeSecs(3600L).setDnsServers(addr).setServerAddr(new LinkAddress(addr, prefixLen)).setMetered(true);
        this.mDhcpServerStartIndex++;
        this.mDeps.makeDhcpServer(this.mIfaceName, params, new DhcpServerCallbacksImpl(this.mDhcpServerStartIndex));
        return true;
    }

    private void stopDhcp() {
        this.mDhcpServerStartIndex++;
        IDhcpServer iDhcpServer = this.mDhcpServer;
        if (iDhcpServer != null) {
            try {
                iDhcpServer.stop(new OnHandlerStatusCallback() { // from class: android.net.ip.IpServer.1
                    @Override // android.net.ip.IpServer.OnHandlerStatusCallback
                    public void callback(int statusCode) {
                        if (statusCode != 1) {
                            Log.e(IpServer.TAG, "Error stopping DHCP server: " + statusCode);
                            IpServer.this.mLastError = 12;
                        }
                    }
                });
                this.mDhcpServer = null;
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    private boolean configureDhcp(boolean enable, Inet4Address addr, int prefixLen) {
        if (enable) {
            return startDhcp(addr, prefixLen);
        }
        stopDhcp();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopIPv4() {
        configureIPv4(false);
        this.mInterfaceCtrl.clearIPv4Address();
    }

    /* JADX WARN: Removed duplicated region for block: B:25:0x007e A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:26:0x007f  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean configureIPv4(boolean r11) {
        /*
            r10 = this;
            java.lang.StringBuilder r0 = new java.lang.StringBuilder
            r0.<init>()
            java.lang.String r1 = "configureIPv4("
            r0.append(r1)
            r0.append(r11)
            java.lang.String r1 = ")"
            r0.append(r1)
            java.lang.String r0 = r0.toString()
            java.lang.String r1 = "IpServer"
            android.util.Log.d(r1, r0)
            r0 = 0
            r2 = 0
            int r3 = r10.mInterfaceType
            r4 = 3
            r5 = 1
            if (r3 != r5) goto L28
            java.lang.String r0 = "192.168.42.129"
            r2 = 24
            goto L35
        L28:
            if (r3 != 0) goto L2f
            java.lang.String r0 = "192.168.43.1"
            r2 = 24
            goto L35
        L2f:
            if (r3 != r4) goto Lbf
            java.lang.String r0 = "192.168.49.1"
            r2 = 24
        L35:
            r3 = 0
            android.os.INetworkManagementService r6 = r10.mNMService     // Catch: java.lang.Exception -> L9d
            java.lang.String r7 = r10.mIfaceName     // Catch: java.lang.Exception -> L9d
            android.net.InterfaceConfiguration r6 = r6.getInterfaceConfig(r7)     // Catch: java.lang.Exception -> L9d
            if (r6 != 0) goto L46
            java.lang.String r4 = "Received null interface config"
            android.util.Log.e(r1, r4)     // Catch: java.lang.Exception -> L9d
            return r3
        L46:
            java.net.InetAddress r7 = android.net.NetworkUtils.numericToInetAddress(r0)     // Catch: java.lang.Exception -> L9d
            android.net.LinkAddress r8 = new android.net.LinkAddress     // Catch: java.lang.Exception -> L9d
            r8.<init>(r7, r2)     // Catch: java.lang.Exception -> L9d
            r6.setLinkAddress(r8)     // Catch: java.lang.Exception -> L9d
            int r9 = r10.mInterfaceType     // Catch: java.lang.Exception -> L9d
            if (r9 == 0) goto L65
            int r9 = r10.mInterfaceType     // Catch: java.lang.Exception -> L9d
            if (r9 != r4) goto L5b
            goto L65
        L5b:
            if (r11 == 0) goto L61
            r6.setInterfaceUp()     // Catch: java.lang.Exception -> L9d
            goto L68
        L61:
            r6.setInterfaceDown()     // Catch: java.lang.Exception -> L9d
            goto L68
        L65:
            r6.ignoreInterfaceUpDownStatus()     // Catch: java.lang.Exception -> L9d
        L68:
            java.lang.String r4 = "running"
            r6.clearFlag(r4)     // Catch: java.lang.Exception -> L9d
            android.os.INetworkManagementService r4 = r10.mNMService     // Catch: java.lang.Exception -> L9d
            java.lang.String r9 = r10.mIfaceName     // Catch: java.lang.Exception -> L9d
            r4.setInterfaceConfig(r9, r6)     // Catch: java.lang.Exception -> L9d
            r4 = r7
            java.net.Inet4Address r4 = (java.net.Inet4Address) r4     // Catch: java.lang.Exception -> L9d
            boolean r1 = r10.configureDhcp(r11, r4, r2)     // Catch: java.lang.Exception -> L9d
            if (r1 != 0) goto L7f
            return r3
        L7f:
            android.net.RouteInfo r1 = new android.net.RouteInfo
            r1.<init>(r8)
            if (r11 == 0) goto L92
            android.net.LinkProperties r3 = r10.mLinkProperties
            r3.addLinkAddress(r8)
            android.net.LinkProperties r3 = r10.mLinkProperties
            r3.addRoute(r1)
            goto L9c
        L92:
            android.net.LinkProperties r3 = r10.mLinkProperties
            r3.removeLinkAddress(r8)
            android.net.LinkProperties r3 = r10.mLinkProperties
            r3.removeRoute(r1)
        L9c:
            return r5
        L9d:
            r4 = move-exception
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r6 = "Error configuring interface "
            r5.append(r6)
            r5.append(r4)
            java.lang.String r5 = r5.toString()
            android.util.Log.e(r1, r5)
            if (r11 != 0) goto Lbe
            r10.stopDhcp()     // Catch: java.lang.Exception -> Lb8
            goto Lbe
        Lb8:
            r5 = move-exception
            java.lang.String r6 = "Error stopping DHCP"
            android.util.Log.e(r1, r6, r5)
        Lbe:
            return r3
        Lbf:
            java.lang.String r1 = "192.168.44.1"
            java.net.InetAddress r1 = android.net.NetworkUtils.numericToInetAddress(r1)
            java.net.Inet4Address r1 = (java.net.Inet4Address) r1
            r3 = 24
            boolean r3 = r10.configureDhcp(r11, r1, r3)
            return r3
        */
        throw new UnsupportedOperationException("Method not decompiled: android.net.ip.IpServer.configureIPv4(boolean):boolean");
    }

    private String getRandomWifiIPv4Address() {
        try {
            byte[] bytes = NetworkUtils.numericToInetAddress(WIFI_HOST_IFACE_ADDR).getAddress();
            bytes[3] = getRandomSanitizedByte((byte) 42, NetworkConstants.asByte(0), NetworkConstants.asByte(1), NetworkConstants.FF);
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return WIFI_HOST_IFACE_ADDR;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startIPv6() {
        this.mInterfaceParams = this.mDeps.getInterfaceParams(this.mIfaceName);
        InterfaceParams interfaceParams = this.mInterfaceParams;
        if (interfaceParams == null) {
            Log.e(TAG, "Failed to find InterfaceParams");
            stopIPv6();
            return false;
        }
        this.mRaDaemon = this.mDeps.getRouterAdvertisementDaemon(interfaceParams);
        if (!this.mRaDaemon.start()) {
            stopIPv6();
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopIPv6() {
        this.mInterfaceParams = null;
        setRaParams(null);
        RouterAdvertisementDaemon routerAdvertisementDaemon = this.mRaDaemon;
        if (routerAdvertisementDaemon != null) {
            routerAdvertisementDaemon.stop();
            this.mRaDaemon = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateUpstreamIPv6LinkProperties(LinkProperties v6only) {
        if (this.mRaDaemon == null || Objects.equals(this.mLastIPv6LinkProperties, v6only)) {
            return;
        }
        RouterAdvertisementDaemon.RaParams params = null;
        if (v6only != null) {
            params = new RouterAdvertisementDaemon.RaParams();
            params.mtu = v6only.getMtu();
            params.hasDefaultRoute = v6only.hasIpv6DefaultRoute();
            if (params.hasDefaultRoute) {
                params.hopLimit = getHopLimit(v6only.getInterfaceName());
            }
            for (LinkAddress linkAddr : v6only.getLinkAddresses()) {
                if (linkAddr.getPrefixLength() == 64) {
                    IpPrefix prefix = new IpPrefix(linkAddr.getAddress(), linkAddr.getPrefixLength());
                    params.prefixes.add(prefix);
                    Inet6Address dnsServer = getLocalDnsIpFor(prefix);
                    if (dnsServer != null) {
                        params.dnses.add(dnsServer);
                    }
                }
            }
        }
        setRaParams(params);
        this.mLastIPv6LinkProperties = v6only;
    }

    private void configureLocalIPv6Routes(HashSet<IpPrefix> deprecatedPrefixes, HashSet<IpPrefix> newPrefixes) {
        if (!deprecatedPrefixes.isEmpty()) {
            ArrayList<RouteInfo> toBeRemoved = getLocalRoutesFor(this.mIfaceName, deprecatedPrefixes);
            try {
                int removalFailures = this.mNMService.removeRoutesFromLocalNetwork(toBeRemoved);
                if (removalFailures > 0) {
                    this.mLog.e(String.format("Failed to remove %d IPv6 routes from local table.", Integer.valueOf(removalFailures)));
                }
            } catch (RemoteException e) {
                SharedLog sharedLog = this.mLog;
                sharedLog.e("Failed to remove IPv6 routes from local table: " + e);
            }
            Iterator<RouteInfo> it = toBeRemoved.iterator();
            while (it.hasNext()) {
                RouteInfo route = it.next();
                this.mLinkProperties.removeRoute(route);
            }
        }
        if (newPrefixes != null && !newPrefixes.isEmpty()) {
            HashSet<IpPrefix> addedPrefixes = (HashSet) newPrefixes.clone();
            RouterAdvertisementDaemon.RaParams raParams = this.mLastRaParams;
            if (raParams != null) {
                addedPrefixes.removeAll(raParams.prefixes);
            }
            if (!addedPrefixes.isEmpty()) {
                ArrayList<RouteInfo> toBeAdded = getLocalRoutesFor(this.mIfaceName, addedPrefixes);
                try {
                    this.mNMService.addInterfaceToLocalNetwork(this.mIfaceName, toBeAdded);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to add IPv6 routes to local table: " + e2);
                }
                Iterator<RouteInfo> it2 = toBeAdded.iterator();
                while (it2.hasNext()) {
                    RouteInfo route2 = it2.next();
                    this.mLinkProperties.addRoute(route2);
                }
            }
        }
    }

    private void configureLocalIPv6Dns(HashSet<Inet6Address> deprecatedDnses, HashSet<Inet6Address> newDnses) {
        if (this.mNetd == null) {
            if (newDnses != null) {
                newDnses.clear();
            }
            this.mLog.e("No netd service instance available; not setting local IPv6 addresses");
            return;
        }
        if (!deprecatedDnses.isEmpty()) {
            Iterator<Inet6Address> it = deprecatedDnses.iterator();
            while (it.hasNext()) {
                Inet6Address dns = it.next();
                if (!this.mInterfaceCtrl.removeAddress(dns, 64)) {
                    SharedLog sharedLog = this.mLog;
                    sharedLog.e("Failed to remove local dns IP " + dns);
                }
                this.mLinkProperties.removeLinkAddress(new LinkAddress(dns, 64));
            }
        }
        if (newDnses != null && !newDnses.isEmpty()) {
            HashSet<Inet6Address> addedDnses = (HashSet) newDnses.clone();
            RouterAdvertisementDaemon.RaParams raParams = this.mLastRaParams;
            if (raParams != null) {
                addedDnses.removeAll(raParams.dnses);
            }
            Iterator<Inet6Address> it2 = addedDnses.iterator();
            while (it2.hasNext()) {
                Inet6Address dns2 = it2.next();
                if (!this.mInterfaceCtrl.addAddress(dns2, 64)) {
                    SharedLog sharedLog2 = this.mLog;
                    sharedLog2.e("Failed to add local dns IP " + dns2);
                    newDnses.remove(dns2);
                }
                this.mLinkProperties.addLinkAddress(new LinkAddress(dns2, 64));
            }
        }
        try {
            this.mNetd.tetherApplyDnsInterfaces();
        } catch (ServiceSpecificException | RemoteException e) {
            this.mLog.e("Failed to update local DNS caching server");
            if (newDnses != null) {
                newDnses.clear();
            }
        }
    }

    private byte getHopLimit(String upstreamIface) {
        try {
            int upstreamHopLimit = Integer.parseUnsignedInt(this.mNetd.getProcSysNet(6, 1, upstreamIface, "hop_limit"));
            return (byte) Integer.min(upstreamHopLimit + 1, 255);
        } catch (Exception e) {
            this.mLog.e("Failed to find upstream interface hop limit", e);
            return (byte) 65;
        }
    }

    private void setRaParams(RouterAdvertisementDaemon.RaParams newParams) {
        if (this.mRaDaemon != null) {
            RouterAdvertisementDaemon.RaParams deprecatedParams = RouterAdvertisementDaemon.RaParams.getDeprecatedRaParams(this.mLastRaParams, newParams);
            configureLocalIPv6Routes(deprecatedParams.prefixes, newParams != null ? newParams.prefixes : null);
            configureLocalIPv6Dns(deprecatedParams.dnses, newParams != null ? newParams.dnses : null);
            this.mRaDaemon.buildNewRa(deprecatedParams, newParams);
        }
        this.mLastRaParams = newParams;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logMessage(State state, int what) {
        SharedLog sharedLog = this.mLog;
        sharedLog.log(state.getName() + " got " + sMagicDecoderRing.get(what, Integer.toString(what)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInterfaceState(int newInterfaceState) {
        this.mServingMode = newInterfaceState;
        this.mCallback.updateInterfaceState(this, newInterfaceState, this.mLastError);
        sendLinkProperties();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendLinkProperties() {
        this.mCallback.updateLinkProperties(this, new LinkProperties(this.mLinkProperties));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetLinkProperties() {
        this.mLinkProperties.clear();
        this.mLinkProperties.setInterfaceName(this.mIfaceName);
    }

    /* loaded from: classes.dex */
    class InitialState extends State {
        InitialState() {
        }

        public void enter() {
            Log.d(IpServer.TAG, "InitialState enter");
            IpServer.this.sendInterfaceState(1);
        }

        public boolean processMessage(Message message) {
            IpServer.this.logMessage(this, message.what);
            int i = message.what;
            if (i != 327782) {
                if (i != 327784) {
                    if (i != 327793) {
                        return false;
                    }
                    IpServer.this.updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    return true;
                }
                Log.d(IpServer.TAG, "InitialState, interface down");
                IpServer ipServer = IpServer.this;
                ipServer.transitionTo(ipServer.mUnavailableState);
                return true;
            }
            Log.d(IpServer.TAG, "InitialState, deal CMD_TETHER_REQUEST");
            IpServer.this.mLastError = 0;
            int i2 = message.arg1;
            if (i2 == 2) {
                IpServer ipServer2 = IpServer.this;
                ipServer2.transitionTo(ipServer2.mTetheredState);
                return true;
            } else if (i2 == 3) {
                IpServer ipServer3 = IpServer.this;
                ipServer3.transitionTo(ipServer3.mLocalHotspotState);
                return true;
            } else {
                Log.e(IpServer.TAG, "Invalid tethering interface serving state specified.");
                return true;
            }
        }
    }

    /* loaded from: classes.dex */
    class BaseServingState extends State {
        BaseServingState() {
        }

        public void enter() {
            Log.d(IpServer.TAG, "BaseServingState enter");
            if (!IpServer.this.startIPv4()) {
                IpServer.this.mLastError = 10;
                return;
            }
            try {
                IpServer.this.mNMService.tetherInterface(IpServer.this.mIfaceName);
                if (!IpServer.this.startIPv6()) {
                    Log.e(IpServer.TAG, "Failed to startIPv6");
                }
            } catch (Exception e) {
                Log.e(IpServer.TAG, "Error Tethering: " + e);
                IpServer.this.mLastError = 6;
            }
        }

        public void exit() {
            IpServer.this.stopIPv6();
            try {
                IpServer.this.mNMService.untetherInterface(IpServer.this.mIfaceName);
            } catch (Exception e) {
                IpServer.this.mLastError = 7;
                Log.e(IpServer.TAG, "Failed to untether interface: " + e);
            }
            IpServer.this.stopIPv4();
            IpServer.this.resetLinkProperties();
        }

        public boolean processMessage(Message message) {
            IpServer.this.logMessage(this, message.what);
            switch (message.what) {
                case IpServer.CMD_TETHER_UNREQUESTED /* 327783 */:
                    IpServer ipServer = IpServer.this;
                    ipServer.transitionTo(ipServer.mInitialState);
                    Log.d(IpServer.TAG, "BaseServingState: Untethered (unrequested)" + IpServer.this.mIfaceName);
                    return true;
                case IpServer.CMD_INTERFACE_DOWN /* 327784 */:
                    IpServer ipServer2 = IpServer.this;
                    ipServer2.transitionTo(ipServer2.mUnavailableState);
                    Log.d(IpServer.TAG, "BaseServingState: Untethered (ifdown)" + IpServer.this.mIfaceName);
                    return true;
                case 327785:
                case 327786:
                case IpServer.CMD_TETHER_CONNECTION_CHANGED /* 327792 */:
                default:
                    return false;
                case IpServer.CMD_IP_FORWARDING_ENABLE_ERROR /* 327787 */:
                case IpServer.CMD_IP_FORWARDING_DISABLE_ERROR /* 327788 */:
                case IpServer.CMD_START_TETHERING_ERROR /* 327789 */:
                case IpServer.CMD_STOP_TETHERING_ERROR /* 327790 */:
                case IpServer.CMD_SET_DNS_FORWARDERS_ERROR /* 327791 */:
                    IpServer.this.mLastError = 5;
                    IpServer ipServer3 = IpServer.this;
                    ipServer3.transitionTo(ipServer3.mInitialState);
                    return true;
                case IpServer.CMD_IPV6_TETHER_UPDATE /* 327793 */:
                    IpServer.this.updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    IpServer.this.sendLinkProperties();
                    return true;
            }
        }
    }

    /* loaded from: classes.dex */
    class LocalHotspotState extends BaseServingState {
        LocalHotspotState() {
            super();
        }

        @Override // android.net.ip.IpServer.BaseServingState
        public void enter() {
            super.enter();
            if (IpServer.this.mLastError != 0) {
                IpServer ipServer = IpServer.this;
                ipServer.transitionTo(ipServer.mInitialState);
            }
            Log.d(IpServer.TAG, "Local hotspot " + IpServer.this.mIfaceName);
            IpServer.this.sendInterfaceState(3);
        }

        @Override // android.net.ip.IpServer.BaseServingState
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) {
                return true;
            }
            IpServer.this.logMessage(this, message.what);
            int i = message.what;
            if (i == 327782) {
                IpServer.this.mLog.e("CMD_TETHER_REQUESTED while in local-only hotspot mode.");
            } else if (i != 327792) {
                return false;
            }
            return true;
        }
    }

    /* loaded from: classes.dex */
    class TetheredState extends BaseServingState {
        TetheredState() {
            super();
        }

        @Override // android.net.ip.IpServer.BaseServingState
        public void enter() {
            super.enter();
            if (IpServer.this.mLastError != 0) {
                IpServer ipServer = IpServer.this;
                ipServer.transitionTo(ipServer.mInitialState);
            }
            Log.d(IpServer.TAG, "Tethered " + IpServer.this.mIfaceName);
            IpServer.this.sendInterfaceState(2);
        }

        @Override // android.net.ip.IpServer.BaseServingState
        public void exit() {
            cleanupUpstream();
            super.exit();
        }

        private void cleanupUpstream() {
            if (IpServer.this.mUpstreamIfaceSet == null) {
                return;
            }
            for (String ifname : IpServer.this.mUpstreamIfaceSet.ifnames) {
                cleanupUpstreamInterface(ifname);
            }
            IpServer.this.mUpstreamIfaceSet = null;
        }

        private void cleanupUpstreamInterface(String upstreamIface) {
            try {
                IpServer.this.mStatsService.forceUpdate();
            } catch (Exception e) {
                Log.e(IpServer.TAG, "Exception in forceUpdate: " + e.toString());
            }
            try {
                IpServer.this.mNMService.stopInterfaceForwarding(IpServer.this.mIfaceName, upstreamIface);
            } catch (Exception e2) {
                Log.e(IpServer.TAG, "Exception in removeInterfaceForward: " + e2.toString());
            }
            try {
                IpServer.this.mNMService.disableNat(IpServer.this.mIfaceName, upstreamIface);
            } catch (Exception e3) {
                Log.e(IpServer.TAG, "Exception in disableNat: " + e3.toString());
            }
        }

        @Override // android.net.ip.IpServer.BaseServingState
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) {
                return true;
            }
            IpServer.this.logMessage(this, message.what);
            int i = message.what;
            if (i == 327782) {
                IpServer.this.mLog.e("CMD_TETHER_REQUESTED while already tethering.");
            } else if (i == 327792) {
                InterfaceSet newUpstreamIfaceSet = (InterfaceSet) message.obj;
                if (noChangeInUpstreamIfaceSet(newUpstreamIfaceSet)) {
                    Log.d(IpServer.TAG, "Connection changed noop - dropping");
                } else if (newUpstreamIfaceSet == null) {
                    cleanupUpstream();
                } else {
                    for (String removed : upstreamInterfacesRemoved(newUpstreamIfaceSet)) {
                        cleanupUpstreamInterface(removed);
                    }
                    Set<String> added = upstreamInterfacesAdd(newUpstreamIfaceSet);
                    IpServer.this.mUpstreamIfaceSet = newUpstreamIfaceSet;
                    for (String ifname : added) {
                        try {
                            IpServer.this.mNMService.enableNat(IpServer.this.mIfaceName, ifname);
                            IpServer.this.mNMService.startInterfaceForwarding(IpServer.this.mIfaceName, ifname);
                        } catch (Exception e) {
                            SharedLog sharedLog = IpServer.this.mLog;
                            sharedLog.e("Exception enabling NAT: " + e);
                            cleanupUpstream();
                            IpServer.this.mLastError = 8;
                            IpServer ipServer = IpServer.this;
                            ipServer.transitionTo(ipServer.mInitialState);
                            return true;
                        }
                    }
                }
            } else {
                return false;
            }
            return true;
        }

        private boolean noChangeInUpstreamIfaceSet(InterfaceSet newIfaces) {
            if (IpServer.this.mUpstreamIfaceSet == null && newIfaces == null) {
                return true;
            }
            if (IpServer.this.mUpstreamIfaceSet != null && newIfaces != null) {
                return IpServer.this.mUpstreamIfaceSet.equals(newIfaces);
            }
            return false;
        }

        private Set<String> upstreamInterfacesRemoved(InterfaceSet newIfaces) {
            if (IpServer.this.mUpstreamIfaceSet == null) {
                return new HashSet();
            }
            HashSet<String> removed = new HashSet<>(IpServer.this.mUpstreamIfaceSet.ifnames);
            removed.removeAll(newIfaces.ifnames);
            return removed;
        }

        private Set<String> upstreamInterfacesAdd(InterfaceSet newIfaces) {
            HashSet<String> added = new HashSet<>(newIfaces.ifnames);
            if (IpServer.this.mUpstreamIfaceSet != null) {
                added.removeAll(IpServer.this.mUpstreamIfaceSet.ifnames);
            }
            return added;
        }
    }

    /* loaded from: classes.dex */
    class UnavailableState extends State {
        UnavailableState() {
        }

        public void enter() {
            IpServer.this.mLastError = 0;
            IpServer.this.sendInterfaceState(0);
        }
    }

    private static ArrayList<RouteInfo> getLocalRoutesFor(String ifname, HashSet<IpPrefix> prefixes) {
        ArrayList<RouteInfo> localRoutes = new ArrayList<>();
        Iterator<IpPrefix> it = prefixes.iterator();
        while (it.hasNext()) {
            IpPrefix ipp = it.next();
            localRoutes.add(new RouteInfo(ipp, null, ifname));
        }
        return localRoutes;
    }

    private static Inet6Address getLocalDnsIpFor(IpPrefix localPrefix) {
        byte[] dnsBytes = localPrefix.getRawAddress();
        dnsBytes[dnsBytes.length - 1] = getRandomSanitizedByte((byte) 42, NetworkConstants.asByte(0), NetworkConstants.asByte(1));
        try {
            return Inet6Address.getByAddress((String) null, dnsBytes, 0);
        } catch (UnknownHostException e) {
            Slog.wtf(TAG, "Failed to construct Inet6Address from: " + localPrefix);
            return null;
        }
    }

    private static byte getRandomSanitizedByte(byte dflt, byte... excluded) {
        byte random = (byte) new Random().nextInt();
        for (int value : excluded) {
            if (random == value) {
                return dflt;
            }
        }
        return random;
    }
}
