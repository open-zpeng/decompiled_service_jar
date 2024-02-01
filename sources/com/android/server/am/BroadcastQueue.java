package com.android.server.am;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ContentResolver;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.server.display.color.DisplayTransformManager;
import com.android.server.pm.PackageManagerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

/* loaded from: classes.dex */
public final class BroadcastQueue {
    static final int BROADCAST_INTENT_MSG = 200;
    static final int BROADCAST_TIMEOUT_MSG = 201;
    static final int MAX_BROADCAST_HISTORY;
    static final int MAX_BROADCAST_SUMMARY_HISTORY;
    private static final String TAG = "BroadcastQueue";
    private static final String TAG_BROADCAST = "BroadcastQueue";
    private static final String TAG_MU = "BroadcastQueue_MU";
    final Intent[] mBroadcastSummaryHistory;
    boolean mBroadcastsScheduled;
    final BroadcastConstants mConstants;
    final boolean mDelayBehindServices;
    final BroadcastDispatcher mDispatcher;
    final BroadcastHandler mHandler;
    boolean mLogLatencyMetrics;
    BroadcastRecord mPendingBroadcast;
    int mPendingBroadcastRecvIndex;
    boolean mPendingBroadcastTimeoutMessage;
    final String mQueueName;
    final ActivityManagerService mService;
    final long[] mSummaryHistoryDispatchTime;
    final long[] mSummaryHistoryEnqueueTime;
    final long[] mSummaryHistoryFinishTime;
    int mSummaryHistoryNext;
    final ArrayList<BroadcastRecord> mParallelBroadcasts = new ArrayList<>();
    final SparseIntArray mSplitRefcounts = new SparseIntArray();
    private int mNextToken = 0;
    final BroadcastRecord[] mBroadcastHistory = new BroadcastRecord[MAX_BROADCAST_HISTORY];
    int mHistoryNext = 0;

