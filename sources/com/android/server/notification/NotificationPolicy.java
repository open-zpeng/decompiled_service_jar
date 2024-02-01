package com.android.server.notification;

import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.notification.NotificationHelper;
import com.android.server.notification.NotificationManagerService;
import com.android.server.pm.Settings;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
/* loaded from: classes.dex */
public class NotificationPolicy {
    public static final long DAY = 86400000;
    private static final boolean DEBUG = true;
    public static final long HOUR = 3600000;
    public static final long MINUTE = 60000;
    public static final long NOTIFICATION_DISAPPEAR_DELAY = 3600000;
    public static final long NOTIFICATION_DISAPPEAR_INTERVAL = 604800000;
    private static final String TAG = "NotificationPolicy";
    private Context mContext;
    private WorkerHandler mHandler;
    private NotificationManagerService.NotificationListeners mListeners;
    private RankingHelper mRankingHelper;
    private Object mLock = new Object();
    private OnListener mListener = null;
    final ArrayList<NotificationRecord> mLastNotificationList = new ArrayList<>();
    final ArrayMap<String, NotificationRecord> mLastNotificationsByKey = new ArrayMap<>();
    final ArrayMap<String, XmlPolicy> mNotificationsPolicy = new ArrayMap<>();

    /* loaded from: classes.dex */
    public interface OnListener {
        void onProvidersLoaded(ArrayList<NotificationRecord> arrayList, ArrayMap<String, NotificationRecord> arrayMap);
    }

    public NotificationPolicy(Looper looper, Context context, RankingHelper rankingHelper, NotificationManagerService.NotificationListeners listeners) {
        this.mContext = context;
        this.mListeners = listeners;
        this.mRankingHelper = rankingHelper;
        this.mHandler = new WorkerHandler(looper);
    }

    public void init() {
        log("init");
    }

    public void onSystemReady() {
        log("onSystemReady");
        loadPolicy(this.mContext);
        loadProviders();
    }

    private void loadProviders() {
        this.mHandler.scheduleProviders(1000L);
    }

