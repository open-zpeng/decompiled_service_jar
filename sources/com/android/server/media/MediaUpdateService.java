package com.android.server.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.media.IMediaExtractorUpdateService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
/* loaded from: classes.dex */
public class MediaUpdateService extends SystemService {
    private static final String EXTRACTOR_UPDATE_SERVICE_NAME = "media.extractor.update";
    final Handler mHandler;
    private IMediaExtractorUpdateService mMediaExtractorUpdateService;
    private static final String TAG = "MediaUpdateService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String MEDIA_UPDATE_PACKAGE_NAME = SystemProperties.get("ro.mediacomponents.package");

    public MediaUpdateService(Context context) {
        super(context);
        this.mHandler = new Handler();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        if (("userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE)) && !TextUtils.isEmpty(MEDIA_UPDATE_PACKAGE_NAME)) {
            connect();
            registerBroadcastReceiver();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void connect() {
        IBinder binder = ServiceManager.getService(EXTRACTOR_UPDATE_SERVICE_NAME);
        if (binder != null) {
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.media.MediaUpdateService.1
                    @Override // android.os.IBinder.DeathRecipient
                    public void binderDied() {
                        Slog.w(MediaUpdateService.TAG, "mediaextractor died; reconnecting");
                        MediaUpdateService.this.mMediaExtractorUpdateService = null;
                        MediaUpdateService.this.connect();
                    }
                }, 0);
            } catch (Exception e) {
                binder = null;
            }
        }
        if (binder != null) {
            this.mMediaExtractorUpdateService = IMediaExtractorUpdateService.Stub.asInterface(binder);
            this.mHandler.post(new Runnable() { // from class: com.android.server.media.MediaUpdateService.2
                @Override // java.lang.Runnable
                public void run() {
                    MediaUpdateService.this.packageStateChanged();
                }
            });
            return;
        }
        Slog.w(TAG, "media.extractor.update not found.");
    }

    private void registerBroadcastReceiver() {
        BroadcastReceiver updateReceiver = new BroadcastReceiver() { // from class: com.android.server.media.MediaUpdateService.3
            /* JADX WARN: Code restructure failed: missing block: B:16:0x0033, code lost:
                if (r0.equals("android.intent.action.PACKAGE_REMOVED") != false) goto L14;
             */
            @Override // android.content.BroadcastReceiver
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            public void onReceive(android.content.Context r6, android.content.Intent r7) {
                /*
                    r5 = this;
                    java.lang.String r0 = "android.intent.extra.user_handle"
                    r1 = 0
                    int r0 = r7.getIntExtra(r0, r1)
                    if (r0 == 0) goto La
                    return
                La:
                    java.lang.String r0 = r7.getAction()
                    r2 = -1
                    int r3 = r0.hashCode()
                    r4 = 172491798(0xa480416, float:9.630418E-33)
                    if (r3 == r4) goto L36
                    r4 = 525384130(0x1f50b9c2, float:4.419937E-20)
                    if (r3 == r4) goto L2d
                    r1 = 1544582882(0x5c1076e2, float:1.6265244E17)
                    if (r3 == r1) goto L23
                    goto L40
                L23:
                    java.lang.String r1 = "android.intent.action.PACKAGE_ADDED"
                    boolean r0 = r0.equals(r1)
                    if (r0 == 0) goto L40
                    r1 = 2
                    goto L41
                L2d:
                    java.lang.String r3 = "android.intent.action.PACKAGE_REMOVED"
                    boolean r0 = r0.equals(r3)
                    if (r0 == 0) goto L40
                    goto L41
                L36:
                    java.lang.String r1 = "android.intent.action.PACKAGE_CHANGED"
                    boolean r0 = r0.equals(r1)
                    if (r0 == 0) goto L40
                    r1 = 1
                    goto L41
                L40:
                    r1 = r2
                L41:
                    switch(r1) {
                        case 0: goto L51;
                        case 1: goto L4b;
                        case 2: goto L45;
                        default: goto L44;
                    }
                L44:
                    goto L64
                L45:
                    com.android.server.media.MediaUpdateService r0 = com.android.server.media.MediaUpdateService.this
                    com.android.server.media.MediaUpdateService.access$200(r0)
                    goto L64
                L4b:
                    com.android.server.media.MediaUpdateService r0 = com.android.server.media.MediaUpdateService.this
                    com.android.server.media.MediaUpdateService.access$200(r0)
                    goto L64
                L51:
                    android.os.Bundle r0 = r7.getExtras()
                    java.lang.String r1 = "android.intent.extra.REPLACING"
                    boolean r0 = r0.getBoolean(r1)
                    if (r0 == 0) goto L5e
                    return
                L5e:
                    com.android.server.media.MediaUpdateService r0 = com.android.server.media.MediaUpdateService.this
                    com.android.server.media.MediaUpdateService.access$200(r0)
                L64:
                    return
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.server.media.MediaUpdateService.AnonymousClass3.onReceive(android.content.Context, android.content.Intent):void");
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction("android.intent.action.PACKAGE_CHANGED");
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(MEDIA_UPDATE_PACKAGE_NAME, 0);
        getContext().registerReceiverAsUser(updateReceiver, UserHandle.ALL, filter, null, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void packageStateChanged() {
        ApplicationInfo packageInfo = null;
        boolean pluginsAvailable = false;
        try {
            packageInfo = getContext().getPackageManager().getApplicationInfo(MEDIA_UPDATE_PACKAGE_NAME, 1048576);
            pluginsAvailable = packageInfo.enabled;
        } catch (Exception e) {
            Slog.v(TAG, "package '" + MEDIA_UPDATE_PACKAGE_NAME + "' not installed");
        }
        if (packageInfo != null && Build.VERSION.SDK_INT != packageInfo.targetSdkVersion) {
            Slog.w(TAG, "This update package is not for this platform version. Ignoring. platform:" + Build.VERSION.SDK_INT + " targetSdk:" + packageInfo.targetSdkVersion);
            pluginsAvailable = false;
        }
        loadExtractorPlugins((packageInfo == null || !pluginsAvailable) ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : packageInfo.sourceDir);
    }

    private void loadExtractorPlugins(String apkPath) {
        try {
            if (this.mMediaExtractorUpdateService != null) {
                this.mMediaExtractorUpdateService.loadPlugins(apkPath);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error in loadPlugins", e);
        }
    }
}
