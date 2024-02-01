package com.android.server;

import android.app.ActivityThread;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteCompatibilityWalFlags;
import android.database.sqlite.SQLiteGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.IIncidentManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.util.TimingsTraceLog;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BinderInternal;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.am.ActivityManagerService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.display.DisplayManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.lights.LightsService;
import com.android.server.media.MediaRouterService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.om.OverlayManagerService;
import com.android.server.os.DeviceIdentifiersPolicyService;
import com.android.server.pm.Installer;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.usage.UsageStatsService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.server.aftersales.AfterSalesService;
import dalvik.system.VMRuntime;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
/* loaded from: classes.dex */
public final class SystemServer {
    private static final String ACCOUNT_SERVICE_CLASS = "com.android.server.accounts.AccountManagerService$Lifecycle";
    private static final String APPWIDGET_SERVICE_CLASS = "com.android.server.appwidget.AppWidgetService";
    private static final String AUTO_FILL_MANAGER_SERVICE_CLASS = "com.android.server.autofill.AutofillManagerService";
    private static final String BACKUP_MANAGER_SERVICE_CLASS = "com.android.server.backup.BackupManagerService$Lifecycle";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";
    private static final String CAR_SERVICE_HELPER_SERVICE_CLASS = "com.android.internal.car.CarServiceHelperService";
    private static final String COMPANION_DEVICE_MANAGER_SERVICE_CLASS = "com.android.server.companion.CompanionDeviceManagerService";
    private static final String CONTENT_SERVICE_CLASS = "com.android.server.content.ContentService$Lifecycle";
    private static final int DEFAULT_SYSTEM_THEME = 16974812;
    private static final long EARLIEST_SUPPORTED_TIME = 86400000;
    private static final boolean ENABLE_FM1388_PLAY_REC = false;
    private static final String ENCRYPTED_STATE = "1";
    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ETHERNET_SERVICE_CLASS = "com.android.server.ethernet.EthernetService";
    private static final String IOT_SERVICE_CLASS = "com.google.android.things.services.IoTSystemService";
    private static final String JOB_SCHEDULER_SERVICE_CLASS = "com.android.server.job.JobSchedulerService";
    private static final String LOCK_SETTINGS_SERVICE_CLASS = "com.android.server.locksettings.LockSettingsService$Lifecycle";
    private static final String LOWPAN_SERVICE_CLASS = "com.android.server.lowpan.LowpanService";
    private static final String MIDI_SERVICE_CLASS = "com.android.server.midi.MidiService$Lifecycle";
    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    private static final String PRINT_MANAGER_SERVICE_CLASS = "com.android.server.print.PrintManagerService";
    private static final String SEARCH_MANAGER_SERVICE_CLASS = "com.android.server.search.SearchManagerService$Lifecycle";
    private static final String SLICE_MANAGER_SERVICE_CLASS = "com.android.server.slice.SliceManagerService$Lifecycle";
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SNAPSHOT_INTERVAL = 3600000;
    private static final String START_HIDL_SERVICES = "StartHidlServices";
    private static final String START_SENSOR_SERVICE = "StartSensorService";
    private static final String STORAGE_MANAGER_SERVICE_CLASS = "com.android.server.StorageManagerService$Lifecycle";
    private static final String STORAGE_STATS_SERVICE_CLASS = "com.android.server.usage.StorageStatsService$Lifecycle";
    private static final String SYSPROP_START_COUNT = "sys.system_server.start_count";
    private static final String SYSPROP_START_ELAPSED = "sys.system_server.start_elapsed";
    private static final String SYSPROP_START_UPTIME = "sys.system_server.start_uptime";
    private static final String SYSTEM_SERVER_TIMING_ASYNC_TAG = "SystemServerTimingAsync";
    private static final String TAG = "SystemServer";
    private static final String TBOX_SERVICE_CLASS = "com.android.server.tbox.TBoxService";
    private static final String THERMAL_OBSERVER_CLASS = "com.google.android.clockwork.ThermalObserver";
    private static final String TIME_ZONE_RULES_MANAGER_SERVICE_CLASS = "com.android.server.timezone.RulesManagerService$Lifecycle";
    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String USBNET_SERVICE_CLASS = "com.android.server.usbnet.USBNetService";
    private static final String USB_SERVICE_CLASS = "com.android.server.usb.UsbService$Lifecycle";
    private static final String VOICE_RECOGNITION_MANAGER_SERVICE_CLASS = "com.android.server.voiceinteraction.VoiceInteractionManagerService";
    private static final String WALLPAPER_SERVICE_CLASS = "com.android.server.wallpaper.WallpaperManagerService$Lifecycle";
    private static final String WEAR_CONFIG_SERVICE_CLASS = "com.google.android.clockwork.WearConfigManagerService";
    private static final String WEAR_CONNECTIVITY_SERVICE_CLASS = "com.android.clockwork.connectivity.WearConnectivityService";
    private static final String WEAR_DISPLAY_SERVICE_CLASS = "com.google.android.clockwork.display.WearDisplayService";
    private static final String WEAR_GLOBAL_ACTIONS_SERVICE_CLASS = "com.android.clockwork.globalactions.GlobalActionsService";
    private static final String WEAR_LEFTY_SERVICE_CLASS = "com.google.android.clockwork.lefty.WearLeftyService";
    private static final String WEAR_SIDEKICK_SERVICE_CLASS = "com.google.android.clockwork.sidekick.SidekickService";
    private static final String WEAR_TIME_SERVICE_CLASS = "com.google.android.clockwork.time.WearTimeService";
    private static final String WIFI_AWARE_SERVICE_CLASS = "com.android.server.wifi.aware.WifiAwareService";
    private static final String WIFI_P2P_SERVICE_CLASS = "com.android.server.wifi.p2p.WifiP2pService";
    private static final String WIFI_SERVICE_CLASS = "com.android.server.wifi.WifiService";
    private static final int sMaxBinderThreads = 31;
    private ActivityManagerService mActivityManagerService;
    private ContentResolver mContentResolver;
    private DisplayManagerService mDisplayManagerService;
    private EntropyMixer mEntropyMixer;
    private boolean mFirstBoot;
    private boolean mOnlyCore;
    private PackageManager mPackageManager;
    private PackageManagerService mPackageManagerService;
    private PowerManagerService mPowerManagerService;
    private Timer mProfilerSnapshotTimer;
    private Future<?> mSensorServiceStart;
    private Context mSystemContext;
    private SystemServiceManager mSystemServiceManager;
    private WebViewUpdateService mWebViewUpdateService;
    private Future<?> mZygotePreload;
    private static final String SYSTEM_SERVER_TIMING_TAG = "SystemServerTiming";
    private static final TimingsTraceLog BOOT_TIMINGS_TRACE_LOG = new TimingsTraceLog(SYSTEM_SERVER_TIMING_TAG, 524288);
    private static final long CATCHCATON = SystemProperties.getLong("persist.sys.debug.caton", -1);
    private static boolean mBootProfDisable = false;
    private final int mFactoryTestMode = FactoryTest.getMode();
    private final int mStartCount = SystemProperties.getInt(SYSPROP_START_COUNT, 0) + 1;
    private final boolean mRuntimeRestart = ENCRYPTED_STATE.equals(SystemProperties.get("sys.boot_completed"));
    private final long mRuntimeStartElapsedTime = SystemClock.elapsedRealtime();
    private final long mRuntimeStartUptime = SystemClock.uptimeMillis();

