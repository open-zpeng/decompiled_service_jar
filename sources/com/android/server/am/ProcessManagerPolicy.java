package com.android.server.am;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.display.DisplayTransformManager;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.PackageManagerService;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpSysConfigUtil;
import com.xiaopeng.util.xpTextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class ProcessManagerPolicy {
    private static final String ACTION_POWER_STATE_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED";
    private static final boolean DEBUG_HIGH_LOADING;
    private static final boolean DEBUG_PAGE_LEAVING;
    private static final boolean DEBUG_USER_LEAVING;
    public static final int DEFAULT_MAX_CACHED_PROCESSES = 16;
    public static final int DEFAULT_MAX_KILL_RECORD = 10;
    public static final String[] FOREGROUND_ADJ_TYPES;
    public static final long MINUTE = 60000;
    public static final long PROCESS_ADJ_INTERVAL = 100;
    public static final String[] PROTECT_ADJ_TYPES;
    private static final String TAG = "ProcessManagerPolicy";
    private static volatile HashMap<String, Integer> sBackgroundSettings;
    private static boolean sForceStoppingFromPower;
    private static ProcessManagerPolicy sPolicy;
    private static volatile ProcessPolicyInfo sPolicyInfo;
    private static volatile ProcessStateRecord sStateRecord;
    private final WorkHandler mBgHandler;
    private Context mContext;
    private ActivityManagerService mService;
    private static final boolean TRACE = SystemProperties.getBoolean("persist.xp.am.process.trace", false);
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.xp.am.process.logger", false);
    private static final boolean DEBUG_ALL = SystemProperties.getBoolean("persist.xp.am.process.logger.all", false);
    private final Handler mUiHandler = new Handler() { // from class: com.android.server.am.ProcessManagerPolicy.6
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    };
    private BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.am.ProcessManagerPolicy.7
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "onReceive action=" + intent.getAction());
            if (ProcessManagerPolicy.ACTION_POWER_STATE_CHANGED.equals(intent.getAction())) {
                int igState = intent.getIntExtra("android.intent.extra.IG_STATE", -1);
                int powerState = intent.getIntExtra("android.intent.extra.POWER_STATE", -1);
                int bootReason = intent.getIntExtra("android.intent.extra.BOOT_REASON", -1);
                boolean forceStop = intent.getBooleanExtra("android.intent.extra.FORCE_STOP", false);
                boolean stopping = ProcessManagerPolicy.sForceStoppingFromPower;
                ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "onReceive power state igState=" + igState + " powerState=" + powerState + " bootReason=" + bootReason + " forceStop=" + forceStop + " stopping=" + stopping);
                if (forceStop && !stopping) {
                    ProcessManagerPolicy.this.updateProcessPowerPolicy();
                }
            }
        }
    };
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    static {
        boolean z = true;
        DEBUG_USER_LEAVING = DEBUG_ALL || SystemProperties.getBoolean("persist.xp.am.process.user_leaving", false);
        DEBUG_PAGE_LEAVING = DEBUG_ALL || SystemProperties.getBoolean("persist.xp.am.process.page_leaving", false);
        if (!DEBUG_ALL && !SystemProperties.getBoolean("persist.xp.am.process.high_loading", false)) {
            z = false;
        }
        DEBUG_HIGH_LOADING = z;
        PROTECT_ADJ_TYPES = new String[]{"service", "broadcast", "ext-provider"};
        FOREGROUND_ADJ_TYPES = new String[]{"vis-activity", "pause-activity"};
        sForceStoppingFromPower = false;
        sPolicyInfo = new ProcessPolicyInfo();
        sStateRecord = new ProcessStateRecord();
        sBackgroundSettings = new HashMap<>();
        sPolicy = null;
    }

    public ProcessManagerPolicy(Context context) {
        this.mContext = null;
        this.mContext = context;
        this.mHandlerThread.start();
        this.mBgHandler = new WorkHandler(this.mHandlerThread.getLooper());
    }

    public static ProcessManagerPolicy get(Context context) {
        if (sPolicy == null) {
            synchronized (ProcessManagerPolicy.class) {
                if (sPolicy == null) {
                    sPolicy = new ProcessManagerPolicy(context);
                }
            }
        }
        return sPolicy;
    }

    public void init() {
        log(TAG, "init");
        initPolicy();
        registerReceiver();
        scheduleProcessStats(JobStatus.DEFAULT_TRIGGER_MAX_DELAY);
    }

    public void setService(ActivityManagerService service) {
        this.mService = service;
    }

    private void initPolicy() {
        Runnable runnable = new Runnable() { // from class: com.android.server.am.ProcessManagerPolicy.1
            @Override // java.lang.Runnable
            public void run() {
                int oldMask = StrictMode.allowThreadDiskReadsMask();
                ProcessPolicy.loadProcessPolicy();
                StrictMode.setThreadPolicyMask(oldMask);
            }
        };
        this.mBgHandler.postDelayed(runnable, 1000L);
    }

    private void registerReceiver() {
        try {
            log(TAG, "registerReceiver");
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SCREEN_ON");
            filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction(ACTION_POWER_STATE_CHANGED);
            this.mContext.registerReceiver(this.mReceiver, filter);
        } catch (Exception e) {
            log(TAG, "registerReceiver e=" + e);
        }
    }

    private void unregisterReceiver() {
        try {
            this.mContext.unregisterReceiver(this.mReceiver);
        } catch (Exception e) {
            log(TAG, "unregisterReceiver e=" + e);
        }
    }

    protected static void maybeUpdateProcessRecord(ProcessRecord r) {
        if (r != null) {
            long now = SystemClock.uptimeMillis();
            boolean changed = r.lastAdj != r.curAdj;
            if (changed) {
                r.adjTime = now;
                r.lastAdj = r.curAdj;
            }
            boolean background = ProcessStateManager.isBackground(r);
            boolean emptyCached = ProcessStateManager.processEmptyCached(r);
            if (background) {
                if (r.backgroundTime == 0) {
                    r.backgroundTime = now;
                }
                ProcessItemRecord item = sStateRecord.records.get(Integer.valueOf(r.pid));
                if (item != null) {
                    int curAdj = item.adj;
                    int setAdj = item.setAdj;
                    if (curAdj == r.curAdj && setAdj > 100 && setAdj > r.curAdj && setAdj > r.setAdj) {
                        r.setAdj = item.setAdj;
                        log(TAG, "maybeUpdateProcessRecord pid=" + r.pid + " packageName=" + r.processName + " curAdj=" + r.curAdj + " setAdj=" + r.setAdj);
                    }
                }
            } else {
                r.backgroundTime = 0L;
                if (r.foregroundTime == 0) {
                    r.foregroundTime = now;
                }
            }
            if (emptyCached) {
                if (r.cachedEmptyTime == 0) {
                    r.cachedEmptyTime = now;
                    return;
                }
                return;
            }
            r.cachedEmptyTime = 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public static void updateProcessPolicyIfNeed(Context context, final ProcessRecord r, ActivityManagerService am) {
        if (context == null || r == null || am == null || r.info == null || r.killed || r.killedByAm) {
            return;
        }
        maybeUpdateProcessRecord(r);
        try {
            boolean shouldKill = ProcessStateManager.shouldKillWhenPageLeaving(r, am.mLruProcesses);
            if (shouldKill) {
                am.mHandler.post(new Runnable() { // from class: com.android.server.am.ProcessManagerPolicy.2
                    @Override // java.lang.Runnable
                    public void run() {
                        if (ProcessRecord.this != null && ProcessRecord.this.killRecord <= 10) {
                            ProcessManagerPolicy.forceStop(ProcessRecord.this.info.packageName, ProcessRecord.this.userId);
                            ProcessRecord.this.killRecord++;
                            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "updateProcessPolicyIfNeed kill r=" + ProcessRecord.this.toString() + " Record=" + ProcessRecord.this.killRecord);
                        }
                    }
                });
            }
        } catch (Exception e) {
            log(TAG, "updateProcessPolicyIfNeed e:" + e);
        }
    }

    private synchronized void updateProcessStatsPolicy() {
        int workingProcessLimited;
        double cpu;
        double mem;
        boolean highLoading;
        final int cachedProcessLimit;
        final ArrayList<ProcessItemRecord> forceList;
        boolean z;
        HashMap<Integer, ProcessItemRecord> records;
        int workingProcessLimited2;
        double cpu2;
        double mem2;
        boolean highLoading2;
        int cachedProcessLimit2;
        ArrayList<ProcessItemRecord> forceList2;
        ProcessManagerPolicy processManagerPolicy = this;
        synchronized (this) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            if (processManagerPolicy.mService == null) {
                return;
            }
            if (processManagerPolicy.mService.mLruProcesses == null) {
                return;
            }
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            double cpu3 = sStateRecord.cpuPercent;
            double mem3 = sStateRecord.memPercent;
            boolean highLoading3 = ProcessStateManager.highLoading(cpu3, mem3);
            int cachedProcessLimit3 = sPolicyInfo.cachedProcessLimit;
            final int killTopProcessNumber = ProcessPolicyArrays.matchPolicy(cpu3, mem3, sPolicyInfo.killTopProcessNumber);
            int workingProcessLimited3 = ProcessPolicyArrays.matchPolicy(cpu3, mem3, sPolicyInfo.workingProcessLimit);
            HashMap<Integer, ProcessItemRecord> records2 = sStateRecord.records;
            final ArrayList<ProcessRecord> processes = processManagerPolicy.mService.mLruProcesses;
            final ArrayList<ProcessRecord> cached = new ArrayList<>();
            ArrayList<String> packages = new ArrayList<>();
            final ArrayList<ProcessItemRecord> list = new ArrayList<>();
            ArrayList<ProcessItemRecord> forceList3 = new ArrayList<>();
            int backgroundProcess = 0;
            if (!ArrayUtils.isEmpty(records2) && !ArrayUtils.isEmpty(processes)) {
                try {
                } catch (Throwable th2) {
                    th = th2;
                }
                synchronized (processManagerPolicy.mService) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        Iterator<ProcessRecord> it = processes.iterator();
                        while (it.hasNext()) {
                            try {
                                ProcessRecord r = it.next();
                                Iterator<ProcessRecord> it2 = it;
                                if (r != null) {
                                    mem2 = mem3;
                                    try {
                                        if (TextUtils.isEmpty(r.info.packageName)) {
                                            records = records2;
                                            workingProcessLimited2 = workingProcessLimited3;
                                            cpu2 = cpu3;
                                            highLoading2 = highLoading3;
                                            cachedProcessLimit2 = cachedProcessLimit3;
                                            forceList2 = forceList3;
                                        } else {
                                            int pid = r.pid;
                                            boolean emptyCached = ProcessStateManager.processEmptyCached(r);
                                            cpu2 = cpu3;
                                            try {
                                                if (records2.containsKey(Integer.valueOf(pid))) {
                                                    ProcessItemRecord item = records2.get(Integer.valueOf(pid));
                                                    item.adj = r.curAdj;
                                                    item.allowKill = ProcessStateManager.packageAllowKill(r.info.packageName, processes);
                                                    records = records2;
                                                    try {
                                                        workingProcessLimited2 = workingProcessLimited3;
                                                        try {
                                                            highLoading2 = highLoading3;
                                                            cachedProcessLimit2 = cachedProcessLimit3;
                                                            try {
                                                                item.forceKill = ProcessStateManager.processForceKilled(r.info.packageName, item.cpu, ProcessStateManager.memSize(item.pss));
                                                                item.packageName = r.info.packageName;
                                                                list.add(item);
                                                                packages.add(r.info.packageName);
                                                                boolean background = ProcessStateManager.isBackground(r);
                                                                if (background) {
                                                                    backgroundProcess++;
                                                                }
                                                                if (item.forceKill) {
                                                                    forceList2 = forceList3;
                                                                    try {
                                                                        forceList2.add(ProcessItemRecord.clone(item));
                                                                    } catch (Throwable th3) {
                                                                        th = th3;
                                                                        while (true) {
                                                                            try {
                                                                                break;
                                                                            } catch (Throwable th4) {
                                                                                th = th4;
                                                                            }
                                                                        }
                                                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                                                        throw th;
                                                                    }
                                                                } else {
                                                                    forceList2 = forceList3;
                                                                }
                                                            } catch (Throwable th5) {
                                                                th = th5;
                                                            }
                                                        } catch (Throwable th6) {
                                                            th = th6;
                                                        }
                                                    } catch (Throwable th7) {
                                                        th = th7;
                                                    }
                                                } else {
                                                    records = records2;
                                                    workingProcessLimited2 = workingProcessLimited3;
                                                    highLoading2 = highLoading3;
                                                    cachedProcessLimit2 = cachedProcessLimit3;
                                                    forceList2 = forceList3;
                                                }
                                                if (emptyCached) {
                                                    cached.add(r);
                                                }
                                            } catch (Throwable th8) {
                                                th = th8;
                                            }
                                        }
                                    } catch (Throwable th9) {
                                        th = th9;
                                    }
                                } else {
                                    records = records2;
                                    workingProcessLimited2 = workingProcessLimited3;
                                    cpu2 = cpu3;
                                    mem2 = mem3;
                                    highLoading2 = highLoading3;
                                    cachedProcessLimit2 = cachedProcessLimit3;
                                    forceList2 = forceList3;
                                }
                                forceList3 = forceList2;
                                it = it2;
                                mem3 = mem2;
                                cpu3 = cpu2;
                                records2 = records;
                                workingProcessLimited3 = workingProcessLimited2;
                                highLoading3 = highLoading2;
                                cachedProcessLimit3 = cachedProcessLimit2;
                            } catch (Throwable th10) {
                                th = th10;
                            }
                        }
                        workingProcessLimited = workingProcessLimited3;
                        cpu = cpu3;
                        mem = mem3;
                        highLoading = highLoading3;
                        cachedProcessLimit = cachedProcessLimit3;
                        forceList = forceList3;
                    } catch (Throwable th11) {
                        th = th11;
                    }
                    try {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        if (!forceList.isEmpty()) {
                            Runnable runnable = new Runnable() { // from class: com.android.server.am.ProcessManagerPolicy.3
                                @Override // java.lang.Runnable
                                public void run() {
                                    Iterator it3 = forceList.iterator();
                                    while (it3.hasNext()) {
                                        ProcessItemRecord r2 = (ProcessItemRecord) it3.next();
                                        if (r2 != null) {
                                            ProcessManagerPolicy.clean(r2.uid, r2.pid, ProcessManagerPolicy.this.mContext.getUserId(), r2.packageName);
                                            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "updateProcessStatsPolicy force kill packageName=" + r2.packageName + " pid=" + r2.pid + " uid=" + r2.uid);
                                        }
                                    }
                                }
                            };
                            processManagerPolicy.mBgHandler.post(runnable);
                        }
                        cached.sort(ProcessStateManager.sCachedEmptyComparator);
                        if (highLoading && !cached.isEmpty()) {
                            Runnable task = new Runnable() { // from class: com.android.server.am.ProcessManagerPolicy.4
                                @Override // java.lang.Runnable
                                public void run() {
                                    int counter = 0;
                                    int size = cached.size();
                                    int killNumber = size - cachedProcessLimit;
                                    Iterator it3 = cached.iterator();
                                    while (it3.hasNext()) {
                                        ProcessRecord r2 = (ProcessRecord) it3.next();
                                        if (killNumber > 0 && counter < killNumber) {
                                            if (r2.info != null) {
                                                synchronized (ProcessManagerPolicy.this.mService) {
                                                    try {
                                                        ActivityManagerService.boostPriorityForLockedSection();
                                                        boolean ret = ProcessStateManager.cleanCachedEmptyProcessIfNeeded(r2, processes);
                                                        if (ret) {
                                                            counter++;
                                                        }
                                                    } catch (Throwable th12) {
                                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                                        throw th12;
                                                    }
                                                }
                                                ActivityManagerService.resetPriorityAfterLockedSection();
                                            }
                                        } else {
                                            return;
                                        }
                                    }
                                }
                            };
                            processManagerPolicy.mBgHandler.post(task);
                        }
                        final int backgroundProcessNumber = backgroundProcess;
                        list.sort(ProcessStateRecord.sComparator);
                        buffer.delete(0, buffer.length());
                        if (DEBUG_HIGH_LOADING) {
                            buffer.append("updateProcessStatsPolicy processes list:");
                            buffer.append("\n");
                        }
                        Iterator<ProcessItemRecord> it3 = list.iterator();
                        while (true) {
                            z = true;
                            if (!it3.hasNext()) {
                                break;
                            }
                            ProcessItemRecord r2 = it3.next();
                            ProcessStateManager.warningProcessItemRecordIfNeeded(r2, sPolicyInfo.warningMax);
                            if (DEBUG_HIGH_LOADING) {
                                boolean foreground = ProcessStateManager.isForeground(r2.adj);
                                try {
                                    buffer.append(ProcessStateManager.toRadio("cpu=", r2.cpu));
                                    buffer.append(" pss=" + r2.pss + "KB");
                                    StringBuilder sb = new StringBuilder();
                                    sb.append(" pid=");
                                    sb.append(r2.pid);
                                    buffer.append(sb.toString());
                                    buffer.append(" adj=" + r2.adj);
                                    buffer.append(" allowKill=" + r2.allowKill);
                                    StringBuilder sb2 = new StringBuilder();
                                    sb2.append(" background=");
                                    if (foreground) {
                                        z = false;
                                    }
                                    sb2.append(z);
                                    buffer.append(sb2.toString());
                                    buffer.append(" packageName=" + r2.packageName);
                                    buffer.append("\n");
                                } catch (Throwable th12) {
                                    th = th12;
                                }
                            }
                            processManagerPolicy = this;
                            th = th12;
                            throw th;
                        }
                        if (DEBUG_HIGH_LOADING) {
                            log(TAG, buffer.toString());
                        }
                        if (backgroundProcessNumber > workingProcessLimited) {
                            z = false;
                        }
                        final boolean backgroundProcessNumberValid = z;
                        buffer.delete(0, buffer.length());
                        buffer.append("updateProcessStatsPolicy");
                        buffer.append(ProcessStateManager.toRadio(" cpu=", cpu));
                        buffer.append(ProcessStateManager.toRadio(" mem=", mem));
                        buffer.append(" highLoading=" + highLoading);
                        buffer.append(" workingNumber=" + list.size());
                        buffer.append(" cachedNumber=" + cached.size());
                        buffer.append(" killPolicyProcessNumber=" + killTopProcessNumber);
                        buffer.append(" workingProcessLimited=" + workingProcessLimited);
                        buffer.append(" backgroundProcessNumber=" + backgroundProcessNumber);
                        buffer.append(" backgroundProcessNumberValid=" + backgroundProcessNumberValid);
                        log(TAG, buffer.toString());
                        if (!backgroundProcessNumberValid || highLoading) {
                            Runnable runnable2 = new Runnable() { // from class: com.android.server.am.ProcessManagerPolicy.5
                                @Override // java.lang.Runnable
                                public void run() {
                                    if (list != null && !list.isEmpty()) {
                                        int counter = 0;
                                        int size = list.size();
                                        StringBuffer sb3 = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                                        int killNumber = backgroundProcessNumberValid ? killTopProcessNumber : size - backgroundProcessNumber;
                                        sb3.append("updateProcessStatsPolicy runnable");
                                        sb3.append(" size=" + size);
                                        sb3.append(" killNumber=" + killNumber);
                                        sb3.append(" \n");
                                        for (int i = 0; i < size && counter <= killNumber; i++) {
                                            ProcessItemRecord item2 = (ProcessItemRecord) list.get(i);
                                            if (item2 != null) {
                                                boolean whitelistProcess = ProcessStateManager.isWhitelistProcess(item2.packageName);
                                                boolean isAppInAudioFocus = ProcessStateManager.isAppInAudioFocus(ProcessManagerPolicy.this.mContext, item2.packageName);
                                                boolean highLoadingProcess = ProcessStateManager.processHighLoading(item2.packageName, item2.cpu, ProcessStateManager.memSize(item2.pss));
                                                boolean allowKill = item2.allowKill;
                                                sb3.append(" packageName=" + item2.packageName);
                                                sb3.append(" pid=" + item2.pid);
                                                sb3.append(ProcessStateManager.toRadio(" cpu=", (double) item2.cpu));
                                                sb3.append(" pss=" + item2.pss + "KB");
                                                StringBuilder sb4 = new StringBuilder();
                                                sb4.append(" counter=");
                                                sb4.append(counter);
                                                sb3.append(sb4.toString());
                                                sb3.append(" allowKill=" + allowKill);
                                                sb3.append(" whitelistProcess=" + whitelistProcess);
                                                sb3.append(" isAppInAudioFocus=" + isAppInAudioFocus);
                                                sb3.append(" highLoadingProcess=" + highLoadingProcess);
                                                if (!whitelistProcess && !isAppInAudioFocus && highLoadingProcess && allowKill) {
                                                    ProcessManagerPolicy.trace(item2.pid);
                                                    ProcessManagerPolicy.clean(item2.uid, item2.pid, ProcessManagerPolicy.this.mContext.getUserId(), item2.packageName);
                                                    sb3.append(" do kill action.");
                                                    sb3.append(" \n");
                                                    counter++;
                                                } else {
                                                    sb3.append(" skip kill action.");
                                                }
                                            }
                                        }
                                        ProcessManagerPolicy.log(sb3.toString());
                                    }
                                }
                            };
                            this.mBgHandler.post(runnable2);
                        }
                    } catch (Throwable th13) {
                        th = th13;
                        while (true) {
                            break;
                            break;
                        }
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void updateProcessPowerPolicy() {
        boolean z = true;
        boolean checkMediaPolicy = SystemProperties.getInt("persist.sys.media.launch.policy", -1) == 3;
        String mediaPolicyPackageName = checkMediaPolicy ? SystemProperties.get("persist.sys.media.launch.packageName", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS) : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        IActivityManager am = ProcessStateManager.getActivityManager();
        List<ActivityManager.RunningAppProcessInfo> list = ProcessStateManager.getRunningAppProcesses();
        if (am != null && list != null) {
            sForceStoppingFromPower = true;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info != null) {
                    String[] strArr = info.pkgList;
                    int length = strArr.length;
                    int i = 0;
                    while (i < length) {
                        String packageName = strArr[i];
                        boolean keepAlive = TextUtils.equals(packageName, mediaPolicyPackageName);
                        boolean shouldKill = (keepAlive || !ProcessStateManager.shouldKillWhenUserLeaving(packageName)) ? false : z;
                        if (shouldKill) {
                            forceStop(packageName, this.mContext.getUserId());
                            log(TAG, "updateProcessPowerPolicy kill packageName:" + packageName);
                        }
                        i++;
                        z = true;
                    }
                    z = true;
                }
            }
            sForceStoppingFromPower = false;
        }
    }

    private void onProcessStatsChanged() {
        double cpu = sStateRecord.cpuPercent;
        double mem = sStateRecord.memPercent;
        long memAvail = sStateRecord.memAvail;
        long memTotal = sStateRecord.memTotal;
        boolean highLoading = ProcessStateManager.highLoading(cpu, mem);
        int delay = ProcessPolicyArrays.matchPolicy(cpu, mem, sPolicyInfo.processStatsInterval);
        log(TAG, "onProcessStatsChanged " + ProcessStateManager.toRadio(" cpu=", cpu) + ProcessStateManager.toRadio(" mem=", mem) + " memAvail=" + memAvail + "KB memTotal=" + memTotal + "KB highLoading=" + highLoading + " delay=" + delay + "min");
        if (highLoading) {
            sStateRecord.dump(10, getProcessesFilter());
        }
        updateProcessStatsPolicy();
        scheduleProcessStats(delay * 60000);
    }

    private void scheduleProcessStats(long delay) {
        this.mBgHandler.removeMessages(1000);
        this.mBgHandler.sendEmptyMessageDelayed(1000, delay);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void calculateProcessStatsLocked() {
        long j;
        if (this.mService != null && this.mService.mProcessCpuTracker != null) {
            try {
                Context context = this.mContext;
                ProcessCpuTracker tracker = this.mService.mProcessCpuTracker;
                int count = tracker != null ? tracker.countWorkingStats() : 0;
                if (tracker != null && count > 0) {
                    float cpuPercent = tracker.getTotalCpuPercent();
                    ArrayList<ProcessItemRecord> cpu = new ArrayList<>();
                    ArrayList<ProcessItemRecord> mem = new ArrayList<>();
                    String[] names = new String[count];
                    int[] pids = new int[count];
                    int[] uids = new int[count];
                    for (int i = 0; i < count; i++) {
                        ProcessCpuTracker.Stats stats = tracker.getWorkingStats(i);
                        if (stats != null) {
                            float radio = ProcessTracker.getCpuPercent(stats);
                            ProcessItemRecord r = new ProcessItemRecord();
                            r.cpu = radio / 100.0f;
                            r.pid = stats.pid;
                            r.uid = stats.uid;
                            r.name = stats.name;
                            r.working = true;
                            pids[i] = stats.pid;
                            uids[i] = stats.uid;
                            names[i] = stats.name;
                            cpu.add(r);
                        }
                    }
                    ActivityManager.MemoryInfo memory = ProcessTracker.getMemoryInfo(context);
                    Debug.MemoryInfo[] info = ProcessTracker.getMemoryInfo(context, pids);
                    if (info != null && info.length > 0 && info.length == count) {
                        int i2 = 0;
                        while (true) {
                            int i3 = i2;
                            if (i3 >= count) {
                                break;
                            }
                            Debug.MemoryInfo mi = info[i3];
                            if (mi != null) {
                                ProcessItemRecord r2 = new ProcessItemRecord();
                                r2.pss = mi.getTotalPss();
                                r2.pid = pids[i3];
                                r2.uid = uids[i3];
                                r2.name = names[i3];
                                r2.working = true;
                                mem.add(r2);
                            }
                            i2 = i3 + 1;
                        }
                    }
                    try {
                        synchronized (sStateRecord) {
                            try {
                                ProcessStateRecord processStateRecord = sStateRecord;
                                if (memory != null) {
                                    try {
                                        j = memory.availMem;
                                    } catch (Throwable th) {
                                        th = th;
                                        throw th;
                                    }
                                } else {
                                    j = 0;
                                }
                                try {
                                    processStateRecord.memAvail = j;
                                    sStateRecord.memTotal = memory != null ? memory.totalMem : 0L;
                                    sStateRecord.cpuPercent = cpuPercent / 100.0f;
                                    long totalMemory = sStateRecord.memTotal / 1024;
                                    try {
                                        long usedMemory = (sStateRecord.memTotal - sStateRecord.memAvail) / 1024;
                                        sStateRecord.memPercent = totalMemory > 0 ? usedMemory / totalMemory : 0.0d;
                                        sStateRecord.setRecord(1, cpu);
                                        sStateRecord.setRecord(2, mem);
                                        sStateRecord.update();
                                    } catch (Throwable th2) {
                                        th = th2;
                                        throw th;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                }
                            } catch (Throwable th4) {
                                th = th4;
                            }
                        }
                    } catch (Throwable th5) {
                        th = th5;
                    }
                }
            } catch (Exception e) {
                log(TAG, "calculateProcessStatsLocked e=" + e);
            }
        }
        onProcessStatsChanged();
    }

    private List<Integer> getProcessesFilter() {
        List<Integer> list = new ArrayList<>();
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                ArrayList<ProcessRecord> processes = this.mService.mLruProcesses;
                if (processes != null) {
                    Iterator<ProcessRecord> it = processes.iterator();
                    while (it.hasNext()) {
                        ProcessRecord r = it.next();
                        if (r != null) {
                            list.add(Integer.valueOf(r.pid));
                        }
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return list;
    }

    private static final int cleanType(String packageName) {
        ProcessPolicyPackages pkg;
        try {
            if (!TextUtils.isEmpty(packageName) && sPolicyInfo.processWhitelist.containsKey(packageName) && (pkg = sPolicyInfo.processWhitelist.get(packageName)) != null) {
                return pkg.cleanType;
            }
            return 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void clean(ProcessRecord r) {
        if (r != null && r.info != null) {
            clean(cleanType(r.info.packageName), r.uid, r.pid, r.userId, r.info.packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void clean(int uid, int pid, int userId, String packageName) {
        clean(cleanType(packageName), uid, pid, userId, packageName);
    }

    private static final void clean(int type, int uid, int pid, int userId, String packageName) {
        if (policyEnabled()) {
            try {
                switch (type) {
                    case 1:
                        kill(uid, pid);
                        break;
                    case 2:
                        forceStop(packageName, userId);
                        break;
                    default:
                        return;
                }
            } catch (Exception e) {
            }
        }
    }

    private static final void kill(int uid, int pid) {
        if (uid > 0 && pid > 0) {
            Process.killProcessQuiet(pid);
            Process.killProcessGroup(uid, pid);
            log(TAG, "killProcess by process policy uid=" + uid + " pid=" + pid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void trace(int pid) {
        if (TRACE) {
            Process.sendSignal(pid, 3);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void forceStop(String packageName, int userId) {
        try {
            IActivityManager am = ProcessStateManager.getActivityManager();
            if (am != null) {
                am.forceStopPackage(packageName, userId);
                log(TAG, "forceStop by process policy packageName=" + packageName + " userId=" + userId);
            }
        } catch (Exception e) {
            log(TAG, "forceStop by process policy e=" + e);
        }
    }

    private static final void forceStop(ProcessRecord r, int userId) {
        try {
            IActivityManager am = ProcessStateManager.getActivityManager();
            if (am != null && r != null && r.info != null && !r.killed && !r.killedByAm) {
                am.forceStopPackage(r.info.packageName, userId);
                log(TAG, "forceStop by process policy packageName=" + r.info.packageName + " userId=" + userId);
            }
        } catch (Exception e) {
            log(TAG, "forceStop by process policy e=" + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String msg) {
        if (DEBUG) {
            Log.i(TAG, msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static boolean isPersistent(String packageName) {
        return true;
    }

    public static void setProcessItemRecord(int pid, ProcessItemRecord item) {
        synchronized (sStateRecord) {
            sStateRecord.setRecord(pid, item);
        }
    }

    public static boolean policyEnabled() {
        return sPolicyInfo.policyEnabled;
    }

    public static long getProcessPolicyDelay() {
        return sPolicyInfo.killTopProcessDelay * 60000;
    }

    public static int fetchBackgroundSettings(String packageName) {
        int settings = -1;
        boolean isSystem = ActivityInfoManager.isSystemApplication(packageName);
        if (!isSystem) {
            synchronized (sBackgroundSettings) {
                if (sBackgroundSettings.containsKey(packageName)) {
                    settings = sBackgroundSettings.getOrDefault(packageName, -1).intValue();
                } else {
                    xpPackageInfo xpi = ProcessStateManager.getOverridePackageInfo(packageName);
                    settings = xpi != null ? xpi.backgroundStatus : -1;
                    if (settings != -1) {
                        sBackgroundSettings.put(packageName, Integer.valueOf(settings));
                    }
                }
            }
        }
        if (DEBUG) {
            log(TAG, "fetchBackgroundSettings packageName=" + packageName + " settings=" + settings);
        }
        return settings;
    }

    /* loaded from: classes.dex */
    public static class ProcessTracker {
        public static ActivityManager.MemoryInfo getMemoryInfo(Context context) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService("activity");
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                return info;
            } catch (Exception e) {
                return null;
            }
        }

        public static Debug.MemoryInfo[] getMemoryInfo(Context context, int[] pids) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService("activity");
                Debug.MemoryInfo[] info = am.getProcessMemoryInfo(pids);
                return info;
            } catch (Exception e) {
                return null;
            }
        }

        public static float getCpuPercent(ProcessCpuTracker.Stats stats) {
            if (stats != null) {
                int totalTime = (int) stats.rel_uptime;
                int user = stats.rel_utime;
                int system = stats.rel_stime;
                int i = stats.rel_minfaults;
                int i2 = stats.rel_majfaults;
                if (totalTime == 0) {
                    totalTime = 1;
                }
                long numerator = user + system + 0 + 0 + 0;
                long denominator = totalTime;
                long thousands = (1000 * numerator) / denominator;
                long hundreds = thousands / 10;
                StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                buffer.append(String.valueOf(hundreds));
                if (hundreds < 10) {
                    long remainder = thousands - (10 * hundreds);
                    if (remainder != 0) {
                        buffer.append('.');
                        buffer.append(String.valueOf(remainder));
                    }
                }
                try {
                    return Float.valueOf(buffer.toString()).floatValue();
                } catch (Exception e) {
                    return 0.0f;
                }
            }
            return 0.0f;
        }
    }

    /* loaded from: classes.dex */
    public static class ProcessItemRecord {
        public int adj;
        public boolean allowKill;
        public float cpu;
        public boolean forceKill;
        public String name;
        public String packageName;
        public int pid;
        public int pss;
        public int setAdj;
        public int uid;
        public int warning;
        public boolean working;

        public static ProcessItemRecord clone(ProcessItemRecord item) {
            if (item != null) {
                ProcessItemRecord record = new ProcessItemRecord();
                record.pid = item.pid;
                record.uid = item.uid;
                record.adj = item.adj;
                record.pss = item.pss;
                record.cpu = item.cpu;
                record.warning = item.warning;
                record.name = item.name;
                record.setAdj = item.setAdj;
                record.working = item.working;
                record.packageName = item.packageName;
                record.allowKill = item.allowKill;
                record.forceKill = item.forceKill;
                return null;
            }
            return null;
        }
    }

    /* loaded from: classes.dex */
    public static class ProcessStateRecord {
        public static final int TYPE_CPU = 1;
        public static final int TYPE_MEM = 2;
        public static final Comparator<ProcessItemRecord> sComparator = new Comparator<ProcessItemRecord>() { // from class: com.android.server.am.ProcessManagerPolicy.ProcessStateRecord.1
            @Override // java.util.Comparator
            public final int compare(ProcessItemRecord a, ProcessItemRecord b) {
                if (a != null && b != null) {
                    if (a.adj != b.adj) {
                        return a.adj > b.adj ? -1 : 1;
                    } else if (a.cpu != b.cpu) {
                        return a.cpu > b.cpu ? -1 : 1;
                    } else if (a.pss != b.pss) {
                        return a.pss > b.pss ? -1 : 1;
                    } else {
                        return 0;
                    }
                }
                return 0;
            }
        };
        public static final Comparator<ProcessItemRecord> sCpuComparator = new Comparator<ProcessItemRecord>() { // from class: com.android.server.am.ProcessManagerPolicy.ProcessStateRecord.2
            @Override // java.util.Comparator
            public final int compare(ProcessItemRecord a, ProcessItemRecord b) {
                if (a != null && b != null) {
                    if (a.cpu != b.cpu) {
                        return a.cpu > b.cpu ? -1 : 1;
                    } else if (a.pss != b.pss) {
                        return a.pss > b.pss ? -1 : 1;
                    } else {
                        return 0;
                    }
                }
                return 0;
            }
        };
        public static final Comparator<ProcessItemRecord> sMemComparator = new Comparator<ProcessItemRecord>() { // from class: com.android.server.am.ProcessManagerPolicy.ProcessStateRecord.3
            @Override // java.util.Comparator
            public final int compare(ProcessItemRecord a, ProcessItemRecord b) {
                if (a == null || b == null || a.pss == b.pss) {
                    return 0;
                }
                return a.pss > b.pss ? -1 : 1;
            }
        };
        public volatile long memTotal = 1024;
        public volatile long memAvail = 0;
        public volatile double memPercent = 0.0d;
        public volatile double cpuPercent = 0.0d;
        public volatile HashMap<Integer, ProcessItemRecord> records = new HashMap<>();
        public volatile ArrayList<ProcessItemRecord> cpu = new ArrayList<>();
        public volatile ArrayList<ProcessItemRecord> mem = new ArrayList<>();

        public void setRecord(int pid, ProcessItemRecord item) {
            this.records.put(Integer.valueOf(pid), item);
        }

        public void setRecord(int type, ArrayList<ProcessItemRecord> list) {
            switch (type) {
                case 1:
                    this.cpu.clear();
                    this.cpu.addAll(list);
                    return;
                case 2:
                    this.mem.clear();
                    this.mem.addAll(list);
                    this.mem.sort(sMemComparator);
                    return;
                default:
                    return;
            }
        }

        public void update() {
            ProcessItemRecord r;
            try {
                for (Integer num : this.records.keySet()) {
                    ProcessItemRecord r2 = this.records.get(Integer.valueOf(num.intValue()));
                    if (r2 != null) {
                        r2.working = false;
                    }
                }
                Iterator<ProcessItemRecord> it = this.cpu.iterator();
                while (it.hasNext()) {
                    ProcessItemRecord r3 = it.next();
                    if (r3 != null) {
                        int pid = r3.pid;
                        boolean has = this.records.containsKey(Integer.valueOf(pid));
                        if (has) {
                            ProcessItemRecord item = this.records.get(Integer.valueOf(pid));
                            if (item != null) {
                                item.cpu = r3.cpu;
                                item.working = r3.working;
                            }
                        } else {
                            this.records.put(Integer.valueOf(pid), r3);
                        }
                    }
                }
                Iterator<ProcessItemRecord> it2 = this.mem.iterator();
                while (it2.hasNext()) {
                    ProcessItemRecord r4 = it2.next();
                    if (r4 != null) {
                        int pid2 = r4.pid;
                        boolean has2 = this.records.containsKey(Integer.valueOf(pid2));
                        if (has2) {
                            ProcessItemRecord item2 = this.records.get(Integer.valueOf(pid2));
                            if (item2 != null) {
                                item2.pss = r4.pss;
                                item2.working = r4.working;
                            }
                        } else {
                            this.records.put(Integer.valueOf(pid2), r4);
                        }
                    }
                }
                Iterator<Map.Entry<Integer, ProcessItemRecord>> iterator = this.records.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Integer, ProcessItemRecord> entry = iterator.next();
                    if (entry != null && (r = entry.getValue()) != null && !r.working) {
                        iterator.remove();
                    }
                }
            } catch (Exception e) {
                ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "processStateRecord update e=" + e);
            }
        }

        public boolean checkFilter(int pid, List<Integer> filter) {
            if (filter == null || filter.isEmpty()) {
                return true;
            }
            return filter.contains(Integer.valueOf(pid));
        }

        public void dump() {
            dump(0);
        }

        public void dump(int depth) {
            dump(depth, null);
        }

        public void dump(int depth, List<Integer> filter) {
            ProcessItemRecord r;
            ProcessItemRecord r2;
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            buffer.delete(0, buffer.length()).append("\n");
            buffer.append("dump process stats");
            buffer.append(" memTotal=" + this.memTotal);
            buffer.append(" memAvail=" + this.memAvail);
            buffer.append(ProcessStateManager.toRadio(" mem=", this.memPercent));
            buffer.append(ProcessStateManager.toRadio(" cpu=", this.cpuPercent));
            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            buffer.delete(0, buffer.length());
            buffer.append("dump process cpu stats:");
            buffer.append("\n");
            if (!this.cpu.isEmpty()) {
                int size = this.cpu.size();
                for (int i = 0; i < size; i++) {
                    if ((depth <= 0 || i <= depth) && (r2 = this.cpu.get(i)) != null && r2.working && checkFilter(r2.pid, filter)) {
                        buffer.append(ProcessStateManager.toRadio("cpu=", r2.cpu));
                        buffer.append(" pid=" + r2.pid);
                        buffer.append(" uid=" + r2.uid);
                        buffer.append(" adj=" + r2.adj);
                        buffer.append(" process=" + r2.name);
                        buffer.append(" working=" + r2.working);
                        buffer.append("\n");
                    }
                }
            }
            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            buffer.delete(0, buffer.length());
            buffer.append("dump process mem stats:");
            buffer.append("\n");
            if (!this.mem.isEmpty()) {
                int size2 = this.mem.size();
                for (int i2 = 0; i2 < size2; i2++) {
                    if ((depth <= 0 || i2 <= depth) && (r = this.mem.get(i2)) != null && r.working && checkFilter(r.pid, filter)) {
                        buffer.append("mem=" + r.pss + "KB");
                        StringBuilder sb = new StringBuilder();
                        sb.append(" pid=");
                        sb.append(r.pid);
                        buffer.append(sb.toString());
                        buffer.append(" uid=" + r.uid);
                        buffer.append(" adj=" + r.adj);
                        buffer.append(" process=" + r.name);
                        buffer.append(" working=" + r.working);
                        buffer.append("\n");
                    }
                }
            }
            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            buffer.delete(0, buffer.length());
            buffer.append("dump others process stats:");
            buffer.append("\n");
            if (!this.records.isEmpty()) {
                ArrayList<ProcessItemRecord> others = new ArrayList<>();
                for (Integer num : this.records.keySet()) {
                    int pid = num.intValue();
                    ProcessItemRecord r3 = this.records.get(Integer.valueOf(pid));
                    if (r3 != null && r3.working && !checkFilter(r3.pid, filter)) {
                        others.add(r3);
                    }
                }
                others.sort(sCpuComparator);
                int index = 0;
                Iterator<ProcessItemRecord> it = others.iterator();
                while (it.hasNext()) {
                    ProcessItemRecord r4 = it.next();
                    if (r4 != null && r4.working && (depth <= 0 || index <= depth)) {
                        index++;
                        buffer.append(ProcessStateManager.toRadio("cpu=", r4.cpu));
                        buffer.append(" mem=" + r4.pss + "KB");
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append(" pid=");
                        sb2.append(r4.pid);
                        buffer.append(sb2.toString());
                        buffer.append(" uid=" + r4.uid);
                        buffer.append(" adj=" + r4.adj);
                        buffer.append(" process=" + r4.name);
                        buffer.append(" working=" + r4.working);
                        buffer.append("\n");
                    }
                }
            }
            ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            if (ProcessManagerPolicy.DEBUG_ALL) {
                buffer.delete(0, buffer.length());
                buffer.append("dump process all records stats:");
                buffer.append("\n");
                if (!this.records.isEmpty()) {
                    for (Integer num2 : this.records.keySet()) {
                        int pid2 = num2.intValue();
                        ProcessItemRecord r5 = this.records.get(Integer.valueOf(pid2));
                        if (r5 != null && r5.working) {
                            buffer.append(ProcessStateManager.toRadio("cpu=", r5.cpu));
                            buffer.append(" mem=" + r5.pss + "KB");
                            StringBuilder sb3 = new StringBuilder();
                            sb3.append(" pid=");
                            sb3.append(r5.pid);
                            buffer.append(sb3.toString());
                            buffer.append(" uid=" + r5.uid);
                            buffer.append(" adj=" + r5.adj);
                            buffer.append(" process=" + r5.name);
                            buffer.append(" working=" + r5.working);
                            buffer.append("\n");
                        }
                    }
                }
                ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            }
        }
    }

    /* loaded from: classes.dex */
    public static class ProcessStateManager {
        public static final Comparator<ProcessRecord> sComparator = new Comparator<ProcessRecord>() { // from class: com.android.server.am.ProcessManagerPolicy.ProcessStateManager.1
            @Override // java.util.Comparator
            public final int compare(ProcessRecord a, ProcessRecord b) {
                if (a != null && b != null) {
                    if (a.curAdj != b.curAdj) {
                        return a.curAdj > b.curAdj ? -1 : 1;
                    } else if (a.foregroundTime != b.foregroundTime) {
                        return a.foregroundTime < b.foregroundTime ? -1 : 1;
                    } else {
                        return 0;
                    }
                }
                return 0;
            }
        };
        public static final Comparator<ProcessRecord> sCachedEmptyComparator = new Comparator<ProcessRecord>() { // from class: com.android.server.am.ProcessManagerPolicy.ProcessStateManager.2
            @Override // java.util.Comparator
            public final int compare(ProcessRecord a, ProcessRecord b) {
                if (a != null && b != null) {
                    if (a.cachedEmptyTime != b.cachedEmptyTime) {
                        return a.cachedEmptyTime > b.cachedEmptyTime ? -1 : 1;
                    } else if (a.curAdj != b.curAdj) {
                        return a.curAdj > b.curAdj ? -1 : 1;
                    } else if (a.foregroundTime != b.foregroundTime) {
                        return a.foregroundTime < b.foregroundTime ? -1 : 1;
                    } else {
                        return 0;
                    }
                }
                return 0;
            }
        };

        protected static boolean shouldKillWhenUserLeaving(String packageName) {
            if (!TextUtils.isEmpty(packageName)) {
                IPackageManager pm = getPackageManager();
                boolean isProtectedProcess = isProtectedProcess(packageName);
                boolean isWhitelistProcess = isWhitelistProcess(packageName);
                try {
                    PackageInfo pi = pm.getPackageInfo(packageName, 0, 0);
                    boolean isSystem = (pi.applicationInfo.flags & 1) == 1;
                    if (ProcessManagerPolicy.DEBUG_USER_LEAVING) {
                        xpLogger.i(ProcessManagerPolicy.TAG, "shouldKillWhenUserLeaving packageName=" + packageName + " isSystem=" + isSystem + " isProtected=" + isProtectedProcess + " isWhitelist=" + isWhitelistProcess);
                    }
                    return (isSystem || isProtectedProcess || isWhitelistProcess) ? false : true;
                } catch (Exception e) {
                }
            }
            return false;
        }

        protected static boolean shouldKillWhenPageLeaving(ProcessRecord r, ArrayList<ProcessRecord> lru) {
            boolean shouldKillDelayed;
            boolean shouldKillImmediately;
            boolean processNeverForeground;
            if (r == null || r.info == null) {
                return false;
            }
            int backgroundStatus = ProcessManagerPolicy.fetchBackgroundSettings(r.info.packageName);
            if (backgroundStatus == 1) {
                shouldKillDelayed = false;
            } else {
                shouldKillDelayed = true;
            }
            if (backgroundStatus != 2) {
                shouldKillImmediately = false;
            } else {
                shouldKillImmediately = true;
            }
            if (r.foregroundTime != 0) {
                processNeverForeground = false;
            } else {
                processNeverForeground = true;
            }
            long now = SystemClock.uptimeMillis();
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            if (ProcessManagerPolicy.DEBUG) {
                buffer.append("shouldKillWhenPageLeaving");
                buffer.append(" packageName=" + r.info.packageName);
                buffer.append(" pid=" + r.pid);
                buffer.append(" uid=" + r.uid);
                buffer.append(" curAdj=" + r.curAdj);
                buffer.append(" adjType=" + r.adjType);
                buffer.append(" curProcState=" + r.curProcState);
                buffer.append(" backgroundTime=" + (now - r.backgroundTime));
                buffer.append(" foregroundTime=" + (now - r.foregroundTime));
                buffer.append(" backgroundStatus=" + backgroundStatus);
                buffer.append(" shouldKillDelayed=" + shouldKillDelayed);
                buffer.append(" shouldKillImmediately=" + shouldKillImmediately);
                buffer.append(" processNeverForeground=" + processNeverForeground);
                buffer.append(" isolated=" + r.isolated);
            }
            boolean isUntrustedProcess = isUntrustedProcess(r.info);
            if (ProcessManagerPolicy.DEBUG) {
                buffer.append(" isUntrustedProcess=" + isUntrustedProcess);
            }
            if (!isUntrustedProcess) {
                if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                    ProcessManagerPolicy.log(buffer.toString());
                }
                return false;
            }
            boolean processAllowKill = processAllowKill(r);
            if (ProcessManagerPolicy.DEBUG) {
                buffer.append(" processAllowKill=" + processAllowKill);
            }
            if (!processAllowKill) {
                if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                    ProcessManagerPolicy.log(buffer.toString());
                }
                return false;
            }
            boolean packageAllowKill = packageAllowKill(r.info.packageName, lru);
            if (ProcessManagerPolicy.DEBUG) {
                buffer.append(" packageAllowKill=" + packageAllowKill);
            }
            if (!packageAllowKill) {
                if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                    ProcessManagerPolicy.log(buffer.toString());
                }
                return false;
            } else if (shouldKillImmediately) {
                if (ProcessManagerPolicy.DEBUG) {
                    buffer.append(" should be kill by policy.");
                }
                if (ProcessManagerPolicy.DEBUG) {
                    ProcessManagerPolicy.log(buffer.toString());
                }
                return true;
            } else {
                boolean isBackgroundKeptValid = isBackgroundKeptValid(r, lru);
                if (ProcessManagerPolicy.DEBUG) {
                    buffer.append(" isBackgroundKeptValid=" + isBackgroundKeptValid);
                }
                if (!isBackgroundKeptValid) {
                    if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                        ProcessManagerPolicy.log(buffer.toString());
                    }
                    return false;
                }
                boolean isForegroundPageValid = isForegroundPageValid(r, lru);
                if (ProcessManagerPolicy.DEBUG) {
                    buffer.append(" isForegroundPageValid=" + isForegroundPageValid);
                }
                if (!isForegroundPageValid) {
                    if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                        ProcessManagerPolicy.log(buffer.toString());
                        return false;
                    }
                    return false;
                } else if (shouldKillDelayed || processNeverForeground) {
                    if (ProcessManagerPolicy.DEBUG) {
                        buffer.append(" should be kill by policy.");
                    }
                    if (ProcessManagerPolicy.DEBUG) {
                        ProcessManagerPolicy.log(buffer.toString());
                        return true;
                    }
                    return true;
                } else if (ProcessManagerPolicy.DEBUG_PAGE_LEAVING) {
                    ProcessManagerPolicy.log(buffer.toString());
                    return false;
                } else {
                    return false;
                }
            }
        }

        protected static boolean cleanCachedEmptyProcessIfNeeded(ProcessRecord r, ArrayList<ProcessRecord> lru) {
            boolean allowKill = false;
            if (r == null || r.info == null) {
                return false;
            }
            boolean ret = false;
            if (r.killedByAm || r.killed) {
                return false;
            }
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            boolean procCached = r.curProcState >= 15 && r.curProcState <= 19;
            buffer.append("cleanCachedEmptyProcessIfNeeded");
            buffer.append(" pid=" + r.pid);
            buffer.append(" adj=" + r.curAdj);
            buffer.append(" empty=" + r.empty);
            buffer.append(" cached=" + r.cached);
            buffer.append(" curProcState=" + r.curProcState);
            buffer.append(" processName=" + r.processName);
            buffer.append(" packageName=" + r.info.packageName);
            buffer.append(" procCached=" + procCached);
            if (ProcessManagerPolicy.sPolicyInfo != null && ProcessManagerPolicy.sPolicyInfo.cleanEmptyProcess && (r.empty || r.cached)) {
                if (procCached && packageAllowKill(r.info.packageName, lru)) {
                    allowKill = true;
                }
                buffer.append(" allowKill=" + allowKill);
                if (allowKill) {
                    ProcessManagerPolicy.clean(r);
                    ret = true;
                }
            }
            buffer.append(" ret=" + ret);
            if (ProcessManagerPolicy.DEBUG_ALL || ret) {
                ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, buffer.toString());
            }
            return ret;
        }

        protected static void warningProcessItemRecordIfNeeded(ProcessItemRecord item, int warningMax) {
            if (item != null) {
                boolean highLoading = processHighLoading(item.packageName, item.cpu, memSize(item.pss));
                if (highLoading) {
                    if (item.warning > warningMax) {
                        item.setAdj = decreaseAdj(item.adj);
                        ProcessManagerPolicy.trace(item.pid);
                        ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "warningProcessItemRecordIfNeeded warning pid=" + item.pid + " packageName=" + item.packageName + toRadio(" cpu=", item.cpu) + " mem=" + item.pss + "KB warningMax=" + warningMax + " setAdj=" + item.setAdj);
                    }
                    item.warning++;
                    ProcessManagerPolicy.setProcessItemRecord(item.pid, item);
                } else if (item.warning > 0) {
                    item.warning = 0;
                    ProcessManagerPolicy.setProcessItemRecord(item.pid, item);
                }
            }
        }

        public static ArrayList<Integer> getRunningApps() {
            ArrayList<Integer> list = new ArrayList<>();
            List<ActivityManager.RunningAppProcessInfo> processes = getRunningAppProcesses();
            if (processes != null && !processes.isEmpty()) {
                for (ActivityManager.RunningAppProcessInfo p : processes) {
                    if (p != null && p.pkgList != null) {
                        boolean android2 = false;
                        String[] strArr = p.pkgList;
                        int length = strArr.length;
                        int i = 0;
                        while (true) {
                            if (i >= length) {
                                break;
                            }
                            String packageName = strArr[i];
                            if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(packageName)) {
                                i++;
                            } else {
                                android2 = true;
                                break;
                            }
                        }
                        if (!android2) {
                            list.add(Integer.valueOf(p.pid));
                        }
                    }
                }
            }
            return list;
        }

        public static List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
            try {
                IActivityManager am = getActivityManager();
                if (am != null) {
                    return am.getRunningAppProcesses();
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        public static IActivityManager getActivityManager() {
            IBinder b = ServiceManager.getService("activity");
            return IActivityManager.Stub.asInterface(b);
        }

        public static IPackageManager getPackageManager() {
            IBinder b = ServiceManager.getService("package");
            return IPackageManager.Stub.asInterface(b);
        }

        public static xpPackageInfo getOverridePackageInfo(String packageName) {
            try {
                IPackageManager pm = getPackageManager();
                if (pm != null) {
                    return pm.getXpPackageInfo(packageName);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        public static boolean isAppInAudioFocus(Context context, String packageName) {
            HashMap<String, Integer> result = findMediaProcess(context);
            if (result != null && !result.isEmpty() && result.containsKey(packageName)) {
                int state = result.get(packageName).intValue();
                switch (state) {
                    case 0:
                    case 1:
                    case 2:
                    case 7:
                        return false;
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                        return true;
                    default:
                        return false;
                }
            }
            return false;
        }

        public static HashMap<String, Integer> findMediaProcess(Context context) {
            List<MediaController> sessions;
            HashMap<String, Integer> result = new HashMap<>();
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService("media_session");
            if (manager != null && (sessions = manager.getActiveSessionsForUser(null, -1)) != null && !sessions.isEmpty()) {
                for (MediaController controller : sessions) {
                    if (controller != null) {
                        String pkg = controller.getPackageName();
                        PlaybackState state = controller.getPlaybackState();
                        int stateInt = state == null ? -11 : state.getState();
                        result.put(pkg, Integer.valueOf(stateInt));
                    }
                }
            }
            return result;
        }

        public static double memSize(long pss) {
            return pss / 1024;
        }

        public static double memRadio(long pss) {
            if (ProcessManagerPolicy.sStateRecord.memTotal > 0) {
                return pss / ProcessManagerPolicy.sStateRecord.memTotal;
            }
            return 0.0d;
        }

        public static String toRadio(String prefix, double radio) {
            return prefix + String.format("%.2f", Double.valueOf(100.0d * radio)) + "%";
        }

        public static boolean highLoading(double cpu, double mem) {
            if (cpu >= ProcessManagerPolicy.sPolicyInfo.cpuMax || mem >= ProcessManagerPolicy.sPolicyInfo.memMax) {
                return true;
            }
            return false;
        }

        public static boolean processForceKilled(String packageName, double cpu, double mem) {
            if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(packageName)) {
                return false;
            }
            return cpu >= ProcessManagerPolicy.sPolicyInfo.processForceKillCpuMax || mem >= ProcessManagerPolicy.sPolicyInfo.processForceKillMemMax;
        }

        public static boolean processEmptyCached(ProcessRecord r) {
            if (r == null) {
                return false;
            }
            boolean cached = r.curProcState >= 15 && r.curProcState <= 19;
            if (cached) {
                return r.cached || r.empty;
            }
            return false;
        }

        public static boolean processHighLoading(double cpu, double mem) {
            if (cpu >= ProcessManagerPolicy.sPolicyInfo.processCpuMax || mem >= ProcessManagerPolicy.sPolicyInfo.processMemMax) {
                return true;
            }
            return false;
        }

        public static boolean processHighLoading(String packageName, double cpu, double mem) {
            HashMap<String, ProcessPolicyPackages> list;
            ProcessPolicyPackages pkg;
            if (!TextUtils.isEmpty(packageName) && (list = ProcessManagerPolicy.sPolicyInfo.processWhitelist) != null && !list.isEmpty() && list.containsKey(packageName) && (pkg = list.get(packageName)) != null) {
                if (pkg.forceBackground) {
                    return false;
                }
                return cpu >= pkg.processCpuMax || mem >= pkg.processMemMax;
            }
            return processHighLoading(cpu, mem);
        }

        public static boolean packageAllowKill(String packageName, ArrayList<ProcessRecord> lru) {
            if (lru == null || TextUtils.isEmpty(packageName)) {
                return false;
            }
            Iterator<ProcessRecord> it = lru.iterator();
            while (it.hasNext()) {
                ProcessRecord r = it.next();
                if (r != null && r.info != null && packageName.equals(r.info.packageName) && !r.isolated && !processAllowKill(r)) {
                    return false;
                }
            }
            return true;
        }

        public static boolean processAllowKill(ProcessRecord r) {
            if (r == null || r.info == null) {
                return false;
            }
            boolean persistent = r.persistent;
            if (persistent) {
                return false;
            }
            int adj = r.curAdj;
            boolean foreground = isForeground(r);
            if (foreground) {
                return false;
            }
            boolean isWhitelist = isWhitelistProcess(r.info.packageName);
            boolean isPersisted = isPersistedProcess(r.info.packageName);
            if (isWhitelist) {
                return false;
            }
            switch (adj) {
                case JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE /* -1000 */:
                case -900:
                    return false;
                case -800:
                case -700:
                    boolean isProtectedProcess = !isPersisted;
                    return isProtectedProcess;
                case 0:
                case HdmiCecKeycode.CEC_KEYCODE_PREVIOUS_CHANNEL /* 50 */:
                case 99:
                case 100:
                    boolean isProtectedProcess2 = isProtectedProcess(r.info);
                    if (foreground || isProtectedProcess2) {
                        return false;
                    }
                    return true;
                case DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE /* 200 */:
                case DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR /* 300 */:
                case 400:
                case SystemService.PHASE_SYSTEM_SERVICES_READY /* 500 */:
                case 600:
                case 700:
                case 800:
                case 900:
                case 906:
                case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE /* 1001 */:
                    return true;
                default:
                    if (adj < 200 && adj <= -900) {
                        return false;
                    }
                    return true;
            }
        }

        public static boolean isForeground(int adj) {
            return adj <= 100 && adj >= 0;
        }

        public static boolean isForeground(ProcessRecord r) {
            char c;
            boolean foreground = false;
            if (r != null) {
                int adj = r.curAdj;
                int procState = r.curProcState;
                switch (adj) {
                    case JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE /* -1000 */:
                    case -900:
                    case -800:
                    case -700:
                        break;
                    case 0:
                    case HdmiCecKeycode.CEC_KEYCODE_PREVIOUS_CHANNEL /* 50 */:
                    case 99:
                    case 100:
                        foreground = true;
                        break;
                    case DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE /* 200 */:
                    case DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR /* 300 */:
                    case 400:
                    case SystemService.PHASE_SYSTEM_SERVICES_READY /* 500 */:
                    case 600:
                    case 700:
                    case 800:
                    case 900:
                    case 906:
                    case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE /* 1001 */:
                        break;
                    default:
                        if (adj < 200 && adj >= 0) {
                            try {
                                String adjType = r.adjType;
                                if (!TextUtils.isEmpty(adjType)) {
                                    String[] strArr = ProcessManagerPolicy.FOREGROUND_ADJ_TYPES;
                                    int length = strArr.length;
                                    int i = 0;
                                    while (true) {
                                        if (i >= length) {
                                            break;
                                        } else {
                                            String val = strArr[i];
                                            if (!adjType.equals(val)) {
                                                i++;
                                            } else {
                                                foreground = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                break;
                            }
                        }
                        break;
                }
                switch (procState) {
                    case 1:
                    case 2:
                        foreground = true;
                        break;
                    case 3:
                    case 4:
                    case 5:
                        boolean isSystem = ActivityInfoManager.isSystemApplication(r.info.packageName);
                        if (isSystem) {
                            foreground = true;
                            break;
                        } else {
                            foreground = ProcessManagerPolicy.fetchBackgroundSettings(r.info.packageName) == 1;
                            break;
                        }
                }
            }
            if (foreground) {
                boolean protect = false;
                long interval = SystemClock.uptimeMillis() - r.adjTime;
                String[] strArr2 = ProcessManagerPolicy.PROTECT_ADJ_TYPES;
                int length2 = strArr2.length;
                int i2 = 0;
                while (true) {
                    if (i2 < length2) {
                        String val2 = strArr2[i2];
                        if (!val2.equals(r.adjType)) {
                            i2++;
                        } else {
                            protect = true;
                        }
                    }
                }
                if (protect && interval < 100) {
                    foreground = false;
                }
                String adjType2 = r != null ? r.adjType : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                int hashCode = adjType2.hashCode();
                if (hashCode == -1247517271) {
                    if (adjType2.equals("fg-service")) {
                        c = 1;
                    }
                    c = 65535;
                } else if (hashCode == -598911378) {
                    if (adjType2.equals("fg-service-act")) {
                        c = 3;
                    }
                    c = 65535;
                } else if (hashCode != 1150501241) {
                    if (hashCode == 1984153269 && adjType2.equals("service")) {
                        c = 0;
                    }
                    c = 65535;
                } else {
                    if (adjType2.equals("exec-service")) {
                        c = 2;
                    }
                    c = 65535;
                }
                switch (c) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                        if (r != null && r.info != null) {
                            boolean isUntrustedProcess = isUntrustedProcess(r.info);
                            if (isUntrustedProcess) {
                                xpPackageInfo xpi = getOverridePackageInfo(r.info.packageName);
                                int backgroundStatus = xpi != null ? xpi.backgroundStatus : -1;
                                boolean shouldKillDelayed = backgroundStatus != 1;
                                boolean shouldKillImmediately = backgroundStatus == 2;
                                if (shouldKillDelayed || shouldKillImmediately) {
                                    return false;
                                }
                                return foreground;
                            }
                            return foreground;
                        }
                        return foreground;
                    default:
                        return foreground;
                }
            }
            return foreground;
        }

        public static boolean isBackground(ProcessRecord r) {
            return !isForeground(r);
        }

        public static int decreaseAdj(int adj) {
            if (adj > 100) {
                return Math.min(adj + 100, 900);
            }
            return adj;
        }

        private static boolean isBackgroundKeptValid(ProcessRecord r, ArrayList<ProcessRecord> lru) {
            if (r == null || r.info == null || lru == null) {
                return false;
            }
            long delay = ProcessManagerPolicy.getProcessPolicyDelay();
            long now = SystemClock.uptimeMillis();
            Iterator<ProcessRecord> it = lru.iterator();
            while (it.hasNext()) {
                ProcessRecord p = it.next();
                if (p != null && p.info != null && (r.uid == p.uid || r.info.packageName.equals(p.info.packageName))) {
                    long backgroundTime = p.backgroundTime > 0 ? now - p.backgroundTime : 0L;
                    if (backgroundTime < delay) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean isForegroundPageValid(ProcessRecord r, ArrayList<ProcessRecord> lru) {
            int processNumber = getNumberBeforeProcess(r, lru);
            return processNumber >= 1;
        }

        private static int getNumberBeforeProcess(ProcessRecord process, ArrayList<ProcessRecord> lru) {
            if (lru != null && process != null) {
                int count = 0;
                int size = lru.size();
                int index = lru.indexOf(process);
                for (int i = index + 1; i < size; i++) {
                    ProcessRecord r = lru.get(i);
                    if (r.uid != process.uid && r != null && r.foregroundTime > 0) {
                        count++;
                    }
                }
                return count;
            }
            return 0;
        }

        private static boolean processValid(ApplicationInfo ai) {
            return (ai == null || TextUtils.isEmpty(ai.packageName)) ? false : true;
        }

        private static boolean isUntrustedProcess(ApplicationInfo ai) {
            return processValid(ai) && !isProtectedProcess(ai);
        }

        private static boolean isProtectedProcess(ApplicationInfo ai) {
            if (processValid(ai)) {
                return ai.isSystemApp() || isProtectedProcess(ai.packageName);
            }
            return false;
        }

        private static boolean isProtectedProcess(String packageName) {
            if (!TextUtils.isEmpty(packageName)) {
                if (packageName.startsWith("com.xiaopeng.") || packageName.startsWith("com.xpeng.")) {
                    return true;
                }
                return false;
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static boolean isWhitelistProcess(String packageName) {
            HashMap<String, ProcessPolicyPackages> list;
            ProcessPolicyPackages pkg;
            if (!TextUtils.isEmpty(packageName) && (list = ProcessManagerPolicy.sPolicyInfo.processWhitelist) != null && !list.isEmpty() && list.containsKey(packageName) && (pkg = list.get(packageName)) != null && pkg.forceBackground) {
                return true;
            }
            return false;
        }

        private static boolean isPersistedProcess(String packageName) {
            HashMap<String, ProcessPolicyPackages> list;
            ProcessPolicyPackages pkg;
            if (!TextUtils.isEmpty(packageName) && (list = ProcessManagerPolicy.sPolicyInfo.processWhitelist) != null && !list.isEmpty() && list.containsKey(packageName) && (pkg = list.get(packageName)) != null && pkg.forcePersistent) {
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    protected static class ProcessPolicy {
        private static final String POLICY_FILE_NAME = "xp_process_policy.json";

        protected ProcessPolicy() {
        }

        public static void loadProcessPolicy() {
            JSONArray arrays;
            String content = xpSysConfigUtil.getConfigContent(POLICY_FILE_NAME);
            if (!TextUtils.isEmpty(content)) {
                try {
                    JSONObject root = new JSONObject(content);
                    ProcessManagerPolicy.sPolicyInfo.version = xpTextUtils.toDouble(xpTextUtils.getValue("version", root), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.version)).doubleValue();
                    ProcessManagerPolicy.sPolicyInfo.product = xpTextUtils.toString(xpTextUtils.getValue("product", root));
                    if (root.has(ProcessPolicyKey.KEY_POLICIES)) {
                        JSONObject policies = root.getJSONObject(ProcessPolicyKey.KEY_POLICIES);
                        ProcessManagerPolicy.sPolicyInfo.cpuMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_CPU_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.cpuMax)).doubleValue();
                        ProcessManagerPolicy.sPolicyInfo.memMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_MEM_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.memMax)).doubleValue();
                        ProcessManagerPolicy.sPolicyInfo.processCpuMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_CPU_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.processCpuMax)).doubleValue();
                        ProcessManagerPolicy.sPolicyInfo.processMemMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_MEM_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.processMemMax)).doubleValue();
                        ProcessManagerPolicy.sPolicyInfo.processForceKillCpuMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_FORCE_KILL_CPU_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.processForceKillCpuMax)).doubleValue();
                        ProcessManagerPolicy.sPolicyInfo.processForceKillMemMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_FORCE_KILL_MEM_MAX, policies), Double.valueOf(ProcessManagerPolicy.sPolicyInfo.processForceKillMemMax)).doubleValue();
                        boolean enabled = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_POLICY_ENABLED, policies), Integer.valueOf(ProcessManagerPolicy.sPolicyInfo.policyEnabled ? 1 : 0)).intValue() == 1;
                        ProcessManagerPolicy.sPolicyInfo.cleanEmptyProcess = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_CLEAN_EMPTY_PROCESS, policies), Integer.valueOf(ProcessManagerPolicy.sPolicyInfo.cleanEmptyProcess ? 1 : 0)).intValue() == 1;
                        ProcessManagerPolicy.sPolicyInfo.warningMax = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_WARNING_MAX, policies), Integer.valueOf(ProcessManagerPolicy.sPolicyInfo.warningMax)).intValue();
                        ProcessManagerPolicy.sPolicyInfo.cachedProcessLimit = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_CACHED_PROCESS_LIMIT, policies), Integer.valueOf(ProcessManagerPolicy.sPolicyInfo.cachedProcessLimit)).intValue();
                        ProcessManagerPolicy.sPolicyInfo.killTopProcessDelay = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_KILL_TOP_PROCESS_DELAY, policies), Integer.valueOf(ProcessManagerPolicy.sPolicyInfo.killTopProcessDelay)).intValue();
                        if (policies.has(ProcessPolicyKey.KEY_WORKING_PROCESS_LIMIT)) {
                            JSONObject object = policies.getJSONObject(ProcessPolicyKey.KEY_WORKING_PROCESS_LIMIT);
                            ProcessManagerPolicy.sPolicyInfo.workingProcessLimit.cpuMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_CPU_MAX_ARRAYS, object));
                            ProcessManagerPolicy.sPolicyInfo.workingProcessLimit.memMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_MEM_MAX_ARRAYS, object));
                            ProcessManagerPolicy.sPolicyInfo.workingProcessLimit.policyArrays = xpTextUtils.toIntArray(toArray(ProcessPolicyKey.KEY_PROCESS_LIMIT, object));
                        }
                        if (policies.has(ProcessPolicyKey.KEY_PROCESS_STATS_INTERVAL)) {
                            JSONObject object2 = policies.getJSONObject(ProcessPolicyKey.KEY_PROCESS_STATS_INTERVAL);
                            ProcessManagerPolicy.sPolicyInfo.processStatsInterval.cpuMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_CPU_MAX_ARRAYS, object2));
                            ProcessManagerPolicy.sPolicyInfo.processStatsInterval.memMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_MEM_MAX_ARRAYS, object2));
                            ProcessManagerPolicy.sPolicyInfo.processStatsInterval.policyArrays = xpTextUtils.toIntArray(toArray(ProcessPolicyKey.KEY_INTERVAL_MINUTES, object2));
                        }
                        if (policies.has(ProcessPolicyKey.KEY_KILL_TOP_PROCESS_NUMBER)) {
                            JSONObject object3 = policies.getJSONObject(ProcessPolicyKey.KEY_KILL_TOP_PROCESS_NUMBER);
                            ProcessManagerPolicy.sPolicyInfo.killTopProcessNumber.cpuMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_CPU_MAX_ARRAYS, object3));
                            ProcessManagerPolicy.sPolicyInfo.killTopProcessNumber.memMaxArrays = xpTextUtils.toDoubleArray(toArray(ProcessPolicyKey.KEY_MEM_MAX_ARRAYS, object3));
                            ProcessManagerPolicy.sPolicyInfo.killTopProcessNumber.policyArrays = xpTextUtils.toIntArray(toArray(ProcessPolicyKey.KEY_KILL_NUMBER, object3));
                        }
                        if (policies.has(ProcessPolicyKey.KEY_PROCESS_WHITELIST) && (arrays = policies.getJSONArray(ProcessPolicyKey.KEY_PROCESS_WHITELIST)) != null) {
                            int length = arrays.length();
                            for (int i = 0; i < length; i++) {
                                JSONObject object4 = arrays.getJSONObject(i);
                                ProcessPolicyPackages pkg = new ProcessPolicyPackages();
                                if (object4 != null) {
                                    pkg.packageName = xpTextUtils.toString(xpTextUtils.getValue("packageName", object4));
                                    pkg.processCpuMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_CPU_MAX, object4), Double.valueOf(pkg.processCpuMax)).doubleValue();
                                    pkg.processMemMax = xpTextUtils.toDouble(xpTextUtils.getValue(ProcessPolicyKey.KEY_PROCESS_MEM_MAX, object4), Double.valueOf(pkg.processMemMax)).doubleValue();
                                    pkg.cleanType = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_CLEAN_TYPE, object4), Integer.valueOf(pkg.cleanType)).intValue();
                                    pkg.forceBackground = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_FORCE_BACKGROUND, object4), Integer.valueOf(pkg.forceBackground ? 1 : 0)).intValue() == 1;
                                    pkg.forcePersistent = xpTextUtils.toInteger(xpTextUtils.getValue(ProcessPolicyKey.KEY_FORCE_PERSISTENT, object4), Integer.valueOf(pkg.forcePersistent ? 1 : 0)).intValue() == 1;
                                    ProcessManagerPolicy.sPolicyInfo.processWhitelist.put(pkg.packageName, pkg);
                                }
                            }
                        }
                        ProcessManagerPolicy.sPolicyInfo.policyEnabled = enabled;
                    }
                    ProcessManagerPolicy.sPolicyInfo.init = true;
                    ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "loadProcessPolicy completed content:" + ProcessManagerPolicy.sPolicyInfo.toString());
                } catch (Exception e) {
                    ProcessManagerPolicy.sPolicyInfo.policyEnabled = false;
                    ProcessManagerPolicy.log(ProcessManagerPolicy.TAG, "loadProcessPolicy e=" + e);
                }
            }
        }

        public static String[] toArray(String name, JSONObject object) {
            String content = xpTextUtils.toString(xpTextUtils.getValue(name, object));
            return xpTextUtils.toStringArray(content, ",");
        }
    }

    /* loaded from: classes.dex */
    protected static class ProcessPolicyKey {
        public static final String KEY_CACHED_PROCESS_LIMIT = "cachedProcessLimit";
        public static final String KEY_CLEAN_EMPTY_PROCESS = "cleanEmptyProcess";
        public static final String KEY_CLEAN_TYPE = "cleanType";
        public static final String KEY_CPU_MAX = "cpuMax";
        public static final String KEY_CPU_MAX_ARRAYS = "cpuMaxArrays";
        public static final String KEY_FORCE_BACKGROUND = "forceBackground";
        public static final String KEY_FORCE_PERSISTENT = "forcePersistent";
        public static final String KEY_INTERVAL_MINUTES = "intervalMinutes";
        public static final String KEY_KILL_NUMBER = "killNumber";
        public static final String KEY_KILL_TOP_PROCESS_DELAY = "killTopProcessDelay";
        public static final String KEY_KILL_TOP_PROCESS_NUMBER = "killTopProcessNumber";
        public static final String KEY_MEM_MAX = "memMax";
        public static final String KEY_MEM_MAX_ARRAYS = "memMaxArrays";
        public static final String KEY_PACKAGE = "package";
        public static final String KEY_PACKAGE_NAME = "packageName";
        public static final String KEY_POLICIES = "policies";
        public static final String KEY_POLICY_ENABLED = "policyEnabled";
        public static final String KEY_PROCESS_CPU_MAX = "processCpuMax";
        public static final String KEY_PROCESS_FORCE_KILL_CPU_MAX = "processForceKillCpuMax";
        public static final String KEY_PROCESS_FORCE_KILL_MEM_MAX = "processForceKillMemMax";
        public static final String KEY_PROCESS_LIMIT = "processLimit";
        public static final String KEY_PROCESS_MEM_MAX = "processMemMax";
        public static final String KEY_PROCESS_STATS_INTERVAL = "processStatsInterval";
        public static final String KEY_PROCESS_WHITELIST = "processWhitelist";
        public static final String KEY_PRODUCT = "product";
        public static final String KEY_VERSION = "version";
        public static final String KEY_WARNING_MAX = "warningMax";
        public static final String KEY_WORKING_PROCESS_LIMIT = "workingProcessLimit";

        protected ProcessPolicyKey() {
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public static class ProcessPolicyInfo {
        public boolean init = false;
        public double version = 0.0d;
        public String product = "xp";
        public double cpuMax = 0.6d;
        public double memMax = 0.6d;
        public double processCpuMax = 0.2d;
        public double processMemMax = 400.0d;
        public double processForceKillCpuMax = 2.0d;
        public double processForceKillMemMax = 1500.0d;
        public int warningMax = 3;
        public boolean policyEnabled = false;
        public boolean cleanEmptyProcess = false;
        public int cachedProcessLimit = 16;
        public int killTopProcessDelay = 3;
        public ProcessPolicyArrays workingProcessLimit = new ProcessPolicyArrays();
        public ProcessPolicyArrays processStatsInterval = new ProcessPolicyArrays();
        public ProcessPolicyArrays killTopProcessNumber = new ProcessPolicyArrays();
        public HashMap<String, ProcessPolicyPackages> processWhitelist = new HashMap<>();

        protected ProcessPolicyInfo() {
        }

        public String toString() {
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            buffer.append("\n");
            buffer.append("ProcessPolicyInfo");
            buffer.append("{");
            buffer.append(" version=" + this.version);
            buffer.append(" cpuMax=" + this.cpuMax);
            buffer.append(" memMax=" + this.memMax);
            buffer.append(" processCpuMax=" + this.processCpuMax);
            buffer.append(" processMemMax=" + this.processMemMax);
            buffer.append(" policyEnabled=" + this.policyEnabled);
            buffer.append(" warningMax=" + this.warningMax);
            buffer.append(" cachedProcessLimit=" + this.cachedProcessLimit);
            buffer.append(" killTopProcessDelay=" + this.killTopProcessDelay);
            buffer.append(" }");
            buffer.append("\n");
            buffer.append(ProcessPolicyKey.KEY_WORKING_PROCESS_LIMIT);
            buffer.append("{");
            buffer.append(" cpuMaxArrays=" + Arrays.toString(this.workingProcessLimit.cpuMaxArrays));
            buffer.append(" memMaxArrays=" + Arrays.toString(this.workingProcessLimit.memMaxArrays));
            buffer.append(" processLimit=" + Arrays.toString(this.workingProcessLimit.policyArrays));
            buffer.append(" }");
            buffer.append("\n");
            buffer.append(ProcessPolicyKey.KEY_PROCESS_STATS_INTERVAL);
            buffer.append("{");
            buffer.append(" cpuMaxArrays=" + Arrays.toString(this.processStatsInterval.cpuMaxArrays));
            buffer.append(" memMaxArrays=" + Arrays.toString(this.processStatsInterval.memMaxArrays));
            buffer.append(" intervalMinutes=" + Arrays.toString(this.processStatsInterval.policyArrays));
            buffer.append(" }");
            buffer.append("\n");
            buffer.append(ProcessPolicyKey.KEY_KILL_TOP_PROCESS_NUMBER);
            buffer.append("{");
            buffer.append(" cpuMaxArrays=" + Arrays.toString(this.killTopProcessNumber.cpuMaxArrays));
            buffer.append(" memMaxArrays=" + Arrays.toString(this.killTopProcessNumber.memMaxArrays));
            buffer.append(" killNumber=" + Arrays.toString(this.killTopProcessNumber.policyArrays));
            buffer.append(" }");
            return buffer.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public static class ProcessPolicyArrays {
        public double[] cpuMaxArrays;
        public double[] memMaxArrays;
        public int[] policyArrays;

        protected ProcessPolicyArrays() {
        }

        public static int findIndex(double value, double[] arrays) {
            int length = arrays != null ? arrays.length : 0;
            for (int i = length - 1; i >= 0; i--) {
                try {
                    if (value > arrays[i]) {
                        return i;
                    }
                } catch (Exception e) {
                }
            }
            return 0;
        }

        public static int matchPolicy(double cpu, double mem, ProcessPolicyArrays policy) {
            if (policy != null) {
                try {
                    int cpuIndex = findIndex(cpu, policy.cpuMaxArrays);
                    int memIndex = findIndex(mem, policy.memMaxArrays);
                    int index = Math.max(cpuIndex, memIndex);
                    return policy.policyArrays[index];
                } catch (Exception e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public static class ProcessPolicyPackages {
        public static final int TYPE_CLEAN_FORCE_STOP = 2;
        public static final int TYPE_CLEAN_KILL_GROUP = 1;
        public int cleanType = 1;
        public boolean forceBackground;
        public boolean forcePersistent;
        public String packageName;
        public double processCpuMax;
        public double processMemMax;

        protected ProcessPolicyPackages() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class WorkHandler extends Handler {
        public static final int MSG_PROCESS_STATS = 1000;

        public WorkHandler(Looper looper) {
            super(looper, null);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 1000) {
                ProcessManagerPolicy.this.calculateProcessStatsLocked();
            }
        }
    }
}
