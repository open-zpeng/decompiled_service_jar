package android.net.dhcp;

import android.content.Context;
import android.net.DhcpResults;
import android.net.NetworkUtils;
import android.net.TrafficStats;
import android.net.dhcp.DhcpPacket;
import android.net.metrics.DhcpClientEvent;
import android.net.metrics.DhcpErrorEvent;
import android.net.metrics.IpConnectivityLog;
import android.net.util.InterfaceParams;
import android.net.util.NetworkConstants;
import android.os.Message;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.PacketSocketAddress;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.internal.util.HexDump;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.usb.descriptors.UsbDescriptor;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import libcore.io.IoBridge;
/* loaded from: classes.dex */
public class DhcpClient extends StateMachine {
    public static final int CMD_CLEAR_LINKADDRESS = 196615;
    public static final int CMD_CONFIGURE_LINKADDRESS = 196616;
    private static final int CMD_EXPIRE_DHCP = 196714;
    private static final int CMD_KICK = 196709;
    public static final int CMD_ON_QUIT = 196613;
    public static final int CMD_POST_DHCP_ACTION = 196612;
    public static final int CMD_PRE_DHCP_ACTION = 196611;
    public static final int CMD_PRE_DHCP_ACTION_COMPLETE = 196614;
    private static final int CMD_REBIND_DHCP = 196713;
    private static final int CMD_RECEIVED_PACKET = 196710;
    private static final int CMD_RENEW_DHCP = 196712;
    public static final int CMD_START_DHCP = 196609;
    public static final int CMD_STOP_DHCP = 196610;
    private static final int CMD_TIMEOUT = 196711;
    private static final boolean DBG = true;
    public static final int DHCP_FAILURE = 2;
    public static final int DHCP_SUCCESS = 1;
    private static final int DHCP_TIMEOUT_MS = 36000;
    private static final boolean DO_UNICAST = false;
    public static final int EVENT_LINKADDRESS_CONFIGURED = 196617;
    private static final int FIRST_TIMEOUT_MS = 2000;
    private static final int MAX_TIMEOUT_MS = 128000;
    private static final boolean MSG_DBG = false;
    private static final boolean PACKET_DBG = false;
    private static final int PRIVATE_BASE = 196708;
    private static final int PUBLIC_BASE = 196608;
    private static final int SECONDS = 1000;
    private static final boolean STATE_DBG = false;
    private static final String TAG = "DhcpClient";
    private State mConfiguringInterfaceState;
    private final Context mContext;
    private final StateMachine mController;
    private State mDhcpBoundState;
    private State mDhcpHaveLeaseState;
    private State mDhcpInitRebootState;
    private State mDhcpInitState;
    private DhcpResults mDhcpLease;
    private long mDhcpLeaseExpiry;
    private State mDhcpRebindingState;
    private State mDhcpRebootingState;
    private State mDhcpRenewingState;
    private State mDhcpRequestingState;
    private State mDhcpSelectingState;
    private State mDhcpState;
    private final WakeupMessage mExpiryAlarm;
    private byte[] mHwAddr;
    private InterfaceParams mIface;
    private final String mIfaceName;
    private PacketSocketAddress mInterfaceBroadcastAddr;
    private final WakeupMessage mKickAlarm;
    private long mLastBoundExitTime;
    private long mLastInitEnterTime;
    private final IpConnectivityLog mMetricsLog;
    private DhcpResults mOffer;
    private FileDescriptor mPacketSock;
    private final Random mRandom;
    private final WakeupMessage mRebindAlarm;
    private ReceiveThread mReceiveThread;
    private boolean mRegisteredForPreDhcpNotification;
    private final WakeupMessage mRenewAlarm;
    private State mStoppedState;
    private final WakeupMessage mTimeoutAlarm;
    private int mTransactionId;
    private long mTransactionStartMillis;
    private FileDescriptor mUdpSock;
    private State mWaitBeforeRenewalState;
    private State mWaitBeforeStartState;
    private static final Class[] sMessageClasses = {DhcpClient.class};
    private static final SparseArray<String> sMessageNames = MessageUtils.findMessageNames(sMessageClasses);
    static final byte[] REQUESTED_PARAMS = {1, 3, 6, UsbDescriptor.DESCRIPTORTYPE_BOS, 26, 28, 51, 58, 59, 43};