    private static native void startHidlServices();

    private static native void startSensorService();

    private static void addBootEvent(String bootevent) {
        if (mBootProfDisable) {
            return;
        }
        try {
            FileOutputStream fbp = new FileOutputStream("/proc/bootprof");
            fbp.write(bootevent.getBytes());
            fbp.flush();
            fbp.close();
        } catch (FileNotFoundException e) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof, not found!", e);
        } catch (IOException e2) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof entry", e2);
        }
    }

    public static void main(String[] args) {
        new SystemServer().run();
    }

    public SystemServer() {
        SystemProperties.set("sys.system_server.pid", String.valueOf(Process.myPid()));
    }

    private void run() {
        try {
            traceBeginAndSlog("InitBeforeStartServices");
            SystemProperties.set(SYSPROP_START_COUNT, String.valueOf(this.mStartCount));
            SystemProperties.set(SYSPROP_START_ELAPSED, String.valueOf(this.mRuntimeStartElapsedTime));
            SystemProperties.set(SYSPROP_START_UPTIME, String.valueOf(this.mRuntimeStartUptime));
            if (System.currentTimeMillis() < 86400000) {
                Slog.w(TAG, "System clock is before 1970; setting to 1970.");
                SystemClock.setCurrentTimeMillis(86400000L);
            }
            String timezoneProperty = SystemProperties.get("persist.sys.timezone");
            if (timezoneProperty == null || timezoneProperty.isEmpty()) {
                Slog.w(TAG, "Timezone not set; setting to GMT.");
                SystemProperties.set("persist.sys.timezone", "GMT");
            }
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                String languageTag = Locale.getDefault().toLanguageTag();
                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                SystemProperties.set("persist.sys.country", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                SystemProperties.set("persist.sys.localevar", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            Binder.setWarnOnBlocking(true);
            PackageItemInfo.setForceSafeLabels(true);
            SQLiteGlobal.sDefaultSyncMode = "FULL";
            SQLiteCompatibilityWalFlags.init((String) null);
            Slog.i(TAG, "Entered the Android system server!");
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            mBootProfDisable = ENCRYPTED_STATE.equals(SystemProperties.get("ro.bootprof.disable", "0"));
            addBootEvent("SystemServer: boot_progress_system_run");
            if (!this.mRuntimeRestart) {
                MetricsLogger.histogram((Context) null, "boot_system_server_init", uptimeMillis);
            }
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());
            VMRuntime.getRuntime().clearGrowthLimit();
            VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);
            Build.ensureFingerprintProperty();
            Environment.setUserRequired(true);
            BaseBundle.setShouldDefuse(true);
            Parcel.setStackTraceParceling(true);
            BinderInternal.disableBackgroundScheduling(true);
            BinderInternal.setMaxThreads(31);
            Process.setThreadPriority(-2);
            Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();
            Looper.getMainLooper().setSlowLogThresholdMs(100L, SLOW_DELIVERY_THRESHOLD_MS);
            System.loadLibrary("android_servers");
            performPendingShutdown();
            createSystemContext();
            this.mSystemServiceManager = new SystemServiceManager(this.mSystemContext);
            this.mSystemServiceManager.setStartInfo(this.mRuntimeRestart, this.mRuntimeStartElapsedTime, this.mRuntimeStartUptime);
            LocalServices.addService(SystemServiceManager.class, this.mSystemServiceManager);
            SystemServerInitThreadPool.get();
            try {
                traceBeginAndSlog("StartServices");
                startBootstrapServices();
                startCoreServices();
                startOtherServices();
                SystemServerInitThreadPool.shutdown();
                traceEnd();
                StrictMode.initVmDefaults(null);
                if (!this.mRuntimeRestart && !isFirstBootOrUpgrade()) {
                    int uptimeMillis2 = (int) SystemClock.elapsedRealtime();
                    MetricsLogger.histogram((Context) null, "boot_system_server_ready", uptimeMillis2);
                    if (uptimeMillis2 > 60000) {
                        Slog.wtf(SYSTEM_SERVER_TIMING_TAG, "SystemServer init took too long. uptimeMillis=" + uptimeMillis2);
                    }
                }
                addBootEvent("SystemServer: boot_progress_system_ready");
                Looper.loop();
                throw new RuntimeException("Main thread loop unexpectedly exited");
            } finally {
            }
        } finally {
        }
    }

    private boolean isFirstBootOrUpgrade() {
        return this.mPackageManagerService.isFirstBoot() || this.mPackageManagerService.isUpgrade();
    }

    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    private void performPendingShutdown() {
        final String reason;
        String shutdownAction = SystemProperties.get(ShutdownThread.SHUTDOWN_ACTION_PROPERTY, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        if (shutdownAction != null && shutdownAction.length() > 0) {
            final boolean reboot = shutdownAction.charAt(0) == '1';
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length());
            } else {
                reason = null;
            }
            if (reason != null && reason.startsWith("recovery-update")) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }
                    if (filename != null && filename.startsWith("/data") && !new File(BLOCK_MAP_FILE).exists()) {
                        Slog.e(TAG, "Can't find block map file, uncrypt failed or unexpected runtime restart?");
                        return;
                    }
                }
            }
            Runnable runnable = new Runnable() { // from class: com.android.server.SystemServer.1
                @Override // java.lang.Runnable
                public void run() {
                    synchronized (this) {
                        ShutdownThread.rebootOrShutdown(null, reboot, reason);
                    }
                }
            };
            Message msg = Message.obtain(UiThread.getHandler(), runnable);
            msg.setAsynchronous(true);
            UiThread.getHandler().sendMessage(msg);
        }
    }

    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        this.mSystemContext = activityThread.getSystemContext();
        this.mSystemContext.setTheme(DEFAULT_SYSTEM_THEME);
        activityThread.getSystemUiContext().setTheme(DEFAULT_SYSTEM_THEME);
    }

    /* JADX WARN: Finally extract failed */
    private void startBootstrapServices() {
        Slog.i(TAG, "Reading configuration...");
        traceBeginAndSlog("ReadingSystemConfig");
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.-$$Lambda$YWiwiKm_Qgqb55C6tTuq_n2JzdY
            @Override // java.lang.Runnable
            public final void run() {
                SystemConfig.getInstance();
            }
        }, "ReadingSystemConfig");
        traceEnd();
        traceBeginAndSlog("StartInstaller");
        Installer installer = (Installer) this.mSystemServiceManager.startService(Installer.class);
        traceEnd();
        traceBeginAndSlog("DeviceIdentifiersPolicyService");
        this.mSystemServiceManager.startService(DeviceIdentifiersPolicyService.class);
        traceEnd();
        traceBeginAndSlog("StartActivityManager");
        this.mActivityManagerService = ((ActivityManagerService.Lifecycle) this.mSystemServiceManager.startService(ActivityManagerService.Lifecycle.class)).getService();
        this.mActivityManagerService.setSystemServiceManager(this.mSystemServiceManager);
        this.mActivityManagerService.setInstaller(installer);
        traceEnd();
        traceBeginAndSlog("StartPowerManager");
        this.mPowerManagerService = (PowerManagerService) this.mSystemServiceManager.startService(PowerManagerService.class);
        traceEnd();
        traceBeginAndSlog("InitPowerManagement");
        this.mActivityManagerService.initPowerManagement();
        traceEnd();
        traceBeginAndSlog("StartRecoverySystemService");
        this.mSystemServiceManager.startService(RecoverySystemService.class);
        traceEnd();
        RescueParty.noteBoot(this.mSystemContext);
        traceBeginAndSlog("StartLightsService");
        this.mSystemServiceManager.startService(LightsService.class);
        traceEnd();
        traceBeginAndSlog("StartSidekickService");
        if (SystemProperties.getBoolean("config.enable_sidekick_graphics", false)) {
            this.mSystemServiceManager.startService(WEAR_SIDEKICK_SERVICE_CLASS);
        }
        traceEnd();
        traceBeginAndSlog("StartDisplayManager");
        this.mDisplayManagerService = (DisplayManagerService) this.mSystemServiceManager.startService(DisplayManagerService.class);
        traceEnd();
        traceBeginAndSlog("WaitForDisplay");
        this.mSystemServiceManager.startBootPhase(100);
        traceEnd();
        String cryptState = SystemProperties.get("vold.decrypt");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            this.mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            this.mOnlyCore = true;
        }
        if (!this.mRuntimeRestart) {
            MetricsLogger.histogram((Context) null, "boot_package_manager_init_start", (int) SystemClock.elapsedRealtime());
        }
        traceBeginAndSlog("StartPackageManagerService");
        this.mPackageManagerService = PackageManagerService.main(this.mSystemContext, installer, this.mFactoryTestMode != 0, this.mOnlyCore);
        this.mFirstBoot = this.mPackageManagerService.isFirstBoot();
        this.mPackageManager = this.mSystemContext.getPackageManager();
        traceEnd();
        if (!this.mRuntimeRestart && !isFirstBootOrUpgrade()) {
            MetricsLogger.histogram((Context) null, "boot_package_manager_init_ready", (int) SystemClock.elapsedRealtime());
        }
        if (!this.mOnlyCore) {
            boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt", false);
            if (!disableOtaDexopt) {
                traceBeginAndSlog("StartOtaDexOptService");
                try {
                    OtaDexoptService.main(this.mSystemContext, this.mPackageManagerService);
                } finally {
                    try {
                        traceEnd();
                    } catch (Throwable th) {
                    }
                }
                traceEnd();
            }
        }
        traceBeginAndSlog("StartUserManagerService");
        this.mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        traceEnd();
        traceBeginAndSlog("InitAttributerCache");
        AttributeCache.init(this.mSystemContext);
        traceEnd();
        traceBeginAndSlog("SetSystemProcess");
        this.mActivityManagerService.setSystemProcess();
        traceEnd();
        this.mDisplayManagerService.setupSchedulerPolicies();
        traceBeginAndSlog("StartOverlayManagerService");
        OverlayManagerService overlayManagerService = new OverlayManagerService(this.mSystemContext, installer);
        this.mSystemServiceManager.startService(overlayManagerService);
        traceEnd();
        if (SystemProperties.getInt("persist.sys.displayinset.top", 0) > 0) {
            overlayManagerService.updateSystemUiContext();
            ((DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class)).onOverlayChanged();
        }
        this.mSensorServiceStart = SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.-$$Lambda$SystemServer$UyrPns7R814g-ZEylCbDKhe8It4
            @Override // java.lang.Runnable
            public final void run() {
                SystemServer.lambda$startBootstrapServices$0();
            }
        }, START_SENSOR_SERVICE);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startBootstrapServices$0() {
        TimingsTraceLog traceLog = new TimingsTraceLog(SYSTEM_SERVER_TIMING_ASYNC_TAG, 524288L);
        traceLog.traceBegin(START_SENSOR_SERVICE);
        startSensorService();
        traceLog.traceEnd();
    }

    private void startCoreServices() {
        traceBeginAndSlog("StartXpBatteryService");
        this.mSystemServiceManager.startService(XpBatteryService.class);
        traceEnd();
        traceBeginAndSlog("StartUsageService");
        this.mSystemServiceManager.startService(UsageStatsService.class);
        this.mActivityManagerService.setUsageStatsManager((UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class));
        traceEnd();
        if (this.mPackageManager.hasSystemFeature("android.software.webview")) {
            traceBeginAndSlog("StartWebViewUpdateService");
            this.mWebViewUpdateService = (WebViewUpdateService) this.mSystemServiceManager.startService(WebViewUpdateService.class);
            traceEnd();
        }
        traceBeginAndSlog("StartBinderCallsStatsService");
        BinderCallsStatsService.start();
        traceEnd();
    }

    /* JADX WARN: Can't wrap try/catch for region: R(150:458|459|115|116|117|118|(0)|121|122|123|124|125|126|127|(0)|144|(1:146)|447|148|149|150|151|(2:152|153)|154|155|156|157|158|159|160|(0)|169|170|171|172|173|174|175|176|177|178|(0)|187|(0)|190|(0)|(0)|195|196|197|198|(0)|201|(0)|422|(0)(0)|215|216|217|218|(3:219|220|221)|222|(0)|225|(0)|228|(0)|408|232|(0)|235|236|237|238|(0)|404|243|(0)|(0)|261|262|263|264|(3:265|266|267)|268|269|270|271|(0)|274|(0)|277|(0)|280|(0)|283|(0)|394|287|(0)|290|(0)|293|294|295|296|297|298|299|300|(0)|303|304|305|306|307|308|309|(0)|(0)|(0)|(0)|321|(0)|324|(0)(0)|327|(0)|330|331|332|(0)|334|335|336|337|(0)|340|(0)|343|344|345|346|347|348|349|(0)|361|362|363) */
    /* JADX WARN: Code restructure failed: missing block: B:363:0x0a65, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:367:0x0a6a, code lost:
        r49 = r5;
        reportWtf("starting MediaRouterService", r0);
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:102:0x0384  */
    /* JADX WARN: Removed duplicated region for block: B:115:0x03b2  */
    /* JADX WARN: Removed duplicated region for block: B:152:0x04a8  */
    /* JADX WARN: Removed duplicated region for block: B:165:0x0503  */
    /* JADX WARN: Removed duplicated region for block: B:207:0x0620  */
    /* JADX WARN: Removed duplicated region for block: B:230:0x06bb  */
    /* JADX WARN: Removed duplicated region for block: B:238:0x06df  */
    /* JADX WARN: Removed duplicated region for block: B:241:0x0707  */
    /* JADX WARN: Removed duplicated region for block: B:243:0x0718  */
    /* JADX WARN: Removed duplicated region for block: B:251:0x0749  */
    /* JADX WARN: Removed duplicated region for block: B:254:0x0762  */
    /* JADX WARN: Removed duplicated region for block: B:259:0x077f  */
    /* JADX WARN: Removed duplicated region for block: B:270:0x07ac  */
    /* JADX WARN: Removed duplicated region for block: B:283:0x07ea  */
    /* JADX WARN: Removed duplicated region for block: B:286:0x0830  */
    /* JADX WARN: Removed duplicated region for block: B:289:0x0849  */
    /* JADX WARN: Removed duplicated region for block: B:294:0x087e  */
    /* JADX WARN: Removed duplicated region for block: B:302:0x08c9  */
    /* JADX WARN: Removed duplicated region for block: B:308:0x08df  */
    /* JADX WARN: Removed duplicated region for block: B:310:0x08f0  */
    /* JADX WARN: Removed duplicated region for block: B:337:0x0971  */
    /* JADX WARN: Removed duplicated region for block: B:340:0x098d  */
    /* JADX WARN: Removed duplicated region for block: B:343:0x09a6  */
    /* JADX WARN: Removed duplicated region for block: B:346:0x09ec  */
    /* JADX WARN: Removed duplicated region for block: B:349:0x0a05  */
    /* JADX WARN: Removed duplicated region for block: B:354:0x0a28  */
    /* JADX WARN: Removed duplicated region for block: B:357:0x0a41  */
    /* JADX WARN: Removed duplicated region for block: B:370:0x0a7f  */
    /* JADX WARN: Removed duplicated region for block: B:382:0x0afd  */
    /* JADX WARN: Removed duplicated region for block: B:384:0x0b1f  */
    /* JADX WARN: Removed duplicated region for block: B:386:0x0b30  */
    /* JADX WARN: Removed duplicated region for block: B:391:0x0b86  */
    /* JADX WARN: Removed duplicated region for block: B:393:0x0b97  */
    /* JADX WARN: Removed duplicated region for block: B:396:0x0bb2  */
    /* JADX WARN: Removed duplicated region for block: B:399:0x0bd6  */
    /* JADX WARN: Removed duplicated region for block: B:400:0x0beb  */
    /* JADX WARN: Removed duplicated region for block: B:403:0x0c04  */
    /* JADX WARN: Removed duplicated region for block: B:421:0x0c73  */
    /* JADX WARN: Removed duplicated region for block: B:424:0x0ca5  */
    /* JADX WARN: Removed duplicated region for block: B:437:0x0d01  */
    /* JADX WARN: Removed duplicated region for block: B:501:0x0c30 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:74:0x02e5  */
    /* JADX WARN: Removed duplicated region for block: B:87:0x0326  */
    /* JADX WARN: Type inference failed for: r0v275, types: [com.android.server.HardwarePropertiesManagerService] */
    /* JADX WARN: Type inference failed for: r0v281, types: [com.android.server.SerialService] */
    /* JADX WARN: Type inference failed for: r0v307, types: [com.android.server.NsdService] */
    /* JADX WARN: Type inference failed for: r0v349, types: [com.android.server.statusbar.StatusBarManagerService] */
    /* JADX WARN: Type inference failed for: r10v7, types: [com.android.server.LocationManagerService] */
    /* JADX WARN: Type inference failed for: r11v2, types: [com.android.server.CountryDetectorService] */
    /* JADX WARN: Type inference failed for: r13v4, types: [android.net.INetworkPolicyManager, com.android.server.net.NetworkPolicyManagerService] */
    /* JADX WARN: Type inference failed for: r13v6 */
    /* JADX WARN: Type inference failed for: r13v7 */
    /* JADX WARN: Type inference failed for: r4v12, types: [android.os.IBinder, com.android.server.wm.WindowManagerService] */
    /* JADX WARN: Type inference failed for: r5v100, types: [android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r5v157, types: [com.android.server.input.InputManagerService, android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r5v164, types: [com.android.server.am.ActivityManagerService] */
    /* JADX WARN: Type inference failed for: r5v88 */
    /* JADX WARN: Type inference failed for: r5v89 */
    /* JADX WARN: Type inference failed for: r6v30, types: [com.android.server.ConsumerIrService] */
    /* JADX WARN: Type inference failed for: r7v31, types: [com.xiaopeng.server.aftersales.AfterSalesService, android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r7v9, types: [com.android.server.IpSecService] */
    /* JADX WARN: Type inference failed for: r9v2, types: [com.android.server.ConnectivityService] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void startOtherServices() {
        /*
            Method dump skipped, instructions count: 3482
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.SystemServer.startOtherServices():void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startOtherServices$1() {
        try {
            Slog.i(TAG, "SecondaryZygotePreload");
            TimingsTraceLog traceLog = new TimingsTraceLog(SYSTEM_SERVER_TIMING_ASYNC_TAG, 524288L);
            traceLog.traceBegin("SecondaryZygotePreload");
            if (!Process.zygoteProcess.preloadDefault(Build.SUPPORTED_32_BIT_ABIS[0])) {
                Slog.e(TAG, "Unable to preload default resources");
            }
            traceLog.traceEnd();
        } catch (Exception ex) {
            Slog.e(TAG, "Exception preloading default resources", ex);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startOtherServices$2() {
        TimingsTraceLog traceLog = new TimingsTraceLog(SYSTEM_SERVER_TIMING_ASYNC_TAG, 524288L);
        traceLog.traceBegin(START_HIDL_SERVICES);
        startHidlServices();
        traceLog.traceEnd();
    }

    public static /* synthetic */ void lambda$startOtherServices$4(final SystemServer systemServer, Context context, WindowManagerService windowManagerF, NetworkManagementService networkManagementF, NetworkPolicyManagerService networkPolicyF, IpSecService ipSecServiceF, NetworkStatsService networkStatsF, ConnectivityService connectivityF, LocationManagerService locationF, CountryDetectorService countryDetectorF, NetworkTimeUpdateService networkTimeUpdaterF, CommonTimeManagementService commonTimeMgmtServiceF, InputManagerService inputManagerF, MediaRouterService mediaRouterF, AfterSalesService afterSalesServiceF) {
        Slog.i(TAG, "Making services ready");
        traceBeginAndSlog("StartActivityManagerReadyPhase");
        systemServer.mSystemServiceManager.startBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        traceEnd();
        traceBeginAndSlog("StartObservingNativeCrashes");
        try {
            systemServer.mActivityManagerService.startObservingNativeCrashes();
        } catch (Throwable e) {
            systemServer.reportWtf("observing native crashes", e);
        }
        traceEnd();
        Future<?> webviewPrep = null;
        if (!systemServer.mOnlyCore && systemServer.mWebViewUpdateService != null) {
            webviewPrep = SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.-$$Lambda$SystemServer$Y1gEdKr_Hb7K7cbTDAo_WOJ-SYI
                @Override // java.lang.Runnable
                public final void run() {
                    SystemServer.lambda$startOtherServices$3(SystemServer.this);
                }
            }, "WebViewFactoryPreparation");
        }
        Future<?> webviewPrep2 = webviewPrep;
        if (systemServer.mPackageManager.hasSystemFeature("android.hardware.type.automotive")) {
            traceBeginAndSlog("StartCarServiceHelperService");
            systemServer.mSystemServiceManager.startService(CAR_SERVICE_HELPER_SERVICE_CLASS);
            traceEnd();
        }
        traceBeginAndSlog("StartSystemUI");
        addBootEvent("SystemServer: StartSystemUI begin");
        try {
            startSystemUi(context, windowManagerF);
        } catch (Throwable e2) {
            systemServer.reportWtf("starting System UI", e2);
        }
        addBootEvent("SystemServer: StartSystemUI end");
        traceEnd();
        traceBeginAndSlog("MakeNetworkManagementServiceReady");
        if (networkManagementF != null) {
            try {
                networkManagementF.systemReady();
            } catch (Throwable e3) {
                systemServer.reportWtf("making Network Managment Service ready", e3);
            }
        }
        CountDownLatch networkPolicyInitReadySignal = null;
        if (networkPolicyF != null) {
            networkPolicyInitReadySignal = networkPolicyF.networkScoreAndNetworkManagementServiceReady();
        }
        CountDownLatch networkPolicyInitReadySignal2 = networkPolicyInitReadySignal;
        traceEnd();
        traceBeginAndSlog("MakeIpSecServiceReady");
        if (ipSecServiceF != null) {
            try {
                ipSecServiceF.systemReady();
            } catch (Throwable e4) {
                systemServer.reportWtf("making IpSec Service ready", e4);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeNetworkStatsServiceReady");
        if (networkStatsF != null) {
            try {
                networkStatsF.systemReady();
            } catch (Throwable e5) {
                systemServer.reportWtf("making Network Stats Service ready", e5);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeConnectivityServiceReady");
        if (connectivityF != null) {
            try {
                connectivityF.systemReady();
            } catch (Throwable e6) {
                systemServer.reportWtf("making Connectivity Service ready", e6);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeNetworkPolicyServiceReady");
        if (networkPolicyF != null) {
            try {
                networkPolicyF.systemReady(networkPolicyInitReadySignal2);
            } catch (Throwable e7) {
                systemServer.reportWtf("making Network Policy Service ready", e7);
            }
        }
        traceEnd();
        traceBeginAndSlog("StartWatchdog");
        Watchdog.getInstance().start();
        traceEnd();
        systemServer.mPackageManagerService.waitForAppDataPrepared();
        traceBeginAndSlog("PhaseThirdPartyAppsCanStart");
        addBootEvent("SystemServer: PhaseThirdPartyAppsCanStart begin");
        if (webviewPrep2 != null) {
            ConcurrentUtils.waitForFutureNoInterrupt(webviewPrep2, "WebViewFactoryPreparation");
        }
        systemServer.mSystemServiceManager.startBootPhase(600);
        addBootEvent("SystemServer: PhaseThirdPartyAppsCanStart end");
        traceEnd();
        traceBeginAndSlog("MakeLocationServiceReady");
        if (locationF != null) {
            try {
                locationF.systemRunning();
            } catch (Throwable e8) {
                systemServer.reportWtf("Notifying Location Service running", e8);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeCountryDetectionServiceReady");
        if (countryDetectorF != null) {
            try {
                countryDetectorF.systemRunning();
            } catch (Throwable e9) {
                systemServer.reportWtf("Notifying CountryDetectorService running", e9);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeNetworkTimeUpdateReady");
        if (networkTimeUpdaterF != null) {
            try {
                networkTimeUpdaterF.systemRunning();
            } catch (Throwable e10) {
                systemServer.reportWtf("Notifying NetworkTimeService running", e10);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeCommonTimeManagementServiceReady");
        if (commonTimeMgmtServiceF != null) {
            try {
                commonTimeMgmtServiceF.systemRunning();
            } catch (Throwable e11) {
                systemServer.reportWtf("Notifying CommonTimeManagementService running", e11);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeInputManagerServiceReady");
        if (inputManagerF != null) {
            try {
                inputManagerF.systemRunning();
            } catch (Throwable e12) {
                systemServer.reportWtf("Notifying InputManagerService running", e12);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeMediaRouterServiceReady");
        if (mediaRouterF != null) {
            try {
                mediaRouterF.systemRunning();
            } catch (Throwable e13) {
                systemServer.reportWtf("Notifying MediaRouterService running", e13);
            }
        }
        traceEnd();
        traceBeginAndSlog("MakeAfterSalesServiceReady");
        if (afterSalesServiceF != null) {
            try {
                afterSalesServiceF.systemRunning();
            } catch (Throwable e14) {
                systemServer.reportWtf("Notifying AfterSalesService running", e14);
            }
        }
        traceEnd();
        traceBeginAndSlog("IncidentDaemonReady");
        try {
            IIncidentManager incident = IIncidentManager.Stub.asInterface(ServiceManager.getService("incident"));
            if (incident != null) {
                incident.systemRunning();
            }
        } catch (Throwable e15) {
            systemServer.reportWtf("Notifying incident daemon running", e15);
        }
        traceEnd();
    }

    public static /* synthetic */ void lambda$startOtherServices$3(SystemServer systemServer) {
        Slog.i(TAG, "WebViewFactoryPreparation");
        TimingsTraceLog traceLog = new TimingsTraceLog(SYSTEM_SERVER_TIMING_ASYNC_TAG, 524288L);
        traceLog.traceBegin("WebViewFactoryPreparation");
        ConcurrentUtils.waitForFutureNoInterrupt(systemServer.mZygotePreload, "Zygote preload");
        systemServer.mZygotePreload = null;
        systemServer.mWebViewUpdateService.prepareWebViewInSystemServer();
        traceLog.traceEnd();
    }

    static final void startSystemUi(Context context, WindowManagerService windowManager) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(AfterSalesService.PackgeName.SYSTEMUI, "com.android.systemui.SystemUIService"));
        intent.addFlags(256);
        context.startServiceAsUser(intent, UserHandle.SYSTEM);
        windowManager.onSystemUiStarted();
    }

    private static void traceBeginAndSlog(String name) {
        Slog.i(TAG, name);
        BOOT_TIMINGS_TRACE_LOG.traceBegin(name);
    }

    private static void traceEnd() {
        BOOT_TIMINGS_TRACE_LOG.traceEnd();
    }
}
