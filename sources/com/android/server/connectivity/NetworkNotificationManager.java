package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.WifiInfo;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.widget.Toast;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;
/* loaded from: classes.dex */
public class NetworkNotificationManager {
    private static final boolean DBG = true;
    private static final String TAG = NetworkNotificationManager.class.getSimpleName();
    private static final boolean VDBG = false;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final SparseIntArray mNotificationTypeMap = new SparseIntArray();
    private final TelephonyManager mTelephonyManager;

    /* loaded from: classes.dex */
    public enum NotificationType {
        LOST_INTERNET(742),
        NETWORK_SWITCH(743),
        NO_INTERNET(741),
        SIGN_IN(740);
        
        public final int eventId;

        NotificationType(int eventId) {
            this.eventId = eventId;
            Holder.sIdToTypeMap.put(eventId, this);
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Holder {
            private static SparseArray<NotificationType> sIdToTypeMap = new SparseArray<>();

            private Holder() {
            }
        }

        public static NotificationType getFromId(int id) {
            return (NotificationType) Holder.sIdToTypeMap.get(id);
        }
    }

    public NetworkNotificationManager(Context c, TelephonyManager t, NotificationManager n) {
        this.mContext = c;
        this.mTelephonyManager = t;
        this.mNotificationManager = n;
    }

    private static int getFirstTransportType(NetworkAgentInfo nai) {
        for (int i = 0; i < 64; i++) {
            if (nai.networkCapabilities.hasTransport(i)) {
                return i;
            }
        }
        return -1;
    }

    private static String getTransportName(int transportType) {
        Resources r = Resources.getSystem();
        String[] networkTypes = r.getStringArray(17236070);
        try {
            return networkTypes[transportType];
        } catch (IndexOutOfBoundsException e) {
            return r.getString(17040352);
        }
    }

    private static int getIcon(int transportType) {
        if (transportType == 1) {
            return 17303458;
        }
        return 17303454;
    }

