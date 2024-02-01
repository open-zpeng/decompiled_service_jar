package com.android.server.connectivity.tethering;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;
import com.android.server.UiModeManagerService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/* loaded from: classes.dex */
public class UpstreamNetworkMonitor {
    private static final int CALLBACK_DEFAULT_INTERNET = 2;
    private static final int CALLBACK_LISTEN_ALL = 1;
    private static final int CALLBACK_MOBILE_REQUEST = 3;
    private static final boolean DBG = false;
    public static final int EVENT_ON_CAPABILITIES = 1;
    public static final int EVENT_ON_LINKPROPERTIES = 2;
    public static final int EVENT_ON_LOST = 3;
    public static final int NOTIFY_LOCAL_PREFIXES = 10;
    private static final String TAG = UpstreamNetworkMonitor.class.getSimpleName();
    private static final boolean VDBG = false;
    private ConnectivityManager mCM;
    private final Context mContext;
    private Network mDefaultInternetNetwork;
    private ConnectivityManager.NetworkCallback mDefaultNetworkCallback;
    private boolean mDunRequired;
    private EntitlementManager mEntitlementMgr;
    private final Handler mHandler;
    private boolean mIsDefaultCellularUpstream;
    private ConnectivityManager.NetworkCallback mListenAllCallback;
    private HashSet<IpPrefix> mLocalPrefixes;
    private final SharedLog mLog;
    private ConnectivityManager.NetworkCallback mMobileNetworkCallback;
    private final HashMap<Network, NetworkState> mNetworkMap;
    private final StateMachine mTarget;
    private Network mTetheringUpstreamNetwork;
    private final int mWhat;

    public UpstreamNetworkMonitor(Context ctx, StateMachine tgt, SharedLog log, int what) {
        this.mNetworkMap = new HashMap<>();
        this.mContext = ctx;
        this.mTarget = tgt;
        this.mHandler = this.mTarget.getHandler();
        this.mLog = log.forSubComponent(TAG);
        this.mWhat = what;
        this.mLocalPrefixes = new HashSet<>();
        this.mIsDefaultCellularUpstream = false;
    }

    @VisibleForTesting
    public UpstreamNetworkMonitor(ConnectivityManager cm, StateMachine tgt, SharedLog log, int what) {
        this((Context) null, tgt, log, what);
        this.mCM = cm;
    }

    public void startTrackDefaultNetwork(NetworkRequest defaultNetworkRequest, EntitlementManager entitle) {
        if (this.mDefaultNetworkCallback == null) {
            NetworkRequest trackDefaultRequest = new NetworkRequest(defaultNetworkRequest);
            this.mDefaultNetworkCallback = new UpstreamNetworkCallback(2);
            cm().requestNetwork(trackDefaultRequest, this.mDefaultNetworkCallback, this.mHandler);
        }
        if (this.mEntitlementMgr == null) {
            this.mEntitlementMgr = entitle;
        }
    }

    public void startObserveAllNetworks() {
        stop();
        NetworkRequest listenAllRequest = new NetworkRequest.Builder().clearCapabilities().build();
        this.mListenAllCallback = new UpstreamNetworkCallback(1);
        cm().registerNetworkCallback(listenAllRequest, this.mListenAllCallback, this.mHandler);
    }

    public void stop() {
        releaseMobileNetworkRequest();
        releaseCallback(this.mListenAllCallback);
        this.mListenAllCallback = null;
        this.mTetheringUpstreamNetwork = null;
        this.mNetworkMap.clear();
    }

    public void updateMobileRequiresDun(boolean dunRequired) {
        boolean valueChanged = this.mDunRequired != dunRequired;
        this.mDunRequired = dunRequired;
        if (valueChanged && mobileNetworkRequested()) {
            releaseMobileNetworkRequest();
            registerMobileNetworkRequest();
        }
    }

    public boolean mobileNetworkRequested() {
        return this.mMobileNetworkCallback != null;
    }

    public void registerMobileNetworkRequest() {
        if (!isCellularUpstreamPermitted()) {
            this.mLog.i("registerMobileNetworkRequest() is not permitted");
            releaseMobileNetworkRequest();
        } else if (this.mMobileNetworkCallback != null) {
            this.mLog.e("registerMobileNetworkRequest() already registered");
        } else {
            int legacyType = this.mDunRequired ? 4 : 5;
            NetworkRequest mobileUpstreamRequest = new NetworkRequest.Builder().setCapabilities(ConnectivityManager.networkCapabilitiesForType(legacyType)).build();
            this.mMobileNetworkCallback = new UpstreamNetworkCallback(3);
            SharedLog sharedLog = this.mLog;
            sharedLog.i("requesting mobile upstream network: " + mobileUpstreamRequest);
            cm().requestNetwork(mobileUpstreamRequest, this.mMobileNetworkCallback, 0, legacyType, this.mHandler);
        }
    }

