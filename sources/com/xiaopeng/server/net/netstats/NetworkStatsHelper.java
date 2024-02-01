package com.xiaopeng.server.net.netstats;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.INetworkStatsService;
import android.net.NetworkTemplate;
import android.os.ServiceManager;
import com.xiaopeng.util.xpLogger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class NetworkStatsHelper {
    private static final String TAG = "NetStatsHelper";

    public static String createNetStatsContent(Context context, long startTime, long endTime) {
        List<JSONObject> list;
        JSONObject json = new JSONObject();
        if (context != null && endTime > startTime) {
            try {
                HashMap<Integer, NetworkStatsEntry> entries = getNetStatsEntry(context, startTime, endTime);
                JSONArray array = new JSONArray();
                if (entries != null) {
                    for (NetworkStatsEntry entry : entries.values()) {
                        if (isBytesValid(entry) && (list = createEntryContent(context, entry)) != null) {
                            for (JSONObject o : list) {
                                if (o != null) {
                                    array.put(o);
                                }
                            }
                        }
                    }
                    json.put("start_time", formatDateTime(startTime));
                    json.put("end_time", formatDateTime(endTime));
                    json.put("details", array);
                } else {
                    return null;
                }
            } catch (Exception e) {
            }
        }
        return json.toString();
    }

    public static HashMap<Integer, NetworkStatsEntry> getNetStatsEntry(Context context, long startTime, long endTime) {
        String str;
        String str2;
        String str3;
        String str4;
        NetworkStatsEntry entry;
        int uid;
        HashMap<Integer, NetworkStatsEntry> map = new HashMap<>();
        if (context != null && endTime > startTime) {
            boolean z = true;
            int TYPE_ETHERNET = '\t';
            NetworkStatsManager manager = (NetworkStatsManager) context.getSystemService("netstats");
            INetworkStatsService stats = INetworkStatsService.Stub.asInterface(ServiceManager.getService("netstats"));
            if (stats != null) {
                try {
                    stats.forceUpdate();
                } catch (Exception e) {
                }
            }
            NetworkStats wifi = querySummary(manager, 1, null, startTime, endTime);
            NetworkStats ethernet = querySummary(manager, 9, null, startTime, endTime);
            boolean z2 = true;
            boolean hasWifiStats = wifi != null && wifi.hasNextBucket();
            if (ethernet == null || !ethernet.hasNextBucket()) {
                z2 = false;
            }
            boolean isEntryUid = z2;
            if (!hasWifiStats && !isEntryUid) {
                xpLogger.log(TAG, "getNetStatsEntry no wifi & ethernet network stats");
                return null;
            }
            while (true) {
                str = " isEntryTag=";
                str2 = " isEntryUid=";
                if (wifi == null || !wifi.hasNextBucket()) {
                    break;
                }
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                wifi.getNextBucket(bucket);
                int uid2 = bucket.getUid();
                int tag = bucket.getTag();
                boolean hasEthernetStats = isEntryUid;
                boolean isEntryUid2 = TrafficStatsEntry.isEntryUid(uid2);
                boolean hasWifiStats2 = hasWifiStats;
                boolean isEntryTag = TrafficStatsEntry.isEntryTag(tag);
                boolean z3 = z;
                StringBuilder sb = new StringBuilder();
                int TYPE_ETHERNET2 = TYPE_ETHERNET;
                sb.append("getNetStatsEntry wifi uid=");
                sb.append(uid2);
                sb.append(" tag=0x");
                sb.append(Integer.toHexString(tag));
                sb.append(" isEntryUid=");
                sb.append(isEntryUid2);
                sb.append(" isEntryTag=");
                sb.append(isEntryTag);
                xpLogger.log(TAG, sb.toString());
                if (map.containsKey(Integer.valueOf(uid2))) {
                    entry = map.get(Integer.valueOf(uid2));
                    uid = uid2;
                } else {
                    uid = uid2;
                    entry = new NetworkStatsEntry(uid, 0L, 0L);
                }
                if (entry != null) {
                    if (isEntryTag && !isEntryUid2) {
                        entry.addTagEntry(tag, uid, bucket.getRxBytes(), bucket.getTxBytes(), bucket.getRxBytes(), bucket.getTxBytes(), 0L, 0L);
                    } else {
                        entry.rxBytes += bucket.getRxBytes();
                        entry.txBytes += bucket.getTxBytes();
                        entry.wifiRxBytes += bucket.getRxBytes();
                        entry.wifiTxBytes += bucket.getTxBytes();
                        entry.addPackages(getPackageNameByUid(context, uid));
                    }
                    map.put(Integer.valueOf(uid), entry);
                }
                isEntryUid = hasEthernetStats;
                hasWifiStats = hasWifiStats2;
                z = z3;
                TYPE_ETHERNET = TYPE_ETHERNET2;
            }
            while (ethernet != null && ethernet.hasNextBucket()) {
                NetworkStats.Bucket bucket2 = new NetworkStats.Bucket();
                ethernet.getNextBucket(bucket2);
                int uid3 = bucket2.getUid();
                int tag2 = bucket2.getTag();
                boolean isEntryUid3 = TrafficStatsEntry.isEntryUid(uid3);
                boolean isEntryTag2 = TrafficStatsEntry.isEntryTag(tag2);
                StringBuilder sb2 = new StringBuilder();
                NetworkStatsManager manager2 = manager;
                sb2.append("getNetStatsEntry ethernet uid=");
                sb2.append(uid3);
                sb2.append(" tag=0x");
                sb2.append(Integer.toHexString(tag2));
                sb2.append(str2);
                sb2.append(isEntryUid3);
                sb2.append(str);
                sb2.append(isEntryTag2);
                xpLogger.log(TAG, sb2.toString());
                NetworkStatsEntry entry2 = map.containsKey(Integer.valueOf(uid3)) ? map.get(Integer.valueOf(uid3)) : new NetworkStatsEntry(uid3, 0L, 0L);
                if (entry2 == null) {
                    str3 = str;
                    str4 = str2;
                } else {
                    if (isEntryTag2 && !isEntryUid3) {
                        entry2.addTagEntry(tag2, uid3, bucket2.getRxBytes(), bucket2.getTxBytes(), 0L, 0L, bucket2.getRxBytes(), bucket2.getTxBytes());
                        str3 = str;
                        str4 = str2;
                    } else {
                        str3 = str;
                        str4 = str2;
                        entry2.rxBytes += bucket2.getRxBytes();
                        entry2.txBytes += bucket2.getTxBytes();
                        entry2.ethernetRxBytes += bucket2.getRxBytes();
                        entry2.ethernetTxBytes += bucket2.getTxBytes();
                        entry2.packages.add(getPackageNameByUid(context, uid3));
                    }
                    map.put(Integer.valueOf(uid3), entry2);
                }
                str = str3;
                str2 = str4;
                manager = manager2;
            }
            if (wifi != null) {
                try {
                    wifi.close();
                } catch (Exception e2) {
                }
            }
            if (ethernet != null) {
                ethernet.close();
            }
        }
        return map;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(7:63|(2:64|65)|(3:(3:76|77|(9:79|(4:82|(2:84|85)(2:87|88)|86|80)|89|90|68|69|70|71|72))|71|72)|67|68|69|70) */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static java.util.List<org.json.JSONObject> createEntryContent(android.content.Context r22, com.xiaopeng.server.net.netstats.NetworkStatsEntry r23) {
        /*
            Method dump skipped, instructions count: 486
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.net.netstats.NetworkStatsHelper.createEntryContent(android.content.Context, com.xiaopeng.server.net.netstats.NetworkStatsEntry):java.util.List");
    }

    public static NetworkStats querySummary(NetworkStatsManager manager, int networkType, String subscriberId, long startTime, long endTime) {
        if (manager != null) {
            try {
                return manager.querySummary(networkType, subscriberId, startTime, endTime);
            } catch (Exception e) {
                xpLogger.log(TAG, "querySummaryForDevice e=" + e);
                return null;
            }
        }
        return null;
    }

    public static int getUid(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(packageName, 128);
            if (info == null) {
                return -1;
            }
            int uid = info.applicationInfo.uid;
            return uid;
        } catch (PackageManager.NameNotFoundException e) {
            xpLogger.log(TAG, "getUid e=" + e);
            return -1;
        }
    }

    public static boolean isBytesValid(long bytes) {
        return bytes > 0;
    }

    public static boolean isBytesValid(NetworkStatsEntry entry) {
        return entry != null && entry.rxBytes + entry.txBytes > 0;
    }

    public static String formatDateTime(long date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(date));
    }

    public static String getPackageNameByUid(Context context, int uid) {
        String packageName = "uid:" + uid;
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> list = pm.getInstalledPackages(0);
        if (list != null) {
            Iterator<PackageInfo> it = list.iterator();
            while (it.hasNext()) {
                PackageInfo pi = it.next();
                if (pi != null) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pi.packageName, 128);
                        if (ai != null && uid == ai.uid) {
                            return pi.packageName;
                        }
                    } catch (Exception e) {
                    }
                }
            }
            return packageName;
        }
        return packageName;
    }

    public static List<PackageInfo> getInstalledPackages(Context context) {
        return context.getPackageManager().getInstalledPackages(0);
    }

    public static NetworkStats.Bucket querySummaryForDevice(NetworkStatsManager manager, NetworkTemplate template, long startTime, long endTime) {
        return null;
    }

    public static NetworkStats queryDetailsForUid(NetworkStatsManager manager, int networkType, long startTime, long endTime, int uid) {
        if (manager != null) {
            try {
                return manager.queryDetailsForUid(networkType, null, startTime, endTime, uid);
            } catch (Exception e) {
                xpLogger.log(TAG, "queryDetailsForUid e=" + e);
                return null;
            }
        }
        return null;
    }

    public static NetworkStats queryDetailsForUidTag(NetworkStatsManager manager, int networkType, long startTime, long endTime, int uid, int tag) {
        if (manager != null) {
            try {
                return manager.queryDetailsForUidTag(networkType, null, startTime, endTime, uid, tag);
            } catch (Exception e) {
                xpLogger.log(TAG, "queryDetailsForUidTag e=" + e);
                return null;
            }
        }
        return null;
    }

    public static NetworkTemplate buildNetworkTemplate(int rule) {
        if (rule == 4) {
            NetworkTemplate template = NetworkTemplate.buildTemplateWifi();
            return template;
        } else if (rule != 5) {
            return null;
        } else {
            NetworkTemplate template2 = new NetworkTemplate(5, (String) null, (String[]) null, (String) null, -1, -1, -1);
            return template2;
        }
    }
}
