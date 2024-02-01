package com.android.server.am;

import android.app.ActivityManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.am.ActivityManagerService;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.wm.WindowProcessController;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/* loaded from: classes.dex */
public final class OomAdjuster {
    static final String OOM_ADJ_REASON_ACTIVITY = "updateOomAdj_activityChange";
    static final String OOM_ADJ_REASON_BIND_SERVICE = "updateOomAdj_bindService";
    static final String OOM_ADJ_REASON_FINISH_RECEIVER = "updateOomAdj_finishReceiver";
    static final String OOM_ADJ_REASON_GET_PROVIDER = "updateOomAdj_getProvider";
    static final String OOM_ADJ_REASON_METHOD = "updateOomAdj";
    static final String OOM_ADJ_REASON_NONE = "updateOomAdj_meh";
    static final String OOM_ADJ_REASON_PROCESS_BEGIN = "updateOomAdj_processBegin";
    static final String OOM_ADJ_REASON_PROCESS_END = "updateOomAdj_processEnd";
    static final String OOM_ADJ_REASON_REMOVE_PROVIDER = "updateOomAdj_removeProvider";
    static final String OOM_ADJ_REASON_START_RECEIVER = "updateOomAdj_startReceiver";
    static final String OOM_ADJ_REASON_START_SERVICE = "updateOomAdj_startService";
    static final String OOM_ADJ_REASON_UI_VISIBILITY = "updateOomAdj_uiVisibility";
    static final String OOM_ADJ_REASON_UNBIND_SERVICE = "updateOomAdj_unbindService";
    static final String OOM_ADJ_REASON_WHITELIST = "updateOomAdj_whitelistChange";
    private static final String TAG = "OomAdjuster";
    ActiveUids mActiveUids;
    AppCompactor mAppCompact;
    ActivityManagerConstants mConstants;
    private final Handler mProcessGroupHandler;
    private final ProcessList mProcessList;
    private final ActivityManagerService mService;
    final long[] mTmpLong = new long[3];
    int mAdjSeq = 0;
    int mNumServiceProcs = 0;
    int mNewNumAServiceProcs = 0;
    int mNewNumServiceProcs = 0;
    int mNumNonCachedProcs = 0;
    int mNumCachedHiddenProcs = 0;
    private final ArraySet<BroadcastQueue> mTmpBroadcastQueue = new ArraySet<>();
    private final ComputeOomAdjWindowCallback mTmpComputeOomAdjWindowCallback = new ComputeOomAdjWindowCallback();
    PowerManagerInternal mLocalPowerManager = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);

    /* JADX INFO: Access modifiers changed from: package-private */
    public OomAdjuster(ActivityManagerService service, ProcessList processList, ActiveUids activeUids) {
        this.mService = service;
        this.mProcessList = processList;
        this.mActiveUids = activeUids;
        this.mConstants = this.mService.mConstants;
        this.mAppCompact = new AppCompactor(this.mService);
        ServiceThread adjusterThread = new ServiceThread(TAG, -10, false);
        adjusterThread.start();
        Process.setThreadGroupAndCpuset(adjusterThread.getThreadId(), 5);
        this.mProcessGroupHandler = new Handler(adjusterThread.getLooper(), new Handler.Callback() { // from class: com.android.server.am.-$$Lambda$OomAdjuster$OVkqAAacT5-taN3pgDzyZj3Ymvk
            @Override // android.os.Handler.Callback
            public final boolean handleMessage(Message message) {
                return OomAdjuster.lambda$new$0(message);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$new$0(Message msg) {
        Trace.traceBegin(64L, "setProcessGroup");
        int pid = msg.arg1;
        int group = msg.arg2;
        try {
            try {
                Process.setProcessGroup(pid, group);
            } catch (Exception e) {
                if (ActivityManagerDebugConfig.DEBUG_ALL) {
                    Slog.w(TAG, "Failed setting process group of " + pid + " to " + group, e);
                }
            }
            return true;
        } finally {
            Trace.traceEnd(64L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initSettings() {
        this.mAppCompact.init();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public boolean updateOomAdjLocked(ProcessRecord app, boolean oomAdjAll, String oomAdjReason) {
        ProcessRecord TOP_APP = this.mService.getTopAppLocked();
        boolean wasCached = app.cached;
        this.mAdjSeq++;
        int cachedAdj = app.getCurRawAdj() >= 900 ? app.getCurRawAdj() : 1001;
        boolean success = updateOomAdjLocked(app, cachedAdj, TOP_APP, false, SystemClock.uptimeMillis());
        if (oomAdjAll && (wasCached != app.cached || app.getCurRawAdj() == 1001)) {
            updateOomAdjLocked(oomAdjReason);
        }
        return success;
    }

    @GuardedBy({"mService"})
    private final boolean updateOomAdjLocked(ProcessRecord app, int cachedAdj, ProcessRecord TOP_APP, boolean doingAll, long now) {
        if (app.thread == null) {
            return false;
        }
        computeOomAdjLocked(app, cachedAdj, TOP_APP, doingAll, now, false);
        return applyOomAdjLocked(app, doingAll, now, SystemClock.elapsedRealtime());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v75, types: [com.android.server.am.ProcessRecord] */
    /* JADX WARN: Type inference failed for: r10v10 */
    /* JADX WARN: Type inference failed for: r10v5 */
    /* JADX WARN: Type inference failed for: r10v6, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r1v31, types: [int] */
    /* JADX WARN: Type inference failed for: r4v44, types: [int] */
    @GuardedBy({"mService"})
    public void updateOomAdjLocked(String oomAdjReason) {
        int numEmptyProcs;
        int emptyProcessLimit;
        int numTrimming;
        boolean z;
        int uidChange;
        ProcessRecord app;
        int cachedProcessLimit;
        int emptyProcessLimit2;
        int lastCachedGroup;
        boolean z2;
        int numEmpty;
        boolean z3;
        boolean z4;
        boolean z5;
        ProcessRecord app2;
        int i;
        int curEmptyAdj;
        int nextCachedAdj;
        int curCachedAdj;
        int lastCachedGroup2;
        int cycleCount;
        int lastCachedGroupImportance;
        boolean z6;
        int lastCachedGroupImportance2;
        int lastCachedGroupUid;
        int emptyFactor;
        int cachedProcessLimit2;
        long nowElapsed;
        int numEmptyProcs2;
        int emptyProcessLimit3;
        boolean z7;
        int numEmptyProcs3;
        int lastCachedGroupImportance3;
        int lastCachedGroupUid2;
        int lastCachedGroup3;
        int lastCachedGroupImportance4;
        int lastCachedGroupImportance5;
        int nextCachedAdj2;
        int lastCachedGroupImportance6;
        boolean retryCycles;
        int lastCachedGroupImportance7;
        int stepEmpty;
        Trace.traceBegin(64L, oomAdjReason);
        this.mService.mOomAdjProfiler.oomAdjStarted();
        ProcessRecord TOP_APP = this.mService.getTopAppLocked();
        long now = SystemClock.uptimeMillis();
        long nowElapsed2 = SystemClock.elapsedRealtime();
        long oldTime = now - 1800000;
        int emptyFactor2 = this.mProcessList.getLruSizeLocked();
        for (int i2 = this.mActiveUids.size() - 1; i2 >= 0; i2--) {
            this.mActiveUids.valueAt(i2).reset();
        }
        if (this.mService.mAtmInternal != null) {
            this.mService.mAtmInternal.rankTaskLayersIfNeeded();
        }
        this.mAdjSeq++;
        boolean z8 = false;
        this.mNewNumServiceProcs = 0;
        this.mNewNumAServiceProcs = 0;
        int emptyProcessLimit4 = this.mConstants.CUR_MAX_EMPTY_PROCESSES;
        int cachedProcessLimit3 = this.mConstants.CUR_MAX_CACHED_PROCESSES - emptyProcessLimit4;
        int numEmptyProcs4 = (emptyFactor2 - this.mNumNonCachedProcs) - this.mNumCachedHiddenProcs;
        if (numEmptyProcs4 <= cachedProcessLimit3) {
            numEmptyProcs = numEmptyProcs4;
        } else {
            numEmptyProcs = cachedProcessLimit3;
        }
        int emptyFactor3 = ((numEmptyProcs + 10) - 1) / 10;
        if (emptyFactor3 < 1) {
            emptyFactor3 = 1;
        }
        int i3 = this.mNumCachedHiddenProcs;
        int cachedFactor = (i3 > 0 ? (i3 + 10) - 1 : 1) / 10;
        if (cachedFactor < 1) {
            cachedFactor = 1;
        }
        int stepCached = -1;
        int stepEmpty2 = -1;
        int numCachedExtraGroup = 0;
        this.mNumNonCachedProcs = 0;
        this.mNumCachedHiddenProcs = 0;
        int nextCachedAdj3 = 900 + 10;
        int curCachedImpAdj = 0;
        int nextEmptyAdj = 905 + 10;
        boolean retryCycles2 = false;
        int i4 = emptyFactor2 - 1;
        while (true) {
            emptyProcessLimit = emptyProcessLimit4;
            if (i4 < 0) {
                break;
            }
            ProcessRecord app3 = this.mProcessList.mLruProcesses.get(i4);
            app3.containsCycle = false;
            app3.setCurRawProcState(20);
            app3.setCurRawAdj(NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE);
            i4--;
            emptyProcessLimit4 = emptyProcessLimit;
        }
        int i5 = emptyFactor2 - 1;
        int i6 = i5;
        int lastCachedGroup4 = 0;
        int lastCachedGroupImportance8 = 0;
        int lastCachedGroupUid3 = 0;
        int curCachedAdj2 = 900;
        int nextCachedAdj4 = nextCachedAdj3;
        int nextCachedAdj5 = 905;
        int nextEmptyAdj2 = nextEmptyAdj;
        while (i6 >= 0) {
            ProcessRecord app4 = this.mProcessList.mLruProcesses.get(i6);
            if (app4.killedByAm || app4.thread == null) {
                cachedProcessLimit2 = cachedProcessLimit3;
                int curEmptyAdj2 = nextCachedAdj5;
                nowElapsed = nowElapsed2;
                numEmptyProcs2 = numEmptyProcs;
                emptyProcessLimit3 = emptyProcessLimit;
                int curEmptyAdj3 = nextCachedAdj4;
                z7 = false;
                numEmptyProcs3 = emptyFactor2;
                lastCachedGroupImportance3 = emptyFactor3;
                nextEmptyAdj2 = nextEmptyAdj2;
                nextCachedAdj4 = curEmptyAdj3;
                curCachedAdj2 = curCachedAdj2;
                lastCachedGroup4 = lastCachedGroup4;
                lastCachedGroupUid3 = lastCachedGroupUid3;
                lastCachedGroupImportance8 = lastCachedGroupImportance8;
                nextCachedAdj5 = curEmptyAdj2;
            } else {
                app4.procStateChanged = false;
                int emptyFactor4 = emptyFactor3;
                cachedProcessLimit2 = cachedProcessLimit3;
                emptyProcessLimit3 = emptyProcessLimit;
                z7 = false;
                int curEmptyAdj4 = nextCachedAdj5;
                nowElapsed = nowElapsed2;
                numEmptyProcs2 = numEmptyProcs;
                numEmptyProcs3 = emptyFactor2;
                computeOomAdjLocked(app4, NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE, TOP_APP, true, now, false);
                boolean retryCycles3 = retryCycles2 | app4.containsCycle;
                if (app4.curAdj < 1001) {
                    lastCachedGroupImportance3 = emptyFactor4;
                    retryCycles2 = retryCycles3;
                    nextCachedAdj5 = curEmptyAdj4;
                } else {
                    switch (app4.getCurProcState()) {
                        case 17:
                        case 18:
                        case 19:
                            boolean inGroup = false;
                            if (app4.connectionGroup == 0) {
                                lastCachedGroupUid2 = lastCachedGroupUid3;
                                lastCachedGroup3 = lastCachedGroup4;
                                lastCachedGroupImportance4 = lastCachedGroupImportance8;
                                lastCachedGroupImportance5 = curCachedAdj2;
                                nextCachedAdj2 = nextCachedAdj4;
                            } else {
                                int lastCachedGroupUid4 = lastCachedGroupUid3;
                                if (lastCachedGroupUid4 == app4.uid) {
                                    lastCachedGroup3 = lastCachedGroup4;
                                    if (lastCachedGroup3 == app4.connectionGroup) {
                                        lastCachedGroupUid2 = lastCachedGroupUid4;
                                        int lastCachedGroupUid5 = lastCachedGroupImportance8;
                                        if (app4.connectionImportance <= lastCachedGroupUid5) {
                                            lastCachedGroupImportance5 = curCachedAdj2;
                                            nextCachedAdj2 = nextCachedAdj4;
                                            lastCachedGroupImportance6 = lastCachedGroupUid5;
                                        } else {
                                            int lastCachedGroupImportance9 = app4.connectionImportance;
                                            int lastCachedGroupImportance10 = curCachedAdj2;
                                            nextCachedAdj2 = nextCachedAdj4;
                                            if (lastCachedGroupImportance10 < nextCachedAdj2 && lastCachedGroupImportance10 < 999) {
                                                curCachedImpAdj++;
                                                lastCachedGroupImportance6 = lastCachedGroupImportance9;
                                                lastCachedGroupImportance5 = lastCachedGroupImportance10;
                                            } else {
                                                lastCachedGroupImportance6 = lastCachedGroupImportance9;
                                                lastCachedGroupImportance5 = lastCachedGroupImportance10;
                                            }
                                        }
                                        inGroup = true;
                                        lastCachedGroupImportance4 = lastCachedGroupImportance6;
                                    } else {
                                        lastCachedGroupImportance5 = curCachedAdj2;
                                        nextCachedAdj2 = nextCachedAdj4;
                                    }
                                } else {
                                    lastCachedGroupImportance5 = curCachedAdj2;
                                    nextCachedAdj2 = nextCachedAdj4;
                                }
                                int lastCachedGroupUid6 = app4.uid;
                                lastCachedGroup3 = app4.connectionGroup;
                                lastCachedGroupImportance4 = app4.connectionImportance;
                                lastCachedGroupUid2 = lastCachedGroupUid6;
                            }
                            if (!inGroup && lastCachedGroupImportance5 != nextCachedAdj2) {
                                int stepCached2 = stepCached + 1;
                                curCachedImpAdj = 0;
                                if (stepCached2 < cachedFactor) {
                                    stepCached = stepCached2;
                                    curCachedAdj2 = lastCachedGroupImportance5;
                                } else {
                                    stepCached = 0;
                                    curCachedAdj2 = nextCachedAdj2;
                                    int nextCachedAdj6 = nextCachedAdj2 + 10;
                                    if (nextCachedAdj6 <= 999) {
                                        nextCachedAdj2 = nextCachedAdj6;
                                    } else {
                                        nextCachedAdj2 = 999;
                                    }
                                }
                            } else {
                                curCachedAdj2 = lastCachedGroupImportance5;
                            }
                            app4.setCurRawAdj(curCachedAdj2 + curCachedImpAdj);
                            app4.curAdj = app4.modifyRawOomAdj(curCachedAdj2 + curCachedImpAdj);
                            boolean z9 = ActivityManagerDebugConfig.DEBUG_LRU;
                            retryCycles2 = retryCycles3;
                            nextCachedAdj4 = nextCachedAdj2;
                            lastCachedGroup4 = lastCachedGroup3;
                            lastCachedGroupImportance8 = lastCachedGroupImportance4;
                            lastCachedGroupUid3 = lastCachedGroupUid2;
                            lastCachedGroupImportance3 = emptyFactor4;
                            nextCachedAdj5 = curEmptyAdj4;
                            continue;
                        default:
                            int lastCachedGroupUid7 = lastCachedGroupUid3;
                            int lastCachedGroup5 = lastCachedGroup4;
                            int lastCachedGroupImportance11 = lastCachedGroupImportance8;
                            int curCachedAdj3 = curCachedAdj2;
                            int nextCachedAdj7 = nextCachedAdj4;
                            int nextEmptyAdj3 = nextEmptyAdj2;
                            if (curEmptyAdj4 == nextEmptyAdj3) {
                                retryCycles = retryCycles3;
                                lastCachedGroupImportance7 = lastCachedGroupImportance11;
                                lastCachedGroupImportance3 = emptyFactor4;
                                stepEmpty = curEmptyAdj4;
                            } else {
                                retryCycles = retryCycles3;
                                int stepEmpty3 = stepEmpty2 + 1;
                                lastCachedGroupImportance7 = lastCachedGroupImportance11;
                                lastCachedGroupImportance3 = emptyFactor4;
                                if (stepEmpty3 < lastCachedGroupImportance3) {
                                    stepEmpty2 = stepEmpty3;
                                    stepEmpty = curEmptyAdj4;
                                } else {
                                    stepEmpty2 = 0;
                                    stepEmpty = nextEmptyAdj3;
                                    nextEmptyAdj3 += 10;
                                    if (nextEmptyAdj3 > 999) {
                                        nextEmptyAdj3 = 999;
                                    }
                                }
                            }
                            app4.setCurRawAdj(stepEmpty);
                            app4.curAdj = app4.modifyRawOomAdj(stepEmpty);
                            boolean z10 = ActivityManagerDebugConfig.DEBUG_LRU;
                            nextEmptyAdj2 = nextEmptyAdj3;
                            nextCachedAdj4 = nextCachedAdj7;
                            curCachedAdj2 = curCachedAdj3;
                            lastCachedGroup4 = lastCachedGroup5;
                            lastCachedGroupUid3 = lastCachedGroupUid7;
                            retryCycles2 = retryCycles;
                            lastCachedGroupImportance8 = lastCachedGroupImportance7;
                            nextCachedAdj5 = stepEmpty;
                            continue;
                    }
                }
            }
            i6--;
            emptyFactor3 = lastCachedGroupImportance3;
            emptyFactor2 = numEmptyProcs3;
            numEmptyProcs = numEmptyProcs2;
            z8 = z7;
            nowElapsed2 = nowElapsed;
            cachedProcessLimit3 = cachedProcessLimit2;
            emptyProcessLimit = emptyProcessLimit3;
        }
        int cachedProcessLimit4 = cachedProcessLimit3;
        boolean allChanged = z8;
        int curEmptyAdj5 = nextCachedAdj5;
        long nowElapsed3 = nowElapsed2;
        int emptyProcessLimit5 = emptyProcessLimit;
        int nextEmptyAdj4 = nextEmptyAdj2;
        int emptyFactor5 = lastCachedGroupUid3;
        int lastCachedGroup6 = lastCachedGroup4;
        int lastCachedGroupUid8 = lastCachedGroupImportance8;
        int curCachedAdj4 = curCachedAdj2;
        int nextCachedAdj8 = nextCachedAdj4;
        int numEmptyProcs5 = emptyFactor2;
        int emptyFactor6 = emptyFactor3;
        int cycleCount2 = 0;
        while (retryCycles2 && cycleCount2 < 10) {
            int cycleCount3 = cycleCount2 + 1;
            boolean retryCycles4 = false;
            int i7 = 0;
            while (i7 < numEmptyProcs5) {
                boolean retryCycles5 = retryCycles4;
                ProcessRecord app5 = this.mProcessList.mLruProcesses.get(i7);
                int nextEmptyAdj5 = nextEmptyAdj4;
                if (app5.killedByAm || app5.thread == null) {
                    emptyFactor = emptyFactor6;
                } else {
                    emptyFactor = emptyFactor6;
                    if (app5.containsCycle) {
                        app5.adjSeq--;
                        app5.completedAdjSeq--;
                    }
                }
                i7++;
                retryCycles4 = retryCycles5;
                nextEmptyAdj4 = nextEmptyAdj5;
                emptyFactor6 = emptyFactor;
            }
            int nextEmptyAdj6 = nextEmptyAdj4;
            int emptyFactor7 = emptyFactor6;
            boolean z11 = true;
            int i8 = 0;
            retryCycles2 = retryCycles4;
            while (i8 < numEmptyProcs5) {
                ProcessRecord app6 = this.mProcessList.mLruProcesses.get(i8);
                if (app6.killedByAm || app6.thread == null || app6.containsCycle != z11) {
                    i = i8;
                    curEmptyAdj = curEmptyAdj5;
                    nextCachedAdj = nextCachedAdj8;
                    curCachedAdj = curCachedAdj4;
                    lastCachedGroup2 = lastCachedGroup6;
                    cycleCount = cycleCount3;
                    lastCachedGroupImportance = lastCachedGroupUid8;
                    z6 = z11;
                    lastCachedGroupImportance2 = emptyFactor5;
                    lastCachedGroupUid = emptyFactor7;
                } else {
                    int curEmptyAdj6 = app6.getCurRawAdj();
                    i = i8;
                    curEmptyAdj = curEmptyAdj5;
                    nextCachedAdj = nextCachedAdj8;
                    curCachedAdj = curCachedAdj4;
                    lastCachedGroup2 = lastCachedGroup6;
                    cycleCount = cycleCount3;
                    lastCachedGroupImportance = lastCachedGroupUid8;
                    z6 = z11;
                    lastCachedGroupImportance2 = emptyFactor5;
                    lastCachedGroupUid = emptyFactor7;
                    if (computeOomAdjLocked(app6, curEmptyAdj6, TOP_APP, true, now, true)) {
                        retryCycles2 = true;
                    }
                }
                i8 = i + 1;
                z11 = z6;
                emptyFactor7 = lastCachedGroupUid;
                curEmptyAdj5 = curEmptyAdj;
                emptyFactor5 = lastCachedGroupImportance2;
                cycleCount3 = cycleCount;
                lastCachedGroup6 = lastCachedGroup2;
                lastCachedGroupUid8 = lastCachedGroupImportance;
                curCachedAdj4 = curCachedAdj;
                nextCachedAdj8 = nextCachedAdj;
            }
            int lastCachedGroupImportance12 = lastCachedGroupUid8;
            int lastCachedGroupImportance13 = emptyFactor5;
            int lastCachedGroupUid9 = emptyFactor7;
            emptyFactor6 = lastCachedGroupUid9;
            nextEmptyAdj4 = nextEmptyAdj6;
            emptyFactor5 = lastCachedGroupImportance13;
            cycleCount2 = cycleCount3;
            lastCachedGroupUid8 = lastCachedGroupImportance12;
        }
        ?? r10 = 1;
        int i9 = numEmptyProcs5 - 1;
        boolean z12 = allChanged;
        boolean z13 = allChanged;
        int numTrimming2 = 0;
        int lastCachedGroupUid10 = 0;
        int emptyProcessLimit6 = 0;
        while (i9 >= 0) {
            ProcessRecord app7 = this.mProcessList.mLruProcesses.get(i9);
            if (app7.killedByAm || app7.thread == null) {
                app = app7;
                boolean z14 = z13;
                int lastCachedGroup7 = numTrimming2;
                cachedProcessLimit = cachedProcessLimit4;
                int numCached = emptyProcessLimit6;
                emptyProcessLimit2 = emptyProcessLimit5;
                z13 = z14;
                lastCachedGroup = numCached;
                numTrimming2 = lastCachedGroup7;
                lastCachedGroupUid10 = lastCachedGroupUid10;
                z2 = z12;
            } else {
                boolean z15 = z12;
                int numTrimming3 = emptyProcessLimit6;
                boolean z16 = z13;
                int numCached2 = numTrimming2;
                int numEmpty2 = lastCachedGroupUid10;
                applyOomAdjLocked(app7, true, now, nowElapsed3);
                int curProcState = app7.getCurProcState();
                if (curProcState == 17 || curProcState == 18) {
                    ProcessRecord app8 = app7;
                    emptyProcessLimit2 = emptyProcessLimit5;
                    numEmpty = numEmpty2;
                    this.mNumCachedHiddenProcs += r10;
                    int numCached3 = numCached2 + 1;
                    if (app8.connectionGroup != 0) {
                        if (z15 == app8.info.uid && z16 == app8.connectionGroup) {
                            numCachedExtraGroup++;
                            z3 = z16;
                            z4 = z15;
                        }
                        ?? r4 = app8.info.uid;
                        z3 = app8.connectionGroup;
                        z4 = r4;
                    } else {
                        z3 = allChanged;
                        z4 = allChanged;
                    }
                    int lastCachedGroupUid11 = numCached3 - numCachedExtraGroup;
                    cachedProcessLimit = cachedProcessLimit4;
                    if (lastCachedGroupUid11 <= cachedProcessLimit) {
                        z5 = z4;
                    } else {
                        StringBuilder sb = new StringBuilder();
                        z5 = z4;
                        sb.append("cached #");
                        sb.append(numCached3);
                        app8.kill(sb.toString(), true);
                    }
                    z16 = z3;
                    numCached2 = numCached3;
                    z15 = z5;
                    app = app8;
                } else {
                    if (curProcState != 20) {
                        this.mNumNonCachedProcs += r10;
                        app2 = app7;
                        numEmpty = numEmpty2;
                    } else {
                        numEmpty = numEmpty2;
                        if (numEmpty > this.mConstants.CUR_TRIM_EMPTY_PROCESSES) {
                            app2 = app7;
                            if (app2.lastActivityTime < oldTime) {
                                app2.kill("empty for " + (((oldTime + 1800000) - app2.lastActivityTime) / 1000) + "s", r10);
                            }
                        } else {
                            app2 = app7;
                        }
                        int numEmpty3 = numEmpty + 1;
                        emptyProcessLimit2 = emptyProcessLimit5;
                        if (numEmpty3 > emptyProcessLimit2) {
                            app2.kill("empty #" + numEmpty3, r10);
                        }
                        numEmpty = numEmpty3;
                        cachedProcessLimit = cachedProcessLimit4;
                        app = app2;
                    }
                    cachedProcessLimit = cachedProcessLimit4;
                    emptyProcessLimit2 = emptyProcessLimit5;
                    app = app2;
                }
                if (app.isolated && app.services.size() <= 0 && app.isolatedEntryPoint == null) {
                    app.kill("isolated not needed", true);
                } else {
                    UidRecord uidRec = app.uidRecord;
                    if (uidRec != null) {
                        uidRec.ephemeral = app.info.isInstantApp();
                        if (uidRec.getCurProcState() > app.getCurProcState()) {
                            uidRec.setCurProcState(app.getCurProcState());
                        }
                        if (app.hasForegroundServices()) {
                            uidRec.foregroundServices = true;
                        }
                    }
                }
                if (app.getCurProcState() >= 15 && !app.killedByAm) {
                    lastCachedGroup = numTrimming3 + 1;
                    lastCachedGroupUid10 = numEmpty;
                    z2 = z15;
                    z13 = z16;
                    numTrimming2 = numCached2;
                } else {
                    lastCachedGroupUid10 = numEmpty;
                    lastCachedGroup = numTrimming3;
                    z2 = z15;
                    z13 = z16;
                    numTrimming2 = numCached2;
                }
            }
            int numTrimming4 = lastCachedGroup;
            ProcessManagerPolicy.updateProcessPolicyIfNeed(this.mService.mContext, app, this.mService);
            i9--;
            emptyProcessLimit5 = emptyProcessLimit2;
            cachedProcessLimit4 = cachedProcessLimit;
            emptyProcessLimit6 = numTrimming4;
            z12 = z2;
            r10 = 1;
        }
        int numCached4 = numTrimming2;
        int numTrimming5 = emptyProcessLimit6;
        int emptyProcessLimit7 = emptyProcessLimit5;
        int numEmpty4 = lastCachedGroupUid10;
        this.mService.incrementProcStateSeqAndNotifyAppsLocked();
        this.mNumServiceProcs = this.mNewNumServiceProcs;
        boolean allChanged2 = this.mService.updateLowMemStateLocked(numCached4, numEmpty4, numTrimming5);
        if (this.mService.mAlwaysFinishActivities) {
            this.mService.mAtmInternal.scheduleDestroyAllActivities("always-finish");
        }
        if (allChanged2) {
            ActivityManagerService activityManagerService = this.mService;
            activityManagerService.requestPssAllProcsLocked(now, allChanged, activityManagerService.mProcessStats.isMemFactorLowered());
        }
        ArrayList<UidRecord> becameIdle = null;
        PowerManagerInternal powerManagerInternal = this.mLocalPowerManager;
        if (powerManagerInternal != null) {
            powerManagerInternal.startUidChanges();
        }
        int i10 = this.mActiveUids.size() - 1;
        while (i10 >= 0) {
            int numEmpty5 = numEmpty4;
            UidRecord uidRec2 = this.mActiveUids.valueAt(i10);
            int uidChange2 = 0;
            int emptyProcessLimit8 = emptyProcessLimit7;
            int numCached5 = numCached4;
            if (uidRec2.getCurProcState() == 21) {
                numTrimming = numTrimming5;
            } else if (uidRec2.setProcState == uidRec2.getCurProcState() && uidRec2.setWhitelist == uidRec2.curWhitelist) {
                numTrimming = numTrimming5;
            } else {
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append("Changes in ");
                    sb2.append(uidRec2);
                    sb2.append(": proc state from ");
                    sb2.append(uidRec2.setProcState);
                    sb2.append(" to ");
                    numTrimming = numTrimming5;
                    int numTrimming6 = uidRec2.getCurProcState();
                    sb2.append(numTrimming6);
                    sb2.append(", whitelist from ");
                    sb2.append(uidRec2.setWhitelist);
                    sb2.append(" to ");
                    sb2.append(uidRec2.curWhitelist);
                    Slog.i("ActivityManager", sb2.toString());
                } else {
                    numTrimming = numTrimming5;
                }
                if (ActivityManager.isProcStateBackground(uidRec2.getCurProcState()) && !uidRec2.curWhitelist) {
                    if (!ActivityManager.isProcStateBackground(uidRec2.setProcState) || uidRec2.setWhitelist) {
                        long nowElapsed4 = nowElapsed3;
                        uidRec2.lastBackgroundTime = nowElapsed4;
                        if (!this.mService.mHandler.hasMessages(58)) {
                            nowElapsed3 = nowElapsed4;
                            this.mService.mHandler.sendEmptyMessageDelayed(58, this.mConstants.BACKGROUND_SETTLE_TIME);
                        } else {
                            nowElapsed3 = nowElapsed4;
                        }
                    }
                    if (uidRec2.idle && !uidRec2.setIdle) {
                        uidChange2 = 2;
                        if (becameIdle == null) {
                            becameIdle = new ArrayList<>();
                        }
                        becameIdle.add(uidRec2);
                        z = false;
                    } else {
                        z = false;
                    }
                } else {
                    if (!uidRec2.idle) {
                        z = false;
                    } else {
                        uidChange2 = 4;
                        EventLogTags.writeAmUidActive(uidRec2.uid);
                        z = false;
                        uidRec2.idle = false;
                    }
                    uidRec2.lastBackgroundTime = 0L;
                }
                boolean wasCached = uidRec2.setProcState > 12 ? true : z;
                boolean isCached = uidRec2.getCurProcState() > 12 ? true : z;
                if (wasCached != isCached || uidRec2.setProcState == 21) {
                    uidChange = uidChange2 | (isCached ? 8 : 16);
                } else {
                    uidChange = uidChange2;
                }
                uidRec2.setProcState = uidRec2.getCurProcState();
                uidRec2.setWhitelist = uidRec2.curWhitelist;
                uidRec2.setIdle = uidRec2.idle;
                ArrayList<UidRecord> becameIdle2 = becameIdle;
                this.mService.mAtmInternal.onUidProcStateChanged(uidRec2.uid, uidRec2.setProcState);
                this.mService.enqueueUidChangeLocked(uidRec2, -1, uidChange);
                this.mService.noteUidProcessState(uidRec2.uid, uidRec2.getCurProcState());
                if (uidRec2.foregroundServices) {
                    this.mService.mServices.foregroundServiceProcStateChangedLocked(uidRec2);
                }
                becameIdle = becameIdle2;
            }
            i10--;
            emptyProcessLimit7 = emptyProcessLimit8;
            numTrimming5 = numTrimming;
            numCached4 = numCached5;
            numEmpty4 = numEmpty5;
        }
        PowerManagerInternal powerManagerInternal2 = this.mLocalPowerManager;
        if (powerManagerInternal2 != null) {
            powerManagerInternal2.finishUidChanges();
        }
        if (becameIdle != null) {
            for (int i11 = becameIdle.size() - 1; i11 >= 0; i11--) {
                this.mService.mServices.stopInBackgroundLocked(becameIdle.get(i11).uid);
            }
        }
        if (this.mService.mProcessStats.shouldWriteNowLocked(now)) {
            ActivityManagerService.MainHandler mainHandler = this.mService.mHandler;
            ActivityManagerService activityManagerService2 = this.mService;
            mainHandler.post(new ActivityManagerService.ProcStatsRunnable(activityManagerService2, activityManagerService2.mProcessStats));
        }
        this.mService.mProcessStats.updateTrackingAssociationsLocked(this.mAdjSeq, now);
        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            long duration = SystemClock.uptimeMillis() - now;
            Slog.d("ActivityManager", "Did OOM ADJ in " + duration + "ms");
        }
        this.mService.mOomAdjProfiler.oomAdjEnded();
        Trace.traceEnd(64L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ComputeOomAdjWindowCallback implements WindowProcessController.ComputeOomAdjCallback {
        int adj;
        ProcessRecord app;
        int appUid;
        boolean foregroundActivities;
        int logUid;
        int procState;
        int processStateCurTop;
        int schedGroup;

        private ComputeOomAdjWindowCallback() {
        }

        void initialize(ProcessRecord app, int adj, boolean foregroundActivities, int procState, int schedGroup, int appUid, int logUid, int processStateCurTop) {
            this.app = app;
            this.adj = adj;
            this.foregroundActivities = foregroundActivities;
            this.procState = procState;
            this.schedGroup = schedGroup;
            this.appUid = appUid;
            this.logUid = logUid;
            this.processStateCurTop = processStateCurTop;
        }

        @Override // com.android.server.wm.WindowProcessController.ComputeOomAdjCallback
        public void onVisibleActivity() {
            if (this.adj > 100) {
                this.adj = 100;
                this.app.adjType = "vis-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster = OomAdjuster.this;
                    oomAdjuster.reportOomAdjMessageLocked("ActivityManager", "Raise adj to vis-activity: " + this.app);
                }
            }
            int i = this.procState;
            int i2 = this.processStateCurTop;
            if (i > i2) {
                this.procState = i2;
                this.app.adjType = "vis-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster2 = OomAdjuster.this;
                    oomAdjuster2.reportOomAdjMessageLocked("ActivityManager", "Raise procstate to vis-activity (top): " + this.app);
                }
            }
            if (this.schedGroup < 2) {
                this.schedGroup = 2;
            }
            ProcessRecord processRecord = this.app;
            processRecord.cached = false;
            processRecord.empty = false;
            this.foregroundActivities = true;
        }

        @Override // com.android.server.wm.WindowProcessController.ComputeOomAdjCallback
        public void onPausedActivity() {
            if (this.adj > 200) {
                this.adj = 200;
                this.app.adjType = "pause-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster = OomAdjuster.this;
                    oomAdjuster.reportOomAdjMessageLocked("ActivityManager", "Raise adj to pause-activity: " + this.app);
                }
            }
            int i = this.procState;
            int i2 = this.processStateCurTop;
            if (i > i2) {
                this.procState = i2;
                this.app.adjType = "pause-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster2 = OomAdjuster.this;
                    oomAdjuster2.reportOomAdjMessageLocked("ActivityManager", "Raise procstate to pause-activity (top): " + this.app);
                }
            }
            if (this.schedGroup < 2) {
                this.schedGroup = 2;
            }
            ProcessRecord processRecord = this.app;
            processRecord.cached = false;
            processRecord.empty = false;
            this.foregroundActivities = true;
        }

        @Override // com.android.server.wm.WindowProcessController.ComputeOomAdjCallback
        public void onStoppingActivity(boolean finishing) {
            if (this.adj > 200) {
                this.adj = 200;
                this.app.adjType = "stop-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster = OomAdjuster.this;
                    oomAdjuster.reportOomAdjMessageLocked("ActivityManager", "Raise adj to stop-activity: " + this.app);
                }
            }
            if (!finishing && this.procState > 16) {
                this.procState = 16;
                this.app.adjType = "stop-activity";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster2 = OomAdjuster.this;
                    oomAdjuster2.reportOomAdjMessageLocked("ActivityManager", "Raise procstate to stop-activity: " + this.app);
                }
            }
            ProcessRecord processRecord = this.app;
            processRecord.cached = false;
            processRecord.empty = false;
            this.foregroundActivities = true;
        }

        @Override // com.android.server.wm.WindowProcessController.ComputeOomAdjCallback
        public void onOtherActivity() {
            if (this.procState > 17) {
                this.procState = 17;
                this.app.adjType = "cch-act";
                if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON || this.logUid == this.appUid) {
                    OomAdjuster oomAdjuster = OomAdjuster.this;
                    oomAdjuster.reportOomAdjMessageLocked("ActivityManager", "Raise procstate to cached activity: " + this.app);
                }
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:120:0x02d7, code lost:
        if (r11 == r0) goto L581;
     */
    /* JADX WARN: Code restructure failed: missing block: B:128:0x02fc, code lost:
        if (r6 <= 3) goto L67;
     */
    /* JADX WARN: Code restructure failed: missing block: B:154:0x038a, code lost:
        if (r7.setProcState > 2) goto L75;
     */
    /* JADX WARN: Removed duplicated region for block: B:333:0x077b  */
    /* JADX WARN: Removed duplicated region for block: B:375:0x080d  */
    /* JADX WARN: Removed duplicated region for block: B:379:0x081c  */
    /* JADX WARN: Removed duplicated region for block: B:413:0x088b  */
    /* JADX WARN: Removed duplicated region for block: B:424:0x08b3  */
    /* JADX WARN: Removed duplicated region for block: B:429:0x08c3  */
    /* JADX WARN: Removed duplicated region for block: B:431:0x08ca  */
    /* JADX WARN: Removed duplicated region for block: B:435:0x08d9  */
    /* JADX WARN: Removed duplicated region for block: B:438:0x08df  */
    /* JADX WARN: Removed duplicated region for block: B:442:0x08ed  */
    /* JADX WARN: Removed duplicated region for block: B:448:0x0943  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private final boolean computeOomAdjLocked(com.android.server.am.ProcessRecord r43, int r44, com.android.server.am.ProcessRecord r45, boolean r46, long r47, boolean r49) {
        /*
            Method dump skipped, instructions count: 3454
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.OomAdjuster.computeOomAdjLocked(com.android.server.am.ProcessRecord, int, com.android.server.am.ProcessRecord, boolean, long, boolean):boolean");
    }

    private boolean shouldSkipDueToCycle(ProcessRecord app, ProcessRecord client, int procState, int adj, boolean cycleReEval) {
        if (client.containsCycle) {
            app.containsCycle = true;
            if (client.completedAdjSeq < this.mAdjSeq) {
                if (!cycleReEval) {
                    return true;
                }
                if (client.getCurRawProcState() >= procState && client.getCurRawAdj() >= adj) {
                    return true;
                }
                return false;
            }
            return false;
        }
        return false;
    }

    @GuardedBy({"mService"})
    void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        if (this.mService.mCurOomAdjObserver != null) {
            this.mService.mUiHandler.obtainMessage(70, msg).sendToTarget();
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:135:0x026a  */
    /* JADX WARN: Removed duplicated region for block: B:140:0x0290  */
    /* JADX WARN: Removed duplicated region for block: B:141:0x029a  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x02a5  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x02c1  */
    /* JADX WARN: Removed duplicated region for block: B:167:0x034c  */
    /* JADX WARN: Removed duplicated region for block: B:170:0x0376  */
    /* JADX WARN: Removed duplicated region for block: B:173:0x03b7  */
    /* JADX WARN: Removed duplicated region for block: B:198:0x0445  */
    /* JADX WARN: Removed duplicated region for block: B:209:0x0472  */
    /* JADX WARN: Removed duplicated region for block: B:216:0x0506  */
    @com.android.internal.annotations.GuardedBy({"mService"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private final boolean applyOomAdjLocked(com.android.server.am.ProcessRecord r22, boolean r23, long r24, long r26) {
        /*
            Method dump skipped, instructions count: 1289
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.OomAdjuster.applyOomAdjLocked(com.android.server.am.ProcessRecord, boolean, long, long):boolean");
    }

    @VisibleForTesting
    void maybeUpdateUsageStats(ProcessRecord app, long nowElapsed) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                maybeUpdateUsageStatsLocked(app, nowElapsed);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @GuardedBy({"mService"})
    private void maybeUpdateUsageStatsLocked(ProcessRecord app, long nowElapsed) {
        boolean isInteraction;
        if (ActivityManagerDebugConfig.DEBUG_USAGE_STATS) {
            Slog.d(TAG, "Checking proc [" + Arrays.toString(app.getPackageList()) + "] state changes: old = " + app.setProcState + ", new = " + app.getCurProcState());
        }
        if (this.mService.mUsageStatsService == null) {
            return;
        }
        if (app.getCurProcState() <= 2 || app.getCurProcState() == 4) {
            isInteraction = true;
            app.setFgInteractionTime(0L);
        } else if (app.getCurProcState() <= 5) {
            if (app.getFgInteractionTime() == 0) {
                app.setFgInteractionTime(nowElapsed);
                isInteraction = false;
            } else {
                isInteraction = nowElapsed > app.getFgInteractionTime() + this.mConstants.SERVICE_USAGE_INTERACTION_TIME;
            }
        } else {
            isInteraction = app.getCurProcState() <= 7;
            app.setFgInteractionTime(0L);
        }
        if (isInteraction && (!app.reportedInteraction || nowElapsed - app.getInteractionEventTime() > this.mConstants.USAGE_STATS_INTERACTION_INTERVAL)) {
            app.setInteractionEventTime(nowElapsed);
            String[] packages = app.getPackageList();
            if (packages != null) {
                for (String str : packages) {
                    this.mService.mUsageStatsService.reportEvent(str, app.userId, 6);
                }
            }
        }
        app.reportedInteraction = isInteraction;
        if (!isInteraction) {
            app.setInteractionEventTime(0L);
        }
    }

    private void maybeUpdateLastTopTime(ProcessRecord app, long nowUptime) {
        if (app.setProcState <= 2 && app.getCurProcState() > 2) {
            app.lastTopTime = nowUptime;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void idleUidsLocked() {
        int N = this.mActiveUids.size();
        if (N <= 0) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        long maxBgTime = nowElapsed - this.mConstants.BACKGROUND_SETTLE_TIME;
        long nextTime = 0;
        PowerManagerInternal powerManagerInternal = this.mLocalPowerManager;
        if (powerManagerInternal != null) {
            powerManagerInternal.startUidChanges();
        }
        for (int i = N - 1; i >= 0; i--) {
            UidRecord uidRec = this.mActiveUids.valueAt(i);
            long bgTime = uidRec.lastBackgroundTime;
            if (bgTime > 0 && !uidRec.idle) {
                if (bgTime <= maxBgTime) {
                    EventLogTags.writeAmUidIdle(uidRec.uid);
                    uidRec.idle = true;
                    uidRec.setIdle = true;
                    this.mService.doStopUidLocked(uidRec.uid, uidRec);
                } else if (nextTime == 0 || nextTime > bgTime) {
                    nextTime = bgTime;
                }
            }
        }
        PowerManagerInternal powerManagerInternal2 = this.mLocalPowerManager;
        if (powerManagerInternal2 != null) {
            powerManagerInternal2.finishUidChanges();
        }
        if (nextTime > 0) {
            this.mService.mHandler.removeMessages(58);
            this.mService.mHandler.sendEmptyMessageDelayed(58, (this.mConstants.BACKGROUND_SETTLE_TIME + nextTime) - nowElapsed);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final void setAppIdTempWhitelistStateLocked(int appId, boolean onWhitelist) {
        boolean changed = false;
        for (int i = this.mActiveUids.size() - 1; i >= 0; i--) {
            UidRecord uidRec = this.mActiveUids.valueAt(i);
            if (UserHandle.getAppId(uidRec.uid) == appId && uidRec.curWhitelist != onWhitelist) {
                uidRec.curWhitelist = onWhitelist;
                changed = true;
            }
        }
        if (changed) {
            updateOomAdjLocked(OOM_ADJ_REASON_WHITELIST);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public final void setUidTempWhitelistStateLocked(int uid, boolean onWhitelist) {
        UidRecord uidRec = this.mActiveUids.get(uid);
        if (uidRec != null && uidRec.curWhitelist != onWhitelist) {
            uidRec.curWhitelist = onWhitelist;
            updateOomAdjLocked(OOM_ADJ_REASON_WHITELIST);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void dumpProcessListVariablesLocked(ProtoOutputStream proto) {
        proto.write(1120986464305L, this.mAdjSeq);
        proto.write(1120986464306L, this.mProcessList.mLruSeq);
        proto.write(1120986464307L, this.mNumNonCachedProcs);
        proto.write(1120986464309L, this.mNumServiceProcs);
        proto.write(1120986464310L, this.mNewNumServiceProcs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void dumpSequenceNumbersLocked(PrintWriter pw) {
        pw.println("  mAdjSeq=" + this.mAdjSeq + " mLruSeq=" + this.mProcessList.mLruSeq);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void dumpProcCountsLocked(PrintWriter pw) {
        pw.println("  mNumNonCachedProcs=" + this.mNumNonCachedProcs + " (" + this.mProcessList.getLruSizeLocked() + " total) mNumCachedHiddenProcs=" + this.mNumCachedHiddenProcs + " mNumServiceProcs=" + this.mNumServiceProcs + " mNewNumServiceProcs=" + this.mNewNumServiceProcs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mService"})
    public void dumpAppCompactorSettings(PrintWriter pw) {
        this.mAppCompact.dump(pw);
    }
}
