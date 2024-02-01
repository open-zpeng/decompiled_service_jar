package com.android.server;

import android.content.Context;
import android.net.INetd;
import android.net.ITestNetworkManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TestNetworkInterface;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TestNetworkService extends ITestNetworkManager.Stub {
    private static final String PERMISSION_NAME = "android.permission.MANAGE_TEST_NETWORKS";
    private static final String TEST_NETWORK_TYPE = "TEST_NETWORK";
    private static final String TEST_TAP_PREFIX = "testtap";
    private static final String TEST_TUN_PREFIX = "testtun";
    private final Context mContext;
    private final Handler mHandler;
    private final INetworkManagementService mNMS;
    private final INetd mNetd;
    private static final String TAG = TestNetworkService.class.getSimpleName();
    private static final AtomicInteger sTestTunIndex = new AtomicInteger();
    @GuardedBy({"mTestNetworkTracker"})
    private final SparseArray<TestNetworkAgent> mTestNetworkTracker = new SparseArray<>();
    private final HandlerThread mHandlerThread = new HandlerThread("TestNetworkServiceThread");

    private static native int jniCreateTunTap(boolean z, String str);

    /* JADX INFO: Access modifiers changed from: protected */
    @VisibleForTesting
    public TestNetworkService(Context context, INetworkManagementService netManager) {
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper());
        this.mContext = (Context) Preconditions.checkNotNull(context, "missing Context");
        this.mNMS = (INetworkManagementService) Preconditions.checkNotNull(netManager, "missing INetworkManagementService");
        this.mNetd = (INetd) Preconditions.checkNotNull(NetdService.getInstance(), "could not get netd instance");
    }

    private TestNetworkInterface createInterface(final boolean isTun, final LinkAddress[] linkAddrs) {
        enforceTestNetworkPermissions(this.mContext);
        Preconditions.checkNotNull(linkAddrs, "missing linkAddrs");
        String ifacePrefix = isTun ? TEST_TUN_PREFIX : TEST_TAP_PREFIX;
        final String iface = ifacePrefix + sTestTunIndex.getAndIncrement();
        return (TestNetworkInterface) Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingSupplier() { // from class: com.android.server.-$$Lambda$TestNetworkService$kNsToB0Cr6DV8jrvpBel_EzoIHE
            public final Object getOrThrow() {
                return TestNetworkService.this.lambda$createInterface$0$TestNetworkService(isTun, iface, linkAddrs);
            }
        });
    }

    public /* synthetic */ TestNetworkInterface lambda$createInterface$0$TestNetworkService(boolean isTun, String iface, LinkAddress[] linkAddrs) throws Exception {
        try {
            ParcelFileDescriptor tunIntf = ParcelFileDescriptor.adoptFd(jniCreateTunTap(isTun, iface));
            for (LinkAddress addr : linkAddrs) {
                this.mNetd.interfaceAddAddress(iface, addr.getAddress().getHostAddress(), addr.getPrefixLength());
            }
            return new TestNetworkInterface(tunIntf, iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public TestNetworkInterface createTunInterface(LinkAddress[] linkAddrs) {
        return createInterface(true, linkAddrs);
    }

    public TestNetworkInterface createTapInterface() {
        return createInterface(false, new LinkAddress[0]);
    }

    /* loaded from: classes.dex */
    public class TestNetworkAgent extends NetworkAgent implements IBinder.DeathRecipient {
        private static final int NETWORK_SCORE = 1;
        @GuardedBy({"mBinderLock"})
        private IBinder mBinder;
        private final Object mBinderLock;
        private final LinkProperties mLp;
        private final NetworkCapabilities mNc;
        private final NetworkInfo mNi;
        private final int mUid;

        private TestNetworkAgent(Looper looper, Context context, NetworkInfo ni, NetworkCapabilities nc, LinkProperties lp, int uid, IBinder binder) throws RemoteException {
            super(looper, context, TestNetworkService.TEST_NETWORK_TYPE, ni, nc, lp, 1);
            this.mBinderLock = new Object();
            this.mUid = uid;
            this.mNi = ni;
            this.mNc = nc;
            this.mLp = lp;
            synchronized (this.mBinderLock) {
                this.mBinder = binder;
                try {
                    this.mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                    throw e;
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            teardown();
        }

        protected void unwanted() {
            teardown();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void teardown() {
            this.mNi.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
            this.mNi.setIsAvailable(false);
            sendNetworkInfo(this.mNi);
            synchronized (this.mBinderLock) {
                if (this.mBinder == null) {
                    return;
                }
                this.mBinder.unlinkToDeath(this, 0);
                this.mBinder = null;
                synchronized (TestNetworkService.this.mTestNetworkTracker) {
                    TestNetworkService.this.mTestNetworkTracker.remove(this.netId);
                }
            }
        }
    }

    /* JADX WARN: Incorrect condition in loop: B:4:0x007a */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.TestNetworkService.TestNetworkAgent registerTestNetworkAgent(android.os.Looper r22, android.content.Context r23, java.lang.String r24, int r25, android.os.IBinder r26) throws android.os.RemoteException, java.net.SocketException {
        /*
            Method dump skipped, instructions count: 241
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.TestNetworkService.registerTestNetworkAgent(android.os.Looper, android.content.Context, java.lang.String, int, android.os.IBinder):com.android.server.TestNetworkService$TestNetworkAgent");
    }

    public void setupTestNetwork(final String iface, final IBinder binder) {
        enforceTestNetworkPermissions(this.mContext);
        Preconditions.checkNotNull(iface, "missing Iface");
        Preconditions.checkNotNull(binder, "missing IBinder");
        if (!iface.startsWith(INetd.IPSEC_INTERFACE_PREFIX) && !iface.startsWith(TEST_TUN_PREFIX)) {
            throw new IllegalArgumentException("Cannot create network for non ipsec, non-testtun interface");
        }
        final int callingUid = Binder.getCallingUid();
        Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.-$$Lambda$TestNetworkService$jaBdxV1WIiJrgh0fXY_tPjFxN8I
            public final void runOrThrow() {
                TestNetworkService.this.lambda$setupTestNetwork$1$TestNetworkService(iface, callingUid, binder);
            }
        });
    }

    public /* synthetic */ void lambda$setupTestNetwork$1$TestNetworkService(String iface, int callingUid, IBinder binder) throws Exception {
        try {
            this.mNMS.setInterfaceUp(iface);
            synchronized (this.mTestNetworkTracker) {
                TestNetworkAgent agent = registerTestNetworkAgent(this.mHandler.getLooper(), this.mContext, iface, callingUid, binder);
                this.mTestNetworkTracker.put(agent.netId, agent);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (SocketException e2) {
            throw new UncheckedIOException(e2);
        }
    }

    public void teardownTestNetwork(int netId) {
        TestNetworkAgent agent;
        enforceTestNetworkPermissions(this.mContext);
        synchronized (this.mTestNetworkTracker) {
            agent = this.mTestNetworkTracker.get(netId);
        }
        if (agent != null) {
            if (agent.mUid == Binder.getCallingUid()) {
                agent.teardown();
                return;
            }
            throw new SecurityException("Attempted to modify other user's test networks");
        }
    }

    public static void enforceTestNetworkPermissions(Context context) {
        context.enforceCallingOrSelfPermission(PERMISSION_NAME, "TestNetworkService");
    }
}
