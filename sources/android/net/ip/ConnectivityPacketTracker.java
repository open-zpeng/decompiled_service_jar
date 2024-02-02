package android.net.ip;

import android.net.NetworkUtils;
import android.net.util.ConnectivityPacketSummary;
import android.net.util.InterfaceParams;
import android.net.util.PacketReader;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.PacketSocketAddress;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import libcore.util.HexEncoding;
/* loaded from: classes.dex */
public class ConnectivityPacketTracker {
    private static final boolean DBG = false;
    private static final String MARK_NAMED_START = "--- START (%s) ---";
    private static final String MARK_NAMED_STOP = "--- STOP (%s) ---";
    private static final String MARK_START = "--- START ---";
    private static final String MARK_STOP = "--- STOP ---";
    private static final String TAG = ConnectivityPacketTracker.class.getSimpleName();
    private String mDisplayName;
    private final LocalLog mLog;
    private final PacketReader mPacketListener;
    private boolean mRunning;
    private final String mTag;

    public ConnectivityPacketTracker(Handler h, InterfaceParams ifParams, LocalLog log) {
        if (ifParams == null) {
            throw new IllegalArgumentException("null InterfaceParams");
        }
        this.mTag = TAG + "." + ifParams.name;
        this.mLog = log;
        this.mPacketListener = new PacketListener(h, ifParams);
    }

    public void start(String displayName) {
        this.mRunning = true;
        this.mDisplayName = displayName;
        this.mPacketListener.start();
    }

    public void stop() {
        this.mPacketListener.stop();
        this.mRunning = false;
        this.mDisplayName = null;
    }

    /* loaded from: classes.dex */
    private final class PacketListener extends PacketReader {
        private final InterfaceParams mInterface;

        PacketListener(Handler h, InterfaceParams ifParams) {
            super(h, ifParams.defaultMtu);
            this.mInterface = ifParams;
        }

        @Override // android.net.util.PacketReader
        protected FileDescriptor createFd() {
            FileDescriptor s = null;
            try {
                s = Os.socket(OsConstants.AF_PACKET, OsConstants.SOCK_RAW, 0);
                NetworkUtils.attachControlPacketFilter(s, OsConstants.ARPHRD_ETHER);
                Os.bind(s, new PacketSocketAddress((short) OsConstants.ETH_P_ALL, this.mInterface.index));
                return s;
            } catch (ErrnoException | IOException e) {
                logError("Failed to create packet tracking socket: ", e);
                closeFd(s);
                return null;
            }
        }

        @Override // android.net.util.PacketReader
        protected void handlePacket(byte[] recvbuf, int length) {
            String summary = ConnectivityPacketSummary.summarize(this.mInterface.macAddr, recvbuf, length);
            if (summary == null) {
                return;
            }
            addLogEntry(summary + "\n[" + new String(HexEncoding.encode(recvbuf, 0, length)) + "]");
        }

        @Override // android.net.util.PacketReader
        protected void onStart() {
            String msg;
            if (TextUtils.isEmpty(ConnectivityPacketTracker.this.mDisplayName)) {
                msg = ConnectivityPacketTracker.MARK_START;
            } else {
                msg = String.format(ConnectivityPacketTracker.MARK_NAMED_START, ConnectivityPacketTracker.this.mDisplayName);
            }
            ConnectivityPacketTracker.this.mLog.log(msg);
        }

        @Override // android.net.util.PacketReader
        protected void onStop() {
            String msg;
            if (TextUtils.isEmpty(ConnectivityPacketTracker.this.mDisplayName)) {
                msg = ConnectivityPacketTracker.MARK_STOP;
            } else {
                msg = String.format(ConnectivityPacketTracker.MARK_NAMED_STOP, ConnectivityPacketTracker.this.mDisplayName);
            }
            if (!ConnectivityPacketTracker.this.mRunning) {
                msg = msg + " (packet listener stopped unexpectedly)";
            }
            ConnectivityPacketTracker.this.mLog.log(msg);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.net.util.PacketReader
        public void logError(String msg, Exception e) {
            Log.e(ConnectivityPacketTracker.this.mTag, msg, e);
            addLogEntry(msg + e);
        }

        private void addLogEntry(String entry) {
            ConnectivityPacketTracker.this.mLog.log(entry);
        }
    }
}
