package android.net.netlink;

import android.net.util.SocketUtils;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes.dex */
public class InetDiagMessage extends NetlinkMessage {
    private static final int[] FAMILY = {OsConstants.AF_INET6, OsConstants.AF_INET};
    public static final String TAG = "InetDiagMessage";
    private static final int TIMEOUT_MS = 500;
    public StructInetDiagMsg mStructInetDiagMsg;

    public static byte[] InetDiagReqV2(int protocol, InetSocketAddress local, InetSocketAddress remote, int family, short flags) {
        byte[] bytes = new byte[72];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        StructNlMsgHdr nlMsgHdr = new StructNlMsgHdr();
        nlMsgHdr.nlmsg_len = bytes.length;
        nlMsgHdr.nlmsg_type = (short) 20;
        nlMsgHdr.nlmsg_flags = flags;
        nlMsgHdr.pack(byteBuffer);
        StructInetDiagReqV2 inetDiagReqV2 = new StructInetDiagReqV2(protocol, local, remote, family);
        inetDiagReqV2.pack(byteBuffer);
        return bytes;
    }

    private InetDiagMessage(StructNlMsgHdr header) {
        super(header);
        this.mStructInetDiagMsg = new StructInetDiagMsg();
    }

    public static InetDiagMessage parse(StructNlMsgHdr header, ByteBuffer byteBuffer) {
        InetDiagMessage msg = new InetDiagMessage(header);
        msg.mStructInetDiagMsg = StructInetDiagMsg.parse(byteBuffer);
        return msg;
    }

    private static int lookupUidByFamily(int protocol, InetSocketAddress local, InetSocketAddress remote, int family, short flags, FileDescriptor fd) throws ErrnoException, InterruptedIOException {
        byte[] msg = InetDiagReqV2(protocol, local, remote, family, flags);
        NetlinkSocket.sendMessage(fd, msg, 0, msg.length, 500L);
        ByteBuffer response = NetlinkSocket.recvMessage(fd, 8192, 500L);
        NetlinkMessage nlMsg = NetlinkMessage.parse(response);
        StructNlMsgHdr hdr = nlMsg.getHeader();
        if (hdr.nlmsg_type != 3 && (nlMsg instanceof InetDiagMessage)) {
            return ((InetDiagMessage) nlMsg).mStructInetDiagMsg.idiag_uid;
        }
        return -1;
    }

    private static int lookupUid(int protocol, InetSocketAddress local, InetSocketAddress remote, FileDescriptor fd) throws ErrnoException, InterruptedIOException {
        int[] iArr;
        int uid;
        for (int family : FAMILY) {
            if (protocol == OsConstants.IPPROTO_UDP) {
                uid = lookupUidByFamily(protocol, remote, local, family, (short) 1, fd);
            } else {
                uid = lookupUidByFamily(protocol, local, remote, family, (short) 1, fd);
            }
            if (uid != -1) {
                return uid;
            }
        }
        if (protocol == OsConstants.IPPROTO_UDP) {
            try {
                InetSocketAddress wildcard = new InetSocketAddress(Inet6Address.getByName("::"), 0);
                int uid2 = lookupUidByFamily(protocol, local, wildcard, OsConstants.AF_INET6, (short) 769, fd);
                if (uid2 != -1) {
                    return uid2;
                }
                InetSocketAddress wildcard2 = new InetSocketAddress(Inet4Address.getByName("0.0.0.0"), 0);
                int uid3 = lookupUidByFamily(protocol, local, wildcard2, OsConstants.AF_INET, (short) 769, fd);
                if (uid3 != -1) {
                    return uid3;
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, e.toString());
            }
        }
        return -1;
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:9:0x001a -> B:24:0x0033). Please submit an issue!!! */
    public static int getConnectionOwnerUid(int protocol, InetSocketAddress local, InetSocketAddress remote) {
        int uid = -1;
        FileDescriptor fd = null;
        try {
            try {
                try {
                    fd = NetlinkSocket.forProto(OsConstants.NETLINK_INET_DIAG);
                    NetlinkSocket.connectToKernel(fd);
                    uid = lookupUid(protocol, local, remote, fd);
                    if (fd != null) {
                        SocketUtils.closeSocket(fd);
                    }
                } catch (Throwable th) {
                    if (fd != null) {
                        try {
                            SocketUtils.closeSocket(fd);
                        } catch (IOException e) {
                            Log.e(TAG, e.toString());
                        }
                    }
                    throw th;
                }
            } catch (ErrnoException | InterruptedIOException | IllegalArgumentException | SocketException e2) {
                Log.e(TAG, e2.toString());
                if (fd != null) {
                    SocketUtils.closeSocket(fd);
                }
            }
        } catch (IOException e3) {
            Log.e(TAG, e3.toString());
        }
        return uid;
    }

    @Override // android.net.netlink.NetlinkMessage
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("InetDiagMessage{ nlmsghdr{");
        sb.append(this.mHeader == null ? "" : this.mHeader.toString());
        sb.append("}, inet_diag_msg{");
        StructInetDiagMsg structInetDiagMsg = this.mStructInetDiagMsg;
        sb.append(structInetDiagMsg != null ? structInetDiagMsg.toString() : "");
        sb.append("} }");
        return sb.toString();
    }
}
