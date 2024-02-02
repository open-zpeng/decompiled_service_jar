package com.android.server.notification;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.server.notification.ManagedServices;
import com.android.server.notification.NotificationManagerService;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class SnoozeHelper {
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_USER_ID = "userId";
    private static final String INDENT = "    ";
    private static final String REPOST_SCHEME = "repost";
    private static final int REQUEST_CODE_REPOST = 1;
    private AlarmManager mAm;
    private Callback mCallback;
    private final Context mContext;
    private final ManagedServices.UserProfiles mUserProfiles;
    private static final String TAG = "SnoozeHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String REPOST_ACTION = SnoozeHelper.class.getSimpleName() + ".EVALUATE";
    private ArrayMap<Integer, ArrayMap<String, ArrayMap<String, NotificationRecord>>> mSnoozedNotifications = new ArrayMap<>();
    private ArrayMap<String, String> mPackages = new ArrayMap<>();
    private ArrayMap<String, Integer> mUsers = new ArrayMap<>();
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.SnoozeHelper.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (SnoozeHelper.DEBUG) {
                Slog.d(SnoozeHelper.TAG, "Reposting notification");
            }
            if (SnoozeHelper.REPOST_ACTION.equals(intent.getAction())) {
                SnoozeHelper.this.repost(intent.getStringExtra(SnoozeHelper.EXTRA_KEY), intent.getIntExtra(SnoozeHelper.EXTRA_USER_ID, 0));
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public interface Callback {
        void repost(int i, NotificationRecord notificationRecord);
    }

    public SnoozeHelper(Context context, Callback callback, ManagedServices.UserProfiles userProfiles) {
        this.mContext = context;
        IntentFilter filter = new IntentFilter(REPOST_ACTION);
        filter.addDataScheme(REPOST_SCHEME);
        this.mContext.registerReceiver(this.mBroadcastReceiver, filter);
        this.mAm = (AlarmManager) this.mContext.getSystemService("alarm");
        this.mCallback = callback;
        this.mUserProfiles = userProfiles;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isSnoozed(int userId, String pkg, String key) {
        return this.mSnoozedNotifications.containsKey(Integer.valueOf(userId)) && this.mSnoozedNotifications.get(Integer.valueOf(userId)).containsKey(pkg) && this.mSnoozedNotifications.get(Integer.valueOf(userId)).get(pkg).containsKey(key);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public Collection<NotificationRecord> getSnoozed(int userId, String pkg) {
        if (this.mSnoozedNotifications.containsKey(Integer.valueOf(userId)) && this.mSnoozedNotifications.get(Integer.valueOf(userId)).containsKey(pkg)) {
            return this.mSnoozedNotifications.get(Integer.valueOf(userId)).get(pkg).values();
        }
        return Collections.EMPTY_LIST;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public List<NotificationRecord> getSnoozed() {
        List<NotificationRecord> snoozedForUser = new ArrayList<>();
        int[] userIds = this.mUserProfiles.getCurrentProfileIds();
        if (userIds != null) {
            for (int i : userIds) {
                ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs = this.mSnoozedNotifications.get(Integer.valueOf(i));
                if (snoozedPkgs != null) {
                    int M = snoozedPkgs.size();
                    for (int j = 0; j < M; j++) {
                        ArrayMap<String, NotificationRecord> records = snoozedPkgs.valueAt(j);
                        if (records != null) {
                            snoozedForUser.addAll(records.values());
                        }
                    }
                }
            }
        }
        return snoozedForUser;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void snooze(NotificationRecord record, long duration) {
        snooze(record);
        scheduleRepost(record.sbn.getPackageName(), record.getKey(), record.getUserId(), duration);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void snooze(NotificationRecord record) {
        int userId = record.getUser().getIdentifier();
        if (DEBUG) {
            Slog.d(TAG, "Snoozing " + record.getKey());
        }
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records = this.mSnoozedNotifications.get(Integer.valueOf(userId));
        if (records == null) {
            records = new ArrayMap<>();
        }
        ArrayMap<String, NotificationRecord> pkgRecords = records.get(record.sbn.getPackageName());
        if (pkgRecords == null) {
            pkgRecords = new ArrayMap<>();
        }
        pkgRecords.put(record.getKey(), record);
        records.put(record.sbn.getPackageName(), pkgRecords);
        this.mSnoozedNotifications.put(Integer.valueOf(userId), records);
        this.mPackages.put(record.getKey(), record.sbn.getPackageName());
        this.mUsers.put(record.getKey(), Integer.valueOf(userId));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean cancel(int userId, String pkg, String tag, int id) {
        ArrayMap<String, NotificationRecord> recordsForPkg;
        if (this.mSnoozedNotifications.containsKey(Integer.valueOf(userId)) && (recordsForPkg = this.mSnoozedNotifications.get(Integer.valueOf(userId)).get(pkg)) != null) {
            Set<Map.Entry<String, NotificationRecord>> records = recordsForPkg.entrySet();
            for (Map.Entry<String, NotificationRecord> record : records) {
                StatusBarNotification sbn = record.getValue().sbn;
                if (Objects.equals(sbn.getTag(), tag) && sbn.getId() == id) {
                    record.getValue().isCanceled = true;
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean cancel(int userId, boolean includeCurrentProfiles) {
        int[] userIds = {userId};
        if (includeCurrentProfiles) {
            userIds = this.mUserProfiles.getCurrentProfileIds();
        }
        for (int i : userIds) {
            ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs = this.mSnoozedNotifications.get(Integer.valueOf(i));
            if (snoozedPkgs != null) {
                int M = snoozedPkgs.size();
                for (int j = 0; j < M; j++) {
                    ArrayMap<String, NotificationRecord> records = snoozedPkgs.valueAt(j);
                    if (records != null) {
                        int P = records.size();
                        for (int k = 0; k < P; k++) {
                            records.valueAt(k).isCanceled = true;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean cancel(int userId, String pkg) {
        if (this.mSnoozedNotifications.containsKey(Integer.valueOf(userId)) && this.mSnoozedNotifications.get(Integer.valueOf(userId)).containsKey(pkg)) {
            ArrayMap<String, NotificationRecord> records = this.mSnoozedNotifications.get(Integer.valueOf(userId)).get(pkg);
            int N = records.size();
            for (int i = 0; i < N; i++) {
                records.valueAt(i).isCanceled = true;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void update(int userId, NotificationRecord record) {
        ArrayMap<String, NotificationRecord> pkgRecords;
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records = this.mSnoozedNotifications.get(Integer.valueOf(userId));
        if (records == null || (pkgRecords = records.get(record.sbn.getPackageName())) == null) {
            return;
        }
        NotificationRecord existing = pkgRecords.get(record.getKey());
        if (existing != null && existing.isCanceled) {
            return;
        }
        pkgRecords.put(record.getKey(), record);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void repost(String key) {
        Integer userId = this.mUsers.get(key);
        if (userId != null) {
            repost(key, userId.intValue());
        }
    }

    protected void repost(String key, int userId) {
        ArrayMap<String, NotificationRecord> pkgRecords;
        String pkg = this.mPackages.remove(key);
        ArrayMap<String, ArrayMap<String, NotificationRecord>> records = this.mSnoozedNotifications.get(Integer.valueOf(userId));
        if (records == null || (pkgRecords = records.get(pkg)) == null) {
            return;
        }
        NotificationRecord record = pkgRecords.remove(key);
        this.mPackages.remove(key);
        this.mUsers.remove(key);
        if (record != null && !record.isCanceled) {
            MetricsLogger.action(record.getLogMaker().setCategory(831).setType(1));
            this.mCallback.repost(userId, record);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void repostGroupSummary(String pkg, int userId, String groupKey) {
        ArrayMap<String, ArrayMap<String, NotificationRecord>> keysByPackage;
        ArrayMap<String, NotificationRecord> recordsByKey;
        if (this.mSnoozedNotifications.containsKey(Integer.valueOf(userId)) && (keysByPackage = this.mSnoozedNotifications.get(Integer.valueOf(userId))) != null && keysByPackage.containsKey(pkg) && (recordsByKey = keysByPackage.get(pkg)) != null) {
            String groupSummaryKey = null;
            int N = recordsByKey.size();
            int i = 0;
            while (true) {
                if (i >= N) {
                    break;
                }
                NotificationRecord potentialGroupSummary = recordsByKey.valueAt(i);
                if (!potentialGroupSummary.sbn.isGroup() || !potentialGroupSummary.getNotification().isGroupSummary() || !groupKey.equals(potentialGroupSummary.getGroupKey())) {
                    i++;
                } else {
                    groupSummaryKey = potentialGroupSummary.getKey();
                    break;
                }
            }
            if (groupSummaryKey != null) {
                NotificationRecord record = recordsByKey.remove(groupSummaryKey);
                this.mPackages.remove(groupSummaryKey);
                this.mUsers.remove(groupSummaryKey);
                if (record != null && !record.isCanceled) {
                    MetricsLogger.action(record.getLogMaker().setCategory(831).setType(1));
                    this.mCallback.repost(userId, record);
                }
            }
        }
    }

    private PendingIntent createPendingIntent(String pkg, String key, int userId) {
        return PendingIntent.getBroadcast(this.mContext, 1, new Intent(REPOST_ACTION).setData(new Uri.Builder().scheme(REPOST_SCHEME).appendPath(key).build()).addFlags(268435456).putExtra(EXTRA_KEY, key).putExtra(EXTRA_USER_ID, userId), 134217728);
    }

    private void scheduleRepost(String pkg, String key, int userId, long duration) {
        long identity = Binder.clearCallingIdentity();
        try {
            PendingIntent pi = createPendingIntent(pkg, key, userId);
            this.mAm.cancel(pi);
            long time = SystemClock.elapsedRealtime() + duration;
            if (DEBUG) {
                Slog.d(TAG, "Scheduling evaluate for " + new Date(time));
            }
            this.mAm.setExactAndAllowWhileIdle(2, time, pi);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void dump(PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        pw.println("\n  Snoozed notifications:");
        for (Integer num : this.mSnoozedNotifications.keySet()) {
            int userId = num.intValue();
            pw.print(INDENT);
            pw.println("user: " + userId);
            ArrayMap<String, ArrayMap<String, NotificationRecord>> snoozedPkgs = this.mSnoozedNotifications.get(Integer.valueOf(userId));
            for (String pkg : snoozedPkgs.keySet()) {
                pw.print(INDENT);
                pw.print(INDENT);
                pw.println("package: " + pkg);
                Set<String> snoozedKeys = snoozedPkgs.get(pkg).keySet();
                for (String key : snoozedKeys) {
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.print(INDENT);
                    pw.println(key);
                }
            }
        }
    }

    protected void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
    }

    public void readXml(XmlPullParser parser, boolean forRestore) throws XmlPullParserException, IOException {
    }

    @VisibleForTesting
    void setAlarmManager(AlarmManager am) {
        this.mAm = am;
    }
}