    private WakeupMessage makeWakeupMessage(String cmdName, int cmd) {
        return new WakeupMessage(this.mContext, getHandler(), DhcpClient.class.getSimpleName() + "." + this.mIfaceName + "." + cmdName, cmd);
    }

    private DhcpClient(Context context, StateMachine controller, String iface) {
        super(TAG, controller.getHandler());
        this.mMetricsLog = new IpConnectivityLog();
        this.mStoppedState = new StoppedState();
        this.mDhcpState = new DhcpState();
        this.mDhcpInitState = new DhcpInitState();
        this.mDhcpSelectingState = new DhcpSelectingState();
        this.mDhcpRequestingState = new DhcpRequestingState();
        this.mDhcpHaveLeaseState = new DhcpHaveLeaseState();
        this.mConfiguringInterfaceState = new ConfiguringInterfaceState();
        this.mDhcpBoundState = new DhcpBoundState();
        this.mDhcpRenewingState = new DhcpRenewingState();
        this.mDhcpRebindingState = new DhcpRebindingState();
        this.mDhcpInitRebootState = new DhcpInitRebootState();
        this.mDhcpRebootingState = new DhcpRebootingState();
        this.mWaitBeforeStartState = new WaitBeforeStartState(this.mDhcpInitState);
        this.mWaitBeforeRenewalState = new WaitBeforeRenewalState(this.mDhcpRenewingState);
        this.mContext = context;
        this.mController = controller;
        this.mIfaceName = iface;
        addState(this.mStoppedState);
        addState(this.mDhcpState);
        addState(this.mDhcpInitState, this.mDhcpState);
        addState(this.mWaitBeforeStartState, this.mDhcpState);
        addState(this.mDhcpSelectingState, this.mDhcpState);
        addState(this.mDhcpRequestingState, this.mDhcpState);
        addState(this.mDhcpHaveLeaseState, this.mDhcpState);
        addState(this.mConfiguringInterfaceState, this.mDhcpHaveLeaseState);
        addState(this.mDhcpBoundState, this.mDhcpHaveLeaseState);
        addState(this.mWaitBeforeRenewalState, this.mDhcpHaveLeaseState);
        addState(this.mDhcpRenewingState, this.mDhcpHaveLeaseState);
        addState(this.mDhcpRebindingState, this.mDhcpHaveLeaseState);
        addState(this.mDhcpInitRebootState, this.mDhcpState);
        addState(this.mDhcpRebootingState, this.mDhcpState);
        setInitialState(this.mStoppedState);
        this.mRandom = new Random();
        this.mKickAlarm = makeWakeupMessage("KICK", CMD_KICK);
        this.mTimeoutAlarm = makeWakeupMessage("TIMEOUT", CMD_TIMEOUT);
        this.mRenewAlarm = makeWakeupMessage("RENEW", CMD_RENEW_DHCP);
        this.mRebindAlarm = makeWakeupMessage("REBIND", CMD_REBIND_DHCP);
        this.mExpiryAlarm = makeWakeupMessage("EXPIRY", CMD_EXPIRE_DHCP);
    }

    public void registerForPreDhcpNotification() {
        this.mRegisteredForPreDhcpNotification = true;
    }

