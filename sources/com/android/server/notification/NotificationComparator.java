package com.android.server.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.telecom.TelecomManager;
import com.android.internal.util.NotificationMessagingUtil;
import java.util.Comparator;
import java.util.Objects;

/* loaded from: classes.dex */
public class NotificationComparator implements Comparator<NotificationRecord> {
    private final Context mContext;
    private String mDefaultPhoneApp;
    private final NotificationMessagingUtil mMessagingUtil;
    private final BroadcastReceiver mPhoneAppBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationComparator.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            NotificationComparator.this.mDefaultPhoneApp = intent.getStringExtra("android.telecom.extra.CHANGE_DEFAULT_DIALER_PACKAGE_NAME");
        }
    };

    public NotificationComparator(Context context) {
        this.mContext = context;
        this.mContext.registerReceiver(this.mPhoneAppBroadcastReceiver, new IntentFilter("android.telecom.action.DEFAULT_DIALER_CHANGED"));
        this.mMessagingUtil = new NotificationMessagingUtil(this.mContext);
    }

    @Override // java.util.Comparator
    public int compare(NotificationRecord left, NotificationRecord right) {
        int leftImportance = left.getImportance();
        int rightImportance = right.getImportance();
        boolean isLeftHighImportance = leftImportance >= 3;
        boolean isRightHighImportance = rightImportance >= 3;
        if (Settings.Secure.getInt(this.mContext.getContentResolver(), "new_interruption_model", 1) == 1 && isLeftHighImportance != isRightHighImportance) {
            return Boolean.compare(isLeftHighImportance, isRightHighImportance) * (-1);
        }
        boolean leftImportantColorized = isImportantColorized(left);
        boolean rightImportantColorized = isImportantColorized(right);
        if (leftImportantColorized != rightImportantColorized) {
            return Boolean.compare(leftImportantColorized, rightImportantColorized) * (-1);
        }
        boolean leftImportantOngoing = isImportantOngoing(left);
        boolean rightImportantOngoing = isImportantOngoing(right);
        if (leftImportantOngoing != rightImportantOngoing) {
            return Boolean.compare(leftImportantOngoing, rightImportantOngoing) * (-1);
        }
        boolean leftMessaging = isImportantMessaging(left);
        boolean rightMessaging = isImportantMessaging(right);
        if (leftMessaging != rightMessaging) {
            return Boolean.compare(leftMessaging, rightMessaging) * (-1);
        }
        boolean leftPeople = isImportantPeople(left);
        boolean rightPeople = isImportantPeople(right);
        int contactAffinityComparison = Float.compare(left.getContactAffinity(), right.getContactAffinity());
        if (leftPeople && rightPeople) {
            if (contactAffinityComparison != 0) {
                return contactAffinityComparison * (-1);
            }
        } else if (leftPeople != rightPeople) {
            return Boolean.compare(leftPeople, rightPeople) * (-1);
        }
        if (leftImportance != rightImportance) {
            return Integer.compare(leftImportance, rightImportance) * (-1);
        }
        if (contactAffinityComparison != 0) {
            return contactAffinityComparison * (-1);
        }
        int leftPackagePriority = left.getPackagePriority();
        int rightPackagePriority = right.getPackagePriority();
        if (leftPackagePriority != rightPackagePriority) {
            return Integer.compare(leftPackagePriority, rightPackagePriority) * (-1);
        }
        int leftPriority = left.sbn.getNotification().priority;
        int rightPriority = right.sbn.getNotification().priority;
        if (leftPriority != rightPriority) {
            return Integer.compare(leftPriority, rightPriority) * (-1);
        }
        boolean leftInterruptive = left.isInterruptive();
        boolean rightInterruptive = right.isInterruptive();
        return leftInterruptive != rightInterruptive ? Boolean.compare(leftInterruptive, rightInterruptive) * (-1) : Long.compare(left.getRankingTimeMs(), right.getRankingTimeMs()) * (-1);
    }

    private boolean isImportantColorized(NotificationRecord record) {
        if (record.getImportance() < 2) {
            return false;
        }
        return record.getNotification().isColorized();
    }

    private boolean isImportantOngoing(NotificationRecord record) {
        if (isOngoing(record) && record.getImportance() >= 2) {
            return isCall(record) || isMediaNotification(record);
        }
        return false;
    }

    protected boolean isImportantPeople(NotificationRecord record) {
        return record.getImportance() >= 2 && record.getContactAffinity() > 0.0f;
    }

    protected boolean isImportantMessaging(NotificationRecord record) {
        return this.mMessagingUtil.isImportantMessaging(record.sbn, record.getImportance());
    }

    private boolean isOngoing(NotificationRecord record) {
        return (record.getNotification().flags & 64) != 0;
    }

    private boolean isMediaNotification(NotificationRecord record) {
        return record.getNotification().hasMediaSession();
    }

    private boolean isCall(NotificationRecord record) {
        return record.isCategory("call") && isDefaultPhoneApp(record.sbn.getPackageName());
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (this.mDefaultPhoneApp == null) {
            TelecomManager telecomm = (TelecomManager) this.mContext.getSystemService("telecom");
            this.mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultDialerPackage() : null;
        }
        return Objects.equals(pkg, this.mDefaultPhoneApp);
    }
}
