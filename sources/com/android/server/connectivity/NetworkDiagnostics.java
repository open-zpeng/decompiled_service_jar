package com.android.server.connectivity;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.text.TextUtils;
import android.util.Pair;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.xiaopeng.server.net.netstats.TrafficStatsEntry;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public class NetworkDiagnostics {
    private static final String TAG = "NetworkDiagnostics";
    private static final InetAddress TEST_DNS4 = NetworkUtils.numericToInetAddress("8.8.8.8");
    private static final InetAddress TEST_DNS6 = NetworkUtils.numericToInetAddress("2001:4860:4860::8888");
    private final CountDownLatch mCountDownLatch;
    private final long mDeadlineTime;
    private final String mDescription;
    private final Integer mInterfaceIndex;
    private final LinkProperties mLinkProperties;
    private final Network mNetwork;
    private final long mTimeoutMs;
    private final Map<InetAddress, Measurement> mIcmpChecks = new HashMap();
    private final Map<Pair<InetAddress, InetAddress>, Measurement> mExplicitSourceIcmpChecks = new HashMap();
    private final Map<InetAddress, Measurement> mDnsUdpChecks = new HashMap();
    private final long mStartTime = now();

    /* loaded from: classes.dex */
    public enum DnsResponseCode {
        NOERROR,
        FORMERR,
        SERVFAIL,
        NXDOMAIN,
        NOTIMP,
        REFUSED
    }

    static /* synthetic */ long access$100() {
        return now();
    }

    private static final long now() {
        return SystemClock.elapsedRealtime();
    }

    /* loaded from: classes.dex */
    public class Measurement {
        private static final String FAILED = "FAILED";
        private static final String SUCCEEDED = "SUCCEEDED";
        long finishTime;
        long startTime;
        private boolean succeeded;
        Thread thread;
        String description = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        String result = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;

        public Measurement() {
        }

        public boolean checkSucceeded() {
            return this.succeeded;
        }

        void recordSuccess(String msg) {
            maybeFixupTimes();
            this.succeeded = true;
            this.result = "SUCCEEDED: " + msg;
            if (NetworkDiagnostics.this.mCountDownLatch != null) {
                NetworkDiagnostics.this.mCountDownLatch.countDown();
            }
        }

        void recordFailure(String msg) {
            maybeFixupTimes();
            this.succeeded = false;
            this.result = "FAILED: " + msg;
            if (NetworkDiagnostics.this.mCountDownLatch != null) {
                NetworkDiagnostics.this.mCountDownLatch.countDown();
            }
        }

        private void maybeFixupTimes() {
            if (this.finishTime == 0) {
                this.finishTime = NetworkDiagnostics.access$100();
            }
            if (this.startTime == 0) {
                this.startTime = this.finishTime;
            }
        }

        public String toString() {
            return this.description + ": " + this.result + " (" + (this.finishTime - this.startTime) + "ms)";
        }
    }

    public NetworkDiagnostics(Network network, LinkProperties lp, long timeoutMs) {
        this.mNetwork = network;
        this.mLinkProperties = lp;
        this.mInterfaceIndex = getInterfaceIndex(this.mLinkProperties.getInterfaceName());
        this.mTimeoutMs = timeoutMs;
        this.mDeadlineTime = this.mStartTime + this.mTimeoutMs;
        if (this.mLinkProperties.isReachable(TEST_DNS4)) {
            this.mLinkProperties.addDnsServer(TEST_DNS4);
        }
        if (this.mLinkProperties.hasGlobalIPv6Address() || this.mLinkProperties.hasIPv6DefaultRoute()) {
            this.mLinkProperties.addDnsServer(TEST_DNS6);
        }
        for (RouteInfo route : this.mLinkProperties.getRoutes()) {
            if (route.hasGateway()) {
                InetAddress gateway = route.getGateway();
                prepareIcmpMeasurement(gateway);
                if (route.isIPv6Default()) {
                    prepareExplicitSourceIcmpMeasurements(gateway);
                }
            }
        }
        for (InetAddress nameserver : this.mLinkProperties.getDnsServers()) {
            prepareIcmpMeasurement(nameserver);
            prepareDnsMeasurement(nameserver);
        }
        this.mCountDownLatch = new CountDownLatch(totalMeasurementCount());
        startMeasurements();
        this.mDescription = "ifaces{" + TextUtils.join(",", this.mLinkProperties.getAllInterfaceNames()) + "} index{" + this.mInterfaceIndex + "} network{" + this.mNetwork + "} nethandle{" + this.mNetwork.getNetworkHandle() + "}";
    }

    private static Integer getInterfaceIndex(String ifname) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifname);
            return Integer.valueOf(ni.getIndex());
        } catch (NullPointerException | SocketException e) {
            return null;
        }
    }

    private void prepareIcmpMeasurement(InetAddress target) {
        if (!this.mIcmpChecks.containsKey(target)) {
            Measurement measurement = new Measurement();
            measurement.thread = new Thread(new IcmpCheck(this, target, measurement));
            this.mIcmpChecks.put(target, measurement);
        }
    }

    private void prepareExplicitSourceIcmpMeasurements(InetAddress target) {
        for (LinkAddress l : this.mLinkProperties.getLinkAddresses()) {
            InetAddress source = l.getAddress();
            if ((source instanceof Inet6Address) && l.isGlobalPreferred()) {
                Pair<InetAddress, InetAddress> srcTarget = new Pair<>(source, target);
                if (!this.mExplicitSourceIcmpChecks.containsKey(srcTarget)) {
                    Measurement measurement = new Measurement();
                    measurement.thread = new Thread(new IcmpCheck(source, target, measurement));
                    this.mExplicitSourceIcmpChecks.put(srcTarget, measurement);
                }
            }
        }
    }

    private void prepareDnsMeasurement(InetAddress target) {
        if (!this.mDnsUdpChecks.containsKey(target)) {
            Measurement measurement = new Measurement();
            measurement.thread = new Thread(new DnsUdpCheck(target, measurement));
            this.mDnsUdpChecks.put(target, measurement);
        }
    }

    private int totalMeasurementCount() {
        return this.mIcmpChecks.size() + this.mExplicitSourceIcmpChecks.size() + this.mDnsUdpChecks.size();
    }

    private void startMeasurements() {
        for (Measurement measurement : this.mIcmpChecks.values()) {
            measurement.thread.start();
        }
        for (Measurement measurement2 : this.mExplicitSourceIcmpChecks.values()) {
            measurement2.thread.start();
        }
        for (Measurement measurement3 : this.mDnsUdpChecks.values()) {
            measurement3.thread.start();
        }
    }

    public void waitForMeasurements() {
        try {
            this.mCountDownLatch.await(this.mDeadlineTime - now(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
    }

    public List<Measurement> getMeasurements() {
        ArrayList<Measurement> measurements = new ArrayList<>(totalMeasurementCount());
        for (Map.Entry<InetAddress, Measurement> entry : this.mIcmpChecks.entrySet()) {
            if (entry.getKey() instanceof Inet4Address) {
                measurements.add(entry.getValue());
            }
        }
        for (Map.Entry<Pair<InetAddress, InetAddress>, Measurement> entry2 : this.mExplicitSourceIcmpChecks.entrySet()) {
            if (entry2.getKey().first instanceof Inet4Address) {
                measurements.add(entry2.getValue());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry3 : this.mDnsUdpChecks.entrySet()) {
            if (entry3.getKey() instanceof Inet4Address) {
                measurements.add(entry3.getValue());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry4 : this.mIcmpChecks.entrySet()) {
            if (entry4.getKey() instanceof Inet6Address) {
                measurements.add(entry4.getValue());
            }
        }
        for (Map.Entry<Pair<InetAddress, InetAddress>, Measurement> entry5 : this.mExplicitSourceIcmpChecks.entrySet()) {
            if (entry5.getKey().first instanceof Inet6Address) {
                measurements.add(entry5.getValue());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry6 : this.mDnsUdpChecks.entrySet()) {
            if (entry6.getKey() instanceof Inet6Address) {
                measurements.add(entry6.getValue());
            }
        }
        return measurements;
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("NetworkDiagnostics:" + this.mDescription);
        long unfinished = this.mCountDownLatch.getCount();
        if (unfinished > 0) {
            pw.println("WARNING: countdown wait incomplete: " + unfinished + " unfinished measurements");
        }
        pw.increaseIndent();
        for (Measurement m : getMeasurements()) {
            String prefix = m.checkSucceeded() ? "." : "F";
            pw.println(prefix + "  " + m.toString());
        }
        pw.decreaseIndent();
    }

    /* loaded from: classes.dex */
    private class SimpleSocketCheck implements Closeable {
        protected final int mAddressFamily;
        protected FileDescriptor mFileDescriptor;
        protected final Measurement mMeasurement;
        protected SocketAddress mSocketAddress;
        protected final InetAddress mSource;
        protected final InetAddress mTarget;

        /* JADX WARN: Removed duplicated region for block: B:15:0x0037  */
        /* JADX WARN: Removed duplicated region for block: B:16:0x0039  */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        protected SimpleSocketCheck(java.net.InetAddress r5, java.net.InetAddress r6, com.android.server.connectivity.NetworkDiagnostics.Measurement r7) {
            /*
                r3 = this;
                com.android.server.connectivity.NetworkDiagnostics.this = r4
                r3.<init>()
                r3.mMeasurement = r7
                boolean r0 = r6 instanceof java.net.Inet6Address
                if (r0 == 0) goto L41
                r0 = 0
                boolean r1 = r6.isLinkLocalAddress()
                if (r1 == 0) goto L34
                java.lang.Integer r1 = com.android.server.connectivity.NetworkDiagnostics.access$200(r4)
                if (r1 == 0) goto L34
                r1 = 0
                byte[] r2 = r6.getAddress()     // Catch: java.net.UnknownHostException -> L2a
                java.lang.Integer r4 = com.android.server.connectivity.NetworkDiagnostics.access$200(r4)     // Catch: java.net.UnknownHostException -> L2a
                int r4 = r4.intValue()     // Catch: java.net.UnknownHostException -> L2a
                java.net.Inet6Address r4 = java.net.Inet6Address.getByAddress(r1, r2, r4)     // Catch: java.net.UnknownHostException -> L2a
                goto L35
            L2a:
                r4 = move-exception
                com.android.server.connectivity.NetworkDiagnostics$Measurement r1 = r3.mMeasurement
                java.lang.String r2 = r4.toString()
                r1.recordFailure(r2)
            L34:
                r4 = r0
            L35:
                if (r4 == 0) goto L39
                r0 = r4
                goto L3a
            L39:
                r0 = r6
            L3a:
                r3.mTarget = r0
                int r0 = android.system.OsConstants.AF_INET6
                r3.mAddressFamily = r0
                goto L47
            L41:
                r3.mTarget = r6
                int r4 = android.system.OsConstants.AF_INET
                r3.mAddressFamily = r4
            L47:
                r3.mSource = r5
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.connectivity.NetworkDiagnostics.SimpleSocketCheck.<init>(com.android.server.connectivity.NetworkDiagnostics, java.net.InetAddress, java.net.InetAddress, com.android.server.connectivity.NetworkDiagnostics$Measurement):void");
        }

        protected SimpleSocketCheck(NetworkDiagnostics networkDiagnostics, InetAddress target, Measurement measurement) {
            this(null, target, measurement);
        }

        protected void setupSocket(int sockType, int protocol, long writeTimeout, long readTimeout, int dstPort) throws ErrnoException, IOException {
            int oldTag = TrafficStats.getAndSetThreadStatsTag(-190);
            try {
                this.mFileDescriptor = Os.socket(this.mAddressFamily, sockType, protocol);
                TrafficStats.setThreadStatsTag(oldTag);
                Os.setsockoptTimeval(this.mFileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(writeTimeout));
                Os.setsockoptTimeval(this.mFileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(readTimeout));
                NetworkDiagnostics.this.mNetwork.bindSocket(this.mFileDescriptor);
                if (this.mSource != null) {
                    Os.bind(this.mFileDescriptor, this.mSource, 0);
                }
                Os.connect(this.mFileDescriptor, this.mTarget, dstPort);
                this.mSocketAddress = Os.getsockname(this.mFileDescriptor);
            } catch (Throwable th) {
                TrafficStats.setThreadStatsTag(oldTag);
                throw th;
            }
        }

        protected String getSocketAddressString() {
            InetSocketAddress inetSockAddr = (InetSocketAddress) this.mSocketAddress;
            InetAddress localAddr = inetSockAddr.getAddress();
            return String.format(localAddr instanceof Inet6Address ? "[%s]:%d" : "%s:%d", localAddr.getHostAddress(), Integer.valueOf(inetSockAddr.getPort()));
        }

        @Override // java.io.Closeable, java.lang.AutoCloseable
        public void close() {
            IoUtils.closeQuietly(this.mFileDescriptor);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class IcmpCheck extends SimpleSocketCheck implements Runnable {
        private static final int PACKET_BUFSIZE = 512;
        private static final int TIMEOUT_RECV = 300;
        private static final int TIMEOUT_SEND = 100;
        private final int mIcmpType;
        private final int mProtocol;

        public IcmpCheck(InetAddress source, InetAddress target, Measurement measurement) {
            super(source, target, measurement);
            if (this.mAddressFamily == OsConstants.AF_INET6) {
                this.mProtocol = OsConstants.IPPROTO_ICMPV6;
                this.mIcmpType = 128;
                this.mMeasurement.description = "ICMPv6";
            } else {
                this.mProtocol = OsConstants.IPPROTO_ICMP;
                this.mIcmpType = 8;
                this.mMeasurement.description = "ICMPv4";
            }
            StringBuilder sb = new StringBuilder();
            Measurement measurement2 = this.mMeasurement;
            sb.append(measurement2.description);
            sb.append(" dst{");
            sb.append(this.mTarget.getHostAddress());
            sb.append("}");
            measurement2.description = sb.toString();
        }

        public IcmpCheck(NetworkDiagnostics networkDiagnostics, InetAddress target, Measurement measurement) {
            this(null, target, measurement);
        }

        @Override // java.lang.Runnable
        public void run() {
            if (this.mMeasurement.finishTime > 0) {
                NetworkDiagnostics.this.mCountDownLatch.countDown();
                return;
            }
            try {
                setupSocket(OsConstants.SOCK_DGRAM, this.mProtocol, 100L, 300L, 0);
                StringBuilder sb = new StringBuilder();
                Measurement measurement = this.mMeasurement;
                sb.append(measurement.description);
                sb.append(" src{");
                sb.append(getSocketAddressString());
                sb.append("}");
                measurement.description = sb.toString();
                byte[] icmpPacket = new byte[8];
                icmpPacket[0] = (byte) this.mIcmpType;
                icmpPacket[1] = 0;
                icmpPacket[2] = 0;
                icmpPacket[3] = 0;
                icmpPacket[4] = 0;
                icmpPacket[5] = 0;
                icmpPacket[6] = 0;
                icmpPacket[7] = 0;
                int count = 0;
                this.mMeasurement.startTime = NetworkDiagnostics.access$100();
                while (NetworkDiagnostics.access$100() < NetworkDiagnostics.this.mDeadlineTime - 400) {
                    count++;
                    icmpPacket[icmpPacket.length - 1] = (byte) count;
                    try {
                        Os.write(this.mFileDescriptor, icmpPacket, 0, icmpPacket.length);
                        try {
                            ByteBuffer reply = ByteBuffer.allocate(512);
                            Os.read(this.mFileDescriptor, reply);
                            Measurement measurement2 = this.mMeasurement;
                            measurement2.recordSuccess("1/" + count);
                            break;
                        } catch (ErrnoException | InterruptedIOException e) {
                        }
                    } catch (ErrnoException | InterruptedIOException e2) {
                        this.mMeasurement.recordFailure(e2.toString());
                    }
                }
                if (this.mMeasurement.finishTime == 0) {
                    Measurement measurement3 = this.mMeasurement;
                    measurement3.recordFailure("0/" + count);
                }
                close();
            } catch (ErrnoException | IOException e3) {
                this.mMeasurement.recordFailure(e3.toString());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DnsUdpCheck extends SimpleSocketCheck implements Runnable {
        private static final int PACKET_BUFSIZE = 512;
        private static final int RR_TYPE_A = 1;
        private static final int RR_TYPE_AAAA = 28;
        private static final int TIMEOUT_RECV = 500;
        private static final int TIMEOUT_SEND = 100;
        private final int mQueryType;
        private final Random mRandom;

        private String responseCodeStr(int rcode) {
            try {
                return DnsResponseCode.values()[rcode].toString();
            } catch (IndexOutOfBoundsException e) {
                return String.valueOf(rcode);
            }
        }

        public DnsUdpCheck(InetAddress target, Measurement measurement) {
            super(NetworkDiagnostics.this, target, measurement);
            this.mRandom = new Random();
            if (this.mAddressFamily == OsConstants.AF_INET6) {
                this.mQueryType = 28;
            } else {
                this.mQueryType = 1;
            }
            Measurement measurement2 = this.mMeasurement;
            measurement2.description = "DNS UDP dst{" + this.mTarget.getHostAddress() + "}";
        }

        @Override // java.lang.Runnable
        public void run() {
            String rcodeStr;
            if (this.mMeasurement.finishTime > 0) {
                NetworkDiagnostics.this.mCountDownLatch.countDown();
                return;
            }
            try {
                setupSocket(OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP, 100L, 500L, 53);
                StringBuilder sb = new StringBuilder();
                Measurement measurement = this.mMeasurement;
                sb.append(measurement.description);
                sb.append(" src{");
                sb.append(getSocketAddressString());
                sb.append("}");
                measurement.description = sb.toString();
                String sixRandomDigits = String.valueOf(this.mRandom.nextInt(900000) + TrafficStatsEntry.FIRST_NETWORK_UID);
                StringBuilder sb2 = new StringBuilder();
                Measurement measurement2 = this.mMeasurement;
                sb2.append(measurement2.description);
                sb2.append(" qtype{");
                sb2.append(this.mQueryType);
                sb2.append("} qname{");
                sb2.append(sixRandomDigits);
                sb2.append("-android-ds.metric.gstatic.com}");
                measurement2.description = sb2.toString();
                byte[] dnsPacket = getDnsQueryPacket(sixRandomDigits);
                int count = 0;
                this.mMeasurement.startTime = NetworkDiagnostics.access$100();
                while (NetworkDiagnostics.access$100() < NetworkDiagnostics.this.mDeadlineTime - 1000) {
                    count++;
                    try {
                        Os.write(this.mFileDescriptor, dnsPacket, 0, dnsPacket.length);
                        try {
                            ByteBuffer reply = ByteBuffer.allocate(512);
                            Os.read(this.mFileDescriptor, reply);
                            if (reply.limit() > 3) {
                                rcodeStr = " " + responseCodeStr(reply.get(3) & UsbDescriptor.DESCRIPTORTYPE_BOS);
                            } else {
                                rcodeStr = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                            }
                            this.mMeasurement.recordSuccess("1/" + count + rcodeStr);
                            break;
                        } catch (ErrnoException | InterruptedIOException e) {
                        }
                    } catch (ErrnoException | InterruptedIOException e2) {
                        this.mMeasurement.recordFailure(e2.toString());
                    }
                }
                if (this.mMeasurement.finishTime == 0) {
                    this.mMeasurement.recordFailure("0/" + count);
                }
                close();
            } catch (ErrnoException | IOException e3) {
                this.mMeasurement.recordFailure(e3.toString());
            }
        }

        private byte[] getDnsQueryPacket(String sixRandomDigits) {
            byte[] rnd = sixRandomDigits.getBytes(StandardCharsets.US_ASCII);
            return new byte[]{(byte) this.mRandom.nextInt(), (byte) this.mRandom.nextInt(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 17, rnd[0], rnd[1], rnd[2], rnd[3], rnd[4], rnd[5], 45, 97, 110, 100, 114, 111, 105, 100, 45, 100, 115, 6, 109, 101, 116, 114, 105, 99, 7, 103, 115, 116, 97, 116, 105, 99, 3, 99, 111, 109, 0, 0, (byte) this.mQueryType, 0, 1};
        }
    }
}
