package com.android.server;

import android.app.IActivityController;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.Binder;
import android.os.Build;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructRlimit;
import android.text.TextUtils;
import android.util.Slog;
import com.android.server.am.ActivityManagerService;
import com.android.server.backup.BackupManagerConstants;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
/* loaded from: classes.dex */
public class Watchdog extends Thread {
    private static final String AFE_TIMEOUT_STATUS = "/sys/q6afe_detect/afe_get_timeout_status";
    static final long CHECK_INTERVAL = 30000;
    static final int COMPLETED = 0;
    static final boolean DB = false;
    static final long DEFAULT_TIMEOUT = 60000;
    private static final String KEY_XP_AFE_COUNT = "persist.xp.afe.count";
    private static final long MAX_CHECK_AFE_TIMEOUT = 300000;
    private static final int MAX_COUNT_AFE = 10;
    private static final int MESSAGE_AFE_CHECK_ERROR = 1;
    private static final int MESSAGE_AFE_CHECK_OVER = 2;
    static final int OVERDUE = 3;
    static final String TAG = "Watchdog";
    static final int WAITED_HALF = 2;
    static final int WAITING = 1;
    static Watchdog sWatchdog;
    ActivityManagerService mActivity;
    private Runnable mAfeStatusRunnable;
    boolean mAllowRestart;
    IActivityController mController;
    private FileObserver mFileObserver;
    final ArrayList<HandlerChecker> mHandlerCheckers;
    final HandlerChecker mMonitorChecker;
    final OpenFdMonitor mOpenFdMonitor;
    int mPhonePid;
    ContentResolver mResolver;
    private Handler mWorkHandler;
    public static final List<String> HAL_INTERFACES_OF_INTEREST = Arrays.asList("android.hardware.audio@2.0::IDevicesFactory", "android.hardware.audio@4.0::IDevicesFactory", "android.hardware.bluetooth@1.0::IBluetoothHci", "android.hardware.camera.provider@2.4::ICameraProvider", "android.hardware.graphics.composer@2.1::IComposer", "android.hardware.media.omx@1.0::IOmx", "android.hardware.media.omx@1.0::IOmxStore", "android.hardware.sensors@1.0::ISensors", "android.hardware.vr@1.0::IVr");
    public static final String[] NATIVE_STACKS_OF_INTEREST = {"/system/bin/audioserver", "/system/bin/cameraserver", "/system/bin/drmserver", "/system/bin/mediadrmserver", "/system/bin/mediaserver", "/system/bin/sdcard", "/system/bin/surfaceflinger", "media.extractor", "media.metrics", "media.codec", "com.android.bluetooth", "statsd", "/system/bin/netd"};
    public static final String[] APP_STACKS_OF_INTEREST = {"com.android.keychain"};

    /* loaded from: classes.dex */
    public interface Monitor {
        void monitor();
    }

    /* loaded from: classes.dex */
    public final class HandlerChecker implements Runnable {
        private Monitor mCurrentMonitor;
        private final Handler mHandler;
        private final String mName;
        private long mStartTime;
        private final long mWaitMax;
        private final ArrayList<Monitor> mMonitors = new ArrayList<>();
        private boolean mCompleted = true;

        HandlerChecker(Handler handler, String name, long waitMaxMillis) {
            this.mHandler = handler;
            this.mName = name;
            this.mWaitMax = waitMaxMillis;
        }

        public void addMonitor(Monitor monitor) {
            this.mMonitors.add(monitor);
        }

        public void scheduleCheckLocked() {
            if (this.mMonitors.size() == 0 && this.mHandler.getLooper().getQueue().isPolling()) {
                this.mCompleted = true;
            } else if (!this.mCompleted) {
            } else {
                this.mCompleted = false;
                this.mCurrentMonitor = null;
                this.mStartTime = SystemClock.uptimeMillis();
                this.mHandler.postAtFrontOfQueue(this);
            }
        }

        public boolean isOverdueLocked() {
            return !this.mCompleted && SystemClock.uptimeMillis() > this.mStartTime + this.mWaitMax;
        }

        public int getCompletionStateLocked() {
            if (this.mCompleted) {
                return 0;
            }
            long latency = SystemClock.uptimeMillis() - this.mStartTime;
            if (latency < this.mWaitMax / 2) {
                return 1;
            }
            if (latency < this.mWaitMax) {
                return 2;
            }
            return 3;
        }

