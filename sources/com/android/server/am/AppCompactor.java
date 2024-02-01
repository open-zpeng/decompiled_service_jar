package com.android.server.am;

import android.app.ActivityThread;
import android.os.Handler;
import android.os.Process;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.job.controllers.JobStatus;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/* loaded from: classes.dex */
public final class AppCompactor {
    private static final String COMPACT_ACTION_ANON = "anon";
    private static final int COMPACT_ACTION_ANON_FLAG = 2;
    private static final String COMPACT_ACTION_FILE = "file";
    private static final int COMPACT_ACTION_FILE_FLAG = 1;
    private static final String COMPACT_ACTION_FULL = "all";
    private static final int COMPACT_ACTION_FULL_FLAG = 3;
    private static final String COMPACT_ACTION_NONE = "";
    private static final int COMPACT_ACTION_NONE_FLAG = 4;
    static final int COMPACT_PROCESS_BFGS = 4;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_MSG = 1;
    static final int COMPACT_PROCESS_PERSISTENT = 3;
    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_SYSTEM_MSG = 2;
    @VisibleForTesting
    static final int DEFAULT_COMPACT_ACTION_1 = 1;
    @VisibleForTesting
    static final int DEFAULT_COMPACT_ACTION_2 = 3;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB = 8000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB = 12000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_1 = 5000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_2 = 10000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_3 = 500;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_4 = 10000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_5 = 600000;
    @VisibleForTesting
    static final long DEFAULT_COMPACT_THROTTLE_6 = 600000;
    @VisibleForTesting
    static final float DEFAULT_STATSD_SAMPLE_RATE = 0.1f;
    @VisibleForTesting
    static final String KEY_COMPACT_ACTION_1 = "compact_action_1";
    @VisibleForTesting
    static final String KEY_COMPACT_ACTION_2 = "compact_action_2";
    @VisibleForTesting
    static final String KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB = "compact_full_delta_rss_throttle_kb";
    @VisibleForTesting
    static final String KEY_COMPACT_FULL_RSS_THROTTLE_KB = "compact_full_rss_throttle_kb";
    @VisibleForTesting
    static final String KEY_COMPACT_PROC_STATE_THROTTLE = "compact_proc_state_throttle";
    @VisibleForTesting
    static final String KEY_COMPACT_STATSD_SAMPLE_RATE = "compact_statsd_sample_rate";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_5 = "compact_throttle_5";
    @VisibleForTesting
    static final String KEY_COMPACT_THROTTLE_6 = "compact_throttle_6";
    @VisibleForTesting
    static final String KEY_USE_COMPACTION = "use_compaction";
    private final ActivityManagerService mAm;
    private int mBfgsCompactionCount;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile String mCompactActionFull;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile String mCompactActionSome;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottleBFGS;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottleFullFull;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottleFullSome;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottlePersistent;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottleSomeFull;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mCompactThrottleSomeSome;
    private Handler mCompactionHandler;
    final ServiceThread mCompactionThread;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile long mFullAnonRssThrottleKb;
    private int mFullCompactionCount;
    @GuardedBy({"mPhenoypeFlagLock"})
    @VisibleForTesting
    volatile long mFullDeltaRssThrottleKb;
    private Map<Integer, LastCompactionStats> mLastCompactionStats;
    private final DeviceConfig.OnPropertiesChangedListener mOnFlagsChangedListener;
    private final ArrayList<ProcessRecord> mPendingCompactionProcesses;
    private int mPersistentCompactionCount;
    private final Object mPhenotypeFlagLock;
    @GuardedBy({"mPhenoypeFlagLock"})
    @VisibleForTesting
    final Set<Integer> mProcStateThrottle;
    private final Random mRandom;
    private int mSomeCompactionCount;
    @GuardedBy({"mPhenotypeFlagLock"})
    @VisibleForTesting
    volatile float mStatsdSampleRate;
    private PropertyChangedCallbackForTest mTestCallback;
    @GuardedBy({"mPhenotypeFlagLock"})
    private volatile boolean mUseCompaction;
    @VisibleForTesting
    static final Boolean DEFAULT_USE_COMPACTION = false;
    @VisibleForTesting
    static final String DEFAULT_COMPACT_PROC_STATE_THROTTLE = String.valueOf(12);

    @VisibleForTesting
    /* loaded from: classes.dex */
    interface PropertyChangedCallbackForTest {
        void onPropertyChanged();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public native void compactSystem();

    static /* synthetic */ ActivityManagerService access$1000(AppCompactor x0) {
        return x0.mAm;
    }

    static /* synthetic */ ArrayList access$1100(AppCompactor x0) {
        return x0.mPendingCompactionProcesses;
    }

    static /* synthetic */ Map access$1200(AppCompactor x0) {
        return x0.mLastCompactionStats;
    }