    public void releaseMobileNetworkRequest() {
        if (this.mMobileNetworkCallback == null) {
            return;
        }
        cm().unregisterNetworkCallback(this.mMobileNetworkCallback);
        this.mMobileNetworkCallback = null;
    }

    public NetworkState selectPreferredUpstreamType(Iterable<Integer> preferredTypes) {
        TypeStatePair typeStatePair = findFirstAvailableUpstreamByType(this.mNetworkMap.values(), preferredTypes, isCellularUpstreamPermitted());
        SharedLog sharedLog = this.mLog;
        sharedLog.log("preferred upstream type: " + ConnectivityManager.getNetworkTypeName(typeStatePair.type));
        int i = typeStatePair.type;
        if (i != -1) {
            if (i == 4 || i == 5) {
                if (!this.mIsDefaultCellularUpstream) {
                    this.mEntitlementMgr.maybeRunProvisioning();
                }
                registerMobileNetworkRequest();
            } else {
                releaseMobileNetworkRequest();
            }
        } else if (!isCellularUpstreamPermitted()) {
            releaseMobileNetworkRequest();
        }
        return typeStatePair.ns;
    }

    public NetworkState getCurrentPreferredUpstream() {
        NetworkState dfltState;
        Network network = this.mDefaultInternetNetwork;
        if (network != null) {
            dfltState = this.mNetworkMap.get(network);
        } else {
            dfltState = null;
        }
        if (isNetworkUsableAndNotCellular(dfltState)) {
            return dfltState;
        }
        if (isCellularUpstreamPermitted()) {
            return !this.mDunRequired ? dfltState : findFirstDunNetwork(this.mNetworkMap.values());
        }
        return null;
    }

    public void setCurrentUpstream(Network upstream) {
        this.mTetheringUpstreamNetwork = upstream;
    }

    public Set<IpPrefix> getLocalPrefixes() {
        return (Set) this.mLocalPrefixes.clone();
    }

