package com.android.server.notification;

import android.app.Notification;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.xiaopeng.server.input.xpInputManagerService;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public class NotificationHelper {
    private static final boolean DEBUG = true;
    private static final String TAG = "NotificationHelper";
    private static final String[] sNotificationExtras = {"android.text", "android.title", "android.subText", "android.messages", "android.intent.extra.CHANNEL_ID"};

    public static ContentValues createContentValues(StatusBarNotification sbn) {
        if (sbn != null && !TextUtils.isEmpty(sbn.getKey()) && sbn.getNotification() != null) {
            NotificationData data = new NotificationData();
            data.from(sbn);
            ContentValues values = new ContentValues();
            values.put("_key", data.key);
            values.put("tag", data.tag);
            values.put("id", Integer.valueOf(data.id));
            values.put(WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.valueOf(data.uid));
            values.put("user", Integer.valueOf(data.user));
            values.put("initialPid", Integer.valueOf(data.initialPid));
            values.put("pkg", data.pkg);
            values.put("opPkg", data.opPkg);
            values.put("postTime", Long.valueOf(data.postTime));
            values.put("groupKey", data.groupKey);
            values.put("overrideGroupKey", data.overrideGroupKey);
            values.put("icon", Integer.valueOf(data.icon));
            values.put("number", Integer.valueOf(data.number));
            values.put(xpInputManagerService.InputPolicyKey.KEY_FLAGS, Integer.valueOf(data.flags));
            values.put(xpInputManagerService.InputPolicyKey.KEY_PRIORITY, Integer.valueOf(data.priority));
            values.put("category", data.category);
            if (data.extras != null) {
                values.put("extras", marshall(data.extras));
            }
            values.put("channelId", data.channelId);
            if (data.smallIcon != null) {
                values.put("smallIcon", marshall(data.smallIcon));
            }
            if (data.largeIcon != null) {
                values.put("largeIcon", marshall(data.largeIcon));
            }
            values.put("majorPriority", Integer.valueOf(data.majorPriority));
            values.put("displayFlag", Integer.valueOf(data.displayFlag));
            values.put("clearFlag", Integer.valueOf(data.clearFlag));
            return values;
        }
        return null;
    }

    public static List<StatusBarNotification> getNotifications(Context context, Cursor cursor) {
        List<StatusBarNotification> list = new ArrayList<>();
        if (cursor != null && !cursor.isClosed() && cursor.getCount() > 0) {
            try {
                try {
                    log("getNotifications count=" + cursor.getCount());
                    cursor.moveToFirst();
                    do {
                        try {
                            String key = cursor.getString(cursor.getColumnIndex("_key"));
                            log("getNotifications key=" + key);
                            if (!TextUtils.isEmpty(key)) {
                                NotificationData data = new NotificationData();
                                data.key = key;
                                data.tag = cursor.getString(cursor.getColumnIndex("tag"));
                                data.id = cursor.getInt(cursor.getColumnIndex("id"));
                                data.uid = cursor.getInt(cursor.getColumnIndex(WatchlistLoggingHandler.WatchlistEventKeys.UID));
                                data.user = cursor.getInt(cursor.getColumnIndex("user"));
                                data.initialPid = cursor.getInt(cursor.getColumnIndex("initialPid"));
                                data.pkg = cursor.getString(cursor.getColumnIndex("pkg"));
                                data.opPkg = cursor.getString(cursor.getColumnIndex("opPkg"));
                                try {
                                    data.postTime = cursor.getLong(cursor.getColumnIndex("postTime"));
                                } catch (Exception e) {
                                }
                                data.groupKey = cursor.getString(cursor.getColumnIndex("groupKey"));
                                data.overrideGroupKey = cursor.getString(cursor.getColumnIndex("overrideGroupKey"));
                                data.icon = cursor.getInt(cursor.getColumnIndex("icon"));
                                data.number = cursor.getInt(cursor.getColumnIndex("number"));
                                data.flags = cursor.getInt(cursor.getColumnIndex(xpInputManagerService.InputPolicyKey.KEY_FLAGS));
                                data.priority = cursor.getInt(cursor.getColumnIndex(xpInputManagerService.InputPolicyKey.KEY_PRIORITY));
                                data.category = cursor.getString(cursor.getColumnIndex("category"));
                                try {
                                    Parcel parcel = unmarshall(cursor.getBlob(cursor.getColumnIndex("extras")));
                                    if (parcel != null) {
                                        data.extras = (Bundle) Bundle.CREATOR.createFromParcel(parcel);
                                    }
                                } catch (Exception e2) {
                                }
                                data.channelId = cursor.getString(cursor.getColumnIndex("channelId"));
                                try {
                                    Parcel parcel2 = unmarshall(cursor.getBlob(cursor.getColumnIndex("smallIcon")));
                                    if (parcel2 != null) {
                                        data.smallIcon = (Icon) Icon.CREATOR.createFromParcel(parcel2);
                                    }
                                } catch (Exception e3) {
                                }
                                try {
                                    Parcel parcel3 = unmarshall(cursor.getBlob(cursor.getColumnIndex("largeIcon")));
                                    if (parcel3 != null) {
                                        data.largeIcon = (Icon) Icon.CREATOR.createFromParcel(parcel3);
                                    }
                                } catch (Exception e4) {
                                }
                                data.majorPriority = cursor.getInt(cursor.getColumnIndex("majorPriority"));
                                data.displayFlag = cursor.getInt(cursor.getColumnIndex("displayFlag"));
                                data.clearFlag = cursor.getInt(cursor.getColumnIndex("clearFlag"));
                                list.add(data.to(context));
                            }
                        } catch (Exception e5) {
                        }
                    } while (cursor.moveToNext());
                } catch (Exception e6) {
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    /* loaded from: classes.dex */
    public static class Providers {
        private static final String AUTHORITY = "com.xiaopeng.providers.notification";
        private static final Uri URI = Uri.parse("content://com.xiaopeng.providers.notification/notification");

        public static void insert(Context context, StatusBarNotification sbn) {
            if (sbn != null) {
                try {
                    if (!TextUtils.isEmpty(sbn.getKey())) {
                        ContentResolver resolver = context.getContentResolver();
                        ContentValues values = NotificationHelper.createContentValues(sbn);
                        resolver.insert(URI, values);
                    }
                } catch (Exception e) {
                    NotificationHelper.log("insert e=" + e);
                }
            }
        }

        public static void delete(Context context, StatusBarNotification sbn) {
            if (sbn != null) {
                try {
                    if (!TextUtils.isEmpty(sbn.getKey())) {
                        ContentResolver resolver = context.getContentResolver();
                        String[] selectionArgs = {sbn.getKey()};
                        resolver.delete(URI, " _key=? ", selectionArgs);
                    }
                } catch (Exception e) {
                    NotificationHelper.log("delete e=" + e);
                }
            }
        }

        public static void update(Context context, StatusBarNotification sbn) {
            if (sbn != null) {
                try {
                    if (!TextUtils.isEmpty(sbn.getKey())) {
                        ContentResolver resolver = context.getContentResolver();
                        ContentValues values = NotificationHelper.createContentValues(sbn);
                        String[] selectionArgs = {sbn.getKey()};
                        resolver.update(URI, values, "_key=?", selectionArgs);
                    }
                } catch (Exception e) {
                    NotificationHelper.log("update e=" + e);
                }
            }
        }

        public static List<StatusBarNotification> query(Context context) {
            Cursor cursor = null;
            try {
                try {
                    ContentResolver resolver = context.getContentResolver();
                    cursor = resolver.query(URI, null, null, null, null);
                    List<StatusBarNotification> notifications = NotificationHelper.getNotifications(context, cursor);
                    if (cursor != null && !cursor.isClosed()) {
                        cursor.close();
                    }
                    return notifications;
                } catch (Exception e) {
                    NotificationHelper.log("query e=" + e);
                    if (cursor == null || cursor.isClosed()) {
                        return null;
                    }
                    cursor.close();
                    return null;
                }
            } catch (Throwable th) {
                if (cursor != null && !cursor.isClosed()) {
                    cursor.close();
                }
                throw th;
            }
        }

        public static IContentProvider acquireProvider(Context context) {
            ContentResolver resolver = context.getContentResolver();
            if (resolver != null) {
                return resolver.acquireProvider(URI);
            }
            return null;
        }

        public static boolean isProviderReady(Context context) {
            return acquireProvider(context) != null;
        }
    }

    /* loaded from: classes.dex */
    public static class NotificationData {
        public String category;
        public String channelId;
        public int clearFlag;
        public int displayFlag;
        public Bundle extras;
        public int flags;
        public String groupKey;
        public int icon;
        public int id;
        public int initialPid;
        public String key;
        public Icon largeIcon;
        public int majorPriority;
        public int number;
        public String opPkg;
        public String overrideGroupKey;
        public String pkg;
        public long postTime;
        public int priority;
        public Icon smallIcon;
        public String tag;
        public int uid;
        public int user;

        public void from(StatusBarNotification sbn) {
            if (sbn != null && sbn.getNotification() != null && !TextUtils.isEmpty(sbn.getKey())) {
                this.key = sbn.getKey();
                this.tag = sbn.getTag();
                this.id = sbn.getId();
                this.uid = sbn.getUid();
                this.user = sbn.getUser().getIdentifier();
                this.initialPid = sbn.getInitialPid();
                this.pkg = sbn.getPackageName();
                this.opPkg = sbn.getOpPkg();
                this.postTime = sbn.getPostTime();
                this.groupKey = sbn.getGroupKey();
                this.overrideGroupKey = sbn.getOverrideGroupKey();
                this.icon = sbn.getNotification().icon;
                this.number = sbn.getNotification().number;
                this.flags = sbn.getNotification().flags;
                this.priority = sbn.getNotification().priority;
                this.category = sbn.getNotification().category;
                this.extras = NotificationHelper.getFilterExtras(sbn.getNotification().extras);
                this.channelId = sbn.getNotification().getChannelId();
                this.majorPriority = sbn.getNotification().majorPriority;
                this.displayFlag = sbn.getNotification().displayFlag;
                this.clearFlag = sbn.getNotification().clearFlag;
            }
        }

        public StatusBarNotification to(Context context) {
            if (!TextUtils.isEmpty(this.key)) {
                try {
                    UserHandle userHandle = new UserHandle(this.user);
                    try {
                        Notification.Builder builder = new Notification.Builder(context, this.channelId);
                        builder.setClearFlag(this.clearFlag);
                        builder.setDisplayFlag(this.displayFlag);
                        builder.setMajorPriority(this.majorPriority);
                        Notification notification = builder.build();
                        notification.icon = this.icon;
                        notification.number = this.number;
                        notification.flags = this.flags;
                        notification.extras = this.extras;
                        notification.priority = this.priority;
                        notification.category = this.category;
                        notification.extras = this.extras;
                        StatusBarNotification sbn = new StatusBarNotification(this.pkg, this.opPkg, this.id, this.tag, this.uid, this.initialPid, notification, userHandle, this.overrideGroupKey, this.postTime);
                        return sbn;
                    } catch (Exception e) {
                        return null;
                    }
                } catch (Exception e2) {
                    return null;
                }
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Bundle getFilterExtras(Bundle extras) {
        String[] strArr;
        if (extras != null) {
            Bundle bundle = new Bundle();
            for (String key : sNotificationExtras) {
                if (extras.containsKey(key)) {
                    String value = extras.getString(key);
                    if (!TextUtils.isEmpty(value)) {
                        bundle.putString(key, value);
                    }
                }
            }
            return bundle;
        }
        return null;
    }

    private static byte[] marshall(Parcelable parcelable) {
        if (parcelable != null) {
            try {
                Parcel parcel = Parcel.obtain();
                parcel.setDataPosition(0);
                parcelable.writeToParcel(parcel, 0);
                byte[] bytes = parcel.marshall();
                parcel.recycle();
                return bytes;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Parcel unmarshall(byte[] bytes) {
        if (bytes != null) {
            try {
                Parcel parcel = Parcel.obtain();
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                return parcel;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String msg) {
        Log.i(TAG, msg);
    }
}
