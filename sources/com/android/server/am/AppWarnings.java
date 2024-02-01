package com.android.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Slog;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.Settings;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AppWarnings {
    private static final String CONFIG_FILE_NAME = "packages-warnings.xml";
    public static final int FLAG_HIDE_COMPILE_SDK = 2;
    public static final int FLAG_HIDE_DEPRECATED_SDK = 4;
    public static final int FLAG_HIDE_DISPLAY_SIZE = 1;
    private static final String TAG = "AppWarnings";
    private final ActivityManagerService mAms;
    private final ConfigHandler mAmsHandler;
    private final AtomicFile mConfigFile;
    private DeprecatedTargetSdkVersionDialog mDeprecatedTargetSdkVersionDialog;
    private final Context mUiContext;
    private final UiHandler mUiHandler;
    private UnsupportedCompileSdkDialog mUnsupportedCompileSdkDialog;
    private UnsupportedDisplaySizeDialog mUnsupportedDisplaySizeDialog;
    private final HashMap<String, Integer> mPackageFlags = new HashMap<>();
    private HashSet<ComponentName> mAlwaysShowUnsupportedCompileSdkWarningActivities = new HashSet<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        this.mAlwaysShowUnsupportedCompileSdkWarningActivities.add(activity);
    }

    public AppWarnings(ActivityManagerService ams, Context uiContext, Handler amsHandler, Handler uiHandler, File systemDir) {
        this.mAms = ams;
        this.mUiContext = uiContext;
        this.mAmsHandler = new ConfigHandler(amsHandler.getLooper());
        this.mUiHandler = new UiHandler(uiHandler.getLooper());
        this.mConfigFile = new AtomicFile(new File(systemDir, CONFIG_FILE_NAME), "warnings-config");
        readConfigFromFileAmsThread();
    }

    public void showUnsupportedDisplaySizeDialogIfNeeded(ActivityRecord r) {
        Configuration globalConfig = this.mAms.getGlobalConfiguration();
        if (globalConfig.densityDpi != DisplayMetrics.DENSITY_DEVICE_STABLE && r.appInfo.requiresSmallestWidthDp > globalConfig.smallestScreenWidthDp) {
            this.mUiHandler.showUnsupportedDisplaySizeDialog(r);
        }
    }

    public void showUnsupportedCompileSdkDialogIfNeeded(ActivityRecord r) {
        if (r.appInfo.compileSdkVersion == 0 || r.appInfo.compileSdkVersionCodename == null || !this.mAlwaysShowUnsupportedCompileSdkWarningActivities.contains(r.realActivity)) {
            return;
        }
        int compileSdk = r.appInfo.compileSdkVersion;
        int platformSdk = Build.VERSION.SDK_INT;
        boolean isCompileSdkPreview = !"REL".equals(r.appInfo.compileSdkVersionCodename);
        boolean isPlatformSdkPreview = !"REL".equals(Build.VERSION.CODENAME);
        if ((isCompileSdkPreview && compileSdk < platformSdk) || ((isPlatformSdkPreview && platformSdk < compileSdk) || (isCompileSdkPreview && isPlatformSdkPreview && platformSdk == compileSdk && !Build.VERSION.CODENAME.equals(r.appInfo.compileSdkVersionCodename)))) {
            this.mUiHandler.showUnsupportedCompileSdkDialog(r);
        }
    }

    public void showDeprecatedTargetDialogIfNeeded(ActivityRecord r) {
        if (r.appInfo.targetSdkVersion < Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT) {
            this.mUiHandler.showDeprecatedTargetDialog(r);
        }
    }

    public void onStartActivity(ActivityRecord r) {
        showUnsupportedCompileSdkDialogIfNeeded(r);
        showUnsupportedDisplaySizeDialogIfNeeded(r);
        showDeprecatedTargetDialogIfNeeded(r);
    }

    public void onResumeActivity(ActivityRecord r) {
        showUnsupportedDisplaySizeDialogIfNeeded(r);
    }

    public void onPackageDataCleared(String name) {
        removePackageAndHideDialogs(name);
    }

    public void onPackageUninstalled(String name) {
        removePackageAndHideDialogs(name);
    }

    public void onDensityChanged() {
        this.mUiHandler.hideUnsupportedDisplaySizeDialog();
    }

    private void removePackageAndHideDialogs(String name) {
        this.mUiHandler.hideDialogsForPackage(name);
        synchronized (this.mPackageFlags) {
            this.mPackageFlags.remove(name);
            this.mAmsHandler.scheduleWrite();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideUnsupportedDisplaySizeDialogUiThread() {
        if (this.mUnsupportedDisplaySizeDialog != null) {
            this.mUnsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showUnsupportedDisplaySizeDialogUiThread(ActivityRecord ar) {
        if (this.mUnsupportedDisplaySizeDialog != null) {
            this.mUnsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 1)) {
            this.mUnsupportedDisplaySizeDialog = new UnsupportedDisplaySizeDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mUnsupportedDisplaySizeDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showUnsupportedCompileSdkDialogUiThread(ActivityRecord ar) {
        if (this.mUnsupportedCompileSdkDialog != null) {
            this.mUnsupportedCompileSdkDialog.dismiss();
            this.mUnsupportedCompileSdkDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 2)) {
            this.mUnsupportedCompileSdkDialog = new UnsupportedCompileSdkDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mUnsupportedCompileSdkDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showDeprecatedTargetSdkDialogUiThread(ActivityRecord ar) {
        if (this.mDeprecatedTargetSdkVersionDialog != null) {
            this.mDeprecatedTargetSdkVersionDialog.dismiss();
            this.mDeprecatedTargetSdkVersionDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 4)) {
            this.mDeprecatedTargetSdkVersionDialog = new DeprecatedTargetSdkVersionDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mDeprecatedTargetSdkVersionDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideDialogsForPackageUiThread(String name) {
        if (this.mUnsupportedDisplaySizeDialog != null && (name == null || name.equals(this.mUnsupportedDisplaySizeDialog.getPackageName()))) {
            this.mUnsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
        if (this.mUnsupportedCompileSdkDialog != null && (name == null || name.equals(this.mUnsupportedCompileSdkDialog.getPackageName()))) {
            this.mUnsupportedCompileSdkDialog.dismiss();
            this.mUnsupportedCompileSdkDialog = null;
        }
        if (this.mDeprecatedTargetSdkVersionDialog != null) {
            if (name == null || name.equals(this.mDeprecatedTargetSdkVersionDialog.getPackageName())) {
                this.mDeprecatedTargetSdkVersionDialog.dismiss();
                this.mDeprecatedTargetSdkVersionDialog = null;
            }
        }
    }

    boolean hasPackageFlag(String name, int flag) {
        return (getPackageFlags(name) & flag) == flag;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPackageFlag(String name, int flag, boolean enabled) {
        synchronized (this.mPackageFlags) {
            int curFlags = getPackageFlags(name);
            int newFlags = enabled ? curFlags | flag : (~flag) & curFlags;
            if (curFlags != newFlags) {
                if (newFlags != 0) {
                    this.mPackageFlags.put(name, Integer.valueOf(newFlags));
                } else {
                    this.mPackageFlags.remove(name);
                }
                this.mAmsHandler.scheduleWrite();
            }
        }
    }

    private int getPackageFlags(String name) {
        int intValue;
        synchronized (this.mPackageFlags) {
            intValue = this.mPackageFlags.getOrDefault(name, 0).intValue();
        }
        return intValue;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class UiHandler extends Handler {
        private static final int MSG_HIDE_DIALOGS_FOR_PACKAGE = 4;
        private static final int MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 2;
        private static final int MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG = 5;
        private static final int MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG = 3;
        private static final int MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 1;

        public UiHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    ActivityRecord ar = (ActivityRecord) msg.obj;
                    AppWarnings.this.showUnsupportedDisplaySizeDialogUiThread(ar);
                    return;
                case 2:
                    AppWarnings.this.hideUnsupportedDisplaySizeDialogUiThread();
                    return;
                case 3:
                    ActivityRecord ar2 = (ActivityRecord) msg.obj;
                    AppWarnings.this.showUnsupportedCompileSdkDialogUiThread(ar2);
                    return;
                case 4:
                    String name = (String) msg.obj;
                    AppWarnings.this.hideDialogsForPackageUiThread(name);
                    return;
                case 5:
                    ActivityRecord ar3 = (ActivityRecord) msg.obj;
                    AppWarnings.this.showDeprecatedTargetSdkDialogUiThread(ar3);
                    return;
                default:
                    return;
            }
        }

        public void showUnsupportedDisplaySizeDialog(ActivityRecord r) {
            removeMessages(1);
            obtainMessage(1, r).sendToTarget();
        }

        public void hideUnsupportedDisplaySizeDialog() {
            removeMessages(2);
            sendEmptyMessage(2);
        }

        public void showUnsupportedCompileSdkDialog(ActivityRecord r) {
            removeMessages(3);
            obtainMessage(3, r).sendToTarget();
        }

        public void showDeprecatedTargetDialog(ActivityRecord r) {
            removeMessages(5);
            obtainMessage(5, r).sendToTarget();
        }

        public void hideDialogsForPackage(String name) {
            obtainMessage(4, name).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ConfigHandler extends Handler {
        private static final int DELAY_MSG_WRITE = 10000;
        private static final int MSG_WRITE = 300;

        public ConfigHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 300) {
                AppWarnings.this.writeConfigToFileAmsThread();
            }
        }

        public void scheduleWrite() {
            removeMessages(300);
            sendEmptyMessageDelayed(300, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeConfigToFileAmsThread() {
        HashMap<String, Integer> packageFlags;
        synchronized (this.mPackageFlags) {
            packageFlags = new HashMap<>(this.mPackageFlags);
        }
        FileOutputStream fos = null;
        try {
            fos = this.mConfigFile.startWrite();
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(fos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            fastXmlSerializer.startTag(null, "packages");
            for (Map.Entry<String, Integer> entry : packageFlags.entrySet()) {
                String pkg = entry.getKey();
                int mode = entry.getValue().intValue();
                if (mode != 0) {
                    fastXmlSerializer.startTag(null, "package");
                    fastXmlSerializer.attribute(null, Settings.ATTR_NAME, pkg);
                    fastXmlSerializer.attribute(null, xpInputManagerService.InputPolicyKey.KEY_FLAGS, Integer.toString(mode));
                    fastXmlSerializer.endTag(null, "package");
                }
            }
            fastXmlSerializer.endTag(null, "packages");
            fastXmlSerializer.endDocument();
            this.mConfigFile.finishWrite(fos);
        } catch (IOException e1) {
            Slog.w(TAG, "Error writing package metadata", e1);
            if (fos != null) {
                this.mConfigFile.failWrite(fos);
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:36:0x0083 A[Catch: IOException -> 0x0087, TRY_ENTER, TRY_LEAVE, TryCatch #0 {IOException -> 0x0087, blocks: (B:36:0x0083, B:46:0x0097, B:51:0x00a5, B:3:0x0002, B:7:0x0020, B:15:0x0030, B:17:0x003d, B:19:0x0044, B:21:0x004f, B:23:0x0058, B:25:0x0061, B:28:0x006a, B:31:0x0071, B:32:0x007a), top: B:59:0x0002, inners: #1, #6 }] */
    /* JADX WARN: Removed duplicated region for block: B:53:0x00a9 A[ORIG_RETURN, RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void readConfigFromFileAmsThread() {
        /*
            r12 = this;
            r0 = 0
            r1 = r0
            android.util.AtomicFile r2 = r12.mConfigFile     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            java.io.FileInputStream r2 = r2.openRead()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r1 = r2
            org.xmlpull.v1.XmlPullParser r2 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            java.nio.charset.Charset r3 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            java.lang.String r3 = r3.name()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r2.setInput(r1, r3)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            int r3 = r2.getEventType()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
        L1a:
            r4 = 1
            r5 = 2
            if (r3 == r5) goto L26
            if (r3 == r4) goto L26
            int r4 = r2.next()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r3 = r4
            goto L1a
        L26:
            if (r3 != r4) goto L30
            if (r1 == 0) goto L2f
            r1.close()     // Catch: java.io.IOException -> L2e
            goto L2f
        L2e:
            r0 = move-exception
        L2f:
            return
        L30:
            java.lang.String r6 = r2.getName()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            java.lang.String r7 = "packages"
            boolean r7 = r7.equals(r6)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            if (r7 == 0) goto L81
            int r7 = r2.next()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r3 = r7
        L42:
            if (r3 != r5) goto L7a
            java.lang.String r7 = r2.getName()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r6 = r7
            int r7 = r2.getDepth()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            if (r7 != r5) goto L7a
            java.lang.String r7 = "package"
            boolean r7 = r7.equals(r6)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            if (r7 == 0) goto L7a
            java.lang.String r7 = "name"
            java.lang.String r7 = r2.getAttributeValue(r0, r7)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            if (r7 == 0) goto L7a
            java.lang.String r8 = "flags"
            java.lang.String r8 = r2.getAttributeValue(r0, r8)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r9 = 0
            if (r8 == 0) goto L71
            int r10 = java.lang.Integer.parseInt(r8)     // Catch: java.lang.NumberFormatException -> L70 java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r9 = r10
            goto L71
        L70:
            r10 = move-exception
        L71:
            java.util.HashMap<java.lang.String, java.lang.Integer> r10 = r12.mPackageFlags     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            java.lang.Integer r11 = java.lang.Integer.valueOf(r9)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r10.put(r7, r11)     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
        L7a:
            int r7 = r2.next()     // Catch: java.lang.Throwable -> L89 java.io.IOException -> L8b org.xmlpull.v1.XmlPullParserException -> L9b
            r3 = r7
            if (r3 != r4) goto L42
        L81:
            if (r1 == 0) goto La9
            r1.close()     // Catch: java.io.IOException -> L87
        L86:
            goto La9
        L87:
            r0 = move-exception
            goto L86
        L89:
            r0 = move-exception
            goto Laa
        L8b:
            r0 = move-exception
            if (r1 == 0) goto L95
            java.lang.String r2 = "AppWarnings"
            java.lang.String r3 = "Error reading package metadata"
            android.util.Slog.w(r2, r3, r0)     // Catch: java.lang.Throwable -> L89
        L95:
            if (r1 == 0) goto La9
            r1.close()     // Catch: java.io.IOException -> L87
            goto L86
        L9b:
            r0 = move-exception
            java.lang.String r2 = "AppWarnings"
            java.lang.String r3 = "Error reading package metadata"
            android.util.Slog.w(r2, r3, r0)     // Catch: java.lang.Throwable -> L89
            if (r1 == 0) goto La9
            r1.close()     // Catch: java.io.IOException -> L87
            goto L86
        La9:
            return
        Laa:
            if (r1 == 0) goto Lb1
            r1.close()     // Catch: java.io.IOException -> Lb0
            goto Lb1
        Lb0:
            r2 = move-exception
        Lb1:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppWarnings.readConfigFromFileAmsThread():void");
    }
}
