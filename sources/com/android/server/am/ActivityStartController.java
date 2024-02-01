package com.android.server.am;

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
import android.view.RemoteAnimationAdapter;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.ActivityStackSupervisor;
import com.android.server.am.ActivityStarter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public class ActivityStartController {
    private static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 1;
    private static final String TAG = "ActivityManager";
    private final ActivityStarter.Factory mFactory;
    private final Handler mHandler;
    private ActivityRecord mLastHomeActivityStartRecord;
    private int mLastHomeActivityStartResult;
    private ActivityStarter mLastStarter;
    private final ArrayList<ActivityStackSupervisor.PendingActivityLaunch> mPendingActivityLaunches;
    private final PendingRemoteAnimationRegistry mPendingRemoteAnimationRegistry;
    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private ActivityRecord[] tmpOutRecord;

    /* loaded from: classes.dex */
    private final class StartHandler extends Handler {
        public StartHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                synchronized (ActivityStartController.this.mService) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        ActivityStartController.this.doPendingActivityLaunches(true);
                    } catch (Throwable th) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStartController(ActivityManagerService service) {
        this(service, service.mStackSupervisor, new ActivityStarter.DefaultFactory(service, service.mStackSupervisor, new ActivityStartInterceptor(service, service.mStackSupervisor)));
    }

    @VisibleForTesting
    ActivityStartController(ActivityManagerService service, ActivityStackSupervisor supervisor, ActivityStarter.Factory factory) {
        this.tmpOutRecord = new ActivityRecord[1];
        this.mPendingActivityLaunches = new ArrayList<>();
        this.mService = service;
        this.mSupervisor = supervisor;
        this.mHandler = new StartHandler(this.mService.mHandlerThread.getLooper());
        this.mFactory = factory;
        this.mFactory.setController(this);
        this.mPendingRemoteAnimationRegistry = new PendingRemoteAnimationRegistry(service, service.mHandler);
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
        if (this.mLastStarter == null) {
            return;
        }
        this.mLastStarter.postStartActivityProcessing(r, result, targetStack);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason) {
        this.mSupervisor.moveHomeStackTaskToTop(reason);
        this.mLastHomeActivityStartResult = obtainStarter(intent, "startHomeActivity: " + reason).setOutActivity(this.tmpOutRecord).setCallingUid(0).setActivityInfo(aInfo).execute();
        this.mLastHomeActivityStartRecord = this.tmpOutRecord[0];
        if (this.mSupervisor.inResumeTopActivity) {
            this.mSupervisor.scheduleResumeTopActivities();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSetupActivity() {
        String vers;
        if (this.mService.getCheckedForSetup()) {
            return;
        }
        ContentResolver resolver = this.mService.mContext.getContentResolver();
        if (this.mService.mFactoryTest != 1 && Settings.Global.getInt(resolver, "device_provisioned", 0) != 0) {
            this.mService.setCheckedForSetup(true);
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
            return this.mService.mUserController.handleIncomingUser(realCallingPid, realCallingUid, targetUserId, false, 2, reason, null);
        }
        this.mService.mUserController.ensureNotSpecialUser(targetUserId);
        return targetUserId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivityInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, SafeActivityOptions options, int userId, TaskRecord inTask, String reason, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent) {
        return obtainStarter(intent, reason).setCallingUid(uid).setRealCallingPid(realCallingPid).setRealCallingUid(realCallingUid).setCallingPackage(callingPackage).setResolvedType(resolvedType).setResultTo(resultTo).setResultWho(resultWho).setRequestCode(requestCode).setStartFlags(startFlags).setActivityOptions(options).setMayWait(checkTargetUser(userId, validateIncomingUser, realCallingPid, realCallingUid, reason)).setInTask(inTask).setOriginatingPendingIntent(originatingPendingIntent).execute();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivitiesInPackage(int uid, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent) {
        return startActivitiesInPackage(uid, 0, -10000, callingPackage, intents, resolvedTypes, resultTo, options, userId, validateIncomingUser, originatingPendingIntent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent) {
        return startActivities(null, uid, realCallingPid, realCallingUid, callingPackage, intents, resolvedTypes, resultTo, options, checkTargetUser(userId, validateIncomingUser, Binder.getCallingPid(), Binder.getCallingUid(), "startActivityInPackage"), "startActivityInPackage", originatingPendingIntent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x006a, code lost:
        throw new java.lang.IllegalArgumentException("File descriptors passed in Intent");
     */
    /* JADX WARN: Code restructure failed: missing block: B:52:0x00b7, code lost:
        throw new java.lang.IllegalArgumentException("FLAG_CANT_SAVE_STATE not supported here");
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r2v0 */
    /* JADX WARN: Type inference failed for: r2v1 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int startActivities(android.app.IApplicationThread r35, int r36, int r37, int r38, java.lang.String r39, android.content.Intent[] r40, java.lang.String[] r41, android.os.IBinder r42, com.android.server.am.SafeActivityOptions r43, int r44, java.lang.String r45, com.android.server.am.PendingIntentRecord r46) {
        /*
            Method dump skipped, instructions count: 441
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStartController.startActivities(android.app.IApplicationThread, int, int, int, java.lang.String, android.content.Intent[], java.lang.String[], android.os.IBinder, com.android.server.am.SafeActivityOptions, int, java.lang.String, com.android.server.am.PendingIntentRecord):int");
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
                starter.startResolvedActivity(pal.r, pal.sourceRecord, null, null, pal.startFlags, resume, null, null, null);
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
        pw.print(prefix);
        pw.print("mLastHomeActivityStartResult=");
        pw.println(this.mLastHomeActivityStartResult);
        if (this.mLastHomeActivityStartRecord != null) {
            pw.print(prefix);
            pw.println("mLastHomeActivityStartRecord:");
            this.mLastHomeActivityStartRecord.dump(pw, prefix + "  ");
        }
        boolean dump = false;
        boolean dumpPackagePresent = dumpPackage != null;
        if (this.mLastStarter != null) {
            if (!dumpPackagePresent || this.mLastStarter.relatedToPackage(dumpPackage) || (this.mLastHomeActivityStartRecord != null && dumpPackage.equals(this.mLastHomeActivityStartRecord.packageName))) {
                dump = true;
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
}