    private void loadPolicy(final Context context) {
        File systemDir = new File(Environment.getRootDirectory(), "etc");
        final File policyFile = new File(systemDir, "xp_notification_policy.xml");
        this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationPolicy.1
            @Override // java.lang.Runnable
            public void run() {
                FileInputStream fis = null;
                try {
                    try {
                        try {
                            fis = new FileInputStream(policyFile);
                            ArrayMap<String, XmlPolicy> map = NotificationPolicy.parseXml(context, fis);
                            if (map != null && !map.isEmpty()) {
                                NotificationPolicy.this.mNotificationsPolicy.putAll((ArrayMap<? extends String, ? extends XmlPolicy>) map);
                            }
                            NotificationPolicy.this.printXmlPolicy();
                            fis.close();
                        } catch (Exception e) {
                            NotificationPolicy.log("loadXmlPolicy e=" + e);
                            if (fis != null) {
                                fis.close();
                            }
                        }
                    } catch (Exception e2) {
                    }
                } catch (Throwable th) {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (Exception e3) {
                        }
                    }
                    throw th;
                }
            }
        });
    }

    public void setListener(OnListener listener) {
        this.mListener = listener;
    }

    public void onNotificationPosted(ArrayMap<String, NotificationRecord> notificationsByKey, StatusBarNotification sbn) {
        try {
            boolean hasDisplayFlag = hasDisplayFlag(sbn, 4);
            log("onNotificationPosted hasDisplayFlag=" + hasDisplayFlag);
            if (hasDisplayFlag && checkNotificationNotNull(sbn)) {
                deleteNotificationLocked(this.mContext, sbn);
                insertNotificationLocked(this.mContext, sbn);
            }
        } catch (Exception e) {
        }
    }

    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            boolean hasDisplayFlag = hasDisplayFlag(sbn, 4);
            log("onNotificationRemoved hasDisplayFlag=" + hasDisplayFlag);
            if (hasDisplayFlag && checkNotificationNotNull(sbn)) {
                deleteNotificationLocked(this.mContext, sbn);
            }
        } catch (Exception e) {
        }
    }

    public void onNotificationUpdated(StatusBarNotification sbn) {
        try {
            boolean hasDisplayFlag = hasDisplayFlag(sbn, 4);
            log("onNotificationUpdated hasDisplayFlag=" + hasDisplayFlag);
            if (hasDisplayFlag && checkNotificationNotNull(sbn)) {
                updateNotificationLocked(this.mContext, sbn);
            }
        } catch (Exception e) {
        }
    }

    public void applyNotificationPolicy(Notification notification, String packageName) {
        if (notification != null && !TextUtils.isEmpty(packageName)) {
            XmlPolicy policy = getNotificationPolicy(packageName);
            if (notification.majorPriority == -1) {
                int majorPriority = -1;
                if (notification.extras.containsKey("android.majorPriority")) {
                    majorPriority = notification.extras.getInt("android.majorPriority");
                } else if (policy != null) {
                    majorPriority = policy.majorPriority;
                }
                if (majorPriority == -1) {
                    majorPriority = 1;
                }
                notification.majorPriority = majorPriority;
            }
            int majorPriority2 = notification.displayFlag;
            if (majorPriority2 == -1) {
                int displayFlag = -1;
                if (notification.extras.containsKey("android.displayFlag")) {
                    displayFlag = notification.extras.getInt("android.displayFlag");
                } else if (policy != null) {
                    displayFlag = policy.displayFlag;
                }
                if (displayFlag == -1) {
                    displayFlag = 0;
                }
                notification.displayFlag = displayFlag;
            }
            int displayFlag2 = notification.clearFlag;
            if (displayFlag2 == -1) {
                int clearFlag = -1;
                if (notification.extras.containsKey("android.clearFlag")) {
                    clearFlag = notification.extras.getInt("android.clearFlag");
                } else if (policy != null) {
                    clearFlag = policy.clearFlag;
                }
                if (clearFlag == -1) {
                    clearFlag = 1;
                }
                notification.clearFlag = clearFlag;
            }
            log("updateNotificationByPolicy notification:" + notification.toString());
        }
    }

    public boolean hasClearFlag(Notification n) {
        return n == null || n.clearFlag == 1;
    }

    public List<StatusBarNotification> getNotificationList(int requestFlag, ArrayList<NotificationRecord> notificationList) {
        List<StatusBarNotification> list = new ArrayList<>();
        ArrayMap<String, NotificationRecord> map = new ArrayMap<>();
        if (notificationList == null || notificationList.isEmpty()) {
            return list;
        }
        Iterator<NotificationRecord> it = notificationList.iterator();
        while (it.hasNext()) {
            NotificationRecord r = it.next();
            if (r != null && r.sbn != null) {
                try {
                    StatusBarNotification sbn = r.sbn;
                    if (sbn != null && !TextUtils.isEmpty(sbn.getKey()) && !map.containsKey(sbn.getKey()) && hasRequestFlag(sbn, requestFlag)) {
                        list.add(sbn);
                        map.put(sbn.getKey(), r);
                    }
                } catch (Exception e) {
                }
            }
        }
        return list;
    }

    public int getUnreadCount(String packageName, ArrayList<NotificationRecord> notificationList) {
        int count = 0;
        if (!TextUtils.isEmpty(packageName) && notificationList != null) {
            int N = notificationList.size();
            for (int i = 0; i < N; i++) {
                try {
                    if (packageName.equals(notificationList.get(i).sbn.getPackageName())) {
                        count += notificationList.get(i).sbn.getNotification().number;
                    }
                } catch (Exception e) {
                }
            }
        }
        return count;
    }

    public String getPackageNameByPid(Context context, int pid) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService("activity");
            List<ActivityManager.RunningAppProcessInfo> processesList = am.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo pi : processesList) {
                if (pi.pid == pid) {
                    String packageName = pi.pkgList[0];
                    return packageName;
                }
            }
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        } catch (Exception e) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    public void sendNotificationNumberAction(StatusBarNotification sbn) {
        if (sbn != null && sbn.getNotification() != null) {
            Intent intent = new Intent("android.intent.action.NOTIFICATION_NUMBER_CHANGED");
            intent.putExtra("android.intent.extra.NOTIFICATION_ID", sbn.getId());
            intent.putExtra("android.intent.extra.NOTIFICATION_KEY", sbn.getKey());
            intent.putExtra("android.intent.extra.NOTIFICATION_VALUE", sbn.getNotification().number);
            intent.putExtra("android.intent.extra.NOTIFICATION_PACKAGENAME", sbn.getPackageName());
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    public boolean hasRequestFlag(StatusBarNotification sbn, int requestFlag) {
        boolean z;
        boolean z2;
        boolean z3;
        boolean z4;
        boolean z5;
        boolean z6;
        boolean z7;
        boolean z8;
        if (sbn != null && sbn.getNotification() != null && !TextUtils.isEmpty(sbn.getKey())) {
            if (requestFlag == 1) {
                return true;
            }
            int displayFlag = sbn.getNotification().displayFlag;
            if (displayFlag == 0 && requestFlag == 0) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 1) != 1) {
                z = false;
            } else {
                z = true;
            }
            boolean hasRequestFlag = z;
            boolean hasDisplayFlag = sbn.getNotification().hasDisplayFlag(1);
            if (hasRequestFlag && hasDisplayFlag) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 2) != 2) {
                z2 = false;
            } else {
                z2 = true;
            }
            boolean hasRequestFlag2 = z2;
            boolean hasDisplayFlag2 = sbn.getNotification().hasDisplayFlag(2);
            if (hasRequestFlag2 && hasDisplayFlag2) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 4) != 4) {
                z3 = false;
            } else {
                z3 = true;
            }
            boolean hasRequestFlag3 = z3;
            boolean hasDisplayFlag3 = sbn.getNotification().hasDisplayFlag(4);
            if (hasRequestFlag3 && hasDisplayFlag3) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 8) != 8) {
                z4 = false;
            } else {
                z4 = true;
            }
            boolean hasRequestFlag4 = z4;
            boolean hasDisplayFlag4 = sbn.getNotification().hasDisplayFlag(8);
            if (hasRequestFlag4 && hasDisplayFlag4) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 16) != 16) {
                z5 = false;
            } else {
                z5 = true;
            }
            boolean hasRequestFlag5 = z5;
            boolean hasDisplayFlag5 = sbn.getNotification().hasDisplayFlag(16);
            if (hasRequestFlag5 && hasDisplayFlag5) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 32) != 32) {
                z6 = false;
            } else {
                z6 = true;
            }
            boolean hasRequestFlag6 = z6;
            boolean hasDisplayFlag6 = sbn.getNotification().hasDisplayFlag(32);
            if (hasRequestFlag6 && hasDisplayFlag6) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 64) != 64) {
                z7 = false;
            } else {
                z7 = true;
            }
            boolean hasRequestFlag7 = z7;
            boolean hasDisplayFlag7 = sbn.getNotification().hasDisplayFlag(64);
            if (hasRequestFlag7 && hasDisplayFlag7) {
                return true;
            }
            if (requestFlag <= 0 || (requestFlag & 128) != 128) {
                z8 = false;
            } else {
                z8 = true;
            }
            boolean hasRequestFlag8 = z8;
            boolean hasDisplayFlag8 = sbn.getNotification().hasDisplayFlag(128);
            if (hasRequestFlag8 && hasDisplayFlag8) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDisplayFlag(StatusBarNotification sbn, int displayFlag) {
        Notification n;
        if (sbn != null && (n = sbn.getNotification()) != null) {
            return n.hasDisplayFlag(displayFlag);
        }
        return false;
    }

    public boolean checkNotificationNotNull(StatusBarNotification sbn) {
        if (sbn != null && sbn.getNotification() != null && !TextUtils.isEmpty(sbn.getKey())) {
            return true;
        }
        return false;
    }

    private void insertNotificationLocked(final Context context, final StatusBarNotification sbn) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationPolicy.2
            @Override // java.lang.Runnable
            public void run() {
                NotificationHelper.Providers.insert(context, sbn);
            }
        });
    }

    private void updateNotificationLocked(final Context context, final StatusBarNotification sbn) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationPolicy.3
            @Override // java.lang.Runnable
            public void run() {
                NotificationHelper.Providers.update(context, sbn);
            }
        });
    }

    private void deleteNotificationLocked(final Context context, final StatusBarNotification sbn) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationPolicy.4
            @Override // java.lang.Runnable
            public void run() {
                NotificationHelper.Providers.delete(context, sbn);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NotificationRecord getNotificationRecord(Context context, StatusBarNotification sbn, RankingHelper ranking) {
        if (sbn != null && sbn.getNotification() != null && !TextUtils.isEmpty(sbn.getKey()) && ranking != null) {
            try {
                String channelId = sbn.getNotification().getChannelId();
                String pkg = sbn.getPackageName();
                int notificationUid = sbn.getUid();
                NotificationChannel channel = ranking.getNotificationChannel(pkg, notificationUid, channelId, false);
                NotificationRecord r = new NotificationRecord(context, sbn, channel);
                return r;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisappear() {
        List<StatusBarNotification> notificationList;
        synchronized (this.mLock) {
            try {
                log("handleDisappear");
                long current = System.currentTimeMillis();
                NotificationManager.from(this.mContext);
                INotificationManager service = NotificationManager.getService();
                if (service != null && (notificationList = service.getNotificationList(0)) != null && this.mLastNotificationList != null) {
                    for (StatusBarNotification sbn : notificationList) {
                        if (sbn != null) {
                            long postTime = sbn.getPostTime();
                            log("handleDisappear sbn:" + sbn.toString());
                            log("handleDisappear current:" + current + " postTime=" + postTime);
                            if (current - postTime >= 604800000) {
                                service.cancelNotificationWithTag(sbn.getPackageName(), sbn.getTag(), sbn.getId(), sbn.getUser().getIdentifier());
                                log("handleDisappear reomve sbn:" + sbn);
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
        this.mHandler.scheduleDisappear(3600000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleProviders() {
        if (NotificationHelper.Providers.isProviderReady(this.mContext)) {
            log("handleLoadProviders providerReady");
            this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationPolicy.5
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        NotificationPolicy.log("loadProviders");
                        NotificationPolicy.this.mLastNotificationList.clear();
                        NotificationPolicy.this.mLastNotificationsByKey.clear();
                        List<StatusBarNotification> lastList = NotificationHelper.Providers.query(NotificationPolicy.this.mContext);
                        if (lastList != null && !lastList.isEmpty()) {
                            for (StatusBarNotification sbn : lastList) {
                                NotificationPolicy.log("loadProviders sbn:" + sbn.toString());
                                NotificationRecord r = NotificationPolicy.this.getNotificationRecord(NotificationPolicy.this.mContext, sbn, NotificationPolicy.this.mRankingHelper);
                                if (r != null) {
                                    NotificationPolicy.this.mLastNotificationList.add(r);
                                    NotificationPolicy.this.mLastNotificationsByKey.put(sbn.getKey(), r);
                                }
                            }
                        }
                        if (NotificationPolicy.this.mListener != null) {
                            NotificationPolicy.this.mListener.onProvidersLoaded(NotificationPolicy.this.mLastNotificationList, NotificationPolicy.this.mLastNotificationsByKey);
                        }
                        NotificationPolicy.this.mHandler.scheduleDisappear(JobStatus.DEFAULT_TRIGGER_MAX_DELAY);
                    } catch (Exception e) {
                        NotificationPolicy.log("loadProviders e=" + e);
                    }
                }
            });
            return;
        }
        this.mHandler.scheduleProviders(1000L);
    }

    private XmlPolicy getNotificationPolicy(String packageName) {
        if (!TextUtils.isEmpty(packageName) && this.mNotificationsPolicy != null) {
            if (this.mNotificationsPolicy.containsKey(packageName)) {
                return this.mNotificationsPolicy.get(packageName);
            }
            String matchedKey = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            for (String key : this.mNotificationsPolicy.keySet()) {
                try {
                    if (!TextUtils.isEmpty(key) && key.contains("*")) {
                        String prefixKey = key.substring(0, key.length() - 1);
                        if (!TextUtils.isEmpty(prefixKey) && packageName.startsWith(prefixKey) && matchedKey != null && matchedKey.length() < key.length()) {
                            matchedKey = key;
                        }
                    }
                } catch (Exception e) {
                }
            }
            if (!TextUtils.isEmpty(matchedKey) && this.mNotificationsPolicy.containsKey(matchedKey)) {
                return this.mNotificationsPolicy.get(matchedKey);
            }
            xpPackageInfo xpPms = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
            if (xpPms != null && xpPms.notificationFlag > 0 && xpPms.notificationFlag == 1) {
                XmlPolicy policy = new XmlPolicy();
                policy.packageName = packageName;
                policy.majorPriority = 0;
                policy.displayFlag = 4;
                policy.clearFlag = 1;
                this.mNotificationsPolicy.put(packageName, policy);
                return policy;
            } else if (this.mNotificationsPolicy.containsKey("*")) {
                return this.mNotificationsPolicy.get("*");
            } else {
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public static ArrayMap<String, XmlPolicy> parseXml(Context context, InputStream is) {
        XmlPolicy policy;
        String NODE_ITEM;
        String ATTR_NAME;
        String NODE_ITEM2 = Settings.TAG_ITEM;
        String ATTR_NAME2 = Settings.ATTR_NAME;
        ArrayMap<String, XmlPolicy> map = new ArrayMap<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            try {
                parser.setInput(is, "utf-8");
                XmlPolicy policy2 = null;
                int eventType = parser.getEventType();
                while (eventType != 1) {
                    String nodeName = parser.getName();
                    Log.i(TAG, "parseXml nodeName=" + nodeName);
                    try {
                        if (!TextUtils.isEmpty(nodeName)) {
                            switch (eventType) {
                                case 2:
                                    Log.i(TAG, "parseXml start tag");
                                    if (Settings.TAG_ITEM.equals(nodeName.toLowerCase())) {
                                        String packageName = parser.getAttributeValue(null, Settings.ATTR_NAME);
                                        String majorPriority = parser.getAttributeValue(null, "majorPriority");
                                        String displayFlag = parser.getAttributeValue(null, "displayFlag");
                                        policy = policy2;
                                        String attributeValue = parser.getAttributeValue(null, "clearFlag");
                                        if (TextUtils.isEmpty(packageName)) {
                                            NODE_ITEM = NODE_ITEM2;
                                            ATTR_NAME = ATTR_NAME2;
                                        } else {
                                            XmlPolicy policy3 = new XmlPolicy();
                                            NODE_ITEM = NODE_ITEM2;
                                            try {
                                                policy3.packageName = packageName.trim();
                                                ATTR_NAME = ATTR_NAME2;
                                                try {
                                                    if (!TextUtils.isEmpty(displayFlag)) {
                                                        policy3.displayFlag = Integer.parseInt(displayFlag.trim());
                                                    }
                                                } catch (Exception e) {
                                                    policy3.displayFlag = -1;
                                                }
                                                try {
                                                    if (!TextUtils.isEmpty(majorPriority)) {
                                                        policy3.majorPriority = Integer.parseInt(majorPriority.trim());
                                                    }
                                                } catch (Exception e2) {
                                                    policy3.majorPriority = -1;
                                                }
                                                try {
                                                    if (!TextUtils.isEmpty(attributeValue)) {
                                                        policy3.clearFlag = Integer.parseInt(attributeValue.trim());
                                                    }
                                                } catch (Exception e3) {
                                                    policy3.clearFlag = -1;
                                                }
                                                policy = policy3;
                                            } catch (Exception e4) {
                                                e = e4;
                                                Log.i(TAG, "parseXml e=" + e);
                                                return map;
                                            }
                                        }
                                        policy2 = policy;
                                        break;
                                    }
                                    break;
                                case 3:
                                    try {
                                        Log.i(TAG, "parseXml end tag");
                                        if (policy2 != null) {
                                            map.put(policy2.packageName, policy2);
                                        }
                                        policy2 = null;
                                        NODE_ITEM = NODE_ITEM2;
                                        ATTR_NAME = ATTR_NAME2;
                                        break;
                                    } catch (Exception e5) {
                                        e = e5;
                                        Log.i(TAG, "parseXml e=" + e);
                                        return map;
                                    }
                            }
                            eventType = parser.next();
                            NODE_ITEM2 = NODE_ITEM;
                            ATTR_NAME2 = ATTR_NAME;
                        }
                        eventType = parser.next();
                        NODE_ITEM2 = NODE_ITEM;
                        ATTR_NAME2 = ATTR_NAME;
                    } catch (Exception e6) {
                        e = e6;
                        Log.i(TAG, "parseXml e=" + e);
                        return map;
                    }
                    policy = policy2;
                    NODE_ITEM = NODE_ITEM2;
                    ATTR_NAME = ATTR_NAME2;
                    policy2 = policy;
                }
            } catch (Exception e7) {
                e = e7;
                Log.i(TAG, "parseXml e=" + e);
                return map;
            }
        } catch (Exception e8) {
            e = e8;
        }
        return map;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class WorkerHandler extends Handler {
        private static final int MESSAGE_DISAPPEAR = 1001;
        private static final int MESSAGE_PROVIDERS = 1002;

        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1001:
                    NotificationPolicy.this.handleDisappear();
                    return;
                case MESSAGE_PROVIDERS /* 1002 */:
                    NotificationPolicy.this.handleProviders();
                    return;
                default:
                    return;
            }
        }

        public void scheduleDisappear(long delayMillis) {
            removeMessages(1001);
            Message m = Message.obtain(this, 1001);
            sendMessageDelayed(m, delayMillis);
        }

        public void scheduleProviders(long delayMillis) {
            removeMessages(MESSAGE_PROVIDERS);
            Message m = Message.obtain(this, (int) MESSAGE_PROVIDERS);
            sendMessageDelayed(m, delayMillis);
        }
    }

    /* loaded from: classes.dex */
    public static class XmlPolicy {
        public String packageName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public int majorPriority = -1;
        public int displayFlag = -1;
        public int clearFlag = -1;

        public String toString() {
            return "XmlPolicy:{packageName=" + this.packageName + " majorPriority=" + this.majorPriority + " displayFlag=" + this.displayFlag + " clearFlag=" + this.clearFlag + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void printXmlPolicy() {
        if (this.mNotificationsPolicy != null) {
            try {
                for (String key : this.mNotificationsPolicy.keySet()) {
                    log(this.mNotificationsPolicy.get(key).toString());
                }
            } catch (Exception e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String msg) {
        Log.i(TAG, msg);
    }
}
