package com.android.server.power;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.app.admin.SecurityLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemVibrator;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TimingsTraceLog;
import com.android.server.LocalServices;
import com.android.server.RescueParty;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.PackageManagerService;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.xiaopeng.util.FeatureOption;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/* loaded from: classes.dex */
public final class ShutdownThread extends Thread {
    private static final int ACTION_DONE_POLL_WAIT_MS = 500;
    private static final int ACTIVITY_MANAGER_STOP_PERCENT = 4;
    private static final int BROADCAST_STOP_PERCENT = 2;
    private static final int MAX_BROADCAST_TIME = 10000;
    private static final int MAX_RADIO_WAIT_TIME = 12000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 20000;
    private static final int MAX_UNCRYPT_WAIT_TIME = 900000;
    private static final String METRICS_FILE_BASENAME = "/data/system/shutdown-metrics";
    private static final int MOUNT_SERVICE_STOP_PERCENT = 20;
    private static final int PACKAGE_MANAGER_STOP_PERCENT = 6;
    private static final int RADIOS_STATE_POLL_SLEEP_MS = 100;
    private static final int RADIO_STOP_PERCENT = 18;
    public static final String REBOOT_SAFEMODE_PROPERTY = "persist.sys.safemode";
    public static final String RO_SAFEMODE_PROPERTY = "ro.sys.safemode";
    public static final String SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested";
    private static final int SHUTDOWN_VIBRATE_MS = 500;
    private static final String TAG = "ShutdownThread";
    private static final boolean is3DUI;
    private static String mReason;
    private static boolean mReboot;
    private static boolean mRebootHasProgressBar;
    private static boolean mRebootSafeMode;
    private static AlertDialog sConfirmDialog;
    private boolean mActionDone;
    private final Object mActionDoneSync = new Object();
    private Context mContext;
    private PowerManager.WakeLock mCpuWakeLock;
    private Handler mHandler;
    private PowerManager mPowerManager;
    private ProgressDialog mProgressDialog;
    private PowerManager.WakeLock mScreenWakeLock;
    private static final Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    private static final ShutdownThread sInstance = new ShutdownThread();
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    private static final ArrayMap<String, Long> TRON_METRICS = new ArrayMap<>();
    private static String METRIC_SYSTEM_SERVER = "shutdown_system_server";
    private static String METRIC_SEND_BROADCAST = "shutdown_send_shutdown_broadcast";
    private static String METRIC_AM = "shutdown_activity_manager";
    private static String METRIC_PM = "shutdown_package_manager";
    private static String METRIC_RADIOS = "shutdown_radios";
    private static String METRIC_RADIO = "shutdown_radio";
    private static String METRIC_SHUTDOWN_TIME_START = "begin_shutdown";

    static /* synthetic */ TimingsTraceLog access$200() {
        return newTimingsLog();
    }

    static {
        is3DUI = FeatureOption.FO_PROJECT_UI_TYPE == 2;
    }

    private ShutdownThread() {
    }

    public static void shutdown(Context context, String reason, boolean confirm) {
        mReboot = false;
        mRebootSafeMode = false;
        mReason = reason;
        shutdownInner(context, confirm);
    }

