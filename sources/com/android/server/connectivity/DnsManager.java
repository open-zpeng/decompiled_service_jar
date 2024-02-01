package com.android.server.connectivity;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.IDnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.ResolverParamsParcel;
import android.net.Uri;
import android.net.shared.PrivateDnsConfig;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import com.android.server.connectivity.DnsManager;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/* loaded from: classes.dex */
public class DnsManager {
    private static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;
    private static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    private static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    private static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final IDnsResolver mDnsResolver;
    private int mMaxSamples;
    private int mMinSamples;
    private int mNumDnsEntries;
    private String mPrivateDnsMode;
    private String mPrivateDnsSpecifier;
    private int mSampleValidity;
    private int mSuccessThreshold;
    private final MockableSystemProperties mSystemProperties;
    private static final String TAG = DnsManager.class.getSimpleName();
    private static final PrivateDnsConfig PRIVATE_DNS_OFF = new PrivateDnsConfig();
    private final Map<Integer, PrivateDnsConfig> mPrivateDnsMap = new HashMap();
    private final Map<Integer, PrivateDnsValidationStatuses> mPrivateDnsValidationMap = new HashMap();

    public static PrivateDnsConfig getPrivateDnsConfig(ContentResolver cr) {
        String mode = getPrivateDnsMode(cr);
        boolean useTls = (TextUtils.isEmpty(mode) || "off".equals(mode)) ? false : true;
        if ("hostname".equals(mode)) {
            String specifier = getStringSetting(cr, "private_dns_specifier");
            return new PrivateDnsConfig(specifier, null);
        }
        return new PrivateDnsConfig(useTls);
    }

    public static Uri[] getPrivateDnsSettingsUris() {
        return new Uri[]{Settings.Global.getUriFor("private_dns_default_mode"), Settings.Global.getUriFor("private_dns_mode"), Settings.Global.getUriFor("private_dns_specifier")};
    }

    /* loaded from: classes.dex */
    public static class PrivateDnsValidationUpdate {
        public final String hostname;
        public final InetAddress ipAddress;
        public final int netId;
        public final boolean validated;

