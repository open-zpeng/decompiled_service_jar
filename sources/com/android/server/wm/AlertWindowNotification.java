package com.android.server.wm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import com.android.server.policy.IconUtilities;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class AlertWindowNotification {
    private static final String CHANNEL_PREFIX = "com.android.server.wm.AlertWindowNotification - ";
    private static final int NOTIFICATION_ID = 0;
    private static NotificationChannelGroup sChannelGroup;
    private static int sNextRequestCode = 0;
    private IconUtilities mIconUtilities;
    private final NotificationManager mNotificationManager;
    private String mNotificationTag;
    private final String mPackageName;
    private boolean mPosted;
    private final int mRequestCode;
    private final WindowManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AlertWindowNotification(WindowManagerService service, String packageName) {
        this.mService = service;
        this.mPackageName = packageName;
        this.mNotificationManager = (NotificationManager) this.mService.mContext.getSystemService("notification");
        this.mNotificationTag = CHANNEL_PREFIX + this.mPackageName;
        int i = sNextRequestCode;
        sNextRequestCode = i + 1;
        this.mRequestCode = i;
        this.mIconUtilities = new IconUtilities(this.mService.mContext);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void post() {
        this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AlertWindowNotification$iVtcJMb6VtqtAgEtGUDCkGay0tM
            @Override // java.lang.Runnable
            public final void run() {
                AlertWindowNotification.this.onPostNotification();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancel(final boolean deleteChannel) {
        this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AlertWindowNotification$ZuqSYML-X-nkNVTba_yeIT9hJ1s
            @Override // java.lang.Runnable
            public final void run() {
                AlertWindowNotification.this.lambda$cancel$0$AlertWindowNotification(deleteChannel);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: onCancelNotification */
    public void lambda$cancel$0$AlertWindowNotification(boolean deleteChannel) {
        if (!this.mPosted) {
            return;
        }
        this.mPosted = false;
        this.mNotificationManager.cancel(this.mNotificationTag, 0);
        if (deleteChannel) {
            this.mNotificationManager.deleteNotificationChannel(this.mNotificationTag);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPostNotification() {
        Drawable drawable;
        if (this.mPosted) {
            return;
        }
        this.mPosted = true;
        Context context = this.mService.mContext;
        PackageManager pm = context.getPackageManager();
        ApplicationInfo aInfo = getApplicationInfo(pm, this.mPackageName);
        String appName = aInfo != null ? pm.getApplicationLabel(aInfo).toString() : this.mPackageName;
        createNotificationChannel(context, appName);
        String message = context.getString(17039482, appName);
        Bundle extras = new Bundle();
        extras.putStringArray("android.foregroundApps", new String[]{this.mPackageName});
        Notification.Builder builder = new Notification.Builder(context, this.mNotificationTag).setOngoing(true).setContentTitle(context.getString(17039483, appName)).setContentText(message).setSmallIcon(17301713).setColor(context.getColor(17170460)).setStyle(new Notification.BigTextStyle().bigText(message)).setLocalOnly(true).addExtras(extras).setContentIntent(getContentIntent(context, this.mPackageName));
        if (aInfo != null && (drawable = pm.getApplicationIcon(aInfo)) != null) {
            Bitmap bitmap = this.mIconUtilities.createIconBitmap(drawable);
            builder.setLargeIcon(bitmap);
        }
        this.mNotificationManager.notify(this.mNotificationTag, 0, builder.build());
    }

    private PendingIntent getContentIntent(Context context, String packageName) {
        Intent intent = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.fromParts("package", packageName, null));
        intent.setFlags(268468224);
        return PendingIntent.getActivity(context, this.mRequestCode, intent, 268435456);
    }

    private void createNotificationChannel(Context context, String appName) {
        if (sChannelGroup == null) {
            sChannelGroup = new NotificationChannelGroup(CHANNEL_PREFIX, this.mService.mContext.getString(17039480));
            this.mNotificationManager.createNotificationChannelGroup(sChannelGroup);
        }
        String nameChannel = context.getString(17039481, appName);
        if (this.mNotificationManager.getNotificationChannel(this.mNotificationTag) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(this.mNotificationTag, nameChannel, 1);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setBlockableSystem(true);
        channel.setGroup(sChannelGroup.getId());
        channel.setBypassDnd(true);
        this.mNotificationManager.createNotificationChannel(channel);
    }

    private ApplicationInfo getApplicationInfo(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
