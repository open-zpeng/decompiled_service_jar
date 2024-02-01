package android.net.dns;

import android.net.Network;
import android.system.GaiException;
import android.system.OsConstants;
import android.system.StructAddrinfo;
import java.net.InetAddress;
import java.net.UnknownHostException;
import libcore.io.Libcore;
/* loaded from: classes.dex */
public class ResolvUtil {
    private static final long NETID_USE_LOCAL_NAMESERVERS = 2147483648L;

    private ResolvUtil() {
    }

    public static InetAddress[] blockingResolveAllLocally(Network network, String name) throws UnknownHostException {
        return blockingResolveAllLocally(network, name, OsConstants.AI_ADDRCONFIG);
    }

    public static InetAddress[] blockingResolveAllLocally(Network network, String name, int aiFlags) throws UnknownHostException {
        StructAddrinfo hints = new StructAddrinfo();
        hints.ai_flags = aiFlags;
        hints.ai_family = OsConstants.AF_UNSPEC;
        hints.ai_socktype = OsConstants.SOCK_STREAM;
        Network networkForResolv = getNetworkWithUseLocalNameserversFlag(network);
        try {
            return Libcore.os.android_getaddrinfo(name, hints, networkForResolv.netId);
        } catch (GaiException gai) {
            gai.rethrowAsUnknownHostException(name + ": TLS-bypass resolution failed");
            return null;
        }
    }

    public static Network getNetworkWithUseLocalNameserversFlag(Network network) {
        long netidForResolv = network.netId | NETID_USE_LOCAL_NAMESERVERS;
        return new Network((int) netidForResolv);
    }

    public static Network makeNetworkWithPrivateDnsBypass(final Network network) {
        return new Network(network) { // from class: android.net.dns.ResolvUtil.1
            @Override // android.net.Network
            public InetAddress[] getAllByName(String host) throws UnknownHostException {
                return ResolvUtil.blockingResolveAllLocally(network, host);
            }
        };
    }
}