    public static DhcpClient makeDhcpClient(Context context, StateMachine controller, InterfaceParams ifParams) {
        DhcpClient client = new DhcpClient(context, controller, ifParams.name);
        client.mIface = ifParams;
        client.start();
        return client;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean initInterface() {
        if (this.mIface == null) {
            this.mIface = InterfaceParams.getByName(this.mIfaceName);
        }
        if (this.mIface == null) {
            Log.e(TAG, "Can't determine InterfaceParams for " + this.mIfaceName);
            return false;
        }
        this.mHwAddr = this.mIface.macAddr.toByteArray();
        this.mInterfaceBroadcastAddr = new PacketSocketAddress(this.mIface.index, DhcpPacket.ETHER_BROADCAST);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startNewTransaction() {
        this.mTransactionId = this.mRandom.nextInt();
        this.mTransactionStartMillis = SystemClock.elapsedRealtime();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean initSockets() {
        return initPacketSocket() && initUdpSocket();
    }

    private boolean initPacketSocket() {
        try {
            this.mPacketSock = Os.socket(OsConstants.AF_PACKET, OsConstants.SOCK_RAW, OsConstants.ETH_P_IP);
            PacketSocketAddress addr = new PacketSocketAddress((short) OsConstants.ETH_P_IP, this.mIface.index);
            Os.bind(this.mPacketSock, addr);
            NetworkUtils.attachDhcpFilter(this.mPacketSock);
            return true;
        } catch (ErrnoException | SocketException e) {
            Log.e(TAG, "Error creating packet socket", e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean initUdpSocket() {
        int oldTag = TrafficStats.getAndSetThreadStatsTag(-192);
        try {
            this.mUdpSock = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            Os.setsockoptInt(this.mUdpSock, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            Os.setsockoptIfreq(this.mUdpSock, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, this.mIfaceName);
            Os.setsockoptInt(this.mUdpSock, OsConstants.SOL_SOCKET, OsConstants.SO_BROADCAST, 1);
            Os.setsockoptInt(this.mUdpSock, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 0);
            Os.bind(this.mUdpSock, Inet4Address.ANY, 68);
            NetworkUtils.protectFromVpn(this.mUdpSock);
            return true;
        } catch (ErrnoException | SocketException e) {
            Log.e(TAG, "Error creating UDP socket", e);
            return false;
        } finally {
            TrafficStats.setThreadStatsTag(oldTag);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean connectUdpSock(Inet4Address to) {
        try {
            Os.connect(this.mUdpSock, to, 67);
            return true;
        } catch (ErrnoException | SocketException e) {
            Log.e(TAG, "Error connecting UDP socket", e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void closeQuietly(FileDescriptor fd) {
        try {
            IoBridge.closeAndSignalBlockedThreads(fd);
        } catch (IOException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeSockets() {
        closeQuietly(this.mUdpSock);
        closeQuietly(this.mPacketSock);
    }

    /* loaded from: classes.dex */
    class ReceiveThread extends Thread {
        private final byte[] mPacket = new byte[NetworkConstants.ETHER_MTU];
        private volatile boolean mStopped = false;

        ReceiveThread() {
        }

        public void halt() {
            this.mStopped = true;
            DhcpClient.this.closeSockets();
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            Log.d(DhcpClient.TAG, "Receive thread started");
            while (!this.mStopped) {
                try {
                    int length = Os.read(DhcpClient.this.mPacketSock, this.mPacket, 0, this.mPacket.length);
                    DhcpPacket packet = DhcpPacket.decodeFullPacket(this.mPacket, length, 0);
                    Log.d(DhcpClient.TAG, "Received packet: " + packet);
                    DhcpClient.this.sendMessage(DhcpClient.CMD_RECEIVED_PACKET, packet);
                } catch (DhcpPacket.ParseException e) {
                    Log.e(DhcpClient.TAG, "Can't parse packet: " + e.getMessage());
                    if (e.errorCode == DhcpErrorEvent.DHCP_NO_COOKIE) {
                        String data = DhcpPacket.ParseException.class.getName();
                        EventLog.writeEvent(1397638484, "31850211", -1, data);
                    }
                    DhcpClient.this.logError(e.errorCode);
                } catch (ErrnoException | IOException e2) {
                    if (!this.mStopped) {
                        Log.e(DhcpClient.TAG, "Read error", e2);
                        DhcpClient.this.logError(DhcpErrorEvent.RECEIVE_ERROR);
                    }
                }
            }
            Log.d(DhcpClient.TAG, "Receive thread stopped");
        }
    }

    private short getSecs() {
        return (short) ((SystemClock.elapsedRealtime() - this.mTransactionStartMillis) / 1000);
    }

    private boolean transmitPacket(ByteBuffer buf, String description, int encap, Inet4Address to) {
        try {
            if (encap == 0) {
                Log.d(TAG, "Broadcasting " + description);
                Os.sendto(this.mPacketSock, buf.array(), 0, buf.limit(), 0, this.mInterfaceBroadcastAddr);
            } else if (encap == 2 && to.equals(DhcpPacket.INADDR_BROADCAST)) {
                Log.d(TAG, "Broadcasting " + description);
                Os.sendto(this.mUdpSock, buf, 0, to, 67);
            } else {
                Log.d(TAG, String.format("Unicasting %s to %s", description, Os.getpeername(this.mUdpSock)));
                Os.write(this.mUdpSock, buf);
            }
            return true;
        } catch (ErrnoException | IOException e) {
            Log.e(TAG, "Can't send packet: ", e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean sendDiscoverPacket() {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(0, this.mTransactionId, getSecs(), this.mHwAddr, false, REQUESTED_PARAMS);
        return transmitPacket(packet, "DHCPDISCOVER", 0, DhcpPacket.INADDR_BROADCAST);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean sendRequestPacket(Inet4Address clientAddress, Inet4Address requestedAddress, Inet4Address serverAddress, Inet4Address to) {
        int encap = DhcpPacket.INADDR_ANY.equals(clientAddress) ? 0 : 2;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(encap, this.mTransactionId, getSecs(), clientAddress, false, this.mHwAddr, requestedAddress, serverAddress, REQUESTED_PARAMS, null);
        String serverStr = serverAddress != null ? serverAddress.getHostAddress() : null;
        String description = "DHCPREQUEST ciaddr=" + clientAddress.getHostAddress() + " request=" + requestedAddress.getHostAddress() + " serverid=" + serverStr;
        return transmitPacket(packet, description, encap, to);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleLeaseTimers() {
        if (this.mDhcpLeaseExpiry == 0) {
            Log.d(TAG, "Infinite lease, no timer scheduling needed");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long remainingDelay = this.mDhcpLeaseExpiry - now;
        long renewDelay = remainingDelay / 2;
        long rebindDelay = (7 * remainingDelay) / 8;
        this.mRenewAlarm.schedule(now + renewDelay);
        this.mRebindAlarm.schedule(now + rebindDelay);
        this.mExpiryAlarm.schedule(now + remainingDelay);
        Log.d(TAG, "Scheduling renewal in " + (renewDelay / 1000) + "s");
        Log.d(TAG, "Scheduling rebind in " + (rebindDelay / 1000) + "s");
        Log.d(TAG, "Scheduling expiry in " + (remainingDelay / 1000) + "s");
    }

    private void notifySuccess() {
        this.mController.sendMessage((int) CMD_POST_DHCP_ACTION, 1, 0, new DhcpResults(this.mDhcpLease));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyFailure() {
        this.mController.sendMessage((int) CMD_POST_DHCP_ACTION, 2, 0, (Object) null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void acceptDhcpResults(DhcpResults results, String msg) {
        this.mDhcpLease = results;
        this.mOffer = null;
        Log.d(TAG, msg + " lease: " + this.mDhcpLease);
        notifySuccess();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearDhcpState() {
        this.mDhcpLease = null;
        this.mDhcpLeaseExpiry = 0L;
        this.mOffer = null;
    }

    public void doQuit() {
        Log.d(TAG, "doQuit");
        quit();
    }

    protected void onQuitting() {
        Log.d(TAG, "onQuitting");
        this.mController.sendMessage((int) CMD_ON_QUIT);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public abstract class LoggingState extends State {
        private long mEnterTimeMs;

        LoggingState() {
        }

        public void enter() {
            this.mEnterTimeMs = SystemClock.elapsedRealtime();
        }

        public void exit() {
            long durationMs = SystemClock.elapsedRealtime() - this.mEnterTimeMs;
            DhcpClient.this.logState(getName(), (int) durationMs);
        }

        private String messageName(int what) {
            return (String) DhcpClient.sMessageNames.get(what, Integer.toString(what));
        }

        private String messageToString(Message message) {
            long now = SystemClock.uptimeMillis();
            StringBuilder b = new StringBuilder(" ");
            TimeUtils.formatDuration(message.getWhen() - now, b);
            b.append(" ");
            b.append(messageName(message.what));
            b.append(" ");
            b.append(message.arg1);
            b.append(" ");
            b.append(message.arg2);
            b.append(" ");
            b.append(message.obj);
            return b.toString();
        }

        public boolean processMessage(Message message) {
            return false;
        }

        public String getName() {
            return getClass().getSimpleName();
        }
    }

    /* loaded from: classes.dex */
    abstract class WaitBeforeOtherState extends LoggingState {
        protected State mOtherState;

        WaitBeforeOtherState() {
            super();
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            DhcpClient.this.mController.sendMessage((int) DhcpClient.CMD_PRE_DHCP_ACTION);
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public boolean processMessage(Message message) {
            super.processMessage(message);
            if (message.what == 196614) {
                DhcpClient.this.transitionTo(this.mOtherState);
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    class StoppedState extends State {
        StoppedState() {
        }

        public boolean processMessage(Message message) {
            if (message.what == 196609) {
                if (DhcpClient.this.mRegisteredForPreDhcpNotification) {
                    DhcpClient.this.transitionTo(DhcpClient.this.mWaitBeforeStartState);
                    return true;
                }
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    class WaitBeforeStartState extends WaitBeforeOtherState {
        public WaitBeforeStartState(State otherState) {
            super();
            this.mOtherState = otherState;
        }
    }

    /* loaded from: classes.dex */
    class WaitBeforeRenewalState extends WaitBeforeOtherState {
        public WaitBeforeRenewalState(State otherState) {
            super();
            this.mOtherState = otherState;
        }
    }

    /* loaded from: classes.dex */
    class DhcpState extends State {
        DhcpState() {
        }

        public void enter() {
            DhcpClient.this.clearDhcpState();
            if (!DhcpClient.this.initInterface() || !DhcpClient.this.initSockets()) {
                DhcpClient.this.notifyFailure();
                DhcpClient.this.transitionTo(DhcpClient.this.mStoppedState);
                return;
            }
            DhcpClient.this.mReceiveThread = new ReceiveThread();
            DhcpClient.this.mReceiveThread.start();
        }

        public void exit() {
            if (DhcpClient.this.mReceiveThread != null) {
                DhcpClient.this.mReceiveThread.halt();
                DhcpClient.this.mReceiveThread = null;
            }
            DhcpClient.this.clearDhcpState();
        }

        public boolean processMessage(Message message) {
            super.processMessage(message);
            if (message.what == 196610) {
                DhcpClient.this.transitionTo(DhcpClient.this.mStoppedState);
                return true;
            }
            return false;
        }
    }

    public boolean isValidPacket(DhcpPacket packet) {
        int xid = packet.getTransactionId();
        if (xid != this.mTransactionId) {
            Log.d(TAG, "Unexpected transaction ID " + xid + ", expected " + this.mTransactionId);
            return false;
        } else if (!Arrays.equals(packet.getClientMac(), this.mHwAddr)) {
            Log.d(TAG, "MAC addr mismatch: got " + HexDump.toHexString(packet.getClientMac()) + ", expected " + HexDump.toHexString(packet.getClientMac()));
            return false;
        } else {
            return true;
        }
    }

    public void setDhcpLeaseExpiry(DhcpPacket packet) {
        long leaseTimeMillis = packet.getLeaseTimeMillis();
        this.mDhcpLeaseExpiry = leaseTimeMillis > 0 ? SystemClock.elapsedRealtime() + leaseTimeMillis : 0L;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public abstract class PacketRetransmittingState extends LoggingState {
        protected int mTimeout;
        private int mTimer;

        protected abstract void receivePacket(DhcpPacket dhcpPacket);

        protected abstract boolean sendPacket();

        PacketRetransmittingState() {
            super();
            this.mTimeout = 0;
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            initTimer();
            maybeInitTimeout();
            DhcpClient.this.sendMessage(DhcpClient.CMD_KICK);
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public boolean processMessage(Message message) {
            super.processMessage(message);
            switch (message.what) {
                case DhcpClient.CMD_KICK /* 196709 */:
                    sendPacket();
                    scheduleKick();
                    return true;
                case DhcpClient.CMD_RECEIVED_PACKET /* 196710 */:
                    receivePacket((DhcpPacket) message.obj);
                    return true;
                case DhcpClient.CMD_TIMEOUT /* 196711 */:
                    timeout();
                    return true;
                default:
                    return false;
            }
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void exit() {
            super.exit();
            DhcpClient.this.mKickAlarm.cancel();
            DhcpClient.this.mTimeoutAlarm.cancel();
        }

        protected void timeout() {
        }

        protected void initTimer() {
            this.mTimer = DhcpClient.FIRST_TIMEOUT_MS;
        }

        protected int jitterTimer(int baseTimer) {
            int maxJitter = baseTimer / 10;
            int jitter = DhcpClient.this.mRandom.nextInt(2 * maxJitter) - maxJitter;
            return baseTimer + jitter;
        }

        protected void scheduleKick() {
            long now = SystemClock.elapsedRealtime();
            long timeout = jitterTimer(this.mTimer);
            long alarmTime = now + timeout;
            DhcpClient.this.mKickAlarm.schedule(alarmTime);
            this.mTimer *= 2;
            if (this.mTimer > DhcpClient.MAX_TIMEOUT_MS) {
                this.mTimer = DhcpClient.MAX_TIMEOUT_MS;
            }
        }

        protected void maybeInitTimeout() {
            if (this.mTimeout > 0) {
                long alarmTime = SystemClock.elapsedRealtime() + this.mTimeout;
                DhcpClient.this.mTimeoutAlarm.schedule(alarmTime);
            }
        }
    }

    /* loaded from: classes.dex */
    class DhcpInitState extends PacketRetransmittingState {
        public DhcpInitState() {
            super();
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState, android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            DhcpClient.this.startNewTransaction();
            DhcpClient.this.mLastInitEnterTime = SystemClock.elapsedRealtime();
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected boolean sendPacket() {
            return DhcpClient.this.sendDiscoverPacket();
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected void receivePacket(DhcpPacket packet) {
            if (DhcpClient.this.isValidPacket(packet) && (packet instanceof DhcpOfferPacket)) {
                DhcpClient.this.mOffer = packet.toDhcpResults();
                if (DhcpClient.this.mOffer != null) {
                    Log.d(DhcpClient.TAG, "Got pending lease: " + DhcpClient.this.mOffer);
                    DhcpClient.this.transitionTo(DhcpClient.this.mDhcpRequestingState);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    class DhcpSelectingState extends LoggingState {
        DhcpSelectingState() {
            super();
        }
    }

    /* loaded from: classes.dex */
    class DhcpRequestingState extends PacketRetransmittingState {
        public DhcpRequestingState() {
            super();
            this.mTimeout = 18000;
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected boolean sendPacket() {
            return DhcpClient.this.sendRequestPacket(DhcpPacket.INADDR_ANY, (Inet4Address) DhcpClient.this.mOffer.ipAddress.getAddress(), DhcpClient.this.mOffer.serverAddress, DhcpPacket.INADDR_BROADCAST);
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected void receivePacket(DhcpPacket packet) {
            if (DhcpClient.this.isValidPacket(packet)) {
                if (!(packet instanceof DhcpAckPacket)) {
                    if (packet instanceof DhcpNakPacket) {
                        Log.d(DhcpClient.TAG, "Received NAK, returning to INIT");
                        DhcpClient.this.mOffer = null;
                        DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
                        return;
                    }
                    return;
                }
                DhcpResults results = packet.toDhcpResults();
                if (results != null) {
                    DhcpClient.this.setDhcpLeaseExpiry(packet);
                    DhcpClient.this.acceptDhcpResults(results, "Confirmed");
                    DhcpClient.this.transitionTo(DhcpClient.this.mConfiguringInterfaceState);
                }
            }
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected void timeout() {
            DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
        }
    }

    /* loaded from: classes.dex */
    class DhcpHaveLeaseState extends State {
        DhcpHaveLeaseState() {
        }

        public boolean processMessage(Message message) {
            if (message.what == DhcpClient.CMD_EXPIRE_DHCP) {
                Log.d(DhcpClient.TAG, "Lease expired!");
                DhcpClient.this.notifyFailure();
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
                return true;
            }
            return false;
        }

        public void exit() {
            DhcpClient.this.mRenewAlarm.cancel();
            DhcpClient.this.mRebindAlarm.cancel();
            DhcpClient.this.mExpiryAlarm.cancel();
            DhcpClient.this.clearDhcpState();
            DhcpClient.this.mController.sendMessage((int) DhcpClient.CMD_CLEAR_LINKADDRESS);
        }
    }

    /* loaded from: classes.dex */
    class ConfiguringInterfaceState extends LoggingState {
        ConfiguringInterfaceState() {
            super();
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            DhcpClient.this.mController.sendMessage((int) DhcpClient.CMD_CONFIGURE_LINKADDRESS, DhcpClient.this.mDhcpLease.ipAddress);
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public boolean processMessage(Message message) {
            super.processMessage(message);
            if (message.what == 196617) {
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpBoundState);
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    class DhcpBoundState extends LoggingState {
        DhcpBoundState() {
            super();
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            if (DhcpClient.this.mDhcpLease.serverAddress != null && !DhcpClient.this.connectUdpSock(DhcpClient.this.mDhcpLease.serverAddress)) {
                DhcpClient.this.notifyFailure();
                DhcpClient.this.transitionTo(DhcpClient.this.mStoppedState);
            }
            DhcpClient.this.scheduleLeaseTimers();
            logTimeToBoundState();
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public void exit() {
            super.exit();
            DhcpClient.this.mLastBoundExitTime = SystemClock.elapsedRealtime();
        }

        @Override // android.net.dhcp.DhcpClient.LoggingState
        public boolean processMessage(Message message) {
            super.processMessage(message);
            if (message.what == DhcpClient.CMD_RENEW_DHCP) {
                if (DhcpClient.this.mRegisteredForPreDhcpNotification) {
                    DhcpClient.this.transitionTo(DhcpClient.this.mWaitBeforeRenewalState);
                    return true;
                }
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpRenewingState);
                return true;
            }
            return false;
        }

        private void logTimeToBoundState() {
            long now = SystemClock.elapsedRealtime();
            if (DhcpClient.this.mLastBoundExitTime > DhcpClient.this.mLastInitEnterTime) {
                DhcpClient.this.logState("RenewingBoundState", (int) (now - DhcpClient.this.mLastBoundExitTime));
            } else {
                DhcpClient.this.logState("InitialBoundState", (int) (now - DhcpClient.this.mLastInitEnterTime));
            }
        }
    }

    /* loaded from: classes.dex */
    abstract class DhcpReacquiringState extends PacketRetransmittingState {
        protected String mLeaseMsg;

        protected abstract Inet4Address packetDestination();

        DhcpReacquiringState() {
            super();
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState, android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            DhcpClient.this.startNewTransaction();
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected boolean sendPacket() {
            return DhcpClient.this.sendRequestPacket((Inet4Address) DhcpClient.this.mDhcpLease.ipAddress.getAddress(), DhcpPacket.INADDR_ANY, null, packetDestination());
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState
        protected void receivePacket(DhcpPacket packet) {
            if (DhcpClient.this.isValidPacket(packet)) {
                if (!(packet instanceof DhcpAckPacket)) {
                    if (packet instanceof DhcpNakPacket) {
                        Log.d(DhcpClient.TAG, "Received NAK, returning to INIT");
                        DhcpClient.this.notifyFailure();
                        DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
                        return;
                    }
                    return;
                }
                DhcpResults results = packet.toDhcpResults();
                if (results != null) {
                    if (!DhcpClient.this.mDhcpLease.ipAddress.equals(results.ipAddress)) {
                        Log.d(DhcpClient.TAG, "Renewed lease not for our current IP address!");
                        DhcpClient.this.notifyFailure();
                        DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
                    }
                    DhcpClient.this.setDhcpLeaseExpiry(packet);
                    DhcpClient.this.acceptDhcpResults(results, this.mLeaseMsg);
                    DhcpClient.this.transitionTo(DhcpClient.this.mDhcpBoundState);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    class DhcpRenewingState extends DhcpReacquiringState {
        public DhcpRenewingState() {
            super();
            this.mLeaseMsg = "Renewed";
        }

        @Override // android.net.dhcp.DhcpClient.PacketRetransmittingState, android.net.dhcp.DhcpClient.LoggingState
        public boolean processMessage(Message message) {
            if (super.processMessage(message)) {
                return true;
            }
            if (message.what == DhcpClient.CMD_REBIND_DHCP) {
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpRebindingState);
                return true;
            }
            return false;
        }

        @Override // android.net.dhcp.DhcpClient.DhcpReacquiringState
        protected Inet4Address packetDestination() {
            return DhcpClient.this.mDhcpLease.serverAddress != null ? DhcpClient.this.mDhcpLease.serverAddress : DhcpPacket.INADDR_BROADCAST;
        }
    }

    /* loaded from: classes.dex */
    class DhcpRebindingState extends DhcpReacquiringState {
        public DhcpRebindingState() {
            super();
            this.mLeaseMsg = "Rebound";
        }

        @Override // android.net.dhcp.DhcpClient.DhcpReacquiringState, android.net.dhcp.DhcpClient.PacketRetransmittingState, android.net.dhcp.DhcpClient.LoggingState
        public void enter() {
            super.enter();
            DhcpClient.closeQuietly(DhcpClient.this.mUdpSock);
            if (!DhcpClient.this.initUdpSocket()) {
                Log.e(DhcpClient.TAG, "Failed to recreate UDP socket");
                DhcpClient.this.transitionTo(DhcpClient.this.mDhcpInitState);
            }
        }

        @Override // android.net.dhcp.DhcpClient.DhcpReacquiringState
        protected Inet4Address packetDestination() {
            return DhcpPacket.INADDR_BROADCAST;
        }
    }

    /* loaded from: classes.dex */
    class DhcpInitRebootState extends LoggingState {
        DhcpInitRebootState() {
            super();
        }
    }

    /* loaded from: classes.dex */
    class DhcpRebootingState extends LoggingState {
        DhcpRebootingState() {
            super();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logError(int errorCode) {
        this.mMetricsLog.log(this.mIfaceName, new DhcpErrorEvent(errorCode));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logState(String name, int durationMs) {
        this.mMetricsLog.log(this.mIfaceName, new DhcpClientEvent(name, durationMs));
    }
}
