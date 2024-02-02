package com.android.server.connectivity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.MessageUtils;
import com.android.server.connectivity.NetworkNotificationManager;
import java.util.HashMap;
/* loaded from: classes.dex */
public class LingerMonitor {
    private static final boolean DBG = true;
    public static final int DEFAULT_NOTIFICATION_DAILY_LIMIT = 3;
    public static final long DEFAULT_NOTIFICATION_RATE_LIMIT_MILLIS = 60000;
    @VisibleForTesting
    public static final int NOTIFY_TYPE_NONE = 0;
    public static final int NOTIFY_TYPE_NOTIFICATION = 1;
    public static final int NOTIFY_TYPE_TOAST = 2;
    private static final boolean VDBG = false;
    private final Context mContext;
    private final int mDailyLimit;
    private long mFirstNotificationMillis;
    private long mLastNotificationMillis;
    private int mNotificationCounter;
    private final NetworkNotificationManager mNotifier;
    private final long mRateLimitMillis;
    private static final String TAG = LingerMonitor.class.getSimpleName();
    private static final HashMap<String, Integer> TRANSPORT_NAMES = makeTransportToNameMap();
    @VisibleForTesting
    public static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));
    private static SparseArray<String> sNotifyTypeNames = MessageUtils.findMessageNames(new Class[]{LingerMonitor.class}, new String[]{"NOTIFY_TYPE_"});
    private final SparseIntArray mNotifications = new SparseIntArray();
    private final SparseBooleanArray mEverNotified = new SparseBooleanArray();

    public LingerMonitor(Context context, NetworkNotificationManager notifier, int dailyLimit, long rateLimitMillis) {
        this.mContext = context;
        this.mNotifier = notifier;
        this.mDailyLimit = dailyLimit;
        this.mRateLimitMillis = rateLimitMillis;
    }

    private static HashMap<String, Integer> makeTransportToNameMap() {
        SparseArray<String> numberToName = MessageUtils.findMessageNames(new Class[]{NetworkCapabilities.class}, new String[]{"TRANSPORT_"});
        HashMap<String, Integer> nameToNumber = new HashMap<>();
        for (int i = 0; i < numberToName.size(); i++) {
            nameToNumber.put(numberToName.valueAt(i), Integer.valueOf(numberToName.keyAt(i)));
        }
        return nameToNumber;
    }

    private static boolean hasTransport(NetworkAgentInfo nai, int transport) {
        return nai.networkCapabilities.hasTransport(transport);
    }

    private int getNotificationSource(NetworkAgentInfo toNai) {
        for (int i = 0; i < this.mNotifications.size(); i++) {
            if (this.mNotifications.valueAt(i) == toNai.network.netId) {
                return this.mNotifications.keyAt(i);
            }
        }
        return 0;
    }

    private boolean everNotified(NetworkAgentInfo nai) {
        return this.mEverNotified.get(nai.network.netId, false);
    }

    @VisibleForTesting
    public boolean isNotificationEnabled(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        String[] notifySwitches = this.mContext.getResources().getStringArray(17236023);
        for (String notifySwitch : notifySwitches) {
            if (!TextUtils.isEmpty(notifySwitch)) {
                String[] transports = notifySwitch.split("-", 2);
                if (transports.length != 2) {
                    Log.e(TAG, "Invalid network switch notification configuration: " + notifySwitch);
                } else {
                    int fromTransport = TRANSPORT_NAMES.get("TRANSPORT_" + transports[0]).intValue();
                    int toTransport = TRANSPORT_NAMES.get("TRANSPORT_" + transports[1]).intValue();
                    if (hasTransport(fromNai, fromTransport) && hasTransport(toNai, toTransport)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void showNotification(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        this.mNotifier.showNotification(fromNai.network.netId, NetworkNotificationManager.NotificationType.NETWORK_SWITCH, fromNai, toNai, createNotificationIntent(), true);
    }

    @VisibleForTesting
    protected PendingIntent createNotificationIntent() {
        return PendingIntent.getActivityAsUser(this.mContext, 0, CELLULAR_SETTINGS, 268435456, null, UserHandle.CURRENT);
    }

    private void maybeStopNotifying(NetworkAgentInfo nai) {
        int fromNetId = getNotificationSource(nai);
        if (fromNetId != 0) {
            this.mNotifications.delete(fromNetId);
            this.mNotifier.clearNotification(fromNetId);
        }
    }

    private void notify(NetworkAgentInfo fromNai, NetworkAgentInfo toNai, boolean forceToast) {
        int notifyType = this.mContext.getResources().getInteger(17694828);
        if (notifyType == 1 && forceToast) {
            notifyType = 2;
        }
        switch (notifyType) {
            case 0:
                return;
            case 1:
                showNotification(fromNai, toNai);
                break;
            case 2:
                this.mNotifier.showToast(fromNai, toNai);
                break;
            default:
                String str = TAG;
                Log.e(str, "Unknown notify type " + notifyType);
                return;
        }
        String str2 = TAG;
        StringBuilder sb = new StringBuilder();
        sb.append("Notifying switch from=");
        sb.append(fromNai.name());
        sb.append(" to=");
        sb.append(toNai.name());
        sb.append(" type=");
        SparseArray<String> sparseArray = sNotifyTypeNames;
        sb.append(sparseArray.get(notifyType, "unknown(" + notifyType + ")"));
        Log.d(str2, sb.toString());
        this.mNotifications.put(fromNai.network.netId, toNai.network.netId);
        this.mEverNotified.put(fromNai.network.netId, true);
    }

    public void noteLingerDefaultNetwork(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        maybeStopNotifying(fromNai);
        if (fromNai.everValidated) {
            boolean forceToast = fromNai.networkCapabilities.hasCapability(17);
            if (everNotified(fromNai) || fromNai.lastValidated || !isNotificationEnabled(fromNai, toNai)) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (isRateLimited(now) || isAboveDailyLimit(now)) {
                return;
            }
            notify(fromNai, toNai, forceToast);
        }
    }

    public void noteDisconnect(NetworkAgentInfo nai) {
        this.mNotifications.delete(nai.network.netId);
        this.mEverNotified.delete(nai.network.netId);
        maybeStopNotifying(nai);
    }

    private boolean isRateLimited(long now) {
        long millisSinceLast = now - this.mLastNotificationMillis;
        if (millisSinceLast < this.mRateLimitMillis) {
            return true;
        }
        this.mLastNotificationMillis = now;
        return false;
    }

    private boolean isAboveDailyLimit(long now) {
        if (this.mFirstNotificationMillis == 0) {
            this.mFirstNotificationMillis = now;
        }
        long millisSinceFirst = now - this.mFirstNotificationMillis;
        if (millisSinceFirst > 86400000) {
            this.mNotificationCounter = 0;
            this.mFirstNotificationMillis = 0L;
        }
        if (this.mNotificationCounter >= this.mDailyLimit) {
            return true;
        }
        this.mNotificationCounter++;
        return false;
    }
}