    public void showNotification(int id, NotificationType notifyType, NetworkAgentInfo nai, NetworkAgentInfo switchToNai, PendingIntent intent, boolean highPriority) {
        int transportType;
        String name;
        CharSequence details;
        CharSequence title;
        String tag = tagFor(id);
        int eventId = notifyType.eventId;
        if (nai != null) {
            transportType = getFirstTransportType(nai);
            String extraInfo = nai.networkInfo.getExtraInfo();
            name = TextUtils.isEmpty(extraInfo) ? nai.networkCapabilities.getSSID() : extraInfo;
            if (!nai.networkCapabilities.hasCapability(12)) {
                return;
            }
        } else {
            transportType = 0;
            name = null;
        }
        int transportType2 = transportType;
        int previousEventId = this.mNotificationTypeMap.get(id);
        NotificationType previousNotifyType = NotificationType.getFromId(previousEventId);
        if (priority(previousNotifyType) > priority(notifyType)) {
            Slog.d(TAG, String.format("ignoring notification %s for network %s with existing notification %s", notifyType, Integer.valueOf(id), previousNotifyType));
            return;
        }
        clearNotification(id);
        Slog.d(TAG, String.format("showNotification tag=%s event=%s transport=%s name=%s highPriority=%s", tag, nameOf(eventId), getTransportName(transportType2), name, Boolean.valueOf(highPriority)));
        Resources r = Resources.getSystem();
        int icon = getIcon(transportType2);
        if (notifyType == NotificationType.NO_INTERNET && transportType2 == 1) {
            title = r.getString(17041117, 0);
            details = r.getString(17041118);
        } else if (notifyType == NotificationType.LOST_INTERNET && transportType2 == 1) {
            title = r.getString(17041117, 0);
            details = r.getString(17041118);
        } else if (notifyType == NotificationType.SIGN_IN) {
            switch (transportType2) {
                case 0:
                    CharSequence title2 = r.getString(17040345, 0);
                    CharSequence details2 = this.mTelephonyManager.getNetworkOperatorName();
                    title = title2;
                    details = details2;
                    break;
                case 1:
                    title = r.getString(17041107, 0);
                    details = r.getString(17040346, WifiInfo.removeDoubleQuotes(nai.networkCapabilities.getSSID()));
                    break;
                default:
                    CharSequence title3 = r.getString(17040345, 0);
                    CharSequence details3 = r.getString(17040346, name);
                    details = details3;
                    title = title3;
                    break;
            }
        } else if (notifyType != NotificationType.NETWORK_SWITCH) {
            String str = TAG;
            Slog.wtf(str, "Unknown notification type " + notifyType + " on network transport " + getTransportName(transportType2));
            return;
        } else {
            String fromTransport = getTransportName(transportType2);
            String toTransport = getTransportName(getFirstTransportType(switchToNai));
            CharSequence title4 = r.getString(17040349, toTransport);
            details = r.getString(17040350, toTransport, fromTransport);
            title = title4;
        }
        CharSequence title5 = title;
        String channelId = highPriority ? SystemNotificationChannels.NETWORK_ALERTS : SystemNotificationChannels.NETWORK_STATUS;
        Notification.Builder builder = new Notification.Builder(this.mContext, channelId).setWhen(System.currentTimeMillis()).setShowWhen(notifyType == NotificationType.NETWORK_SWITCH).setSmallIcon(icon).setAutoCancel(true).setTicker(title5).setColor(this.mContext.getColor(17170861)).setContentTitle(title5).setContentIntent(intent).setLocalOnly(true).setOnlyAlertOnce(true);
        if (notifyType == NotificationType.NETWORK_SWITCH) {
            builder.setStyle(new Notification.BigTextStyle().bigText(details));
        } else {
            builder.setContentText(details);
        }
        if (notifyType == NotificationType.SIGN_IN) {
            builder.extend(new Notification.TvExtender().setChannelId(channelId));
        }
        Notification notification = builder.build();
        this.mNotificationTypeMap.put(id, eventId);
        try {
            this.mNotificationManager.notifyAsUser(tag, eventId, notification, UserHandle.ALL);
        } catch (NullPointerException npe) {
            Slog.d(TAG, "setNotificationVisible: visible notificationManager error", npe);
        }
    }

    public void clearNotification(int id) {
        if (this.mNotificationTypeMap.indexOfKey(id) < 0) {
            return;
        }
        String tag = tagFor(id);
        int eventId = this.mNotificationTypeMap.get(id);
        Slog.d(TAG, String.format("clearing notification tag=%s event=%s", tag, nameOf(eventId)));
        try {
            this.mNotificationManager.cancelAsUser(tag, eventId, UserHandle.ALL);
        } catch (NullPointerException npe) {
            Slog.d(TAG, String.format("failed to clear notification tag=%s event=%s", tag, nameOf(eventId)), npe);
        }
        this.mNotificationTypeMap.delete(id);
    }

    public void setProvNotificationVisible(boolean visible, int id, String action) {
        if (visible) {
            Intent intent = new Intent(action);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this.mContext, 0, intent, 0);
            showNotification(id, NotificationType.SIGN_IN, null, null, pendingIntent, false);
            return;
        }
        clearNotification(id);
    }

    public void showToast(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        String fromTransport = getTransportName(getFirstTransportType(fromNai));
        String toTransport = getTransportName(getFirstTransportType(toNai));
        String text = this.mContext.getResources().getString(17040351, fromTransport, toTransport);
        Toast.makeText(this.mContext, text, 1).show();
    }

    @VisibleForTesting
    static String tagFor(int id) {
        return String.format("ConnectivityNotification:%d", Integer.valueOf(id));
    }

    @VisibleForTesting
    static String nameOf(int eventId) {
        NotificationType t = NotificationType.getFromId(eventId);
        return t != null ? t.name() : "UNKNOWN";
    }

    private static int priority(NotificationType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case SIGN_IN:
                return 4;
            case NO_INTERNET:
                return 3;
            case NETWORK_SWITCH:
                return 2;
            case LOST_INTERNET:
                return 1;
            default:
                return 0;
        }
    }
}