        public PrivateDnsValidationUpdate(int netId, InetAddress ipAddress, String hostname, boolean validated) {
            this.netId = netId;
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.validated = validated;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PrivateDnsValidationStatuses {
        private Map<Pair<String, InetAddress>, ValidationStatus> mValidationMap;

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes.dex */
        public enum ValidationStatus {
            IN_PROGRESS,
            FAILED,
            SUCCEEDED
        }

        private PrivateDnsValidationStatuses() {
            this.mValidationMap = new HashMap();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean hasValidatedServer() {
            for (ValidationStatus status : this.mValidationMap.values()) {
                if (status == ValidationStatus.SUCCEEDED) {
                    return true;
                }
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateTrackedDnses(String[] ipAddresses, String hostname) {
            Set<Pair<String, InetAddress>> latestDnses = new HashSet<>();
            for (String ipAddress : ipAddresses) {
                try {
                    latestDnses.add(new Pair<>(hostname, InetAddress.parseNumericAddress(ipAddress)));
                } catch (IllegalArgumentException e) {
                }
            }
            Iterator<Map.Entry<Pair<String, InetAddress>, ValidationStatus>> it = this.mValidationMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Pair<String, InetAddress>, ValidationStatus> entry = it.next();
                if (!latestDnses.contains(entry.getKey())) {
                    it.remove();
                }
            }
            for (Pair<String, InetAddress> p : latestDnses) {
                if (!this.mValidationMap.containsKey(p)) {
                    this.mValidationMap.put(p, ValidationStatus.IN_PROGRESS);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateStatus(PrivateDnsValidationUpdate update) {
            Pair<String, InetAddress> p = new Pair<>(update.hostname, update.ipAddress);
            if (!this.mValidationMap.containsKey(p)) {
                return;
            }
            if (update.validated) {
                this.mValidationMap.put(p, ValidationStatus.SUCCEEDED);
            } else {
                this.mValidationMap.put(p, ValidationStatus.FAILED);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public LinkProperties fillInValidatedPrivateDns(final LinkProperties lp) {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
            this.mValidationMap.forEach(new BiConsumer() { // from class: com.android.server.connectivity.-$$Lambda$DnsManager$PrivateDnsValidationStatuses$_X4_M08nKysv-L4hDpqAsa4SBxI
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    DnsManager.PrivateDnsValidationStatuses.lambda$fillInValidatedPrivateDns$0(lp, (Pair) obj, (DnsManager.PrivateDnsValidationStatuses.ValidationStatus) obj2);
                }
            });
            return lp;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$fillInValidatedPrivateDns$0(LinkProperties lp, Pair key, ValidationStatus value) {
            if (value == ValidationStatus.SUCCEEDED) {
                lp.addValidatedPrivateDnsServer((InetAddress) key.second);
            }
        }
    }

    public DnsManager(Context ctx, IDnsResolver dnsResolver, MockableSystemProperties sp) {
        this.mContext = ctx;
        this.mContentResolver = this.mContext.getContentResolver();
        this.mDnsResolver = dnsResolver;
        this.mSystemProperties = sp;
    }

    public PrivateDnsConfig getPrivateDnsConfig() {
        return getPrivateDnsConfig(this.mContentResolver);
    }

    public void removeNetwork(Network network) {
        this.mPrivateDnsMap.remove(Integer.valueOf(network.netId));
        this.mPrivateDnsValidationMap.remove(Integer.valueOf(network.netId));
    }

    public PrivateDnsConfig updatePrivateDns(Network network, PrivateDnsConfig cfg) {
        String str = TAG;
        Slog.w(str, "updatePrivateDns(" + network + ", " + cfg + ")");
        if (cfg != null) {
            return this.mPrivateDnsMap.put(Integer.valueOf(network.netId), cfg);
        }
        return this.mPrivateDnsMap.remove(Integer.valueOf(network.netId));
    }

    public void updatePrivateDnsStatus(int netId, LinkProperties lp) {
        PrivateDnsConfig privateDnsCfg = this.mPrivateDnsMap.getOrDefault(Integer.valueOf(netId), PRIVATE_DNS_OFF);
        boolean useTls = privateDnsCfg.useTls;
        PrivateDnsValidationStatuses statuses = useTls ? this.mPrivateDnsValidationMap.get(Integer.valueOf(netId)) : null;
        boolean usingPrivateDns = true;
        boolean validated = statuses != null && statuses.hasValidatedServer();
        boolean strictMode = privateDnsCfg.inStrictMode();
        String tlsHostname = strictMode ? privateDnsCfg.hostname : null;
        if (!strictMode && !validated) {
            usingPrivateDns = false;
        }
        lp.setUsePrivateDns(usingPrivateDns);
        lp.setPrivateDnsServerName(tlsHostname);
        if (usingPrivateDns && statuses != null) {
            statuses.fillInValidatedPrivateDns(lp);
        } else {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
        }
    }

    public void updatePrivateDnsValidation(PrivateDnsValidationUpdate update) {
        PrivateDnsValidationStatuses statuses = this.mPrivateDnsValidationMap.get(Integer.valueOf(update.netId));
        if (statuses == null) {
            return;
        }
        statuses.updateStatus(update);
    }

    public void setDnsConfigurationForNetwork(int netId, final LinkProperties lp, boolean isDefaultNetwork) {
        String[] strArr;
        updateParametersSettings();
        ResolverParamsParcel paramsParcel = new ResolverParamsParcel();
        PrivateDnsConfig privateDnsCfg = this.mPrivateDnsMap.getOrDefault(Integer.valueOf(netId), PRIVATE_DNS_OFF);
        boolean useTls = privateDnsCfg.useTls;
        boolean strictMode = privateDnsCfg.inStrictMode();
        paramsParcel.netId = netId;
        paramsParcel.sampleValiditySeconds = this.mSampleValidity;
        paramsParcel.successThreshold = this.mSuccessThreshold;
        paramsParcel.minSamples = this.mMinSamples;
        paramsParcel.maxSamples = this.mMaxSamples;
        paramsParcel.servers = NetworkUtils.makeStrings(lp.getDnsServers());
        paramsParcel.domains = getDomainStrings(lp.getDomains());
        paramsParcel.tlsName = strictMode ? privateDnsCfg.hostname : "";
        if (strictMode) {
            strArr = NetworkUtils.makeStrings((Collection) Arrays.stream(privateDnsCfg.ips).filter(new Predicate() { // from class: com.android.server.connectivity.-$$Lambda$DnsManager$Z_oEyRSp0wthIcVTcqKDoAJRe6Q
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isReachable;
                    isReachable = lp.isReachable((InetAddress) obj);
                    return isReachable;
                }
            }).collect(Collectors.toList()));
        } else {
            strArr = useTls ? paramsParcel.servers : new String[0];
        }
        paramsParcel.tlsServers = strArr;
        paramsParcel.tlsFingerprints = new String[0];
        if (useTls) {
            if (!this.mPrivateDnsValidationMap.containsKey(Integer.valueOf(netId))) {
                this.mPrivateDnsValidationMap.put(Integer.valueOf(netId), new PrivateDnsValidationStatuses());
            }
            this.mPrivateDnsValidationMap.get(Integer.valueOf(netId)).updateTrackedDnses(paramsParcel.tlsServers, paramsParcel.tlsName);
        } else {
            this.mPrivateDnsValidationMap.remove(Integer.valueOf(netId));
        }
        Slog.d(TAG, String.format("setDnsConfigurationForNetwork(%d, %s, %s, %d, %d, %d, %d, %d, %d, %s, %s)", Integer.valueOf(paramsParcel.netId), Arrays.toString(paramsParcel.servers), Arrays.toString(paramsParcel.domains), Integer.valueOf(paramsParcel.sampleValiditySeconds), Integer.valueOf(paramsParcel.successThreshold), Integer.valueOf(paramsParcel.minSamples), Integer.valueOf(paramsParcel.maxSamples), Integer.valueOf(paramsParcel.baseTimeoutMsec), Integer.valueOf(paramsParcel.retryCount), paramsParcel.tlsName, Arrays.toString(paramsParcel.tlsServers)));
        try {
            this.mDnsResolver.setResolverConfiguration(paramsParcel);
            if (isDefaultNetwork) {
                setDefaultDnsSystemProperties(lp.getDnsServers());
            }
            flushVmDnsCache();
        } catch (RemoteException | ServiceSpecificException e) {
            String str = TAG;
            Slog.e(str, "Error setting DNS configuration: " + e);
        }
    }

    public void setDefaultDnsSystemProperties(Collection<InetAddress> dnses) {
        int last = 0;
        for (InetAddress dns : dnses) {
            last++;
            setNetDnsProperty(last, dns.getHostAddress());
        }
        for (int i = last + 1; i <= this.mNumDnsEntries; i++) {
            setNetDnsProperty(i, "");
        }
        this.mNumDnsEntries = last;
    }

    private void flushVmDnsCache() {
        Intent intent = new Intent("android.intent.action.CLEAR_DNS_CACHE");
        intent.addFlags(536870912);
        intent.addFlags(67108864);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateParametersSettings() {
        int i;
        this.mSampleValidity = getIntSetting("dns_resolver_sample_validity_seconds", 1800);
        int i2 = this.mSampleValidity;
        if (i2 < 0 || i2 > 65535) {
            String str = TAG;
            Slog.w(str, "Invalid sampleValidity=" + this.mSampleValidity + ", using default=1800");
            this.mSampleValidity = 1800;
        }
        this.mSuccessThreshold = getIntSetting("dns_resolver_success_threshold_percent", 25);
        int i3 = this.mSuccessThreshold;
        if (i3 < 0 || i3 > 100) {
            String str2 = TAG;
            Slog.w(str2, "Invalid successThreshold=" + this.mSuccessThreshold + ", using default=25");
            this.mSuccessThreshold = 25;
        }
        this.mMinSamples = getIntSetting("dns_resolver_min_samples", 8);
        this.mMaxSamples = getIntSetting("dns_resolver_max_samples", 64);
        int i4 = this.mMinSamples;
        if (i4 < 0 || i4 > (i = this.mMaxSamples) || i > 64) {
            String str3 = TAG;
            Slog.w(str3, "Invalid sample count (min, max)=(" + this.mMinSamples + ", " + this.mMaxSamples + "), using default=(8, 64)");
            this.mMinSamples = 8;
            this.mMaxSamples = 64;
        }
    }

    private int getIntSetting(String which, int dflt) {
        return Settings.Global.getInt(this.mContentResolver, which, dflt);
    }

    private void setNetDnsProperty(int which, String value) {
        String key = "net.dns" + which;
        try {
            this.mSystemProperties.set(key, value);
        } catch (Exception e) {
            Slog.e(TAG, "Error setting unsupported net.dns property: ", e);
        }
    }

    private static String getPrivateDnsMode(ContentResolver cr) {
        String mode = getStringSetting(cr, "private_dns_mode");
        if (TextUtils.isEmpty(mode)) {
            mode = getStringSetting(cr, "private_dns_default_mode");
        }
        return TextUtils.isEmpty(mode) ? "opportunistic" : mode;
    }

    private static String getStringSetting(ContentResolver cr, String which) {
        return Settings.Global.getString(cr, which);
    }

    private static String[] getDomainStrings(String domains) {
        return TextUtils.isEmpty(domains) ? new String[0] : domains.split(" ");
    }
}
