package com.xiaopeng.server.policy;

import android.app.AlertDialog;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/* loaded from: classes2.dex */
public class xpBootManagerPolicy {
    private static final long MINUTE = 60000;
    private static final int MSG_HANDLE_ACTIVITY_CHANGED = 1002;
    private static final int MSG_HANDLE_BOOT_CHANGED = 1001;
    private static final String TAG = "xpBootManagerPolicy";
    private static final double sLimitedCpu = 1.0d;
    private Context mContext;
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.xp.boot.policy.debug", false);
    private static final double CPU = xpTextUtils.toDouble(SystemProperties.get("persist.xp.boot.policy.cpu"), Double.valueOf(1.0d)).doubleValue();
    private static final long TIMEOUT = FeatureOption.FO_BOOT_POLICY_TIMEOUT * 60000;
    private static final String[] PRELOAD_COMPONENTS = {"com.xiaopeng.carcontrol/com.xiaopeng.carcontrol.view.MainActivity", "com.xiaopeng.musicradio/com.xiaopeng.musicradio.main.view.HomeActivity"};
    public static final boolean BOOT_POLICY_ENABLED = SystemProperties.getBoolean("persist.xp.boot.policy.enabled", FeatureOption.FO_BOOT_POLICY_ENABLED);
    public static final String[] sPreloadComponents = getPreloadComponents();
    private static volatile long sPreloadStartMillis = 0;
    private static volatile List<String> sPreloadedList = new ArrayList();
    private static volatile boolean sBootReady = false;
    private static volatile boolean sDismissCompleted = false;
    private static volatile boolean sSystemReady = false;
    private static volatile boolean sActivityReady = false;
    private static volatile boolean sSystemBootCompleted = false;
    private static volatile boolean sBootMessagesCompleted = false;
    private static volatile boolean sHomeCompleted = false;
    private static volatile boolean sPreloadCompleted = false;
    private static volatile boolean sPreloadStarted = false;
    private static volatile boolean sTimeout = false;
    private static volatile boolean sShowingBootMessages = false;
    private static volatile boolean sBootAnimationStopped = false;
    private static volatile ComponentName sFallbackHome = null;
    private static xpBootManagerPolicy sManager = null;
    private AlertDialog mBootMessages = null;
    private Handler mHandler = new Handler(Looper.getMainLooper()) { // from class: com.xiaopeng.server.policy.xpBootManagerPolicy.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int i = msg.what;
            if (i != 1001 && i == xpBootManagerPolicy.MSG_HANDLE_ACTIVITY_CHANGED) {
                xpBootManagerPolicy.this.handleActivityChanged((ComponentName) msg.obj);
            }
        }
    };

    private xpBootManagerPolicy(Context context) {
        log(TAG, "init");
        this.mContext = context;
        init();
    }

    public static xpBootManagerPolicy get(Context context) {
        if (sManager == null) {
            synchronized (xpBootManagerPolicy.class) {
                if (sManager == null) {
                    sManager = new xpBootManagerPolicy(context);
                }
            }
        }
        return sManager;
    }

    private void init() {
        scheduleBootTasks(this.mContext);
    }

    public void onBootReady() {
    }

    public boolean isBootReady() {
        return sBootReady;
    }

    public boolean allowFinishBootMessages() {
        return (BOOT_POLICY_ENABLED && sShowingBootMessages) ? false : true;
    }

    public boolean allowFinishBootAnimation() {
        return !BOOT_POLICY_ENABLED || sShowingBootMessages;
    }

    public boolean checkBootAnimationCompleted() {
        if (BOOT_POLICY_ENABLED && sSystemBootCompleted) {
            return true;
        }
        return false;
    }

    public void setBootMessagesDialog(AlertDialog dialog, boolean completed) {
        this.mBootMessages = dialog;
        sBootMessagesCompleted = completed;
    }

    public void onBootChanged(boolean systemBooted, boolean showingBootMessages) {
        log(TAG, "onBootChanged systemBooted=" + systemBooted + " showingBootMessages=" + showingBootMessages);
        synchronized (this) {
            sSystemBootCompleted = systemBooted;
            sShowingBootMessages = showingBootMessages;
        }
    }

    public void onActivityChanged(ComponentName component) {
        Handler handler = this.mHandler;
        handler.sendMessage(handler.obtainMessage(MSG_HANDLE_ACTIVITY_CHANGED, component));
    }

    public boolean abortActivityIfNeed(Context context, ComponentName component) {
        boolean abort;
        if (BOOT_POLICY_ENABLED && !sBootReady && !sActivityReady && xpActivityManager.isComponentValid(component)) {
            boolean isHome = xpActivityManager.isHome(context, component);
            boolean isPreload = isPreloadActivity(component);
            boolean isFallbackHome = sFallbackHome == null;
            boolean preloadCompleted = preloadCompleted();
            if (isFallbackHome || preloadCompleted) {
                abort = false;
            } else {
                abort = !isPreload;
            }
            log(TAG, "abortActivityIfNeed abort=" + abort + " component=" + component + " home=" + isHome + " preload=" + isPreload + " isFallbackHome=" + isFallbackHome);
            return abort;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleBootTasks(final Context context) {
        if (BOOT_POLICY_ENABLED && !sBootReady) {
            long now = SystemClock.elapsedRealtime();
            double usage = getCpuUsage() / 100.0d;
            boolean z = false;
            boolean usageNormal = usage > CPU && usage < CPU;
            boolean userUnlocked = isUserUnlocked();
            boolean bootReady = false;
            sSystemReady = sSystemBootCompleted | sBootMessagesCompleted;
            if (sPreloadStarted && now - sPreloadStartMillis > TIMEOUT) {
                z = true;
            }
            sTimeout = z;
            if (sTimeout || (sSystemReady && sActivityReady && usageNormal)) {
                bootReady = true;
            }
            if (bootReady) {
                performEnableScreen();
                hideBootMessagesDialog();
                startNextActivityLocked(context, 0L);
                onBootCompleted();
            }
            if (sDismissCompleted && (sTimeout || (sSystemReady && sActivityReady && usageNormal))) {
                sBootReady = true;
            }
            if (sSystemReady && userUnlocked && !sPreloadStarted) {
                startNextActivityLocked(context, 0L);
            }
            log(TAG, "scheduleBootTasks bootReady=" + sBootReady + " dismissCompleted=" + sDismissCompleted + " systemReady=" + sSystemReady + " activityReady=" + sActivityReady + " systemBootCompleted=" + sSystemBootCompleted + " bootMessagesCompleted=" + sBootMessagesCompleted + " homeCompleted=" + sHomeCompleted + " preloadCompleted=" + sPreloadCompleted + " preloadStart=" + sPreloadStartMillis + " now=" + now + " usage=" + usage + " timeout=" + sTimeout + " userUnlocked=" + userUnlocked + " usageNormal=" + usageNormal);
            this.mHandler.postDelayed(new Runnable() { // from class: com.xiaopeng.server.policy.xpBootManagerPolicy.2
                @Override // java.lang.Runnable
                public void run() {
                    xpBootManagerPolicy.this.scheduleBootTasks(context);
                }
            }, 2000L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleActivityChanged(ComponentName component) {
        if (BOOT_POLICY_ENABLED && !sBootReady && !sActivityReady && xpActivityManager.isComponentValid(component)) {
            if (sFallbackHome == null) {
                sFallbackHome = ComponentName.createRelative(component.getPackageName(), component.getClassName());
            }
            boolean isHome = xpActivityManager.isHome(this.mContext, component);
            boolean isPreload = isPreloadActivity(component);
            boolean isUnlocked = isUserUnlocked();
            log(TAG, "onActivityChanged component=" + component + " home=" + isHome + " preload=" + isPreload + " isUnlocked=" + isUnlocked);
            if (isPreload) {
                sPreloadedList.add(component.flattenToString());
            }
            sPreloadCompleted = preloadCompleted();
            boolean z = false;
            sHomeCompleted = sHomeCompleted || (sPreloadCompleted && isHome);
            if (sPreloadCompleted && sHomeCompleted) {
                z = true;
            }
            sActivityReady = z;
            if (isUnlocked) {
                startNextActivityLocked(this.mContext, 0L);
            }
            scheduleBootTasks(this.mContext);
        }
    }

    private void startNextActivityLocked(final Context context, long delay) {
        log(TAG, "startNextActivityLocked bootReady=" + sBootReady + " activityReady=" + sActivityReady + " timeout=" + sTimeout + " preloadCompleted=" + sPreloadCompleted + " homeCompleted=" + sHomeCompleted);
        if (!BOOT_POLICY_ENABLED || sBootReady || sActivityReady || context == null) {
            return;
        }
        this.mHandler.postDelayed(new Runnable() { // from class: com.xiaopeng.server.policy.xpBootManagerPolicy.3
            @Override // java.lang.Runnable
            public void run() {
                if (xpBootManagerPolicy.sTimeout) {
                    xpBootManagerPolicy.this.startHomeActivity(context);
                } else if (!xpBootManagerPolicy.sPreloadCompleted) {
                    xpBootManagerPolicy.this.startPreloadActivity(context);
                } else if (!xpBootManagerPolicy.sHomeCompleted) {
                    xpBootManagerPolicy.this.startHomeActivity(context);
                }
            }
        }, delay);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startHomeActivity(Context context) {
        log(TAG, "startHomeActivity");
        xpActivityManager.startHome(context);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startPreloadActivity(Context context) {
        String[] strArr;
        sPreloadStarted = true;
        if (sPreloadStartMillis == 0) {
            sPreloadStartMillis = SystemClock.elapsedRealtime();
        }
        ComponentName component = null;
        if (preloadComponentsNotEmpty()) {
            for (String var : sPreloadComponents) {
                boolean preloaded = sPreloadedList.contains(var);
                if (!TextUtils.isEmpty(var) && !preloaded) {
                    try {
                        component = ComponentName.unflattenFromString(var);
                        if (xpActivityManager.isComponentValid(component)) {
                            break;
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }
        if (xpActivityManager.isComponentValid(component)) {
            log(TAG, "startPreloadActivity start component=" + component);
            try {
                Intent intent = new Intent();
                intent.setClassName(component.getPackageName(), component.getClassName());
                intent.addFlags(270532608);
                context.startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
            } catch (Exception e2) {
                log(TAG, "startPreloadActivity e=" + e2);
            }
        }
    }

    public void hideBootMessagesDialog() {
        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.policy.xpBootManagerPolicy.4
            @Override // java.lang.Runnable
            public void run() {
                if (xpBootManagerPolicy.this.mBootMessages != null) {
                    xpBootManagerPolicy.log(xpBootManagerPolicy.TAG, "handleHideBootMessage: dismissing");
                    xpBootManagerPolicy.this.mBootMessages.dismiss();
                    xpBootManagerPolicy.this.mBootMessages = null;
                    boolean unused = xpBootManagerPolicy.sDismissCompleted = true;
                }
            }
        });
    }

    public void performEnableScreen() {
        log(TAG, "performEnableScreen bootAnimationStopped=" + sBootAnimationStopped);
        if (!sBootAnimationStopped) {
            SystemProperties.set("service.bootanim.exit", "1");
            sBootAnimationStopped = true;
            sDismissCompleted = true;
        }
        try {
            IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            if (surfaceFlinger != null) {
                log(TAG, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                surfaceFlinger.transact(1, data, null, 0);
                data.recycle();
                sDismissCompleted = true;
            }
        } catch (RemoteException e) {
            log(TAG, "Boot completed: SurfaceFlinger is dead!");
        }
    }

    private boolean isUserUnlocked() {
        return UserManager.get(this.mContext).isUserUnlocked();
    }

    /* JADX WARN: Code restructure failed: missing block: B:12:0x001f, code lost:
        r0 = false;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean preloadCompleted() {
        /*
            r6 = this;
            r0 = 1
            boolean r1 = preloadComponentsNotEmpty()
            if (r1 == 0) goto L29
            java.lang.String[] r1 = com.xiaopeng.server.policy.xpBootManagerPolicy.sPreloadComponents
            int r2 = r1.length
            r3 = 0
        Lb:
            if (r3 >= r2) goto L29
            r4 = r1[r3]
            boolean r5 = android.text.TextUtils.isEmpty(r4)
            if (r5 == 0) goto L16
            goto L23
        L16:
            monitor-enter(r6)
            java.util.List<java.lang.String> r5 = com.xiaopeng.server.policy.xpBootManagerPolicy.sPreloadedList     // Catch: java.lang.Throwable -> L26
            boolean r5 = r5.contains(r4)     // Catch: java.lang.Throwable -> L26
            if (r5 != 0) goto L22
            r0 = 0
            monitor-exit(r6)     // Catch: java.lang.Throwable -> L26
            goto L29
        L22:
            monitor-exit(r6)     // Catch: java.lang.Throwable -> L26
        L23:
            int r3 = r3 + 1
            goto Lb
        L26:
            r1 = move-exception
            monitor-exit(r6)     // Catch: java.lang.Throwable -> L26
            throw r1
        L29:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.policy.xpBootManagerPolicy.preloadCompleted():boolean");
    }

    private boolean isPreloadActivity(ComponentName component) {
        String[] strArr;
        if (xpActivityManager.isComponentValid(component)) {
            try {
                if (preloadComponentsNotEmpty()) {
                    for (String var : sPreloadComponents) {
                        ComponentName c = !TextUtils.isEmpty(var) ? ComponentName.unflattenFromString(var) : null;
                        if (xpActivityManager.isComponentEqual(component, c)) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
            }
            return false;
        }
        return false;
    }

    public void onBootCompleted() {
        SystemProperties.set("sys.xp.boot_completed", "1");
        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.policy.-$$Lambda$xpBootManagerPolicy$rSmnJGqfOlZ9TzpClb6cfZH9R6A
            @Override // java.lang.Runnable
            public final void run() {
                xpBootManagerPolicy.this.lambda$onBootCompleted$0$xpBootManagerPolicy();
            }
        });
    }

    public /* synthetic */ void lambda$onBootCompleted$0$xpBootManagerPolicy() {
        try {
            Intent intent = new Intent("com.xiaopeng.intent.action.BOOT_COMPLETED");
            intent.addFlags(16777216);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            log(TAG, "onBootCompleted by boot policy");
        } catch (Exception e) {
            log(TAG, "onBootCompleted e=" + e);
        }
    }

    private static double getCpuUsage() {
        IActivityManager am = xpActivityManager.getActivityManager();
        if (am != null) {
            try {
                double[] info = am.getUsageInfo();
                if (info != null && info.length > 0) {
                    return info[0];
                }
                return CPU;
            } catch (Exception e) {
                return CPU;
            }
        }
        return CPU;
    }

    private static boolean preloadComponentsNotEmpty() {
        String[] strArr = sPreloadComponents;
        return strArr != null && strArr.length > 0;
    }

    private static String[] getPreloadComponents() {
        String[] defaultComponents = PRELOAD_COMPONENTS;
        String[] preloadComponents = readPreloadComponents();
        String[] components = (preloadComponents == null || preloadComponents.length <= 0) ? defaultComponents : preloadComponents;
        if (components != null && components.length > 0) {
            ArrayList<String> list = new ArrayList<>();
            for (String var : components) {
                ComponentName component = ComponentName.unflattenFromString(var);
                if (component != null) {
                    String key = "persist.xp.boot.policy." + component.getPackageName();
                    if (SystemProperties.getBoolean(key, true)) {
                        list.add(var);
                    }
                }
            }
            if (list.size() > 0) {
                String[] array = new String[list.size()];
                list.toArray(array);
                log(TAG, "getPreloadComponents value=" + Arrays.toString(array));
                return array;
            }
            return null;
        }
        return null;
    }

    private static String[] readPreloadComponents() {
        try {
            File file = new File("/system/etc/xui_preload_components.ini");
            String[] value = xpTextUtils.getArrays(file);
            log(TAG, "readPreloadComponents value=" + Arrays.toString(value));
            if (value != null) {
                if (value.length > 0) {
                    return value;
                }
                return null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String tag, String msg) {
        long now = System.currentTimeMillis();
        xpLogger.i(tag, msg);
        if (DEBUG) {
            log(format(now) + " " + tag + " " + msg + "\n");
        }
    }

    private static boolean log(String msg) {
        try {
            if (!TextUtils.isEmpty(msg)) {
                File root = new File("/data/Log/log0");
                if (!root.exists()) {
                    root.mkdirs();
                }
                File file = new File("/data/Log/log0/boot.txt");
                if (!file.exists()) {
                    file.createNewFile();
                }
                byte[] bytes = msg.getBytes();
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write(bytes);
                fos.close();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static String format(long date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(date));
    }
}
