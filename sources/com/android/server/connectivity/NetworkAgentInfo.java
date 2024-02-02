package com.android.server.connectivity;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.WakeupMessage;
import com.android.server.ConnectivityService;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
/* loaded from: classes.dex */
public class NetworkAgentInfo implements Comparable<NetworkAgentInfo> {
    private static final boolean ADD = true;
    public static final int EVENT_NETWORK_LINGER_COMPLETE = 1001;
    private static final boolean REMOVE = false;
    private static final String TAG = ConnectivityService.class.getSimpleName();
    private static final boolean VDBG = false;
    public final AsyncChannel asyncChannel;
    public boolean avoidUnvalidated;
    public Nat464Xlat clatd;
    public boolean created;
    private int currentScore;
    public boolean everCaptivePortalDetected;
    public boolean everConnected;
    public boolean everValidated;
    public boolean lastCaptivePortalDetected;
    public boolean lastValidated;
    public LinkProperties linkProperties;
    private final ConnectivityService mConnService;
    private final Context mContext;
    private final Handler mHandler;
    private long mLingerExpiryMs;
    private WakeupMessage mLingerMessage;
    private boolean mLingering;
    public final Messenger messenger;
    public final Network network;
    public NetworkCapabilities networkCapabilities;
    public NetworkInfo networkInfo;
    public final NetworkMisc networkMisc;
    public final NetworkMonitor networkMonitor;
    private final SortedSet<LingerTimer> mLingerTimers = new TreeSet();
    private final SparseArray<LingerTimer> mLingerTimerForRequest = new SparseArray<>();
    private final SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();
    private int mNumRequestNetworkRequests = 0;
    private int mNumBackgroundNetworkRequests = 0;

    /* loaded from: classes.dex */
    public static class LingerTimer implements Comparable<LingerTimer> {
        public final long expiryMs;
        public final NetworkRequest request;

        public LingerTimer(NetworkRequest request, long expiryMs) {
            this.request = request;
            this.expiryMs = expiryMs;
        }

        public boolean equals(Object o) {
            if (o instanceof LingerTimer) {
                LingerTimer other = (LingerTimer) o;
                return this.request.requestId == other.request.requestId && this.expiryMs == other.expiryMs;
            }
            return false;
        }

        public int hashCode() {
            return Objects.hash(Integer.valueOf(this.request.requestId), Long.valueOf(this.expiryMs));
        }

        @Override // java.lang.Comparable
        public int compareTo(LingerTimer other) {
            if (this.expiryMs != other.expiryMs) {
                return Long.compare(this.expiryMs, other.expiryMs);
            }
            return Integer.compare(this.request.requestId, other.request.requestId);
        }

        public String toString() {
            return String.format("%s, expires %dms", this.request.toString(), Long.valueOf(this.expiryMs - SystemClock.elapsedRealtime()));
        }
    }

    public NetworkAgentInfo(Messenger messenger, AsyncChannel ac, Network net, NetworkInfo info, LinkProperties lp, NetworkCapabilities nc, int score, Context context, Handler handler, NetworkMisc misc, NetworkRequest defaultRequest, ConnectivityService connService) {
        this.messenger = messenger;
        this.asyncChannel = ac;
        this.network = net;
        this.networkInfo = info;
        this.linkProperties = lp;
        this.networkCapabilities = nc;
        this.currentScore = score;
        this.mConnService = connService;
        this.mContext = context;
        this.mHandler = handler;
        this.networkMonitor = this.mConnService.createNetworkMonitor(context, handler, this, defaultRequest);
        this.networkMisc = misc;
    }

    public ConnectivityService connService() {
        return this.mConnService;
    }

    public Handler handler() {
        return this.mHandler;
    }