    private static void shutdownInner(final Context context, boolean confirm) {
        int resourceId;
        int i;
        context.assertRuntimeOverlayThemable();
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
            int longPressBehavior = context.getResources().getInteger(17694825);
            if (mRebootSafeMode) {
                resourceId = 17040910;
            } else if (longPressBehavior == 2) {
                resourceId = 17041037;
            } else {
                resourceId = 17041036;
            }
            Log.d(TAG, "Notifying thread to start shutdown longPressBehavior=" + longPressBehavior);
            if (confirm) {
                CloseDialogReceiver closer = new CloseDialogReceiver(context);
                AlertDialog alertDialog = sConfirmDialog;
                if (alertDialog != null) {
                    alertDialog.dismiss();
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                if (mRebootSafeMode) {
                    i = 17040911;
                } else {
                    i = 17040893;
                }
                sConfirmDialog = builder.setTitle(i).setMessage(resourceId).setPositiveButton(17039379, new DialogInterface.OnClickListener() { // from class: com.android.server.power.ShutdownThread.1
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialog, int which) {
                        ShutdownThread.beginShutdownSequence(context);
                    }
                }).setNegativeButton(17039369, (DialogInterface.OnClickListener) null).create();
                AlertDialog alertDialog2 = sConfirmDialog;
                closer.dialog = alertDialog2;
                alertDialog2.setOnDismissListener(closer);
                sConfirmDialog.getWindow().setType(2009);
                sConfirmDialog.show();
                return;
            }
            beginShutdownSequence(context);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class CloseDialogReceiver extends BroadcastReceiver implements DialogInterface.OnDismissListener {
        public Dialog dialog;
        private Context mContext;

        CloseDialogReceiver(Context context) {
            this.mContext = context;
            IntentFilter filter = new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS");
            context.registerReceiver(this, filter);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            this.dialog.cancel();
        }

        @Override // android.content.DialogInterface.OnDismissListener
        public void onDismiss(DialogInterface unused) {
            this.mContext.unregisterReceiver(this);
        }
    }

    public static void reboot(Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootSafeMode = false;
        mRebootHasProgressBar = false;
        mReason = reason;
        shutdownInner(context, confirm);
    }

    public static void rebootSafeMode(Context context, boolean confirm) {
        UserManager um = (UserManager) context.getSystemService("user");
        if (um.hasUserRestriction("no_safe_boot")) {
            return;
        }
        mReboot = true;
        mRebootSafeMode = true;
        mRebootHasProgressBar = false;
        mReason = null;
        shutdownInner(context, confirm);
    }

    private static ProgressDialog showShutdownDialog(Context context) {
        ProgressDialog pd = new ProgressDialog(context);
        String str = mReason;
        if (str != null && str.startsWith("recovery-update")) {
            mRebootHasProgressBar = RecoverySystem.UNCRYPT_PACKAGE_FILE.exists() && !RecoverySystem.BLOCK_MAP_FILE.exists();
            pd.setTitle(context.getText(17040917));
            if (mRebootHasProgressBar) {
                pd.setMax(100);
                pd.setProgress(0);
                pd.setIndeterminate(false);
                pd.setProgressNumberFormat(null);
                pd.setProgressStyle(1);
                pd.setMessage(context.getText(17040915));
            } else if (showSysuiReboot()) {
                return null;
            } else {
                pd.setIndeterminate(true);
                pd.setMessage(context.getText(17040916));
            }
        } else {
            String str2 = mReason;
            if (str2 != null && str2.equals("recovery")) {
                if (RescueParty.isAttemptingFactoryReset()) {
                    pd.setTitle(context.getText(17040893));
                    pd.setMessage(context.getText(17041038));
                    pd.setIndeterminate(true);
                } else {
                    pd.setTitle(context.getText(17040913));
                    pd.setMessage(context.getText(17040912));
                    pd.setIndeterminate(true);
                    if (is3DUI) {
                        SystemProperties.set("service.bootanim.exit", "0");
                        SystemProperties.set("ctl.start", "bootanim");
                        return pd;
                    }
                }
            } else if (showSysuiReboot()) {
                return null;
            } else {
                pd.setTitle(context.getText(17040893));
                pd.setMessage(context.getText(17041038));
                pd.setIndeterminate(true);
            }
        }
        pd.setCancelable(false);
        pd.getWindow().setType(2009);
        pd.show();
        return pd;
    }

    private static boolean showSysuiReboot() {
        Log.d(TAG, "Attempting to use SysUI shutdown UI");
        try {
            StatusBarManagerInternal service = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
            if (service.showShutdownUi(mReboot, mReason)) {
                Log.d(TAG, "SysUI handling shutdown UI");
                return true;
            }
        } catch (Exception e) {
        }
        Log.d(TAG, "SysUI is unavailable");
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Shutdown sequence already running, returning.");
                return;
            }
            sIsStarted = true;
            sInstance.mProgressDialog = showShutdownDialog(context);
            ShutdownThread shutdownThread = sInstance;
            shutdownThread.mContext = context;
            shutdownThread.mPowerManager = (PowerManager) context.getSystemService("power");
            ShutdownThread shutdownThread2 = sInstance;
            shutdownThread2.mCpuWakeLock = null;
            try {
                shutdownThread2.mCpuWakeLock = shutdownThread2.mPowerManager.newWakeLock(1, "ShutdownThread-cpu");
                sInstance.mCpuWakeLock.setReferenceCounted(false);
                sInstance.mCpuWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mCpuWakeLock = null;
            }
            ShutdownThread shutdownThread3 = sInstance;
            shutdownThread3.mScreenWakeLock = null;
            if (shutdownThread3.mPowerManager.isScreenOn()) {
                try {
                    sInstance.mScreenWakeLock = sInstance.mPowerManager.newWakeLock(26, "ShutdownThread-screen");
                    sInstance.mScreenWakeLock.setReferenceCounted(false);
                    sInstance.mScreenWakeLock.acquire();
                } catch (SecurityException e2) {
                    Log.w(TAG, "No permission to acquire wake lock", e2);
                    sInstance.mScreenWakeLock = null;
                }
            }
            if (SecurityLog.isLoggingEnabled()) {
                SecurityLog.writeEvent(210010, new Object[0]);
            }
            sInstance.mHandler = new Handler() { // from class: com.android.server.power.ShutdownThread.2
            };
            sInstance.start();
        }
    }

    void actionDone() {
        synchronized (this.mActionDoneSync) {
            this.mActionDone = true;
            this.mActionDoneSync.notifyAll();
        }
    }

    @Override // java.lang.Thread, java.lang.Runnable
    public void run() {
        TimingsTraceLog shutdownTimingLog = newTimingsLog();
        shutdownTimingLog.traceBegin("SystemServerShutdown");
        metricShutdownStart();
        metricStarted(METRIC_SYSTEM_SERVER);
        BroadcastReceiver br = new BroadcastReceiver() { // from class: com.android.server.power.ShutdownThread.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                ShutdownThread.this.actionDone();
            }
        };
        StringBuilder sb = new StringBuilder();
        sb.append(mReboot ? "1" : "0");
        String str = mReason;
        if (str == null) {
            str = "";
        }
        sb.append(str);
        String reason = sb.toString();
        SystemProperties.set(SHUTDOWN_ACTION_PROPERTY, reason);
        if (mRebootSafeMode) {
            SystemProperties.set(REBOOT_SAFEMODE_PROPERTY, "1");
        }
        metricStarted(METRIC_SEND_BROADCAST);
        shutdownTimingLog.traceBegin("SendShutdownBroadcast");
        Log.i(TAG, "Sending shutdown broadcast...");
        this.mActionDone = false;
        Intent intent = new Intent("android.intent.action.ACTION_SHUTDOWN");
        intent.addFlags(1342177280);
        this.mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL, null, br, this.mHandler, 0, null, null);
        long endTime = SystemClock.elapsedRealtime() + JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        synchronized (this.mActionDoneSync) {
            while (true) {
                if (this.mActionDone) {
                    break;
                }
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                }
                if (mRebootHasProgressBar) {
                    int status = (int) ((((JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY - delay) * 1.0d) * 2.0d) / 10000.0d);
                    sInstance.setRebootProgress(status, null);
                }
                try {
                    this.mActionDoneSync.wait(Math.min(delay, 500L));
                } catch (InterruptedException e) {
                }
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(2, null);
        }
        shutdownTimingLog.traceEnd();
        metricEnded(METRIC_SEND_BROADCAST);
        Log.i(TAG, "Shutting down activity manager...");
        shutdownTimingLog.traceBegin("ShutdownActivityManager");
        metricStarted(METRIC_AM);
        IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(10000);
            } catch (RemoteException e2) {
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(4, null);
        }
        shutdownTimingLog.traceEnd();
        metricEnded(METRIC_AM);
        Log.i(TAG, "Shutting down package manager...");
        shutdownTimingLog.traceBegin("ShutdownPackageManager");
        metricStarted(METRIC_PM);
        PackageManagerService pm = (PackageManagerService) ServiceManager.getService("package");
        if (pm != null) {
            pm.shutdown();
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(6, null);
        }
        shutdownTimingLog.traceEnd();
        metricEnded(METRIC_PM);
        shutdownTimingLog.traceBegin("ShutdownRadios");
        metricStarted(METRIC_RADIOS);
        shutdownRadios(MAX_RADIO_WAIT_TIME);
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(18, null);
        }
        shutdownTimingLog.traceEnd();
        metricEnded(METRIC_RADIOS);
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(20, null);
            uncrypt();
        }
        shutdownTimingLog.traceEnd();
        metricEnded(METRIC_SYSTEM_SERVER);
        saveMetrics(mReboot, mReason);
        rebootOrShutdown(this.mContext, mReboot, mReason);
    }

    private static TimingsTraceLog newTimingsLog() {
        return new TimingsTraceLog("ShutdownTiming", 524288L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void metricStarted(String metricKey) {
        synchronized (TRON_METRICS) {
            TRON_METRICS.put(metricKey, Long.valueOf(SystemClock.elapsedRealtime() * (-1)));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void metricEnded(String metricKey) {
        synchronized (TRON_METRICS) {
            TRON_METRICS.put(metricKey, Long.valueOf(SystemClock.elapsedRealtime() + TRON_METRICS.get(metricKey).longValue()));
        }
    }

    private static void metricShutdownStart() {
        synchronized (TRON_METRICS) {
            TRON_METRICS.put(METRIC_SHUTDOWN_TIME_START, Long.valueOf(System.currentTimeMillis()));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setRebootProgress(final int progress, final CharSequence message) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.power.ShutdownThread.4
            @Override // java.lang.Runnable
            public void run() {
                if (ShutdownThread.this.mProgressDialog != null) {
                    ShutdownThread.this.mProgressDialog.setProgress(progress);
                    if (message != null) {
                        ShutdownThread.this.mProgressDialog.setMessage(message);
                    }
                }
            }
        });
    }

    private void shutdownRadios(final int timeout) {
        final long endTime = SystemClock.elapsedRealtime() + timeout;
        final boolean[] done = new boolean[1];
        Thread t = new Thread() { // from class: com.android.server.power.ShutdownThread.5
            /* JADX WARN: Removed duplicated region for block: B:12:0x0026 A[Catch: RemoteException -> 0x0021, TRY_LEAVE, TryCatch #0 {RemoteException -> 0x0021, blocks: (B:4:0x0018, B:12:0x0026), top: B:33:0x0018 }] */
            /* JADX WARN: Removed duplicated region for block: B:19:0x004e  */
            @Override // java.lang.Thread, java.lang.Runnable
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct code enable 'Show inconsistent code' option in preferences
            */
            public void run() {
                /*
                    r14 = this;
                    android.util.TimingsTraceLog r0 = com.android.server.power.ShutdownThread.access$200()
                    java.lang.String r1 = "phone"
                    android.os.IBinder r1 = android.os.ServiceManager.checkService(r1)
                    com.android.internal.telephony.ITelephony r1 = com.android.internal.telephony.ITelephony.Stub.asInterface(r1)
                    r2 = 0
                    java.lang.String r3 = "RemoteException during radio shutdown"
                    r4 = 1
                    java.lang.String r5 = "ShutdownThread"
                    if (r1 == 0) goto L23
                    boolean r6 = r1.needMobileRadioShutdown()     // Catch: android.os.RemoteException -> L21
                    if (r6 != 0) goto L1f
                    goto L23
                L1f:
                    r6 = r2
                    goto L24
                L21:
                    r6 = move-exception
                    goto L36
                L23:
                    r6 = r4
                L24:
                    if (r6 != 0) goto L3b
                    java.lang.String r7 = "Turning off cellular radios..."
                    android.util.Log.w(r5, r7)     // Catch: android.os.RemoteException -> L21
                    java.lang.String r7 = com.android.server.power.ShutdownThread.access$300()     // Catch: android.os.RemoteException -> L21
                    com.android.server.power.ShutdownThread.access$400(r7)     // Catch: android.os.RemoteException -> L21
                    r1.shutdownMobileRadios()     // Catch: android.os.RemoteException -> L21
                    goto L3b
                L36:
                    android.util.Log.e(r5, r3, r6)
                    r6 = 1
                    goto L3c
                L3b:
                L3c:
                    java.lang.String r7 = "Waiting for Radio..."
                    android.util.Log.i(r5, r7)
                    long r7 = r2
                    long r9 = android.os.SystemClock.elapsedRealtime()
                    long r7 = r7 - r9
                L48:
                    r9 = 0
                    int r9 = (r7 > r9 ? 1 : (r7 == r9 ? 0 : -1))
                    if (r9 <= 0) goto Lba
                    boolean r9 = com.android.server.power.ShutdownThread.access$500()
                    if (r9 == 0) goto L6c
                    int r9 = r4
                    long r10 = (long) r9
                    long r10 = r10 - r7
                    double r10 = (double) r10
                    r12 = 4607182418800017408(0x3ff0000000000000, double:1.0)
                    double r10 = r10 * r12
                    r12 = 4622945017495814144(0x4028000000000000, double:12.0)
                    double r10 = r10 * r12
                    double r12 = (double) r9
                    double r10 = r10 / r12
                    int r9 = (int) r10
                    int r9 = r9 + 6
                    com.android.server.power.ShutdownThread r10 = com.android.server.power.ShutdownThread.access$600()
                    r11 = 0
                    com.android.server.power.ShutdownThread.access$700(r10, r9, r11)
                L6c:
                    if (r6 != 0) goto La0
                    boolean r9 = r1.needMobileRadioShutdown()     // Catch: android.os.RemoteException -> L75
                    r9 = r9 ^ r4
                    r6 = r9
                    goto L7a
                L75:
                    r9 = move-exception
                    android.util.Log.e(r5, r3, r9)
                    r6 = 1
                L7a:
                    if (r6 == 0) goto La0
                    java.lang.String r9 = "Radio turned off."
                    android.util.Log.i(r5, r9)
                    java.lang.String r9 = com.android.server.power.ShutdownThread.access$300()
                    com.android.server.power.ShutdownThread.access$800(r9)
                    android.util.ArrayMap r9 = com.android.server.power.ShutdownThread.access$900()
                    java.lang.String r10 = com.android.server.power.ShutdownThread.access$300()
                    java.lang.Object r9 = r9.get(r10)
                    java.lang.Long r9 = (java.lang.Long) r9
                    long r9 = r9.longValue()
                    java.lang.String r11 = "ShutdownRadio"
                    r0.logDuration(r11, r9)
                La0:
                    if (r6 == 0) goto Lac
                    java.lang.String r3 = "Radio shutdown complete."
                    android.util.Log.i(r5, r3)
                    boolean[] r3 = r5
                    r3[r2] = r4
                    goto Lba
                Lac:
                    r9 = 100
                    android.os.SystemClock.sleep(r9)
                    long r9 = r2
                    long r11 = android.os.SystemClock.elapsedRealtime()
                    long r7 = r9 - r11
                    goto L48
                Lba:
                    return
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.server.power.ShutdownThread.AnonymousClass5.run():void");
            }
        };
        t.start();
        try {
            t.join(timeout);
        } catch (InterruptedException e) {
        }
        if (!done[0]) {
            Log.w(TAG, "Timed out waiting for Radio shutdown.");
        }
    }

    public static void rebootOrShutdown(Context context, boolean reboot, String reason) {
        if (reboot) {
            Log.i(TAG, "Rebooting, reason: " + reason);
            PowerManagerService.lowLevelReboot(reason);
            Log.e(TAG, "Reboot failed, will attempt shutdown instead");
            reason = null;
        } else if (context != null) {
            try {
                new SystemVibrator(context).vibrate(500L, VIBRATION_ATTRIBUTES);
            } catch (Exception e) {
                Log.w(TAG, "Failed to vibrate during shutdown.", e);
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e2) {
            }
        }
        Log.i(TAG, "Performing low-level shutdown...");
        PowerManagerService.lowLevelShutdown(reason);
    }

    private static void saveMetrics(boolean reboot, String reason) {
        StringBuilder metricValue = new StringBuilder();
        metricValue.append("reboot:");
        metricValue.append(reboot ? "y" : "n");
        metricValue.append(",");
        metricValue.append("reason:");
        metricValue.append(reason);
        int metricsSize = TRON_METRICS.size();
        for (int i = 0; i < metricsSize; i++) {
            String name = TRON_METRICS.keyAt(i);
            long value = TRON_METRICS.valueAt(i).longValue();
            if (value < 0) {
                Log.e(TAG, "metricEnded wasn't called for " + name);
            } else {
                metricValue.append(',');
                metricValue.append(name);
                metricValue.append(':');
                metricValue.append(value);
            }
        }
        File tmp = new File("/data/system/shutdown-metrics.tmp");
        boolean saved = false;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(metricValue.toString().getBytes(StandardCharsets.UTF_8));
            saved = true;
            fos.close();
        } catch (IOException e) {
            Log.e(TAG, "Cannot save shutdown metrics", e);
        }
        if (saved) {
            tmp.renameTo(new File("/data/system/shutdown-metrics.txt"));
        }
    }

    private void uncrypt() {
        Log.i(TAG, "Calling uncrypt and monitoring the progress...");
        final RecoverySystem.ProgressListener progressListener = new RecoverySystem.ProgressListener() { // from class: com.android.server.power.ShutdownThread.6
            @Override // android.os.RecoverySystem.ProgressListener
            public void onProgress(int status) {
                if (status >= 0 && status < 100) {
                    CharSequence msg = ShutdownThread.this.mContext.getText(17040914);
                    ShutdownThread.sInstance.setRebootProgress(((int) ((status * 80.0d) / 100.0d)) + 20, msg);
                } else if (status == 100) {
                    CharSequence msg2 = ShutdownThread.this.mContext.getText(17040916);
                    ShutdownThread.sInstance.setRebootProgress(status, msg2);
                }
            }
        };
        final boolean[] done = {false};
        Thread t = new Thread() { // from class: com.android.server.power.ShutdownThread.7
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                RecoverySystem recoverySystem = (RecoverySystem) ShutdownThread.this.mContext.getSystemService("recovery");
                try {
                    String filename = FileUtils.readTextFile(RecoverySystem.UNCRYPT_PACKAGE_FILE, 0, null);
                    RecoverySystem.processPackage(ShutdownThread.this.mContext, new File(filename), progressListener);
                } catch (IOException e) {
                    Log.e(ShutdownThread.TAG, "Error uncrypting file", e);
                }
                done[0] = true;
            }
        };
        t.start();
        try {
            t.join(900000L);
        } catch (InterruptedException e) {
        }
        if (!done[0]) {
            Log.w(TAG, "Timed out waiting for uncrypt.");
            String timeoutMessage = String.format("uncrypt_time: %d\nuncrypt_error: %d\n", 900, 100);
            try {
                FileUtils.stringToFile(RecoverySystem.UNCRYPT_STATUS_FILE, timeoutMessage);
            } catch (IOException e2) {
                Log.e(TAG, "Failed to write timeout message to uncrypt status", e2);
            }
        }
    }
}
