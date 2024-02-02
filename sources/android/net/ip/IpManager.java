package android.net.ip;

import android.content.Context;
import android.net.Network;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.net.ip.IpClient;
/* loaded from: classes.dex */
public class IpManager extends IpClient {

    /* loaded from: classes.dex */
    public static class Callback extends IpClient.Callback {
    }

    /* loaded from: classes.dex */
    public static class InitialConfiguration extends IpClient.InitialConfiguration {
    }

    /* loaded from: classes.dex */
    public static class ProvisioningConfiguration extends IpClient.ProvisioningConfiguration {
        public ProvisioningConfiguration(IpClient.ProvisioningConfiguration ipcConfig) {
            super(ipcConfig);
        }

        /* loaded from: classes.dex */
        public static class Builder extends IpClient.ProvisioningConfiguration.Builder {
            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withoutIPv4() {
                super.withoutIPv4();
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withoutIPv6() {
                super.withoutIPv6();
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withoutIpReachabilityMonitor() {
                super.withoutIpReachabilityMonitor();
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withPreDhcpAction() {
                super.withPreDhcpAction();
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withPreDhcpAction(int dhcpActionTimeoutMs) {
                super.withPreDhcpAction(dhcpActionTimeoutMs);
                return this;
            }

            public Builder withInitialConfiguration(InitialConfiguration initialConfig) {
                super.withInitialConfiguration((IpClient.InitialConfiguration) initialConfig);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
                super.withStaticConfiguration(staticConfig);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
                super.withApfCapabilities(apfCapabilities);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withProvisioningTimeoutMs(int timeoutMs) {
                super.withProvisioningTimeoutMs(timeoutMs);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withNetwork(Network network) {
                super.withNetwork(network);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public Builder withDisplayName(String displayName) {
                super.withDisplayName(displayName);
                return this;
            }

            @Override // android.net.ip.IpClient.ProvisioningConfiguration.Builder
            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(super.build());
            }
        }
    }

    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    public IpManager(Context context, String ifName, Callback callback) {
        super(context, ifName, callback);
    }

    public void startProvisioning(ProvisioningConfiguration req) {
        super.startProvisioning((IpClient.ProvisioningConfiguration) req);
    }
}
