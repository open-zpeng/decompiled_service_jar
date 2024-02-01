package android.net.shared;

import android.net.InetAddresses;
import android.net.InitialConfigurationParcelable;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.text.TextUtils;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/* loaded from: classes.dex */
public class InitialConfiguration {
    public static final InetAddress INET6_ANY = InetAddresses.parseNumericAddress("::");
    private static final int RFC6177_MIN_PREFIX_LENGTH = 48;
    private static final int RFC7421_PREFIX_LENGTH = 64;
    public final Set<LinkAddress> ipAddresses = new HashSet();
    public final Set<IpPrefix> directlyConnectedRoutes = new HashSet();
    public final Set<InetAddress> dnsServers = new HashSet();

    public static InitialConfiguration copy(InitialConfiguration config) {
        if (config == null) {
            return null;
        }
        InitialConfiguration configCopy = new InitialConfiguration();
        configCopy.ipAddresses.addAll(config.ipAddresses);
        configCopy.directlyConnectedRoutes.addAll(config.directlyConnectedRoutes);
        configCopy.dnsServers.addAll(config.dnsServers);
        return configCopy;
    }

    public String toString() {
        return String.format("InitialConfiguration(IPs: {%s}, prefixes: {%s}, DNS: {%s})", TextUtils.join(", ", this.ipAddresses), TextUtils.join(", ", this.directlyConnectedRoutes), TextUtils.join(", ", this.dnsServers));
    }

