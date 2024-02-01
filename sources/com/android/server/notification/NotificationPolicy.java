package com.android.server.notification;

import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
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
import com.android.server.job.controllers.JobStatus;
import com.android.server.notification.NotificationHelper;
import com.android.server.notification.NotificationManagerService;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
                        } catch (Throwable th) {
                            if (fis != null) {
                                try {
                                    fis.close();
                                } catch (Exception e) {
                                }
                            }
                            throw th;
                        }
                    } catch (Exception e2) {
                        NotificationPolicy.log("loadXmlPolicy e=" + e2);
                        if (fis != null) {
                            fis.close();
                        }
                    }
                } catch (Exception e3) {
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
            return "";
        } catch (Exception e) {
            return "";
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
                sbn.getNotification().getChannelId();
                sbn.getPackageName();
                sbn.getUid();
                NotificationRecord r = new NotificationRecord(context, sbn, null);
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
        ArrayMap<String, XmlPolicy> arrayMap;
        if (!TextUtils.isEmpty(packageName) && (arrayMap = this.mNotificationsPolicy) != null) {
            if (arrayMap.containsKey(packageName)) {
                return this.mNotificationsPolicy.get(packageName);
            }
            String matchedKey = "";
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
    /* JADX WARN: Can't wrap try/catch for region: R(7:9|(5:11|(2:13|(1:15)(7:25|26|(1:28)|29|17|18|19))(2:33|(9:35|36|37|38|(15:40|41|42|43|44|45|46|(1:48)|49|50|(1:52)|53|54|(1:56)|57)(1:71)|58|17|18|19)(1:75))|21|22|23)(1:76)|16|17|18|19|7) */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationPolicy.XmlPolicy> parseXml(android.content.Context r20, java.io.InputStream r21) {
        /*
            Method dump skipped, instructions count: 358
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationPolicy.parseXml(android.content.Context, java.io.InputStream):android.util.ArrayMap");
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
            int i = msg.what;
            if (i == 1001) {
                NotificationPolicy.this.handleDisappear();
            } else if (i == MESSAGE_PROVIDERS) {
                NotificationPolicy.this.handleProviders();
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
        public String packageName = "";
        public int majorPriority = -1;
        public int displayFlag = -1;
        public int clearFlag = -1;

        public String toString() {
            return "XmlPolicy:{packageName=" + this.packageName + " majorPriority=" + this.majorPriority + " displayFlag=" + this.displayFlag + " clearFlag=" + this.clearFlag + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void printXmlPolicy() {
        ArrayMap<String, XmlPolicy> arrayMap = this.mNotificationsPolicy;
        if (arrayMap != null) {
            try {
                for (String key : arrayMap.keySet()) {
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
