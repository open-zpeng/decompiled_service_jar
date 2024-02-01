package com.android.server.connectivity.tethering;

import android.net.INetd;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.ip.InterfaceController;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.util.InterfaceParams;
import android.net.util.InterfaceSet;
import android.net.util.NetworkConstants;
import android.net.util.SharedLog;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
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
public class TetherInterfaceStateMachine extends StateMachine {
    private static final int BASE_IFACE = 327780;
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
    private static final boolean DBG = false;
    private static final byte DOUG_ADAMS = 42;
    private static final String TAG = "TetherInterfaceSM";
    private static final String USB_NEAR_IFACE_ADDR = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH = 24;
    private static final boolean VDBG = false;
    private static final String WIFI_HOST_IFACE_ADDR = "192.168.43.1";
    private static final int WIFI_HOST_IFACE_PREFIX_LENGTH = 24;
    private final TetheringDependencies mDeps;
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
    private final IControlsTethering mTetherController;
    private final State mTetheredState;
    private final State mUnavailableState;
    private InterfaceSet mUpstreamIfaceSet;
    private static final IpPrefix LINK_LOCAL_PREFIX = new IpPrefix("fe80::/64");
    private static final Class[] messageClasses = {TetherInterfaceStateMachine.class};
    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(messageClasses);