    static /* synthetic */ int access$1308(AppCompactor x0) {
        int i = x0.mSomeCompactionCount;
        x0.mSomeCompactionCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$1408(AppCompactor x0) {
        int i = x0.mFullCompactionCount;
        x0.mFullCompactionCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$1508(AppCompactor x0) {
        int i = x0.mPersistentCompactionCount;
        x0.mPersistentCompactionCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$1608(AppCompactor x0) {
        int i = x0.mBfgsCompactionCount;
        x0.mBfgsCompactionCount = i + 1;
        return i;
    }

    static /* synthetic */ Random access$1700(AppCompactor x0) {
        return x0.mRandom;
    }

    static /* synthetic */ void access$1800(AppCompactor x0) {
        x0.compactSystem();
    }

    public AppCompactor(ActivityManagerService am) {
        this.mPendingCompactionProcesses = new ArrayList<>();
        this.mOnFlagsChangedListener = new DeviceConfig.OnPropertiesChangedListener() { // from class: com.android.server.am.AppCompactor.1
            public void onPropertiesChanged(DeviceConfig.Properties properties) {
                synchronized (AppCompactor.this.mPhenotypeFlagLock) {
                    for (String name : properties.getKeyset()) {
                        if (AppCompactor.KEY_USE_COMPACTION.equals(name)) {
                            AppCompactor.this.updateUseCompaction();
                        } else {
                            if (!AppCompactor.KEY_COMPACT_ACTION_1.equals(name) && !AppCompactor.KEY_COMPACT_ACTION_2.equals(name)) {
                                if (!AppCompactor.KEY_COMPACT_THROTTLE_1.equals(name) && !AppCompactor.KEY_COMPACT_THROTTLE_2.equals(name) && !AppCompactor.KEY_COMPACT_THROTTLE_3.equals(name) && !AppCompactor.KEY_COMPACT_THROTTLE_4.equals(name)) {
                                    if (AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE.equals(name)) {
                                        AppCompactor.this.updateStatsdSampleRate();
                                    } else if (AppCompactor.KEY_COMPACT_FULL_RSS_THROTTLE_KB.equals(name)) {
                                        AppCompactor.this.updateFullRssThrottle();
                                    } else if (AppCompactor.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB.equals(name)) {
                                        AppCompactor.this.updateFullDeltaRssThrottle();
                                    } else if (AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE.equals(name)) {
                                        AppCompactor.this.updateProcStateThrottle();
                                    }
                                }
                                AppCompactor.this.updateCompactionThrottles();
                            }
                            AppCompactor.this.updateCompactionActions();
                        }
                    }
                }
                if (AppCompactor.this.mTestCallback != null) {
                    AppCompactor.this.mTestCallback.onPropertyChanged();
                }
            }
        };
        this.mPhenotypeFlagLock = new Object();
        this.mCompactActionSome = compactActionIntToString(1);
        this.mCompactActionFull = compactActionIntToString(3);
        this.mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
        this.mCompactThrottleSomeFull = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        this.mCompactThrottleFullSome = 500L;
        this.mCompactThrottleFullFull = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        this.mCompactThrottleBFGS = 600000L;
        this.mCompactThrottlePersistent = 600000L;
        this.mUseCompaction = DEFAULT_USE_COMPACTION.booleanValue();
        this.mRandom = new Random();
        this.mStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
        this.mFullAnonRssThrottleKb = DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
        this.mFullDeltaRssThrottleKb = DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
        this.mLastCompactionStats = new LinkedHashMap<Integer, LastCompactionStats>() { // from class: com.android.server.am.AppCompactor.2
            @Override // java.util.LinkedHashMap
            protected boolean removeEldestEntry(Map.Entry<Integer, LastCompactionStats> entry) {
                return size() > 100;
            }
        };
        this.mAm = am;
        this.mCompactionThread = new ServiceThread("CompactionThread", -2, true);
        this.mProcStateThrottle = new HashSet();
    }

    @VisibleForTesting
    AppCompactor(ActivityManagerService am, PropertyChangedCallbackForTest callback) {
        this(am);
        this.mTestCallback = callback;
    }

    public void init() {
        DeviceConfig.addOnPropertiesChangedListener("activity_manager", ActivityThread.currentApplication().getMainExecutor(), this.mOnFlagsChangedListener);
        synchronized (this.mPhenotypeFlagLock) {
            updateUseCompaction();
            updateCompactionActions();
            updateCompactionThrottles();
            updateStatsdSampleRate();
            updateFullRssThrottle();
            updateFullDeltaRssThrottle();
            updateProcStateThrottle();
        }
        Process.setThreadGroupAndCpuset(this.mCompactionThread.getThreadId(), 2);
    }

    public boolean useCompaction() {
        boolean z;
        synchronized (this.mPhenotypeFlagLock) {
            z = this.mUseCompaction;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void dump(PrintWriter pw) {
        pw.println("AppCompactor settings");
        synchronized (this.mPhenotypeFlagLock) {
            pw.println("  use_compaction=" + this.mUseCompaction);
            pw.println("  compact_action_1=" + this.mCompactActionSome);
            pw.println("  compact_action_2=" + this.mCompactActionFull);
            pw.println("  compact_throttle_1=" + this.mCompactThrottleSomeSome);
            pw.println("  compact_throttle_2=" + this.mCompactThrottleSomeFull);
            pw.println("  compact_throttle_3=" + this.mCompactThrottleFullSome);
            pw.println("  compact_throttle_4=" + this.mCompactThrottleFullFull);
            pw.println("  compact_throttle_5=" + this.mCompactThrottleBFGS);
            pw.println("  compact_throttle_6=" + this.mCompactThrottlePersistent);
            pw.println("  compact_statsd_sample_rate=" + this.mStatsdSampleRate);
            pw.println("  compact_full_rss_throttle_kb=" + this.mFullAnonRssThrottleKb);
            pw.println("  compact_full_delta_rss_throttle_kb=" + this.mFullDeltaRssThrottleKb);
            pw.println("  compact_proc_state_throttle=" + Arrays.toString(this.mProcStateThrottle.toArray(new Integer[0])));
            pw.println("  " + this.mSomeCompactionCount + " some, " + this.mFullCompactionCount + " full, " + this.mPersistentCompactionCount + " persistent, " + this.mBfgsCompactionCount + " BFGS compactions.");
            StringBuilder sb = new StringBuilder();
            sb.append("  Tracking last compaction stats for ");
            sb.append(this.mLastCompactionStats.size());
            sb.append(" processes.");
            pw.println(sb.toString());
            if (ActivityManagerDebugConfig.DEBUG_COMPACTION) {
                for (Map.Entry<Integer, LastCompactionStats> entry : this.mLastCompactionStats.entrySet()) {
                    int pid = entry.getKey().intValue();
                    LastCompactionStats stats = entry.getValue();
                    pw.println("    " + pid + ": " + Arrays.toString(stats.getRssAfterCompaction()));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void compactAppSome(ProcessRecord app) {
        app.reqCompactAction = 1;
        this.mPendingCompactionProcesses.add(app);
        Handler handler = this.mCompactionHandler;
        handler.sendMessage(handler.obtainMessage(1, app.setAdj, app.setProcState));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void compactAppFull(ProcessRecord app) {
        app.reqCompactAction = 2;
        this.mPendingCompactionProcesses.add(app);
        Handler handler = this.mCompactionHandler;
        handler.sendMessage(handler.obtainMessage(1, app.setAdj, app.setProcState));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void compactAppPersistent(ProcessRecord app) {
        app.reqCompactAction = 3;
        this.mPendingCompactionProcesses.add(app);
        Handler handler = this.mCompactionHandler;
        handler.sendMessage(handler.obtainMessage(1, app.curAdj, app.setProcState));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public boolean shouldCompactPersistent(ProcessRecord app, long now) {
        return app.lastCompactTime == 0 || now - app.lastCompactTime > this.mCompactThrottlePersistent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void compactAppBfgs(ProcessRecord app) {
        app.reqCompactAction = 4;
        this.mPendingCompactionProcesses.add(app);
        Handler handler = this.mCompactionHandler;
        handler.sendMessage(handler.obtainMessage(1, app.curAdj, app.setProcState));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public boolean shouldCompactBFGS(ProcessRecord app, long now) {
        return app.lastCompactTime == 0 || now - app.lastCompactTime > this.mCompactThrottleBFGS;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mAm"})
    public void compactAllSystem() {
        if (this.mUseCompaction) {
            Handler handler = this.mCompactionHandler;
            handler.sendMessage(handler.obtainMessage(2));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateUseCompaction() {
        this.mUseCompaction = DeviceConfig.getBoolean("activity_manager", KEY_USE_COMPACTION, DEFAULT_USE_COMPACTION.booleanValue());
        if (this.mUseCompaction && !this.mCompactionThread.isAlive()) {
            this.mCompactionThread.start();
            this.mCompactionHandler = new MemCompactionHandler();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateCompactionActions() {
        int compactAction1 = DeviceConfig.getInt("activity_manager", KEY_COMPACT_ACTION_1, 1);
        int compactAction2 = DeviceConfig.getInt("activity_manager", KEY_COMPACT_ACTION_2, 3);
        this.mCompactActionSome = compactActionIntToString(compactAction1);
        this.mCompactActionFull = compactActionIntToString(compactAction2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateCompactionThrottles() {
        boolean useThrottleDefaults = false;
        String throttleSomeSomeFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_1);
        String throttleSomeFullFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_2);
        String throttleFullSomeFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_3);
        String throttleFullFullFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_4);
        String throttleBFGSFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_5);
        String throttlePersistentFlag = DeviceConfig.getProperty("activity_manager", KEY_COMPACT_THROTTLE_6);
        if (TextUtils.isEmpty(throttleSomeSomeFlag) || TextUtils.isEmpty(throttleSomeFullFlag) || TextUtils.isEmpty(throttleFullSomeFlag) || TextUtils.isEmpty(throttleFullFullFlag) || TextUtils.isEmpty(throttleBFGSFlag) || TextUtils.isEmpty(throttlePersistentFlag)) {
            useThrottleDefaults = true;
        } else {
            try {
                this.mCompactThrottleSomeSome = Integer.parseInt(throttleSomeSomeFlag);
                this.mCompactThrottleSomeFull = Integer.parseInt(throttleSomeFullFlag);
                this.mCompactThrottleFullSome = Integer.parseInt(throttleFullSomeFlag);
                this.mCompactThrottleFullFull = Integer.parseInt(throttleFullFullFlag);
                this.mCompactThrottleBFGS = Integer.parseInt(throttleBFGSFlag);
                this.mCompactThrottlePersistent = Integer.parseInt(throttlePersistentFlag);
            } catch (NumberFormatException e) {
                useThrottleDefaults = true;
            }
        }
        if (useThrottleDefaults) {
            this.mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
            this.mCompactThrottleSomeFull = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
            this.mCompactThrottleFullSome = 500L;
            this.mCompactThrottleFullFull = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
            this.mCompactThrottleBFGS = 600000L;
            this.mCompactThrottlePersistent = 600000L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateStatsdSampleRate() {
        this.mStatsdSampleRate = DeviceConfig.getFloat("activity_manager", KEY_COMPACT_STATSD_SAMPLE_RATE, (float) DEFAULT_STATSD_SAMPLE_RATE);
        this.mStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, this.mStatsdSampleRate));
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateFullRssThrottle() {
        this.mFullAnonRssThrottleKb = DeviceConfig.getLong("activity_manager", KEY_COMPACT_FULL_RSS_THROTTLE_KB, (long) DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
        if (this.mFullAnonRssThrottleKb < 0) {
            this.mFullAnonRssThrottleKb = DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateFullDeltaRssThrottle() {
        this.mFullDeltaRssThrottleKb = DeviceConfig.getLong("activity_manager", KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, (long) DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);
        if (this.mFullDeltaRssThrottleKb < 0) {
            this.mFullDeltaRssThrottleKb = DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPhenotypeFlagLock"})
    public void updateProcStateThrottle() {
        String procStateThrottleString = DeviceConfig.getString("activity_manager", KEY_COMPACT_PROC_STATE_THROTTLE, DEFAULT_COMPACT_PROC_STATE_THROTTLE);
        if (!parseProcStateThrottle(procStateThrottleString)) {
            Slog.w("ActivityManager", "Unable to parse app compact proc state throttle \"" + procStateThrottleString + "\" falling back to default.");
            if (!parseProcStateThrottle(DEFAULT_COMPACT_PROC_STATE_THROTTLE)) {
                Slog.wtf("ActivityManager", "Unable to parse default app compact proc state throttle " + DEFAULT_COMPACT_PROC_STATE_THROTTLE);
            }
        }
    }

    private boolean parseProcStateThrottle(String procStateThrottleString) {
        String[] procStates = TextUtils.split(procStateThrottleString, ",");
        this.mProcStateThrottle.clear();
        for (String procState : procStates) {
            try {
                this.mProcStateThrottle.add(Integer.valueOf(Integer.parseInt(procState)));
            } catch (NumberFormatException e) {
                Slog.e("ActivityManager", "Failed to parse default app compaction proc state: " + procState);
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    static String compactActionIntToString(int action) {
        if (action != 1) {
            if (action != 2) {
                if (action != 3) {
                    return action != 4 ? "" : "";
                }
                return COMPACT_ACTION_FULL;
            }
            return COMPACT_ACTION_ANON;
        }
        return COMPACT_ACTION_FILE;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class LastCompactionStats {
        private final long[] mRssAfterCompaction;

        LastCompactionStats(long[] rss) {
            this.mRssAfterCompaction = rss;
        }

        long[] getRssAfterCompaction() {
            return this.mRssAfterCompaction;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class MemCompactionHandler extends Handler {
        private MemCompactionHandler() {
            super(AppCompactor.this.mCompactionThread.getLooper());
        }

        /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
            jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:156:0x03a9
            	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
            */
        @Override // android.os.Handler
        public void handleMessage(android.os.Message r62) {
            /*
                Method dump skipped, instructions count: 1632
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppCompactor.MemCompactionHandler.handleMessage(android.os.Message):void");
        }
    }
}