    public boolean isValid() {
        if (this.ipAddresses.isEmpty()) {
            return false;
        }
        for (final LinkAddress addr : this.ipAddresses) {
            if (!any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$-me_MsIS7iJUFBnz7VXb_bkuZ1g
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean contains;
                    contains = ((IpPrefix) obj).contains(addr.getAddress());
                    return contains;
                }
            })) {
                return false;
            }
        }
        for (final InetAddress addr2 : this.dnsServers) {
            if (!any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$fPcApZyObbcs0b_t0iDOb7uyyVo
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean contains;
                    contains = ((IpPrefix) obj).contains(addr2);
                    return contains;
                }
            })) {
                return false;
            }
        }
        if (any(this.ipAddresses, not(new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$V8CoLWaph9IN8YQg4mdBlxzUFkI
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isPrefixLengthCompliant;
                isPrefixLengthCompliant = InitialConfiguration.isPrefixLengthCompliant((LinkAddress) obj);
                return isPrefixLengthCompliant;
            }
        }))) {
            return false;
        }
        return ((any(this.directlyConnectedRoutes, new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$p_tYzrU5UtVU6wwyLn0miHqz-00
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isIPv6DefaultRoute;
                isIPv6DefaultRoute = InitialConfiguration.isIPv6DefaultRoute((IpPrefix) obj);
                return isIPv6DefaultRoute;
            }
        }) && all(this.ipAddresses, not(new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$w5pXtjcZU54QER7TNMAvC4NSrfg
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isIPv6GUA;
                isIPv6GUA = InitialConfiguration.isIPv6GUA((LinkAddress) obj);
                return isIPv6GUA;
            }
        }))) || any(this.directlyConnectedRoutes, not(new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$GEWT0gKtlkvLtgqIAII3RLQt3xQ
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isPrefixLengthCompliant;
                isPrefixLengthCompliant = InitialConfiguration.isPrefixLengthCompliant((IpPrefix) obj);
                return isPrefixLengthCompliant;
            }
        })) || this.ipAddresses.stream().filter(new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$58qOz8A9XDsHfGLzFKkSY-aJR6w
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isIPv4;
                isIPv4 = InitialConfiguration.isIPv4((LinkAddress) obj);
                return isIPv4;
            }
        }).count() > 1) ? false : true;
    }

    public boolean isProvisionedBy(List<LinkAddress> addresses, List<RouteInfo> routes) {
        if (this.ipAddresses.isEmpty()) {
            return false;
        }
        for (final LinkAddress addr : this.ipAddresses) {
            if (!any(addresses, new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$52iZTx1bKOBJkSzJZ2-XL1b1D8g
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isSameAddressAs;
                    isSameAddressAs = addr.isSameAddressAs((LinkAddress) obj);
                    return isSameAddressAs;
                }
            })) {
                return false;
            }
        }
        if (routes != null) {
            for (final IpPrefix prefix : this.directlyConnectedRoutes) {
                if (!any(routes, new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$4Dtg_2trFtjFoeKOpr0rSIEUXu8
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        boolean isDirectlyConnectedRoute;
                        isDirectlyConnectedRoute = InitialConfiguration.isDirectlyConnectedRoute((RouteInfo) obj, prefix);
                        return isDirectlyConnectedRoute;
                    }
                })) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    public InitialConfigurationParcelable toStableParcelable() {
        InitialConfigurationParcelable p = new InitialConfigurationParcelable();
        p.ipAddresses = (LinkAddress[]) this.ipAddresses.toArray(new LinkAddress[0]);
        p.directlyConnectedRoutes = (IpPrefix[]) this.directlyConnectedRoutes.toArray(new IpPrefix[0]);
        p.dnsServers = (String[]) ParcelableUtil.toParcelableArray(this.dnsServers, $$Lambda$OsobWheG5dMvEj_cOJtueqUBqBI.INSTANCE, String.class);
        return p;
    }

    public static InitialConfiguration fromStableParcelable(InitialConfigurationParcelable p) {
        if (p == null) {
            return null;
        }
        InitialConfiguration config = new InitialConfiguration();
        config.ipAddresses.addAll(Arrays.asList(p.ipAddresses));
        config.directlyConnectedRoutes.addAll(Arrays.asList(p.directlyConnectedRoutes));
        config.dnsServers.addAll(ParcelableUtil.fromParcelableArray(p.dnsServers, $$Lambda$SYWvjOUPlAZ_O2Z6yfFU9np1858.INSTANCE));
        return config;
    }

    public boolean equals(Object obj) {
        if (obj instanceof InitialConfiguration) {
            InitialConfiguration other = (InitialConfiguration) obj;
            return this.ipAddresses.equals(other.ipAddresses) && this.directlyConnectedRoutes.equals(other.directlyConnectedRoutes) && this.dnsServers.equals(other.dnsServers);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isDirectlyConnectedRoute(RouteInfo route, IpPrefix prefix) {
        return !route.hasGateway() && prefix.equals(route.getDestination());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isPrefixLengthCompliant(LinkAddress addr) {
        return isIPv4(addr) || isCompliantIPv6PrefixLength(addr.getPrefixLength());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isPrefixLengthCompliant(IpPrefix prefix) {
        return isIPv4(prefix) || isCompliantIPv6PrefixLength(prefix.getPrefixLength());
    }

    private static boolean isCompliantIPv6PrefixLength(int prefixLength) {
        return 48 <= prefixLength && prefixLength <= 64;
    }

    private static boolean isIPv4(IpPrefix prefix) {
        return prefix.getAddress() instanceof Inet4Address;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isIPv4(LinkAddress addr) {
        return addr.getAddress() instanceof Inet4Address;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isIPv6DefaultRoute(IpPrefix prefix) {
        return prefix.getAddress().equals(INET6_ANY);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isIPv6GUA(LinkAddress addr) {
        return addr.isIpv6() && addr.isGlobalPreferred();
    }

    public static <T> boolean any(Iterable<T> coll, Predicate<T> fn) {
        for (T t : coll) {
            if (fn.test(t)) {
                return true;
            }
        }
        return false;
    }

    public static <T> boolean all(Iterable<T> coll, Predicate<T> fn) {
        return !any(coll, not(fn));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$not$4(Predicate fn, Object t) {
        return !fn.test(t);
    }

    public static <T> Predicate<T> not(final Predicate<T> fn) {
        return new Predicate() { // from class: android.net.shared.-$$Lambda$InitialConfiguration$MwQ3SqOgt4uewKbIAElg1lpdfTI
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return InitialConfiguration.lambda$not$4(fn, obj);
            }
        };
    }
}
