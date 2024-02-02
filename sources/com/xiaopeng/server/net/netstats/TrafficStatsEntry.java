package com.xiaopeng.server.net.netstats;

import android.net.TrafficStats;
import android.text.TextUtils;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.xiaopeng.server.aftersales.AfterSalesService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class TrafficStatsEntry {
    public static final int FIRST_NETWORK_UID = 100000;
    public static final int LAST_NETWORK_UID = 110000;
    public static final int TAG_APP_AUTOPILOT = -16449537;
    public static final int TAG_APP_AUTO_SHOW = -14483457;
    public static final int TAG_APP_AVATAR_SERVICE = -15663105;
    public static final int TAG_APP_BLUETOOTH_PHONE = -16121857;
    public static final int TAG_APP_BUG_HUNTER = -14548993;
    public static final int TAG_APP_CAR_ACCOUNT = -15335425;
    public static final int TAG_APP_CAR_CAMERA = -15532033;
    public static final int TAG_APP_CAR_CONTROL = -15269889;
    public static final int TAG_APP_CAR_DIAGNOSIS = -14352385;
    public static final int TAG_APP_CAR_GALLERY = -15466497;
    public static final int TAG_APP_CAR_REMOTE_CONTROL = -15073281;
    public static final int TAG_APP_CAR_SERVICE = -15400961;
    public static final int TAG_APP_CAR_SETTINGS = -15204353;
    public static final int TAG_APP_CAR_SPEECH_SERVICE = -15138817;
    public static final int TAG_APP_DATA_COLLECTOR = -16252929;
    public static final int TAG_APP_DATA_UPLOADER = -16187393;
    public static final int TAG_APP_DEVICE_COMMUNICATION = -15597569;
    public static final int TAG_APP_DEV_TOOLS = -16384001;
    public static final int TAG_APP_ENGINE = -16515073;
    public static final int TAG_APP_FACTORY = -14417921;
    public static final int TAG_APP_IPC = -14614529;
    public static final int TAG_APP_NETWORK_MONITOR = -14286849;
    public static final int TAG_APP_OOBE = -16580609;
    public static final int TAG_APP_OTA = -16646145;
    public static final int TAG_APP_PSO_SERVICE = -14221313;
    public static final int TAG_APP_XUI_SERVICE = -16318465;
    private static final HashMap<String, Entry> sEntry = new HashMap<>();

    static {
        sEntry.clear();
        sEntry.put("com.xiaopeng.ota", new Entry(FIRST_NETWORK_UID, TAG_APP_OTA));
        sEntry.put("com.xiaopeng.oobe", new Entry(100001, TAG_APP_OOBE));
        sEntry.put("com.xiaopeng.appengine", new Entry(100002, TAG_APP_ENGINE));
        sEntry.put("com.xiaopeng.autopilot", new Entry(100003, TAG_APP_AUTOPILOT));
        sEntry.put(AfterSalesService.PackgeName.DEVTOOLS, new Entry(100004, TAG_APP_DEV_TOOLS));
        sEntry.put("com.xiaopeng.xuiservice", new Entry(100005, TAG_APP_XUI_SERVICE));
        sEntry.put("com.xiaopeng.data.collector", new Entry(100006, TAG_APP_DATA_COLLECTOR));
        sEntry.put("com.xiaopeng.data.uploader", new Entry(100007, TAG_APP_DATA_UPLOADER));
        sEntry.put("com.xiaopeng.btphone", new Entry(100008, TAG_APP_BLUETOOTH_PHONE));
        sEntry.put("com.xiaopeng.aiavatarservice", new Entry(100009, TAG_APP_AVATAR_SERVICE));
        sEntry.put("com.xiaopeng.device.communication", new Entry(100010, TAG_APP_DEVICE_COMMUNICATION));
        sEntry.put("com.xiaopeng.xmart.camera", new Entry(100011, TAG_APP_CAR_CAMERA));
        sEntry.put("com.xiaopeng.xmart.cargallery", new Entry(100012, TAG_APP_CAR_GALLERY));
        sEntry.put("com.android.car", new Entry(100013, TAG_APP_CAR_SERVICE));
        sEntry.put("com.xiaopeng.caraccount", new Entry(100014, TAG_APP_CAR_ACCOUNT));
        sEntry.put("com.xiaopeng.carcontrol", new Entry(100015, TAG_APP_CAR_CONTROL));
        sEntry.put("com.xiaopeng.car.settings", new Entry(100016, TAG_APP_CAR_SETTINGS));
        sEntry.put("com.xiaopeng.xpspeechservice", new Entry(100017, TAG_APP_CAR_SPEECH_SERVICE));
        sEntry.put("com.xpeng.xpcarremotecontrol", new Entry(100018, TAG_APP_CAR_REMOTE_CONTROL));
        sEntry.put("com.xiaopeng.ipc", new Entry(100019, TAG_APP_IPC));
        sEntry.put("com.xiaopeng.bughunter", new Entry(100020, TAG_APP_BUG_HUNTER));
        sEntry.put("com.xiaopeng.autoshow", new Entry(100021, TAG_APP_AUTO_SHOW));
        sEntry.put("com.xiaopeng.factory", new Entry(100022, TAG_APP_FACTORY));
        sEntry.put(AfterSalesService.PackgeName.CARDIAGNOSIS, new Entry(100023, TAG_APP_CAR_DIAGNOSIS));
        sEntry.put("com.xiaopeng.networkmonitor", new Entry(100024, TAG_APP_NETWORK_MONITOR));
        sEntry.put("android.E28psoService", new Entry(100025, TAG_APP_PSO_SERVICE));
    }

    public static void setTrafficInfo(int uid, int tag) {
        TrafficStats.getAndSetThreadStatsTag(tag);
        TrafficStats.setThreadStatsUid(uid);
    }

    public static List<HashMap<String, Object>> getTrafficInfo() {
        List<HashMap<String, Object>> list = new ArrayList<>();
        try {
            File file = new File("/system/etc/xp_traffic_stats_maps.json");
            if (file.exists()) {
                StringBuilder builder = new StringBuilder();
                InputStreamReader sr = new InputStreamReader(new FileInputStream(file));
                BufferedReader br = new BufferedReader(sr);
                while (true) {
                    String line = br.readLine();
                    if (line == null) {
                        break;
                    }
                    builder.append(line);
                }
                br.close();
                sr.close();
                JSONObject jObject = new JSONObject(builder.toString());
                JSONArray jArray = jObject.getJSONArray("data");
                if (jArray != null) {
                    int length = jArray.length();
                    for (int i = 0; i < length; i++) {
                        JSONObject object = jArray.getJSONObject(i);
                        HashMap<String, Object> map = new HashMap<>();
                        if (object.has("packageName")) {
                            String packageName = object.getString("packageName");
                            map.put("packageName", packageName);
                        }
                        if (object.has(WatchlistLoggingHandler.WatchlistEventKeys.UID)) {
                            int uid = object.getInt(WatchlistLoggingHandler.WatchlistEventKeys.UID);
                            map.put(WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.valueOf(uid));
                        }
                        if (object.has("tag")) {
                            String tag = object.getString("tag");
                            map.put("tag", tag);
                        }
                        list.add(map);
                    }
                }
            }
        } catch (Exception e) {
        }
        return list;
    }

    public static int getTag(String packageName) {
        Entry entry = getEntry(packageName);
        if (entry != null) {
            return entry.tag;
        }
        return -1;
    }

    public static int getUid(String packageName) {
        Entry entry = getEntry(packageName);
        if (entry != null) {
            return entry.uid;
        }
        return -1;
    }

    public static String getPackageName(int tag, int uid) {
        if (sEntry != null) {
            for (String key : sEntry.keySet()) {
                Entry entry = sEntry.get(key);
                if (entry != null && (entry.tag == tag || entry.uid == uid)) {
                    return key;
                }
            }
            return null;
        }
        return null;
    }

    public static boolean isEntryTag(int tag) {
        if (sEntry != null) {
            for (String key : sEntry.keySet()) {
                Entry entry = sEntry.get(key);
                if (entry != null && entry.tag == tag) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public static boolean isEntryUid(int uid) {
        if (sEntry != null) {
            for (String key : sEntry.keySet()) {
                Entry entry = sEntry.get(key);
                if (entry != null && entry.uid == uid) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static Entry getEntry(String packageName) {
        if (!TextUtils.isEmpty(packageName) && sEntry != null && sEntry.containsKey(packageName)) {
            return sEntry.get(packageName);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Entry {
        public int tag;
        public int uid;

        public Entry(int uid, int tag) {
            this.uid = uid;
            this.tag = tag;
        }
    }
}
