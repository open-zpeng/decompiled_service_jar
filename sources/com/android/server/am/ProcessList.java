package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.graphics.Point;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AppZygote;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ChildZygoteProcess;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StatsLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.Watchdog;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.pm.dex.DexManager;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowManagerService;
import com.xpeng.server.am.BootEvent;
import dalvik.system.VMRuntime;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public final class ProcessList {
    static final int BACKUP_APP_ADJ = 300;
    static final int CACHED_APP_IMPORTANCE_LEVELS = 5;
    static final int CACHED_APP_LMK_FIRST_ADJ = 950;
    static final int CACHED_APP_MAX_ADJ = 999;
    static final int CACHED_APP_MIN_ADJ = 900;
    static final int FOREGROUND_APP_ADJ = 0;
    static final int HEAVY_WEIGHT_APP_ADJ = 400;
    static final int HOME_APP_ADJ = 600;
    static final int INVALID_ADJ = -10000;
    static final byte LMK_GETKILLCNT = 4;
    static final byte LMK_PROCMAXMEM = 5;
    static final byte LMK_PROCPRIO = 1;
    static final byte LMK_PROCPURGE = 3;
    static final byte LMK_PROCREMOVE = 2;
    static final byte LMK_TARGET = 0;
    static final long MAX_EMPTY_TIME = 1800000;
    private static final int MEMORY_EXTRA_INCREASE = 102400;
    static final int MIN_CACHED_APPS = 2;
    static final int MIN_CRASH_INTERVAL = 60000;
    static final int NATIVE_ADJ = -1000;
    static final int PAGE_SIZE = 4096;
    static final int PERCEPTIBLE_APP_ADJ = 200;
    static final int PERCEPTIBLE_LOW_APP_ADJ = 250;
    static final int PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ = 50;
    static final int PERSISTENT_PROC_ADJ = -800;
    static final int PERSISTENT_SERVICE_ADJ = -700;
    static final int PREVIOUS_APP_ADJ = 700;
    public static final int PROC_MEM_CACHED = 4;
    public static final int PROC_MEM_IMPORTANT = 2;
    public static final int PROC_MEM_NUM = 5;
    public static final int PROC_MEM_PERSISTENT = 0;
    public static final int PROC_MEM_SERVICE = 3;
    public static final int PROC_MEM_TOP = 1;
    private static final String PROPERTY_USE_APP_IMAGE_STARTUP_CACHE = "persist.device_config.runtime_native.use_app_image_startup_cache";
    public static final int PSS_ALL_INTERVAL = 1200000;
    private static final int PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL = 30000;
    private static final int PSS_FIRST_ASLEEP_CACHED_INTERVAL = 60000;
    private static final int PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL = 60000;
    private static final int PSS_FIRST_ASLEEP_TOP_INTERVAL = 20000;
    private static final int PSS_FIRST_BACKGROUND_INTERVAL = 20000;
    private static final int PSS_FIRST_CACHED_INTERVAL = 20000;
    private static final int PSS_FIRST_PERSISTENT_INTERVAL = 30000;
    private static final int PSS_FIRST_TOP_INTERVAL = 10000;
    public static final int PSS_MAX_INTERVAL = 3600000;
    public static final int PSS_MIN_TIME_FROM_STATE_CHANGE = 15000;
    public static final int PSS_SAFE_TIME_FROM_STATE_CHANGE = 1000;
    private static final int PSS_SAME_CACHED_INTERVAL = 600000;
    private static final int PSS_SAME_IMPORTANT_INTERVAL = 600000;
    private static final int PSS_SAME_PERSISTENT_INTERVAL = 600000;
    private static final int PSS_SAME_SERVICE_INTERVAL = 300000;
    private static final int PSS_SAME_TOP_INTERVAL = 60000;
    private static final int PSS_TEST_FIRST_BACKGROUND_INTERVAL = 5000;
    private static final int PSS_TEST_FIRST_TOP_INTERVAL = 3000;
    public static final int PSS_TEST_MIN_TIME_FROM_STATE_CHANGE = 10000;
    private static final int PSS_TEST_SAME_BACKGROUND_INTERVAL = 15000;
    private static final int PSS_TEST_SAME_IMPORTANT_INTERVAL = 10000;
    static final int SCHED_GROUP_BACKGROUND = 0;
    static final int SCHED_GROUP_DEFAULT = 2;
    static final int SCHED_GROUP_RESTRICTED = 1;
    public static final int SCHED_GROUP_TOP_APP = 3;
    static final int SCHED_GROUP_TOP_APP_BOUND = 4;
    static final int SERVICE_ADJ = 500;
    static final int SERVICE_B_ADJ = 800;
    static final int SYSTEM_ADJ = -900;
    static final String TAG = "ActivityManager";
    static final int TRIM_CRITICAL_THRESHOLD = 3;
    static final int TRIM_LOW_THRESHOLD = 5;
    static final int UNKNOWN_ADJ = 1001;
    static final int VISIBLE_APP_ADJ = 100;
    static final int VISIBLE_APP_LAYER_MAX = 99;
    @GuardedBy({"sLmkdSocketLock"})
    private static InputStream sLmkdInputStream;
    @GuardedBy({"sLmkdSocketLock"})
    private static OutputStream sLmkdOutputStream;
    @GuardedBy({"sLmkdSocketLock"})
    private static LocalSocket sLmkdSocket;
    ActiveUids mActiveUids;
    private long mCachedRestoreLevel;
    private boolean mHaveDisplaySize;
    private final long mTotalMemMb;
    static KillHandler sKillHandler = null;
    static ServiceThread sKillThread = null;
    private static Object sLmkdSocketLock = new Object();
    private static final int[] sProcStateToProcMem = {0, 0, 1, 2, 2, 1, 2, 2, 2, 2, 2, 3, 4, 1, 2, 4, 4, 4, 4, 4, 4};
    private static final long[] sFirstAwakePssTimes = {30000, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, 20000, 20000, 20000};
    private static final long[] sSameAwakePssTimes = {600000, 60000, 600000, BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS, 600000};
    private static final long[] sFirstAsleepPssTimes = {60000, 20000, 30000, 30000, 60000};
    private static final long[] sSameAsleepPssTimes = {600000, 60000, 600000, BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS, 600000};
    private static final long[] sTestFirstPssTimes = {BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS, 5000, 5000, 5000};
    private static final long[] sTestSamePssTimes = {15000, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, 15000, 15000};
    ActivityManagerService mService = null;
    private final int[] mOomAdj = {0, 100, 200, PERCEPTIBLE_LOW_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_LMK_FIRST_ADJ};
    private final int[] mOomMinFreeLow = {12288, 18432, 24576, 36864, 43008, 49152};
    private final int[] mOomMinFreeHigh = {73728, 92160, 110592, 129024, 147456, 184320};
    private final int[] mOomMinFree = new int[this.mOomAdj.length];
    @GuardedBy({"mService"})
    final StringBuilder mStringBuilder = new StringBuilder(256);
    @GuardedBy({"mService"})
    @VisibleForTesting
    long mProcStateSeqCounter = 0;
    @GuardedBy({"mService"})
    private long mProcStartSeqCounter = 0;
    @GuardedBy({"mService"})
    final LongSparseArray<ProcessRecord> mPendingStarts = new LongSparseArray<>();
    final ArrayList<ProcessRecord> mLruProcesses = new ArrayList<>();
    int mLruProcessActivityStart = 0;
    int mLruProcessServiceStart = 0;
    int mLruSeq = 0;
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<>();
    final ProcessMap<AppZygote> mAppZygotes = new ProcessMap<>();
    final ArrayMap<AppZygote, ArrayList<ProcessRecord>> mAppZygoteProcesses = new ArrayMap<>();
    @VisibleForTesting
    IsolatedUidRange mGlobalIsolatedUids = new IsolatedUidRange(99000, 99999);
    @VisibleForTesting
    IsolatedUidRangeAllocator mAppIsolatedUidRangeAllocator = new IsolatedUidRangeAllocator(90000, 98999, 100);
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<>();
    final MyProcessMap mProcessNames = new MyProcessMap();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class IsolatedUidRange {
        @VisibleForTesting
        public final int mFirstUid;
        @VisibleForTesting
        public final int mLastUid;
        @GuardedBy({"ProcessList.this.mService"})
        private int mNextUid;
        @GuardedBy({"ProcessList.this.mService"})
        private final SparseBooleanArray mUidUsed = new SparseBooleanArray();

        IsolatedUidRange(int firstUid, int lastUid) {
            this.mFirstUid = firstUid;
            this.mLastUid = lastUid;
            this.mNextUid = firstUid;
        }

        @GuardedBy({"ProcessList.this.mService"})
        int allocateIsolatedUidLocked(int userId) {
            int stepsLeft = (this.mLastUid - this.mFirstUid) + 1;
            for (int i = 0; i < stepsLeft; i++) {
                int i2 = this.mNextUid;
                if (i2 < this.mFirstUid || i2 > this.mLastUid) {
                    this.mNextUid = this.mFirstUid;
                }
                int uid = UserHandle.getUid(userId, this.mNextUid);
                this.mNextUid++;
                if (!this.mUidUsed.get(uid, false)) {
                    this.mUidUsed.put(uid, true);
                    return uid;
                }
            }
            return -1;
        }

        @GuardedBy({"ProcessList.this.mService"})
        void freeIsolatedUidLocked(int uid) {
            this.mUidUsed.delete(uid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class IsolatedUidRangeAllocator {
        @GuardedBy({"ProcessList.this.mService"})
        private final ProcessMap<IsolatedUidRange> mAppRanges = new ProcessMap<>();
        @GuardedBy({"ProcessList.this.mService"})
        private final BitSet mAvailableUidRanges;
        private final int mFirstUid;
        private final int mNumUidRanges;
        private final int mNumUidsPerRange;

        IsolatedUidRangeAllocator(int firstUid, int lastUid, int numUidsPerRange) {
            this.mFirstUid = firstUid;
            this.mNumUidsPerRange = numUidsPerRange;
            this.mNumUidRanges = ((lastUid - firstUid) + 1) / numUidsPerRange;
            this.mAvailableUidRanges = new BitSet(this.mNumUidRanges);
            this.mAvailableUidRanges.set(0, this.mNumUidRanges);
        }

        @GuardedBy({"ProcessList.this.mService"})
        IsolatedUidRange getIsolatedUidRangeLocked(String processName, int uid) {
            return (IsolatedUidRange) this.mAppRanges.get(processName, uid);
        }

        @GuardedBy({"ProcessList.this.mService"})
        IsolatedUidRange getOrCreateIsolatedUidRangeLocked(String processName, int uid) {
            IsolatedUidRange range = getIsolatedUidRangeLocked(processName, uid);
            if (range == null) {
                int uidRangeIndex = this.mAvailableUidRanges.nextSetBit(0);
                if (uidRangeIndex < 0) {
                    return null;
                }
                this.mAvailableUidRanges.clear(uidRangeIndex);
                int i = this.mFirstUid;
                int i2 = this.mNumUidsPerRange;
                int actualUid = i + (uidRangeIndex * i2);
                IsolatedUidRange range2 = new IsolatedUidRange(actualUid, (i2 + actualUid) - 1);
                this.mAppRanges.put(processName, uid, range2);
                return range2;
            }
            return range;
        }

        @GuardedBy({"ProcessList.this.mService"})
        void freeUidRangeLocked(ApplicationInfo info) {
            IsolatedUidRange range = (IsolatedUidRange) this.mAppRanges.get(info.processName, info.uid);
            if (range != null) {
                int uidRangeIndex = (range.mFirstUid - this.mFirstUid) / this.mNumUidsPerRange;
                this.mAvailableUidRanges.set(uidRangeIndex);
                this.mAppRanges.remove(info.processName, info.uid);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class MyProcessMap extends ProcessMap<ProcessRecord> {
        MyProcessMap() {
        }

        public ProcessRecord put(String name, int uid, ProcessRecord value) {
            ProcessRecord r = (ProcessRecord) super.put(name, uid, value);
            ProcessList.this.mService.mAtmInternal.onProcessAdded(r.getWindowProcessController());
            return r;
        }

        /* renamed from: remove */
        public ProcessRecord m13remove(String name, int uid) {
            ProcessRecord r = (ProcessRecord) super.remove(name, uid);
            ProcessList.this.mService.mAtmInternal.onProcessRemoved(name, uid);
            return r;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class KillHandler extends Handler {
        static final int KILL_PROCESS_GROUP_MSG = 4000;

        public KillHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == KILL_PROCESS_GROUP_MSG) {
                Trace.traceBegin(64L, "killProcessGroup");
                Slog.i(ProcessList.TAG, "handleMessage Kill GROUP MSG");
                Process.killProcessGroup(msg.arg1, msg.arg2);
                Trace.traceEnd(64L);
                return;
            }
            super.handleMessage(msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        this.mTotalMemMb = minfo.getTotalSize() / 1048576;
        updateOomLevels(0, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void init(ActivityManagerService service, ActiveUids activeUids) {
        this.mService = service;
        this.mActiveUids = activeUids;
        if (sKillHandler == null) {
            sKillThread = new ServiceThread("ActivityManager:kill", 10, true);
            sKillThread.start();
            sKillHandler = new KillHandler(sKillThread.getLooper());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyDisplaySize(WindowManagerService wm) {
        if (!this.mHaveDisplaySize) {
            Point p = new Point();
            wm.getBaseDisplaySize(0, p);
            if (p.x != 0 && p.y != 0) {
                updateOomLevels(p.x, p.y, true);
                this.mHaveDisplaySize = true;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:65:0x0140  */
    /* JADX WARN: Removed duplicated region for block: B:71:0x0149  */
    /* JADX WARN: Removed duplicated region for block: B:98:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void updateOomLevels(int r19, int r20, boolean r21) {
        /*
            Method dump skipped, instructions count: 405
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProcessList.updateOomLevels(int, int, boolean):void");
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit / 2;
    }

    private static String buildOomTag(String prefix, String compactPrefix, String space, int val, int base, boolean compact) {
        int diff = val - base;
        if (diff == 0) {
            if (compact) {
                return compactPrefix;
            }
            if (space == null) {
                return prefix;
            }
            return prefix + space;
        }
        if (diff < 10) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix);
            sb.append(compact ? "+" : "+ ");
            sb.append(Integer.toString(diff));
            return sb.toString();
        }
        return prefix + "+" + Integer.toString(diff);
    }

    public static String makeOomAdjString(int setAdj, boolean compact) {
        if (setAdj >= CACHED_APP_MIN_ADJ) {
            return buildOomTag("cch", "cch", "   ", setAdj, CACHED_APP_MIN_ADJ, compact);
        }
        if (setAdj >= SERVICE_B_ADJ) {
            return buildOomTag("svcb  ", "svcb", null, setAdj, SERVICE_B_ADJ, compact);
        }
        if (setAdj >= PREVIOUS_APP_ADJ) {
            return buildOomTag("prev  ", "prev", null, setAdj, PREVIOUS_APP_ADJ, compact);
        }
        if (setAdj >= 600) {
            return buildOomTag("home  ", "home", null, setAdj, 600, compact);
        }
        if (setAdj >= 500) {
            return buildOomTag("svc   ", "svc", null, setAdj, 500, compact);
        }
        if (setAdj >= HEAVY_WEIGHT_APP_ADJ) {
            return buildOomTag("hvy   ", "hvy", null, setAdj, HEAVY_WEIGHT_APP_ADJ, compact);
        }
        if (setAdj >= 300) {
            return buildOomTag("bkup  ", "bkup", null, setAdj, 300, compact);
        }
        if (setAdj >= PERCEPTIBLE_LOW_APP_ADJ) {
            return buildOomTag("prcl  ", "prcl", null, setAdj, PERCEPTIBLE_LOW_APP_ADJ, compact);
        }
        if (setAdj >= 200) {
            return buildOomTag("prcp  ", "prcp", null, setAdj, 200, compact);
        }
        if (setAdj >= 100) {
            return buildOomTag("vis", "vis", "   ", setAdj, 100, compact);
        }
        if (setAdj >= 0) {
            return buildOomTag("fore  ", "fore", null, setAdj, 0, compact);
        }
        if (setAdj >= PERSISTENT_SERVICE_ADJ) {
            return buildOomTag("psvc  ", "psvc", null, setAdj, PERSISTENT_SERVICE_ADJ, compact);
        }
        if (setAdj >= PERSISTENT_PROC_ADJ) {
            return buildOomTag("pers  ", "pers", null, setAdj, PERSISTENT_PROC_ADJ, compact);
        }
        if (setAdj >= SYSTEM_ADJ) {
            return buildOomTag("sys   ", "sys", null, setAdj, SYSTEM_ADJ, compact);
        }
        if (setAdj >= -1000) {
            return buildOomTag("ntv  ", "ntv", null, setAdj, -1000, compact);
        }
        return Integer.toString(setAdj);
    }

    public static String makeProcStateString(int curProcState) {
        switch (curProcState) {
            case 0:
                return "PER ";
            case 1:
                return "PERU";
            case 2:
                return "TOP ";
            case 3:
                return "FGSL";
            case 4:
                return "BTOP";
            case 5:
                return "FGS ";
            case 6:
                return "BFGS";
            case 7:
                return "IMPF";
            case 8:
                return "IMPB";
            case 9:
                return "TRNB";
            case 10:
                return "BKUP";
            case 11:
                return "SVC ";
            case 12:
                return "RCVR";
            case 13:
                return "TPSL";
            case 14:
                return "HVY ";
            case 15:
                return "HOME";
            case 16:
                return "LAST";
            case 17:
                return "CAC ";
            case 18:
                return "CACC";
            case 19:
                return "CRE ";
            case 20:
                return "CEM ";
            case 21:
                return "NONE";
            default:
                return "??";
        }
    }

    public static int makeProcStateProtoEnum(int curProcState) {
        switch (curProcState) {
            case -1:
                return CACHED_APP_MAX_ADJ;
            case 0:
                return 1000;
            case 1:
                return 1001;
            case 2:
                return 1002;
            case 3:
                return 1003;
            case 4:
                return 1020;
            case 5:
                return 1003;
            case 6:
                return 1004;
            case 7:
                return 1005;
            case 8:
                return 1006;
            case 9:
                return 1007;
            case 10:
                return 1008;
            case 11:
                return 1009;
            case 12:
                return 1010;
            case 13:
                return 1011;
            case 14:
                return 1012;
            case 15:
                return 1013;
            case 16:
                return 1014;
            case 17:
                return 1015;
            case 18:
                return 1016;
            case 19:
                return 1017;
            case 20:
                return 1018;
            case 21:
                return 1019;
            default:
                return 998;
        }
    }

    public static void appendRamKb(StringBuilder sb, long ramKb) {
        int j = 0;
        int fact = 10;
        while (j < 6) {
            if (ramKb < fact) {
                sb.append(' ');
            }
            j++;
            fact *= 10;
        }
        sb.append(ramKb);
    }

    /* loaded from: classes.dex */
    public static final class ProcStateMemTracker {
        int mPendingHighestMemState;
        int mPendingMemState;
        float mPendingScalingFactor;
        final int[] mHighestMem = new int[5];
        final float[] mScalingFactor = new float[5];
        int mTotalHighestMem = 4;

        public ProcStateMemTracker() {
            for (int i = 0; i < 5; i++) {
                this.mHighestMem[i] = 5;
                this.mScalingFactor[i] = 1.0f;
            }
            this.mPendingMemState = -1;
        }

        public void dumpLine(PrintWriter pw) {
            pw.print("best=");
            pw.print(this.mTotalHighestMem);
            pw.print(" (");
            boolean needSep = false;
            for (int i = 0; i < 5; i++) {
                if (this.mHighestMem[i] < 5) {
                    if (needSep) {
                        pw.print(", ");
                    }
                    pw.print(i);
                    pw.print("=");
                    pw.print(this.mHighestMem[i]);
                    pw.print(" ");
                    pw.print(this.mScalingFactor[i]);
                    pw.print("x");
                    needSep = true;
                }
            }
            pw.print(")");
            if (this.mPendingMemState >= 0) {
                pw.print(" / pending state=");
                pw.print(this.mPendingMemState);
                pw.print(" highest=");
                pw.print(this.mPendingHighestMemState);
                pw.print(" ");
                pw.print(this.mPendingScalingFactor);
                pw.print("x");
            }
            pw.println();
        }
    }

    public static boolean procStatesDifferForMem(int procState1, int procState2) {
        int[] iArr = sProcStateToProcMem;
        return iArr[procState1] != iArr[procState2];
    }

    public static long minTimeFromStateChange(boolean test) {
        if (test) {
            return JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        }
        return 15000L;
    }

    public static void commitNextPssTime(ProcStateMemTracker tracker) {
        if (tracker.mPendingMemState >= 0) {
            tracker.mHighestMem[tracker.mPendingMemState] = tracker.mPendingHighestMemState;
            tracker.mScalingFactor[tracker.mPendingMemState] = tracker.mPendingScalingFactor;
            tracker.mTotalHighestMem = tracker.mPendingHighestMemState;
            tracker.mPendingMemState = -1;
        }
    }

    public static void abortNextPssTime(ProcStateMemTracker tracker) {
        tracker.mPendingMemState = -1;
    }

    public static long computeNextPssTime(int procState, ProcStateMemTracker tracker, boolean test, boolean sleeping, long now) {
        boolean first;
        float scalingFactor;
        long[] table;
        int memState = sProcStateToProcMem[procState];
        if (tracker != null) {
            int highestMemState = memState < tracker.mTotalHighestMem ? memState : tracker.mTotalHighestMem;
            first = highestMemState < tracker.mHighestMem[memState];
            tracker.mPendingMemState = memState;
            tracker.mPendingHighestMemState = highestMemState;
            if (first) {
                scalingFactor = 1.0f;
                tracker.mPendingScalingFactor = 1.0f;
            } else {
                scalingFactor = tracker.mScalingFactor[memState];
                tracker.mPendingScalingFactor = 1.5f * scalingFactor;
            }
        } else {
            first = true;
            scalingFactor = 1.0f;
        }
        if (test) {
            if (first) {
                table = sTestFirstPssTimes;
            } else {
                table = sTestSamePssTimes;
            }
        } else if (first) {
            table = sleeping ? sFirstAsleepPssTimes : sFirstAwakePssTimes;
        } else {
            table = sleeping ? sSameAsleepPssTimes : sSameAwakePssTimes;
        }
        long delay = ((float) table[memState]) * scalingFactor;
        if (delay > 3600000) {
            delay = 3600000;
        }
        return now + delay;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getMemLevel(int adjustment) {
        int i = 0;
        while (true) {
            int[] iArr = this.mOomAdj;
            if (i < iArr.length) {
                if (adjustment > iArr[i]) {
                    i++;
                } else {
                    return this.mOomMinFree[i] * 1024;
                }
            } else {
                return this.mOomMinFree[iArr.length - 1] * 1024;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getCachedRestoreThresholdKb() {
        return this.mCachedRestoreLevel;
    }

    public static void setOomAdj(int pid, int uid, int amt) {
        if (pid <= 0 || amt == 1001) {
            return;
        }
        long start = SystemClock.elapsedRealtime();
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putInt(1);
        buf.putInt(pid);
        buf.putInt(uid);
        buf.putInt(amt);
        writeLmkd(buf, null);
        long now = SystemClock.elapsedRealtime();
        if (now - start > 250) {
            Slog.w(TAG, "SLOW OOM ADJ: " + (now - start) + "ms for pid " + pid + " = " + amt);
        }
    }

    public static void setOomMem(int pid, int oomMem) {
        if (pid <= 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putInt(5);
        buf.putInt(pid);
        buf.putInt(oomMem);
        writeLmkd(buf, null);
    }

    public static final void remove(int pid) {
        if (pid <= 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(2);
        buf.putInt(pid);
        writeLmkd(buf, null);
    }

    public static final Integer getLmkdKillCount(int min_oom_adj, int max_oom_adj) {
        ByteBuffer buf = ByteBuffer.allocate(12);
        ByteBuffer repl = ByteBuffer.allocate(8);
        buf.putInt(4);
        buf.putInt(min_oom_adj);
        buf.putInt(max_oom_adj);
        if (writeLmkd(buf, repl)) {
            int i = repl.getInt();
            if (i != 4) {
                Slog.e(TAG, "Failed to get kill count, code mismatch");
                return null;
            }
            return new Integer(repl.getInt());
        }
        return null;
    }

    @GuardedBy({"sLmkdSocketLock"})
    private static boolean openLmkdSocketLS() {
        try {
            sLmkdSocket = new LocalSocket(3);
            sLmkdSocket.connect(new LocalSocketAddress("lmkd", LocalSocketAddress.Namespace.RESERVED));
            sLmkdOutputStream = sLmkdSocket.getOutputStream();
            sLmkdInputStream = sLmkdSocket.getInputStream();
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "lowmemorykiller daemon socket open failed");
            sLmkdSocket = null;
            return false;
        }
    }

    @GuardedBy({"sLmkdSocketLock"})
    private static boolean writeLmkdCommandLS(ByteBuffer buf) {
        try {
            sLmkdOutputStream.write(buf.array(), 0, buf.position());
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Error writing to lowmemorykiller socket");
            IoUtils.closeQuietly(sLmkdSocket);
            sLmkdSocket = null;
            return false;
        }
    }

    @GuardedBy({"sLmkdSocketLock"})
    private static boolean readLmkdReplyLS(ByteBuffer buf) {
        try {
            int len = sLmkdInputStream.read(buf.array(), 0, buf.array().length);
            if (len == buf.array().length) {
                return true;
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error reading from lowmemorykiller socket");
        }
        IoUtils.closeQuietly(sLmkdSocket);
        sLmkdSocket = null;
        return false;
    }

    private static boolean writeLmkd(ByteBuffer buf, ByteBuffer repl) {
        synchronized (sLmkdSocketLock) {
            for (int i = 0; i < 3; i++) {
                if (sLmkdSocket == null) {
                    if (!openLmkdSocketLS()) {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                        }
                    } else {
                        ByteBuffer purge_buf = ByteBuffer.allocate(4);
                        purge_buf.putInt(3);
                        if (!writeLmkdCommandLS(purge_buf)) {
                        }
                    }
                }
                if (writeLmkdCommandLS(buf) && (repl == null || readLmkdReplyLS(repl))) {
                    return true;
                }
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void killProcessGroup(int uid, int pid) {
        KillHandler killHandler = sKillHandler;
        if (killHandler != null) {
            killHandler.sendMessage(killHandler.obtainMessage(4000, uid, pid));
            return;
        }
        Slog.w(TAG, "Asked to kill process group before system bringup!");
        Process.killProcessGroup(uid, pid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean keepIfLarge) {
        if (uid == 1000) {
            SparseArray<ProcessRecord> procs = (SparseArray) this.mProcessNames.getMap().get(processName);
            if (procs == null) {
                return null;
            }
            int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                int procUid = procs.keyAt(i);
                if (UserHandle.isCore(procUid) && UserHandle.isSameUser(procUid, uid)) {
                    return procs.valueAt(i);
                }
            }
        }
        ProcessRecord proc = (ProcessRecord) this.mProcessNames.get(processName, uid);
        if (proc != null && !keepIfLarge && this.mService.mLastMemoryLevel > 0 && proc.setProcState >= 20) {
            if (ActivityManagerDebugConfig.DEBUG_PSS) {
                Slog.d(TAG, "May not keep " + proc + ": pss=" + proc.lastCachedPss);
            }
            if (proc.lastCachedPss >= getCachedRestoreThresholdKb()) {
                if (proc.baseProcessTracker != null) {
                    proc.baseProcessTracker.reportCachedKill(proc.pkgList.mPkgList, proc.lastCachedPss);
                    for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                        ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                        StatsLog.write(17, proc.info.uid, holder.state.getName(), holder.state.getPackage(), proc.lastCachedPss, holder.appVersion);
                    }
                }
                proc.kill(Long.toString(proc.lastCachedPss) + "k from cached", true);
            }
        }
        return proc;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        long homeAppMem = getMemLevel(600);
        long cachedAppMem = getMemLevel(CACHED_APP_MIN_ADJ);
        outInfo.availMem = Process.getFreeMemory();
        outInfo.totalMem = Process.getTotalMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < ((cachedAppMem - homeAppMem) / 2) + homeAppMem;
        outInfo.hiddenAppThreshold = cachedAppMem;
        outInfo.secondaryServerThreshold = getMemLevel(500);
        outInfo.visibleAppThreshold = getMemLevel(100);
        outInfo.foregroundAppThreshold = getMemLevel(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessRecord findAppProcessLocked(IBinder app, String reason) {
        int NP = this.mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = (SparseArray) this.mProcessNames.getMap().valueAt(ip);
            int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord p = apps.valueAt(ia);
                if (p.thread != null && p.thread.asBinder() == app) {
                    return p;
                }
            }
        }
        Slog.w(TAG, "Can't find mystery application for " + reason + " from pid=" + Binder.getCallingPid() + " uid=" + Binder.getCallingUid() + ": " + app);
        return null;
    }

    private void checkSlow(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if (now - startTime > 50) {
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r15v0, types: [com.android.server.am.ProcessList] */
    /* JADX WARN: Type inference failed for: r15v4 */
    /* JADX WARN: Type inference failed for: r15v5 */
    /* JADX WARN: Type inference failed for: r15v6 */
    /* JADX WARN: Type inference failed for: r15v8 */
    @GuardedBy({"mService"})
    public boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord, boolean disableHiddenApiChecks, boolean mountExtStorageFull, String abiOverride) {
        String str;
        boolean z;
        int[] gids;
        int mountExternal;
        String invokeWith;
        String requiredAbi;
        String requiredAbi2;
        String str2;
        int mountExternal2;
        int[] gids2;
        ProcessRecord processRecord = this;
        if (app.pendingStart) {
            return true;
        }
        long startTime = SystemClock.elapsedRealtime();
        boolean z2 = false;
        if (app.pid > 0 && app.pid != ActivityManagerService.MY_PID) {
            processRecord.checkSlow(startTime, "startProcess: removing from pids map");
            processRecord.mService.mPidsSelfLocked.remove(app);
            processRecord.mService.mHandler.removeMessages(20, app);
            processRecord.checkSlow(startTime, "startProcess: done removing from pids map");
            app.setPid(0);
            app.startSeq = 0L;
        }
        if (ActivityManagerDebugConfig.DEBUG_PROCESSES && processRecord.mService.mProcessesOnHold.contains(app)) {
            Slog.v(TAG, "startProcessLocked removing on hold: " + app);
        }
        processRecord.mService.mProcessesOnHold.remove(app);
        processRecord.checkSlow(startTime, "startProcess: starting to update cpu stats");
        processRecord.mService.updateCpuStats();
        processRecord.checkSlow(startTime, "startProcess: done updating cpu stats");
        try {
            try {
                try {
                    int userId = UserHandle.getUserId(app.uid);
                    AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, userId);
                    int uid = app.uid;
                    if (app.isolated) {
                        gids = null;
                        mountExternal = 0;
                    } else {
                        try {
                            try {
                                processRecord.checkSlow(startTime, "startProcess: getting gids from package manager");
                                IPackageManager pm = AppGlobals.getPackageManager();
                                int[] permGids = pm.getPackageGids(app.info.packageName, 268435456, app.userId);
                                if (StorageManager.hasIsolatedStorage() && mountExtStorageFull) {
                                    mountExternal2 = 6;
                                } else {
                                    StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
                                    int mountExternal3 = storageManagerInternal.getExternalStorageMountMode(uid, app.info.packageName);
                                    mountExternal2 = mountExternal3;
                                }
                                if (ArrayUtils.isEmpty(permGids)) {
                                    gids2 = new int[3];
                                } else {
                                    gids2 = new int[permGids.length + 3];
                                    System.arraycopy(permGids, 0, gids2, 3, permGids.length);
                                }
                                gids2[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
                                gids2[1] = UserHandle.getCacheAppGid(UserHandle.getAppId(uid));
                                gids2[2] = UserHandle.getUserGid(UserHandle.getUserId(uid));
                                if (gids2[0] == -1) {
                                    gids2[0] = gids2[2];
                                }
                                if (gids2[1] == -1) {
                                    gids2[1] = gids2[2];
                                }
                                mountExternal = mountExternal2;
                                gids = gids2;
                            } catch (RuntimeException e) {
                                e = e;
                                str = TAG;
                                processRecord = app;
                                z = false;
                                Slog.e(str, "Failure starting process " + processRecord.processName, e);
                                ProcessRecord processRecord2 = processRecord;
                                this.mService.forceStopPackageLocked(processRecord2.info.packageName, UserHandle.getAppId(processRecord2.uid), false, false, true, false, false, processRecord2.userId, "start failure");
                                return z;
                            }
                        } catch (RemoteException e2) {
                            throw e2.rethrowAsRuntimeException();
                        }
                    }
                    app.mountMode = mountExternal;
                    processRecord.checkSlow(startTime, "startProcess: building args");
                    int uid2 = processRecord.mService.mAtmInternal.isFactoryTestProcess(app.getWindowProcessController()) ? 0 : uid;
                    int runtimeFlags = 0;
                    if ((app.info.flags & 2) != 0) {
                        int runtimeFlags2 = 0 | 1;
                        runtimeFlags = runtimeFlags2 | 256 | 2;
                        if (Settings.Global.getInt(processRecord.mService.mContext.getContentResolver(), "art_verifier_verify_debuggable", 1) == 0) {
                            runtimeFlags |= 512;
                            Slog.w(TAG, app + ": ART verification disabled");
                        }
                    }
                    if ((app.info.flags & 16384) != 0 || processRecord.mService.mSafeMode) {
                        runtimeFlags |= 8;
                    }
                    if ((app.info.privateFlags & DumpState.DUMP_VOLUMES) != 0) {
                        runtimeFlags |= 32768;
                    }
                    if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                        runtimeFlags |= 2;
                    }
                    String genDebugInfoProperty = SystemProperties.get("debug.generate-debug-info");
                    if ("1".equals(genDebugInfoProperty) || "true".equals(genDebugInfoProperty)) {
                        runtimeFlags |= 32;
                    }
                    String genMiniDebugInfoProperty = SystemProperties.get("dalvik.vm.minidebuginfo");
                    if ("1".equals(genMiniDebugInfoProperty) || "true".equals(genMiniDebugInfoProperty)) {
                        runtimeFlags |= 2048;
                    }
                    if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
                        runtimeFlags |= 16;
                    }
                    if ("1".equals(SystemProperties.get("debug.assert"))) {
                        runtimeFlags |= 4;
                    }
                    if (processRecord.mService.mNativeDebuggingApp != null && processRecord.mService.mNativeDebuggingApp.equals(app.processName)) {
                        runtimeFlags = runtimeFlags | 64 | 32 | 128;
                        processRecord.mService.mNativeDebuggingApp = null;
                    }
                    if (app.info.isEmbeddedDexUsed() || (app.info.isPrivilegedApp() && DexManager.isPackageSelectedToRunOob(app.pkgList.mPkgList.keySet()))) {
                        runtimeFlags |= 1024;
                    }
                    if (!disableHiddenApiChecks && !processRecord.mService.mHiddenApiBlacklist.isDisabled()) {
                        app.info.maybeUpdateHiddenApiEnforcementPolicy(processRecord.mService.mHiddenApiBlacklist.getPolicy());
                        int policy = app.info.getHiddenApiEnforcementPolicy();
                        int policyBits = policy << Zygote.API_ENFORCEMENT_POLICY_SHIFT;
                        if ((policyBits & 12288) != policyBits) {
                            throw new IllegalStateException("Invalid API policy: " + policy);
                        }
                        runtimeFlags |= policyBits;
                    }
                    String useAppImageCache = SystemProperties.get(PROPERTY_USE_APP_IMAGE_STARTUP_CACHE, "");
                    int runtimeFlags3 = (TextUtils.isEmpty(useAppImageCache) || useAppImageCache.equals("false")) ? runtimeFlags : 65536 | runtimeFlags;
                    String invokeWith2 = null;
                    if ((app.info.flags & 2) != 0) {
                        String wrapperFileName = app.info.nativeLibraryDir + "/wrap.sh";
                        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                        try {
                            if (new File(wrapperFileName).exists()) {
                                invokeWith2 = "/system/bin/logwrapper " + wrapperFileName;
                            }
                            StrictMode.setThreadPolicy(oldPolicy);
                            invokeWith = invokeWith2;
                        } catch (RuntimeException e3) {
                            e = e3;
                            str = TAG;
                            processRecord = app;
                            z = false;
                            Slog.e(str, "Failure starting process " + processRecord.processName, e);
                            ProcessRecord processRecord22 = processRecord;
                            this.mService.forceStopPackageLocked(processRecord22.info.packageName, UserHandle.getAppId(processRecord22.uid), false, false, true, false, false, processRecord22.userId, "start failure");
                            return z;
                        }
                    } else {
                        invokeWith = null;
                    }
                    if (abiOverride != null) {
                        requiredAbi = abiOverride;
                    } else {
                        try {
                            requiredAbi = app.info.primaryCpuAbi;
                        } catch (RuntimeException e4) {
                            e = e4;
                            str = TAG;
                            processRecord = app;
                            z = false;
                            Slog.e(str, "Failure starting process " + processRecord.processName, e);
                            ProcessRecord processRecord222 = processRecord;
                            this.mService.forceStopPackageLocked(processRecord222.info.packageName, UserHandle.getAppId(processRecord222.uid), false, false, true, false, false, processRecord222.userId, "start failure");
                            return z;
                        }
                    }
                    if (requiredAbi == null) {
                        z2 = false;
                        String requiredAbi3 = Build.SUPPORTED_ABIS[0];
                        requiredAbi2 = requiredAbi3;
                    } else {
                        z2 = false;
                        requiredAbi2 = requiredAbi;
                    }
                    String instructionSet = app.info.primaryCpuAbi != null ? VMRuntime.getInstructionSet(app.info.primaryCpuAbi) : null;
                    app.gids = gids;
                    app.setRequiredAbi(requiredAbi2);
                    app.instructionSet = instructionSet;
                    if (TextUtils.isEmpty(app.info.seInfoUser)) {
                        StringBuilder sb = new StringBuilder();
                        str2 = "";
                        sb.append("SELinux tag not defined for ");
                        sb.append(app.info.packageName);
                        sb.append(" (uid ");
                        sb.append(app.uid);
                        sb.append(")");
                        Slog.wtf(TAG, "SELinux tag not defined", new IllegalStateException(sb.toString()));
                    } else {
                        str2 = "";
                    }
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append(app.info.seInfo);
                    sb2.append(TextUtils.isEmpty(app.info.seInfoUser) ? str2 : app.info.seInfoUser);
                    String seInfo = sb2.toString();
                    return startProcessLocked(hostingRecord, "android.app.ActivityThread", app, uid2, gids, runtimeFlags3, mountExternal, seInfo, requiredAbi2, instructionSet, invokeWith, startTime);
                } catch (RuntimeException e5) {
                    e = e5;
                    str = TAG;
                    processRecord = app;
                    z = z2;
                }
            } catch (RuntimeException e6) {
                e = e6;
            }
        } catch (RemoteException e7) {
            throw e7.rethrowAsRuntimeException();
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:17:0x00a2  */
    /* JADX WARN: Removed duplicated region for block: B:22:0x00e2  */
    @com.android.internal.annotations.GuardedBy({"mService"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    boolean startProcessLocked(com.android.server.am.HostingRecord r29, final java.lang.String r30, final com.android.server.am.ProcessRecord r31, int r32, final int[] r33, final int r34, final int r35, java.lang.String r36, final java.lang.String r37, final java.lang.String r38, final java.lang.String r39, long r40) {
        /*
            Method dump skipped, instructions count: 316
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProcessList.startProcessLocked(com.android.server.am.HostingRecord, java.lang.String, com.android.server.am.ProcessRecord, int, int[], int, int, java.lang.String, java.lang.String, java.lang.String, java.lang.String, long):boolean");
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r3v0 */
    /* JADX WARN: Type inference failed for: r3v2, types: [java.lang.String] */
    /* JADX WARN: Type inference failed for: r3v3 */
    /* JADX WARN: Type inference failed for: r4v0 */
    /* JADX WARN: Type inference failed for: r4v2, types: [com.android.server.am.ProcessRecord] */
    /* JADX WARN: Type inference failed for: r4v3 */
    public /* synthetic */ void lambda$startProcessLocked$0$ProcessList(ProcessRecord app, String entryPoint, int[] gids, int runtimeFlags, int mountExternal, String requiredAbi, String instructionSet, String invokeWith, long startSeq) {
        long j;
        ProcessRecord processRecord;
        Process.ProcessStartResult startResult;
        try {
            processRecord = entryPoint;
            j = app;
            try {
                startResult = startProcess(app.hostingRecord, processRecord, j, app.startUid, gids, runtimeFlags, mountExternal, app.seInfo, requiredAbi, instructionSet, invokeWith, app.startTime);
            } catch (RuntimeException e) {
                e = e;
                processRecord = app;
                j = startSeq;
            }
        } catch (RuntimeException e2) {
            e = e2;
            j = startSeq;
            processRecord = app;
        }
        try {
            try {
                synchronized (this.mService) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        handleProcessStartedLocked(app, startResult, startSeq);
                        ActivityManagerService.resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                th = th2;
            }
        } catch (RuntimeException e3) {
            e = e3;
            RuntimeException e4 = e;
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    Slog.e(TAG, "Failure starting process " + processRecord.processName, e4);
                    this.mPendingStarts.remove(j);
                    processRecord.pendingStart = false;
                    this.mService.forceStopPackageLocked(processRecord.info.packageName, UserHandle.getAppId(processRecord.uid), false, false, true, false, false, processRecord.userId, "start failure");
                } finally {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    @GuardedBy({"mService"})
    public void killAppZygoteIfNeededLocked(AppZygote appZygote) {
        ApplicationInfo appInfo = appZygote.getAppInfo();
        ArrayList<ProcessRecord> zygoteProcesses = this.mAppZygoteProcesses.get(appZygote);
        if (zygoteProcesses != null && zygoteProcesses.size() == 0) {
            this.mAppZygotes.remove(appInfo.processName, appInfo.uid);
            this.mAppZygoteProcesses.remove(appZygote);
            this.mAppIsolatedUidRangeAllocator.freeUidRangeLocked(appInfo);
            appZygote.stopZygote();
        }
    }

    @GuardedBy({"mService"})
    private void removeProcessFromAppZygoteLocked(ProcessRecord app) {
        IsolatedUidRange appUidRange = this.mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info.processName, app.hostingRecord.getDefiningUid());
        if (appUidRange != null) {
            appUidRange.freeIsolatedUidLocked(app.uid);
        }
        AppZygote appZygote = (AppZygote) this.mAppZygotes.get(app.info.processName, app.hostingRecord.getDefiningUid());
        if (appZygote != null) {
            ArrayList<ProcessRecord> zygoteProcesses = this.mAppZygoteProcesses.get(appZygote);
            zygoteProcesses.remove(app);
            if (zygoteProcesses.size() == 0) {
                this.mService.mHandler.removeMessages(71);
                if (app.removed) {
                    killAppZygoteIfNeededLocked(appZygote);
                    return;
                }
                Message msg = this.mService.mHandler.obtainMessage(71);
                msg.obj = appZygote;
                this.mService.mHandler.sendMessageDelayed(msg, 5000L);
            }
        }
    }

    private AppZygote createAppZygoteForProcessIfNeeded(ProcessRecord app) {
        AppZygote appZygote;
        ArrayList<ProcessRecord> zygoteProcessList;
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                int uid = app.hostingRecord.getDefiningUid();
                appZygote = (AppZygote) this.mAppZygotes.get(app.info.processName, uid);
                if (appZygote == null) {
                    if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                        Slog.d(TAG, "Creating new app zygote.");
                    }
                    IsolatedUidRange uidRange = this.mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info.processName, app.hostingRecord.getDefiningUid());
                    int userId = UserHandle.getUserId(uid);
                    int firstUid = UserHandle.getUid(userId, uidRange.mFirstUid);
                    int lastUid = UserHandle.getUid(userId, uidRange.mLastUid);
                    ApplicationInfo appInfo = new ApplicationInfo(app.info);
                    appInfo.packageName = app.hostingRecord.getDefiningPackageName();
                    appInfo.uid = uid;
                    appZygote = new AppZygote(appInfo, uid, firstUid, lastUid);
                    this.mAppZygotes.put(app.info.processName, uid, appZygote);
                    zygoteProcessList = new ArrayList<>();
                    this.mAppZygoteProcesses.put(appZygote, zygoteProcessList);
                } else {
                    if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                        Slog.d(TAG, "Reusing existing app zygote.");
                    }
                    this.mService.mHandler.removeMessages(71, appZygote);
                    zygoteProcessList = this.mAppZygoteProcesses.get(appZygote);
                }
                zygoteProcessList.add(app);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return appZygote;
    }

    private Process.ProcessStartResult startProcess(HostingRecord hostingRecord, String entryPoint, ProcessRecord app, int uid, int[] gids, int runtimeFlags, int mountExternal, String seInfo, String requiredAbi, String instructionSet, String invokeWith, long startTime) {
        Process.ProcessStartResult startResult;
        try {
            Trace.traceBegin(64L, "Start proc: " + app.processName);
            checkSlow(startTime, "startProcess: asking zygote to start proc");
            if (hostingRecord.usesWebviewZygote()) {
                String str = app.processName;
                int i = app.info.targetSdkVersion;
                String str2 = app.info.dataDir;
                String str3 = app.info.packageName;
                startResult = Process.startWebView(entryPoint, str, uid, uid, gids, runtimeFlags, mountExternal, i, seInfo, requiredAbi, instructionSet, str2, null, str3, new String[]{"seq=" + app.startSeq});
            } else if (hostingRecord.usesAppZygote()) {
                AppZygote appZygote = createAppZygoteForProcessIfNeeded(app);
                ChildZygoteProcess process = appZygote.getProcess();
                String str4 = app.processName;
                int i2 = app.info.targetSdkVersion;
                String str5 = app.info.dataDir;
                String str6 = app.info.packageName;
                startResult = process.start(entryPoint, str4, uid, uid, gids, runtimeFlags, mountExternal, i2, seInfo, requiredAbi, instructionSet, str5, (String) null, str6, false, new String[]{"seq=" + app.startSeq});
            } else {
                String str7 = app.processName;
                int i3 = app.info.targetSdkVersion;
                String str8 = app.info.dataDir;
                String str9 = app.info.packageName;
                startResult = Process.start(entryPoint, str7, uid, uid, gids, runtimeFlags, mountExternal, i3, seInfo, requiredAbi, instructionSet, str8, invokeWith, str9, new String[]{"seq=" + app.startSeq});
            }
            checkSlow(startTime, "startProcess: returned from zygote!");
            return startResult;
        } finally {
            Trace.traceEnd(64L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final void startProcessLocked(ProcessRecord app, HostingRecord hostingRecord) {
        startProcessLocked(app, hostingRecord, null);
    }

    @GuardedBy({"mService"})
    final boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord, String abiOverride) {
        return startProcessLocked(app, hostingRecord, false, false, abiOverride);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final ProcessRecord startProcessLocked(String processName, ApplicationInfo info, boolean knownToBeDead, int intentFlags, HostingRecord hostingRecord, boolean allowWhileBooting, boolean isolated, int isolatedUid, boolean keepIfLarge, String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler) {
        ProcessRecord app;
        long startTime = SystemClock.elapsedRealtime();
        if (isolated) {
            app = null;
        } else {
            ProcessRecord app2 = getProcessRecordLocked(processName, info.uid, keepIfLarge);
            checkSlow(startTime, "startProcess: after getProcessRecord");
            if ((intentFlags & 4) != 0) {
                if (this.mService.mAppErrors.isBadProcessLocked(info)) {
                    if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                        Slog.v(TAG, "Bad process: " + info.uid + SliceClientPermissions.SliceAuthority.DELIMITER + info.processName);
                    }
                    return null;
                }
            } else {
                if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                    Slog.v(TAG, "Clearing bad process: " + info.uid + SliceClientPermissions.SliceAuthority.DELIMITER + info.processName);
                }
                this.mService.mAppErrors.resetProcessCrashTimeLocked(info);
                if (this.mService.mAppErrors.isBadProcessLocked(info)) {
                    EventLog.writeEvent((int) EventLogTags.AM_PROC_GOOD, Integer.valueOf(UserHandle.getUserId(info.uid)), Integer.valueOf(info.uid), info.processName);
                    this.mService.mAppErrors.clearBadProcessLocked(info);
                    if (app2 != null) {
                        app2.bad = false;
                    }
                }
            }
            app = app2;
        }
        if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
            StringBuilder sb = new StringBuilder();
            sb.append("startProcess: name=");
            sb.append(processName);
            sb.append(" app=");
            sb.append(app);
            sb.append(" knownToBeDead=");
            sb.append(knownToBeDead);
            sb.append(" thread=");
            sb.append(app != null ? app.thread : null);
            sb.append(" pid=");
            sb.append(app != null ? app.pid : -1);
            Slog.v(TAG, sb.toString());
        }
        if (app != null && app.pid > 0) {
            if ((!knownToBeDead && !app.killed) || app.thread == null) {
                if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                    Slog.v(TAG, "App already running: " + app);
                }
                app.addPackage(info.packageName, info.longVersionCode, this.mService.mProcessStats);
                checkSlow(startTime, "startProcess: done, added package to proc");
                return app;
            }
            if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                Slog.v(TAG, "App died: " + app);
            }
            checkSlow(startTime, "startProcess: bad proc running, killing");
            killProcessGroup(app.uid, app.pid);
            this.mService.handleAppDiedLocked(app, true, true);
            checkSlow(startTime, "startProcess: done killing old proc");
        }
        if (app == null) {
            checkSlow(startTime, "startProcess: creating new process record");
            ProcessRecord app3 = newProcessRecordLocked(info, processName, isolated, isolatedUid, hostingRecord);
            if (app3 == null) {
                Slog.w(TAG, "Failed making new process record for " + processName + SliceClientPermissions.SliceAuthority.DELIMITER + info.uid + " isolated=" + isolated);
                return null;
            }
            app3.crashHandler = crashHandler;
            app3.isolatedEntryPoint = entryPoint;
            app3.isolatedEntryPointArgs = entryPointArgs;
            checkSlow(startTime, "startProcess: done creating new process record");
            app = app3;
        } else {
            app.addPackage(info.packageName, info.longVersionCode, this.mService.mProcessStats);
            checkSlow(startTime, "startProcess: added package to existing proc");
        }
        if (this.mService.mProcessesReady || this.mService.isAllowedWhileBooting(info) || allowWhileBooting) {
            checkSlow(startTime, "startProcess: stepping in to startProcess");
            boolean success = startProcessLocked(app, hostingRecord, abiOverride);
            checkSlow(startTime, "startProcess: done starting proc!");
            if (success) {
                return app;
            }
            return null;
        }
        if (!this.mService.mProcessesOnHold.contains(app)) {
            this.mService.mProcessesOnHold.add(app);
        }
        if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
            Slog.v(TAG, "System not ready, putting on hold: " + app);
        }
        checkSlow(startTime, "startProcess: returning with proc on hold");
        return app;
    }

    @GuardedBy({"mService"})
    private String isProcStartValidLocked(ProcessRecord app, long expectedStartSeq) {
        if (app.killedByAm) {
            sb = 0 == 0 ? new StringBuilder() : null;
            sb.append("killedByAm=true;");
        }
        if (this.mProcessNames.get(app.processName, app.uid) != app) {
            if (sb == null) {
                sb = new StringBuilder();
            }
            sb.append("No entry in mProcessNames;");
        }
        if (!app.pendingStart) {
            if (sb == null) {
                sb = new StringBuilder();
            }
            sb.append("pendingStart=false;");
        }
        if (app.startSeq > expectedStartSeq) {
            if (sb == null) {
                sb = new StringBuilder();
            }
            sb.append("seq=" + app.startSeq + ",expected=" + expectedStartSeq + ";");
        }
        if (sb == null) {
            return null;
        }
        return sb.toString();
    }

    @GuardedBy({"mService"})
    private boolean handleProcessStartedLocked(ProcessRecord pending, Process.ProcessStartResult startResult, long expectedStartSeq) {
        if (this.mPendingStarts.get(expectedStartSeq) == null) {
            if (pending.pid == startResult.pid) {
                pending.setUsingWrapper(startResult.usingWrapper);
                return false;
            }
            return false;
        }
        return handleProcessStartedLocked(pending, startResult.pid, startResult.usingWrapper, expectedStartSeq, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public boolean handleProcessStartedLocked(ProcessRecord app, int pid, boolean usingWrapper, long expectedStartSeq, boolean procAttached) {
        String str;
        ProcessRecord oldApp;
        this.mPendingStarts.remove(expectedStartSeq);
        String reason = isProcStartValidLocked(app, expectedStartSeq);
        if (reason != null) {
            Slog.w(TAG, app + " start not valid, killing pid=" + pid + ", " + reason);
            app.pendingStart = false;
            Process.killProcessQuiet(pid);
            Process.killProcessGroup(app.uid, app.pid);
            return false;
        }
        this.mService.mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);
        checkSlow(app.startTime, "startProcess: done updating battery stats");
        Object[] objArr = new Object[6];
        objArr[0] = Integer.valueOf(UserHandle.getUserId(app.startUid));
        objArr[1] = Integer.valueOf(pid);
        objArr[2] = Integer.valueOf(app.startUid);
        objArr[3] = app.processName;
        objArr[4] = app.hostingRecord.getType();
        objArr[5] = app.hostingRecord.getName() != null ? app.hostingRecord.getName() : "";
        EventLog.writeEvent((int) EventLogTags.AM_PROC_START, objArr);
        try {
            AppGlobals.getPackageManager().logAppProcessStartIfNeeded(app.processName, app.uid, app.seInfo, app.info.sourceDir, pid);
        } catch (RemoteException e) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("AP_Init:[");
        sb.append(app.hostingRecord.getType());
        sb.append("]:[");
        sb.append(app.processName);
        if (app.hostingRecord.getName() != null) {
            str = "]:[" + app.hostingRecord.getName();
        } else {
            str = "";
        }
        sb.append(str);
        sb.append("]:pid:");
        sb.append(pid);
        sb.append(app.isPersistent() ? ":(PersistAP)" : "");
        BootEvent.addBootEvent(sb.toString());
        if (app.isPersistent()) {
            Watchdog.getInstance().processStarted(app.processName, pid);
        }
        checkSlow(app.startTime, "startProcess: building log message");
        StringBuilder buf = this.mStringBuilder;
        buf.setLength(0);
        buf.append("Start proc ");
        buf.append(pid);
        buf.append(':');
        buf.append(app.processName);
        buf.append('/');
        UserHandle.formatUid(buf, app.startUid);
        if (app.isolatedEntryPoint != null) {
            buf.append(" [");
            buf.append(app.isolatedEntryPoint);
            buf.append("]");
        }
        buf.append(" for ");
        buf.append(app.hostingRecord.getType());
        if (app.hostingRecord.getName() != null) {
            buf.append(" ");
            buf.append(app.hostingRecord.getName());
        }
        this.mService.reportUidInfoMessageLocked(TAG, buf.toString(), app.startUid);
        app.setPid(pid);
        app.setUsingWrapper(usingWrapper);
        app.pendingStart = false;
        checkSlow(app.startTime, "startProcess: starting to update pids map");
        synchronized (this.mService.mPidsSelfLocked) {
            oldApp = this.mService.mPidsSelfLocked.get(pid);
        }
        if (oldApp != null && !app.isolated) {
            Slog.wtf(TAG, "handleProcessStartedLocked process:" + app.processName + " startSeq:" + app.startSeq + " pid:" + pid + " belongs to another existing app:" + oldApp.processName + " startSeq:" + oldApp.startSeq);
            this.mService.cleanUpApplicationRecordLocked(oldApp, false, false, -1, true);
        }
        this.mService.mPidsSelfLocked.put(app);
        synchronized (this.mService.mPidsSelfLocked) {
            if (!procAttached) {
                Message msg = this.mService.mHandler.obtainMessage(20);
                msg.obj = app;
                this.mService.mHandler.sendMessageDelayed(msg, usingWrapper ? 1200000L : 20000L);
            }
        }
        checkSlow(app.startTime, "startProcess: done updating pids map");
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void removeLruProcessLocked(ProcessRecord app) {
        int lrui = this.mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            if (!app.killed) {
                if (app.isPersistent()) {
                    Slog.w(TAG, "Removing persistent process that hasn't been killed: " + app);
                } else {
                    Slog.wtfStack(TAG, "Removing process that hasn't been killed: " + app);
                    if (app.pid > 0) {
                        Slog.i(TAG, "Removing process that hasn't been killed: " + app.pid);
                        Process.killProcessQuiet(app.pid);
                        killProcessGroup(app.uid, app.pid);
                    } else {
                        app.pendingStart = false;
                    }
                }
            }
            int i = this.mLruProcessActivityStart;
            if (lrui < i) {
                this.mLruProcessActivityStart = i - 1;
            }
            int i2 = this.mLruProcessServiceStart;
            if (lrui < i2) {
                this.mLruProcessServiceStart = i2 - 1;
            }
            this.mLruProcesses.remove(lrui);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public boolean killPackageProcessesLocked(String packageName, int appId, int userId, int minOomAdj, String reason) {
        return killPackageProcessesLocked(packageName, appId, userId, minOomAdj, false, true, true, false, false, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:50:0x00b6  */
    /* JADX WARN: Removed duplicated region for block: B:91:0x00b5 A[SYNTHETIC] */
    @com.android.internal.annotations.GuardedBy({"mService"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public final boolean killPackageProcessesLocked(java.lang.String r20, int r21, int r22, int r23, boolean r24, boolean r25, boolean r26, boolean r27, boolean r28, java.lang.String r29) {
        /*
            Method dump skipped, instructions count: 375
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProcessList.killPackageProcessesLocked(java.lang.String, int, int, int, boolean, boolean, boolean, boolean, boolean, java.lang.String):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart, boolean allowRestart, String reason) {
        String name = app.processName;
        int uid = app.uid;
        if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
            Slog.d(TAG, "Force removing proc " + app.toShortString() + " (" + name + SliceClientPermissions.SliceAuthority.DELIMITER + uid + ")");
        }
        ProcessRecord old = (ProcessRecord) this.mProcessNames.get(name, uid);
        if (old != app) {
            Slog.w(TAG, "Ignoring remove of inactive process: " + app);
            return false;
        }
        removeProcessNameLocked(name, uid);
        this.mService.mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
        boolean needRestart = false;
        if ((app.pid > 0 && app.pid != ActivityManagerService.MY_PID) || (app.pid == 0 && app.pendingStart)) {
            int pid = app.pid;
            if (pid > 0) {
                this.mService.mPidsSelfLocked.remove(app);
                this.mService.mHandler.removeMessages(20, app);
                this.mService.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
                if (app.isolated) {
                    this.mService.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
                    this.mService.getPackageManagerInternalLocked().removeIsolatedUid(app.uid);
                }
            }
            boolean willRestart = false;
            if (app.isPersistent() && !app.isolated) {
                if (!callerWillRestart) {
                    willRestart = true;
                } else {
                    needRestart = true;
                }
            }
            if (app.isPersistent() && app.isInstalling()) {
                willRestart = false;
            }
            app.kill(reason, true);
            this.mService.handleAppDiedLocked(app, willRestart, allowRestart);
            if (willRestart) {
                removeLruProcessLocked(app);
                this.mService.addAppLocked(app.info, null, false, null);
            }
        } else {
            this.mRemovedProcesses.add(app);
        }
        return needRestart;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final void addProcessNameLocked(ProcessRecord proc) {
        ProcessRecord old = removeProcessNameLocked(proc.processName, proc.uid);
        if (old == proc && proc.isPersistent()) {
            Slog.w(TAG, "Re-adding persistent process " + proc);
        } else if (old != null) {
            Slog.wtf(TAG, "Already have existing proc " + old + " when adding " + proc);
        }
        UidRecord uidRec = this.mActiveUids.get(proc.uid);
        if (uidRec == null) {
            uidRec = new UidRecord(proc.uid);
            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                Slog.i(TAG, "Creating new process uid: " + uidRec);
            }
            if (Arrays.binarySearch(this.mService.mDeviceIdleTempWhitelist, UserHandle.getAppId(proc.uid)) >= 0 || this.mService.mPendingTempWhitelist.indexOfKey(proc.uid) >= 0) {
                uidRec.curWhitelist = true;
                uidRec.setWhitelist = true;
            }
            uidRec.updateHasInternetPermission();
            this.mActiveUids.put(proc.uid, uidRec);
            EventLogTags.writeAmUidRunning(uidRec.uid);
            this.mService.noteUidProcessState(uidRec.uid, uidRec.getCurProcState());
        }
        proc.uidRecord = uidRec;
        proc.renderThreadTid = 0;
        uidRec.numProcs++;
        this.mProcessNames.put(proc.processName, proc.uid, proc);
        if (proc.isolated) {
            this.mIsolatedProcesses.put(proc.uid, proc);
        }
    }

    @GuardedBy({"mService"})
    private IsolatedUidRange getOrCreateIsolatedUidRangeLocked(ApplicationInfo info, HostingRecord hostingRecord) {
        if (hostingRecord == null || !hostingRecord.usesAppZygote()) {
            return this.mGlobalIsolatedUids;
        }
        return this.mAppIsolatedUidRangeAllocator.getOrCreateIsolatedUidRangeLocked(info.processName, hostingRecord.getDefiningUid());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final ProcessRecord newProcessRecordLocked(ApplicationInfo info, String customProcess, boolean isolated, int isolatedUid, HostingRecord hostingRecord) {
        String proc = customProcess != null ? customProcess : info.processName;
        int userId = UserHandle.getUserId(info.uid);
        int uid = info.uid;
        if (isolated) {
            if (isolatedUid == 0) {
                IsolatedUidRange uidRange = getOrCreateIsolatedUidRangeLocked(info, hostingRecord);
                if (uidRange == null || (uid = uidRange.allocateIsolatedUidLocked(userId)) == -1) {
                    return null;
                }
            } else {
                uid = isolatedUid;
            }
            this.mService.getPackageManagerInternalLocked().addIsolatedUid(uid, info.uid);
            this.mService.mBatteryStatsService.addIsolatedUid(uid, info.uid);
            StatsLog.write(43, info.uid, uid, 1);
        }
        ProcessRecord r = new ProcessRecord(this.mService, info, proc, uid);
        if (!this.mService.mBooted && !this.mService.mBooting && userId == 0 && (info.flags & 9) == 9) {
            r.setCurrentSchedulingGroup(2);
            r.setSchedGroup = 2;
            r.setPersistent(true);
            r.maxAdj = PERSISTENT_PROC_ADJ;
        }
        if (isolated && isolatedUid != 0) {
            r.maxAdj = PERSISTENT_SERVICE_ADJ;
        }
        addProcessNameLocked(r);
        return r;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final ProcessRecord removeProcessNameLocked(String name, int uid) {
        return removeProcessNameLocked(name, uid, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final ProcessRecord removeProcessNameLocked(String name, int uid, ProcessRecord expecting) {
        ProcessRecord old = (ProcessRecord) this.mProcessNames.get(name, uid);
        if (expecting == null || old == expecting) {
            this.mProcessNames.m13remove(name, uid);
        }
        if (old != null && old.uidRecord != null) {
            old.uidRecord.numProcs--;
            if (old.uidRecord.numProcs == 0) {
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG, "No more processes in " + old.uidRecord);
                }
                this.mService.enqueueUidChangeLocked(old.uidRecord, -1, 1);
                EventLogTags.writeAmUidStopped(uid);
                this.mActiveUids.remove(uid);
                this.mService.noteUidProcessState(uid, 21);
            }
            old.uidRecord = null;
        }
        this.mIsolatedProcesses.remove(uid);
        this.mGlobalIsolatedUids.freeIsolatedUidLocked(uid);
        ProcessRecord record = expecting != null ? expecting : old;
        if (record != null && record.appZygote) {
            removeProcessFromAppZygoteLocked(record);
        }
        return old;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void updateCoreSettingsLocked(Bundle settings) {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord processRecord = this.mLruProcesses.get(i);
            try {
                if (processRecord.thread != null) {
                    processRecord.thread.setCoreSettings(settings);
                }
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void killAllBackgroundProcessesExceptLocked(int minTargetSdk, int maxProcState) {
        ArrayList<ProcessRecord> procs = new ArrayList<>();
        int NP = this.mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = (SparseArray) this.mProcessNames.getMap().valueAt(ip);
            int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.removed || ((minTargetSdk < 0 || app.info.targetSdkVersion < minTargetSdk) && (maxProcState < 0 || app.setProcState > maxProcState))) {
                    procs.add(app);
                }
            }
        }
        int N = procs.size();
        for (int i = 0; i < N; i++) {
            removeProcessLocked(procs.get(i), false, true, "kill all background except");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void updateAllTimePrefsLocked(int timePref) {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = this.mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.updateTimePrefs(timePref);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to update preferences for: " + r.info.processName);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAllHttpProxy() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
                    ProcessRecord r = this.mLruProcesses.get(i);
                    if (r.pid != ActivityManagerService.MY_PID && r.thread != null && !r.isolated) {
                        try {
                            r.thread.updateHttpProxy();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to update http proxy for: " + r.info.processName);
                        }
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        ActivityThread.updateHttpProxy(this.mService.mContext);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void clearAllDnsCacheLocked() {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = this.mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.clearDnsCache();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to clear dns cache for: " + r.info.processName);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void handleAllTrustStorageUpdateLocked() {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = this.mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.handleTrustStorageUpdate();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to handle trust storage update for: " + r.info.processName);
                }
            }
        }
    }

    @GuardedBy({"mService"})
    int updateLruProcessInternalLocked(ProcessRecord app, long now, int index, int lruSeq, String what, Object obj, ProcessRecord srcApp) {
        app.lastActivityTime = now;
        if (app.hasActivitiesOrRecentTasks()) {
            return index;
        }
        int lrui = this.mLruProcesses.lastIndexOf(app);
        if (lrui < 0) {
            Slog.wtf(TAG, "Adding dependent process " + app + " not on LRU list: " + what + " " + obj + " from " + srcApp);
            return index;
        } else if (lrui >= index) {
            return index;
        } else {
            int i = this.mLruProcessActivityStart;
            if (lrui >= i && index < i) {
                return index;
            }
            this.mLruProcesses.remove(lrui);
            if (index > 0) {
                index--;
            }
            if (ActivityManagerDebugConfig.DEBUG_LRU) {
                Slog.d(TAG, "Moving dep from " + lrui + " to " + index + " in LRU list: " + app);
            }
            this.mLruProcesses.add(index, app);
            app.lruSeq = lruSeq;
            return index;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:86:0x01f9, code lost:
        if (com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU == false) goto L121;
     */
    /* JADX WARN: Code restructure failed: missing block: B:87:0x01fb, code lost:
        android.util.Slog.d(com.android.server.am.ProcessList.TAG, "Already found a different group: connGroup=" + r11 + " group=" + r4.connectionGroup);
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x021a, code lost:
        if (com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU == false) goto L121;
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x021c, code lost:
        android.util.Slog.d(com.android.server.am.ProcessList.TAG, "Already found a different activity: connUid=" + r10 + " uid=" + r4.info.uid);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void updateClientActivitiesOrdering(com.android.server.am.ProcessRecord r17, int r18, int r19, int r20) {
        /*
            Method dump skipped, instructions count: 799
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProcessList.updateClientActivitiesOrdering(com.android.server.am.ProcessRecord, int, int, int):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void updateLruProcessLocked(ProcessRecord app, boolean activityChange, ProcessRecord client) {
        int nextIndex;
        int j;
        long now;
        boolean hasActivity = app.hasActivitiesOrRecentTasks() || app.hasClientActivities() || app.treatLikeActivity;
        if (activityChange || !hasActivity) {
            this.mLruSeq++;
            long now2 = SystemClock.uptimeMillis();
            app.lastActivityTime = now2;
            if (hasActivity) {
                int N = this.mLruProcesses.size();
                if (N > 0 && this.mLruProcesses.get(N - 1) == app) {
                    if (ActivityManagerDebugConfig.DEBUG_LRU) {
                        Slog.d(TAG, "Not moving, already top activity: " + app);
                        return;
                    }
                    return;
                }
            } else {
                int i = this.mLruProcessServiceStart;
                if (i > 0 && this.mLruProcesses.get(i - 1) == app) {
                    if (ActivityManagerDebugConfig.DEBUG_LRU) {
                        Slog.d(TAG, "Not moving, already top other: " + app);
                        return;
                    }
                    return;
                }
            }
            int lrui = this.mLruProcesses.lastIndexOf(app);
            if (app.isPersistent() && lrui >= 0) {
                if (ActivityManagerDebugConfig.DEBUG_LRU) {
                    Slog.d(TAG, "Not moving, persistent: " + app);
                    return;
                }
                return;
            }
            if (lrui >= 0) {
                int i2 = this.mLruProcessActivityStart;
                if (lrui < i2) {
                    this.mLruProcessActivityStart = i2 - 1;
                }
                int i3 = this.mLruProcessServiceStart;
                if (lrui < i3) {
                    this.mLruProcessServiceStart = i3 - 1;
                }
                this.mLruProcesses.remove(lrui);
            }
            int nextActivityIndex = -1;
            if (hasActivity) {
                int N2 = this.mLruProcesses.size();
                int nextIndex2 = this.mLruProcessServiceStart;
                if (!app.hasActivitiesOrRecentTasks() && !app.treatLikeActivity && this.mLruProcessActivityStart < N2 - 1) {
                    if (ActivityManagerDebugConfig.DEBUG_LRU) {
                        Slog.d(TAG, "Adding to second-top of LRU activity list: " + app + " group=" + app.connectionGroup + " importance=" + app.connectionImportance);
                    }
                    int pos = N2 - 1;
                    while (pos > this.mLruProcessActivityStart) {
                        ProcessRecord posproc = this.mLruProcesses.get(pos);
                        if (posproc.info.uid == app.info.uid) {
                            break;
                        }
                        pos--;
                    }
                    this.mLruProcesses.add(pos, app);
                    int endIndex = pos - 1;
                    if (endIndex < this.mLruProcessActivityStart) {
                        endIndex = this.mLruProcessActivityStart;
                    }
                    nextActivityIndex = endIndex;
                    updateClientActivitiesOrdering(app, pos, this.mLruProcessActivityStart, endIndex);
                } else {
                    if (ActivityManagerDebugConfig.DEBUG_LRU) {
                        Slog.d(TAG, "Adding to top of LRU activity list: " + app);
                    }
                    this.mLruProcesses.add(app);
                    nextActivityIndex = this.mLruProcesses.size() - 1;
                }
                nextIndex = nextIndex2;
            } else {
                int index = this.mLruProcessServiceStart;
                if (client != null) {
                    int clientIndex = this.mLruProcesses.lastIndexOf(client);
                    if (ActivityManagerDebugConfig.DEBUG_LRU && clientIndex < 0) {
                        Slog.d(TAG, "Unknown client " + client + " when updating " + app);
                    }
                    if (clientIndex <= lrui) {
                        clientIndex = lrui;
                    }
                    if (clientIndex >= 0 && index > clientIndex) {
                        index = clientIndex;
                    }
                }
                if (ActivityManagerDebugConfig.DEBUG_LRU) {
                    Slog.d(TAG, "Adding at " + index + " of LRU list: " + app);
                }
                this.mLruProcesses.add(index, app);
                nextIndex = index - 1;
                this.mLruProcessActivityStart++;
                this.mLruProcessServiceStart++;
                if (index > 1) {
                    updateClientActivitiesOrdering(app, this.mLruProcessServiceStart - 1, 0, index - 1);
                }
            }
            app.lruSeq = this.mLruSeq;
            int nextIndex3 = nextIndex;
            int j2 = app.connections.size() - 1;
            int nextActivityIndex2 = nextActivityIndex;
            while (j2 >= 0) {
                ConnectionRecord cr = app.connections.valueAt(j2);
                if (cr.binding == null || cr.serviceDead || cr.binding.service == null || cr.binding.service.app == null || cr.binding.service.app.lruSeq == this.mLruSeq || (cr.flags & 1073742128) != 0) {
                    j = j2;
                    now = now2;
                } else if (cr.binding.service.app.isPersistent()) {
                    j = j2;
                    now = now2;
                } else if (cr.binding.service.app.hasClientActivities()) {
                    if (nextActivityIndex2 >= 0) {
                        j = j2;
                        now = now2;
                        nextActivityIndex2 = updateLruProcessInternalLocked(cr.binding.service.app, now2, nextActivityIndex2, this.mLruSeq, "service connection", cr, app);
                    } else {
                        j = j2;
                        now = now2;
                    }
                } else {
                    j = j2;
                    now = now2;
                    nextIndex3 = updateLruProcessInternalLocked(cr.binding.service.app, now, nextIndex3, this.mLruSeq, "service connection", cr, app);
                }
                j2 = j - 1;
                now2 = now;
            }
            long now3 = now2;
            for (int j3 = app.conProviders.size() - 1; j3 >= 0; j3--) {
                ContentProviderRecord cpr = app.conProviders.get(j3).provider;
                if (cpr.proc != null && cpr.proc.lruSeq != this.mLruSeq && !cpr.proc.isPersistent()) {
                    nextIndex3 = updateLruProcessInternalLocked(cpr.proc, now3, nextIndex3, this.mLruSeq, "provider reference", cpr, app);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ProcessRecord getLRURecordForAppLocked(IApplicationThread thread) {
        IBinder threadBinder = thread.asBinder();
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord rec = this.mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return rec;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean haveBackgroundProcessLocked() {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord rec = this.mLruProcesses.get(i);
            if (rec.thread != null && rec.setProcState >= 17) {
                return true;
            }
        }
        return false;
    }

    private static int procStateToImportance(int procState, int memAdj, ActivityManager.RunningAppProcessInfo currApp, int clientTargetSdk) {
        int imp = ActivityManager.RunningAppProcessInfo.procStateToImportanceForTargetSdk(procState, clientTargetSdk);
        if (imp == HEAVY_WEIGHT_APP_ADJ) {
            currApp.lru = memAdj;
        } else {
            currApp.lru = 0;
        }
        return imp;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void fillInProcMemInfoLocked(ProcessRecord app, ActivityManager.RunningAppProcessInfo outInfo, int clientTargetSdk) {
        outInfo.pid = app.pid;
        outInfo.uid = app.info.uid;
        if (this.mService.mAtmInternal.isHeavyWeightProcess(app.getWindowProcessController())) {
            outInfo.flags |= 1;
        }
        if (app.isPersistent()) {
            outInfo.flags |= 2;
        }
        if (app.hasActivities()) {
            outInfo.flags |= 4;
        }
        outInfo.lastTrimLevel = app.trimMemoryLevel;
        int adj = app.curAdj;
        int procState = app.getCurProcState();
        outInfo.importance = procStateToImportance(procState, adj, outInfo, clientTargetSdk);
        outInfo.importanceReasonCode = app.adjTypeCode;
        outInfo.processState = app.getCurProcState();
        outInfo.isFocused = app == this.mService.getTopAppLocked();
        outInfo.lastActivityTime = app.lastActivityTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcessesLocked(boolean allUsers, int userId, boolean allUids, int callingUid, int clientTargetSdk) {
        List<ActivityManager.RunningAppProcessInfo> runList = null;
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = this.mLruProcesses.get(i);
            if ((allUsers || app.userId == userId) && ((allUids || app.uid == callingUid) && app.thread != null && !app.isCrashing() && !app.isNotResponding())) {
                ActivityManager.RunningAppProcessInfo currApp = new ActivityManager.RunningAppProcessInfo(app.processName, app.pid, app.getPackageList());
                fillInProcMemInfoLocked(app, currApp, clientTargetSdk);
                if (app.adjSource instanceof ProcessRecord) {
                    currApp.importanceReasonPid = ((ProcessRecord) app.adjSource).pid;
                    currApp.importanceReasonImportance = ActivityManager.RunningAppProcessInfo.procStateToImportance(app.adjSourceProcState);
                } else if (app.adjSource instanceof ActivityServiceConnectionsHolder) {
                    ActivityServiceConnectionsHolder r = (ActivityServiceConnectionsHolder) app.adjSource;
                    int pid = r.getActivityPid();
                    if (pid != -1) {
                        currApp.importanceReasonPid = pid;
                    }
                }
                if (app.adjTarget instanceof ComponentName) {
                    currApp.importanceReasonComponent = (ComponentName) app.adjTarget;
                }
                if (runList == null) {
                    runList = new ArrayList<>();
                }
                runList.add(currApp);
            }
        }
        return runList;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public int getLruSizeLocked() {
        return this.mLruProcesses.size();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void dumpLruListHeaderLocked(PrintWriter pw) {
        pw.print("  Process LRU list (sorted by oom_adj, ");
        pw.print(this.mLruProcesses.size());
        pw.print(" total, non-act at ");
        pw.print(this.mLruProcesses.size() - this.mLruProcessActivityStart);
        pw.print(", non-svc at ");
        pw.print(this.mLruProcesses.size() - this.mLruProcessServiceStart);
        pw.println("):");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public ArrayList<ProcessRecord> collectProcessesLocked(int start, boolean allPkgs, String[] args) {
        if (args != null && args.length > start && args[start].charAt(0) != '-') {
            ArrayList<ProcessRecord> procs = new ArrayList<>();
            int pid = -1;
            try {
                pid = Integer.parseInt(args[start]);
            } catch (NumberFormatException e) {
            }
            for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord proc = this.mLruProcesses.get(i);
                if (proc.pid > 0 && proc.pid == pid) {
                    procs.add(proc);
                } else if (allPkgs && proc.pkgList != null && proc.pkgList.containsKey(args[start])) {
                    procs.add(proc);
                } else if (proc.processName.equals(args[start])) {
                    procs.add(proc);
                }
            }
            int i2 = procs.size();
            if (i2 <= 0) {
                return null;
            }
            return procs;
        }
        return new ArrayList<>(this.mLruProcesses);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void updateApplicationInfoLocked(List<String> packagesToUpdate, int userId, boolean updateFrameworkRes) {
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = this.mLruProcesses.get(i);
            if (app.thread != null && (userId == -1 || app.userId == userId)) {
                int packageCount = app.pkgList.size();
                for (int j = 0; j < packageCount; j++) {
                    String packageName = app.pkgList.keyAt(j);
                    if (updateFrameworkRes || packagesToUpdate.contains(packageName)) {
                        try {
                            ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 1024, app.userId);
                            if (ai != null) {
                                app.thread.scheduleApplicationInfoChanged(ai);
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, String.format("Failed to update %s ApplicationInfo for %s", packageName, app));
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        boolean foundProcess = false;
        for (int i = this.mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = this.mLruProcesses.get(i);
            if (r.thread != null && (userId == -1 || r.userId == userId)) {
                try {
                    for (int index = packages.length - 1; index >= 0 && !foundProcess; index--) {
                        if (packages[index].equals(r.info.packageName)) {
                            foundProcess = true;
                        }
                    }
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException e) {
                }
            }
        }
        if (!foundProcess) {
            try {
                AppGlobals.getPackageManager().notifyPackagesReplacedReceived(packages);
            } catch (RemoteException e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public int getUidProcStateLocked(int uid) {
        UidRecord uidRec = this.mActiveUids.get(uid);
        if (uidRec == null) {
            return 21;
        }
        return uidRec.getCurProcState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public UidRecord getUidRecordLocked(int uid) {
        return this.mActiveUids.get(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void doStopUidForIdleUidsLocked() {
        int size = this.mActiveUids.size();
        for (int i = 0; i < size; i++) {
            int uid = this.mActiveUids.keyAt(i);
            if (!UserHandle.isCore(uid)) {
                UidRecord uidRec = this.mActiveUids.valueAt(i);
                if (uidRec.idle) {
                    this.mService.doStopUidLocked(uidRec.uid, uidRec);
                }
            }
        }
    }
}