        public Thread getThread() {
            return this.mHandler.getLooper().getThread();
        }

        public String getName() {
            return this.mName;
        }

        public String describeBlockedStateLocked() {
            if (this.mCurrentMonitor == null) {
                return "Blocked in handler on " + this.mName + " (" + getThread().getName() + ")";
            }
            return "Blocked in monitor " + this.mCurrentMonitor.getClass().getName() + " on " + this.mName + " (" + getThread().getName() + ")";
        }

        @Override // java.lang.Runnable
        public void run() {
            int size = this.mMonitors.size();
            for (int i = 0; i < size; i++) {
                synchronized (Watchdog.this) {
                    this.mCurrentMonitor = this.mMonitors.get(i);
                }
                this.mCurrentMonitor.monitor();
            }
            synchronized (Watchdog.this) {
                this.mCompleted = true;
                this.mCurrentMonitor = null;
            }
        }
    }

    /* loaded from: classes.dex */
    final class RebootRequestReceiver extends BroadcastReceiver {
        RebootRequestReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context c, Intent intent) {
            if (intent.getIntExtra("nowait", 0) != 0) {
                Watchdog.this.rebootSystem("Received ACTION_REBOOT broadcast");
                return;
            }
            Slog.w(Watchdog.TAG, "Unsupported ACTION_REBOOT broadcast: " + intent);
        }
    }

    /* loaded from: classes.dex */
    private static final class BinderThreadMonitor implements Monitor {
        private BinderThreadMonitor() {
        }

        @Override // com.android.server.Watchdog.Monitor
        public void monitor() {
            Binder.blockUntilThreadAvailable();
        }
    }

    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }
        return sWatchdog;
    }

    private Watchdog() {
        super("watchdog");
        this.mHandlerCheckers = new ArrayList<>();
        this.mAllowRestart = true;
        this.mAfeStatusRunnable = new Runnable() { // from class: com.android.server.Watchdog.2
            @Override // java.lang.Runnable
            public void run() {
                Watchdog.this.mFileObserver = new AfeFileObserver(Watchdog.AFE_TIMEOUT_STATUS, Watchdog.this.mWorkHandler);
                Watchdog.this.mFileObserver.startWatching();
            }
        };
        this.mMonitorChecker = new HandlerChecker(FgThread.getHandler(), "foreground thread", 60000L);
        this.mHandlerCheckers.add(this.mMonitorChecker);
        this.mHandlerCheckers.add(new HandlerChecker(new Handler(Looper.getMainLooper()), "main thread", 60000L));
        this.mHandlerCheckers.add(new HandlerChecker(UiThread.getHandler(), "ui thread", 60000L));
        this.mHandlerCheckers.add(new HandlerChecker(IoThread.getHandler(), "i/o thread", 60000L));
        this.mHandlerCheckers.add(new HandlerChecker(DisplayThread.getHandler(), "display thread", 60000L));
        addMonitor(new BinderThreadMonitor());
        this.mOpenFdMonitor = OpenFdMonitor.create();
        HandlerThread handlerThread = new HandlerThread("workThread");
        handlerThread.start();
        this.mWorkHandler = new Handler(handlerThread.getLooper()) { // from class: com.android.server.Watchdog.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case 1:
                        Watchdog.this.checkAfeStatus(false);
                        return;
                    case 2:
                        Slog.i(Watchdog.TAG, "release observer");
                        Watchdog.this.mFileObserver.stopWatching();
                        Watchdog.this.mFileObserver = null;
                        Watchdog.this.checkAfeStatus(true);
                        getLooper().quitSafely();
                        Watchdog.this.mWorkHandler = null;
                        return;
                    default:
                        return;
                }
            }
        };
    }

    /* loaded from: classes.dex */
    private static class AfeFileObserver extends FileObserver {
        WeakReference<Handler> mWeakHandler;

        public AfeFileObserver(String file, Handler handler) {
            super(file, 2);
            this.mWeakHandler = new WeakReference<>(handler);
        }

        @Override // android.os.FileObserver
        public void onEvent(int event, String path) {
            Slog.i(Watchdog.TAG, "path: " + path + ", event: " + event);
            if (!TextUtils.isEmpty(path) && Watchdog.AFE_TIMEOUT_STATUS.contains(path) && (event & 2) != 0 && this.mWeakHandler.get() != null) {
                this.mWeakHandler.get().sendEmptyMessage(1);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkAfeStatus(boolean success) {
        try {
            String status = FileUtils.readTextFile(new File(AFE_TIMEOUT_STATUS), 2, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS).trim();
            int node = TextUtils.isEmpty(status) ? 0 : Integer.decode(status).intValue();
            int preCount = SystemProperties.getInt(KEY_XP_AFE_COUNT, 0);
            Slog.i(TAG, "checkAfeStatus node: " + node + ", preCount: " + preCount + ", success: " + success);
            if (node >= 2 && !success) {
                SystemProperties.set(KEY_XP_AFE_COUNT, "1");
                if (preCount == 0) {
                    rebootSystem("afe error reboot");
                }
            } else if (node >= 2 || !success || preCount == 0) {
            } else {
                if (preCount < 10) {
                    SystemProperties.set(KEY_XP_AFE_COUNT, String.valueOf(preCount + 1));
                } else {
                    SystemProperties.set(KEY_XP_AFE_COUNT, String.valueOf(0));
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "checkAfeStatus error: " + e.getMessage());
        }
    }

    public void init(Context context, ActivityManagerService activity) {
        this.mResolver = context.getContentResolver();
        this.mActivity = activity;
        context.registerReceiver(new RebootRequestReceiver(), new IntentFilter("android.intent.action.REBOOT"), "android.permission.REBOOT", null);
    }

    public void initAfeCheck() {
        this.mWorkHandler.post(this.mAfeStatusRunnable);
        this.mWorkHandler.sendEmptyMessage(1);
        this.mWorkHandler.sendEmptyMessageDelayed(2, 300000L);
    }

    public void processStarted(String name, int pid) {
        synchronized (this) {
            if ("com.android.phone".equals(name)) {
                this.mPhonePid = pid;
            }
        }
    }

    public void setActivityController(IActivityController controller) {
        synchronized (this) {
            this.mController = controller;
        }
    }

    public void setAllowRestart(boolean allowRestart) {
        synchronized (this) {
            this.mAllowRestart = allowRestart;
        }
    }

    public void addMonitor(Monitor monitor) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Monitors can't be added once the Watchdog is running");
            }
            this.mMonitorChecker.addMonitor(monitor);
        }
    }

    public void addThread(Handler thread) {
        addThread(thread, 60000L);
    }

    public void addThread(Handler thread, long timeoutMillis) {
        synchronized (this) {
            if (isAlive()) {
                throw new RuntimeException("Threads can't be added once the Watchdog is running");
            }
            String name = thread.getLooper().getThread().getName();
            this.mHandlerCheckers.add(new HandlerChecker(thread, name, timeoutMillis));
        }
    }

    void rebootSystem(String reason) {
        Slog.i(TAG, "Rebooting system because: " + reason);
        IPowerManager pms = ServiceManager.getService("power");
        try {
            pms.reboot(false, reason, false);
        } catch (RemoteException e) {
        }
    }

    private int evaluateCheckerCompletionLocked() {
        int state = 0;
        for (int i = 0; i < this.mHandlerCheckers.size(); i++) {
            HandlerChecker hc = this.mHandlerCheckers.get(i);
            state = Math.max(state, hc.getCompletionStateLocked());
        }
        return state;
    }

    private ArrayList<HandlerChecker> getBlockedCheckersLocked() {
        ArrayList<HandlerChecker> checkers = new ArrayList<>();
        for (int i = 0; i < this.mHandlerCheckers.size(); i++) {
            HandlerChecker hc = this.mHandlerCheckers.get(i);
            if (hc.isOverdueLocked()) {
                checkers.add(hc);
            }
        }
        return checkers;
    }

    private String describeCheckersLocked(List<HandlerChecker> checkers) {
        StringBuilder builder = new StringBuilder(128);
        for (int i = 0; i < checkers.size(); i++) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(checkers.get(i).describeBlockedStateLocked());
        }
        return builder.toString();
    }

    private ArrayList<Integer> getInterestingHalPids() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            ArrayList<IServiceManager.InstanceDebugInfo> dump = serviceManager.debugDump();
            HashSet<Integer> pids = new HashSet<>();
            Iterator<IServiceManager.InstanceDebugInfo> it = dump.iterator();
            while (it.hasNext()) {
                IServiceManager.InstanceDebugInfo info = it.next();
                if (info.pid != -1 && HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName)) {
                    pids.add(Integer.valueOf(info.pid));
                }
            }
            return new ArrayList<>(pids);
        } catch (RemoteException e) {
            return new ArrayList<>();
        }
    }

    private ArrayList<Integer> getInterestingNativePids() {
        ArrayList<Integer> pids = getInterestingHalPids();
        int[] nativePids = Process.getPidsForCommands(NATIVE_STACKS_OF_INTEREST);
        if (nativePids != null) {
            pids.ensureCapacity(pids.size() + nativePids.length);
            for (int i : nativePids) {
                pids.add(Integer.valueOf(i));
            }
        }
        return pids;
    }

    private int[] getInterstingAppPids() {
        return Process.getPidsForCommands(APP_STACKS_OF_INTEREST);
    }

    /* JADX WARN: Can't wrap try/catch for region: R(28:4|(3:8|5|6)|9|(1:11)|12|(8:15|(1:17)|18|19|20|(2:22|23)(1:25)|24|13)|30|31|(1:33)|34|(4:36|(3:104|105|106)(1:(3:101|102|103)(1:(4:96|(1:98)|99|100)(1:40)))|83|84)(1:107)|41|42|43|(1:45)|46|(3:48|(1:50)|51)|(1:53)(1:95)|54|55|56|123|(4:62|63|64|(5:80|81|82|83|84)(1:66))(1:87)|67|(1:69)|(1:71)(1:(1:76)(1:(1:78)(1:79)))|72|73) */
    /* JADX WARN: Multi-variable type inference failed */
    @Override // java.lang.Thread, java.lang.Runnable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void run() {
        /*
            Method dump skipped, instructions count: 418
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.Watchdog.run():void");
    }

    private void doSysRq(char c) {
        try {
            FileWriter sysrq_trigger = new FileWriter("/proc/sysrq-trigger");
            sysrq_trigger.write(c);
            sysrq_trigger.close();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write to /proc/sysrq-trigger", e);
        }
    }

    /* loaded from: classes.dex */
    public static final class OpenFdMonitor {
        private static final int FD_HIGH_WATER_MARK = 12;
        private final File mDumpDir;
        private final File mFdHighWaterMark;

        public static OpenFdMonitor create() {
            if (Build.IS_DEBUGGABLE) {
                String dumpDirStr = SystemProperties.get("dalvik.vm.stack-trace-dir", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                if (dumpDirStr.isEmpty()) {
                    return null;
                }
                try {
                    StructRlimit rlimit = Os.getrlimit(OsConstants.RLIMIT_NOFILE);
                    File fdThreshold = new File("/proc/self/fd/" + (rlimit.rlim_cur - 12));
                    return new OpenFdMonitor(new File(dumpDirStr), fdThreshold);
                } catch (ErrnoException errno) {
                    Slog.w(Watchdog.TAG, "Error thrown from getrlimit(RLIMIT_NOFILE)", errno);
                    return null;
                }
            }
            return null;
        }

        OpenFdMonitor(File dumpDir, File fdThreshold) {
            this.mDumpDir = dumpDir;
            this.mFdHighWaterMark = fdThreshold;
        }

        private void dumpOpenDescriptors() {
            try {
                File dumpFile = File.createTempFile("anr_fd_", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, this.mDumpDir);
                Process proc = new ProcessBuilder(new String[0]).command("/system/bin/lsof", "-p", String.valueOf(Process.myPid())).redirectErrorStream(true).redirectOutput(dumpFile).start();
                int returnCode = proc.waitFor();
                if (returnCode != 0) {
                    Slog.w(Watchdog.TAG, "Unable to dump open descriptors, lsof return code: " + returnCode);
                    dumpFile.delete();
                }
            } catch (IOException | InterruptedException ex) {
                Slog.w(Watchdog.TAG, "Unable to dump open descriptors: " + ex);
            }
        }

        public boolean monitor() {
            if (this.mFdHighWaterMark.exists()) {
                dumpOpenDescriptors();
                return true;
            }
            return false;
        }
    }
}
