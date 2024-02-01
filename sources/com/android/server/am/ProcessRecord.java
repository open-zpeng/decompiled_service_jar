package com.android.server.am;

import android.app.ActivityManager;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.VersionedPackage;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.Zygote;
import com.android.server.UiModeManagerService;
import com.android.server.Watchdog;
import com.android.server.am.AppNotRespondingDialog;
import com.android.server.am.ProcessList;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.wm.WindowProcessController;
import com.android.server.wm.WindowProcessListener;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ProcessRecord implements WindowProcessListener {
    private static final String TAG = "ActivityManager";
    int adjSeq;
    Object adjSource;
    int adjSourceProcState;
    Object adjTarget;
    String adjType;
    int adjTypeCode;
    Dialog anrDialog;
    final boolean appZygote;
    volatile long backgroundTime;
    boolean bad;
    ProcessState baseProcessTracker;
    boolean cached;
    volatile long cachedEmptyTime;
    CompatibilityInfo compat;
    int completedAdjSeq;
    int connectionGroup;
    int connectionImportance;
    ServiceRecord connectionService;
    boolean containsCycle;
    Dialog crashDialog;
    Runnable crashHandler;
    ActivityManager.ProcessErrorStateInfo crashingReport;
    int curAdj;
    long curCpuTime;
    BatteryStatsImpl.Uid.Proc curProcBatteryStats;
    IBinder.DeathRecipient deathRecipient;
    boolean empty;
    ComponentName errorReportReceiver;
    boolean execServicesFg;
    boolean forceCrashReport;
    Object forcingToImportant;
    volatile long foregroundTime;
    int[] gids;
    boolean hasAboveClient;
    boolean hasShownUi;
    boolean hasStartedServices;
    HostingRecord hostingRecord;
    public boolean inFullBackup;
    final ApplicationInfo info;
    long initialIdlePss;
    String instructionSet;
    final boolean isolated;
    String isolatedEntryPoint;
    String[] isolatedEntryPointArgs;
    boolean killed;
    boolean killedByAm;
    long lastActivityTime;
    long lastCachedPss;
    long lastCachedSwapPss;
    int lastCompactAction;
    long lastCompactTime;
    long lastCpuTime;
    long lastLowMemory;
    Debug.MemoryInfo lastMemInfo;
    long lastMemInfoTime;
    long lastProviderTime;
    long lastPss;
    long lastPssTime;
    long lastRequestedGc;
    long lastStateTime;
    long lastSwapPss;
    long lastTopTime;
    int lruSeq;
    private boolean mCrashing;
    private int mCurRawAdj;
    private int mCurSchedGroup;
    private boolean mDebugging;
    private long mFgInteractionTime;
    private int mFgServiceTypes;
    private boolean mHasClientActivities;
    private boolean mHasForegroundActivities;
    private boolean mHasForegroundServices;
    private boolean mHasOverlayUi;
    private boolean mHasTopUi;
    private ActiveInstrumentation mInstr;
    private long mInteractionEventTime;
    private boolean mNotResponding;
    private boolean mPendingUiClean;
    boolean mPersistent;
    private int mRepFgServiceTypes;
    private String mRequiredAbi;
    private final ActivityManagerService mService;
    private boolean mUsingWrapper;
    private long mWhenUnimportant;
    private final WindowProcessController mWindowProcessController;
    int maxAdj;
    int mountMode;
    long nextPssTime;
    boolean notCachedSinceIdle;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;
    boolean pendingStart;
    int pid;
    ArraySet<String> pkgDeps;
    String procStatFile;
    boolean procStateChanged;
    final String processName;
    int pssStatType;
    volatile boolean removed;
    int renderThreadTid;
    boolean repForegroundActivities;
    boolean reportLowMemory;
    boolean reportedInteraction;
    int reqCompactAction;
    boolean runningRemoteAnimation;
    int savedPriority;
    String seInfo;
    boolean serviceHighRam;
    boolean serviceb;
    int setAdj;
    int setRawAdj;
    int setSchedGroup;
    String shortStringName;
    long startSeq;
    long startTime;
    int startUid;
    boolean starting;
    String stringName;
    boolean systemNoUi;
    IApplicationThread thread;
    boolean treatLikeActivity;
    int trimMemoryLevel;
    final int uid;
    UidRecord uidRecord;
    boolean unlocked;
    final int userId;
    int verifiedAdj;
    Dialog waitDialog;
    boolean waitedForDebugger;
    String waitingToKill;
    boolean whitelistManager;
    final PackageList pkgList = new PackageList();
    final ProcessList.ProcStateMemTracker procStateMemTracker = new ProcessList.ProcStateMemTracker();
    int mCurProcState = 21;
    private int mRepProcState = 21;
    private int mCurRawProcState = 21;
    int setProcState = 21;
    int pssProcState = 21;
    final ArraySet<BroadcastRecord> curReceivers = new ArraySet<>();
    final ArraySet<ServiceRecord> services = new ArraySet<>();
    final ArraySet<ServiceRecord> executingServices = new ArraySet<>();
    final ArraySet<ConnectionRecord> connections = new ArraySet<>();
    final ArraySet<ReceiverList> receivers = new ArraySet<>();
    final ArrayMap<String, ContentProviderRecord> pubProviders = new ArrayMap<>();
    final ArrayList<ContentProviderConnection> conProviders = new ArrayList<>();
    final ArraySet<Binder> mAllowBackgroundActivityStartsTokens = new ArraySet<>();
    private ArraySet<Integer> mBoundClientUids = new ArraySet<>();
    volatile int lastAdj = 0;
    volatile int killRecord = 0;
    volatile long adjTime = 0;
    volatile int oomMem = -1;
    volatile int lastPid = 0;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class PackageList {
        final ArrayMap<String, ProcessStats.ProcessStateHolder> mPkgList = new ArrayMap<>();

        PackageList() {
        }

        ProcessStats.ProcessStateHolder put(String key, ProcessStats.ProcessStateHolder value) {
            ProcessRecord.this.mWindowProcessController.addPackage(key);
            return this.mPkgList.put(key, value);
        }

        void clear() {
            this.mPkgList.clear();
            ProcessRecord.this.mWindowProcessController.clearPackageList();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public int size() {
            return this.mPkgList.size();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public String keyAt(int index) {
            return this.mPkgList.keyAt(index);
        }

        public ProcessStats.ProcessStateHolder valueAt(int index) {
            return this.mPkgList.valueAt(index);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProcessStats.ProcessStateHolder get(String pkgName) {
            return this.mPkgList.get(pkgName);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public boolean containsKey(Object key) {
            return this.mPkgList.containsKey(key);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStartParams(int startUid, HostingRecord hostingRecord, String seInfo, long startTime) {
        this.startUid = startUid;
        this.hostingRecord = hostingRecord;
        this.seInfo = seInfo;
        this.startTime = startTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        long nowUptime = SystemClock.uptimeMillis();
        pw.print(prefix);
        pw.print("user #");
        pw.print(this.userId);
        pw.print(" uid=");
        pw.print(this.info.uid);
        if (this.uid != this.info.uid) {
            pw.print(" ISOLATED uid=");
            pw.print(this.uid);
        }
        pw.print(" gids={");
        if (this.gids != null) {
            for (int gi = 0; gi < this.gids.length; gi++) {
                if (gi != 0) {
                    pw.print(", ");
                }
                pw.print(this.gids[gi]);
            }
        }
        pw.println("}");
        pw.print(prefix);
        pw.print("mRequiredAbi=");
        pw.print(this.mRequiredAbi);
        pw.print(" instructionSet=");
        pw.println(this.instructionSet);
        if (this.info.className != null) {
            pw.print(prefix);
            pw.print("class=");
            pw.println(this.info.className);
        }
        if (this.info.manageSpaceActivityName != null) {
            pw.print(prefix);
            pw.print("manageSpaceActivityName=");
            pw.println(this.info.manageSpaceActivityName);
        }
        pw.print(prefix);
        pw.print("dir=");
        pw.print(this.info.sourceDir);
        pw.print(" publicDir=");
        pw.print(this.info.publicSourceDir);
        pw.print(" data=");
        pw.println(this.info.dataDir);
        pw.print(prefix);
        pw.print("packageList={");
        for (int i = 0; i < this.pkgList.size(); i++) {
            if (i > 0) {
                pw.print(", ");
            }
            pw.print(this.pkgList.keyAt(i));
        }
        pw.println("}");
        if (this.pkgDeps != null) {
            pw.print(prefix);
            pw.print("packageDependencies={");
            for (int i2 = 0; i2 < this.pkgDeps.size(); i2++) {
                if (i2 > 0) {
                    pw.print(", ");
                }
                pw.print(this.pkgDeps.valueAt(i2));
            }
            pw.println("}");
        }
        pw.print(prefix);
        pw.print("compat=");
        pw.println(this.compat);
        if (this.mInstr != null) {
            pw.print(prefix);
            pw.print("mInstr=");
            pw.println(this.mInstr);
        }
        pw.print(prefix);
        pw.print("thread=");
        pw.println(this.thread);
        pw.print(prefix);
        pw.print("pid=");
        pw.print(this.pid);
        pw.print(" starting=");
        pw.println(this.starting);
        pw.print(prefix);
        pw.print("lastActivityTime=");
        TimeUtils.formatDuration(this.lastActivityTime, nowUptime, pw);
        pw.print(" lastPssTime=");
        TimeUtils.formatDuration(this.lastPssTime, nowUptime, pw);
        pw.print(" pssStatType=");
        pw.print(this.pssStatType);
        pw.print(" nextPssTime=");
        TimeUtils.formatDuration(this.nextPssTime, nowUptime, pw);
        pw.println();
        pw.print(prefix);
        pw.print("adjSeq=");
        pw.print(this.adjSeq);
        pw.print(" lruSeq=");
        pw.print(this.lruSeq);
        pw.print(" lastPss=");
        DebugUtils.printSizeValue(pw, this.lastPss * 1024);
        pw.print(" lastSwapPss=");
        DebugUtils.printSizeValue(pw, this.lastSwapPss * 1024);
        pw.print(" lastCachedPss=");
        DebugUtils.printSizeValue(pw, this.lastCachedPss * 1024);
        pw.print(" lastCachedSwapPss=");
        DebugUtils.printSizeValue(pw, this.lastCachedSwapPss * 1024);
        pw.println();
        pw.print(prefix);
        pw.print("procStateMemTracker: ");
        this.procStateMemTracker.dumpLine(pw);
        pw.print(prefix);
        pw.print("cached=");
        pw.print(this.cached);
        pw.print(" empty=");
        pw.println(this.empty);
        if (this.serviceb) {
            pw.print(prefix);
            pw.print("serviceb=");
            pw.print(this.serviceb);
            pw.print(" serviceHighRam=");
            pw.println(this.serviceHighRam);
        }
        if (this.notCachedSinceIdle) {
            pw.print(prefix);
            pw.print("notCachedSinceIdle=");
            pw.print(this.notCachedSinceIdle);
            pw.print(" initialIdlePss=");
            pw.println(this.initialIdlePss);
        }
        pw.print(prefix);
        pw.print("oom: max=");
        pw.print(this.maxAdj);
        pw.print(" curRaw=");
        pw.print(this.mCurRawAdj);
        pw.print(" setRaw=");
        pw.print(this.setRawAdj);
        pw.print(" cur=");
        pw.print(this.curAdj);
        pw.print(" set=");
        pw.println(this.setAdj);
        pw.print(prefix);
        pw.print("lastCompactTime=");
        pw.print(this.lastCompactTime);
        pw.print(" lastCompactAction=");
        pw.print(this.lastCompactAction);
        pw.print(prefix);
        pw.print("mCurSchedGroup=");
        pw.print(this.mCurSchedGroup);
        pw.print(" setSchedGroup=");
        pw.print(this.setSchedGroup);
        pw.print(" systemNoUi=");
        pw.print(this.systemNoUi);
        pw.print(" trimMemoryLevel=");
        pw.println(this.trimMemoryLevel);
        pw.print(prefix);
        pw.print("curProcState=");
        pw.print(getCurProcState());
        pw.print(" mRepProcState=");
        pw.print(this.mRepProcState);
        pw.print(" pssProcState=");
        pw.print(this.pssProcState);
        pw.print(" setProcState=");
        pw.print(this.setProcState);
        pw.print(" lastStateTime=");
        TimeUtils.formatDuration(this.lastStateTime, nowUptime, pw);
        pw.println();
        if (this.hasShownUi || this.mPendingUiClean || this.hasAboveClient || this.treatLikeActivity) {
            pw.print(prefix);
            pw.print("hasShownUi=");
            pw.print(this.hasShownUi);
            pw.print(" pendingUiClean=");
            pw.print(this.mPendingUiClean);
            pw.print(" hasAboveClient=");
            pw.print(this.hasAboveClient);
            pw.print(" treatLikeActivity=");
            pw.println(this.treatLikeActivity);
        }
        if (this.connectionService != null || this.connectionGroup != 0) {
            pw.print(prefix);
            pw.print("connectionGroup=");
            pw.print(this.connectionGroup);
            pw.print(" Importance=");
            pw.print(this.connectionImportance);
            pw.print(" Service=");
            pw.println(this.connectionService);
        }
        if (hasTopUi() || hasOverlayUi() || this.runningRemoteAnimation) {
            pw.print(prefix);
            pw.print("hasTopUi=");
            pw.print(hasTopUi());
            pw.print(" hasOverlayUi=");
            pw.print(hasOverlayUi());
            pw.print(" runningRemoteAnimation=");
            pw.println(this.runningRemoteAnimation);
        }
        if (this.mHasForegroundServices || this.forcingToImportant != null) {
            pw.print(prefix);
            pw.print("mHasForegroundServices=");
            pw.print(this.mHasForegroundServices);
            pw.print(" forcingToImportant=");
            pw.println(this.forcingToImportant);
        }
        if (this.reportedInteraction || this.mFgInteractionTime != 0) {
            pw.print(prefix);
            pw.print("reportedInteraction=");
            pw.print(this.reportedInteraction);
            if (this.mInteractionEventTime != 0) {
                pw.print(" time=");
                TimeUtils.formatDuration(this.mInteractionEventTime, SystemClock.elapsedRealtime(), pw);
            }
            if (this.mFgInteractionTime != 0) {
                pw.print(" fgInteractionTime=");
                TimeUtils.formatDuration(this.mFgInteractionTime, SystemClock.elapsedRealtime(), pw);
            }
            pw.println();
        }
        if (this.mPersistent || this.removed) {
            pw.print(prefix);
            pw.print("persistent=");
            pw.print(this.mPersistent);
            pw.print(" removed=");
            pw.println(this.removed);
        }
        if (this.mHasClientActivities || this.mHasForegroundActivities || this.repForegroundActivities) {
            pw.print(prefix);
            pw.print("hasClientActivities=");
            pw.print(this.mHasClientActivities);
            pw.print(" foregroundActivities=");
            pw.print(this.mHasForegroundActivities);
            pw.print(" (rep=");
            pw.print(this.repForegroundActivities);
            pw.println(")");
        }
        if (this.lastProviderTime > 0) {
            pw.print(prefix);
            pw.print("lastProviderTime=");
            TimeUtils.formatDuration(this.lastProviderTime, nowUptime, pw);
            pw.println();
        }
        if (this.lastTopTime > 0) {
            pw.print(prefix);
            pw.print("lastTopTime=");
            TimeUtils.formatDuration(this.lastTopTime, nowUptime, pw);
            pw.println();
        }
        if (this.hasStartedServices) {
            pw.print(prefix);
            pw.print("hasStartedServices=");
            pw.println(this.hasStartedServices);
        }
        if (this.pendingStart) {
            pw.print(prefix);
            pw.print("pendingStart=");
            pw.println(this.pendingStart);
        }
        pw.print(prefix);
        pw.print("startSeq=");
        pw.println(this.startSeq);
        pw.print(prefix);
        pw.print("mountMode=");
        pw.println(DebugUtils.valueToString(Zygote.class, "MOUNT_EXTERNAL_", this.mountMode));
        if (this.setProcState > 11) {
            pw.print(prefix);
            pw.print("lastCpuTime=");
            pw.print(this.lastCpuTime);
            if (this.lastCpuTime > 0) {
                pw.print(" timeUsed=");
                TimeUtils.formatDuration(this.curCpuTime - this.lastCpuTime, pw);
            }
            pw.print(" whenUnimportant=");
            TimeUtils.formatDuration(this.mWhenUnimportant - nowUptime, pw);
            pw.println();
        }
        pw.print(prefix);
        pw.print("lastRequestedGc=");
        TimeUtils.formatDuration(this.lastRequestedGc, nowUptime, pw);
        pw.print(" lastLowMemory=");
        TimeUtils.formatDuration(this.lastLowMemory, nowUptime, pw);
        pw.print(" reportLowMemory=");
        pw.println(this.reportLowMemory);
        if (this.killed || this.killedByAm || this.waitingToKill != null) {
            pw.print(prefix);
            pw.print("killed=");
            pw.print(this.killed);
            pw.print(" killedByAm=");
            pw.print(this.killedByAm);
            pw.print(" waitingToKill=");
            pw.println(this.waitingToKill);
        }
        if (this.mDebugging || this.mCrashing || this.crashDialog != null || this.mNotResponding || this.anrDialog != null || this.bad) {
            pw.print(prefix);
            pw.print("mDebugging=");
            pw.print(this.mDebugging);
            pw.print(" mCrashing=");
            pw.print(this.mCrashing);
            pw.print(" ");
            pw.print(this.crashDialog);
            pw.print(" mNotResponding=");
            pw.print(this.mNotResponding);
            pw.print(" ");
            pw.print(this.anrDialog);
            pw.print(" bad=");
            pw.print(this.bad);
            if (this.errorReportReceiver != null) {
                pw.print(" errorReportReceiver=");
                pw.print(this.errorReportReceiver.flattenToShortString());
            }
            pw.println();
        }
        if (this.whitelistManager) {
            pw.print(prefix);
            pw.print("whitelistManager=");
            pw.println(this.whitelistManager);
        }
        if (this.isolatedEntryPoint != null || this.isolatedEntryPointArgs != null) {
            pw.print(prefix);
            pw.print("isolatedEntryPoint=");
            pw.println(this.isolatedEntryPoint);
            pw.print(prefix);
            pw.print("isolatedEntryPointArgs=");
            pw.println(Arrays.toString(this.isolatedEntryPointArgs));
        }
        this.mWindowProcessController.dump(pw, prefix);
        if (this.services.size() > 0) {
            pw.print(prefix);
            pw.println("Services:");
            for (int i3 = 0; i3 < this.services.size(); i3++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.services.valueAt(i3));
            }
        }
        if (this.executingServices.size() > 0) {
            pw.print(prefix);
            pw.print("Executing Services (fg=");
            pw.print(this.execServicesFg);
            pw.println(")");
            for (int i4 = 0; i4 < this.executingServices.size(); i4++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.executingServices.valueAt(i4));
            }
        }
        if (this.connections.size() > 0) {
            pw.print(prefix);
            pw.println("Connections:");
            for (int i5 = 0; i5 < this.connections.size(); i5++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.connections.valueAt(i5));
            }
        }
        if (this.pubProviders.size() > 0) {
            pw.print(prefix);
            pw.println("Published Providers:");
            for (int i6 = 0; i6 < this.pubProviders.size(); i6++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.pubProviders.keyAt(i6));
                pw.print(prefix);
                pw.print("    -> ");
                pw.println(this.pubProviders.valueAt(i6));
            }
        }
        if (this.conProviders.size() > 0) {
            pw.print(prefix);
            pw.println("Connected Providers:");
            for (int i7 = 0; i7 < this.conProviders.size(); i7++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.conProviders.get(i7).toShortString());
            }
        }
        if (!this.curReceivers.isEmpty()) {
            pw.print(prefix);
            pw.println("Current Receivers:");
            for (int i8 = 0; i8 < this.curReceivers.size(); i8++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.curReceivers.valueAt(i8));
            }
        }
        if (this.receivers.size() > 0) {
            pw.print(prefix);
            pw.println("Receivers:");
            for (int i9 = 0; i9 < this.receivers.size(); i9++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.receivers.valueAt(i9));
            }
        }
        if (this.mAllowBackgroundActivityStartsTokens.size() > 0) {
            pw.print(prefix);
            pw.println("Background activity start whitelist tokens:");
            for (int i10 = 0; i10 < this.mAllowBackgroundActivityStartsTokens.size(); i10++) {
                pw.print(prefix);
                pw.print("  - ");
                pw.println(this.mAllowBackgroundActivityStartsTokens.valueAt(i10));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessRecord(ActivityManagerService _service, ApplicationInfo _info, String _processName, int _uid) {
        this.mService = _service;
        this.info = _info;
        boolean z = true;
        this.isolated = _info.uid != _uid;
        this.appZygote = (UserHandle.getAppId(_uid) < 90000 || UserHandle.getAppId(_uid) > 98999) ? false : z;
        this.uid = _uid;
        this.userId = UserHandle.getUserId(_uid);
        this.processName = _processName;
        this.maxAdj = NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE;
        this.setRawAdj = -10000;
        this.mCurRawAdj = -10000;
        this.verifiedAdj = -10000;
        this.setAdj = -10000;
        this.curAdj = -10000;
        this.mPersistent = false;
        this.removed = false;
        long uptimeMillis = SystemClock.uptimeMillis();
        this.nextPssTime = uptimeMillis;
        this.lastPssTime = uptimeMillis;
        this.lastStateTime = uptimeMillis;
        this.mWindowProcessController = new WindowProcessController(this.mService.mActivityTaskManager, this.info, this.processName, this.uid, this.userId, this, this);
        this.pkgList.put(_info.packageName, new ProcessStats.ProcessStateHolder(_info.longVersionCode));
    }

    public void setPid(int _pid) {
        this.pid = _pid;
        this.mWindowProcessController.setPid(this.pid);
        this.procStatFile = null;
        this.shortStringName = null;
        this.stringName = null;
    }

    public void makeActive(IApplicationThread _thread, ProcessStatsService tracker) {
        if (this.thread == null) {
            ProcessState origBase = this.baseProcessTracker;
            if (origBase != null) {
                origBase.setState(-1, tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), this.pkgList.mPkgList);
                for (int ipkg = this.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                    StatsLog.write(3, this.uid, this.processName, this.pkgList.keyAt(ipkg), ActivityManager.processStateAmToProto(-1), this.pkgList.valueAt(ipkg).appVersion);
                }
                origBase.makeInactive();
            }
            this.baseProcessTracker = tracker.getProcessStateLocked(this.info.packageName, this.info.uid, this.info.longVersionCode, this.processName);
            this.baseProcessTracker.makeActive();
            for (int i = 0; i < this.pkgList.size(); i++) {
                ProcessStats.ProcessStateHolder holder = this.pkgList.valueAt(i);
                if (holder.state != null && holder.state != origBase) {
                    holder.state.makeInactive();
                }
                tracker.updateProcessStateHolderLocked(holder, this.pkgList.keyAt(i), this.info.uid, this.info.longVersionCode, this.processName);
                if (holder.state != this.baseProcessTracker) {
                    holder.state.makeActive();
                }
            }
        }
        this.thread = _thread;
        this.mWindowProcessController.setThread(this.thread);
    }

    public void makeInactive(ProcessStatsService tracker) {
        this.thread = null;
        this.mWindowProcessController.setThread(null);
        ProcessState origBase = this.baseProcessTracker;
        if (origBase != null) {
            origBase.setState(-1, tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), this.pkgList.mPkgList);
            for (int ipkg = this.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                StatsLog.write(3, this.uid, this.processName, this.pkgList.keyAt(ipkg), ActivityManager.processStateAmToProto(-1), this.pkgList.valueAt(ipkg).appVersion);
            }
            origBase.makeInactive();
            this.baseProcessTracker = null;
            for (int i = 0; i < this.pkgList.size(); i++) {
                ProcessStats.ProcessStateHolder holder = this.pkgList.valueAt(i);
                if (holder.state != null && holder.state != origBase) {
                    holder.state.makeInactive();
                }
                holder.pkg = null;
                holder.state = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasActivities() {
        return this.mWindowProcessController.hasActivities();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasActivitiesOrRecentTasks() {
        return this.mWindowProcessController.hasActivitiesOrRecentTasks();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasRecentTasks() {
        return this.mWindowProcessController.hasRecentTasks();
    }

    public boolean isInterestingToUserLocked() {
        if (this.mWindowProcessController.isInterestingToUser()) {
            return true;
        }
        int servicesSize = this.services.size();
        for (int i = 0; i < servicesSize; i++) {
            ServiceRecord r = this.services.valueAt(i);
            if (r.isForeground) {
                return true;
            }
        }
        return false;
    }

    public void unlinkDeathRecipient() {
        IApplicationThread iApplicationThread;
        if (this.deathRecipient != null && (iApplicationThread = this.thread) != null) {
            iApplicationThread.asBinder().unlinkToDeath(this.deathRecipient, 0);
        }
        this.deathRecipient = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateHasAboveClientLocked() {
        this.hasAboveClient = false;
        for (int i = this.connections.size() - 1; i >= 0; i--) {
            ConnectionRecord cr = this.connections.valueAt(i);
            if ((cr.flags & 8) != 0) {
                this.hasAboveClient = true;
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int modifyRawOomAdj(int adj) {
        if (this.hasAboveClient && adj >= 0) {
            if (adj < 100) {
                return 100;
            }
            if (adj < 200) {
                return 200;
            }
            if (adj < 250) {
                return 250;
            }
            if (adj < 900) {
                return 900;
            }
            if (adj < 999) {
                return adj + 1;
            }
            return adj;
        }
        return adj;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleCrash(String message) {
        if (!this.killedByAm && this.thread != null) {
            if (this.pid == Process.myPid()) {
                Slog.w(TAG, "scheduleCrash: trying to crash system process!");
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                try {
                    this.thread.scheduleCrash(message);
                } catch (RemoteException e) {
                    kill("scheduleCrash for '" + message + "' failed", true);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void kill(String reason, boolean noisy) {
        if (!this.killedByAm) {
            Trace.traceBegin(64L, "kill");
            if (this.mService != null && (noisy || this.info.uid == this.mService.mCurOomAdjUid)) {
                ActivityManagerService activityManagerService = this.mService;
                activityManagerService.reportUidInfoMessageLocked(TAG, "Killing " + toShortString() + " (adj " + this.setAdj + "): " + reason, this.info.uid);
            }
            if (this.pid > 0) {
                EventLog.writeEvent((int) EventLogTags.AM_KILL, Integer.valueOf(this.userId), Integer.valueOf(this.pid), this.processName, Integer.valueOf(this.setAdj), reason);
                Process.killProcessQuiet(this.pid);
                ProcessList.killProcessGroup(this.uid, this.pid);
            } else {
                this.pendingStart = false;
            }
            if (!this.mPersistent) {
                this.killed = true;
                this.killedByAm = true;
            }
            Trace.traceEnd(64L);
        }
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        writeToProto(proto, fieldId, -1);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId, int lruIndex) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, this.pid);
        proto.write(1138166333442L, this.processName);
        proto.write(1120986464259L, this.info.uid);
        if (UserHandle.getAppId(this.info.uid) >= 10000) {
            proto.write(1120986464260L, this.userId);
            proto.write(1120986464261L, UserHandle.getAppId(this.info.uid));
        }
        if (this.uid != this.info.uid) {
            proto.write(1120986464262L, UserHandle.getAppId(this.uid));
        }
        proto.write(1133871366151L, this.mPersistent);
        if (lruIndex >= 0) {
            proto.write(1120986464264L, lruIndex);
        }
        proto.end(token);
    }

    public String toShortString() {
        String str = this.shortStringName;
        if (str != null) {
            return str;
        }
        StringBuilder sb = new StringBuilder(128);
        toShortString(sb);
        String sb2 = sb.toString();
        this.shortStringName = sb2;
        return sb2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void toShortString(StringBuilder sb) {
        sb.append(this.pid);
        sb.append(':');
        sb.append(this.processName);
        sb.append('/');
        if (this.info.uid < 10000) {
            sb.append(this.uid);
            return;
        }
        sb.append('u');
        sb.append(this.userId);
        int appId = UserHandle.getAppId(this.info.uid);
        if (appId >= 10000) {
            sb.append('a');
            sb.append(appId - 10000);
        } else {
            sb.append('s');
            sb.append(appId);
        }
        if (this.uid != this.info.uid) {
            sb.append('i');
            sb.append(UserHandle.getAppId(this.uid) - 99000);
        }
    }

    public String toString() {
        String str = this.stringName;
        if (str != null) {
            return str;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ProcessRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        toShortString(sb);
        sb.append('}');
        String sb2 = sb.toString();
        this.stringName = sb2;
        return sb2;
    }

    public String makeAdjReason() {
        if (this.adjSource != null || this.adjTarget != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(' ');
            Object obj = this.adjTarget;
            if (obj instanceof ComponentName) {
                sb.append(((ComponentName) obj).flattenToShortString());
            } else if (obj != null) {
                sb.append(obj.toString());
            } else {
                sb.append("{null}");
            }
            sb.append("<=");
            Object obj2 = this.adjSource;
            if (obj2 instanceof ProcessRecord) {
                sb.append("Proc{");
                sb.append(((ProcessRecord) this.adjSource).toShortString());
                sb.append("}");
            } else if (obj2 != null) {
                sb.append(obj2.toString());
            } else {
                sb.append("{null}");
            }
            return sb.toString();
        }
        return null;
    }

    public boolean addPackage(String pkg, long versionCode, ProcessStatsService tracker) {
        if (!this.pkgList.containsKey(pkg)) {
            ProcessStats.ProcessStateHolder holder = new ProcessStats.ProcessStateHolder(versionCode);
            if (this.baseProcessTracker != null) {
                tracker.updateProcessStateHolderLocked(holder, pkg, this.info.uid, versionCode, this.processName);
                this.pkgList.put(pkg, holder);
                if (holder.state != this.baseProcessTracker) {
                    holder.state.makeActive();
                    return true;
                }
                return true;
            }
            this.pkgList.put(pkg, holder);
            return true;
        }
        return false;
    }

    public int getSetAdjWithServices() {
        if (this.setAdj >= 900 && this.hasStartedServices) {
            return 800;
        }
        return this.setAdj;
    }

    public void forceProcessStateUpTo(int newState) {
        if (this.mRepProcState > newState) {
            this.mRepProcState = newState;
            setCurProcState(newState);
            setCurRawProcState(newState);
            for (int ipkg = this.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                StatsLog.write(3, this.uid, this.processName, this.pkgList.keyAt(ipkg), ActivityManager.processStateAmToProto(this.mRepProcState), this.pkgList.valueAt(ipkg).appVersion);
            }
        }
    }

    public void resetPackageList(ProcessStatsService tracker) {
        int N = this.pkgList.size();
        if (this.baseProcessTracker != null) {
            long now = SystemClock.uptimeMillis();
            this.baseProcessTracker.setState(-1, tracker.getMemFactorLocked(), now, this.pkgList.mPkgList);
            for (int ipkg = this.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                StatsLog.write(3, this.uid, this.processName, this.pkgList.keyAt(ipkg), ActivityManager.processStateAmToProto(-1), this.pkgList.valueAt(ipkg).appVersion);
            }
            if (N != 1) {
                for (int i = 0; i < N; i++) {
                    ProcessStats.ProcessStateHolder holder = this.pkgList.valueAt(i);
                    if (holder.state != null && holder.state != this.baseProcessTracker) {
                        holder.state.makeInactive();
                    }
                }
                this.pkgList.clear();
                ProcessStats.ProcessStateHolder holder2 = new ProcessStats.ProcessStateHolder(this.info.longVersionCode);
                tracker.updateProcessStateHolderLocked(holder2, this.info.packageName, this.info.uid, this.info.longVersionCode, this.processName);
                this.pkgList.put(this.info.packageName, holder2);
                if (holder2.state != this.baseProcessTracker) {
                    holder2.state.makeActive();
                }
            }
        } else if (N != 1) {
            this.pkgList.clear();
            this.pkgList.put(this.info.packageName, new ProcessStats.ProcessStateHolder(this.info.longVersionCode));
        }
    }

    public String[] getPackageList() {
        int size = this.pkgList.size();
        if (size == 0) {
            return null;
        }
        String[] list = new String[size];
        for (int i = 0; i < this.pkgList.size(); i++) {
            list[i] = this.pkgList.keyAt(i);
        }
        return list;
    }

    public List<VersionedPackage> getPackageListWithVersionCode() {
        int size = this.pkgList.size();
        if (size == 0) {
            return null;
        }
        List<VersionedPackage> list = new ArrayList<>();
        for (int i = 0; i < this.pkgList.size(); i++) {
            list.add(new VersionedPackage(this.pkgList.keyAt(i), this.pkgList.valueAt(i).appVersion));
        }
        return list;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowProcessController getWindowProcessController() {
        return this.mWindowProcessController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCurrentSchedulingGroup(int curSchedGroup) {
        this.mCurSchedGroup = curSchedGroup;
        this.mWindowProcessController.setCurrentSchedulingGroup(curSchedGroup);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentSchedulingGroup() {
        return this.mCurSchedGroup;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCurProcState(int curProcState) {
        this.mCurProcState = curProcState;
        this.mWindowProcessController.setCurrentProcState(this.mCurProcState);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurProcState() {
        return this.mCurProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCurRawProcState(int curRawProcState) {
        this.mCurRawProcState = curRawProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurRawProcState() {
        return this.mCurRawProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setReportedProcState(int repProcState) {
        this.mRepProcState = repProcState;
        for (int ipkg = this.pkgList.size() - 1; ipkg >= 0; ipkg--) {
            StatsLog.write(3, this.uid, this.processName, this.pkgList.keyAt(ipkg), ActivityManager.processStateAmToProto(this.mRepProcState), this.pkgList.valueAt(ipkg).appVersion);
        }
        this.mWindowProcessController.setReportedProcState(repProcState);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getReportedProcState() {
        return this.mRepProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCrashing(boolean crashing) {
        this.mCrashing = crashing;
        this.mWindowProcessController.setCrashing(crashing);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCrashing() {
        return this.mCrashing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setNotResponding(boolean notResponding) {
        this.mNotResponding = notResponding;
        this.mWindowProcessController.setNotResponding(notResponding);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNotResponding() {
        return this.mNotResponding;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPersistent(boolean persistent) {
        this.mPersistent = persistent;
        this.mWindowProcessController.setPersistent(persistent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPersistent() {
        return this.mPersistent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInstalling() {
        ApplicationInfo applicationInfo;
        ActivityManagerService activityManagerService = this.mService;
        if (activityManagerService != null && (applicationInfo = this.info) != null) {
            return activityManagerService.isInstalling(applicationInfo.packageName);
        }
        return false;
    }

    public void setRequiredAbi(String requiredAbi) {
        this.mRequiredAbi = requiredAbi;
        this.mWindowProcessController.setRequiredAbi(requiredAbi);
    }

    String getRequiredAbi() {
        return this.mRequiredAbi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasForegroundServices(boolean hasForegroundServices, int fgServiceTypes) {
        this.mHasForegroundServices = hasForegroundServices;
        this.mFgServiceTypes = fgServiceTypes;
        this.mWindowProcessController.setHasForegroundServices(hasForegroundServices);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasForegroundServices() {
        return this.mHasForegroundServices;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasLocationForegroundServices() {
        return this.mHasForegroundServices && (this.mFgServiceTypes & 8) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getForegroundServiceTypes() {
        if (this.mHasForegroundServices) {
            return this.mFgServiceTypes;
        }
        return 0;
    }

    int getReportedForegroundServiceTypes() {
        return this.mRepFgServiceTypes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setReportedForegroundServiceTypes(int foregroundServiceTypes) {
        this.mRepFgServiceTypes = foregroundServiceTypes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasForegroundActivities(boolean hasForegroundActivities) {
        this.mHasForegroundActivities = hasForegroundActivities;
        this.mWindowProcessController.setHasForegroundActivities(hasForegroundActivities);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasForegroundActivities() {
        return this.mHasForegroundActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasClientActivities(boolean hasClientActivities) {
        this.mHasClientActivities = hasClientActivities;
        this.mWindowProcessController.setHasClientActivities(hasClientActivities);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasClientActivities() {
        return this.mHasClientActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasTopUi(boolean hasTopUi) {
        this.mHasTopUi = hasTopUi;
        this.mWindowProcessController.setHasTopUi(hasTopUi);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasTopUi() {
        return this.mHasTopUi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasOverlayUi(boolean hasOverlayUi) {
        this.mHasOverlayUi = hasOverlayUi;
        this.mWindowProcessController.setHasOverlayUi(hasOverlayUi);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasOverlayUi() {
        return this.mHasOverlayUi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInteractionEventTime(long interactionEventTime) {
        this.mInteractionEventTime = interactionEventTime;
        this.mWindowProcessController.setInteractionEventTime(interactionEventTime);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getInteractionEventTime() {
        return this.mInteractionEventTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFgInteractionTime(long fgInteractionTime) {
        this.mFgInteractionTime = fgInteractionTime;
        this.mWindowProcessController.setFgInteractionTime(fgInteractionTime);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getFgInteractionTime() {
        return this.mFgInteractionTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWhenUnimportant(long whenUnimportant) {
        this.mWhenUnimportant = whenUnimportant;
        this.mWindowProcessController.setWhenUnimportant(whenUnimportant);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getWhenUnimportant() {
        return this.mWhenUnimportant;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDebugging(boolean debugging) {
        this.mDebugging = debugging;
        this.mWindowProcessController.setDebugging(debugging);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDebugging() {
        return this.mDebugging;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setUsingWrapper(boolean usingWrapper) {
        this.mUsingWrapper = usingWrapper;
        this.mWindowProcessController.setUsingWrapper(usingWrapper);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUsingWrapper() {
        return this.mUsingWrapper;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addAllowBackgroundActivityStartsToken(Binder entity) {
        if (entity == null) {
            return;
        }
        this.mAllowBackgroundActivityStartsTokens.add(entity);
        this.mWindowProcessController.setAllowBackgroundActivityStarts(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAllowBackgroundActivityStartsToken(Binder entity) {
        if (entity == null) {
            return;
        }
        this.mAllowBackgroundActivityStartsTokens.remove(entity);
        this.mWindowProcessController.setAllowBackgroundActivityStarts(!this.mAllowBackgroundActivityStartsTokens.isEmpty());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addBoundClientUid(int clientUid) {
        this.mBoundClientUids.add(Integer.valueOf(clientUid));
        this.mWindowProcessController.setBoundClientUids(this.mBoundClientUids);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBoundClientUids() {
        if (this.services.isEmpty()) {
            clearBoundClientUids();
            return;
        }
        ArraySet<Integer> boundClientUids = new ArraySet<>();
        int K = this.services.size();
        for (int j = 0; j < K; j++) {
            ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns = this.services.valueAt(j).getConnections();
            int N = conns.size();
            for (int conni = 0; conni < N; conni++) {
                ArrayList<ConnectionRecord> c = conns.valueAt(conni);
                for (int i = 0; i < c.size(); i++) {
                    boundClientUids.add(Integer.valueOf(c.get(i).clientUid));
                }
            }
        }
        this.mBoundClientUids = boundClientUids;
        this.mWindowProcessController.setBoundClientUids(this.mBoundClientUids);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addBoundClientUidsOfNewService(ServiceRecord sr) {
        if (sr == null) {
            return;
        }
        ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns = sr.getConnections();
        for (int conni = conns.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> c = conns.valueAt(conni);
            for (int i = 0; i < c.size(); i++) {
                this.mBoundClientUids.add(Integer.valueOf(c.get(i).clientUid));
            }
        }
        this.mWindowProcessController.setBoundClientUids(this.mBoundClientUids);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearBoundClientUids() {
        this.mBoundClientUids.clear();
        this.mWindowProcessController.setBoundClientUids(this.mBoundClientUids);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActiveInstrumentation(ActiveInstrumentation instr) {
        this.mInstr = instr;
        boolean z = true;
        boolean isInstrumenting = instr != null;
        WindowProcessController windowProcessController = this.mWindowProcessController;
        if (!isInstrumenting || !instr.mHasBackgroundActivityStartsPermission) {
            z = false;
        }
        windowProcessController.setInstrumenting(isInstrumenting, z);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActiveInstrumentation getActiveInstrumentation() {
        return this.mInstr;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCurRawAdj(int curRawAdj) {
        this.mCurRawAdj = curRawAdj;
        this.mWindowProcessController.setPerceptible(curRawAdj <= 200);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurRawAdj() {
        return this.mCurRawAdj;
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void clearProfilerIfNeeded() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.mService.mProfileData.getProfileProc() != null && this.mService.mProfileData.getProfilerInfo() != null && this.mService.mProfileData.getProfileProc() == this) {
                    this.mService.clearProfilerLocked();
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void updateServiceConnectionActivities() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mService.mServices.updateServiceConnectionActivitiesLocked(this);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void setPendingUiClean(boolean pendingUiClean) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mPendingUiClean = pendingUiClean;
                this.mWindowProcessController.setPendingUiClean(pendingUiClean);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasPendingUiClean() {
        return this.mPendingUiClean;
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                setPendingUiClean(true);
                forceProcessStateUpTo(newState);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange, boolean updateOomAdj) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (updateServiceConnectionActivities) {
                    this.mService.mServices.updateServiceConnectionActivitiesLocked(this);
                }
                this.mService.mProcessList.updateLruProcessLocked(this, activityChange, null);
                if (updateOomAdj) {
                    this.mService.updateOomAdjLocked("updateOomAdj_activityChange");
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowProcessListener
    public boolean isRemoved() {
        return this.removed;
    }

    @Override // com.android.server.wm.WindowProcessListener
    public long getCpuTime() {
        return this.mService.mProcessCpuTracker.getCpuTimeForPid(this.pid);
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void onStartActivity(int topProcessState, boolean setProfileProc, String packageName, long versionCode) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.waitingToKill = null;
                if (setProfileProc) {
                    this.mService.mProfileData.setProfileProc(this);
                }
                if (packageName != null) {
                    addPackage(packageName, versionCode, this.mService.mProcessStats);
                }
                updateProcessInfo(false, true, true);
                this.hasShownUi = true;
                setPendingUiClean(true);
                forceProcessStateUpTo(topProcessState);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void appDied() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mService.appDiedLocked(this);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowProcessListener
    public void setRunningRemoteAnimation(boolean runningRemoteAnimation) {
        if (this.pid == Process.myPid()) {
            Slog.wtf(TAG, "system can't run remote animation");
            return;
        }
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.runningRemoteAnimation == runningRemoteAnimation) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                this.runningRemoteAnimation = runningRemoteAnimation;
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                    Slog.i(TAG, "Setting runningRemoteAnimation=" + runningRemoteAnimation + " for pid=" + this.pid);
                }
                this.mService.updateOomAdjLocked(this, true, "updateOomAdj_uiVisibility");
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public long getInputDispatchingTimeout() {
        return this.mWindowProcessController.getInputDispatchingTimeout();
    }

    public int getProcessClassEnum() {
        if (this.pid == ActivityManagerService.MY_PID) {
            return 3;
        }
        ApplicationInfo applicationInfo = this.info;
        if (applicationInfo == null) {
            return 0;
        }
        return (applicationInfo.flags & 1) != 0 ? 2 : 1;
    }

    @VisibleForTesting
    boolean isSilentAnr() {
        return (getShowBackground() || isInterestingForBackgroundTraces()) ? false : true;
    }

    @VisibleForTesting
    List<ProcessRecord> getLruProcessList() {
        return this.mService.mProcessList.mLruProcesses;
    }

    @VisibleForTesting
    boolean isMonitorCpuUsage() {
        ActivityManagerService activityManagerService = this.mService;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void appNotResponding(String activityShortComponentName, ApplicationInfo aInfo, String parentShortComponentName, WindowProcessController parentProcess, boolean aboveSystem, String annotation) {
        long timeMillis;
        String[] nativeProcs;
        ArrayList<Integer> nativePids;
        String cpuInfo;
        String cpuInfo2;
        long timeMillis2;
        ArrayList<Integer> firstPids = new ArrayList<>(5);
        SparseArray<Boolean> lastPids = new SparseArray<>(20);
        this.mWindowProcessController.appEarlyNotResponding(annotation, new Runnable() { // from class: com.android.server.am.-$$Lambda$ProcessRecord$1qn6-pj5yWgiSnKANZpVz3gwd30
            @Override // java.lang.Runnable
            public final void run() {
                ProcessRecord.this.lambda$appNotResponding$0$ProcessRecord();
            }
        });
        long anrTime = SystemClock.uptimeMillis();
        if (isMonitorCpuUsage()) {
            this.mService.updateCpuStatsNow();
        }
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
            } catch (Throwable th) {
                th = th;
            }
            try {
                if (this.mService.mAtmInternal.isShuttingDown()) {
                    Slog.i(TAG, "During shutdown skipping ANR: " + this + " " + annotation);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } else if (isNotResponding()) {
                    Slog.i(TAG, "Skipping duplicate ANR: " + this + " " + annotation);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } else if (isCrashing()) {
                    Slog.i(TAG, "Crashing app skipping ANR: " + this + " " + annotation);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } else if (this.killedByAm) {
                    Slog.i(TAG, "App already killed by AM skipping ANR: " + this + " " + annotation);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } else if (this.killed) {
                    Slog.i(TAG, "Skipping died app ANR: " + this + " " + annotation);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } else {
                    setNotResponding(true);
                    EventLog.writeEvent((int) EventLogTags.AM_ANR, Integer.valueOf(this.userId), Integer.valueOf(this.pid), this.processName, Integer.valueOf(this.info.flags), annotation);
                    long timeMillis3 = System.currentTimeMillis();
                    firstPids.add(Integer.valueOf(this.pid));
                    if (isSilentAnr()) {
                        timeMillis = timeMillis3;
                    } else {
                        int parentPid = this.pid;
                        if (parentProcess != null && parentProcess.getPid() > 0) {
                            parentPid = parentProcess.getPid();
                        }
                        if (parentPid != this.pid) {
                            firstPids.add(Integer.valueOf(parentPid));
                        }
                        if (ActivityManagerService.MY_PID != this.pid && ActivityManagerService.MY_PID != parentPid) {
                            firstPids.add(Integer.valueOf(ActivityManagerService.MY_PID));
                        }
                        int i = getLruProcessList().size() - 1;
                        while (i >= 0) {
                            ProcessRecord r = getLruProcessList().get(i);
                            if (r == null || r.thread == null) {
                                timeMillis2 = timeMillis3;
                            } else {
                                int myPid = r.pid;
                                if (myPid > 0) {
                                    timeMillis2 = timeMillis3;
                                    if (myPid != this.pid && myPid != parentPid && myPid != ActivityManagerService.MY_PID) {
                                        if (r.isPersistent()) {
                                            firstPids.add(Integer.valueOf(myPid));
                                        } else if (r.treatLikeActivity) {
                                            firstPids.add(Integer.valueOf(myPid));
                                        } else {
                                            lastPids.put(myPid, Boolean.TRUE);
                                        }
                                    }
                                } else {
                                    timeMillis2 = timeMillis3;
                                }
                            }
                            i--;
                            timeMillis3 = timeMillis2;
                        }
                        timeMillis = timeMillis3;
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    StringBuilder info = new StringBuilder();
                    info.setLength(0);
                    info.append("ANR in ");
                    info.append(this.processName);
                    if (activityShortComponentName != null) {
                        info.append(" (");
                        info.append(activityShortComponentName);
                        info.append(")");
                    }
                    info.append("\n");
                    info.append("PID: ");
                    info.append(this.pid);
                    info.append("\n");
                    if (annotation != null) {
                        info.append("Reason: ");
                        info.append(annotation);
                        info.append("\n");
                    }
                    if (parentShortComponentName != null && parentShortComponentName.equals(activityShortComponentName)) {
                        info.append("Parent: ");
                        info.append(parentShortComponentName);
                        info.append("\n");
                    }
                    ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);
                    String[] nativeProcs2 = null;
                    if (isSilentAnr()) {
                        int i2 = 0;
                        while (true) {
                            if (i2 >= Watchdog.NATIVE_STACKS_OF_INTEREST.length) {
                                break;
                            } else if (Watchdog.NATIVE_STACKS_OF_INTEREST[i2].equals(this.processName)) {
                                nativeProcs2 = new String[]{this.processName};
                                break;
                            } else {
                                i2++;
                            }
                        }
                        nativeProcs = nativeProcs2;
                    } else {
                        String[] nativeProcs3 = Watchdog.NATIVE_STACKS_OF_INTEREST;
                        nativeProcs = nativeProcs3;
                    }
                    int[] pids = nativeProcs == null ? null : Process.getPidsForCommands(nativeProcs);
                    if (pids != null) {
                        ArrayList<Integer> nativePids2 = new ArrayList<>(pids.length);
                        for (int i3 : pids) {
                            nativePids2.add(Integer.valueOf(i3));
                        }
                        nativePids = nativePids2;
                    } else {
                        nativePids = null;
                    }
                    File tracesFile = ActivityManagerService.dumpStackTraces(firstPids, isSilentAnr() ? null : processCpuTracker, isSilentAnr() ? null : lastPids, nativePids);
                    if (isMonitorCpuUsage()) {
                        this.mService.updateCpuStatsNow();
                        synchronized (this.mService.mProcessCpuTracker) {
                            cpuInfo2 = this.mService.mProcessCpuTracker.printCurrentState(anrTime);
                        }
                        info.append(processCpuTracker.printCurrentLoad());
                        info.append(cpuInfo2);
                        cpuInfo = cpuInfo2;
                    } else {
                        cpuInfo = null;
                    }
                    info.append(processCpuTracker.printCurrentState(anrTime));
                    Slog.e(TAG, info.toString());
                    if (tracesFile == null) {
                        Process.sendSignal(this.pid, 3);
                    }
                    int i4 = this.uid;
                    String str = this.processName;
                    String str2 = activityShortComponentName == null ? UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN : activityShortComponentName;
                    ApplicationInfo applicationInfo = this.info;
                    int i5 = applicationInfo != null ? applicationInfo.isInstantApp() ? 2 : 1 : 0;
                    int i6 = isInterestingToUserLocked() ? 2 : 1;
                    int processClassEnum = getProcessClassEnum();
                    ApplicationInfo applicationInfo2 = this.info;
                    long timeMillis4 = timeMillis;
                    StatsLog.write(79, i4, str, str2, annotation, i5, i6, processClassEnum, applicationInfo2 != null ? applicationInfo2.packageName : "");
                    ProcessRecord parentPr = parentProcess != null ? (ProcessRecord) parentProcess.mOwner : null;
                    this.mService.addErrorToDropBox("xiaopengbughunter_" + this.processName + "_anr_" + timeMillis4, this, this.processName, activityShortComponentName, parentShortComponentName, parentPr, annotation, cpuInfo, tracesFile, null);
                    if (this.mWindowProcessController.appNotResponding(info.toString(), new Runnable() { // from class: com.android.server.am.-$$Lambda$ProcessRecord$Cb3MKja7_iTlaFQrvQTzPvLyoT8
                        @Override // java.lang.Runnable
                        public final void run() {
                            ProcessRecord.this.lambda$appNotResponding$1$ProcessRecord();
                        }
                    }, new Runnable() { // from class: com.android.server.am.-$$Lambda$ProcessRecord$2DImTokd0AWNTECl3WgBxJkOOqs
                        @Override // java.lang.Runnable
                        public final void run() {
                            ProcessRecord.this.lambda$appNotResponding$2$ProcessRecord();
                        }
                    })) {
                        return;
                    }
                    synchronized (this.mService) {
                        try {
                            try {
                                ActivityManagerService.boostPriorityForLockedSection();
                                if (this.mService.mBatteryStatsService != null) {
                                    try {
                                        this.mService.mBatteryStatsService.noteProcessAnr(this.processName, this.uid);
                                    } catch (Throwable th2) {
                                        th = th2;
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                                if (isSilentAnr() && !isDebugging()) {
                                    kill("bg anr", true);
                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                    return;
                                }
                                makeAppNotRespondingLocked(activityShortComponentName, annotation != null ? "ANR " + annotation : "ANR", info.toString());
                                if (this.mService.mUiHandler != null) {
                                    Message msg = Message.obtain();
                                    msg.what = 2;
                                    try {
                                        msg.obj = new AppNotRespondingDialog.Data(this, aInfo, aboveSystem);
                                        this.mService.mUiHandler.sendMessage(msg);
                                    } catch (Throwable th3) {
                                        th = th3;
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                                try {
                                    Intent appErrorService = this.mService.createAppErrorServiceLocked(this, timeMillis4, null);
                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                    if (appErrorService != null) {
                                        try {
                                            this.mService.mContext.startServiceAsUser(appErrorService, UserHandle.SYSTEM);
                                        } catch (Exception e) {
                                            Slog.w(TAG, "bug report receiver dissappeared", e);
                                        }
                                    }
                                } catch (Throwable th4) {
                                    th = th4;
                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                    throw th;
                                }
                            } catch (Throwable th5) {
                                th = th5;
                            }
                        } catch (Throwable th6) {
                            th = th6;
                        }
                    }
                }
            } catch (Throwable th7) {
                th = th7;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th8) {
                        th = th8;
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public /* synthetic */ void lambda$appNotResponding$0$ProcessRecord() {
        kill("anr", true);
    }

    public /* synthetic */ void lambda$appNotResponding$1$ProcessRecord() {
        kill("anr", true);
    }

    public /* synthetic */ void lambda$appNotResponding$2$ProcessRecord() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mService.mServices.scheduleServiceTimeoutLocked(this);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    private void makeAppNotRespondingLocked(String activity, String shortMsg, String longMsg) {
        setNotResponding(true);
        if (this.mService.mAppErrors != null) {
            this.notRespondingReport = this.mService.mAppErrors.generateProcessError(this, 2, activity, shortMsg, longMsg, null);
        }
        startAppProblemLocked();
        getWindowProcessController().stopFreezingActivities();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAppProblemLocked() {
        int[] currentProfileIds;
        this.errorReportReceiver = null;
        for (int userId : this.mService.mUserController.getCurrentProfileIds()) {
            if (this.userId == userId) {
                this.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(this.mService.mContext, this.info.packageName, this.info.flags);
            }
        }
        this.mService.skipCurrentReceiverLocked(this);
    }

    private boolean isInterestingForBackgroundTraces() {
        if (this.pid == ActivityManagerService.MY_PID || isInterestingToUserLocked()) {
            return true;
        }
        ApplicationInfo applicationInfo = this.info;
        return (applicationInfo != null && "com.android.systemui".equals(applicationInfo.packageName)) || hasTopUi() || hasOverlayUi();
    }

    private boolean getShowBackground() {
        return Settings.Secure.getInt(this.mService.mContext.getContentResolver(), "anr_show_background", 0) != 0;
    }
}
