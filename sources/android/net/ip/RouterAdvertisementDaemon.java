package android.net.ip;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.TrafficStats;
import android.net.util.InterfaceParams;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import libcore.io.IoBridge;

/* loaded from: classes.dex */
public class RouterAdvertisementDaemon {
    private static final int DAY_IN_SECONDS = 86400;
    private static final int DEFAULT_LIFETIME = 3600;
    private static final int MAX_RTR_ADV_INTERVAL_SEC = 600;
    private static final int MAX_URGENT_RTR_ADVERTISEMENTS = 5;
    private static final int MIN_DELAY_BETWEEN_RAS_SEC = 3;
    private static final int MIN_RA_HEADER_SIZE = 16;
    private static final int MIN_RTR_ADV_INTERVAL_SEC = 300;
    private final InetSocketAddress mAllNodes;
    private final InterfaceParams mInterface;
    private volatile MulticastTransmitter mMulticastTransmitter;
    @GuardedBy({"mLock"})
    private int mRaLength;
    @GuardedBy({"mLock"})
    private RaParams mRaParams;
    private volatile FileDescriptor mSocket;
    private volatile UnicastResponder mUnicastResponder;
    private static final String TAG = RouterAdvertisementDaemon.class.getSimpleName();
    private static final byte ICMPV6_ND_ROUTER_SOLICIT = asByte(133);
    private static final byte ICMPV6_ND_ROUTER_ADVERT = asByte(134);
    private static final byte[] ALL_NODES = {-1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private final byte[] mRA = new byte[1280];
    @GuardedBy({"mLock"})
    private final DeprecatedInfoTracker mDeprecatedInfoTracker = new DeprecatedInfoTracker();

    /* loaded from: classes.dex */
    public static class RaParams {
        static final byte DEFAULT_HOPLIMIT = 65;
        public HashSet<Inet6Address> dnses;
        public boolean hasDefaultRoute;
        public byte hopLimit;
        public int mtu;
        public HashSet<IpPrefix> prefixes;

        public RaParams() {
            this.hasDefaultRoute = false;
            this.hopLimit = DEFAULT_HOPLIMIT;
            this.mtu = 1280;
            this.prefixes = new HashSet<>();
            this.dnses = new HashSet<>();
        }

        public RaParams(RaParams other) {
            this.hasDefaultRoute = other.hasDefaultRoute;
            this.hopLimit = other.hopLimit;
            this.mtu = other.mtu;
            this.prefixes = (HashSet) other.prefixes.clone();
            this.dnses = (HashSet) other.dnses.clone();
        }

        public static RaParams getDeprecatedRaParams(RaParams oldRa, RaParams newRa) {
            RaParams newlyDeprecated = new RaParams();
            if (oldRa != null) {
                Iterator<IpPrefix> it = oldRa.prefixes.iterator();
                while (it.hasNext()) {
                    IpPrefix ipp = it.next();
                    if (newRa == null || !newRa.prefixes.contains(ipp)) {
                        newlyDeprecated.prefixes.add(ipp);
                    }
                }
                Iterator<Inet6Address> it2 = oldRa.dnses.iterator();
                while (it2.hasNext()) {
                    Inet6Address dns = it2.next();
                    if (newRa == null || !newRa.dnses.contains(dns)) {
                        newlyDeprecated.dnses.add(dns);
                    }
                }
            }
            return newlyDeprecated;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class DeprecatedInfoTracker {
        private final HashMap<Inet6Address, Integer> mDnses;
        private final HashMap<IpPrefix, Integer> mPrefixes;

        private DeprecatedInfoTracker() {
            this.mPrefixes = new HashMap<>();
            this.mDnses = new HashMap<>();
        }

        Set<IpPrefix> getPrefixes() {
            return this.mPrefixes.keySet();
        }

        void putPrefixes(Set<IpPrefix> prefixes) {
            for (IpPrefix ipp : prefixes) {
                this.mPrefixes.put(ipp, 5);
            }
        }

        void removePrefixes(Set<IpPrefix> prefixes) {
            for (IpPrefix ipp : prefixes) {
                this.mPrefixes.remove(ipp);
            }
        }

        Set<Inet6Address> getDnses() {
            return this.mDnses.keySet();
        }

        void putDnses(Set<Inet6Address> dnses) {
            for (Inet6Address dns : dnses) {
                this.mDnses.put(dns, 5);
            }
        }

        void removeDnses(Set<Inet6Address> dnses) {
            for (Inet6Address dns : dnses) {
                this.mDnses.remove(dns);
            }
        }

        boolean isEmpty() {
            return this.mPrefixes.isEmpty() && this.mDnses.isEmpty();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean decrementCounters() {
            boolean removed = decrementCounter(this.mPrefixes);
            return removed | decrementCounter(this.mDnses);
        }

        private <T> boolean decrementCounter(HashMap<T, Integer> map) {
            boolean removed = false;
            Iterator<Map.Entry<T, Integer>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<T, Integer> kv = it.next();
                if (kv.getValue().intValue() == 0) {
                    it.remove();
                    removed = true;
                } else {
                    kv.setValue(Integer.valueOf(kv.getValue().intValue() - 1));
                }
            }
            return removed;
        }
    }

    public RouterAdvertisementDaemon(InterfaceParams ifParams) {
        this.mInterface = ifParams;
        this.mAllNodes = new InetSocketAddress(getAllNodesForScopeId(this.mInterface.index), 0);
    }

    public void buildNewRa(RaParams deprecatedParams, RaParams newParams) {
        synchronized (this.mLock) {
            if (deprecatedParams != null) {
                try {
                    this.mDeprecatedInfoTracker.putPrefixes(deprecatedParams.prefixes);
                    this.mDeprecatedInfoTracker.putDnses(deprecatedParams.dnses);
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (newParams != null) {
                this.mDeprecatedInfoTracker.removePrefixes(newParams.prefixes);
                this.mDeprecatedInfoTracker.removeDnses(newParams.dnses);
            }
            this.mRaParams = newParams;
            assembleRaLocked();
        }
        maybeNotifyMulticastTransmitter();
    }

    public boolean start() {
        if (!createSocket()) {
            return false;
        }
        this.mMulticastTransmitter = new MulticastTransmitter();
        this.mMulticastTransmitter.start();
        this.mUnicastResponder = new UnicastResponder();
        this.mUnicastResponder.start();
        return true;
    }

    public void stop() {
        closeSocket();
        maybeNotifyMulticastTransmitter();
        this.mMulticastTransmitter = null;
        this.mUnicastResponder = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:15:0x0024 A[Catch: BufferOverflowException -> 0x001e, TryCatch #0 {BufferOverflowException -> 0x001e, blocks: (B:8:0x0017, B:15:0x0024, B:17:0x002b, B:19:0x0041, B:20:0x0056, B:22:0x005e, B:23:0x006f, B:25:0x0079, B:26:0x0088, B:27:0x0092, B:29:0x0098, B:30:0x00a9, B:32:0x00b5), top: B:39:0x0017 }] */
    /* JADX WARN: Removed duplicated region for block: B:16:0x0029  */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0041 A[Catch: BufferOverflowException -> 0x001e, TryCatch #0 {BufferOverflowException -> 0x001e, blocks: (B:8:0x0017, B:15:0x0024, B:17:0x002b, B:19:0x0041, B:20:0x0056, B:22:0x005e, B:23:0x006f, B:25:0x0079, B:26:0x0088, B:27:0x0092, B:29:0x0098, B:30:0x00a9, B:32:0x00b5), top: B:39:0x0017 }] */
    /* JADX WARN: Removed duplicated region for block: B:29:0x0098 A[Catch: BufferOverflowException -> 0x001e, LOOP:1: B:27:0x0092->B:29:0x0098, LOOP_END, TryCatch #0 {BufferOverflowException -> 0x001e, blocks: (B:8:0x0017, B:15:0x0024, B:17:0x002b, B:19:0x0041, B:20:0x0056, B:22:0x005e, B:23:0x006f, B:25:0x0079, B:26:0x0088, B:27:0x0092, B:29:0x0098, B:30:0x00a9, B:32:0x00b5), top: B:39:0x0017 }] */
    /* JADX WARN: Removed duplicated region for block: B:32:0x00b5 A[Catch: BufferOverflowException -> 0x001e, TRY_LEAVE, TryCatch #0 {BufferOverflowException -> 0x001e, blocks: (B:8:0x0017, B:15:0x0024, B:17:0x002b, B:19:0x0041, B:20:0x0056, B:22:0x005e, B:23:0x006f, B:25:0x0079, B:26:0x0088, B:27:0x0092, B:29:0x0098, B:30:0x00a9, B:32:0x00b5), top: B:39:0x0017 }] */
    /* JADX WARN: Removed duplicated region for block: B:37:0x00d8  */
    /* JADX WARN: Removed duplicated region for block: B:43:? A[RETURN, SYNTHETIC] */
    @com.android.internal.annotations.GuardedBy({"mLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void assembleRaLocked() {
        /*
            r8 = this;
            byte[] r0 = r8.mRA
            java.nio.ByteBuffer r0 = java.nio.ByteBuffer.wrap(r0)
            java.nio.ByteOrder r1 = java.nio.ByteOrder.BIG_ENDIAN
            r0.order(r1)
            android.net.ip.RouterAdvertisementDaemon$RaParams r1 = r8.mRaParams
            r2 = 1
            r3 = 0
            if (r1 == 0) goto L13
            r1 = r2
            goto L14
        L13:
            r1 = r3
        L14:
            r4 = 0
            if (r1 == 0) goto L21
            android.net.ip.RouterAdvertisementDaemon$RaParams r5 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            boolean r5 = r5.hasDefaultRoute     // Catch: java.nio.BufferOverflowException -> L1e
            if (r5 == 0) goto L21
            goto L22
        L1e:
            r2 = move-exception
            goto Lc0
        L21:
            r2 = r3
        L22:
            if (r1 == 0) goto L29
            android.net.ip.RouterAdvertisementDaemon$RaParams r5 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            byte r5 = r5.hopLimit     // Catch: java.nio.BufferOverflowException -> L1e
            goto L2b
        L29:
            r5 = 65
        L2b:
            putHeader(r0, r2, r5)     // Catch: java.nio.BufferOverflowException -> L1e
            android.net.util.InterfaceParams r2 = r8.mInterface     // Catch: java.nio.BufferOverflowException -> L1e
            android.net.MacAddress r2 = r2.macAddr     // Catch: java.nio.BufferOverflowException -> L1e
            byte[] r2 = r2.toByteArray()     // Catch: java.nio.BufferOverflowException -> L1e
            putSlla(r0, r2)     // Catch: java.nio.BufferOverflowException -> L1e
            int r2 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r2     // Catch: java.nio.BufferOverflowException -> L1e
            if (r1 == 0) goto L88
            android.net.ip.RouterAdvertisementDaemon$RaParams r2 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            int r2 = r2.mtu     // Catch: java.nio.BufferOverflowException -> L1e
            putMtu(r0, r2)     // Catch: java.nio.BufferOverflowException -> L1e
            int r2 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r2     // Catch: java.nio.BufferOverflowException -> L1e
            android.net.ip.RouterAdvertisementDaemon$RaParams r2 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.HashSet<android.net.IpPrefix> r2 = r2.prefixes     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.Iterator r2 = r2.iterator()     // Catch: java.nio.BufferOverflowException -> L1e
        L56:
            boolean r5 = r2.hasNext()     // Catch: java.nio.BufferOverflowException -> L1e
            r6 = 3600(0xe10, float:5.045E-42)
            if (r5 == 0) goto L6f
            java.lang.Object r5 = r2.next()     // Catch: java.nio.BufferOverflowException -> L1e
            android.net.IpPrefix r5 = (android.net.IpPrefix) r5     // Catch: java.nio.BufferOverflowException -> L1e
            putPio(r0, r5, r6, r6)     // Catch: java.nio.BufferOverflowException -> L1e
            int r6 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r6     // Catch: java.nio.BufferOverflowException -> L1e
            r4 = 1
            goto L56
        L6f:
            android.net.ip.RouterAdvertisementDaemon$RaParams r2 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.HashSet<java.net.Inet6Address> r2 = r2.dnses     // Catch: java.nio.BufferOverflowException -> L1e
            int r2 = r2.size()     // Catch: java.nio.BufferOverflowException -> L1e
            if (r2 <= 0) goto L88
            android.net.ip.RouterAdvertisementDaemon$RaParams r2 = r8.mRaParams     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.HashSet<java.net.Inet6Address> r2 = r2.dnses     // Catch: java.nio.BufferOverflowException -> L1e
            putRdnss(r0, r2, r6)     // Catch: java.nio.BufferOverflowException -> L1e
            int r2 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r2     // Catch: java.nio.BufferOverflowException -> L1e
            r2 = 1
            r4 = r2
        L88:
            android.net.ip.RouterAdvertisementDaemon$DeprecatedInfoTracker r2 = r8.mDeprecatedInfoTracker     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.Set r2 = r2.getPrefixes()     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.Iterator r2 = r2.iterator()     // Catch: java.nio.BufferOverflowException -> L1e
        L92:
            boolean r5 = r2.hasNext()     // Catch: java.nio.BufferOverflowException -> L1e
            if (r5 == 0) goto La9
            java.lang.Object r5 = r2.next()     // Catch: java.nio.BufferOverflowException -> L1e
            android.net.IpPrefix r5 = (android.net.IpPrefix) r5     // Catch: java.nio.BufferOverflowException -> L1e
            putPio(r0, r5, r3, r3)     // Catch: java.nio.BufferOverflowException -> L1e
            int r6 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r6     // Catch: java.nio.BufferOverflowException -> L1e
            r4 = 1
            goto L92
        La9:
            android.net.ip.RouterAdvertisementDaemon$DeprecatedInfoTracker r2 = r8.mDeprecatedInfoTracker     // Catch: java.nio.BufferOverflowException -> L1e
            java.util.Set r2 = r2.getDnses()     // Catch: java.nio.BufferOverflowException -> L1e
            boolean r5 = r2.isEmpty()     // Catch: java.nio.BufferOverflowException -> L1e
            if (r5 != 0) goto Lbf
            putRdnss(r0, r2, r3)     // Catch: java.nio.BufferOverflowException -> L1e
            int r5 = r0.position()     // Catch: java.nio.BufferOverflowException -> L1e
            r8.mRaLength = r5     // Catch: java.nio.BufferOverflowException -> L1e
            r4 = 1
        Lbf:
            goto Ld6
        Lc0:
            java.lang.String r5 = android.net.ip.RouterAdvertisementDaemon.TAG
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            r6.<init>()
            java.lang.String r7 = "Could not construct new RA: "
            r6.append(r7)
            r6.append(r2)
            java.lang.String r6 = r6.toString()
            android.util.Log.e(r5, r6)
        Ld6:
            if (r4 != 0) goto Lda
            r8.mRaLength = r3
        Lda:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: android.net.ip.RouterAdvertisementDaemon.assembleRaLocked():void");
    }

    private void maybeNotifyMulticastTransmitter() {
        MulticastTransmitter m = this.mMulticastTransmitter;
        if (m != null) {
            m.hup();
        }
    }

    private static Inet6Address getAllNodesForScopeId(int scopeId) {
        try {
            return Inet6Address.getByAddress("ff02::1", ALL_NODES, scopeId);
        } catch (UnknownHostException uhe) {
            String str = TAG;
            Log.wtf(str, "Failed to construct ff02::1 InetAddress: " + uhe);
            return null;
        }
    }

    private static byte asByte(int value) {
        return (byte) value;
    }

    private static short asShort(int value) {
        return (short) value;
    }

    private static void putHeader(ByteBuffer ra, boolean hasDefaultRoute, byte hopLimit) {
        ra.put(ICMPV6_ND_ROUTER_ADVERT).put(asByte(0)).putShort(asShort(0)).put(hopLimit).put(hasDefaultRoute ? asByte(8) : asByte(0)).putShort(hasDefaultRoute ? asShort(DEFAULT_LIFETIME) : asShort(0)).putInt(0).putInt(0);
    }

    private static void putSlla(ByteBuffer ra, byte[] slla) {
        if (slla == null || slla.length != 6) {
            return;
        }
        ra.put((byte) 1).put((byte) 1).put(slla);
    }

    private static void putExpandedFlagsOption(ByteBuffer ra) {
        ra.put((byte) 26).put((byte) 1).putShort(asShort(0)).putInt(0);
    }

    private static void putMtu(ByteBuffer ra, int mtu) {
        ra.put((byte) 5).put((byte) 1).putShort(asShort(0)).putInt(mtu >= 1280 ? mtu : 1280);
    }

    private static void putPio(ByteBuffer ra, IpPrefix ipp, int validTime, int preferredTime) {
        int prefixLength = ipp.getPrefixLength();
        if (prefixLength != 64) {
            return;
        }
        if (validTime < 0) {
            validTime = 0;
        }
        if (preferredTime < 0) {
            preferredTime = 0;
        }
        if (preferredTime > validTime) {
            preferredTime = validTime;
        }
        byte[] addr = ipp.getAddress().getAddress();
        ra.put((byte) 3).put((byte) 4).put(asByte(prefixLength)).put(asByte(192)).putInt(validTime).putInt(preferredTime).putInt(0).put(addr);
    }

    private static void putRio(ByteBuffer ra, IpPrefix ipp) {
        int i;
        int prefixLength = ipp.getPrefixLength();
        if (prefixLength > 64) {
            return;
        }
        if (prefixLength == 0) {
            i = 1;
        } else {
            i = prefixLength <= 8 ? 2 : 3;
        }
        byte RIO_NUM_8OCTETS = asByte(i);
        byte[] addr = ipp.getAddress().getAddress();
        ra.put((byte) 24).put(RIO_NUM_8OCTETS).put(asByte(prefixLength)).put(asByte(24)).putInt(DEFAULT_LIFETIME);
        if (prefixLength > 0) {
            ra.put(addr, 0, prefixLength > 64 ? 16 : 8);
        }
    }

    private static void putRdnss(ByteBuffer ra, Set<Inet6Address> dnses, int lifetime) {
        HashSet<Inet6Address> filteredDnses = new HashSet<>();
        for (Inet6Address dns : dnses) {
            if (new LinkAddress(dns, 64).isGlobalPreferred()) {
                filteredDnses.add(dns);
            }
        }
        if (filteredDnses.isEmpty()) {
            return;
        }
        byte RDNSS_NUM_8OCTETS = asByte((dnses.size() * 2) + 1);
        ra.put((byte) 25).put(RDNSS_NUM_8OCTETS).putShort(asShort(0)).putInt(lifetime);
        Iterator<Inet6Address> it = filteredDnses.iterator();
        while (it.hasNext()) {
            ra.put(it.next().getAddress());
        }
    }

    private boolean createSocket() {
        int oldTag = TrafficStats.getAndSetThreadStatsTag(-510);
        try {
            try {
                this.mSocket = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_RAW, OsConstants.IPPROTO_ICMPV6);
                Os.setsockoptTimeval(this.mSocket, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(300L));
                Os.setsockoptIfreq(this.mSocket, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, this.mInterface.name);
                NetworkUtils.protectFromVpn(this.mSocket);
                NetworkUtils.setupRaSocket(this.mSocket, this.mInterface.index);
                TrafficStats.setThreadStatsTag(oldTag);
                return true;
            } catch (ErrnoException | IOException e) {
                String str = TAG;
                Log.e(str, "Failed to create RA daemon socket: " + e);
                TrafficStats.setThreadStatsTag(oldTag);
                return false;
            }
        } catch (Throwable th) {
            TrafficStats.setThreadStatsTag(oldTag);
            throw th;
        }
    }

    private void closeSocket() {
        if (this.mSocket != null) {
            try {
                IoBridge.closeAndSignalBlockedThreads(this.mSocket);
            } catch (IOException e) {
            }
        }
        this.mSocket = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isSocketValid() {
        FileDescriptor s = this.mSocket;
        return s != null && s.valid();
    }

    private boolean isSuitableDestination(InetSocketAddress dest) {
        if (this.mAllNodes.equals(dest)) {
            return true;
        }
        InetAddress destip = dest.getAddress();
        return (destip instanceof Inet6Address) && destip.isLinkLocalAddress() && ((Inet6Address) destip).getScopeId() == this.mInterface.index;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeSendRA(InetSocketAddress dest) {
        if (dest == null || !isSuitableDestination(dest)) {
            dest = this.mAllNodes;
        }
        try {
            synchronized (this.mLock) {
                if (this.mRaLength < 16) {
                    return;
                }
                Os.sendto(this.mSocket, this.mRA, 0, this.mRaLength, 0, dest);
                String str = TAG;
                Log.d(str, "RA sendto " + dest.getAddress().getHostAddress());
            }
        } catch (ErrnoException | SocketException e) {
            if (isSocketValid()) {
                String str2 = TAG;
                Log.e(str2, "sendto error: " + e);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class UnicastResponder extends Thread {
        private final byte[] mSolication;
        private final InetSocketAddress solicitor;

        private UnicastResponder() {
            this.solicitor = new InetSocketAddress();
            this.mSolication = new byte[1280];
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            while (RouterAdvertisementDaemon.this.isSocketValid()) {
                try {
                    int rval = Os.recvfrom(RouterAdvertisementDaemon.this.mSocket, this.mSolication, 0, this.mSolication.length, 0, this.solicitor);
                    if (rval >= 1 && this.mSolication[0] == RouterAdvertisementDaemon.ICMPV6_ND_ROUTER_SOLICIT) {
                        RouterAdvertisementDaemon.this.maybeSendRA(this.solicitor);
                    }
                } catch (ErrnoException | SocketException e) {
                    if (RouterAdvertisementDaemon.this.isSocketValid()) {
                        String str = RouterAdvertisementDaemon.TAG;
                        Log.e(str, "recvfrom error: " + e);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class MulticastTransmitter extends Thread {
        private final Random mRandom;
        private final AtomicInteger mUrgentAnnouncements;

        private MulticastTransmitter() {
            this.mRandom = new Random();
            this.mUrgentAnnouncements = new AtomicInteger(0);
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            while (RouterAdvertisementDaemon.this.isSocketValid()) {
                try {
                    Thread.sleep(getNextMulticastTransmitDelayMs());
                } catch (InterruptedException e) {
                }
                RouterAdvertisementDaemon routerAdvertisementDaemon = RouterAdvertisementDaemon.this;
                routerAdvertisementDaemon.maybeSendRA(routerAdvertisementDaemon.mAllNodes);
                synchronized (RouterAdvertisementDaemon.this.mLock) {
                    if (RouterAdvertisementDaemon.this.mDeprecatedInfoTracker.decrementCounters()) {
                        RouterAdvertisementDaemon.this.assembleRaLocked();
                    }
                }
            }
        }

        public void hup() {
            this.mUrgentAnnouncements.set(4);
            interrupt();
        }

        private int getNextMulticastTransmitDelaySec() {
            synchronized (RouterAdvertisementDaemon.this.mLock) {
                if (RouterAdvertisementDaemon.this.mRaLength >= 16) {
                    boolean deprecationInProgress = !RouterAdvertisementDaemon.this.mDeprecatedInfoTracker.isEmpty();
                    int urgentPending = this.mUrgentAnnouncements.getAndDecrement();
                    if (urgentPending > 0 || deprecationInProgress) {
                        return 3;
                    }
                    return this.mRandom.nextInt(300) + 300;
                }
                return RouterAdvertisementDaemon.DAY_IN_SECONDS;
            }
        }

        private long getNextMulticastTransmitDelayMs() {
            return getNextMulticastTransmitDelaySec() * 1000;
        }
    }
}