    public TetherInterfaceStateMachine(String ifaceName, Looper looper, int interfaceType, SharedLog log, INetworkManagementService nMService, INetworkStatsService statsService, IControlsTethering tetherController, TetheringDependencies deps) {
        super(ifaceName, looper);
        this.mLog = log.forSubComponent(ifaceName);
        this.mNMService = nMService;
        this.mNetd = deps.getNetdService();
        this.mStatsService = statsService;
        this.mTetherController = tetherController;
        this.mInterfaceCtrl = new InterfaceController(ifaceName, nMService, this.mNetd, this.mLog);
        this.mIfaceName = ifaceName;
        this.mInterfaceType = interfaceType;
        this.mLinkProperties = new LinkProperties();
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
    public void stopIPv4() {
        configureIPv4(false);
        this.mInterfaceCtrl.clearIPv4Address();
    }

    private boolean configureIPv4(boolean enabled) {
        String ipAsString;
        int prefixLen;
        if (this.mInterfaceType == 1) {
            ipAsString = USB_NEAR_IFACE_ADDR;
            prefixLen = 24;
        } else if (this.mInterfaceType != 0) {
            return true;
        } else {
            ipAsString = getRandomWifiIPv4Address();
            prefixLen = 24;
        }
        try {
            InterfaceConfiguration ifcg = this.mNMService.getInterfaceConfig(this.mIfaceName);
            if (ifcg == null) {
                this.mLog.e("Received null interface config");
                return false;
            }
            InetAddress addr = NetworkUtils.numericToInetAddress(ipAsString);
            LinkAddress linkAddr = new LinkAddress(addr, prefixLen);
            ifcg.setLinkAddress(linkAddr);
            if (this.mInterfaceType == 0) {
                ifcg.ignoreInterfaceUpDownStatus();
            } else if (enabled) {
                ifcg.setInterfaceUp();
            } else {
                ifcg.setInterfaceDown();
            }
            ifcg.clearFlag("running");
            this.mNMService.setInterfaceConfig(this.mIfaceName, ifcg);
            RouteInfo route = new RouteInfo(linkAddr);
            if (enabled) {
                this.mLinkProperties.addLinkAddress(linkAddr);
                this.mLinkProperties.addRoute(route);
            } else {
                this.mLinkProperties.removeLinkAddress(linkAddr);
                this.mLinkProperties.removeRoute(route);
            }
            return true;
        } catch (Exception e) {
            SharedLog sharedLog = this.mLog;
            sharedLog.e("Error configuring interface " + e);
            return false;
        }
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
        if (this.mInterfaceParams == null) {
            this.mLog.e("Failed to find InterfaceParams");
            stopIPv6();
            return false;
        }
        this.mRaDaemon = this.mDeps.getRouterAdvertisementDaemon(this.mInterfaceParams);
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
        if (this.mRaDaemon != null) {
            this.mRaDaemon.stop();
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
            params.hasDefaultRoute = v6only.hasIPv6DefaultRoute();
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
            if (this.mLastRaParams != null) {
                addedPrefixes.removeAll(this.mLastRaParams.prefixes);
            }
            if (this.mLastRaParams == null || this.mLastRaParams.prefixes.isEmpty()) {
                addedPrefixes.add(LINK_LOCAL_PREFIX);
            }
            if (!addedPrefixes.isEmpty()) {
                ArrayList<RouteInfo> toBeAdded = getLocalRoutesFor(this.mIfaceName, addedPrefixes);
                try {
                    this.mNMService.addInterfaceToLocalNetwork(this.mIfaceName, toBeAdded);
                } catch (RemoteException e2) {
                    SharedLog sharedLog2 = this.mLog;
                    sharedLog2.e("Failed to add IPv6 routes to local table: " + e2);
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
            if (this.mLastRaParams != null) {
                addedDnses.removeAll(this.mLastRaParams.dnses);
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
        this.mTetherController.updateInterfaceState(this, newInterfaceState, this.mLastError);
        sendLinkProperties();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendLinkProperties() {
        this.mTetherController.updateLinkProperties(this, new LinkProperties(this.mLinkProperties));
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
            TetherInterfaceStateMachine.this.sendInterfaceState(1);
        }

        public boolean processMessage(Message message) {
            TetherInterfaceStateMachine.this.logMessage(this, message.what);
            int i = message.what;
            if (i != 327782) {
                if (i == 327784) {
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mUnavailableState);
                    return true;
                } else if (i != 327793) {
                    return false;
                } else {
                    TetherInterfaceStateMachine.this.updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    return true;
                }
            }
            TetherInterfaceStateMachine.this.mLastError = 0;
            switch (message.arg1) {
                case 2:
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mTetheredState);
                    return true;
                case 3:
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mLocalHotspotState);
                    return true;
                default:
                    TetherInterfaceStateMachine.this.mLog.e("Invalid tethering interface serving state specified.");
                    return true;
            }
        }
    }

    /* loaded from: classes.dex */
    class BaseServingState extends State {
        BaseServingState() {
        }

        public void enter() {
            if (!TetherInterfaceStateMachine.this.startIPv4()) {
                TetherInterfaceStateMachine.this.mLastError = 10;
                return;
            }
            try {
                TetherInterfaceStateMachine.this.mNMService.tetherInterface(TetherInterfaceStateMachine.this.mIfaceName);
                if (!TetherInterfaceStateMachine.this.startIPv6()) {
                    TetherInterfaceStateMachine.this.mLog.e("Failed to startIPv6");
                }
            } catch (Exception e) {
                SharedLog sharedLog = TetherInterfaceStateMachine.this.mLog;
                sharedLog.e("Error Tethering: " + e);
                TetherInterfaceStateMachine.this.mLastError = 6;
            }
        }

        public void exit() {
            TetherInterfaceStateMachine.this.stopIPv6();
            try {
                TetherInterfaceStateMachine.this.mNMService.untetherInterface(TetherInterfaceStateMachine.this.mIfaceName);
            } catch (Exception e) {
                TetherInterfaceStateMachine.this.mLastError = 7;
                SharedLog sharedLog = TetherInterfaceStateMachine.this.mLog;
                sharedLog.e("Failed to untether interface: " + e);
            }
            TetherInterfaceStateMachine.this.stopIPv4();
            TetherInterfaceStateMachine.this.resetLinkProperties();
        }

        public boolean processMessage(Message message) {
            TetherInterfaceStateMachine.this.logMessage(this, message.what);
            switch (message.what) {
                case TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED /* 327783 */:
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mInitialState);
                    return true;
                case TetherInterfaceStateMachine.CMD_INTERFACE_DOWN /* 327784 */:
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mUnavailableState);
                    return true;
                case 327785:
                case 327786:
                case TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED /* 327792 */:
                default:
                    return false;
                case TetherInterfaceStateMachine.CMD_IP_FORWARDING_ENABLE_ERROR /* 327787 */:
                case TetherInterfaceStateMachine.CMD_IP_FORWARDING_DISABLE_ERROR /* 327788 */:
                case TetherInterfaceStateMachine.CMD_START_TETHERING_ERROR /* 327789 */:
                case TetherInterfaceStateMachine.CMD_STOP_TETHERING_ERROR /* 327790 */:
                case TetherInterfaceStateMachine.CMD_SET_DNS_FORWARDERS_ERROR /* 327791 */:
                    TetherInterfaceStateMachine.this.mLastError = 5;
                    TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mInitialState);
                    return true;
                case TetherInterfaceStateMachine.CMD_IPV6_TETHER_UPDATE /* 327793 */:
                    TetherInterfaceStateMachine.this.updateUpstreamIPv6LinkProperties((LinkProperties) message.obj);
                    TetherInterfaceStateMachine.this.sendLinkProperties();
                    return true;
            }
        }
    }

    /* loaded from: classes.dex */
    class LocalHotspotState extends BaseServingState {
        LocalHotspotState() {
            super();
        }

        @Override // com.android.server.connectivity.tethering.TetherInterfaceStateMachine.BaseServingState
        public void enter() {
            super.enter();
            if (TetherInterfaceStateMachine.this.mLastError != 0) {
                TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mInitialState);
            }
            TetherInterfaceStateMachine.this.sendInterfaceState(3);
        }

        @Override // com.android.server.connectivity.tethering.TetherInterfaceStateMachine.BaseServingState
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) {
                return true;
            }
            TetherInterfaceStateMachine.this.logMessage(this, message.what);
            int i = message.what;
            if (i == 327782) {
                TetherInterfaceStateMachine.this.mLog.e("CMD_TETHER_REQUESTED while in local-only hotspot mode.");
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

        @Override // com.android.server.connectivity.tethering.TetherInterfaceStateMachine.BaseServingState
        public void enter() {
            super.enter();
            if (TetherInterfaceStateMachine.this.mLastError != 0) {
                TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mInitialState);
            }
            TetherInterfaceStateMachine.this.sendInterfaceState(2);
        }

        @Override // com.android.server.connectivity.tethering.TetherInterfaceStateMachine.BaseServingState
        public void exit() {
            cleanupUpstream();
            super.exit();
        }

        private void cleanupUpstream() {
            if (TetherInterfaceStateMachine.this.mUpstreamIfaceSet == null) {
                return;
            }
            for (String ifname : TetherInterfaceStateMachine.this.mUpstreamIfaceSet.ifnames) {
                cleanupUpstreamInterface(ifname);
            }
            TetherInterfaceStateMachine.this.mUpstreamIfaceSet = null;
        }

        private void cleanupUpstreamInterface(String upstreamIface) {
            try {
                TetherInterfaceStateMachine.this.mStatsService.forceUpdate();
            } catch (Exception e) {
            }
            try {
                TetherInterfaceStateMachine.this.mNMService.stopInterfaceForwarding(TetherInterfaceStateMachine.this.mIfaceName, upstreamIface);
            } catch (Exception e2) {
            }
            try {
                TetherInterfaceStateMachine.this.mNMService.disableNat(TetherInterfaceStateMachine.this.mIfaceName, upstreamIface);
            } catch (Exception e3) {
            }
        }

        @Override // com.android.server.connectivity.tethering.TetherInterfaceStateMachine.BaseServingState
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) {
                return true;
            }
            TetherInterfaceStateMachine.this.logMessage(this, message.what);
            int i = message.what;
            if (i == 327782) {
                TetherInterfaceStateMachine.this.mLog.e("CMD_TETHER_REQUESTED while already tethering.");
            } else if (i == 327792) {
                InterfaceSet newUpstreamIfaceSet = (InterfaceSet) message.obj;
                if (!noChangeInUpstreamIfaceSet(newUpstreamIfaceSet)) {
                    if (newUpstreamIfaceSet == null) {
                        cleanupUpstream();
                    } else {
                        for (String removed : upstreamInterfacesRemoved(newUpstreamIfaceSet)) {
                            cleanupUpstreamInterface(removed);
                        }
                        Set<String> added = upstreamInterfacesAdd(newUpstreamIfaceSet);
                        TetherInterfaceStateMachine.this.mUpstreamIfaceSet = newUpstreamIfaceSet;
                        for (String ifname : added) {
                            try {
                                TetherInterfaceStateMachine.this.mNMService.enableNat(TetherInterfaceStateMachine.this.mIfaceName, ifname);
                                TetherInterfaceStateMachine.this.mNMService.startInterfaceForwarding(TetherInterfaceStateMachine.this.mIfaceName, ifname);
                            } catch (Exception e) {
                                SharedLog sharedLog = TetherInterfaceStateMachine.this.mLog;
                                sharedLog.e("Exception enabling NAT: " + e);
                                cleanupUpstream();
                                TetherInterfaceStateMachine.this.mLastError = 8;
                                TetherInterfaceStateMachine.this.transitionTo(TetherInterfaceStateMachine.this.mInitialState);
                                return true;
                            }
                        }
                    }
                }
            } else {
                return false;
            }
            return true;
        }

        private boolean noChangeInUpstreamIfaceSet(InterfaceSet newIfaces) {
            if (TetherInterfaceStateMachine.this.mUpstreamIfaceSet == null && newIfaces == null) {
                return true;
            }
            if (TetherInterfaceStateMachine.this.mUpstreamIfaceSet != null && newIfaces != null) {
                return TetherInterfaceStateMachine.this.mUpstreamIfaceSet.equals(newIfaces);
            }
            return false;
        }

        private Set<String> upstreamInterfacesRemoved(InterfaceSet newIfaces) {
            if (TetherInterfaceStateMachine.this.mUpstreamIfaceSet == null) {
                return new HashSet();
            }
            HashSet<String> removed = new HashSet<>(TetherInterfaceStateMachine.this.mUpstreamIfaceSet.ifnames);
            removed.removeAll(newIfaces.ifnames);
            return removed;
        }

        private Set<String> upstreamInterfacesAdd(InterfaceSet newIfaces) {
            HashSet<String> added = new HashSet<>(newIfaces.ifnames);
            if (TetherInterfaceStateMachine.this.mUpstreamIfaceSet != null) {
                added.removeAll(TetherInterfaceStateMachine.this.mUpstreamIfaceSet.ifnames);
            }
            return added;
        }
    }

    /* loaded from: classes.dex */
    class UnavailableState extends State {
        UnavailableState() {
        }

        public void enter() {
            TetherInterfaceStateMachine.this.mLastError = 0;
            TetherInterfaceStateMachine.this.sendInterfaceState(0);
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
