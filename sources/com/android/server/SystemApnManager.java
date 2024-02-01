package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.INetd;
import android.net.IpPrefix;
import android.net.util.NetdService;
import android.util.Slog;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public final class SystemApnManager {
    private static final String PRIMARY_APPS_CONFIG_FILE = "/system/etc/system_apn_apps.conf";
    private static final int SYSTEM_APN_OEM_ID = 14;
    private static final String TAG = SystemApnManager.class.getSimpleName();
    private static final String UPDATED_APPS_CONFIG_FILE = "/data/etc/system_apn_apps.conf";
    private Context mContext;
    private INetd mNetd = NetdService.getInstance();

    public SystemApnManager(Context context) {
        this.mContext = context;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isSystemApnConfigExist() {
        File primaryFile = new File(PRIMARY_APPS_CONFIG_FILE);
        File file = new File(UPDATED_APPS_CONFIG_FILE);
        return primaryFile.isFile() || file.isFile();
    }

    public void addSystemApnRoute(String iface, String gateway, String secondIp) {
        String str = TAG;
        Slog.d(str, "addSystemApnRoute(): " + iface);
        try {
            IpPrefix dst = new IpPrefix("0.0.0.0/0");
            this.mNetd.networkAddRoute(14, iface, dst.toString(), gateway);
        } catch (Exception e) {
            String str2 = TAG;
            Slog.e(str2, "fail to add default route to system apn table for " + iface);
            e.printStackTrace();
        }
        try {
            this.mNetd.interfaceAddAddress(iface, secondIp, 24);
        } catch (Exception e2) {
            String str3 = TAG;
            Slog.e(str3, "fail to add secondary ip to " + iface);
            e2.printStackTrace();
        }
    }

    public void removeSystemApnRoute(String iface, String gateway, String secondIp) {
        String str = TAG;
        Slog.d(str, "removeSystemApnRoute(): " + iface);
        try {
            IpPrefix dst = new IpPrefix("0.0.0.0/0");
            this.mNetd.networkRemoveRoute(14, iface, dst.toString(), gateway);
        } catch (Exception e) {
            Slog.e(TAG, "fail to remove route for system apn table");
        }
        try {
            this.mNetd.interfaceDelAddress(iface, secondIp, 24);
        } catch (Exception e2) {
            String str2 = TAG;
            Slog.e(str2, "fail to del second ip address for " + iface);
        }
    }

    public void addSystemApnNatRule(String iface, String natIpAddr, int mark, int gid) {
        String str = TAG;
        Slog.d(str, "addSystemApnNatRule(): gid = " + gid);
        try {
            this.mNetd.addSystemApnNatRule(iface, mark, natIpAddr, gid);
        } catch (Exception e) {
            String str2 = TAG;
            Slog.e(str2, "fail to add system apn nat rule for gid " + gid);
        }
    }

    public void removeSystemApnNatRule(String iface, String natIpAddr, int mark, int gid) {
        String str = TAG;
        Slog.d(str, "removeSystemApnNatRule(): gid = " + gid);
        try {
            this.mNetd.removeSystemApnNatRule(iface, mark, natIpAddr, gid);
        } catch (Exception e) {
            Slog.e(TAG, "fail to remove system apn nat rule");
        }
    }

    public Set<Integer> getSystemApnAppGids() {
        File config = new File(UPDATED_APPS_CONFIG_FILE);
        File primary = new File(PRIMARY_APPS_CONFIG_FILE);
        List<String> packageNames = new ArrayList<>();
        if (config.isFile()) {
            List<String> packages = getSystemApnAppPackageNames(config);
            packageNames.addAll(packages);
        } else if (primary.isFile()) {
            List<String> packages2 = getSystemApnAppPackageNames(primary);
            packageNames.addAll(packages2);
        }
        Map<String, Integer> appGidMap = new HashMap<>();
        for (String name : packageNames) {
            PackageManager pm = this.mContext.getPackageManager();
            try {
                int uid = pm.getPackageUid(name, 0);
                String str = TAG;
                Slog.d(str, "app " + name + " uid = " + uid);
                appGidMap.put(name, Integer.valueOf(uid));
            } catch (PackageManager.NameNotFoundException e) {
                String str2 = TAG;
                Slog.e(str2, "getSystemApnAppGids():package " + name + " is not found");
            }
        }
        return new HashSet(appGidMap.values());
    }

    private List<String> getSystemApnAppPackageNames(File configFile) {
        String line;
        Slog.d(TAG, "getSystemApnAppPackageNames()");
        List<String> appPackageNames = new ArrayList<>();
        FileReader fr = null;
        BufferedReader br = null;
        try {
            try {
                fr = new FileReader(configFile);
                br = new BufferedReader(fr);
                do {
                    line = br.readLine();
                    if (line != null && !line.startsWith("#")) {
                        appPackageNames.add(line);
                    }
                    String str = TAG;
                    Slog.d(str, "getSystemApnAppPackageNames(): " + line);
                } while (line != null);
            } catch (Exception e) {
                Slog.e(TAG, "fail to read system apn app config file");
            }
            return appPackageNames;
        } finally {
            IoUtils.closeQuietly(fr);
            IoUtils.closeQuietly(br);
        }
    }
}
