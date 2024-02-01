package com.android.server.wm;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationAdapter;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.ActivityStackSupervisor;
import com.android.server.wm.ActivityStarter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes2.dex */
public class ActivityStartController {
    private static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 1;
    private static final String TAG = "ActivityTaskManager";
    boolean mCheckedForSetup;
    private final ActivityStarter.Factory mFactory;
    private final Handler mHandler;
    private ActivityRecord mLastHomeActivityStartRecord;
    private int mLastHomeActivityStartResult;
    private ActivityStarter mLastStarter;
    private final ArrayList<ActivityStackSupervisor.PendingActivityLaunch> mPendingActivityLaunches;
    private final PendingRemoteAnimationRegistry mPendingRemoteAnimationRegistry;
    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private ActivityRecord[] tmpOutRecord;

    /* loaded from: classes2.dex */
    private final class StartHandler extends Handler {
        public StartHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                synchronized (ActivityStartController.this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        ActivityStartController.this.doPendingActivityLaunches(true);
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStartController(ActivityTaskManagerService service) {
        this(service, service.mStackSupervisor, new ActivityStarter.DefaultFactory(service, service.mStackSupervisor, new ActivityStartInterceptor(service, service.mStackSupervisor)));
    }

    @VisibleForTesting
    ActivityStartController(ActivityTaskManagerService service, ActivityStackSupervisor supervisor, ActivityStarter.Factory factory) {
        this.tmpOutRecord = new ActivityRecord[1];
        this.mPendingActivityLaunches = new ArrayList<>();
        this.mCheckedForSetup = false;
        this.mService = service;
        this.mSupervisor = supervisor;
        this.mHandler = new StartHandler(this.mService.mH.getLooper());
        this.mFactory = factory;
        this.mFactory.setController(this);
        this.mPendingRemoteAnimationRegistry = new PendingRemoteAnimationRegistry(service, service.mH);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter obtainStarter(Intent intent, String reason) {
        return this.mFactory.obtain().setIntent(intent).setReason(reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onExecutionComplete(ActivityStarter starter) {
        if (this.mLastStarter == null) {
            this.mLastStarter = this.mFactory.obtain();
        }
        this.mLastStarter.set(starter);
        this.mFactory.recycle(starter);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postStartActivityProcessingForLastStarter(ActivityRecord r, int result, ActivityStack targetStack) {
        ActivityStarter activityStarter = this.mLastStarter;
        if (activityStarter == null) {
            return;
        }
        activityStarter.postStartActivityProcessing(r, result, targetStack);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason, int displayId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(1);
        if (!ActivityRecord.isResolverActivity(aInfo.name)) {
            options.setLaunchActivityType(2);
        }
        options.setLaunchDisplayId(displayId);
        this.mLastHomeActivityStartResult = obtainStarter(intent, "startHomeActivity: " + reason).setOutActivity(this.tmpOutRecord).setCallingUid(0).setActivityInfo(aInfo).setActivityOptions(options.toBundle()).execute();
        this.mLastHomeActivityStartRecord = this.tmpOutRecord[0];
        ActivityDisplay display = this.mService.mRootActivityContainer.getActivityDisplay(displayId);
        ActivityStack homeStack = display != null ? display.getHomeStack() : null;
        if (homeStack != null && homeStack.mInResumeTopActivity) {
            this.mSupervisor.scheduleResumeTopActivities();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSetupActivity() {
        String vers;
        if (this.mCheckedForSetup) {
            return;
        }
        ContentResolver resolver = this.mService.mContext.getContentResolver();
        if (this.mService.mFactoryTest != 1 && Settings.Global.getInt(resolver, "device_provisioned", 0) != 0) {
            this.mCheckedForSetup = true;
            Intent intent = new Intent("android.intent.action.UPGRADE_SETUP");
            List<ResolveInfo> ris = this.mService.mContext.getPackageManager().queryIntentActivities(intent, 1049728);
            if (!ris.isEmpty()) {
                ResolveInfo ri = ris.get(0);
                if (ri.activityInfo.metaData != null) {
                    vers = ri.activityInfo.metaData.getString("android.SETUP_VERSION");
                } else {
                    vers = null;
                }
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString("android.SETUP_VERSION");
                }
                String lastVers = Settings.Secure.getString(resolver, "last_setup_shown");
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(268435456);
                    intent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    obtainStarter(intent, "startSetupActivity").setCallingUid(0).setActivityInfo(ri.activityInfo).execute();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int checkTargetUser(int targetUserId, boolean validateIncomingUser, int realCallingPid, int realCallingUid, String reason) {
        if (validateIncomingUser) {
            return this.mService.handleIncomingUser(realCallingPid, realCallingUid, targetUserId, reason);
        }
        this.mService.mAmInternal.ensureNotSpecialUser(targetUserId);
        return targetUserId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivityInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, SafeActivityOptions options, int userId, TaskRecord inTask, String reason, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
        return obtainStarter(intent, reason).setCallingUid(uid).setRealCallingPid(realCallingPid).setRealCallingUid(realCallingUid).setCallingPackage(callingPackage).setResolvedType(resolvedType).setResultTo(resultTo).setResultWho(resultWho).setRequestCode(requestCode).setStartFlags(startFlags).setActivityOptions(options).setMayWait(checkTargetUser(userId, validateIncomingUser, realCallingPid, realCallingUid, reason)).setInTask(inTask).setOriginatingPendingIntent(originatingPendingIntent).setAllowBackgroundActivityStart(allowBackgroundActivityStart).execute();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivitiesInPackage(int uid, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
        return startActivitiesInPackage(uid, 0, -1, callingPackage, intents, resolvedTypes, resultTo, options, userId, validateIncomingUser, originatingPendingIntent, allowBackgroundActivityStart);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
        return startActivities(null, uid, realCallingPid, realCallingUid, callingPackage, intents, resolvedTypes, resultTo, options, checkTargetUser(userId, validateIncomingUser, Binder.getCallingPid(), Binder.getCallingUid(), "startActivityInPackage"), "startActivityInPackage", originatingPendingIntent, allowBackgroundActivityStart);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:100:0x01ab, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:102:0x01ad, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x0091, code lost:
        throw new java.lang.IllegalArgumentException("FLAG_CANT_SAVE_STATE not supported here");
     */
    /* JADX WARN: Code restructure failed: missing block: B:71:0x0143, code lost:
        r19 = r10;
        r0 = new com.android.server.wm.ActivityRecord[1];
        r13 = r1.mService.mGlobalLock;
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x0157, code lost:
        monitor-enter(r13);
     */
    /* JADX WARN: Code restructure failed: missing block: B:73:0x0158, code lost:
        com.android.server.wm.WindowManagerService.boostPriorityForLockedSection();
     */
    /* JADX WARN: Code restructure failed: missing block: B:74:0x015b, code lost:
        r14 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x015e, code lost:
        if (r14 >= r12.length) goto L111;
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x0160, code lost:
        r0 = r12[r14].setOutActivity(r0).execute();
     */
    /* JADX WARN: Code restructure failed: missing block: B:78:0x016a, code lost:
        if (r0 >= 0) goto L94;
     */
    /* JADX WARN: Code restructure failed: missing block: B:79:0x016c, code lost:
        r15 = r14 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:81:0x016f, code lost:
        if (r15 >= r12.length) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:82:0x0171, code lost:
        r1.mFactory.recycle(r12[r15]);
        r15 = r15 + 1;
        r1 = r26;
     */
    /* JADX WARN: Code restructure failed: missing block: B:83:0x017f, code lost:
        monitor-exit(r13);
     */
    /* JADX WARN: Code restructure failed: missing block: B:84:0x0180, code lost:
        com.android.server.wm.WindowManagerService.resetPriorityAfterLockedSection();
        android.os.Binder.restoreCallingIdentity(r19);
     */
    /* JADX WARN: Code restructure failed: missing block: B:85:0x0186, code lost:
        return r0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:88:0x018a, code lost:
        if (r0[0] == null) goto L100;
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x018c, code lost:
        r1 = r0[0].appToken;
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x0191, code lost:
        r1 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:91:0x0193, code lost:
        r14 = r14 + 1;
        r1 = r26;
     */
    /* JADX WARN: Code restructure failed: missing block: B:92:0x019b, code lost:
        monitor-exit(r13);
     */
    /* JADX WARN: Code restructure failed: missing block: B:93:0x019c, code lost:
        com.android.server.wm.WindowManagerService.resetPriorityAfterLockedSection();
     */
    /* JADX WARN: Code restructure failed: missing block: B:94:0x019f, code lost:
        android.os.Binder.restoreCallingIdentity(r19);
     */
    /* JADX WARN: Code restructure failed: missing block: B:95:0x01a4, code lost:
        return 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x01a5, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x01a6, code lost:
        monitor-exit(r13);
     */
    /* JADX WARN: Code restructure failed: missing block: B:98:0x01a7, code lost:
        com.android.server.wm.WindowManagerService.resetPriorityAfterLockedSection();
     */
    /* JADX WARN: Code restructure failed: missing block: B:99:0x01aa, code lost:
        throw r0;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int startActivities(android.app.IApplicationThread r27, int r28, int r29, int r30, java.lang.String r31, android.content.Intent[] r32, java.lang.String[] r33, android.os.IBinder r34, com.android.server.wm.SafeActivityOptions r35, int r36, java.lang.String r37, com.android.server.am.PendingIntentRecord r38, boolean r39) {
        /*
            Method dump skipped, instructions count: 502
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStartController.startActivities(android.app.IApplicationThread, int, int, int, java.lang.String, android.content.Intent[], java.lang.String[], android.os.IBinder, com.android.server.wm.SafeActivityOptions, int, java.lang.String, com.android.server.am.PendingIntentRecord, boolean):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Intent[] lambda$startActivities$0(int x$0) {
        return new Intent[x$0];
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void schedulePendingActivityLaunches(long delayMs) {
        this.mHandler.removeMessages(1);
        Message msg = this.mHandler.obtainMessage(1);
        this.mHandler.sendMessageDelayed(msg, delayMs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void doPendingActivityLaunches(boolean doResume) {
        while (!this.mPendingActivityLaunches.isEmpty()) {
            boolean z = false;
            ActivityStackSupervisor.PendingActivityLaunch pal = this.mPendingActivityLaunches.remove(0);
            if (doResume && this.mPendingActivityLaunches.isEmpty()) {
                z = true;
            }
            boolean resume = z;
            ActivityStarter starter = obtainStarter(null, "pendingActivityLaunch");
            try {
                starter.startResolvedActivity(pal.r, pal.sourceRecord, null, null, pal.startFlags, resume, pal.r.pendingOptions, null);
            } catch (Exception e) {
                Slog.e(TAG, "Exception during pending activity launch pal=" + pal, e);
                pal.sendErrorResult(e.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addPendingActivityLaunch(ActivityStackSupervisor.PendingActivityLaunch launch) {
        this.mPendingActivityLaunches.add(launch);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearPendingActivityLaunches(String packageName) {
        int pendingLaunches = this.mPendingActivityLaunches.size();
        for (int palNdx = pendingLaunches - 1; palNdx >= 0; palNdx--) {
            ActivityStackSupervisor.PendingActivityLaunch pal = this.mPendingActivityLaunches.get(palNdx);
            ActivityRecord r = pal.r;
            if (r != null && r.packageName.equals(packageName)) {
                this.mPendingActivityLaunches.remove(palNdx);
            }
        }
        return this.mPendingActivityLaunches.size() < pendingLaunches;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRemoteAnimationForNextActivityStart(String packageName, RemoteAnimationAdapter adapter) {
        this.mPendingRemoteAnimationRegistry.addPendingAnimation(packageName, adapter);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PendingRemoteAnimationRegistry getPendingRemoteAnimationRegistry() {
        return this.mPendingRemoteAnimationRegistry;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix, String dumpPackage) {
        ActivityRecord activityRecord;
        pw.print(prefix);
        pw.print("mLastHomeActivityStartResult=");
        pw.println(this.mLastHomeActivityStartResult);
        if (this.mLastHomeActivityStartRecord != null) {
            pw.print(prefix);
            pw.println("mLastHomeActivityStartRecord:");
            this.mLastHomeActivityStartRecord.dump(pw, prefix + "  ");
        }
        boolean dump = true;
        boolean dumpPackagePresent = dumpPackage != null;
        ActivityStarter activityStarter = this.mLastStarter;
        if (activityStarter != null) {
            if (dumpPackagePresent && !activityStarter.relatedToPackage(dumpPackage) && ((activityRecord = this.mLastHomeActivityStartRecord) == null || !dumpPackage.equals(activityRecord.packageName))) {
                dump = false;
            }
            if (dump) {
                pw.print(prefix);
                this.mLastStarter.dump(pw, prefix + "  ");
                if (dumpPackagePresent) {
                    return;
                }
            }
        }
        if (dumpPackagePresent) {
            pw.print(prefix);
            pw.println("(nothing)");
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        Iterator<ActivityStackSupervisor.PendingActivityLaunch> it = this.mPendingActivityLaunches.iterator();
        while (it.hasNext()) {
            ActivityStackSupervisor.PendingActivityLaunch activity = it.next();
            activity.r.writeIdentifierToProto(proto, fieldId);
        }
    }
}
