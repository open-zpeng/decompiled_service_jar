package com.android.server.am;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
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
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.display.DisplayTransformManager;
import com.android.server.pm.DumpState;
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
    final boolean mDelayBehindServices;
    final BroadcastHandler mHandler;
    int mPendingBroadcastRecvIndex;
    boolean mPendingBroadcastTimeoutMessage;
    final String mQueueName;
    final ActivityManagerService mService;
    final long mTimeoutPeriod;
    final ArrayList<BroadcastRecord> mParallelBroadcasts = new ArrayList<>();
    final ArrayList<BroadcastRecord> mOrderedBroadcasts = new ArrayList<>();
    final BroadcastRecord[] mBroadcastHistory = new BroadcastRecord[MAX_BROADCAST_HISTORY];
    int mHistoryNext = 0;
    final Intent[] mBroadcastSummaryHistory = new Intent[MAX_BROADCAST_SUMMARY_HISTORY];
    int mSummaryHistoryNext = 0;
    final long[] mSummaryHistoryEnqueueTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryDispatchTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    final long[] mSummaryHistoryFinishTime = new long[MAX_BROADCAST_SUMMARY_HISTORY];
    boolean mBroadcastsScheduled = false;
    BroadcastRecord mPendingBroadcast = null;

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
            switch (msg.what) {
                case 200:
                    if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                        Slog.v("BroadcastQueue", "Received BROADCAST_INTENT_MSG");
                    }
                    BroadcastQueue.this.processNextBroadcast(true);
                    return;
                case BroadcastQueue.BROADCAST_TIMEOUT_MSG /* 201 */:
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
                    return;
                default:
                    return;
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
            BroadcastQueue.this.mService.mAppErrors.appNotResponding(this.mApp, null, null, false, this.mAnnotation);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BroadcastQueue(ActivityManagerService service, Handler handler, String name, long timeoutPeriod, boolean allowDelayBehindServices) {
        this.mService = service;
        this.mHandler = new BroadcastHandler(handler.getLooper());
        this.mQueueName = name;
        this.mTimeoutPeriod = timeoutPeriod;
        this.mDelayBehindServices = allowDelayBehindServices;
    }

    public String toString() {
        return this.mQueueName;
    }

    public boolean isPendingBroadcastProcessLocked(int pid) {
        return this.mPendingBroadcast != null && this.mPendingBroadcast.curApp.pid == pid;
    }

    public void enqueueParallelBroadcastLocked(BroadcastRecord r) {
        this.mParallelBroadcasts.add(r);
        enqueueBroadcastHelper(r);
    }

    public void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        this.mOrderedBroadcasts.add(r);
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
        return replaceBroadcastLocked(this.mOrderedBroadcasts, r, "ORDERED");
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
        app.forceProcessStateUpTo(10);
        this.mService.updateLruProcessLocked(app, false, null);
        if (!skipOomAdj) {
            this.mService.updateOomAdjLocked();
        }
        r.intent.setComponent(r.curComponent);
        try {
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT) {
                Slog.v("BroadcastQueue", "Delivering to component " + r.curComponent + ": " + r);
            }
            this.mService.notifyPackageUse(r.intent.getComponent().getPackageName(), 3);
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver, this.mService.compatibilityInfoForPackageLocked(r.curReceiver.applicationInfo), r.resultCode, r.resultData, r.resultExtras, r.ordered, r.userId, app.repProcState);
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                Slog.v("BroadcastQueue", "Process cur broadcast " + r + " DELIVERED for app " + app);
            }
            if (1 == 0) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Process cur broadcast " + r + ": NOT STARTED!");
                }
                r.receiver = null;
                r.curApp = null;
                app.curReceivers.remove(r);
            }
        } catch (Throwable th) {
            if (0 == 0) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Process cur broadcast " + r + ": NOT STARTED!");
                }
                r.receiver = null;
                r.curApp = null;
                app.curReceivers.remove(r);
            }
            throw th;
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
        BroadcastRecord r = null;
        if (this.mOrderedBroadcasts.size() > 0) {
            BroadcastRecord br = this.mOrderedBroadcasts.get(0);
            if (br.curApp == app) {
                r = br;
            }
        }
        if (r == null && this.mPendingBroadcast != null && this.mPendingBroadcast.curApp == app) {
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
        this.mHandler.sendMessage(this.mHandler.obtainMessage(200, this));
        this.mBroadcastsScheduled = true;
    }

    public BroadcastRecord getMatchingOrderedReceiver(IBinder receiver) {
        BroadcastRecord r;
        if (this.mOrderedBroadcasts.size() > 0 && (r = this.mOrderedBroadcasts.get(0)) != null && r.receiver == receiver) {
            return r;
        }
        return null;
    }

    public boolean finishReceiverLocked(BroadcastRecord r, int resultCode, String resultData, Bundle resultExtras, boolean resultAbort, boolean waitForServices) {
        ActivityInfo nextReceiver;
        int state = r.state;
        ActivityInfo receiver = r.curReceiver;
        r.state = 0;
        if (state == 0) {
            Slog.w("BroadcastQueue", "finishReceiver [" + this.mQueueName + "] called but state is IDLE");
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
        if (waitForServices && r.curComponent != null && r.queue.mDelayBehindServices && r.queue.mOrderedBroadcasts.size() > 0 && r.queue.mOrderedBroadcasts.get(0) == r) {
            if (r.nextReceiver < r.receivers.size()) {
                Object obj = r.receivers.get(r.nextReceiver);
                nextReceiver = obj instanceof ActivityInfo ? (ActivityInfo) obj : null;
            } else {
                nextReceiver = null;
            }
            if ((receiver == null || nextReceiver == null || receiver.applicationInfo.uid != nextReceiver.applicationInfo.uid || !receiver.processName.equals(nextReceiver.processName)) && this.mService.mServices.hasBackgroundServicesLocked(r.userId)) {
                Slog.i("BroadcastQueue", "Delay finish: " + r.curComponent.flattenToShortString());
                r.state = 4;
                return false;
            }
        }
        r.curComponent = null;
        if (state != 1 && state != 3) {
            return false;
        }
        return true;
    }

    public void backgroundServicesFinishedLocked(int userId) {
        if (this.mOrderedBroadcasts.size() > 0) {
            BroadcastRecord br = this.mOrderedBroadcasts.get(0);
            if (br.userId == userId && br.state == 4) {
                Slog.i("BroadcastQueue", "Resuming delayed broadcast");
                br.curComponent = null;
                br.state = 0;
                processNextBroadcast(false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver, Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
        if (app != null) {
            if (app.thread == null) {
                throw new RemoteException("app.thread must not be null");
            }
            try {
                app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode, data, extras, ordered, sticky, sendingUser, app.repProcState);
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

    private void deliverToRegisteredReceiverLocked(BroadcastRecord r, BroadcastFilter filter, boolean ordered, int index) {
        boolean skip = false;
        if (filter.requiredPermission != null) {
            int perm = this.mService.checkComponentPermission(filter.requiredPermission, r.callingPid, r.callingUid, -1, true);
            if (perm != 0) {
                Slog.w("BroadcastQueue", "Permission Denial: broadcasting " + r.intent.toString() + " from " + r.callerPackage + " (pid=" + r.callingPid + ", uid=" + r.callingUid + ") requires " + filter.requiredPermission + " due to registered receiver " + filter);
                skip = true;
            } else {
                int opCode = AppOpsManager.permissionToOpCode(filter.requiredPermission);
                if (opCode != -1 && this.mService.mAppOpsService.noteOperation(opCode, r.callingUid, r.callerPackage) != 0) {
                    Slog.w("BroadcastQueue", "Appop Denial: broadcasting " + r.intent.toString() + " from " + r.callerPackage + " (pid=" + r.callingPid + ", uid=" + r.callingUid + ") requires appop " + AppOpsManager.permissionToOp(filter.requiredPermission) + " due to registered receiver " + filter);
                    skip = true;
                }
            }
        }
        if (!skip && r.requiredPermissions != null && r.requiredPermissions.length > 0) {
            int i = 0;
            while (true) {
                if (i >= r.requiredPermissions.length) {
                    break;
                }
                String requiredPermission = r.requiredPermissions[i];
                int perm2 = this.mService.checkComponentPermission(requiredPermission, filter.receiverList.pid, filter.receiverList.uid, -1, true);
                if (perm2 != 0) {
                    Slog.w("BroadcastQueue", "Permission Denial: receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") requires " + requiredPermission + " due to sender " + r.callerPackage + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
                int appOp = AppOpsManager.permissionToOpCode(requiredPermission);
                if (appOp == -1 || appOp == r.appOp || this.mService.mAppOpsService.noteOperation(appOp, filter.receiverList.uid, filter.packageName) == 0) {
                    i++;
                } else {
                    Slog.w("BroadcastQueue", "Appop Denial: receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") requires appop " + AppOpsManager.permissionToOp(requiredPermission) + " due to sender " + r.callerPackage + " (uid " + r.callingUid + ")");
                    skip = true;
                    break;
                }
            }
        }
        if (!skip && (r.requiredPermissions == null || r.requiredPermissions.length == 0)) {
            int perm3 = this.mService.checkComponentPermission(null, filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm3 != 0) {
                Slog.w("BroadcastQueue", "Permission Denial: security check failed when receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") due to sender " + r.callerPackage + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }
        if (!skip && r.appOp != -1 && this.mService.mAppOpsService.noteOperation(r.appOp, filter.receiverList.uid, filter.packageName) != 0) {
            Slog.w("BroadcastQueue", "Appop Denial: receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") requires appop " + AppOpsManager.opToName(r.appOp) + " due to sender " + r.callerPackage + " (uid " + r.callingUid + ")");
            skip = true;
        }
        if (!this.mService.mIntentFirewall.checkBroadcast(r.intent, r.callingUid, r.callingPid, r.resolvedType, filter.receiverList.uid)) {
            skip = true;
        }
        if (!skip && (filter.receiverList.app == null || filter.receiverList.app.killed || filter.receiverList.app.crashing)) {
            Slog.w("BroadcastQueue", "Skipping deliver [" + this.mQueueName + "] " + r + " to " + filter.receiverList + ": process gone or crashing");
            skip = true;
        }
        boolean perm4 = (r.intent.getFlags() & DumpState.DUMP_COMPILER_STATS) != 0;
        boolean visibleToInstantApps = perm4;
        if (!skip && !visibleToInstantApps && filter.instantApp && filter.receiverList.uid != r.callingUid) {
            Slog.w("BroadcastQueue", "Instant App Denial: receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") due to sender " + r.callerPackage + " (uid " + r.callingUid + ") not specifying FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS");
            skip = true;
        }
        if (!skip && !filter.visibleToInstantApp && r.callerInstantApp && filter.receiverList.uid != r.callingUid) {
            Slog.w("BroadcastQueue", "Instant App Denial: receiving " + r.intent.toString() + " to " + filter.receiverList.app + " (pid=" + filter.receiverList.pid + ", uid=" + filter.receiverList.uid + ") requires receiver be visible to instant apps due to sender " + r.callerPackage + " (uid " + r.callingUid + ")");
            skip = true;
        }
        if (skip) {
            r.delivery[index] = 2;
        } else if (this.mService.mPermissionReviewRequired && !requestStartTargetPermissionsReviewIfNeededLocked(r, filter.packageName, filter.owningUserId)) {
            r.delivery[index] = 2;
        } else {
            r.delivery[index] = 1;
            if (ordered) {
                r.receiver = filter.receiverList.receiver.asBinder();
                r.curFilter = filter;
                filter.receiverList.curBroadcast = r;
                r.state = 2;
                if (filter.receiverList.app != null) {
                    r.curApp = filter.receiverList.app;
                    if (filter.receiverList.app.curReceivers == null) {
                        Slog.e("BroadcastQueue", "Failure sending broadcast " + r.intent + " for " + filter.receiverList.app);
                        return;
                    }
                    filter.receiverList.app.curReceivers.add(r);
                    this.mService.updateOomAdjLocked(r.curApp, true);
                }
            }
            try {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT) {
                    Slog.i("BroadcastQueue", "Delivering to " + filter + " : " + r);
                }
                if (filter.receiverList.app != null && filter.receiverList.app.inFullBackup) {
                    if (ordered) {
                        skipReceiverLocked(r);
                    }
                } else {
                    performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver, new Intent(r.intent), r.resultCode, r.resultData, r.resultExtras, r.ordered, r.initialSticky, r.userId);
                }
                if (ordered) {
                    r.state = 3;
                }
            } catch (RemoteException e) {
                Slog.w("BroadcastQueue", "Failure sending broadcast " + r.intent, e);
                if (ordered) {
                    r.receiver = null;
                    r.curFilter = null;
                    filter.receiverList.curBroadcast = null;
                    if (filter.receiverList.app != null) {
                        filter.receiverList.app.curReceivers.remove(r);
                    }
                }
            }
        }
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(BroadcastRecord receiverRecord, String receivingPackageName, final int receivingUserId) {
        if (this.mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(receivingPackageName, receivingUserId)) {
            boolean callerForeground = receiverRecord.callerApp == null || receiverRecord.callerApp.setSchedGroup != 0;
            if (!callerForeground || receiverRecord.intent.getComponent() == null) {
                Slog.w("BroadcastQueue", "u" + receivingUserId + " Receiving a broadcast in package" + receivingPackageName + " requires a permissions review");
            } else {
                IIntentSender target = this.mService.getIntentSenderLocked(1, receiverRecord.callerPackage, receiverRecord.callingUid, receiverRecord.userId, null, null, 0, new Intent[]{receiverRecord.intent}, new String[]{receiverRecord.intent.resolveType(this.mService.mContext.getContentResolver())}, 1409286144, null);
                final Intent intent = new Intent("android.intent.action.REVIEW_PERMISSIONS");
                intent.addFlags(276824064);
                intent.putExtra("android.intent.extra.PACKAGE_NAME", receivingPackageName);
                intent.putExtra("android.intent.extra.INTENT", new IntentSender(target));
                if (ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW) {
                    Slog.i("BroadcastQueue", "u" + receivingUserId + " Launching permission review for package " + receivingPackageName);
                }
                this.mHandler.post(new Runnable() { // from class: com.android.server.am.BroadcastQueue.1
                    @Override // java.lang.Runnable
                    public void run() {
                        BroadcastQueue.this.mService.mContext.startActivityAsUser(intent, new UserHandle(receivingUserId));
                    }
                });
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
                if ((pi.protectionLevel & 31) != 2) {
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
    /* JADX WARN: Removed duplicated region for block: B:114:0x035e  */
    /* JADX WARN: Removed duplicated region for block: B:117:0x036c  */
    /* JADX WARN: Removed duplicated region for block: B:120:0x038d  */
    /* JADX WARN: Removed duplicated region for block: B:301:0x0b0e  */
    /* JADX WARN: Removed duplicated region for block: B:306:0x0b4e  */
    /* JADX WARN: Removed duplicated region for block: B:348:0x0ca6  */
    /* JADX WARN: Removed duplicated region for block: B:349:0x0cd1  */
    /* JADX WARN: Removed duplicated region for block: B:352:0x0cf2  */
    /* JADX WARN: Removed duplicated region for block: B:353:0x0cf5  */
    /* JADX WARN: Removed duplicated region for block: B:356:0x0d0b  */
    /* JADX WARN: Removed duplicated region for block: B:358:0x0d60  */
    /* JADX WARN: Removed duplicated region for block: B:360:0x0d69 A[LOOP:2: B:60:0x01ce->B:360:0x0d69, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:393:0x03c1 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void processNextBroadcastLocked(boolean r38, boolean r39) {
        /*
            Method dump skipped, instructions count: 3440
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.BroadcastQueue.processNextBroadcastLocked(boolean, boolean):void");
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
        boolean z = false;
        if (fromMsg) {
            this.mPendingBroadcastTimeoutMessage = false;
        }
        if (this.mOrderedBroadcasts.size() == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        BroadcastRecord r = this.mOrderedBroadcasts.get(0);
        if (fromMsg) {
            if (!this.mService.mProcessesReady) {
                return;
            }
            long timeoutTime = r.receiverTime + this.mTimeoutPeriod;
            if (timeoutTime > now) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v("BroadcastQueue", "Premature timeout [" + this.mQueueName + "] @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for " + timeoutTime);
                }
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
        }
        BroadcastRecord br = this.mOrderedBroadcasts.get(0);
        if (br.state == 4) {
            StringBuilder sb = new StringBuilder();
            sb.append("Waited long enough for: ");
            sb.append(br.curComponent != null ? br.curComponent.flattenToShortString() : "(null)");
            Slog.i("BroadcastQueue", sb.toString());
            br.curComponent = null;
            br.state = 0;
            processNextBroadcast(false);
            return;
        }
        if (r.curApp != null && r.curApp.debugging) {
            z = true;
        }
        boolean debugging = z;
        Slog.w("BroadcastQueue", "Timeout of broadcast " + r + " - receiver=" + r.receiver + ", started " + (now - r.receiverTime) + "ms ago");
        r.receiverTime = now;
        if (!debugging) {
            r.anrCount++;
        }
        ProcessRecord app = null;
        String anrMessage = null;
        if (r.nextReceiver > 0) {
            curReceiver = r.receivers.get(r.nextReceiver - 1);
            r.delivery[r.nextReceiver - 1] = 3;
        } else {
            curReceiver = r.curReceiver;
        }
        Object curReceiver2 = curReceiver;
        Slog.w("BroadcastQueue", "Receiver during timeout of " + r + " : " + curReceiver2);
        logBroadcastReceiverDiscardLocked(r);
        if (curReceiver2 != null && (curReceiver2 instanceof BroadcastFilter)) {
            BroadcastFilter bf = (BroadcastFilter) curReceiver2;
            if (bf.receiverList.pid != 0 && bf.receiverList.pid != ActivityManagerService.MY_PID) {
                synchronized (this.mService.mPidsSelfLocked) {
                    app = this.mService.mPidsSelfLocked.get(bf.receiverList.pid);
                }
            }
        } else {
            app = r.curApp;
        }
        ProcessRecord app2 = app;
        if (app2 != null) {
            anrMessage = "Broadcast of " + r.intent.toString();
        }
        String anrMessage2 = anrMessage;
        if (this.mPendingBroadcast == r) {
            this.mPendingBroadcast = null;
        }
        finishReceiverLocked(r, r.resultCode, r.resultData, r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();
        if (!debugging && anrMessage2 != null) {
            this.mHandler.post(new AppNotResponding(app2, anrMessage2));
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
        this.mBroadcastHistory[this.mHistoryNext] = historyRecord;
        this.mHistoryNext = ringAdvance(this.mHistoryNext, 1, MAX_BROADCAST_HISTORY);
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
        for (int i2 = this.mOrderedBroadcasts.size() - 1; i2 >= 0; i2--) {
            didSomething |= this.mOrderedBroadcasts.get(i2).cleanupDisabledPackageReceiversLocked(packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething;
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
        objArr[1] = record.callerPackage == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : record.callerPackage;
        objArr[2] = record.callerApp == null ? "process unknown" : record.callerApp.toShortString();
        objArr[3] = record.intent == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : record.intent.getAction();
        return String.format("Broadcast %s from %s (%s) %s", objArr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isIdle() {
        return this.mParallelBroadcasts.isEmpty() && this.mOrderedBroadcasts.isEmpty() && this.mPendingBroadcast == null;
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
        int N2 = this.mOrderedBroadcasts.size();
        for (int i3 = N2 - 1; i3 >= 0; i3--) {
            this.mOrderedBroadcasts.get(i3).writeToProto(proto, 2246267895811L);
        }
        if (this.mPendingBroadcast != null) {
            this.mPendingBroadcast.writeToProto(proto, 1146756268036L);
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
        int i4 = this.mSummaryHistoryNext;
        int ringIndex2 = i4;
        int lastIndex3 = i4;
        while (true) {
            int ringIndex3 = ringAdvance(ringIndex2, i, MAX_BROADCAST_SUMMARY_HISTORY);
            Intent intent = this.mBroadcastSummaryHistory[ringIndex3];
            if (intent != null) {
                long summaryToken = proto.start(2246267895814L);
                lastIndex = lastIndex3;
                intent.writeToProto(proto, 1146756268033L, false, true, true, false);
                proto.write(1112396529666L, this.mSummaryHistoryEnqueueTime[ringIndex3]);
                proto.write(1112396529667L, this.mSummaryHistoryDispatchTime[ringIndex3]);
                proto.write(1112396529668L, this.mSummaryHistoryFinishTime[ringIndex3]);
                proto.end(summaryToken);
            } else {
                lastIndex = lastIndex3;
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
        int ringIndex;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        boolean z = true;
        if (this.mParallelBroadcasts.size() > 0 || this.mOrderedBroadcasts.size() > 0 || this.mPendingBroadcast != null) {
            boolean printed = false;
            boolean needSep3 = needSep;
            for (int i = this.mParallelBroadcasts.size() - 1; i >= 0; i--) {
                BroadcastRecord br = this.mParallelBroadcasts.get(i);
                if (dumpPackage == null || dumpPackage.equals(br.callerPackage)) {
                    if (!printed) {
                        if (needSep3) {
                            pw.println();
                        }
                        needSep3 = true;
                        printed = true;
                        pw.println("  Active broadcasts [" + this.mQueueName + "]:");
                    }
                    pw.println("  Active Broadcast " + this.mQueueName + " #" + i + ":");
                    br.dump(pw, "    ", sdf);
                }
            }
            boolean printed2 = false;
            needSep2 = true;
            for (int i2 = this.mOrderedBroadcasts.size() - 1; i2 >= 0; i2--) {
                BroadcastRecord br2 = this.mOrderedBroadcasts.get(i2);
                if (dumpPackage == null || dumpPackage.equals(br2.callerPackage)) {
                    if (!printed2) {
                        if (needSep2) {
                            pw.println();
                        }
                        needSep2 = true;
                        printed2 = true;
                        pw.println("  Active ordered broadcasts [" + this.mQueueName + "]:");
                    }
                    pw.println("  Active Ordered Broadcast " + this.mQueueName + " #" + i2 + ":");
                    this.mOrderedBroadcasts.get(i2).dump(pw, "    ", sdf);
                }
            }
            if (dumpPackage == null || (this.mPendingBroadcast != null && dumpPackage.equals(this.mPendingBroadcast.callerPackage))) {
                if (needSep2) {
                    pw.println();
                }
                pw.println("  Pending broadcast [" + this.mQueueName + "]:");
                if (this.mPendingBroadcast != null) {
                    this.mPendingBroadcast.dump(pw, "    ", sdf);
                } else {
                    pw.println("    (null)");
                }
                needSep2 = true;
            }
        } else {
            needSep2 = needSep;
        }
        int i3 = -1;
        int lastIndex = this.mHistoryNext;
        boolean needSep4 = needSep2;
        boolean printed3 = false;
        int ringIndex2 = lastIndex;
        do {
            ringIndex2 = ringAdvance(ringIndex2, -1, MAX_BROADCAST_HISTORY);
            BroadcastRecord r = this.mBroadcastHistory[ringIndex2];
            if (r != null) {
                i3++;
                if (dumpPackage == null || dumpPackage.equals(r.callerPackage)) {
                    if (!printed3) {
                        if (needSep4) {
                            pw.println();
                        }
                        needSep4 = true;
                        pw.println("  Historical broadcasts [" + this.mQueueName + "]:");
                        printed3 = true;
                    }
                    if (dumpAll) {
                        pw.print("  Historical Broadcast " + this.mQueueName + " #");
                        pw.print(i3);
                        pw.println(":");
                        r.dump(pw, "    ", sdf);
                        continue;
                    } else {
                        pw.print("  #");
                        pw.print(i3);
                        pw.print(": ");
                        pw.println(r);
                        pw.print("    ");
                        pw.println(r.intent.toShortString(false, true, true, false));
                        if (r.targetComp != null && r.targetComp != r.intent.getComponent()) {
                            pw.print("    targetComp: ");
                            pw.println(r.targetComp.toShortString());
                        }
                        Bundle bundle = r.intent.getExtras();
                        if (bundle != null) {
                            pw.print("    extras: ");
                            pw.println(bundle.toString());
                            continue;
                        } else {
                            continue;
                        }
                    }
                }
            }
        } while (ringIndex2 != lastIndex);
        if (dumpPackage == null) {
            int lastIndex2 = this.mSummaryHistoryNext;
            if (dumpAll) {
                printed3 = false;
                i3 = -1;
                ringIndex = lastIndex2;
            } else {
                ringIndex = lastIndex2;
                int ringIndex3 = i3;
                while (ringIndex3 > 0 && ringIndex != lastIndex2) {
                    ringIndex = ringAdvance(ringIndex, -1, MAX_BROADCAST_SUMMARY_HISTORY);
                    if (this.mBroadcastHistory[ringIndex] != null) {
                        ringIndex3--;
                    }
                }
            }
            while (true) {
                int j = MAX_BROADCAST_SUMMARY_HISTORY;
                ringIndex = ringAdvance(ringIndex, -1, j);
                Intent intent = this.mBroadcastSummaryHistory[ringIndex];
                if (intent != null) {
                    if (!printed3) {
                        if (needSep4) {
                            pw.println();
                        }
                        needSep4 = true;
                        pw.println("  Historical broadcasts summary [" + this.mQueueName + "]:");
                        printed3 = true;
                    }
                    if (!dumpAll && i3 >= 50) {
                        pw.println("  ...");
                        break;
                    }
                    i3++;
                    pw.print("  #");
                    pw.print(i3);
                    pw.print(": ");
                    pw.println(intent.toShortString(false, z, z, false));
                    pw.print("    ");
                    TimeUtils.formatDuration(this.mSummaryHistoryDispatchTime[ringIndex] - this.mSummaryHistoryEnqueueTime[ringIndex], pw);
                    pw.print(" dispatch ");
                    TimeUtils.formatDuration(this.mSummaryHistoryFinishTime[ringIndex] - this.mSummaryHistoryDispatchTime[ringIndex], pw);
                    pw.println(" finish");
                    pw.print("    enq=");
                    pw.print(sdf.format(new Date(this.mSummaryHistoryEnqueueTime[ringIndex])));
                    pw.print(" disp=");
                    pw.print(sdf.format(new Date(this.mSummaryHistoryDispatchTime[ringIndex])));
                    pw.print(" fin=");
                    pw.println(sdf.format(new Date(this.mSummaryHistoryFinishTime[ringIndex])));
                    Bundle bundle2 = intent.getExtras();
                    if (bundle2 != null) {
                        pw.print("    extras: ");
                        pw.println(bundle2.toString());
                    }
                }
                if (ringIndex == lastIndex2) {
                    break;
                }
                z = true;
            }
        }
        return needSep4;
    }
}
