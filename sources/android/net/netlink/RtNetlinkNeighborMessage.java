package android.net.netlink;

import android.system.OsConstants;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes.dex */
public class RtNetlinkNeighborMessage extends NetlinkMessage {
    public static final short NDA_CACHEINFO = 3;
    public static final short NDA_DST = 1;
    public static final short NDA_IFINDEX = 8;
    public static final short NDA_LLADDR = 2;
    public static final short NDA_MASTER = 9;
    public static final short NDA_PORT = 6;
    public static final short NDA_PROBES = 4;
    public static final short NDA_UNSPEC = 0;
    public static final short NDA_VLAN = 5;
    public static final short NDA_VNI = 7;
    private StructNdaCacheInfo mCacheInfo;
    private InetAddress mDestination;
    private byte[] mLinkLayerAddr;
    private StructNdMsg mNdmsg;
    private int mNumProbes;

    private static StructNlAttr findNextAttrOfType(short attrType, ByteBuffer byteBuffer) {
        while (byteBuffer != null && byteBuffer.remaining() > 0) {
            StructNlAttr nlAttr = StructNlAttr.peek(byteBuffer);
            if (nlAttr != null) {
                if (nlAttr.nla_type == attrType) {
                    return StructNlAttr.parse(byteBuffer);
                }
                if (byteBuffer.remaining() >= nlAttr.getAlignedLength()) {
                    byteBuffer.position(byteBuffer.position() + nlAttr.getAlignedLength());
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        return null;
    }

    public static RtNetlinkNeighborMessage parse(StructNlMsgHdr header, ByteBuffer byteBuffer) {
        RtNetlinkNeighborMessage neighMsg = new RtNetlinkNeighborMessage(header);
        neighMsg.mNdmsg = StructNdMsg.parse(byteBuffer);
        if (neighMsg.mNdmsg == null) {
            return null;
        }
        int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = findNextAttrOfType((short) 1, byteBuffer);
        if (nlAttr != null) {
            neighMsg.mDestination = nlAttr.getValueAsInetAddress();
        }
        byteBuffer.position(baseOffset);
        StructNlAttr nlAttr2 = findNextAttrOfType((short) 2, byteBuffer);
        if (nlAttr2 != null) {
            neighMsg.mLinkLayerAddr = nlAttr2.nla_value;
        }
        byteBuffer.position(baseOffset);
        StructNlAttr nlAttr3 = findNextAttrOfType((short) 4, byteBuffer);
        if (nlAttr3 != null) {
            neighMsg.mNumProbes = nlAttr3.getValueAsInt(0);
        }
        byteBuffer.position(baseOffset);
        StructNlAttr nlAttr4 = findNextAttrOfType((short) 3, byteBuffer);
        if (nlAttr4 != null) {
            neighMsg.mCacheInfo = StructNdaCacheInfo.parse(nlAttr4.getValueAsByteBuffer());
        }
        int kAdditionalSpace = NetlinkConstants.alignedLengthOf(neighMsg.mHeader.nlmsg_len - 28);
        if (byteBuffer.remaining() < kAdditionalSpace) {
            byteBuffer.position(byteBuffer.limit());
        } else {
            byteBuffer.position(baseOffset + kAdditionalSpace);
        }
        return neighMsg;
    }

    public static byte[] newGetNeighborsRequest(int seqNo) {
        byte[] bytes = new byte[28];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_len = 28;
        nlmsghdr.nlmsg_type = (short) 30;
        nlmsghdr.nlmsg_flags = (short) 769;
        nlmsghdr.nlmsg_seq = seqNo;
        nlmsghdr.pack(byteBuffer);
        StructNdMsg ndmsg = new StructNdMsg();
        ndmsg.pack(byteBuffer);
        return bytes;
    }

    public static byte[] newNewNeighborMessage(int seqNo, InetAddress ip, short nudState, int ifIndex, byte[] llAddr) {
        StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_type = (short) 28;
        nlmsghdr.nlmsg_flags = (short) 261;
        nlmsghdr.nlmsg_seq = seqNo;
        RtNetlinkNeighborMessage msg = new RtNetlinkNeighborMessage(nlmsghdr);
        msg.mNdmsg = new StructNdMsg();
        msg.mNdmsg.ndm_family = (byte) (ip instanceof Inet6Address ? OsConstants.AF_INET6 : OsConstants.AF_INET);
        StructNdMsg structNdMsg = msg.mNdmsg;
        structNdMsg.ndm_ifindex = ifIndex;
        structNdMsg.ndm_state = nudState;
        msg.mDestination = ip;
        msg.mLinkLayerAddr = llAddr;
        byte[] bytes = new byte[msg.getRequiredSpace()];
        nlmsghdr.nlmsg_len = bytes.length;
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        msg.pack(byteBuffer);
        return bytes;
    }

    private RtNetlinkNeighborMessage(StructNlMsgHdr header) {
        super(header);
        this.mNdmsg = null;
        this.mDestination = null;
        this.mLinkLayerAddr = null;
        this.mNumProbes = 0;
        this.mCacheInfo = null;
    }

    public StructNdMsg getNdHeader() {
        return this.mNdmsg;
    }

    public InetAddress getDestination() {
        return this.mDestination;
    }

    public byte[] getLinkLayerAddress() {
        return this.mLinkLayerAddr;
    }

    public int getProbes() {
        return this.mNumProbes;
    }

    public StructNdaCacheInfo getCacheInfo() {
        return this.mCacheInfo;
    }

    public int getRequiredSpace() {
        InetAddress inetAddress = this.mDestination;
        int spaceRequired = inetAddress != null ? 28 + NetlinkConstants.alignedLengthOf(inetAddress.getAddress().length + 4) : 28;
        byte[] bArr = this.mLinkLayerAddr;
        if (bArr != null) {
            return spaceRequired + NetlinkConstants.alignedLengthOf(bArr.length + 4);
        }
        return spaceRequired;
    }

    private static void packNlAttr(short nlType, byte[] nlValue, ByteBuffer byteBuffer) {
        StructNlAttr nlAttr = new StructNlAttr();
        nlAttr.nla_type = nlType;
        nlAttr.nla_value = nlValue;
        nlAttr.nla_len = (short) (nlAttr.nla_value.length + 4);
        nlAttr.pack(byteBuffer);
    }

    public void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);
        this.mNdmsg.pack(byteBuffer);
        InetAddress inetAddress = this.mDestination;
        if (inetAddress != null) {
            packNlAttr((short) 1, inetAddress.getAddress(), byteBuffer);
        }
        byte[] bArr = this.mLinkLayerAddr;
        if (bArr != null) {
            packNlAttr((short) 2, bArr, byteBuffer);
        }
    }

    @Override // android.net.netlink.NetlinkMessage
    public String toString() {
        InetAddress inetAddress = this.mDestination;
        String ipLiteral = inetAddress == null ? "" : inetAddress.getHostAddress();
        StringBuilder sb = new StringBuilder();
        sb.append("RtNetlinkNeighborMessage{ nlmsghdr{");
        sb.append(this.mHeader == null ? "" : this.mHeader.toString());
        sb.append("}, ndmsg{");
        StructNdMsg structNdMsg = this.mNdmsg;
        sb.append(structNdMsg == null ? "" : structNdMsg.toString());
        sb.append("}, destination{");
        sb.append(ipLiteral);
        sb.append("} linklayeraddr{");
        sb.append(NetlinkConstants.hexify(this.mLinkLayerAddr));
        sb.append("} probes{");
        sb.append(this.mNumProbes);
        sb.append("} cacheinfo{");
        StructNdaCacheInfo structNdaCacheInfo = this.mCacheInfo;
        sb.append(structNdaCacheInfo != null ? structNdaCacheInfo.toString() : "");
        sb.append("} }");
        return sb.toString();
    }
}
