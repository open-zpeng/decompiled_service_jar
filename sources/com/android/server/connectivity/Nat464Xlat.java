package com.android.server.connectivity;

import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.util.ArrayUtils;
import com.android.server.net.BaseNetworkObserver;
import java.net.Inet4Address;
import java.util.Objects;
/* loaded from: classes.dex */
public class Nat464Xlat extends BaseNetworkObserver {
    private static final String CLAT_PREFIX = "v4-";
    private String mBaseIface;
    private String mIface;
    private final INetworkManagementService mNMService;
    private final NetworkAgentInfo mNetwork;
    private State mState = State.IDLE;
    private static final String TAG = Nat464Xlat.class.getSimpleName();
    private static final int[] NETWORK_TYPES = {0, 1, 9};
    private static final NetworkInfo.State[] NETWORK_STATES = {NetworkInfo.State.CONNECTED, NetworkInfo.State.SUSPENDED};

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public enum State {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    public Nat464Xlat(INetworkManagementService nmService, NetworkAgentInfo nai) {
        this.mNMService = nmService;
        this.mNetwork = nai;
    }

    public static boolean requiresClat(NetworkAgentInfo nai) {
        boolean supported = ArrayUtils.contains(NETWORK_TYPES, nai.networkInfo.getType());
        boolean connected = ArrayUtils.contains(NETWORK_STATES, nai.networkInfo.getState());
        boolean hasIPv4Address = nai.linkProperties != null && nai.linkProperties.hasIPv4Address();
        return supported && connected && !hasIPv4Address;
    }

    public boolean isStarted() {
        return this.mState != State.IDLE;
    }

    public boolean isStarting() {
        return this.mState == State.STARTING;
    }

    public boolean isRunning() {
        return this.mState == State.RUNNING;
    }

    public boolean isStopping() {
        return this.mState == State.STOPPING;
    }

    private void enterStartingState(String baseIface) {
        try {
            this.mNMService.registerObserver(this);
            try {
                this.mNMService.startClatd(baseIface);
            } catch (RemoteException | IllegalStateException e) {
                String str = TAG;
                Slog.e(str, "Error starting clatd on " + baseIface, e);
            }
            this.mIface = CLAT_PREFIX + baseIface;
            this.mBaseIface = baseIface;
            this.mState = State.STARTING;
        } catch (RemoteException e2) {
            String str2 = TAG;
            Slog.e(str2, "startClat: Can't register interface observer for clat on " + this.mNetwork.name());
        }
    }

    private void enterRunningState() {
        this.mState = State.RUNNING;
    }

    private void enterStoppingState() {
        try {
            this.mNMService.stopClatd(this.mBaseIface);
        } catch (RemoteException | IllegalStateException e) {
            String str = TAG;
            Slog.e(str, "Error stopping clatd on " + this.mBaseIface, e);
        }
        this.mState = State.STOPPING;
    }

    private void enterIdleState() {
        try {
            this.mNMService.unregisterObserver(this);
        } catch (RemoteException | IllegalStateException e) {
            String str = TAG;
            Slog.e(str, "Error unregistering clatd observer on " + this.mBaseIface, e);
        }
        this.mIface = null;
        this.mBaseIface = null;
        this.mState = State.IDLE;
    }

    public void start() {
        if (isStarted()) {
            Slog.e(TAG, "startClat: already started");
        } else if (this.mNetwork.linkProperties == null) {
            Slog.e(TAG, "startClat: Can't start clat with null LinkProperties");
        } else {
            String baseIface = this.mNetwork.linkProperties.getInterfaceName();
            if (baseIface == null) {
                Slog.e(TAG, "startClat: Can't start clat on null interface");
                return;
            }
            String str = TAG;
            Slog.i(str, "Starting clatd on " + baseIface);
            enterStartingState(baseIface);
        }
    }

    public void stop() {
        if (!isStarted()) {
            return;
        }
        String str = TAG;
        Slog.i(str, "Stopping clatd on " + this.mBaseIface);
        boolean wasStarting = isStarting();
        enterStoppingState();
        if (wasStarting) {
            enterIdleState();
        }
    }

    public void fixupLinkProperties(LinkProperties oldLp, LinkProperties lp) {
        if (!isRunning() || lp == null || lp.getAllInterfaceNames().contains(this.mIface)) {
            return;
        }
        String str = TAG;
        Slog.d(str, "clatd running, updating NAI for " + this.mIface);
        for (LinkProperties stacked : oldLp.getStackedLinks()) {
            if (Objects.equals(this.mIface, stacked.getInterfaceName())) {
                lp.addStackedLink(stacked);
                return;
            }
        }
    }

    private LinkProperties makeLinkProperties(LinkAddress clatAddress) {
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName(this.mIface);
        RouteInfo ipv4Default = new RouteInfo(new LinkAddress(Inet4Address.ANY, 0), clatAddress.getAddress(), this.mIface);
        stacked.addRoute(ipv4Default);
        stacked.addLinkAddress(clatAddress);
        return stacked;
    }

    private LinkAddress getLinkAddress(String iface) {
        try {
            InterfaceConfiguration config = this.mNMService.getInterfaceConfig(iface);
            return config.getLinkAddress();
        } catch (RemoteException | IllegalStateException e) {
            String str = TAG;
            Slog.e(str, "Error getting link properties: " + e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleInterfaceLinkStateChanged(String iface, boolean up) {
        if (!isStarting() || !up || !Objects.equals(this.mIface, iface)) {
            return;
        }
        LinkAddress clatAddress = getLinkAddress(iface);
        if (clatAddress == null) {
            String str = TAG;
            Slog.e(str, "clatAddress was null for stacked iface " + iface);
            return;
        }
        Slog.i(TAG, String.format("interface %s is up, adding stacked link %s on top of %s", this.mIface, this.mIface, this.mBaseIface));
        enterRunningState();
        LinkProperties lp = new LinkProperties(this.mNetwork.linkProperties);
        lp.addStackedLink(makeLinkProperties(clatAddress));
        this.mNetwork.connService().handleUpdateLinkProperties(this.mNetwork, lp);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleInterfaceRemoved(String iface) {
        if (!Objects.equals(this.mIface, iface)) {
            return;
        }
        if (!isRunning() && !isStopping()) {
            return;
        }
        String str = TAG;
        Slog.i(str, "interface " + iface + " removed");
        if (!isStopping()) {
            enterStoppingState();
        }
        enterIdleState();
        LinkProperties lp = new LinkProperties(this.mNetwork.linkProperties);
        lp.removeStackedLink(iface);
        this.mNetwork.connService().handleUpdateLinkProperties(this.mNetwork, lp);
    }

    public void interfaceLinkStateChanged(final String iface, final boolean up) {
        this.mNetwork.handler().post(new Runnable() { // from class: com.android.server.connectivity.-$$Lambda$Nat464Xlat$40jKHQd7R0zgcegyEyc9zPHKXVA
            @Override // java.lang.Runnable
            public final void run() {
                Nat464Xlat.this.handleInterfaceLinkStateChanged(iface, up);
            }
        });
    }

    public void interfaceRemoved(final String iface) {
        this.mNetwork.handler().post(new Runnable() { // from class: com.android.server.connectivity.-$$Lambda$Nat464Xlat$PACHOP9HoYvr_jzHtIwFDy31Ud4
            @Override // java.lang.Runnable
            public final void run() {
                Nat464Xlat.this.handleInterfaceRemoved(iface);
            }
        });
    }

    public String toString() {
        return "mBaseIface: " + this.mBaseIface + ", mIface: " + this.mIface + ", mState: " + this.mState;
    }
}
