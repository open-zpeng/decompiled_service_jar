package com.xiaopeng.server.net.netstats;

import android.text.TextUtils;
import com.android.server.backup.BackupManagerConstants;
import java.util.ArrayList;
import java.util.HashMap;
/* loaded from: classes.dex */
public class NetworkStatsEntry {
    public long endTime;
    public long ethernetRxBytes;
    public long ethernetTxBytes;
    public long rxBytes;
    public long startTime;
    public long txBytes;
    public int uid;
    public long wifiRxBytes;
    public long wifiTxBytes;
    public HashMap<Integer, NetworkStatsEntry> tags = new HashMap<>();
    public ArrayList<String> packages = new ArrayList<>();

    public NetworkStatsEntry(int uid, long rxBytes, long txBytes) {
        this.uid = uid;
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
    }

    public void resetBytes() {
        this.rxBytes = 0L;
        this.txBytes = 0L;
        this.wifiRxBytes = 0L;
        this.wifiTxBytes = 0L;
        this.ethernetRxBytes = 0L;
        this.ethernetTxBytes = 0L;
    }

    public void addTagEntry(int _tag, int _uid, long _rxBytes, long _txBytes, long _wifiRxBytes, long _wifiTxBytes, long _ethernetRxBytes, long _ethernetTxBytes) {
        if (_tag > 0 && this.tags != null) {
            NetworkStatsEntry entry = this.tags.containsKey(Integer.valueOf(_tag)) ? this.tags.get(Integer.valueOf(_tag)) : new NetworkStatsEntry(_uid, 0L, 0L);
            if (entry != null) {
                entry.rxBytes += _rxBytes;
                entry.txBytes += _txBytes;
                entry.wifiRxBytes += _wifiRxBytes;
                entry.wifiTxBytes += _wifiTxBytes;
                entry.ethernetRxBytes += _ethernetRxBytes;
                entry.ethernetTxBytes += _ethernetTxBytes;
                entry.addPackages(TrafficStatsEntry.getPackageName(_tag, _uid));
                this.tags.put(Integer.valueOf(this.uid), entry);
            }
        }
    }

    public void addPackages(String packageName) {
        if (!TextUtils.isEmpty(packageName) && !this.packages.contains(packageName)) {
            this.packages.add(packageName);
        }
    }

    public String getPackages() {
        if (this.packages != null && this.packages.size() > 0) {
            return this.packages.get(0);
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer();
        buffer.append("NetStatsEntry");
        buffer.append(" uid=" + this.uid);
        buffer.append(" rxBytes=" + this.rxBytes);
        buffer.append(" txBytes=" + this.txBytes);
        buffer.append(" wifiRxBytes=" + this.wifiRxBytes);
        buffer.append(" wifiTxBytes=" + this.wifiTxBytes);
        buffer.append(" ethernetRxBytes=" + this.ethernetRxBytes);
        buffer.append(" ethernetTxBytes=" + this.ethernetTxBytes);
        buffer.append(" startTime=" + this.startTime);
        buffer.append(" endTime=" + this.endTime);
        buffer.append(" tags=" + this.tags.toString());
        buffer.append(" packages=" + this.packages.toString());
        return buffer.toString();
    }
}
