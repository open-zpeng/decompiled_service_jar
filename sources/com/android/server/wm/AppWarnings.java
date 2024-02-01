package com.android.server.wm;

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
/* loaded from: classes2.dex */
public class AppWarnings {
    private static final String CONFIG_FILE_NAME = "packages-warnings.xml";
    public static final int FLAG_HIDE_COMPILE_SDK = 2;
    public static final int FLAG_HIDE_DEPRECATED_SDK = 4;
    public static final int FLAG_HIDE_DISPLAY_SIZE = 1;
    private static final String TAG = "AppWarnings";
    private final ActivityTaskManagerService mAtm;
    private final AtomicFile mConfigFile;
    private DeprecatedTargetSdkVersionDialog mDeprecatedTargetSdkVersionDialog;
    private final ConfigHandler mHandler;
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

    public AppWarnings(ActivityTaskManagerService atm, Context uiContext, Handler handler, Handler uiHandler, File systemDir) {
        this.mAtm = atm;
        this.mUiContext = uiContext;
        this.mHandler = new ConfigHandler(handler.getLooper());
        this.mUiHandler = new UiHandler(uiHandler.getLooper());
        this.mConfigFile = new AtomicFile(new File(systemDir, CONFIG_FILE_NAME), "warnings-config");
        readConfigFromFileAmsThread();
    }

    public void showUnsupportedDisplaySizeDialogIfNeeded(ActivityRecord r) {
        Configuration globalConfig = this.mAtm.getGlobalConfiguration();
        if (globalConfig.densityDpi != DisplayMetrics.DENSITY_DEVICE_STABLE && r.appInfo.requiresSmallestWidthDp > globalConfig.smallestScreenWidthDp) {
            this.mUiHandler.showUnsupportedDisplaySizeDialog(r);
        }
    }

