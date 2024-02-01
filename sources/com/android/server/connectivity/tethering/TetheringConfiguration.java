package com.android.server.connectivity.tethering;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.util.SharedLog;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.xiaopeng.server.input.xpInputActionHandler;
import com.xiaopeng.server.wm.xpWindowManagerService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;

/* loaded from: classes.dex */
public class TetheringConfiguration {
    private final String[] DEFAULT_IPV4_DNS = {"8.8.4.4", "8.8.8.8"};
    public final boolean chooseUpstreamAutomatically;
    public final String[] defaultIPv4DNS;
    public final boolean enableLegacyDhcpServer;
    public final boolean isDunRequired;
    public final String[] legacyDhcpRanges;
    public final Collection<Integer> preferredUpstreamIfaceTypes;
    public final String[] provisioningApp;
    public final String provisioningAppNoUi;
    public final int provisioningCheckPeriod;
    public final int subId;
    public final String[] tetherableBluetoothRegexs;
    public final String[] tetherableUsbRegexs;
    public final String[] tetherableWifiRegexs;
    private static final String TAG = TetheringConfiguration.class.getSimpleName();
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] LEGACY_DHCP_DEFAULT_RANGE = {"192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254", "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254", "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254", "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254"};

    public TetheringConfiguration(Context ctx, SharedLog log, int id) {
        SharedLog configLog = log.forSubComponent(xpWindowManagerService.WindowConfigJson.KEY_CONFIG);
        this.subId = id;
        Resources res = getResources(ctx, this.subId);
        this.tetherableUsbRegexs = getResourceStringArray(res, 17236081);
        this.tetherableWifiRegexs = getResourceStringArray(res, 17236082);
        this.tetherableBluetoothRegexs = getResourceStringArray(res, 17236078);
        this.isDunRequired = checkDunRequired(ctx, this.subId);
        this.chooseUpstreamAutomatically = getResourceBoolean(res, 17891554);
        this.preferredUpstreamIfaceTypes = getUpstreamIfaceTypes(res, this.isDunRequired);
        this.legacyDhcpRanges = getLegacyDhcpRanges(res);
        this.defaultIPv4DNS = copy(this.DEFAULT_IPV4_DNS);
        this.enableLegacyDhcpServer = getEnableLegacyDhcpServer(ctx);
        this.provisioningApp = getResourceStringArray(res, 17236048);
        this.provisioningAppNoUi = getProvisioningAppNoUi(res);
        this.provisioningCheckPeriod = getResourceInteger(res, 17694844, 0);
        configLog.log(toString());
    }

    public boolean isUsb(String iface) {
        return matchesDownstreamRegexs(iface, this.tetherableUsbRegexs);
    }

    public boolean isWifi(String iface) {
        return matchesDownstreamRegexs(iface, this.tetherableWifiRegexs);
    }

    public boolean isBluetooth(String iface) {
        return matchesDownstreamRegexs(iface, this.tetherableBluetoothRegexs);
    }

    public boolean hasMobileHotspotProvisionApp() {
        return !TextUtils.isEmpty(this.provisioningAppNoUi);
    }

    public void dump(PrintWriter pw) {
        pw.print("subId: ");
        pw.println(this.subId);
        dumpStringArray(pw, "tetherableUsbRegexs", this.tetherableUsbRegexs);
        dumpStringArray(pw, "tetherableWifiRegexs", this.tetherableWifiRegexs);
        dumpStringArray(pw, "tetherableBluetoothRegexs", this.tetherableBluetoothRegexs);
        pw.print("isDunRequired: ");
        pw.println(this.isDunRequired);
        pw.print("chooseUpstreamAutomatically: ");
        pw.println(this.chooseUpstreamAutomatically);
        dumpStringArray(pw, "preferredUpstreamIfaceTypes", preferredUpstreamNames(this.preferredUpstreamIfaceTypes));
        dumpStringArray(pw, "legacyDhcpRanges", this.legacyDhcpRanges);
        dumpStringArray(pw, "defaultIPv4DNS", this.defaultIPv4DNS);
        dumpStringArray(pw, "provisioningApp", this.provisioningApp);
        pw.print("provisioningAppNoUi: ");
        pw.println(this.provisioningAppNoUi);
        pw.print("enableLegacyDhcpServer: ");
        pw.println(this.enableLegacyDhcpServer);
    }

    public String toString() {
        StringJoiner sj = new StringJoiner(" ");
        sj.add(String.format("subId:%d", Integer.valueOf(this.subId)));
        sj.add(String.format("tetherableUsbRegexs:%s", makeString(this.tetherableUsbRegexs)));
        sj.add(String.format("tetherableWifiRegexs:%s", makeString(this.tetherableWifiRegexs)));
        sj.add(String.format("tetherableBluetoothRegexs:%s", makeString(this.tetherableBluetoothRegexs)));
        sj.add(String.format("isDunRequired:%s", Boolean.valueOf(this.isDunRequired)));
        sj.add(String.format("chooseUpstreamAutomatically:%s", Boolean.valueOf(this.chooseUpstreamAutomatically)));
        sj.add(String.format("preferredUpstreamIfaceTypes:%s", makeString(preferredUpstreamNames(this.preferredUpstreamIfaceTypes))));
        sj.add(String.format("provisioningApp:%s", makeString(this.provisioningApp)));
        sj.add(String.format("provisioningAppNoUi:%s", this.provisioningAppNoUi));
        sj.add(String.format("enableLegacyDhcpServer:%s", Boolean.valueOf(this.enableLegacyDhcpServer)));
        return String.format("TetheringConfiguration{%s}", sj.toString());
    }

    private static void dumpStringArray(PrintWriter pw, String label, String[] values) {
        pw.print(label);
        pw.print(": ");
        if (values != null) {
            StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (String value : values) {
                sj.add(value);
            }
            pw.print(sj.toString());
        } else {
            pw.print("null");
        }
        pw.println();
    }

    private static String makeString(String[] strings) {
        if (strings == null) {
            return "null";
        }
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String s : strings) {
            sj.add(s);
        }
        return sj.toString();
    }

    private static String[] preferredUpstreamNames(Collection<Integer> upstreamTypes) {
        String[] upstreamNames = null;
        if (upstreamTypes != null) {
            upstreamNames = new String[upstreamTypes.size()];
            int i = 0;
            for (Integer netType : upstreamTypes) {
                upstreamNames[i] = ConnectivityManager.getNetworkTypeName(netType.intValue());
                i++;
            }
        }
        return upstreamNames;
    }

    public static boolean checkDunRequired(Context ctx, int id) {
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(xpInputActionHandler.MODE_PHONE);
        if (tm != null) {
            return tm.getTetherApnRequired(id);
        }
        return false;
    }

    /* JADX WARN: Code restructure failed: missing block: B:8:0x0034, code lost:
        if (r8 != 5) goto L8;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static java.util.Collection<java.lang.Integer> getUpstreamIfaceTypes(android.content.res.Resources r12, boolean r13) {
        /*
            r0 = 17236080(0x1070070, float:2.4795898E-38)
            int[] r0 = r12.getIntArray(r0)
            java.util.ArrayList r1 = new java.util.ArrayList
            int r2 = r0.length
            r1.<init>(r2)
            int r2 = r0.length
            r3 = 0
            java.lang.Integer r4 = java.lang.Integer.valueOf(r3)
            r5 = r3
        L14:
            r6 = 4
            r7 = 5
            if (r5 >= r2) goto L47
            r8 = r0[r5]
            java.lang.String r9 = com.android.server.connectivity.tethering.TetheringConfiguration.TAG
            java.lang.StringBuilder r10 = new java.lang.StringBuilder
            r10.<init>()
            java.lang.String r11 = "getUpstreamIfaceTypes : "
            r10.append(r11)
            r10.append(r8)
            java.lang.String r10 = r10.toString()
            android.util.Log.i(r9, r10)
            if (r8 == 0) goto L3a
            if (r8 == r6) goto L37
            if (r8 == r7) goto L3a
            goto L3d
        L37:
            if (r13 != 0) goto L3d
            goto L44
        L3a:
            if (r13 == 0) goto L3d
            goto L44
        L3d:
            java.lang.Integer r6 = java.lang.Integer.valueOf(r8)
            r1.add(r6)
        L44:
            int r5 = r5 + 1
            goto L14
        L47:
            r2 = 1
            if (r13 == 0) goto L4e
            appendIfNotPresent(r1, r6)
            goto L69
        L4e:
            r5 = 2
            java.lang.Integer[] r5 = new java.lang.Integer[r5]
            r5[r3] = r4
            java.lang.Integer r3 = java.lang.Integer.valueOf(r7)
            r5[r2] = r3
            boolean r3 = containsOneOf(r1, r5)
            if (r3 != 0) goto L69
            r1.add(r4)
            java.lang.Integer r3 = java.lang.Integer.valueOf(r7)
            r1.add(r3)
        L69:
            r3 = 9
            prependIfNotPresent(r1, r3)
            setDefaultUpstream(r1, r2)
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.connectivity.tethering.TetheringConfiguration.getUpstreamIfaceTypes(android.content.res.Resources, boolean):java.util.Collection");
    }

    private static boolean matchesDownstreamRegexs(String iface, String[] regexs) {
        for (String regex : regexs) {
            if (iface.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String[] getLegacyDhcpRanges(Resources res) {
        String[] fromResource = getResourceStringArray(res, 17236079);
        if (fromResource.length > 0 && fromResource.length % 2 == 0) {
            return fromResource;
        }
        return copy(LEGACY_DHCP_DEFAULT_RANGE);
    }

    private static String getProvisioningAppNoUi(Resources res) {
        try {
            return res.getString(17039762);
        } catch (Resources.NotFoundException e) {
            return "";
        }
    }

    private static boolean getResourceBoolean(Resources res, int resId) {
        try {
            return res.getBoolean(resId);
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    private static String[] getResourceStringArray(Resources res, int resId) {
        try {
            String[] strArray = res.getStringArray(resId);
            return strArray != null ? strArray : EMPTY_STRING_ARRAY;
        } catch (Resources.NotFoundException e) {
            return EMPTY_STRING_ARRAY;
        }
    }

    private static int getResourceInteger(Resources res, int resId, int defaultValue) {
        try {
            return res.getInteger(resId);
        } catch (Resources.NotFoundException e) {
            return defaultValue;
        }
    }

    private static boolean getEnableLegacyDhcpServer(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int intVal = Settings.Global.getInt(cr, "tether_enable_legacy_dhcp_server", 0);
        return intVal != 0;
    }

    private Resources getResources(Context ctx, int subId) {
        if (subId != -1) {
            return getResourcesForSubIdWrapper(ctx, subId);
        }
        return ctx.getResources();
    }

    @VisibleForTesting
    protected Resources getResourcesForSubIdWrapper(Context ctx, int subId) {
        return SubscriptionManager.getResourcesForSubId(ctx, subId);
    }

    private static String[] copy(String[] strarray) {
        return (String[]) Arrays.copyOf(strarray, strarray.length);
    }

    private static void prependIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(Integer.valueOf(value))) {
            return;
        }
        list.add(0, Integer.valueOf(value));
    }

    private static void setDefaultUpstream(ArrayList<Integer> list, int value) {
        if (list.contains(Integer.valueOf(value))) {
            list.remove(Integer.valueOf(value));
        }
        list.add(0, Integer.valueOf(value));
    }

    private static void appendIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(Integer.valueOf(value))) {
            return;
        }
        list.add(Integer.valueOf(value));
    }

    private static boolean containsOneOf(ArrayList<Integer> list, Integer... values) {
        for (Integer value : values) {
            if (list.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