    public Network network() {
        return this.network;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.connectivity.NetworkAgentInfo$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$android$net$NetworkRequest$Type = new int[NetworkRequest.Type.values().length];

        static {
            try {
                $SwitchMap$android$net$NetworkRequest$Type[NetworkRequest.Type.REQUEST.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$android$net$NetworkRequest$Type[NetworkRequest.Type.BACKGROUND_REQUEST.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$android$net$NetworkRequest$Type[NetworkRequest.Type.TRACK_DEFAULT.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
            try {
                $SwitchMap$android$net$NetworkRequest$Type[NetworkRequest.Type.LISTEN.ordinal()] = 4;
            } catch (NoSuchFieldError e4) {
            }
            try {
                $SwitchMap$android$net$NetworkRequest$Type[NetworkRequest.Type.NONE.ordinal()] = 5;
            } catch (NoSuchFieldError e5) {
            }
        }
    }

    private void updateRequestCounts(boolean add, NetworkRequest request) {
        int delta = add ? 1 : -1;
        switch (AnonymousClass1.$SwitchMap$android$net$NetworkRequest$Type[request.type.ordinal()]) {
            case 1:
                this.mNumRequestNetworkRequests += delta;
                return;
            case 2:
                this.mNumRequestNetworkRequests += delta;
                this.mNumBackgroundNetworkRequests += delta;
                return;
            case 3:
            case 4:
                return;
            default:
                Log.wtf(TAG, "Unhandled request type " + request.type);
                return;
        }
    }

    public boolean addRequest(NetworkRequest networkRequest) {
        NetworkRequest existing = this.mNetworkRequests.get(networkRequest.requestId);
        if (existing == networkRequest) {
            return false;
        }
        if (existing != null) {
            Log.wtf(TAG, String.format("Duplicate requestId for %s and %s on %s", networkRequest, existing, name()));
            updateRequestCounts(false, existing);
        }
        this.mNetworkRequests.put(networkRequest.requestId, networkRequest);
        updateRequestCounts(true, networkRequest);
        return true;
    }

    public void removeRequest(int requestId) {
        NetworkRequest existing = this.mNetworkRequests.get(requestId);
        if (existing == null) {
            return;
        }
        updateRequestCounts(false, existing);
        this.mNetworkRequests.remove(requestId);
        if (existing.isRequest()) {
            unlingerRequest(existing);
        }
    }

    public boolean isSatisfyingRequest(int id) {
        return this.mNetworkRequests.get(id) != null;
    }

    public NetworkRequest requestAt(int index) {
        return this.mNetworkRequests.valueAt(index);
    }

    public int numRequestNetworkRequests() {
        return this.mNumRequestNetworkRequests;
    }

    public int numBackgroundNetworkRequests() {
        return this.mNumBackgroundNetworkRequests;
    }

    public int numForegroundNetworkRequests() {
        return this.mNumRequestNetworkRequests - this.mNumBackgroundNetworkRequests;
    }

    public int numNetworkRequests() {
        return this.mNetworkRequests.size();
    }

    public boolean isBackgroundNetwork() {
        return !isVPN() && numForegroundNetworkRequests() == 0 && this.mNumBackgroundNetworkRequests > 0 && !isLingering();
    }

    public boolean isSuspended() {
        return this.networkInfo.getState() == NetworkInfo.State.SUSPENDED;
    }

    public boolean satisfies(NetworkRequest request) {
        return this.created && request.networkCapabilities.satisfiedByNetworkCapabilities(this.networkCapabilities);
    }

    public boolean satisfiesImmutableCapabilitiesOf(NetworkRequest request) {
        return this.created && request.networkCapabilities.satisfiedByImmutableNetworkCapabilities(this.networkCapabilities);
    }

    public boolean isVPN() {
        return this.networkCapabilities.hasTransport(4);
    }

    private int getCurrentScore(boolean pretendValidated) {
        if (this.networkMisc.explicitlySelected && (this.networkMisc.acceptUnvalidated || pretendValidated)) {
            return 100;
        }
        int score = this.currentScore;
        if (!this.lastValidated && !pretendValidated && !ignoreWifiUnvalidationPenalty()) {
            score -= 40;
        }
        if (score < 0) {
            return 0;
        }
        return score;
    }

    private boolean ignoreWifiUnvalidationPenalty() {
        boolean isWifi = this.networkCapabilities.hasTransport(1) && this.networkCapabilities.hasCapability(12);
        boolean avoidBadWifi = this.mConnService.avoidBadWifi() || this.avoidUnvalidated;
        return isWifi && !avoidBadWifi && this.everValidated;
    }

    public int getCurrentScore() {
        return getCurrentScore(false);
    }

    public int getCurrentScoreAsValidated() {
        return getCurrentScore(true);
    }

    public void setCurrentScore(int newScore) {
        this.currentScore = newScore;
    }

    public NetworkState getNetworkState() {
        NetworkState networkState;
        synchronized (this) {
            String subscriberId = this.networkMisc != null ? this.networkMisc.subscriberId : null;
            networkState = new NetworkState(new NetworkInfo(this.networkInfo), new LinkProperties(this.linkProperties), new NetworkCapabilities(this.networkCapabilities), this.network, subscriberId, (String) null);
        }
        return networkState;
    }

    public void lingerRequest(NetworkRequest request, long now, long duration) {
        if (this.mLingerTimerForRequest.get(request.requestId) != null) {
            String str = TAG;
            Log.wtf(str, name() + ": request " + request.requestId + " already lingered");
        }
        long expiryMs = now + duration;
        LingerTimer timer = new LingerTimer(request, expiryMs);
        this.mLingerTimers.add(timer);
        this.mLingerTimerForRequest.put(request.requestId, timer);
    }

    public boolean unlingerRequest(NetworkRequest request) {
        LingerTimer timer = this.mLingerTimerForRequest.get(request.requestId);
        if (timer != null) {
            this.mLingerTimers.remove(timer);
            this.mLingerTimerForRequest.remove(request.requestId);
            return true;
        }
        return false;
    }

    public long getLingerExpiry() {
        return this.mLingerExpiryMs;
    }

    public void updateLingerTimer() {
        long newExpiry = this.mLingerTimers.isEmpty() ? 0L : this.mLingerTimers.last().expiryMs;
        if (newExpiry == this.mLingerExpiryMs) {
            return;
        }
        if (this.mLingerMessage != null) {
            this.mLingerMessage.cancel();
            this.mLingerMessage = null;
        }
        if (newExpiry > 0) {
            ConnectivityService connectivityService = this.mConnService;
            Context context = this.mContext;
            Handler handler = this.mHandler;
            this.mLingerMessage = connectivityService.makeWakeupMessage(context, handler, "NETWORK_LINGER_COMPLETE." + this.network.netId, EVENT_NETWORK_LINGER_COMPLETE, this);
            this.mLingerMessage.schedule(newExpiry);
        }
        this.mLingerExpiryMs = newExpiry;
    }

    public void linger() {
        this.mLingering = true;
    }

    public void unlinger() {
        this.mLingering = false;
    }

    public boolean isLingering() {
        return this.mLingering;
    }

    public void clearLingerState() {
        if (this.mLingerMessage != null) {
            this.mLingerMessage.cancel();
            this.mLingerMessage = null;
        }
        this.mLingerTimers.clear();
        this.mLingerTimerForRequest.clear();
        updateLingerTimer();
        this.mLingering = false;
    }

    public void dumpLingerTimers(PrintWriter pw) {
        for (LingerTimer timer : this.mLingerTimers) {
            pw.println(timer);
        }
    }

    public void updateClat(INetworkManagementService netd) {
        if (Nat464Xlat.requiresClat(this)) {
            maybeStartClat(netd);
        } else {
            maybeStopClat();
        }
    }

    public void maybeStartClat(INetworkManagementService netd) {
        if (this.clatd != null && this.clatd.isStarted()) {
            return;
        }
        this.clatd = new Nat464Xlat(netd, this);
        this.clatd.start();
    }

    public void maybeStopClat() {
        if (this.clatd == null) {
            return;
        }
        this.clatd.stop();
        this.clatd = null;
    }

    public String toString() {
        return "NetworkAgentInfo{ ni{" + this.networkInfo + "}  network{" + this.network + "}  nethandle{" + this.network.getNetworkHandle() + "}  lp{" + this.linkProperties + "}  nc{" + this.networkCapabilities + "}  Score{" + getCurrentScore() + "}  everValidated{" + this.everValidated + "}  lastValidated{" + this.lastValidated + "}  created{" + this.created + "} lingering{" + isLingering() + "} explicitlySelected{" + this.networkMisc.explicitlySelected + "} acceptUnvalidated{" + this.networkMisc.acceptUnvalidated + "} everCaptivePortalDetected{" + this.everCaptivePortalDetected + "} lastCaptivePortalDetected{" + this.lastCaptivePortalDetected + "} clat{" + this.clatd + "} }";
    }

    public String name() {
        return "NetworkAgentInfo [" + this.networkInfo.getTypeName() + " (" + this.networkInfo.getSubtypeName() + ") - " + Objects.toString(this.network) + "]";
    }

    @Override // java.lang.Comparable
    public int compareTo(NetworkAgentInfo other) {
        return other.getCurrentScore() - getCurrentScore();
    }
}