    public void showUnsupportedCompileSdkDialogIfNeeded(ActivityRecord r) {
        if (r.appInfo.compileSdkVersion == 0 || r.appInfo.compileSdkVersionCodename == null || !this.mAlwaysShowUnsupportedCompileSdkWarningActivities.contains(r.mActivityComponent)) {
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
            this.mHandler.scheduleWrite();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideUnsupportedDisplaySizeDialogUiThread() {
        UnsupportedDisplaySizeDialog unsupportedDisplaySizeDialog = this.mUnsupportedDisplaySizeDialog;
        if (unsupportedDisplaySizeDialog != null) {
            unsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showUnsupportedDisplaySizeDialogUiThread(ActivityRecord ar) {
        UnsupportedDisplaySizeDialog unsupportedDisplaySizeDialog = this.mUnsupportedDisplaySizeDialog;
        if (unsupportedDisplaySizeDialog != null) {
            unsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 1)) {
            this.mUnsupportedDisplaySizeDialog = new UnsupportedDisplaySizeDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mUnsupportedDisplaySizeDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showUnsupportedCompileSdkDialogUiThread(ActivityRecord ar) {
        UnsupportedCompileSdkDialog unsupportedCompileSdkDialog = this.mUnsupportedCompileSdkDialog;
        if (unsupportedCompileSdkDialog != null) {
            unsupportedCompileSdkDialog.dismiss();
            this.mUnsupportedCompileSdkDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 2)) {
            this.mUnsupportedCompileSdkDialog = new UnsupportedCompileSdkDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mUnsupportedCompileSdkDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showDeprecatedTargetSdkDialogUiThread(ActivityRecord ar) {
        DeprecatedTargetSdkVersionDialog deprecatedTargetSdkVersionDialog = this.mDeprecatedTargetSdkVersionDialog;
        if (deprecatedTargetSdkVersionDialog != null) {
            deprecatedTargetSdkVersionDialog.dismiss();
            this.mDeprecatedTargetSdkVersionDialog = null;
        }
        if (ar != null && !hasPackageFlag(ar.packageName, 4)) {
            this.mDeprecatedTargetSdkVersionDialog = new DeprecatedTargetSdkVersionDialog(this, this.mUiContext, ar.info.applicationInfo);
            this.mDeprecatedTargetSdkVersionDialog.show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideDialogsForPackageUiThread(String name) {
        UnsupportedDisplaySizeDialog unsupportedDisplaySizeDialog = this.mUnsupportedDisplaySizeDialog;
        if (unsupportedDisplaySizeDialog != null && (name == null || name.equals(unsupportedDisplaySizeDialog.getPackageName()))) {
            this.mUnsupportedDisplaySizeDialog.dismiss();
            this.mUnsupportedDisplaySizeDialog = null;
        }
        UnsupportedCompileSdkDialog unsupportedCompileSdkDialog = this.mUnsupportedCompileSdkDialog;
        if (unsupportedCompileSdkDialog != null && (name == null || name.equals(unsupportedCompileSdkDialog.getPackageName()))) {
            this.mUnsupportedCompileSdkDialog.dismiss();
            this.mUnsupportedCompileSdkDialog = null;
        }
        DeprecatedTargetSdkVersionDialog deprecatedTargetSdkVersionDialog = this.mDeprecatedTargetSdkVersionDialog;
        if (deprecatedTargetSdkVersionDialog != null) {
            if (name == null || name.equals(deprecatedTargetSdkVersionDialog.getPackageName())) {
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
                this.mHandler.scheduleWrite();
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
    /* loaded from: classes2.dex */
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
            int i = msg.what;
            if (i == 1) {
                ActivityRecord ar = (ActivityRecord) msg.obj;
                AppWarnings.this.showUnsupportedDisplaySizeDialogUiThread(ar);
            } else if (i == 2) {
                AppWarnings.this.hideUnsupportedDisplaySizeDialogUiThread();
            } else if (i == 3) {
                ActivityRecord ar2 = (ActivityRecord) msg.obj;
                AppWarnings.this.showUnsupportedCompileSdkDialogUiThread(ar2);
            } else if (i == 4) {
                String name = (String) msg.obj;
                AppWarnings.this.hideDialogsForPackageUiThread(name);
            } else if (i == 5) {
                ActivityRecord ar3 = (ActivityRecord) msg.obj;
                AppWarnings.this.showDeprecatedTargetSdkDialogUiThread(ar3);
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
    /* loaded from: classes2.dex */
    public final class ConfigHandler extends Handler {
        private static final int DELAY_MSG_WRITE = 10000;
        private static final int MSG_WRITE = 1;

        public ConfigHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                AppWarnings.this.writeConfigToFileAmsThread();
            }
        }

        public void scheduleWrite() {
            removeMessages(1);
            sendEmptyMessageDelayed(1, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
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

    /* JADX WARN: Removed duplicated region for block: B:36:0x0084 A[Catch: IOException -> 0x0088, TRY_ENTER, TRY_LEAVE, TryCatch #1 {IOException -> 0x0088, blocks: (B:36:0x0084, B:46:0x0094, B:51:0x009f, B:3:0x0005, B:7:0x0023, B:15:0x0033, B:17:0x003f, B:19:0x0046, B:21:0x0051, B:23:0x0059, B:25:0x0062, B:28:0x006b, B:31:0x0072, B:32:0x007b), top: B:61:0x0005, inners: #5, #6 }] */
    /* JADX WARN: Removed duplicated region for block: B:53:0x00a3 A[ORIG_RETURN, RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void readConfigFromFileAmsThread() {
        /*
            r13 = this;
            java.lang.String r0 = "Error reading package metadata"
            java.lang.String r1 = "AppWarnings"
            r2 = 0
            android.util.AtomicFile r3 = r13.mConfigFile     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            java.io.FileInputStream r3 = r3.openRead()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r2 = r3
            org.xmlpull.v1.XmlPullParser r3 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            java.nio.charset.Charset r4 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            java.lang.String r4 = r4.name()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r3.setInput(r2, r4)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            int r4 = r3.getEventType()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
        L1d:
            r5 = 1
            r6 = 2
            if (r4 == r6) goto L29
            if (r4 == r5) goto L29
            int r5 = r3.next()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r4 = r5
            goto L1d
        L29:
            if (r4 != r5) goto L33
            if (r2 == 0) goto L32
            r2.close()     // Catch: java.io.IOException -> L31
            goto L32
        L31:
            r0 = move-exception
        L32:
            return
        L33:
            java.lang.String r7 = r3.getName()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            java.lang.String r8 = "packages"
            boolean r8 = r8.equals(r7)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            if (r8 == 0) goto L82
            int r8 = r3.next()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r4 = r8
        L44:
            if (r4 != r6) goto L7b
            java.lang.String r8 = r3.getName()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r7 = r8
            int r8 = r3.getDepth()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            if (r8 != r6) goto L7b
            java.lang.String r8 = "package"
            boolean r8 = r8.equals(r7)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            if (r8 == 0) goto L7b
            java.lang.String r8 = "name"
            r9 = 0
            java.lang.String r8 = r3.getAttributeValue(r9, r8)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            if (r8 == 0) goto L7b
            java.lang.String r10 = "flags"
            java.lang.String r9 = r3.getAttributeValue(r9, r10)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r10 = 0
            if (r9 == 0) goto L72
            int r11 = java.lang.Integer.parseInt(r9)     // Catch: java.lang.NumberFormatException -> L71 java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r10 = r11
            goto L72
        L71:
            r11 = move-exception
        L72:
            java.util.HashMap<java.lang.String, java.lang.Integer> r11 = r13.mPackageFlags     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            java.lang.Integer r12 = java.lang.Integer.valueOf(r10)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r11.put(r8, r12)     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
        L7b:
            int r8 = r3.next()     // Catch: java.lang.Throwable -> L8a java.io.IOException -> L8c org.xmlpull.v1.XmlPullParserException -> L98
            r4 = r8
            if (r4 != r5) goto L44
        L82:
            if (r2 == 0) goto La3
            r2.close()     // Catch: java.io.IOException -> L88
        L87:
            goto La3
        L88:
            r0 = move-exception
            goto L87
        L8a:
            r0 = move-exception
            goto La4
        L8c:
            r3 = move-exception
            if (r2 == 0) goto L92
            android.util.Slog.w(r1, r0, r3)     // Catch: java.lang.Throwable -> L8a
        L92:
            if (r2 == 0) goto La3
            r2.close()     // Catch: java.io.IOException -> L88
            goto L87
        L98:
            r3 = move-exception
            android.util.Slog.w(r1, r0, r3)     // Catch: java.lang.Throwable -> L8a
            if (r2 == 0) goto La3
            r2.close()     // Catch: java.io.IOException -> L88
            goto L87
        La3:
            return
        La4:
            if (r2 == 0) goto Lab
            r2.close()     // Catch: java.io.IOException -> Laa
            goto Lab
        Laa:
            r1 = move-exception
        Lab:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.AppWarnings.readConfigFromFileAmsThread():void");
    }
}
