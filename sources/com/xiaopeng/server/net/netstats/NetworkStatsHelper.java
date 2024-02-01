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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
/* loaded from: classes.dex */
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
        NetworkStatsEntry entry;
        boolean isEntryTag;
        int tag;
        boolean isEntryUid;
        HashMap<Integer, NetworkStatsEntry> map = new HashMap<>();
        if (context != null && endTime > startTime) {
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
            boolean z = false;
            boolean hasWifiStats = wifi != null && wifi.hasNextBucket();
            if (ethernet != null && ethernet.hasNextBucket()) {
                z = true;
            }
            boolean hasEthernetStats = z;
            if (!hasWifiStats && !hasEthernetStats) {
                xpLogger.log(TAG, "getNetStatsEntry no wifi & ethernet network stats");
                return null;
            }
            while (wifi != null && wifi.hasNextBucket()) {
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                wifi.getNextBucket(bucket);
                int uid = bucket.getUid();
                int tag2 = bucket.getTag();
                boolean isEntryUid2 = TrafficStatsEntry.isEntryUid(uid);
                boolean isEntryTag2 = TrafficStatsEntry.isEntryTag(tag2);
                boolean hasEthernetStats2 = hasEthernetStats;
                StringBuilder sb = new StringBuilder();
                boolean hasWifiStats2 = hasWifiStats;
                sb.append("getNetStatsEntry wifi uid=");
                sb.append(uid);
                sb.append(" tag=0x");
                sb.append(Integer.toHexString(tag2));
                sb.append(" isEntryUid=");
                sb.append(isEntryUid2);
                sb.append(" isEntryTag=");
                sb.append(isEntryTag2);
                xpLogger.log(TAG, sb.toString());
                NetworkStatsEntry entry2 = map.containsKey(Integer.valueOf(uid)) ? map.get(Integer.valueOf(uid)) : new NetworkStatsEntry(uid, 0L, 0L);
                if (entry2 != null) {
                    if (isEntryTag2 && !isEntryUid2) {
                        entry2.addTagEntry(tag2, uid, bucket.getRxBytes(), bucket.getTxBytes(), bucket.getRxBytes(), bucket.getTxBytes(), 0L, 0L);
                    } else {
                        entry2.rxBytes += bucket.getRxBytes();
                        entry2.txBytes += bucket.getTxBytes();
                        entry2.wifiRxBytes += bucket.getRxBytes();
                        entry2.wifiTxBytes += bucket.getTxBytes();
                        entry2.addPackages(getPackageNameByUid(context, uid));
                    }
                    map.put(Integer.valueOf(uid), entry2);
                }
                hasEthernetStats = hasEthernetStats2;
                hasWifiStats = hasWifiStats2;
            }
            while (ethernet != null && ethernet.hasNextBucket()) {
                NetworkStats.Bucket bucket2 = new NetworkStats.Bucket();
                ethernet.getNextBucket(bucket2);
                int uid2 = bucket2.getUid();
                int tag3 = bucket2.getTag();
                boolean isEntryUid3 = TrafficStatsEntry.isEntryUid(uid2);
                boolean isEntryTag3 = TrafficStatsEntry.isEntryTag(tag3);
                xpLogger.log(TAG, "getNetStatsEntry ethernet uid=" + uid2 + " tag=0x" + Integer.toHexString(tag3) + " isEntryUid=" + isEntryUid3 + " isEntryTag=" + isEntryTag3);
                if (map.containsKey(Integer.valueOf(uid2))) {
                    entry = map.get(Integer.valueOf(uid2));
                    isEntryTag = isEntryTag3;
                    tag = tag3;
                    isEntryUid = isEntryUid3;
                } else {
                    isEntryTag = isEntryTag3;
                    tag = tag3;
                    isEntryUid = isEntryUid3;
                    entry = new NetworkStatsEntry(uid2, 0L, 0L);
                }
                if (entry != null) {
                    if (isEntryTag && !isEntryUid) {
                        entry.addTagEntry(tag, uid2, bucket2.getRxBytes(), bucket2.getTxBytes(), 0L, 0L, bucket2.getRxBytes(), bucket2.getTxBytes());
                    } else {
                        entry.rxBytes += bucket2.getRxBytes();
                        entry.txBytes += bucket2.getTxBytes();
                        entry.ethernetRxBytes += bucket2.getRxBytes();
                        entry.ethernetTxBytes += bucket2.getTxBytes();
                        entry.packages.add(getPackageNameByUid(context, uid2));
                    }
                    map.put(Integer.valueOf(uid2), entry);
                }
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

    public static List<JSONObject> createEntryContent(Context context, NetworkStatsEntry entry) {
        List<JSONObject> list = new ArrayList<>();
        JSONObject object = new JSONObject();
        if (entry != null) {
            try {
                String packageName = entry.getPackages();
                int uid = entry.uid;
                if (uid == 0) {
                    return null;
                }
                if (uid == 1000) {
                    packageName = "system";
                    HashMap<Integer, NetworkStatsEntry> tags = entry.tags;
                    if (tags != null && tags.keySet() != null) {
                        for (Integer num : tags.keySet()) {
                            int tag = num.intValue();
                            JSONObject o = new JSONObject();
                            NetworkStatsEntry nse = tags.get(Integer.valueOf(tag));
                            if (nse != null) {
                                o.put("APP", nse.getPackages());
                                o.put("Wifi", nse.wifiRxBytes + nse.wifiTxBytes);
                                o.put("WifiUp", nse.wifiTxBytes);
                                o.put("WifiDown", nse.wifiRxBytes);
                                o.put("Mobile", nse.ethernetRxBytes + nse.ethernetTxBytes);
                                o.put("MobileUp", nse.ethernetTxBytes);
                                o.put("MobileDown", nse.ethernetRxBytes);
                                list.add(o);
                            }
                        }
                    }
                }
                if (TrafficStatsEntry.isEntryUid(uid)) {
                    packageName = TrafficStatsEntry.getPackageName(-1, uid);
                }
                object.put("APP", packageName);
                object.put("Wifi", entry.wifiRxBytes + entry.wifiTxBytes);
                object.put("WifiUp", entry.wifiTxBytes);
                object.put("WifiDown", entry.wifiRxBytes);
                object.put("Mobile", entry.ethernetRxBytes + entry.ethernetTxBytes);
                object.put("MobileUp", entry.ethernetTxBytes);
                object.put("MobileDown", entry.ethernetRxBytes);
                list.add(object);
            } catch (Exception e) {
            }
        }
        return list;
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
            for (PackageInfo pi : list) {
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
        switch (rule) {
            case 4:
                NetworkTemplate template = NetworkTemplate.buildTemplateWifi();
                return template;
            case 5:
                NetworkTemplate template2 = new NetworkTemplate(5, (String) null, (String[]) null, (String) null, -1, -1, -1);
                return template2;
            default:
                return null;
        }
    }
}
