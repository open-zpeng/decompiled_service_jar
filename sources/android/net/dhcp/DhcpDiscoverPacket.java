package android.net.dhcp;

import android.net.util.NetworkConstants;
import java.nio.ByteBuffer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class DhcpDiscoverPacket extends DhcpPacket {
    /* JADX INFO: Access modifiers changed from: package-private */
    public DhcpDiscoverPacket(int transId, short secs, byte[] clientMac, boolean broadcast) {
        super(transId, secs, INADDR_ANY, INADDR_ANY, INADDR_ANY, INADDR_ANY, clientMac, broadcast);
    }

    @Override // android.net.dhcp.DhcpPacket
    public String toString() {
        String s = super.toString();
        StringBuilder sb = new StringBuilder();
        sb.append(s);
        sb.append(" DISCOVER ");
        sb.append(this.mBroadcast ? "broadcast " : "unicast ");
        return sb.toString();
    }

    @Override // android.net.dhcp.DhcpPacket
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(NetworkConstants.ETHER_MTU);
        fillInPacket(encap, INADDR_BROADCAST, INADDR_ANY, destUdp, srcUdp, result, (byte) 1, this.mBroadcast);
        result.flip();
        return result;
    }

    @Override // android.net.dhcp.DhcpPacket
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, (byte) 53, (byte) 1);
        addTlv(buffer, (byte) 61, getClientId());
        addCommonClientTlvs(buffer);
        addTlv(buffer, (byte) 55, this.mRequestedParams);
        addTlvEnd(buffer);
    }
}
