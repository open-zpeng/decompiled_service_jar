package android.net.netlink;

import android.system.OsConstants;
import com.android.internal.util.HexDump;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.wm.WindowManagerService;
import java.nio.ByteBuffer;

/* loaded from: classes.dex */
public class NetlinkConstants {
    public static final int NLA_ALIGNTO = 4;
    public static final short NLMSG_DONE = 3;
    public static final short NLMSG_ERROR = 2;
    public static final short NLMSG_MAX_RESERVED = 15;
    public static final short NLMSG_NOOP = 1;
    public static final short NLMSG_OVERRUN = 4;
    public static final short RTM_DELADDR = 21;
    public static final short RTM_DELLINK = 17;
    public static final short RTM_DELNEIGH = 29;
    public static final short RTM_DELROUTE = 25;
    public static final short RTM_DELRULE = 33;
    public static final short RTM_GETADDR = 22;
    public static final short RTM_GETLINK = 18;
    public static final short RTM_GETNEIGH = 30;
    public static final short RTM_GETROUTE = 26;
    public static final short RTM_GETRULE = 34;
    public static final short RTM_NEWADDR = 20;
    public static final short RTM_NEWLINK = 16;
    public static final short RTM_NEWNDUSEROPT = 68;
    public static final short RTM_NEWNEIGH = 28;
    public static final short RTM_NEWROUTE = 24;
    public static final short RTM_NEWRULE = 32;
    public static final short RTM_SETLINK = 19;
    public static final short SOCK_DIAG_BY_FAMILY = 20;

    private NetlinkConstants() {
    }

    public static final int alignedLengthOf(short length) {
        int intLength = 65535 & length;
        return alignedLengthOf(intLength);
    }

    public static final int alignedLengthOf(int length) {
        if (length <= 0) {
            return 0;
        }
        return (((length + 4) - 1) / 4) * 4;
    }

    public static String stringForAddressFamily(int family) {
        return family == OsConstants.AF_INET ? "AF_INET" : family == OsConstants.AF_INET6 ? "AF_INET6" : family == OsConstants.AF_NETLINK ? "AF_NETLINK" : String.valueOf(family);
    }

    public static String stringForProtocol(int protocol) {
        return protocol == OsConstants.IPPROTO_TCP ? "IPPROTO_TCP" : protocol == OsConstants.IPPROTO_UDP ? "IPPROTO_UDP" : String.valueOf(protocol);
    }

    public static String hexify(byte[] bytes) {
        return bytes == null ? "(null)" : HexDump.toHexString(bytes);
    }

    public static String hexify(ByteBuffer buffer) {
        return buffer == null ? "(null)" : HexDump.toHexString(buffer.array(), buffer.position(), buffer.remaining());
    }

    public static String stringForNlMsgType(short nlm_type) {
        if (nlm_type != 1) {
            if (nlm_type != 2) {
                if (nlm_type != 3) {
                    if (nlm_type != 4) {
                        if (nlm_type != 68) {
                            switch (nlm_type) {
                                case 16:
                                    return "RTM_NEWLINK";
                                case 17:
                                    return "RTM_DELLINK";
                                case 18:
                                    return "RTM_GETLINK";
                                case 19:
                                    return "RTM_SETLINK";
                                case 20:
                                    return "RTM_NEWADDR";
                                case 21:
                                    return "RTM_DELADDR";
                                case 22:
                                    return "RTM_GETADDR";
                                default:
                                    switch (nlm_type) {
                                        case WindowManagerService.H.WAITING_FOR_DRAWN_TIMEOUT /* 24 */:
                                            return "RTM_NEWROUTE";
                                        case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                                            return "RTM_DELROUTE";
                                        case 26:
                                            return "RTM_GETROUTE";
                                        default:
                                            switch (nlm_type) {
                                                case 28:
                                                    return "RTM_NEWNEIGH";
                                                case HdmiCecKeycode.CEC_KEYCODE_NUMBER_ENTRY_MODE /* 29 */:
                                                    return "RTM_DELNEIGH";
                                                case 30:
                                                    return "RTM_GETNEIGH";
                                                default:
                                                    switch (nlm_type) {
                                                        case 32:
                                                            return "RTM_NEWRULE";
                                                        case 33:
                                                            return "RTM_DELRULE";
                                                        case 34:
                                                            return "RTM_GETRULE";
                                                        default:
                                                            return "unknown RTM type: " + String.valueOf((int) nlm_type);
                                                    }
                                            }
                                    }
                            }
                        }
                        return "RTM_NEWNDUSEROPT";
                    }
                    return "NLMSG_OVERRUN";
                }
                return "NLMSG_DONE";
            }
            return "NLMSG_ERROR";
        }
        return "NLMSG_NOOP";
    }
}
