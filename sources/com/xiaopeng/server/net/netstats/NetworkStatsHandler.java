package com.xiaopeng.server.net.netstats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.xiaopeng.util.xpLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
/* loaded from: classes.dex */
public class NetworkStatsHandler {
    private static final String ACTION_NETSTATS = "system.traffic.statistics.action";
    private static final String EVENT_DATA_TRAFFIC = "data_traffic";
    private static final String KEY_NETSTATS_TIME = "persist.xiaopeng.netstats.time";
    private static final String NETSTATS_DIR = "/data/netstats";
    private static final String PACKAGE_NETSTATS = "com.xiaopeng.data.uploader";
    private static final String TAG = "NetStatsService";
    private final AlarmManager mAlarmManager;
    private Context mContext;
    private Handler mHandler;
    private PendingIntent mPendingIntent;
    private static final long NETSTATS_DELAY = SystemProperties.getLong("persist.xp.netstats.delay", 900000);
    private static final long NETSTATS_INTERVAL = SystemProperties.getLong("persist.xp.netstats.interval", (long) BackupAgentTimeoutParameters.DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS);
    private static NetworkStatsHandler sNetworkStats = null;
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.server.net.netstats.NetworkStatsHandler.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            xpLogger.log(NetworkStatsHandler.TAG, "onReceive action=" + action);
            if ("android.intent.action.TIME_SET".equals(action) || "android.intent.action.TIMEZONE_CHANGED".equals(action)) {
                NetworkStatsHandler.this.onTimeChanged();
            } else if ("android.intent.action.ACTION_SHUTDOWN".equals(action)) {
                NetworkStatsHandler.this.uploadNetStats(false);
            }
        }
    };

    public static NetworkStatsHandler getInstance(Context context) {
        if (sNetworkStats == null) {
            synchronized (NetworkStatsHandler.class) {
                if (sNetworkStats == null) {
                    sNetworkStats = new NetworkStatsHandler(context);
                }
            }
        }
        return sNetworkStats;
    }

    private NetworkStatsHandler(Context context) {
        Intent intent = new Intent(ACTION_NETSTATS);
        this.mContext = context;
        this.mAlarmManager = (AlarmManager) context.getSystemService("alarm");
        this.mPendingIntent = PendingIntent.getBroadcast(context, 0, intent, 134217728);
        startHandlerThread();
    }

    public void onBootPhase(int phase) {
        onStart();
    }

    private void onStart() {
        registerReceiver();
        dumpNetStats();
        scheduleNetStats();
    }

    private void onStop() {
        stopHandlerThread();
        unregisterReceiver();
    }

    private void startHandlerThread() {
        if (this.mHandlerThread != null) {
            this.mHandlerThread.start();
            this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
        }
    }

    private void stopHandlerThread() {
        if (this.mHandlerThread != null) {
            this.mHandlerThread.quitSafely();
        }
    }

    public void uploadNetStats(boolean isSendNet) {
        long now = System.currentTimeMillis();
        long startTime = SystemProperties.getLong(KEY_NETSTATS_TIME, 0L);
        long endTime = now - NETSTATS_DELAY;
        StringBuffer buffer = new StringBuffer("uploadNetStats");
        buffer.append(" now=" + NetworkStatsHelper.formatDateTime(now));
        buffer.append(" startTime=" + NetworkStatsHelper.formatDateTime(startTime));
        buffer.append(" endTime=" + NetworkStatsHelper.formatDateTime(endTime));
        if (endTime > 0 && endTime > startTime) {
            try {
                String content = NetworkStatsHelper.createNetStatsContent(this.mContext, startTime, endTime);
                if (content != null && !TextUtils.isEmpty(content)) {
                    boolean ret = createNetStatsFile(endTime + ".json", content);
                    buffer.append(" ret=" + ret);
                    buffer.append(" content=" + content);
                    if (ret) {
                        if (isSendNet) {
                            sendNetStatsBroadcast(this.mContext);
                        }
                        SystemProperties.set(KEY_NETSTATS_TIME, String.valueOf(endTime));
                    }
                } else {
                    buffer.append(" content is null");
                }
            } catch (Exception e) {
                buffer.append(" e=" + e);
            }
        }
        xpLogger.log(TAG, buffer.toString());
        scheduleNetStats();
    }

    private void scheduleNetStats() {
        this.mHandler.removeMessages(100);
        this.mHandler.sendEmptyMessageDelayed(100, NETSTATS_INTERVAL);
    }

    private void dumpNetStats() {
        SystemProperties.set("ctl.start", "network_stat_traffic");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onTimeChanged() {
        xpLogger.log(TAG, "onTimeChanged");
    }

    private static void sendNetStatsBroadcast(Context context) {
        Intent intent = new Intent();
        intent.setAction(ACTION_NETSTATS);
        intent.setPackage(PACKAGE_NETSTATS);
        intent.addFlags(16777216);
        context.sendBroadcastAsUser(intent, UserHandle.ALL);
        xpLogger.log(TAG, "sendNetStatsBroadcast");
    }

    private static boolean createNetStatsFile(String name, String content) {
        try {
            if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(content)) {
                File root = new File(NETSTATS_DIR);
                if (!root.exists()) {
                    root.mkdirs();
                    root.setReadable(true);
                    root.setWritable(true);
                    root.setExecutable(true);
                    root.setReadable(true, false);
                    root.setWritable(true, false);
                    root.setExecutable(true, false);
                }
                File file = new File("/data/netstats/" + name);
                file.createNewFile();
                byte[] bytes = content.getBytes();
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                file.setReadable(true);
                file.setWritable(true);
                file.setExecutable(true);
                file.setReadable(true, false);
                file.setWritable(true, false);
                file.setExecutable(true, false);
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    private void registerReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.ACTION_SHUTDOWN");
            this.mContext.registerReceiver(this.mReceiver, filter);
        } catch (Exception e) {
        }
    }

    private void unregisterReceiver() {
        try {
            this.mContext.unregisterReceiver(this.mReceiver);
        } catch (Exception e) {
        }
    }

    private void startAlarm() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(6, 1);
        calendar.set(11, 0);
        calendar.set(12, 0);
        calendar.set(13, 0);
        calendar.set(14, 0);
        long time = calendar.getTimeInMillis();
        xpLogger.log(TAG, "setAlarm time=" + time);
        this.mAlarmManager.setRepeating(0, time, 86400000L, this.mPendingIntent);
    }

    private void cancelAlarm() {
        this.mAlarmManager.cancel(this.mPendingIntent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class WorkHandler extends Handler {
        public static final int MSG_TRAFFIC_STATS = 100;

        public WorkHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 100) {
                NetworkStatsHandler.this.uploadNetStats(true);
            }
        }
    }
}
