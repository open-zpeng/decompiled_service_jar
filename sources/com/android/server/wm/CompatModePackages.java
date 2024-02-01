package com.android.server.wm;

import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import com.android.server.job.controllers.JobStatus;
import java.util.HashMap;

/* loaded from: classes2.dex */
public final class CompatModePackages {
    private static final int COMPAT_FLAG_DONT_ASK = 1;
    private static final int COMPAT_FLAG_ENABLED = 2;
    private static final int MSG_WRITE = 300;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_CONFIGURATION = "ActivityTaskManager";
    private final AtomicFile mFile;
    private final CompatHandler mHandler;
    private final HashMap<String, Integer> mPackages = new HashMap<>();
    private final ActivityTaskManagerService mService;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
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

    /* JADX WARN: Removed duplicated region for block: B:37:0x00b5 A[Catch: IOException -> 0x00b9, TRY_ENTER, TRY_LEAVE, TryCatch #0 {IOException -> 0x00b9, blocks: (B:37:0x00b5, B:47:0x00c6, B:52:0x00d1, B:3:0x0032, B:7:0x0052, B:15:0x0062, B:17:0x006e, B:19:0x0075, B:21:0x007f, B:23:0x0087, B:25:0x0091, B:28:0x009b, B:31:0x00a2, B:33:0x00ac), top: B:60:0x0032, inners: #1, #5 }] */
    /* JADX WARN: Removed duplicated region for block: B:54:0x00d5 A[ORIG_RETURN, RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public CompatModePackages(com.android.server.wm.ActivityTaskManagerService r17, java.io.File r18, android.os.Handler r19) {
        /*
            Method dump skipped, instructions count: 222
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.CompatModePackages.<init>(com.android.server.wm.ActivityTaskManagerService, java.io.File, android.os.Handler):void");
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

    public boolean getPackageAskCompatModeLocked(String packageName) {
        return (getPackageFlags(packageName) & 1) == 0;
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
            Slog.w("ActivityTaskManager", "setPackageScreenCompatMode failed: unknown package " + packageName);
            return;
        }
        setPackageScreenCompatModeLocked(ai, mode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPackageScreenCompatModeLocked(ApplicationInfo ai, int mode) {
        boolean enable;
        int newFlags;
        int newFlags2;
        String packageName = ai.packageName;
        int curFlags = getPackageFlags(packageName);
        if (mode == 0) {
            enable = false;
        } else if (mode != 1) {
            if (mode == 2) {
                enable = (curFlags & 2) == 0;
            } else {
                Slog.w("ActivityTaskManager", "Unknown screen compat mode req #" + mode + "; ignoring");
                return;
            }
        } else {
            enable = true;
        }
        if (enable) {
            newFlags = 2 | curFlags;
        } else {
            newFlags = curFlags & (-3);
        }
        CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
        if (ci.alwaysSupportsScreen()) {
            Slog.w("ActivityTaskManager", "Ignoring compat mode change of " + packageName + "; compatibility never needed");
            newFlags = 0;
        }
        if (!ci.neverSupportsScreen()) {
            newFlags2 = newFlags;
        } else {
            Slog.w("ActivityTaskManager", "Ignoring compat mode change of " + packageName + "; compatibility always needed");
            newFlags2 = 0;
        }
        if (newFlags2 != curFlags) {
            if (newFlags2 != 0) {
                this.mPackages.put(packageName, Integer.valueOf(newFlags2));
            } else {
                this.mPackages.remove(packageName);
            }
            CompatibilityInfo ci2 = compatibilityInfoForPackageLocked(ai);
            scheduleWrite();
            ActivityStack stack = this.mService.getTopDisplayFocusedStack();
            ActivityRecord starting = stack.restartPackage(packageName);
            SparseArray<WindowProcessController> pidMap = this.mService.mProcessMap.getPidMap();
            for (int i = pidMap.size() - 1; i >= 0; i--) {
                WindowProcessController app = pidMap.valueAt(i);
                if (app.mPkgList.contains(packageName)) {
                    try {
                        if (app.hasThread()) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                                Slog.v("ActivityTaskManager", "Sending to proc " + app.mName + " new compat " + ci2);
                            }
                            app.getThread().updatePackageCompatibilityInfo(packageName, ci2);
                        }
                    } catch (Exception e) {
                    }
                }
            }
            if (starting != null) {
                starting.ensureActivityConfiguration(0, false);
                stack.ensureActivitiesVisibleLocked(starting, 0, false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:39:0x00de  */
    /* JADX WARN: Removed duplicated region for block: B:66:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void saveCompatModes() {
        /*
            Method dump skipped, instructions count: 234
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.CompatModePackages.saveCompatModes():void");
    }
}
