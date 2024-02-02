package com.android.server.am;

import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.Settings;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
/* loaded from: classes.dex */
public final class CompatModePackages {
    public static final int COMPAT_FLAG_DONT_ASK = 1;
    public static final int COMPAT_FLAG_ENABLED = 2;
    private static final int MSG_WRITE = 300;
    private static final String TAG = "ActivityManager";
    private static final String TAG_CONFIGURATION = "ActivityManager";
    private final AtomicFile mFile;
    private final CompatHandler mHandler;
    private final HashMap<String, Integer> mPackages = new HashMap<>();
    private final ActivityManagerService mService;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class CompatHandler extends Handler {
        public CompatHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 300) {
                CompatModePackages.this.saveCompatModes();
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:36:0x00ab A[Catch: IOException -> 0x00af, TRY_ENTER, TRY_LEAVE, TryCatch #5 {IOException -> 0x00af, blocks: (B:36:0x00ab, B:46:0x00bf, B:51:0x00cd), top: B:68:0x002a }] */
    /* JADX WARN: Removed duplicated region for block: B:75:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public CompatModePackages(com.android.server.am.ActivityManagerService r13, java.io.File r14, android.os.Handler r15) {
        /*
            r12 = this;
            r12.<init>()
            java.util.HashMap r0 = new java.util.HashMap
            r0.<init>()
            r12.mPackages = r0
            r12.mService = r13
            android.util.AtomicFile r0 = new android.util.AtomicFile
            java.io.File r1 = new java.io.File
            java.lang.String r2 = "packages-compat.xml"
            r1.<init>(r14, r2)
            java.lang.String r2 = "compat-mode"
            r0.<init>(r1, r2)
            r12.mFile = r0
            com.android.server.am.CompatModePackages$CompatHandler r0 = new com.android.server.am.CompatModePackages$CompatHandler
            android.os.Looper r1 = r15.getLooper()
            r0.<init>(r1)
            r12.mHandler = r0
            r0 = 0
            r1 = r0
            android.util.AtomicFile r2 = r12.mFile     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            java.io.FileInputStream r2 = r2.openRead()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r1 = r2
            org.xmlpull.v1.XmlPullParser r2 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            java.nio.charset.Charset r3 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            java.lang.String r3 = r3.name()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r2.setInput(r1, r3)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            int r3 = r2.getEventType()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
        L42:
            r4 = 1
            r5 = 2
            if (r3 == r5) goto L4e
            if (r3 == r4) goto L4e
            int r4 = r2.next()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r3 = r4
            goto L42
        L4e:
            if (r3 != r4) goto L58
            if (r1 == 0) goto L57
            r1.close()     // Catch: java.io.IOException -> L56
            goto L57
        L56:
            r0 = move-exception
        L57:
            return
        L58:
            java.lang.String r6 = r2.getName()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            java.lang.String r7 = "compat-packages"
            boolean r7 = r7.equals(r6)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            if (r7 == 0) goto La9
            int r7 = r2.next()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r3 = r7
        L69:
            if (r3 != r5) goto La2
            java.lang.String r7 = r2.getName()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r6 = r7
            int r7 = r2.getDepth()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            if (r7 != r5) goto La2
            java.lang.String r7 = "pkg"
            boolean r7 = r7.equals(r6)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            if (r7 == 0) goto La2
            java.lang.String r7 = "name"
            java.lang.String r7 = r2.getAttributeValue(r0, r7)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            if (r7 == 0) goto La2
            java.lang.String r8 = "mode"
            java.lang.String r8 = r2.getAttributeValue(r0, r8)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r9 = 0
            if (r8 == 0) goto L99
            int r10 = java.lang.Integer.parseInt(r8)     // Catch: java.lang.NumberFormatException -> L98 java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r9 = r10
            goto L99
        L98:
            r10 = move-exception
        L99:
            java.util.HashMap<java.lang.String, java.lang.Integer> r10 = r12.mPackages     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            java.lang.Integer r11 = java.lang.Integer.valueOf(r9)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r10.put(r7, r11)     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
        La2:
            int r7 = r2.next()     // Catch: java.lang.Throwable -> Lb1 java.io.IOException -> Lb3 org.xmlpull.v1.XmlPullParserException -> Lc3
            r3 = r7
            if (r3 != r4) goto L69
        La9:
            if (r1 == 0) goto Ld1
            r1.close()     // Catch: java.io.IOException -> Laf
        Lae:
            goto Ld1
        Laf:
            r0 = move-exception
            goto Lae
        Lb1:
            r0 = move-exception
            goto Ld2
        Lb3:
            r0 = move-exception
            if (r1 == 0) goto Lbd
            java.lang.String r2 = "ActivityManager"
            java.lang.String r3 = "Error reading compat-packages"
            android.util.Slog.w(r2, r3, r0)     // Catch: java.lang.Throwable -> Lb1
        Lbd:
            if (r1 == 0) goto Ld1
            r1.close()     // Catch: java.io.IOException -> Laf
            goto Lae
        Lc3:
            r0 = move-exception
            java.lang.String r2 = "ActivityManager"
            java.lang.String r3 = "Error reading compat-packages"
            android.util.Slog.w(r2, r3, r0)     // Catch: java.lang.Throwable -> Lb1
            if (r1 == 0) goto Ld1
            r1.close()     // Catch: java.io.IOException -> Laf
            goto Lae
        Ld1:
            return
        Ld2:
            if (r1 == 0) goto Ld9
            r1.close()     // Catch: java.io.IOException -> Ld8
            goto Ld9
        Ld8:
            r2 = move-exception
        Ld9:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.CompatModePackages.<init>(com.android.server.am.ActivityManagerService, java.io.File, android.os.Handler):void");
    }

    public HashMap<String, Integer> getPackages() {
        return this.mPackages;
    }

    private int getPackageFlags(String packageName) {
        Integer flags = this.mPackages.get(packageName);
        if (flags != null) {
            return flags.intValue();
        }
        return 0;
    }

    public void handlePackageDataClearedLocked(String packageName) {
        removePackage(packageName);
    }

    public void handlePackageUninstalledLocked(String packageName) {
        removePackage(packageName);
    }

    private void removePackage(String packageName) {
        if (this.mPackages.containsKey(packageName)) {
            this.mPackages.remove(packageName);
            scheduleWrite();
        }
    }

    public void handlePackageAddedLocked(String packageName, boolean updated) {
        ApplicationInfo ai = null;
        boolean mayCompat = false;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            return;
        }
        CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
        if (!ci.alwaysSupportsScreen() && !ci.neverSupportsScreen()) {
            mayCompat = true;
        }
        if (updated && !mayCompat && this.mPackages.containsKey(packageName)) {
            this.mPackages.remove(packageName);
            scheduleWrite();
        }
    }

    private void scheduleWrite() {
        this.mHandler.removeMessages(300);
        Message msg = this.mHandler.obtainMessage(300);
        this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        Configuration globalConfig = this.mService.getGlobalConfiguration();
        CompatibilityInfo ci = new CompatibilityInfo(ai, globalConfig.screenLayout, globalConfig.smallestScreenWidthDp, (getPackageFlags(ai.packageName) & 2) != 0);
        return ci;
    }

    public int computeCompatModeLocked(ApplicationInfo ai) {
        boolean enabled = (getPackageFlags(ai.packageName) & 2) != 0;
        Configuration globalConfig = this.mService.getGlobalConfiguration();
        CompatibilityInfo info = new CompatibilityInfo(ai, globalConfig.screenLayout, globalConfig.smallestScreenWidthDp, enabled);
        if (info.alwaysSupportsScreen()) {
            return -2;
        }
        if (info.neverSupportsScreen()) {
            return -1;
        }
        return enabled ? 1 : 0;
    }

    public boolean getFrontActivityAskCompatModeLocked() {
        ActivityRecord r = this.mService.getFocusedStack().topRunningActivityLocked();
        if (r == null) {
            return false;
        }
        return getPackageAskCompatModeLocked(r.packageName);
    }

    public boolean getPackageAskCompatModeLocked(String packageName) {
        return (getPackageFlags(packageName) & 1) == 0;
    }

    public void setFrontActivityAskCompatModeLocked(boolean ask) {
        ActivityRecord r = this.mService.getFocusedStack().topRunningActivityLocked();
        if (r != null) {
            setPackageAskCompatModeLocked(r.packageName, ask);
        }
    }

    public void setPackageAskCompatModeLocked(String packageName, boolean ask) {
        setPackageFlagLocked(packageName, 1, ask);
    }

    private void setPackageFlagLocked(String packageName, int flag, boolean set) {
        int curFlags = getPackageFlags(packageName);
        int newFlags = set ? (~flag) & curFlags : curFlags | flag;
        if (curFlags != newFlags) {
            if (newFlags != 0) {
                this.mPackages.put(packageName, Integer.valueOf(newFlags));
            } else {
                this.mPackages.remove(packageName);
            }
            scheduleWrite();
        }
    }

    public int getFrontActivityScreenCompatModeLocked() {
        ActivityRecord r = this.mService.getFocusedStack().topRunningActivityLocked();
        if (r == null) {
            return -3;
        }
        return computeCompatModeLocked(r.info.applicationInfo);
    }

    public void setFrontActivityScreenCompatModeLocked(int mode) {
        ActivityRecord r = this.mService.getFocusedStack().topRunningActivityLocked();
        if (r == null) {
            Slog.w("ActivityManager", "setFrontActivityScreenCompatMode failed: no top activity");
        } else {
            setPackageScreenCompatModeLocked(r.info.applicationInfo, mode);
        }
    }

    public int getPackageScreenCompatModeLocked(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            return -3;
        }
        return computeCompatModeLocked(ai);
    }

    public void setPackageScreenCompatModeLocked(String packageName, int mode) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            Slog.w("ActivityManager", "setPackageScreenCompatMode failed: unknown package " + packageName);
            return;
        }
        setPackageScreenCompatModeLocked(ai, mode);
    }

    private void setPackageScreenCompatModeLocked(ApplicationInfo ai, int mode) {
        boolean enable;
        int newFlags;
        String packageName = ai.packageName;
        int curFlags = getPackageFlags(packageName);
        switch (mode) {
            case 0:
                enable = false;
                break;
            case 1:
                enable = true;
                break;
            case 2:
                if ((curFlags & 2) != 0) {
                    enable = false;
                    break;
                } else {
                    enable = true;
                    break;
                }
            default:
                Slog.w("ActivityManager", "Unknown screen compat mode req #" + mode + "; ignoring");
                return;
        }
        if (enable) {
            newFlags = curFlags | 2;
        } else {
            newFlags = curFlags & (-3);
        }
        CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
        if (ci.alwaysSupportsScreen()) {
            Slog.w("ActivityManager", "Ignoring compat mode change of " + packageName + "; compatibility never needed");
            newFlags = 0;
        }
        if (ci.neverSupportsScreen()) {
            Slog.w("ActivityManager", "Ignoring compat mode change of " + packageName + "; compatibility always needed");
            newFlags = 0;
        }
        if (newFlags != curFlags) {
            if (newFlags != 0) {
                this.mPackages.put(packageName, Integer.valueOf(newFlags));
            } else {
                this.mPackages.remove(packageName);
            }
            CompatibilityInfo ci2 = compatibilityInfoForPackageLocked(ai);
            scheduleWrite();
            ActivityStack stack = this.mService.getFocusedStack();
            ActivityRecord starting = stack.restartPackage(packageName);
            int i = this.mService.mLruProcesses.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 >= 0) {
                    ProcessRecord app = this.mService.mLruProcesses.get(i2);
                    if (app.pkgList.containsKey(packageName)) {
                        try {
                            if (app.thread != null) {
                                if (ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                                    Slog.v("ActivityManager", "Sending to proc " + app.processName + " new compat " + ci2);
                                }
                                app.thread.updatePackageCompatibilityInfo(packageName, ci2);
                            }
                        } catch (Exception e) {
                        }
                    }
                    i = i2 - 1;
                } else if (starting != null) {
                    starting.ensureActivityConfiguration(0, false);
                    stack.ensureActivitiesVisibleLocked(starting, 0, false);
                    return;
                } else {
                    return;
                }
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    void saveCompatModes() {
        HashMap<String, Integer> pkgs;
        FastXmlSerializer fastXmlSerializer;
        IPackageManager pm;
        int screenLayout;
        int smallestScreenWidthDp;
        Iterator<Map.Entry<String, Integer>> it;
        HashMap<String, Integer> pkgs2;
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                pkgs = new HashMap<>(this.mPackages);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        String str = null;
        FileOutputStream fos = null;
        try {
            fos = this.mFile.startWrite();
            fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(fos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            fastXmlSerializer.startTag(null, "compat-packages");
            pm = AppGlobals.getPackageManager();
            Configuration globalConfig = this.mService.getGlobalConfiguration();
            screenLayout = globalConfig.screenLayout;
            smallestScreenWidthDp = globalConfig.smallestScreenWidthDp;
            it = pkgs.entrySet().iterator();
        } catch (IOException e) {
            e1 = e;
        }
        while (true) {
            Iterator<Map.Entry<String, Integer>> it2 = it;
            if (!it2.hasNext()) {
                fastXmlSerializer.endTag(null, "compat-packages");
                fastXmlSerializer.endDocument();
                this.mFile.finishWrite(fos);
                return;
            }
            Map.Entry<String, Integer> entry = it2.next();
            String pkg = entry.getKey();
            int mode = entry.getValue().intValue();
            if (mode != 0) {
                ApplicationInfo ai = str;
                try {
                    ai = pm.getApplicationInfo(pkg, 0, 0);
                } catch (RemoteException e2) {
                } catch (IOException e3) {
                    e1 = e3;
                }
                if (ai != 0) {
                    CompatibilityInfo info = new CompatibilityInfo(ai, screenLayout, smallestScreenWidthDp, false);
                    if (!info.alwaysSupportsScreen() && !info.neverSupportsScreen()) {
                        fastXmlSerializer.startTag(str, "pkg");
                        fastXmlSerializer.attribute(str, Settings.ATTR_NAME, pkg);
                        pkgs2 = pkgs;
                        try {
                            fastXmlSerializer.attribute(null, xpInputManagerService.InputPolicyKey.KEY_MODE, Integer.toString(mode));
                            fastXmlSerializer.endTag(null, "pkg");
                            it = it2;
                            pkgs = pkgs2;
                            str = null;
                        } catch (IOException e4) {
                            e1 = e4;
                        }
                    }
                }
            }
            pkgs2 = pkgs;
            it = it2;
            pkgs = pkgs2;
            str = null;
            e1 = e4;
            Slog.w("ActivityManager", "Error writing compat packages", e1);
            if (fos != null) {
                this.mFile.failWrite(fos);
                return;
            }
            return;
        }
    }
}