    static {
        MAX_BROADCAST_HISTORY = ActivityManager.isLowRamDeviceStatic() ? 10 : 50;
        MAX_BROADCAST_SUMMARY_HISTORY = ActivityManager.isLowRamDeviceStatic() ? 25 : DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BroadcastHandler extends Handler {
        public BroadcastHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 200) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Received BROADCAST_INTENT_MSG [" + BroadcastQueue.this.mQueueName + "]");
                }
                BroadcastQueue.this.processNextBroadcast(true);
            } else if (i == BroadcastQueue.BROADCAST_TIMEOUT_MSG) {
                synchronized (BroadcastQueue.this.mService) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        BroadcastQueue.this.broadcastTimeoutLocked(true);
                    } catch (Throwable th) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AppNotResponding implements Runnable {
        private final String mAnnotation;
        private final ProcessRecord mApp;

        public AppNotResponding(ProcessRecord app, String annotation) {
            this.mApp = app;
            this.mAnnotation = annotation;
        }

        @Override // java.lang.Runnable
        public void run() {
            this.mApp.appNotResponding(null, null, null, null, false, this.mAnnotation);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BroadcastQueue(ActivityManagerService service, Handler handler, String name, BroadcastConstants constants, boolean allowDelayBehindServices) {
        int i = MAX_BROADCAST_SUMMARY_HISTORY;
        this.mBroadcastSummaryHistory = new Intent[i];
        this.mSummaryHistoryNext = 0;
        this.mSummaryHistoryEnqueueTime = new long[i];
        this.mSummaryHistoryDispatchTime = new long[i];
        this.mSummaryHistoryFinishTime = new long[i];
        this.mBroadcastsScheduled = false;
        this.mPendingBroadcast = null;
        this.mLogLatencyMetrics = true;
        this.mService = service;
        this.mHandler = new BroadcastHandler(handler.getLooper());
        this.mQueueName = name;
        this.mDelayBehindServices = allowDelayBehindServices;
        this.mConstants = constants;
        this.mDispatcher = new BroadcastDispatcher(this, this.mConstants, this.mHandler, this.mService);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void start(ContentResolver resolver) {
        this.mDispatcher.start();
        this.mConstants.startObserving(this.mHandler, resolver);
    }

    public String toString() {
        return this.mQueueName;
    }

    public boolean isPendingBroadcastProcessLocked(int pid) {
        BroadcastRecord broadcastRecord = this.mPendingBroadcast;
        return broadcastRecord != null && broadcastRecord.curApp.pid == pid;
    }

    public void enqueueParallelBroadcastLocked(BroadcastRecord r) {
        this.mParallelBroadcasts.add(r);
        enqueueBroadcastHelper(r);
    }

    public void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        this.mDispatcher.enqueueOrderedBroadcastLocked(r);
        enqueueBroadcastHelper(r);
    }

    private void enqueueBroadcastHelper(BroadcastRecord r) {
        r.enqueueClockTime = System.currentTimeMillis();
        if (Trace.isTagEnabled(64L)) {
            Trace.asyncTraceBegin(64L, createBroadcastTraceTitle(r, 0), System.identityHashCode(r));
        }
    }

    public final BroadcastRecord replaceParallelBroadcastLocked(BroadcastRecord r) {
        return replaceBroadcastLocked(this.mParallelBroadcasts, r, "PARALLEL");
    }

    public final BroadcastRecord replaceOrderedBroadcastLocked(BroadcastRecord r) {
        return this.mDispatcher.replaceBroadcastLocked(r, "ORDERED");
    }

    private BroadcastRecord replaceBroadcastLocked(ArrayList<BroadcastRecord> queue, BroadcastRecord r, String typeForLogging) {
        Intent intent = r.intent;
        for (int i = queue.size() - 1; i > 0; i--) {
            BroadcastRecord old = queue.get(i);
            if (old.userId == r.userId && intent.filterEquals(old.intent)) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "***** DROPPING " + typeForLogging + " [" + this.mQueueName + "]: " + intent);
                }
                queue.set(i, r);
                return old;
            }
        }
        return null;
    }

    /* JADX WARN: Type inference failed for: r4v1, types: [com.android.server.am.ProcessRecord, android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r4v4, types: [com.android.server.am.ProcessRecord, android.os.IBinder] */
    private final void processCurBroadcastLocked(BroadcastRecord r, ProcessRecord app, boolean skipOomAdj) throws RemoteException {
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
            Slog.v("BroadcastQueue", "Process cur broadcast " + r + " for app " + app);
        }
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (app.inFullBackup) {
            skipReceiverLocked(r);
            return;
        }
        r.receiver = app.thread.asBinder();
        r.curApp = app;
        app.curReceivers.add(r);
        app.forceProcessStateUpTo(12);
        this.mService.mProcessList.updateLruProcessLocked(app, false, null);
        if (!skipOomAdj) {
            this.mService.updateOomAdjLocked("updateOomAdj_meh");
        }
        r.intent.setComponent(r.curComponent);
        boolean started = false;
        try {
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT) {
                Slog.v("BroadcastQueue", "Delivering to component " + r.curComponent + ": " + r);
            }
            this.mService.notifyPackageUse(r.intent.getComponent().getPackageName(), 3);
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver, this.mService.compatibilityInfoForPackage(r.curReceiver.applicationInfo), r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId, app.getReportedProcState());
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                Slog.v("BroadcastQueue", "Process cur broadcast " + r + " DELIVERED for app " + app);
            }
            started = true;
        } finally {
            if (!started) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Process cur broadcast " + r + ": NOT STARTED!");
                }
                ?? r4 = 0;
                r.receiver = r4;
                r.curApp = r4;
                app.curReceivers.remove(r);
            }
        }
    }

    public boolean sendPendingBroadcastsLocked(ProcessRecord app) {
        BroadcastRecord br = this.mPendingBroadcast;
        if (br == null || br.curApp.pid <= 0 || br.curApp.pid != app.pid) {
            return false;
        }
        if (br.curApp != app) {
            Slog.e("BroadcastQueue", "App mismatch when sending pending broadcast to " + app.processName + ", intended target is " + br.curApp.processName);
            return false;
        }
        try {
            this.mPendingBroadcast = null;
            processCurBroadcastLocked(br, app, false);
            return true;
        } catch (Exception e) {
            Slog.w("BroadcastQueue", "Exception in new application when starting receiver " + br.curComponent.flattenToShortString(), e);
            logBroadcastReceiverDiscardLocked(br);
            finishReceiverLocked(br, br.resultCode, br.resultData, br.resultExtras, br.resultAbort, false);
            scheduleBroadcastsLocked();
            br.state = 0;
            throw new RuntimeException(e.getMessage());
        }
    }

    public void skipPendingBroadcastLocked(int pid) {
        BroadcastRecord br = this.mPendingBroadcast;
        if (br != null && br.curApp.pid == pid) {
            br.state = 0;
            br.nextReceiver = this.mPendingBroadcastRecvIndex;
            this.mPendingBroadcast = null;
            scheduleBroadcastsLocked();
        }
    }

    public void skipCurrentReceiverLocked(ProcessRecord app) {
        BroadcastRecord broadcastRecord;
        BroadcastRecord r = null;
        BroadcastRecord curActive = this.mDispatcher.getActiveBroadcastLocked();
        if (curActive != null && curActive.curApp == app) {
            r = curActive;
        }
        if (r == null && (broadcastRecord = this.mPendingBroadcast) != null && broadcastRecord.curApp == app) {
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                Slog.v("BroadcastQueue", "[" + this.mQueueName + "] skip & discard pending app " + r);
            }
            r = this.mPendingBroadcast;
        }
        if (r != null) {
            skipReceiverLocked(r);
        }
    }

    private void skipReceiverLocked(BroadcastRecord r) {
        logBroadcastReceiverDiscardLocked(r);
        finishReceiverLocked(r, r.resultCode, r.resultData, r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();
    }

    public void scheduleBroadcastsLocked() {
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
            Slog.v("BroadcastQueue", "Schedule broadcasts [" + this.mQueueName + "]: current=" + this.mBroadcastsScheduled);
        }
        if (this.mBroadcastsScheduled) {
            return;
        }
        BroadcastHandler broadcastHandler = this.mHandler;
        broadcastHandler.sendMessage(broadcastHandler.obtainMessage(200, this));
        this.mBroadcastsScheduled = true;
    }

    public BroadcastRecord getMatchingOrderedReceiver(IBinder receiver) {
        BroadcastRecord br = this.mDispatcher.getActiveBroadcastLocked();
        if (br != null && br.receiver == receiver) {
            return br;
        }
        return null;
    }

    private int nextSplitTokenLocked() {
        int next = this.mNextToken + 1;
        if (next <= 0) {
            next = 1;
        }
        this.mNextToken = next;
        return next;
    }

    private void postActivityStartTokenRemoval(final ProcessRecord app, final BroadcastRecord r) {
        String msgToken = (app.toShortString() + r.toString()).intern();
        this.mHandler.removeCallbacksAndMessages(msgToken);
        this.mHandler.postAtTime(new Runnable() { // from class: com.android.server.am.-$$Lambda$BroadcastQueue$u5X4lnAPSSN1Kjb_BebqIicVqK4
            @Override // java.lang.Runnable
            public final void run() {
                ProcessRecord.this.removeAllowBackgroundActivityStartsToken(r);
            }
        }, msgToken, r.receiverTime + this.mConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT);
    }

    public boolean finishReceiverLocked(BroadcastRecord r, int resultCode, String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices) {
        ActivityInfo nextReceiver;
        int state = r.state;
        ActivityInfo receiver = r.curReceiver;
        long finishTime = SystemClock.uptimeMillis();
        long elapsed = finishTime - r.receiverTime;
        boolean z = false;
        r.state = 0;
        if (state == 0) {
            Slog.w("BroadcastQueue", "finishReceiver [" + this.mQueueName + "] called but state is IDLE");
        }
        if (r.allowBackgroundActivityStarts && r.curApp != null) {
            if (elapsed <= this.mConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT) {
                postActivityStartTokenRemoval(r.curApp, r);
            } else {
                r.curApp.removeAllowBackgroundActivityStartsToken(r);
            }
        }
        if (r.nextReceiver > 0) {
            r.duration[r.nextReceiver - 1] = elapsed;
        }
        if (!r.timeoutExempt) {
            if (this.mConstants.SLOW_TIME > 0 && elapsed > this.mConstants.SLOW_TIME) {
                if (!UserHandle.isCore(r.curApp.uid)) {
                    if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i("BroadcastQueue", "Broadcast receiver " + (r.nextReceiver - 1) + " was slow: " + receiver + " br=" + r);
                    }
                    if (r.curApp != null) {
                        this.mDispatcher.startDeferring(r.curApp.uid);
                    } else {
                        Slog.d("BroadcastQueue", "finish receiver curApp is null? " + r);
                    }
                } else if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i("BroadcastQueue", "Core uid " + r.curApp.uid + " receiver was slow but not deferring: " + receiver + " br=" + r);
                }
            }
        } else if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
            Slog.i("BroadcastQueue", "Finished broadcast " + r.intent.getAction() + " is exempt from deferral policy");
        }
        r.receiver = null;
        r.intent.setComponent(null);
        if (r.curApp != null && r.curApp.curReceivers.contains(r)) {
            r.curApp.curReceivers.remove(r);
        }
        if (r.curFilter != null) {
            r.curFilter.receiverList.curBroadcast = null;
        }
        r.curFilter = null;
        r.curReceiver = null;
        r.curApp = null;
        this.mPendingBroadcast = null;
        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        if (resultAbort && (r.intent.getFlags() & 134217728) == 0) {
            r.resultAbort = resultAbort;
        } else {
            r.resultAbort = false;
        }
        if (waitForServices && r.curComponent != null && r.queue.mDelayBehindServices && r.queue.mDispatcher.getActiveBroadcastLocked() == r) {
            if (r.nextReceiver < r.receivers.size()) {
                Object obj = r.receivers.get(r.nextReceiver);
                nextReceiver = obj instanceof ActivityInfo ? (ActivityInfo) obj : null;
            } else {
                nextReceiver = null;
            }
            if (receiver != null && nextReceiver != null && receiver.applicationInfo.uid == nextReceiver.applicationInfo.uid && receiver.processName.equals(nextReceiver.processName)) {
                z = false;
            } else if (!this.mService.mServices.hasBackgroundServicesLocked(r.userId)) {
                z = false;
            } else {
                Slog.i("BroadcastQueue", "Delay finish: " + r.curComponent.flattenToShortString());
                r.state = 4;
                return false;
            }
        }
        r.curComponent = null;
        if (state == 1 || state == 3) {
            return true;
        }
        return z;
    }

    public void backgroundServicesFinishedLocked(int userId) {
        BroadcastRecord br = this.mDispatcher.getActiveBroadcastLocked();
        if (br != null && br.userId == userId && br.state == 4) {
            Slog.i("BroadcastQueue", "Resuming delayed broadcast");
            br.curComponent = null;
            br.state = 0;
            processNextBroadcast(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver, Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
        if (app != null) {
            if (app.thread == null) {
                throw new RemoteException("app.thread must not be null");
            }
            try {
                app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode, data, extras, ordered, sticky, sendingUser, app.getReportedProcState());
                return;
            } catch (RemoteException ex) {
                synchronized (this.mService) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        Slog.w("BroadcastQueue", "Can't deliver broadcast to " + app.processName + " (pid " + app.pid + "). Crashing it.");
                        app.scheduleCrash("can't deliver broadcast");
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw ex;
                    } catch (Throwable th) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
            }
        }
        receiver.performReceive(intent, resultCode, data, extras, ordered, sticky, sendingUser);
    }

    /* JADX WARN: Removed duplicated region for block: B:72:0x03c9  */
    /* JADX WARN: Removed duplicated region for block: B:73:0x03cb  */
    /* JADX WARN: Removed duplicated region for block: B:93:0x0495  */
    /* JADX WARN: Removed duplicated region for block: B:95:0x049a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void deliverToRegisteredReceiverLocked(com.android.server.am.BroadcastRecord r22, com.android.server.am.BroadcastFilter r23, boolean r24, int r25) {
        /*
            Method dump skipped, instructions count: 1427
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.BroadcastQueue.deliverToRegisteredReceiverLocked(com.android.server.am.BroadcastRecord, com.android.server.am.BroadcastFilter, boolean, int):void");
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(BroadcastRecord receiverRecord, String receivingPackageName, final int receivingUserId) {
        boolean callerForeground;
        if (this.mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(receivingPackageName, receivingUserId)) {
            if (receiverRecord.callerApp != null) {
                callerForeground = receiverRecord.callerApp.setSchedGroup != 0;
            } else {
                callerForeground = true;
            }
            if (callerForeground && receiverRecord.intent.getComponent() != null) {
                PendingIntentRecord intentSender = this.mService.mPendingIntentController.getIntentSender(1, receiverRecord.callerPackage, receiverRecord.callingUid, receiverRecord.userId, null, null, 0, new Intent[]{receiverRecord.intent}, new String[]{receiverRecord.intent.resolveType(this.mService.mContext.getContentResolver())}, 1409286144, null);
                final Intent intent = new Intent("android.intent.action.REVIEW_PERMISSIONS");
                intent.addFlags(411041792);
                intent.putExtra("android.intent.extra.PACKAGE_NAME", receivingPackageName);
                intent.putExtra("android.intent.extra.INTENT", new IntentSender(intentSender));
                if (ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW) {
                    Slog.i("BroadcastQueue", "u" + receivingUserId + " Launching permission review for package " + receivingPackageName);
                }
                this.mHandler.post(new Runnable() { // from class: com.android.server.am.BroadcastQueue.1
                    @Override // java.lang.Runnable
                    public void run() {
                        BroadcastQueue.this.mService.mContext.startActivityAsUser(intent, new UserHandle(receivingUserId));
                    }
                });
            } else {
                Slog.w("BroadcastQueue", "u" + receivingUserId + " Receiving a broadcast in package" + receivingPackageName + " requires a permissions review");
            }
            return false;
        }
        return true;
    }

    final void scheduleTempWhitelistLocked(int uid, long duration, BroadcastRecord r) {
        if (duration > 2147483647L) {
            duration = 2147483647L;
        }
        StringBuilder b = new StringBuilder();
        b.append("broadcast:");
        UserHandle.formatUid(b, r.callingUid);
        b.append(":");
        if (r.intent.getAction() != null) {
            b.append(r.intent.getAction());
        } else if (r.intent.getComponent() != null) {
            r.intent.getComponent().appendShortString(b);
        } else if (r.intent.getData() != null) {
            b.append(r.intent.getData());
        }
        this.mService.tempWhitelistUidLocked(uid, duration, b.toString());
    }

    final boolean isSignaturePerm(String[] perms) {
        if (perms == null) {
            return false;
        }
        IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = perms.length - 1; i >= 0; i--) {
            try {
                PermissionInfo pi = pm.getPermissionInfo(perms[i], PackageManagerService.PLATFORM_PACKAGE_NAME, 0);
                if (pi == null || (pi.protectionLevel & 31) != 2) {
                    return false;
                }
            } catch (RemoteException e) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void processNextBroadcast(boolean fromMsg) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                processNextBroadcastLocked(fromMsg, false);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:174:0x0576  */
    /* JADX WARN: Removed duplicated region for block: B:178:0x0587  */
    /* JADX WARN: Removed duplicated region for block: B:181:0x0595  */
    /* JADX WARN: Removed duplicated region for block: B:184:0x05b6  */
    /* JADX WARN: Removed duplicated region for block: B:374:0x0deb  */
    /* JADX WARN: Removed duplicated region for block: B:379:0x0e2b  */
    /* JADX WARN: Removed duplicated region for block: B:421:0x0f7a  */
    /* JADX WARN: Removed duplicated region for block: B:422:0x0fa5  */
    /* JADX WARN: Removed duplicated region for block: B:425:0x0fcb  */
    /* JADX WARN: Removed duplicated region for block: B:426:0x0fce  */
    /* JADX WARN: Removed duplicated region for block: B:429:0x0fe4  */
    /* JADX WARN: Removed duplicated region for block: B:431:0x1033  */
    /* JADX WARN: Removed duplicated region for block: B:433:0x103d A[LOOP:2: B:60:0x01cb->B:433:0x103d, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:442:0x04d5 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:469:0x05ea A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public final void processNextBroadcastLocked(boolean r37, boolean r38) {
        /*
            Method dump skipped, instructions count: 4167
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.BroadcastQueue.processNextBroadcastLocked(boolean, boolean):void");
    }

    private void maybeAddAllowBackgroundActivityStartsToken(ProcessRecord proc, BroadcastRecord r) {
        if (r == null || proc == null || !r.allowBackgroundActivityStarts) {
            return;
        }
        String msgToken = (proc.toShortString() + r.toString()).intern();
        this.mHandler.removeCallbacksAndMessages(msgToken);
        proc.addAllowBackgroundActivityStartsToken(r);
    }

    final void setBroadcastTimeoutLocked(long timeoutTime) {
        if (!this.mPendingBroadcastTimeoutMessage) {
            Message msg = this.mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG, this);
            this.mHandler.sendMessageAtTime(msg, timeoutTime);
            this.mPendingBroadcastTimeoutMessage = true;
        }
    }

    final void cancelBroadcastTimeoutLocked() {
        if (this.mPendingBroadcastTimeoutMessage) {
            this.mHandler.removeMessages(BROADCAST_TIMEOUT_MSG, this);
            this.mPendingBroadcastTimeoutMessage = false;
        }
    }

    final void broadcastTimeoutLocked(boolean fromMsg) {
        Object curReceiver;
        ProcessRecord app;
        String anrMessage;
        boolean debugging = false;
        if (fromMsg) {
            this.mPendingBroadcastTimeoutMessage = false;
        }
        if (this.mDispatcher.isEmpty() || this.mDispatcher.getActiveBroadcastLocked() == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        BroadcastRecord r = this.mDispatcher.getActiveBroadcastLocked();
        if (fromMsg) {
            if (!this.mService.mProcessesReady) {
                return;
            }
            if (r.timeoutExempt) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.i("BroadcastQueue", "Broadcast timeout but it's exempt: " + r.intent.getAction());
                    return;
                }
                return;
            }
            long timeoutTime = r.receiverTime + this.mConstants.TIMEOUT;
            if (timeoutTime > now) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Premature timeout [" + this.mQueueName + "] @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for " + timeoutTime);
                }
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
        }
        if (r.state == 4) {
            StringBuilder sb = new StringBuilder();
            sb.append("Waited long enough for: ");
            sb.append(r.curComponent != null ? r.curComponent.flattenToShortString() : "(null)");
            Slog.i("BroadcastQueue", sb.toString());
            r.curComponent = null;
            r.state = 0;
            processNextBroadcast(false);
            return;
        }
        if (r.curApp != null && r.curApp.isDebugging()) {
            debugging = true;
        }
        Slog.w("BroadcastQueue", "Timeout of broadcast " + r + " - receiver=" + r.receiver + ", started " + (now - r.receiverTime) + "ms ago");
        r.receiverTime = now;
        if (!debugging) {
            r.anrCount++;
        }
        ProcessRecord app2 = null;
        if (r.nextReceiver > 0) {
            Object curReceiver2 = r.receivers.get(r.nextReceiver - 1);
            r.delivery[r.nextReceiver - 1] = 3;
            curReceiver = curReceiver2;
        } else {
            Object curReceiver3 = r.curReceiver;
            curReceiver = curReceiver3;
        }
        Slog.w("BroadcastQueue", "Receiver during timeout of " + r + " : " + curReceiver);
        logBroadcastReceiverDiscardLocked(r);
        if (curReceiver != null && (curReceiver instanceof BroadcastFilter)) {
            BroadcastFilter bf = (BroadcastFilter) curReceiver;
            if (bf.receiverList.pid != 0 && bf.receiverList.pid != ActivityManagerService.MY_PID) {
                synchronized (this.mService.mPidsSelfLocked) {
                    app2 = this.mService.mPidsSelfLocked.get(bf.receiverList.pid);
                }
            }
            app = app2;
        } else {
            app = r.curApp;
        }
        if (app == null) {
            anrMessage = null;
        } else {
            String anrMessage2 = "Broadcast of " + r.intent.toString();
            anrMessage = anrMessage2;
        }
        if (this.mPendingBroadcast == r) {
            this.mPendingBroadcast = null;
        }
        finishReceiverLocked(r, r.resultCode, r.resultData, r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();
        if (!debugging && anrMessage != null) {
            this.mHandler.post(new AppNotResponding(app, anrMessage));
        }
    }

    private final int ringAdvance(int x, int increment, int ringSize) {
        int x2 = x + increment;
        if (x2 < 0) {
            return ringSize - 1;
        }
        if (x2 >= ringSize) {
            return 0;
        }
        return x2;
    }

    private final void addBroadcastToHistoryLocked(BroadcastRecord original) {
        if (original.callingUid < 0) {
            return;
        }
        original.finishTime = SystemClock.uptimeMillis();
        if (Trace.isTagEnabled(64L)) {
            Trace.asyncTraceEnd(64L, createBroadcastTraceTitle(original, 1), System.identityHashCode(original));
        }
        BroadcastRecord historyRecord = original.maybeStripForHistory();
        BroadcastRecord[] broadcastRecordArr = this.mBroadcastHistory;
        int i = this.mHistoryNext;
        broadcastRecordArr[i] = historyRecord;
        this.mHistoryNext = ringAdvance(i, 1, MAX_BROADCAST_HISTORY);
        this.mBroadcastSummaryHistory[this.mSummaryHistoryNext] = historyRecord.intent;
        this.mSummaryHistoryEnqueueTime[this.mSummaryHistoryNext] = historyRecord.enqueueClockTime;
        this.mSummaryHistoryDispatchTime[this.mSummaryHistoryNext] = historyRecord.dispatchClockTime;
        this.mSummaryHistoryFinishTime[this.mSummaryHistoryNext] = System.currentTimeMillis();
        this.mSummaryHistoryNext = ringAdvance(this.mSummaryHistoryNext, 1, MAX_BROADCAST_SUMMARY_HISTORY);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean cleanupDisabledPackageReceiversLocked(String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        boolean didSomething = false;
        for (int i = this.mParallelBroadcasts.size() - 1; i >= 0; i--) {
            didSomething |= this.mParallelBroadcasts.get(i).cleanupDisabledPackageReceiversLocked(packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething | this.mDispatcher.cleanupDisabledPackageReceiversLocked(packageName, filterByClasses, userId, doit);
    }

    final void logBroadcastReceiverDiscardLocked(BroadcastRecord r) {
        int logIndex = r.nextReceiver - 1;
        if (logIndex >= 0 && logIndex < r.receivers.size()) {
            Object curReceiver = r.receivers.get(logIndex);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent((int) EventLogTags.AM_BROADCAST_DISCARD_FILTER, Integer.valueOf(bf.owningUserId), Integer.valueOf(System.identityHashCode(r)), r.intent.getAction(), Integer.valueOf(logIndex), Integer.valueOf(System.identityHashCode(bf)));
                return;
            }
            ResolveInfo ri = (ResolveInfo) curReceiver;
            EventLog.writeEvent((int) EventLogTags.AM_BROADCAST_DISCARD_APP, Integer.valueOf(UserHandle.getUserId(ri.activityInfo.applicationInfo.uid)), Integer.valueOf(System.identityHashCode(r)), r.intent.getAction(), Integer.valueOf(logIndex), ri.toString());
            return;
        }
        if (logIndex < 0) {
            Slog.w("BroadcastQueue", "Discarding broadcast before first receiver is invoked: " + r);
        }
        EventLog.writeEvent((int) EventLogTags.AM_BROADCAST_DISCARD_APP, -1, Integer.valueOf(System.identityHashCode(r)), r.intent.getAction(), Integer.valueOf(r.nextReceiver), "NONE");
    }

    private String createBroadcastTraceTitle(BroadcastRecord record, int state) {
        Object[] objArr = new Object[4];
        objArr[0] = state == 0 ? "in queue" : "dispatched";
        objArr[1] = record.callerPackage == null ? "" : record.callerPackage;
        objArr[2] = record.callerApp == null ? "process unknown" : record.callerApp.toShortString();
        objArr[3] = record.intent != null ? record.intent.getAction() : "";
        return String.format("Broadcast %s from %s (%s) %s", objArr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isIdle() {
        return this.mParallelBroadcasts.isEmpty() && this.mDispatcher.isEmpty() && this.mPendingBroadcast == null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelDeferrals() {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mDispatcher.cancelDeferralsLocked();
                scheduleBroadcastsLocked();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String describeState() {
        String str;
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                str = this.mParallelBroadcasts.size() + " parallel; " + this.mDispatcher.describeStateLocked();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return str;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        int i;
        int lastIndex;
        long token = proto.start(fieldId);
        proto.write(1138166333441L, this.mQueueName);
        int N = this.mParallelBroadcasts.size();
        for (int i2 = N - 1; i2 >= 0; i2--) {
            this.mParallelBroadcasts.get(i2).writeToProto(proto, 2246267895810L);
        }
        this.mDispatcher.writeToProto(proto, 2246267895811L);
        BroadcastRecord broadcastRecord = this.mPendingBroadcast;
        if (broadcastRecord != null) {
            broadcastRecord.writeToProto(proto, 1146756268036L);
        }
        int lastIndex2 = this.mHistoryNext;
        int ringIndex = lastIndex2;
        do {
            i = -1;
            ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = this.mBroadcastHistory[ringIndex];
            if (r != null) {
                r.writeToProto(proto, 2246267895813L);
                continue;
            }
        } while (ringIndex != lastIndex2);
        int i3 = this.mSummaryHistoryNext;
        int ringIndex2 = i3;
        int lastIndex3 = i3;
        while (true) {
            int ringIndex3 = ringAdvance(ringIndex2, i, MAX_BROADCAST_SUMMARY_HISTORY);
            Intent intent = this.mBroadcastSummaryHistory[ringIndex3];
            if (intent == null) {
                lastIndex = lastIndex3;
            } else {
                long summaryToken = proto.start(2246267895814L);
                lastIndex = lastIndex3;
                intent.writeToProto(proto, 1146756268033L, false, true, true, false);
                proto.write(1112396529666L, this.mSummaryHistoryEnqueueTime[ringIndex3]);
                proto.write(1112396529667L, this.mSummaryHistoryDispatchTime[ringIndex3]);
                proto.write(1112396529668L, this.mSummaryHistoryFinishTime[ringIndex3]);
                proto.end(summaryToken);
            }
            int lastIndex4 = lastIndex;
            if (ringIndex3 != lastIndex4) {
                lastIndex3 = lastIndex4;
                ringIndex2 = ringIndex3;
                i = -1;
            } else {
                proto.end(token);
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage, boolean needSep) {
        boolean needSep2;
        BroadcastRecord broadcastRecord;
        boolean printed;
        String str;
        int lastIndex;
        String str2 = dumpPackage;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String str3 = ":";
        if (this.mParallelBroadcasts.isEmpty() && this.mDispatcher.isEmpty() && this.mPendingBroadcast == null) {
            needSep2 = needSep;
        } else {
            boolean printed2 = false;
            boolean needSep3 = needSep;
            for (int i = this.mParallelBroadcasts.size() - 1; i >= 0; i--) {
                BroadcastRecord br = this.mParallelBroadcasts.get(i);
                if (str2 == null || str2.equals(br.callerPackage)) {
                    if (!printed2) {
                        if (needSep3) {
                            pw.println();
                        }
                        needSep3 = true;
                        printed2 = true;
                        pw.println("  Active broadcasts [" + this.mQueueName + "]:");
                    }
                    pw.println("  Active Broadcast " + this.mQueueName + " #" + i + ":");
                    br.dump(pw, "    ", sdf);
                }
            }
            this.mDispatcher.dumpLocked(pw, str2, this.mQueueName, sdf);
            if (str2 == null || ((broadcastRecord = this.mPendingBroadcast) != null && str2.equals(broadcastRecord.callerPackage))) {
                pw.println();
                pw.println("  Pending broadcast [" + this.mQueueName + "]:");
                BroadcastRecord broadcastRecord2 = this.mPendingBroadcast;
                if (broadcastRecord2 != null) {
                    broadcastRecord2.dump(pw, "    ", sdf);
                } else {
                    pw.println("    (null)");
                }
                needSep2 = true;
            } else {
                needSep2 = needSep3;
            }
        }
        this.mConstants.dump(pw);
        boolean printed3 = false;
        int i2 = -1;
        int lastIndex2 = this.mHistoryNext;
        int ringIndex = lastIndex2;
        while (true) {
            int ringIndex2 = ringAdvance(ringIndex, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = this.mBroadcastHistory[ringIndex2];
            int lastIndex3 = lastIndex2;
            if (r == null) {
                str = str3;
            } else {
                i2++;
                if (str2 != null && !str2.equals(r.callerPackage)) {
                    str = str3;
                } else {
                    if (printed3) {
                        printed = printed3;
                    } else {
                        if (needSep2) {
                            pw.println();
                        }
                        needSep2 = true;
                        pw.println("  Historical broadcasts [" + this.mQueueName + "]:");
                        printed = true;
                    }
                    if (dumpAll) {
                        pw.print("  Historical Broadcast " + this.mQueueName + " #");
                        pw.print(i2);
                        pw.println(str3);
                        r.dump(pw, "    ", sdf);
                        str = str3;
                    } else {
                        pw.print("  #");
                        pw.print(i2);
                        pw.print(": ");
                        pw.println(r);
                        pw.print("    ");
                        str = str3;
                        pw.println(r.intent.toShortString(false, true, true, false));
                        if (r.targetComp != null && r.targetComp != r.intent.getComponent()) {
                            pw.print("    targetComp: ");
                            pw.println(r.targetComp.toShortString());
                        }
                        Bundle bundle = r.intent.getExtras();
                        if (bundle != null) {
                            pw.print("    extras: ");
                            pw.println(bundle.toString());
                        }
                    }
                    printed3 = printed;
                }
            }
            ringIndex = ringIndex2;
            if (ringIndex == lastIndex3) {
                break;
            }
            str2 = dumpPackage;
            lastIndex2 = lastIndex3;
            str3 = str;
        }
        if (str2 == null) {
            int lastIndex4 = this.mSummaryHistoryNext;
            int ringIndex3 = lastIndex4;
            if (dumpAll) {
                printed3 = false;
                i2 = -1;
            } else {
                int j = i2;
                while (j > 0 && ringIndex3 != lastIndex4) {
                    ringIndex3 = ringAdvance(ringIndex3, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                    if (this.mBroadcastHistory[ringIndex3] != null) {
                        j--;
                    }
                }
            }
            while (true) {
                ringIndex3 = ringAdvance(ringIndex3, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                Intent intent = this.mBroadcastSummaryHistory[ringIndex3];
                if (intent == null) {
                    lastIndex = lastIndex4;
                } else {
                    if (!printed3) {
                        if (needSep2) {
                            pw.println();
                        }
                        pw.println("  Historical broadcasts summary [" + this.mQueueName + "]:");
                        printed3 = true;
                        needSep2 = true;
                    }
                    if (!dumpAll && i2 >= 50) {
                        pw.println("  ...");
                        break;
                    }
                    i2++;
                    pw.print("  #");
                    pw.print(i2);
                    pw.print(": ");
                    boolean printed4 = printed3;
                    pw.println(intent.toShortString(false, true, true, false));
                    pw.print("    ");
                    lastIndex = lastIndex4;
                    TimeUtils.formatDuration(this.mSummaryHistoryDispatchTime[ringIndex3] - this.mSummaryHistoryEnqueueTime[ringIndex3], pw);
                    pw.print(" dispatch ");
                    TimeUtils.formatDuration(this.mSummaryHistoryFinishTime[ringIndex3] - this.mSummaryHistoryDispatchTime[ringIndex3], pw);
                    pw.println(" finish");
                    pw.print("    enq=");
                    pw.print(sdf.format(new Date(this.mSummaryHistoryEnqueueTime[ringIndex3])));
                    pw.print(" disp=");
                    pw.print(sdf.format(new Date(this.mSummaryHistoryDispatchTime[ringIndex3])));
                    pw.print(" fin=");
                    pw.println(sdf.format(new Date(this.mSummaryHistoryFinishTime[ringIndex3])));
                    Bundle bundle2 = intent.getExtras();
                    if (bundle2 != null) {
                        pw.print("    extras: ");
                        pw.println(bundle2.toString());
                    }
                    printed3 = printed4;
                }
                int lastIndex5 = lastIndex;
                if (ringIndex3 == lastIndex5) {
                    break;
                }
                lastIndex4 = lastIndex5;
            }
        }
        return needSep2;
    }
}