    private boolean isCellularUpstreamPermitted() {
        EntitlementManager entitlementManager = this.mEntitlementMgr;
        if (entitlementManager != null) {
            return entitlementManager.isCellularUpstreamPermitted();
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAvailable(Network network) {
        if (this.mNetworkMap.containsKey(network)) {
            return;
        }
        this.mNetworkMap.put(network, new NetworkState((NetworkInfo) null, (LinkProperties) null, (NetworkCapabilities) null, network, (String) null, (String) null));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNetCap(Network network, NetworkCapabilities newNc) {
        NetworkState prev = this.mNetworkMap.get(network);
        if (prev == null || newNc.equals(prev.networkCapabilities)) {
            return;
        }
        if (network.equals(this.mTetheringUpstreamNetwork) && newNc.hasSignalStrength()) {
            int newSignal = newNc.getSignalStrength();
            String prevSignal = getSignalStrength(prev.networkCapabilities);
            this.mLog.logf("upstream network signal strength: %s -> %s", prevSignal, Integer.valueOf(newSignal));
        }
        this.mNetworkMap.put(network, new NetworkState((NetworkInfo) null, prev.linkProperties, newNc, network, (String) null, (String) null));
        notifyTarget(1, network);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLinkProp(Network network, LinkProperties newLp) {
        NetworkState prev = this.mNetworkMap.get(network);
        if (prev == null || newLp.equals(prev.linkProperties)) {
            return;
        }
        this.mNetworkMap.put(network, new NetworkState((NetworkInfo) null, newLp, prev.networkCapabilities, network, (String) null, (String) null));
        notifyTarget(2, network);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSuspended(Network network) {
        if (network.equals(this.mTetheringUpstreamNetwork)) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log("SUSPENDED current upstream: " + network);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleResumed(Network network) {
        if (network.equals(this.mTetheringUpstreamNetwork)) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log("RESUMED current upstream: " + network);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLost(Network network) {
        if (!this.mNetworkMap.containsKey(network)) {
            return;
        }
        notifyTarget(3, this.mNetworkMap.remove(network));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void recomputeLocalPrefixes() {
        HashSet<IpPrefix> localPrefixes = allLocalPrefixes(this.mNetworkMap.values());
        if (!this.mLocalPrefixes.equals(localPrefixes)) {
            this.mLocalPrefixes = localPrefixes;
            notifyTarget(10, localPrefixes.clone());
        }
    }

    private ConnectivityManager cm() {
        if (this.mCM == null) {
            this.mCM = (ConnectivityManager) this.mContext.getSystemService("connectivity");
        }
        return this.mCM;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UpstreamNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final int mCallbackType;

        UpstreamNetworkCallback(int callbackType) {
            this.mCallbackType = callbackType;
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            UpstreamNetworkMonitor.this.handleAvailable(network);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onCapabilitiesChanged(Network network, NetworkCapabilities newNc) {
            if (this.mCallbackType == 2) {
                UpstreamNetworkMonitor.this.mDefaultInternetNetwork = network;
                boolean newIsCellular = UpstreamNetworkMonitor.isCellular(newNc);
                if (UpstreamNetworkMonitor.this.mIsDefaultCellularUpstream != newIsCellular) {
                    UpstreamNetworkMonitor.this.mIsDefaultCellularUpstream = newIsCellular;
                    UpstreamNetworkMonitor.this.mEntitlementMgr.notifyUpstream(newIsCellular);
                    return;
                }
                return;
            }
            UpstreamNetworkMonitor.this.handleNetCap(network, newNc);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            if (this.mCallbackType == 2) {
                return;
            }
            UpstreamNetworkMonitor.this.handleLinkProp(network, newLp);
            if (this.mCallbackType == 1) {
                UpstreamNetworkMonitor.this.recomputeLocalPrefixes();
            }
        }

        public void onNetworkSuspended(Network network) {
            if (this.mCallbackType == 1) {
                UpstreamNetworkMonitor.this.handleSuspended(network);
            }
        }

        public void onNetworkResumed(Network network) {
            if (this.mCallbackType == 1) {
                UpstreamNetworkMonitor.this.handleResumed(network);
            }
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            if (this.mCallbackType == 2) {
                UpstreamNetworkMonitor.this.mDefaultInternetNetwork = null;
                UpstreamNetworkMonitor.this.mIsDefaultCellularUpstream = false;
                UpstreamNetworkMonitor.this.mEntitlementMgr.notifyUpstream(false);
                return;
            }
            UpstreamNetworkMonitor.this.handleLost(network);
            if (this.mCallbackType == 1) {
                UpstreamNetworkMonitor.this.recomputeLocalPrefixes();
            }
        }
    }

    private void releaseCallback(ConnectivityManager.NetworkCallback cb) {
        if (cb != null) {
            cm().unregisterNetworkCallback(cb);
        }
    }

    private void notifyTarget(int which, Network network) {
        notifyTarget(which, this.mNetworkMap.get(network));
    }

    private void notifyTarget(int which, Object obj) {
        this.mTarget.sendMessage(this.mWhat, which, 0, obj);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class TypeStatePair {
        public NetworkState ns;
        public int type;

        private TypeStatePair() {
            this.type = -1;
            this.ns = null;
        }
    }

    private static TypeStatePair findFirstAvailableUpstreamByType(Iterable<NetworkState> netStates, Iterable<Integer> preferredTypes, boolean isCellularUpstreamPermitted) {
        TypeStatePair result = new TypeStatePair();
        for (Integer num : preferredTypes) {
            int type = num.intValue();
            try {
                NetworkCapabilities nc = ConnectivityManager.networkCapabilitiesForType(type);
                if (isCellularUpstreamPermitted || !isCellular(nc)) {
                    nc.setSingleUid(Process.myUid());
                    for (NetworkState value : netStates) {
                        if (nc.satisfiedByNetworkCapabilities(value.networkCapabilities)) {
                            result.type = type;
                            result.ns = value;
                            return result;
                        }
                    }
                    continue;
                }
            } catch (IllegalArgumentException e) {
                String str = TAG;
                Log.e(str, "No NetworkCapabilities mapping for legacy type: " + ConnectivityManager.getNetworkTypeName(type));
            }
        }
        return result;
    }

    private static HashSet<IpPrefix> allLocalPrefixes(Iterable<NetworkState> netStates) {
        HashSet<IpPrefix> prefixSet = new HashSet<>();
        for (NetworkState ns : netStates) {
            LinkProperties lp = ns.linkProperties;
            if (lp != null) {
                prefixSet.addAll(PrefixUtils.localPrefixesFrom(lp));
            }
        }
        return prefixSet;
    }

    private static String getSignalStrength(NetworkCapabilities nc) {
        if (nc == null || !nc.hasSignalStrength()) {
            return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
        }
        return Integer.toString(nc.getSignalStrength());
    }

    private static boolean isCellular(NetworkState ns) {
        return ns != null && isCellular(ns.networkCapabilities);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isCellular(NetworkCapabilities nc) {
        return nc != null && nc.hasTransport(0) && nc.hasCapability(15);
    }

    private static boolean hasCapability(NetworkState ns, int netCap) {
        return (ns == null || ns.networkCapabilities == null || !ns.networkCapabilities.hasCapability(netCap)) ? false : true;
    }

    private static boolean isNetworkUsableAndNotCellular(NetworkState ns) {
        return (ns == null || ns.networkCapabilities == null || ns.linkProperties == null || isCellular(ns.networkCapabilities)) ? false : true;
    }

    private static NetworkState findFirstDunNetwork(Iterable<NetworkState> netStates) {
        for (NetworkState ns : netStates) {
            if (isCellular(ns) && hasCapability(ns, 2)) {
                return ns;
            }
        }
        return null;
    }
}
