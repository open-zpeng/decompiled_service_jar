package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Trace;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Pools;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStack;
import com.android.server.am.LaunchParamsController;
import com.android.server.pm.DumpState;
import com.android.server.pm.InstantAppResolver;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ActivityStarter {
    private static final int INVALID_LAUNCH_MODE = -1;
    private static final String TAG = "ActivityManager";
    private static final String TAG_CONFIGURATION = "ActivityManager";
    private static final String TAG_FOCUS = "ActivityManager";
    private static final String TAG_RESULTS = "ActivityManager";
    private static final String TAG_USER_LEAVING = "ActivityManager";
    private boolean mAddingToTask;
    private boolean mAvoidMoveToFront;
    private int mCallingUid;
    private final ActivityStartController mController;
    private boolean mDoResume;
    private TaskRecord mInTask;
    private Intent mIntent;
    private boolean mIntentDelivered;
    private final ActivityStartInterceptor mInterceptor;
    private boolean mKeepCurTransition;
    private int mLastStartActivityResult;
    private long mLastStartActivityTimeMs;
    private String mLastStartReason;
    private int mLaunchFlags;
    private int mLaunchMode;
    private boolean mLaunchTaskBehind;
    private boolean mMovedToFront;
    private ActivityInfo mNewTaskInfo;
    private Intent mNewTaskIntent;
    private boolean mNoAnimation;
    private ActivityRecord mNotTop;
    private ActivityOptions mOptions;
    private int mPreferredDisplayId;
    private TaskRecord mReuseTask;
    private final ActivityManagerService mService;
    private ActivityRecord mSourceRecord;
    private ActivityStack mSourceStack;
    private ActivityRecord mStartActivity;
    private int mStartFlags;
    private final ActivityStackSupervisor mSupervisor;
    private ActivityStack mTargetStack;
    private IVoiceInteractor mVoiceInteractor;
    private IVoiceInteractionSession mVoiceSession;
    private LaunchParamsController.LaunchParams mLaunchParams = new LaunchParamsController.LaunchParams();
    private final ActivityRecord[] mLastStartActivityRecord = new ActivityRecord[1];
    private Request mRequest = new Request();

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface Factory {
        ActivityStarter obtain();

        void recycle(ActivityStarter activityStarter);

        void setController(ActivityStartController activityStartController);
    }

    /* loaded from: classes.dex */
    static class DefaultFactory implements Factory {
        private ActivityStartController mController;
        private ActivityStartInterceptor mInterceptor;
        private ActivityManagerService mService;
        private ActivityStackSupervisor mSupervisor;
        private final int MAX_STARTER_COUNT = 3;
        private Pools.SynchronizedPool<ActivityStarter> mStarterPool = new Pools.SynchronizedPool<>(3);

        /* JADX INFO: Access modifiers changed from: package-private */
        public DefaultFactory(ActivityManagerService service, ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
            this.mService = service;
            this.mSupervisor = supervisor;
            this.mInterceptor = interceptor;
        }

        @Override // com.android.server.am.ActivityStarter.Factory
        public void setController(ActivityStartController controller) {
            this.mController = controller;
        }

        @Override // com.android.server.am.ActivityStarter.Factory
        public ActivityStarter obtain() {
            ActivityStarter starter = (ActivityStarter) this.mStarterPool.acquire();
            if (starter == null) {
                return new ActivityStarter(this.mController, this.mService, this.mSupervisor, this.mInterceptor);
            }
            return starter;
        }

        @Override // com.android.server.am.ActivityStarter.Factory
        public void recycle(ActivityStarter starter) {
            starter.reset(true);
            this.mStarterPool.release(starter);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Request {
        private static final int DEFAULT_CALLING_PID = 0;
        private static final int DEFAULT_CALLING_UID = -1;
        static final int DEFAULT_REAL_CALLING_PID = 0;
        static final int DEFAULT_REAL_CALLING_UID = -10000;
        ActivityInfo activityInfo;
        SafeActivityOptions activityOptions;
        boolean allowPendingRemoteAnimationRegistryLookup;
        boolean avoidMoveToFront;
        IApplicationThread caller;
        String callingPackage;
        boolean componentSpecified;
        Intent ephemeralIntent;
        int filterCallingUid;
        Configuration globalConfig;
        boolean ignoreTargetSecurity;
        TaskRecord inTask;
        Intent intent;
        boolean mayWait;
        PendingIntentRecord originatingPendingIntent;
        ActivityRecord[] outActivity;
        ProfilerInfo profilerInfo;
        String reason;
        int requestCode;
        ResolveInfo resolveInfo;
        String resolvedType;
        IBinder resultTo;
        String resultWho;
        int startFlags;
        int userId;
        IVoiceInteractor voiceInteractor;
        IVoiceInteractionSession voiceSession;
        WaitResult waitResult;
        int callingPid = 0;
        int callingUid = -1;
        int realCallingPid = 0;
        int realCallingUid = DEFAULT_REAL_CALLING_UID;

        Request() {
            reset();
        }

        void reset() {
            this.caller = null;
            this.intent = null;
            this.ephemeralIntent = null;
            this.resolvedType = null;
            this.activityInfo = null;
            this.resolveInfo = null;
            this.voiceSession = null;
            this.voiceInteractor = null;
            this.resultTo = null;
            this.resultWho = null;
            this.requestCode = 0;
            this.callingPid = 0;
            this.callingUid = -1;
            this.callingPackage = null;
            this.realCallingPid = 0;
            this.realCallingUid = DEFAULT_REAL_CALLING_UID;
            this.startFlags = 0;
            this.activityOptions = null;
            this.ignoreTargetSecurity = false;
            this.componentSpecified = false;
            this.outActivity = null;
            this.inTask = null;
            this.reason = null;
            this.profilerInfo = null;
            this.globalConfig = null;
            this.userId = 0;
            this.waitResult = null;
            this.mayWait = false;
            this.avoidMoveToFront = false;
            this.allowPendingRemoteAnimationRegistryLookup = true;
            this.filterCallingUid = DEFAULT_REAL_CALLING_UID;
            this.originatingPendingIntent = null;
        }

        void set(Request request) {
            this.caller = request.caller;
            this.intent = request.intent;
            this.ephemeralIntent = request.ephemeralIntent;
            this.resolvedType = request.resolvedType;
            this.activityInfo = request.activityInfo;
            this.resolveInfo = request.resolveInfo;
            this.voiceSession = request.voiceSession;
            this.voiceInteractor = request.voiceInteractor;
            this.resultTo = request.resultTo;
            this.resultWho = request.resultWho;
            this.requestCode = request.requestCode;
            this.callingPid = request.callingPid;
            this.callingUid = request.callingUid;
            this.callingPackage = request.callingPackage;
            this.realCallingPid = request.realCallingPid;
            this.realCallingUid = request.realCallingUid;
            this.startFlags = request.startFlags;
            this.activityOptions = request.activityOptions;
            this.ignoreTargetSecurity = request.ignoreTargetSecurity;
            this.componentSpecified = request.componentSpecified;
            this.outActivity = request.outActivity;
            this.inTask = request.inTask;
            this.reason = request.reason;
            this.profilerInfo = request.profilerInfo;
            this.globalConfig = request.globalConfig;
            this.userId = request.userId;
            this.waitResult = request.waitResult;
            this.mayWait = request.mayWait;
            this.avoidMoveToFront = request.avoidMoveToFront;
            this.allowPendingRemoteAnimationRegistryLookup = request.allowPendingRemoteAnimationRegistryLookup;
            this.filterCallingUid = request.filterCallingUid;
            this.originatingPendingIntent = request.originatingPendingIntent;
        }
    }

    ActivityStarter(ActivityStartController controller, ActivityManagerService service, ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
        this.mController = controller;
        this.mService = service;
        this.mSupervisor = supervisor;
        this.mInterceptor = interceptor;
        reset(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void set(ActivityStarter starter) {
        this.mStartActivity = starter.mStartActivity;
        this.mIntent = starter.mIntent;
        this.mCallingUid = starter.mCallingUid;
        this.mOptions = starter.mOptions;
        this.mLaunchTaskBehind = starter.mLaunchTaskBehind;
        this.mLaunchFlags = starter.mLaunchFlags;
        this.mLaunchMode = starter.mLaunchMode;
        this.mLaunchParams.set(starter.mLaunchParams);
        this.mNotTop = starter.mNotTop;
        this.mDoResume = starter.mDoResume;
        this.mStartFlags = starter.mStartFlags;
        this.mSourceRecord = starter.mSourceRecord;
        this.mPreferredDisplayId = starter.mPreferredDisplayId;
        this.mInTask = starter.mInTask;
        this.mAddingToTask = starter.mAddingToTask;
        this.mReuseTask = starter.mReuseTask;
        this.mNewTaskInfo = starter.mNewTaskInfo;
        this.mNewTaskIntent = starter.mNewTaskIntent;
        this.mSourceStack = starter.mSourceStack;
        this.mTargetStack = starter.mTargetStack;
        this.mMovedToFront = starter.mMovedToFront;
        this.mNoAnimation = starter.mNoAnimation;
        this.mKeepCurTransition = starter.mKeepCurTransition;
        this.mAvoidMoveToFront = starter.mAvoidMoveToFront;
        this.mVoiceSession = starter.mVoiceSession;
        this.mVoiceInteractor = starter.mVoiceInteractor;
        this.mIntentDelivered = starter.mIntentDelivered;
        this.mRequest.set(starter.mRequest);
    }

    ActivityRecord getStartActivity() {
        return this.mStartActivity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean relatedToPackage(String packageName) {
        return (this.mLastStartActivityRecord[0] != null && packageName.equals(this.mLastStartActivityRecord[0].packageName)) || (this.mStartActivity != null && packageName.equals(this.mStartActivity.packageName));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int execute() {
        try {
            if (this.mRequest.mayWait) {
                return startActivityMayWait(this.mRequest.caller, this.mRequest.callingUid, this.mRequest.callingPackage, this.mRequest.realCallingPid, this.mRequest.realCallingUid, this.mRequest.intent, this.mRequest.resolvedType, this.mRequest.voiceSession, this.mRequest.voiceInteractor, this.mRequest.resultTo, this.mRequest.resultWho, this.mRequest.requestCode, this.mRequest.startFlags, this.mRequest.profilerInfo, this.mRequest.waitResult, this.mRequest.globalConfig, this.mRequest.activityOptions, this.mRequest.ignoreTargetSecurity, this.mRequest.userId, this.mRequest.inTask, this.mRequest.reason, this.mRequest.allowPendingRemoteAnimationRegistryLookup, this.mRequest.originatingPendingIntent);
            }
            return startActivity(this.mRequest.caller, this.mRequest.intent, this.mRequest.ephemeralIntent, this.mRequest.resolvedType, this.mRequest.activityInfo, this.mRequest.resolveInfo, this.mRequest.voiceSession, this.mRequest.voiceInteractor, this.mRequest.resultTo, this.mRequest.resultWho, this.mRequest.requestCode, this.mRequest.callingPid, this.mRequest.callingUid, this.mRequest.callingPackage, this.mRequest.realCallingPid, this.mRequest.realCallingUid, this.mRequest.startFlags, this.mRequest.activityOptions, this.mRequest.ignoreTargetSecurity, this.mRequest.componentSpecified, this.mRequest.outActivity, this.mRequest.inTask, this.mRequest.reason, this.mRequest.allowPendingRemoteAnimationRegistryLookup, this.mRequest.originatingPendingIntent);
        } finally {
            onExecutionComplete();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int startResolvedActivity(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity) {
        try {
            return startActivity(r, sourceRecord, voiceSession, voiceInteractor, startFlags, doResume, options, inTask, outActivity);
        } finally {
            onExecutionComplete();
        }
    }

    private int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent, String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid, String callingPackage, int realCallingPid, int realCallingUid, int startFlags, SafeActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified, ActivityRecord[] outActivity, TaskRecord inTask, String reason, boolean allowPendingRemoteAnimationRegistryLookup, PendingIntentRecord originatingPendingIntent) {
        if (TextUtils.isEmpty(reason)) {
            throw new IllegalArgumentException("Need to specify a reason.");
        }
        this.mLastStartReason = reason;
        this.mLastStartActivityTimeMs = System.currentTimeMillis();
        this.mLastStartActivityRecord[0] = null;
        this.mLastStartActivityResult = startActivity(caller, intent, ephemeralIntent, resolvedType, aInfo, rInfo, voiceSession, voiceInteractor, resultTo, resultWho, requestCode, callingPid, callingUid, callingPackage, realCallingPid, realCallingUid, startFlags, options, ignoreTargetSecurity, componentSpecified, this.mLastStartActivityRecord, inTask, allowPendingRemoteAnimationRegistryLookup, originatingPendingIntent);
        if (outActivity != null) {
            outActivity[0] = this.mLastStartActivityRecord[0];
        }
        return getExternalResult(this.mLastStartActivityResult);
    }

    static int getExternalResult(int result) {
        if (result != 102) {
            return result;
        }
        return 0;
    }

    private void onExecutionComplete() {
        this.mController.onExecutionComplete(this);
    }

    /* JADX WARN: Removed duplicated region for block: B:100:0x01ff  */
    /* JADX WARN: Removed duplicated region for block: B:104:0x0214  */
    /* JADX WARN: Removed duplicated region for block: B:134:0x0304  */
    /* JADX WARN: Removed duplicated region for block: B:135:0x0328  */
    /* JADX WARN: Removed duplicated region for block: B:137:0x0334  */
    /* JADX WARN: Removed duplicated region for block: B:141:0x034d  */
    /* JADX WARN: Removed duplicated region for block: B:168:0x048d  */
    /* JADX WARN: Removed duplicated region for block: B:169:0x0490  */
    /* JADX WARN: Removed duplicated region for block: B:172:0x04b9  */
    /* JADX WARN: Removed duplicated region for block: B:180:0x04d1  */
    /* JADX WARN: Removed duplicated region for block: B:191:0x0522  */
    /* JADX WARN: Removed duplicated region for block: B:194:0x052f  */
    /* JADX WARN: Removed duplicated region for block: B:195:0x0536  */
    /* JADX WARN: Removed duplicated region for block: B:97:0x01f4  */
    /* JADX WARN: Removed duplicated region for block: B:98:0x01f7  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int startActivity(android.app.IApplicationThread r76, android.content.Intent r77, android.content.Intent r78, java.lang.String r79, android.content.pm.ActivityInfo r80, android.content.pm.ResolveInfo r81, android.service.voice.IVoiceInteractionSession r82, com.android.internal.app.IVoiceInteractor r83, android.os.IBinder r84, java.lang.String r85, int r86, int r87, int r88, java.lang.String r89, int r90, int r91, int r92, com.android.server.am.SafeActivityOptions r93, boolean r94, boolean r95, com.android.server.am.ActivityRecord[] r96, com.android.server.am.TaskRecord r97, boolean r98, com.android.server.am.PendingIntentRecord r99) {
        /*
            Method dump skipped, instructions count: 1380
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStarter.startActivity(android.app.IApplicationThread, android.content.Intent, android.content.Intent, java.lang.String, android.content.pm.ActivityInfo, android.content.pm.ResolveInfo, android.service.voice.IVoiceInteractionSession, com.android.internal.app.IVoiceInteractor, android.os.IBinder, java.lang.String, int, int, int, java.lang.String, int, int, int, com.android.server.am.SafeActivityOptions, boolean, boolean, com.android.server.am.ActivityRecord[], com.android.server.am.TaskRecord, boolean, com.android.server.am.PendingIntentRecord):int");
    }

    private void maybeLogActivityStart(int callingUid, String callingPackage, int realCallingUid, Intent intent, ProcessRecord callerApp, ActivityRecord r, PendingIntentRecord originatingPendingIntent) {
        boolean z;
        long j;
        String str;
        if (callerApp != null) {
            z = callerApp.foregroundActivities;
        } else {
            z = false;
        }
        boolean callerAppHasForegroundActivity = z;
        if (!this.mService.isActivityStartsLoggingEnabled() || callerAppHasForegroundActivity || r == null) {
            return;
        }
        try {
            Trace.traceBegin(64L, "logActivityStart");
            int callingUidProcState = this.mService.getUidStateLocked(callingUid);
            boolean callingUidHasAnyVisibleWindow = this.mService.mWindowManager.isAnyWindowVisibleForUid(callingUid);
            int realCallingUidProcState = callingUid == realCallingUid ? callingUidProcState : this.mService.getUidStateLocked(realCallingUid);
            boolean realCallingUidHasAnyVisibleWindow = callingUid == realCallingUid ? callingUidHasAnyVisibleWindow : this.mService.mWindowManager.isAnyWindowVisibleForUid(realCallingUid);
            String targetPackage = r.packageName;
            int targetUid = r.appInfo != null ? r.appInfo.uid : -1;
            int targetUidProcState = this.mService.getUidStateLocked(targetUid);
            boolean targetUidHasAnyVisibleWindow = targetUid != -1 ? this.mService.mWindowManager.isAnyWindowVisibleForUid(targetUid) : false;
            if (targetUid != -1) {
                str = this.mService.getPendingTempWhitelistTagForUidLocked(targetUid);
            } else {
                str = null;
            }
            String targetWhitelistTag = str;
            try {
                this.mSupervisor.getActivityMetricsLogger().logActivityStart(intent, callerApp, r, callingUid, callingPackage, callingUidProcState, callingUidHasAnyVisibleWindow, realCallingUid, realCallingUidProcState, realCallingUidHasAnyVisibleWindow, targetUid, targetPackage, targetUidProcState, targetUidHasAnyVisibleWindow, targetWhitelistTag, originatingPendingIntent != null);
                Trace.traceEnd(64L);
            } catch (Throwable th) {
                th = th;
                j = 64;
                Trace.traceEnd(j);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            j = 64;
        }
    }

    private Intent createLaunchIntent(AuxiliaryResolveInfo auxiliaryResponse, Intent originalIntent, String callingPackage, Bundle verificationBundle, String resolvedType, int userId) {
        Intent intent;
        ComponentName componentName;
        if (auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo) {
            this.mService.getPackageManagerInternalLocked().requestInstantAppResolutionPhaseTwo(auxiliaryResponse, originalIntent, resolvedType, callingPackage, verificationBundle, userId);
        }
        Intent sanitizeIntent = InstantAppResolver.sanitizeIntent(originalIntent);
        List list = null;
        if (auxiliaryResponse != null) {
            intent = auxiliaryResponse.failureIntent;
        } else {
            intent = null;
        }
        if (auxiliaryResponse != null) {
            componentName = auxiliaryResponse.installFailureActivity;
        } else {
            componentName = null;
        }
        String str = auxiliaryResponse == null ? null : auxiliaryResponse.token;
        boolean z = auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo;
        if (auxiliaryResponse != null) {
            list = auxiliaryResponse.filters;
        }
        return InstantAppResolver.buildEphemeralInstallerIntent(originalIntent, sanitizeIntent, intent, callingPackage, verificationBundle, resolvedType, userId, componentName, str, z, list);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postStartActivityProcessing(ActivityRecord r, int result, ActivityStack targetStack) {
        if (ActivityManager.isStartResultFatalError(result)) {
            return;
        }
        this.mSupervisor.reportWaitingActivityLaunchedIfNeeded(r, result);
        ActivityStack startedActivityStack = null;
        ActivityStack currentStack = r.getStack();
        if (currentStack != null) {
            startedActivityStack = currentStack;
        } else if (this.mTargetStack != null) {
            startedActivityStack = targetStack;
        }
        if (startedActivityStack == null) {
            return;
        }
        boolean clearedTask = (this.mLaunchFlags & 268468224) == 268468224 && this.mReuseTask != null;
        if (result == 2 || result == 3 || clearedTask) {
            switch (startedActivityStack.getWindowingMode()) {
                case 2:
                    this.mService.mTaskChangeNotificationController.notifyPinnedActivityRestartAttempt(clearedTask);
                    return;
                case 3:
                    ActivityStack homeStack = this.mSupervisor.mHomeStack;
                    if (homeStack != null && homeStack.shouldBeVisible(null)) {
                        this.mService.mWindowManager.showRecentApps();
                        return;
                    }
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(19:180|(27:(2:182|(16:184|74|75|76|77|78|79|80|(8:82|83|84|(2:157|158)|86|87|88|89)(1:164)|91|92|93|(3:110|111|(2:113|114)(5:134|(4:135|136|137|(1:1)(1:141))|143|144|(1:146)))|95|96|97))|210|211|212|213|214|215|216|217|(1:219)(1:232)|220|(3:224|225|226)(1:222)|223|75|76|77|78|79|80|(0)(0)|91|92|93|(0)|95|96|97)|190|191|(3:252|253|(2:255|256)(2:257|258))(1:193)|194|195|196|(1:198)|199|(1:201)(1:249)|202|203|204|205|206|207|208|209) */
    /* JADX WARN: Code restructure failed: missing block: B:103:0x01f0, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:151:0x0372, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:152:0x0373, code lost:
        r28 = r7;
     */
    /* JADX WARN: Code restructure failed: missing block: B:154:0x038d, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:155:0x038e, code lost:
        r28 = r7;
     */
    /* JADX WARN: Code restructure failed: missing block: B:156:0x03a4, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:157:0x03a5, code lost:
        r28 = r7;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:167:0x0430  */
    /* JADX WARN: Removed duplicated region for block: B:183:0x047b  */
    /* JADX WARN: Removed duplicated region for block: B:270:0x048e A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:274:0x0199 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:59:0x0107  */
    /* JADX WARN: Removed duplicated region for block: B:79:0x0178 A[Catch: all -> 0x015b, TRY_ENTER, TRY_LEAVE, TryCatch #20 {all -> 0x015b, blocks: (B:69:0x014d, B:79:0x0178, B:93:0x01c2), top: B:278:0x014d }] */
    /* JADX WARN: Type inference failed for: r0v45 */
    /* JADX WARN: Type inference failed for: r0v52, types: [boolean] */
    /* JADX WARN: Type inference failed for: r0v90 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int startActivityMayWait(android.app.IApplicationThread r49, int r50, java.lang.String r51, int r52, int r53, android.content.Intent r54, java.lang.String r55, android.service.voice.IVoiceInteractionSession r56, com.android.internal.app.IVoiceInteractor r57, android.os.IBinder r58, java.lang.String r59, int r60, int r61, android.app.ProfilerInfo r62, android.app.WaitResult r63, android.content.res.Configuration r64, com.android.server.am.SafeActivityOptions r65, boolean r66, int r67, com.android.server.am.TaskRecord r68, java.lang.String r69, boolean r70, com.android.server.am.PendingIntentRecord r71) {
        /*
            Method dump skipped, instructions count: 1358
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStarter.startActivityMayWait(android.app.IApplicationThread, int, java.lang.String, int, int, android.content.Intent, java.lang.String, android.service.voice.IVoiceInteractionSession, com.android.internal.app.IVoiceInteractor, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.app.WaitResult, android.content.res.Configuration, com.android.server.am.SafeActivityOptions, boolean, int, com.android.server.am.TaskRecord, java.lang.String, boolean, com.android.server.am.PendingIntentRecord):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int computeResolveFilterUid(int customCallingUid, int actualCallingUid, int filterCallingUid) {
        if (filterCallingUid != -10000) {
            return filterCallingUid;
        }
        return customCallingUid >= 0 ? customCallingUid : actualCallingUid;
    }

    private int startActivity(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity) {
        try {
            this.mService.mWindowManager.deferSurfaceLayout();
            int result = startActivityUnchecked(r, sourceRecord, voiceSession, voiceInteractor, startFlags, doResume, options, inTask, outActivity);
            ActivityStack stack = this.mStartActivity.getStack();
            if (!ActivityManager.isStartResultSuccessful(result) && stack != null) {
                stack.finishActivityLocked(this.mStartActivity, 0, null, "startActivity", true);
            }
            this.mService.mWindowManager.continueSurfaceLayout();
            postStartActivityProcessing(r, result, this.mTargetStack);
            return result;
        } catch (Throwable th) {
            ActivityStack stack2 = this.mStartActivity.getStack();
            if (!ActivityManager.isStartResultSuccessful(-96) && stack2 != null) {
                stack2.finishActivityLocked(this.mStartActivity, 0, null, "startActivity", true);
            }
            this.mService.mWindowManager.continueSurfaceLayout();
            throw th;
        }
    }

    private int startActivityUnchecked(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity) {
        setInitialState(r, options, inTask, doResume, startFlags, sourceRecord, voiceSession, voiceInteractor);
        computeLaunchingTaskFlags();
        computeSourceStack();
        this.mIntent.setFlags(this.mLaunchFlags);
        ActivityRecord reusedActivity = getReusableIntentActivity();
        int preferredWindowingMode = 0;
        int preferredLaunchDisplayId = 0;
        if (this.mOptions != null) {
            preferredWindowingMode = this.mOptions.getLaunchWindowingMode();
            preferredLaunchDisplayId = this.mOptions.getLaunchDisplayId();
        }
        if (!this.mLaunchParams.isEmpty()) {
            if (this.mLaunchParams.hasPreferredDisplay()) {
                preferredLaunchDisplayId = this.mLaunchParams.mPreferredDisplayId;
            }
            if (this.mLaunchParams.hasWindowingMode()) {
                preferredWindowingMode = this.mLaunchParams.mWindowingMode;
            }
        }
        if (reusedActivity != null) {
            if (this.mService.getLockTaskController().isLockTaskModeViolation(reusedActivity.getTask(), (this.mLaunchFlags & 268468224) == 268468224)) {
                Slog.e("ActivityManager", "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return 101;
            }
            boolean clearTopAndResetStandardLaunchMode = (this.mLaunchFlags & 69206016) == 69206016 && this.mLaunchMode == 0;
            if (this.mStartActivity.getTask() == null && !clearTopAndResetStandardLaunchMode) {
                this.mStartActivity.setTask(reusedActivity.getTask());
            }
            if (reusedActivity.getTask().intent == null) {
                reusedActivity.getTask().setIntent(this.mStartActivity);
            }
            if ((this.mLaunchFlags & 67108864) != 0 || isDocumentLaunchesIntoExisting(this.mLaunchFlags) || isLaunchModeOneOf(3, 2)) {
                TaskRecord task = reusedActivity.getTask();
                ActivityRecord top = task.performClearTaskForReuseLocked(this.mStartActivity, this.mLaunchFlags);
                if (reusedActivity.getTask() == null) {
                    reusedActivity.setTask(task);
                }
                if (top != null) {
                    if (top.frontOfTask) {
                        top.getTask().setIntent(this.mStartActivity);
                    }
                    deliverNewIntent(top);
                }
            }
            this.mSupervisor.sendPowerHintForLaunchStartIfNeeded(false, reusedActivity);
            ActivityRecord reusedActivity2 = setTargetStackAndMoveToFrontIfNeeded(reusedActivity);
            ActivityRecord outResult = (outActivity == null || outActivity.length <= 0) ? null : outActivity[0];
            if (outResult != null && (outResult.finishing || outResult.noDisplay)) {
                outActivity[0] = reusedActivity2;
            }
            if ((this.mStartFlags & 1) != 0) {
                resumeTargetStackIfNeeded();
                return 1;
            } else if (reusedActivity2 != null) {
                setTaskFromIntentActivity(reusedActivity2);
                if (!this.mAddingToTask && this.mReuseTask == null) {
                    resumeTargetStackIfNeeded();
                    if (outActivity != null && outActivity.length > 0) {
                        outActivity[0] = reusedActivity2;
                    }
                    return this.mMovedToFront ? 2 : 3;
                }
            }
        }
        if (this.mStartActivity.packageName == null) {
            ActivityStack sourceStack = this.mStartActivity.resultTo != null ? this.mStartActivity.resultTo.getStack() : null;
            if (sourceStack != null) {
                sourceStack.sendActivityResultLocked(-1, this.mStartActivity.resultTo, this.mStartActivity.resultWho, this.mStartActivity.requestCode, 0, null);
            }
            ActivityOptions.abort(this.mOptions);
            return -92;
        }
        ActivityStack topStack = this.mSupervisor.mFocusedStack;
        ActivityRecord topFocused = topStack.getTopActivity();
        ActivityRecord top2 = topStack.topRunningNonDelayedActivityLocked(this.mNotTop);
        boolean dontStart = top2 != null && this.mStartActivity.resultTo == null && top2.realActivity.equals(this.mStartActivity.realActivity) && top2.userId == this.mStartActivity.userId && top2.app != null && top2.app.thread != null && ((this.mLaunchFlags & 536870912) != 0 || isLaunchModeOneOf(1, 2));
        if (dontStart) {
            topStack.mLastPausedActivity = null;
            if (this.mDoResume) {
                this.mSupervisor.resumeFocusedStackTopActivityLocked();
            }
            ActivityOptions.abort(this.mOptions);
            if ((this.mStartFlags & 1) != 0) {
                return 1;
            }
            deliverNewIntent(top2);
            this.mSupervisor.handleNonResizableTaskIfNeeded(top2.getTask(), preferredWindowingMode, preferredLaunchDisplayId, topStack);
            return 3;
        }
        boolean newTask = false;
        TaskRecord taskToAffiliate = (!this.mLaunchTaskBehind || this.mSourceRecord == null) ? null : this.mSourceRecord.getTask();
        int result = 0;
        if (this.mStartActivity.resultTo == null && this.mInTask == null && !this.mAddingToTask && (this.mLaunchFlags & 268435456) != 0) {
            newTask = true;
            result = setTaskFromReuseOrCreateNewTask(taskToAffiliate, topStack);
        } else if (this.mSourceRecord != null) {
            result = setTaskFromSourceRecord();
        } else if (this.mInTask != null) {
            result = setTaskFromInTask();
        } else {
            setTaskToCurrentTopOrCreateNewTask();
        }
        boolean newTask2 = newTask;
        int result2 = result;
        if (result2 != 0) {
            return result2;
        }
        this.mService.grantUriPermissionFromIntentLocked(this.mCallingUid, this.mStartActivity.packageName, this.mIntent, this.mStartActivity.getUriPermissionsLocked(), this.mStartActivity.userId);
        this.mService.grantEphemeralAccessLocked(this.mStartActivity.userId, this.mIntent, this.mStartActivity.appInfo.uid, UserHandle.getAppId(this.mCallingUid));
        if (newTask2) {
            EventLog.writeEvent((int) EventLogTags.AM_CREATE_TASK, Integer.valueOf(this.mStartActivity.userId), Integer.valueOf(this.mStartActivity.getTask().taskId));
        }
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, this.mStartActivity, this.mStartActivity.getTask());
        this.mTargetStack.mLastPausedActivity = null;
        this.mSupervisor.sendPowerHintForLaunchStartIfNeeded(false, this.mStartActivity);
        this.mTargetStack.startActivityLocked(this.mStartActivity, topFocused, newTask2, this.mKeepCurTransition, this.mOptions);
        if (this.mDoResume) {
            ActivityRecord topTaskActivity = this.mStartActivity.getTask().topRunningActivityLocked();
            if (!this.mTargetStack.isFocusable() || (topTaskActivity != null && topTaskActivity.mTaskOverlay && this.mStartActivity != topTaskActivity)) {
                this.mTargetStack.ensureActivitiesVisibleLocked(null, 0, false);
                this.mService.mWindowManager.executeAppTransition();
            } else {
                if (this.mTargetStack.isFocusable() && !this.mSupervisor.isFocusedStack(this.mTargetStack)) {
                    this.mTargetStack.moveToFront("startActivityUnchecked");
                }
                this.mSupervisor.resumeFocusedStackTopActivityLocked(this.mTargetStack, this.mStartActivity, this.mOptions);
            }
        } else if (this.mStartActivity != null) {
            this.mSupervisor.mRecentTasks.add(this.mStartActivity.getTask());
        }
        this.mSupervisor.updateUserStackLocked(this.mStartActivity.userId, this.mTargetStack);
        this.mSupervisor.handleNonResizableTaskIfNeeded(this.mStartActivity.getTask(), preferredWindowingMode, preferredLaunchDisplayId, this.mTargetStack);
        return 0;
    }

    private int startPhoneActivityUnchecked(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity) {
        int result;
        setInitialState(r, options, null, doResume, startFlags, sourceRecord, voiceSession, voiceInteractor);
        computeLaunchingTaskFlags();
        this.mIntent.setFlags(this.mLaunchFlags);
        if (this.mStartActivity.packageName == null) {
            ActivityStack sourceStack = this.mStartActivity.resultTo != null ? this.mStartActivity.resultTo.getStack() : null;
            if (sourceStack != null) {
                sourceStack.sendActivityResultLocked(-1, this.mStartActivity.resultTo, this.mStartActivity.resultWho, this.mStartActivity.requestCode, 0, null);
            }
            ActivityOptions.abort(this.mOptions);
            return -92;
        }
        ActivityStack topStack = this.mSupervisor.mPhoneStack;
        ActivityRecord topFocused = topStack.getTopActivity();
        ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(this.mNotTop);
        boolean dontStart = top != null && this.mStartActivity.resultTo == null && top.realActivity.equals(this.mStartActivity.realActivity) && top.userId == this.mStartActivity.userId && top.app != null && top.app.thread != null && ((this.mLaunchFlags & 536870912) != 0 || isLaunchModeOneOf(1, 2));
        if (dontStart) {
            topStack.mLastPausedActivity = null;
            if (this.mDoResume) {
                this.mSupervisor.resumePhoneStackTopActivityLocked();
            }
            ActivityOptions.abort(this.mOptions);
            if ((this.mStartFlags & 1) != 0) {
                return 1;
            }
            deliverNewIntent(top);
            return 3;
        }
        boolean newTask = false;
        if (this.mStartActivity.resultTo == null && (this.mLaunchFlags & 268435456) != 0) {
            newTask = true;
            result = setPhoneTaskFromNewTask();
        } else {
            result = setPhoneTask();
        }
        boolean newTask2 = newTask;
        if (newTask2) {
            EventLog.writeEvent((int) EventLogTags.AM_CREATE_TASK, Integer.valueOf(this.mStartActivity.userId), Integer.valueOf(this.mStartActivity.getTask().taskId));
        }
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, this.mStartActivity, this.mStartActivity.getTask());
        this.mTargetStack.mLastPausedActivity = null;
        this.mTargetStack.startActivityLocked(this.mStartActivity, topFocused, newTask2, this.mKeepCurTransition, this.mOptions);
        if (this.mDoResume) {
            Slog.d("ActivityManager", "startPhoneActivityUnchecked doResume..");
            this.mSupervisor.resumePhoneStackTopActivityLocked(this.mTargetStack, this.mStartActivity, this.mOptions);
        } else if (this.mStartActivity != null) {
            this.mSupervisor.mRecentTasks.add(this.mStartActivity.getTask());
        }
        this.mSupervisor.updateUserStackLocked(this.mStartActivity.userId, this.mTargetStack);
        Slog.d("ActivityManager", "startPhoneActivityUnchecked Success..");
        return 0;
    }

    private int setPhoneTaskFromNewTask() {
        this.mTargetStack = this.mSupervisor.getPhoneStack();
        TaskRecord task = this.mTargetStack.createTaskRecord(this.mSupervisor.getNextTaskIdForUserLocked(this.mStartActivity.userId), this.mNewTaskInfo != null ? this.mNewTaskInfo : this.mStartActivity.info, this.mNewTaskIntent != null ? this.mNewTaskIntent : this.mIntent, this.mVoiceSession, this.mVoiceInteractor, !this.mLaunchTaskBehind, this.mStartActivity, this.mSourceRecord, this.mOptions);
        addOrReparentStartingActivity(task, "setPhoneTaskFromNewTask");
        updateBounds(this.mStartActivity.getTask(), this.mLaunchParams.mBounds);
        Slog.d("ActivityManager", "Starting new activity " + this.mStartActivity + " in new task " + this.mStartActivity.getTask());
        return 0;
    }

    private int setPhoneTask() {
        this.mTargetStack = this.mSupervisor.getPhoneStack();
        TaskRecord topTask = this.mTargetStack.topTask();
        if (topTask == null) {
            setPhoneTaskFromNewTask();
            return 0;
        }
        addOrReparentStartingActivity(topTask, "setPhoneTask");
        Slog.d("ActivityManager", "Starting new activity " + this.mStartActivity + " in top task " + this.mStartActivity.getTask());
        return 0;
    }

    void reset(boolean clearRequest) {
        this.mStartActivity = null;
        this.mIntent = null;
        this.mCallingUid = -1;
        this.mOptions = null;
        this.mLaunchTaskBehind = false;
        this.mLaunchFlags = 0;
        this.mLaunchMode = -1;
        this.mLaunchParams.reset();
        this.mNotTop = null;
        this.mDoResume = false;
        this.mStartFlags = 0;
        this.mSourceRecord = null;
        this.mPreferredDisplayId = -1;
        this.mInTask = null;
        this.mAddingToTask = false;
        this.mReuseTask = null;
        this.mNewTaskInfo = null;
        this.mNewTaskIntent = null;
        this.mSourceStack = null;
        this.mTargetStack = null;
        this.mMovedToFront = false;
        this.mNoAnimation = false;
        this.mKeepCurTransition = false;
        this.mAvoidMoveToFront = false;
        this.mVoiceSession = null;
        this.mVoiceInteractor = null;
        this.mIntentDelivered = false;
        if (clearRequest) {
            this.mRequest.reset();
        }
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, TaskRecord inTask, boolean doResume, int startFlags, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        reset(false);
        this.mStartActivity = r;
        this.mIntent = r.intent;
        this.mOptions = options;
        this.mCallingUid = r.launchedFromUid;
        this.mSourceRecord = sourceRecord;
        this.mVoiceSession = voiceSession;
        this.mVoiceInteractor = voiceInteractor;
        this.mPreferredDisplayId = getPreferedDisplayId(this.mSourceRecord, this.mStartActivity, options);
        this.mLaunchParams.reset();
        this.mSupervisor.getLaunchParamsController().calculate(inTask, null, r, sourceRecord, options, this.mLaunchParams);
        this.mLaunchMode = r.launchMode;
        this.mLaunchFlags = adjustLaunchFlagsToDocumentMode(r, 3 == this.mLaunchMode, 2 == this.mLaunchMode, this.mIntent.getFlags());
        this.mLaunchTaskBehind = (!r.mLaunchTaskBehind || isLaunchModeOneOf(2, 3) || (this.mLaunchFlags & DumpState.DUMP_FROZEN) == 0) ? false : true;
        sendNewTaskResultRequestIfNeeded();
        if ((this.mLaunchFlags & DumpState.DUMP_FROZEN) != 0 && r.resultTo == null) {
            this.mLaunchFlags |= 268435456;
        }
        if ((this.mLaunchFlags & 268435456) != 0 && (this.mLaunchTaskBehind || r.info.documentLaunchMode == 2)) {
            this.mLaunchFlags |= 134217728;
        }
        this.mSupervisor.mUserLeaving = (this.mLaunchFlags & DumpState.DUMP_DOMAIN_PREFERRED) == 0;
        if (ActivityManagerDebugConfig.DEBUG_USER_LEAVING) {
            Slog.v("ActivityManager", "startActivity() => mUserLeaving=" + this.mSupervisor.mUserLeaving);
        }
        this.mDoResume = doResume;
        if (!doResume || !r.okToShowLocked()) {
            r.delayedResume = true;
            this.mDoResume = false;
        }
        if (this.mOptions != null) {
            if (this.mOptions.getLaunchTaskId() != -1 && this.mOptions.getTaskOverlay()) {
                r.mTaskOverlay = true;
                if (!this.mOptions.canTaskOverlayResume()) {
                    TaskRecord task = this.mSupervisor.anyTaskForIdLocked(this.mOptions.getLaunchTaskId());
                    ActivityRecord top = task != null ? task.getTopActivity() : null;
                    if (top != null && !top.isState(ActivityStack.ActivityState.RESUMED)) {
                        this.mDoResume = false;
                        this.mAvoidMoveToFront = true;
                    }
                }
            } else if (this.mOptions.getAvoidMoveToFront()) {
                this.mDoResume = false;
                this.mAvoidMoveToFront = true;
            }
        }
        this.mNotTop = (this.mLaunchFlags & 16777216) != 0 ? r : null;
        this.mInTask = inTask;
        if (inTask != null && !inTask.inRecents) {
            Slog.w("ActivityManager", "Starting activity in task not in recents: " + inTask);
            this.mInTask = null;
        }
        this.mStartFlags = startFlags;
        if ((startFlags & 1) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = this.mSupervisor.mFocusedStack.topRunningNonDelayedActivityLocked(this.mNotTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                this.mStartFlags &= -2;
            }
        }
        this.mNoAnimation = (this.mLaunchFlags & 65536) != 0;
    }

    private void sendNewTaskResultRequestIfNeeded() {
        ActivityStack sourceStack = this.mStartActivity.resultTo != null ? this.mStartActivity.resultTo.getStack() : null;
        if (sourceStack != null && (this.mLaunchFlags & 268435456) != 0) {
            Slog.w("ActivityManager", "Activity is launching as a new task, so cancelling activity result.");
            sourceStack.sendActivityResultLocked(-1, this.mStartActivity.resultTo, this.mStartActivity.resultWho, this.mStartActivity.requestCode, 0, null);
            this.mStartActivity.resultTo = null;
        }
    }

    private void computeLaunchingTaskFlags() {
        if (this.mSourceRecord == null && this.mInTask != null && this.mInTask.getStack() != null) {
            Intent baseIntent = this.mInTask.getBaseIntent();
            ActivityRecord root = this.mInTask.getRootActivity();
            if (baseIntent == null) {
                ActivityOptions.abort(this.mOptions);
                throw new IllegalArgumentException("Launching into task without base intent: " + this.mInTask);
            }
            if (isLaunchModeOneOf(3, 2)) {
                if (!baseIntent.getComponent().equals(this.mStartActivity.intent.getComponent())) {
                    ActivityOptions.abort(this.mOptions);
                    throw new IllegalArgumentException("Trying to launch singleInstance/Task " + this.mStartActivity + " into different task " + this.mInTask);
                } else if (root != null) {
                    ActivityOptions.abort(this.mOptions);
                    throw new IllegalArgumentException("Caller with mInTask " + this.mInTask + " has root " + root + " but target is singleInstance/Task");
                }
            }
            if (root == null) {
                this.mLaunchFlags = (this.mLaunchFlags & (-403185665)) | (baseIntent.getFlags() & 403185664);
                this.mIntent.setFlags(this.mLaunchFlags);
                this.mInTask.setIntent(this.mStartActivity);
                this.mAddingToTask = true;
            } else if ((this.mLaunchFlags & 268435456) != 0) {
                this.mAddingToTask = false;
            } else {
                this.mAddingToTask = true;
            }
            this.mReuseTask = this.mInTask;
        } else {
            this.mInTask = null;
            if ((this.mStartActivity.isResolverActivity() || this.mStartActivity.noDisplay) && this.mSourceRecord != null && this.mSourceRecord.inFreeformWindowingMode()) {
                this.mAddingToTask = true;
            }
        }
        if (this.mInTask == null) {
            if (this.mSourceRecord == null) {
                if ((this.mLaunchFlags & 268435456) == 0 && this.mInTask == null) {
                    Slog.w("ActivityManager", "startActivity called from non-Activity context; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: " + this.mIntent);
                    this.mLaunchFlags = this.mLaunchFlags | 268435456;
                }
            } else if (this.mSourceRecord.launchMode == 3) {
                this.mLaunchFlags |= 268435456;
            } else if (isLaunchModeOneOf(3, 2)) {
                this.mLaunchFlags |= 268435456;
            }
        }
    }

    private void computeSourceStack() {
        if (this.mSourceRecord == null) {
            this.mSourceStack = null;
        } else if (!this.mSourceRecord.finishing) {
            this.mSourceStack = this.mSourceRecord.getStack();
        } else {
            if ((this.mLaunchFlags & 268435456) == 0) {
                Slog.w("ActivityManager", "startActivity called from finishing " + this.mSourceRecord + "; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: " + this.mIntent);
                this.mLaunchFlags = this.mLaunchFlags | 268435456;
                this.mNewTaskInfo = this.mSourceRecord.info;
                TaskRecord sourceTask = this.mSourceRecord.getTask();
                this.mNewTaskIntent = sourceTask != null ? sourceTask.intent : null;
            }
            this.mSourceRecord = null;
            this.mSourceStack = null;
        }
    }

    private ActivityRecord getReusableIntentActivity() {
        boolean putIntoExistingTask = ((this.mLaunchFlags & 268435456) != 0 && (this.mLaunchFlags & 134217728) == 0) || isLaunchModeOneOf(3, 2);
        boolean putIntoExistingTask2 = putIntoExistingTask & (this.mInTask == null && this.mStartActivity.resultTo == null);
        if (this.mOptions != null && this.mOptions.getLaunchTaskId() != -1) {
            TaskRecord task = this.mSupervisor.anyTaskForIdLocked(this.mOptions.getLaunchTaskId());
            ActivityRecord intentActivity = task != null ? task.getTopActivity() : null;
            return intentActivity;
        } else if (!putIntoExistingTask2) {
            return null;
        } else {
            if (3 == this.mLaunchMode) {
                ActivityRecord intentActivity2 = this.mSupervisor.findActivityLocked(this.mIntent, this.mStartActivity.info, this.mStartActivity.isActivityTypeHome());
                return intentActivity2;
            } else if ((this.mLaunchFlags & 4096) != 0) {
                ActivityRecord intentActivity3 = this.mSupervisor.findActivityLocked(this.mIntent, this.mStartActivity.info, 2 != this.mLaunchMode);
                return intentActivity3;
            } else {
                ActivityRecord intentActivity4 = this.mSupervisor.findTaskLocked(this.mStartActivity, this.mPreferredDisplayId);
                return intentActivity4;
            }
        }
    }

    private int getPreferedDisplayId(ActivityRecord sourceRecord, ActivityRecord startingActivity, ActivityOptions options) {
        if (startingActivity != null && startingActivity.requestedVrComponent != null) {
            return 0;
        }
        int displayId = this.mService.mVr2dDisplayId;
        if (displayId != -1) {
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityManager", "getSourceDisplayId :" + displayId);
            }
            return displayId;
        }
        int launchDisplayId = options != null ? options.getLaunchDisplayId() : -1;
        if (launchDisplayId != -1) {
            return launchDisplayId;
        }
        int displayId2 = sourceRecord != null ? sourceRecord.getDisplayId() : -1;
        if (displayId2 == -1) {
            return 0;
        }
        return displayId2;
    }

    private ActivityRecord setTargetStackAndMoveToFrontIfNeeded(ActivityRecord intentActivity) {
        this.mTargetStack = intentActivity.getStack();
        this.mTargetStack.mLastPausedActivity = null;
        ActivityStack focusStack = this.mSupervisor.getFocusedStack();
        ActivityRecord curTop = focusStack == null ? null : focusStack.topRunningNonDelayedActivityLocked(this.mNotTop);
        TaskRecord topTask = curTop != null ? curTop.getTask() : null;
        if (topTask != null && ((topTask != intentActivity.getTask() || topTask != focusStack.topTask()) && !this.mAvoidMoveToFront)) {
            this.mStartActivity.intent.addFlags(DumpState.DUMP_CHANGES);
            if (this.mSourceRecord == null || (this.mSourceStack.getTopActivity() != null && this.mSourceStack.getTopActivity().getTask() == this.mSourceRecord.getTask())) {
                if (this.mLaunchTaskBehind && this.mSourceRecord != null) {
                    intentActivity.setTaskToAffiliateWith(this.mSourceRecord.getTask());
                }
                boolean willClearTask = (this.mLaunchFlags & 268468224) == 268468224;
                if (!willClearTask) {
                    ActivityStack launchStack = getLaunchStack(this.mStartActivity, this.mLaunchFlags, this.mStartActivity.getTask(), this.mOptions);
                    TaskRecord intentTask = intentActivity.getTask();
                    if (launchStack == null || launchStack == this.mTargetStack) {
                        this.mTargetStack.moveTaskToFrontLocked(intentTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "bringingFoundTaskToFront");
                        this.mMovedToFront = true;
                    } else if (launchStack.inSplitScreenWindowingMode()) {
                        if ((this.mLaunchFlags & 4096) != 0) {
                            intentTask.reparent(launchStack, true, 0, true, true, "launchToSide");
                        } else {
                            this.mTargetStack.moveTaskToFrontLocked(intentTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "bringToFrontInsteadOfAdjacentLaunch");
                        }
                        this.mMovedToFront = launchStack != launchStack.getDisplay().getTopStackInWindowingMode(launchStack.getWindowingMode());
                    } else if (launchStack.mDisplayId != this.mTargetStack.mDisplayId) {
                        intentActivity.getTask().reparent(launchStack, true, 0, true, true, "reparentToDisplay");
                        this.mMovedToFront = true;
                    } else if (launchStack.isActivityTypeHome() && !this.mTargetStack.isActivityTypeHome()) {
                        intentActivity.getTask().reparent(launchStack, true, 0, true, true, "reparentingHome");
                        this.mMovedToFront = true;
                    }
                    this.mOptions = null;
                    intentActivity.showStartingWindow(null, false, true);
                }
            }
        }
        this.mTargetStack = intentActivity.getStack();
        if (!this.mMovedToFront && this.mDoResume) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Bring to front target: " + this.mTargetStack + " from " + intentActivity);
            }
            this.mTargetStack.moveToFront("intentActivityFound");
        }
        this.mSupervisor.handleNonResizableTaskIfNeeded(intentActivity.getTask(), 0, 0, this.mTargetStack);
        return (this.mLaunchFlags & DumpState.DUMP_COMPILER_STATS) != 0 ? this.mTargetStack.resetTaskIfNeededLocked(intentActivity, this.mStartActivity) : intentActivity;
    }

    private void setTaskFromIntentActivity(ActivityRecord intentActivity) {
        if ((this.mLaunchFlags & 268468224) == 268468224) {
            TaskRecord task = intentActivity.getTask();
            task.performClearTaskLocked();
            this.mReuseTask = task;
            this.mReuseTask.setIntent(this.mStartActivity);
        } else if ((this.mLaunchFlags & 67108864) != 0 || isLaunchModeOneOf(3, 2)) {
            ActivityRecord top = intentActivity.getTask().performClearTaskLocked(this.mStartActivity, this.mLaunchFlags);
            if (top == null) {
                this.mAddingToTask = true;
                this.mStartActivity.setTask(null);
                this.mSourceRecord = intentActivity;
                TaskRecord task2 = this.mSourceRecord.getTask();
                if (task2 != null && task2.getStack() == null) {
                    this.mTargetStack = computeStackFocus(this.mSourceRecord, false, this.mLaunchFlags, this.mOptions);
                    this.mTargetStack.addTask(task2, true ^ this.mLaunchTaskBehind, "startActivityUnchecked");
                }
            }
        } else if (this.mStartActivity.realActivity.equals(intentActivity.getTask().realActivity)) {
            if (((this.mLaunchFlags & 536870912) != 0 || 1 == this.mLaunchMode) && intentActivity.realActivity.equals(this.mStartActivity.realActivity)) {
                if (intentActivity.frontOfTask) {
                    intentActivity.getTask().setIntent(this.mStartActivity);
                }
                deliverNewIntent(intentActivity);
            } else if (!intentActivity.getTask().isSameIntentFilter(this.mStartActivity)) {
                this.mAddingToTask = true;
                this.mSourceRecord = intentActivity;
            }
        } else if ((this.mLaunchFlags & DumpState.DUMP_COMPILER_STATS) == 0) {
            this.mAddingToTask = true;
            this.mSourceRecord = intentActivity;
        } else if (!intentActivity.getTask().rootWasReset) {
            intentActivity.getTask().setIntent(this.mStartActivity);
        }
    }

    private void resumeTargetStackIfNeeded() {
        if (this.mDoResume) {
            this.mSupervisor.resumeFocusedStackTopActivityLocked(this.mTargetStack, null, this.mOptions);
        } else {
            ActivityOptions.abort(this.mOptions);
        }
        this.mSupervisor.updateUserStackLocked(this.mStartActivity.userId, this.mTargetStack);
    }

    private int setTaskFromReuseOrCreateNewTask(TaskRecord taskToAffiliate, ActivityStack topStack) {
        this.mTargetStack = computeStackFocus(this.mStartActivity, true, this.mLaunchFlags, this.mOptions);
        if (this.mReuseTask == null) {
            TaskRecord task = this.mTargetStack.createTaskRecord(this.mSupervisor.getNextTaskIdForUserLocked(this.mStartActivity.userId), this.mNewTaskInfo != null ? this.mNewTaskInfo : this.mStartActivity.info, this.mNewTaskIntent != null ? this.mNewTaskIntent : this.mIntent, this.mVoiceSession, this.mVoiceInteractor, !this.mLaunchTaskBehind, this.mStartActivity, this.mSourceRecord, this.mOptions);
            addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask - mReuseTask");
            updateBounds(this.mStartActivity.getTask(), this.mLaunchParams.mBounds);
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityManager", "Starting new activity " + this.mStartActivity + " in new task " + this.mStartActivity.getTask());
            }
        } else {
            addOrReparentStartingActivity(this.mReuseTask, "setTaskFromReuseOrCreateNewTask");
        }
        if (taskToAffiliate != null) {
            this.mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
        }
        if (this.mService.getLockTaskController().isLockTaskModeViolation(this.mStartActivity.getTask())) {
            Slog.e("ActivityManager", "Attempted Lock Task Mode violation mStartActivity=" + this.mStartActivity);
            return 101;
        } else if (this.mDoResume) {
            this.mTargetStack.moveToFront("reuseOrNewTask");
            return 0;
        } else {
            return 0;
        }
    }

    private void deliverNewIntent(ActivityRecord activity) {
        if (this.mIntentDelivered) {
            return;
        }
        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, activity, activity.getTask());
        activity.deliverNewIntentLocked(this.mCallingUid, this.mStartActivity.intent, this.mStartActivity.launchedFromPackage);
        this.mIntentDelivered = true;
    }

    private int setTaskFromSourceRecord() {
        ActivityRecord top;
        if (this.mService.getLockTaskController().isLockTaskModeViolation(this.mSourceRecord.getTask())) {
            Slog.e("ActivityManager", "Attempted Lock Task Mode violation mStartActivity=" + this.mStartActivity);
            return 101;
        }
        TaskRecord sourceTask = this.mSourceRecord.getTask();
        ActivityStack sourceStack = this.mSourceRecord.getStack();
        int targetDisplayId = this.mTargetStack != null ? this.mTargetStack.mDisplayId : sourceStack.mDisplayId;
        boolean moveStackAllowed = (sourceStack.topTask() == sourceTask && this.mStartActivity.canBeLaunchedOnDisplay(targetDisplayId)) ? false : true;
        if (moveStackAllowed) {
            this.mTargetStack = getLaunchStack(this.mStartActivity, this.mLaunchFlags, this.mStartActivity.getTask(), this.mOptions);
            if (this.mTargetStack == null && targetDisplayId != sourceStack.mDisplayId) {
                this.mTargetStack = this.mService.mStackSupervisor.getValidLaunchStackOnDisplay(sourceStack.mDisplayId, this.mStartActivity);
            }
            if (this.mTargetStack == null) {
                this.mTargetStack = this.mService.mStackSupervisor.getNextValidLaunchStackLocked(this.mStartActivity, -1);
            }
        }
        if (this.mTargetStack == null) {
            this.mTargetStack = sourceStack;
        } else if (this.mTargetStack != sourceStack) {
            sourceTask.reparent(this.mTargetStack, true, 0, false, true, "launchToSide");
        }
        TaskRecord topTask = this.mTargetStack.topTask();
        if (topTask != sourceTask && !this.mAvoidMoveToFront) {
            this.mTargetStack.moveTaskToFrontLocked(sourceTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "sourceTaskToFront");
        } else if (this.mDoResume) {
            this.mTargetStack.moveToFront("sourceStackToFront");
        }
        if (!this.mAddingToTask && (this.mLaunchFlags & 67108864) != 0) {
            ActivityRecord top2 = sourceTask.performClearTaskLocked(this.mStartActivity, this.mLaunchFlags);
            this.mKeepCurTransition = true;
            if (top2 != null) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, this.mStartActivity, top2.getTask());
                deliverNewIntent(top2);
                this.mTargetStack.mLastPausedActivity = null;
                if (this.mDoResume) {
                    this.mSupervisor.resumeFocusedStackTopActivityLocked();
                }
                ActivityOptions.abort(this.mOptions);
                return 3;
            }
        } else if (!this.mAddingToTask && (this.mLaunchFlags & DumpState.DUMP_INTENT_FILTER_VERIFIERS) != 0 && (top = sourceTask.findActivityInHistoryLocked(this.mStartActivity)) != null) {
            TaskRecord task = top.getTask();
            task.moveActivityToFrontLocked(top);
            top.updateOptionsLocked(this.mOptions);
            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, this.mStartActivity, task);
            deliverNewIntent(top);
            this.mTargetStack.mLastPausedActivity = null;
            if (this.mDoResume) {
                this.mSupervisor.resumeFocusedStackTopActivityLocked();
            }
            return 3;
        }
        addOrReparentStartingActivity(sourceTask, "setTaskFromSourceRecord");
        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
            Slog.v("ActivityManager", "Starting new activity " + this.mStartActivity + " in existing task " + this.mStartActivity.getTask() + " from source " + this.mSourceRecord);
        }
        return 0;
    }

    private int setTaskFromInTask() {
        if (this.mService.getLockTaskController().isLockTaskModeViolation(this.mInTask)) {
            Slog.e("ActivityManager", "Attempted Lock Task Mode violation mStartActivity=" + this.mStartActivity);
            return 101;
        }
        this.mTargetStack = this.mInTask.getStack();
        ActivityRecord top = this.mInTask.getTopActivity();
        if (top != null && top.realActivity.equals(this.mStartActivity.realActivity) && top.userId == this.mStartActivity.userId && ((this.mLaunchFlags & 536870912) != 0 || isLaunchModeOneOf(1, 2))) {
            this.mTargetStack.moveTaskToFrontLocked(this.mInTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "inTaskToFront");
            if ((this.mStartFlags & 1) != 0) {
                return 1;
            }
            deliverNewIntent(top);
            return 3;
        } else if (!this.mAddingToTask) {
            this.mTargetStack.moveTaskToFrontLocked(this.mInTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "inTaskToFront");
            ActivityOptions.abort(this.mOptions);
            return 2;
        } else {
            if (!this.mLaunchParams.mBounds.isEmpty()) {
                ActivityStack stack = this.mSupervisor.getLaunchStack(null, null, this.mInTask, true);
                if (stack != this.mInTask.getStack()) {
                    this.mInTask.reparent(stack, true, 1, false, true, "inTaskToFront");
                    this.mTargetStack = this.mInTask.getStack();
                }
                updateBounds(this.mInTask, this.mLaunchParams.mBounds);
            }
            this.mTargetStack.moveTaskToFrontLocked(this.mInTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "inTaskToFront");
            addOrReparentStartingActivity(this.mInTask, "setTaskFromInTask");
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityManager", "Starting new activity " + this.mStartActivity + " in explicit task " + this.mStartActivity.getTask());
                return 0;
            }
            return 0;
        }
    }

    @VisibleForTesting
    void updateBounds(TaskRecord task, Rect bounds) {
        if (bounds.isEmpty()) {
            return;
        }
        ActivityStack stack = task.getStack();
        if (stack != null && stack.resizeStackWithLaunchBounds()) {
            this.mService.resizeStack(stack.mStackId, bounds, true, false, true, -1);
        } else {
            task.updateOverrideConfiguration(bounds);
        }
    }

    private void setTaskToCurrentTopOrCreateNewTask() {
        this.mTargetStack = computeStackFocus(this.mStartActivity, false, this.mLaunchFlags, this.mOptions);
        if (this.mDoResume) {
            this.mTargetStack.moveToFront("addingToTopTask");
        }
        ActivityRecord prev = this.mTargetStack.getTopActivity();
        TaskRecord task = prev != null ? prev.getTask() : this.mTargetStack.createTaskRecord(this.mSupervisor.getNextTaskIdForUserLocked(this.mStartActivity.userId), this.mStartActivity.info, this.mIntent, null, null, true, this.mStartActivity, this.mSourceRecord, this.mOptions);
        addOrReparentStartingActivity(task, "setTaskToCurrentTopOrCreateNewTask");
        this.mTargetStack.positionChildWindowContainerAtTop(task);
        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
            Slog.v("ActivityManager", "Starting new activity " + this.mStartActivity + " in new guessed " + this.mStartActivity.getTask());
        }
    }

    private void addOrReparentStartingActivity(TaskRecord parent, String reason) {
        if (this.mStartActivity.getTask() == null || this.mStartActivity.getTask() == parent) {
            parent.addActivityToTop(this.mStartActivity);
        } else {
            this.mStartActivity.reparent(parent, parent.mActivities.size(), reason);
        }
    }

    private int adjustLaunchFlagsToDocumentMode(ActivityRecord r, boolean launchSingleInstance, boolean launchSingleTask, int launchFlags) {
        if ((launchFlags & DumpState.DUMP_FROZEN) != 0 && (launchSingleInstance || launchSingleTask)) {
            Slog.i("ActivityManager", "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is \"singleInstance\" or \"singleTask\"");
            return launchFlags & (-134742017);
        }
        switch (r.info.documentLaunchMode) {
            case 0:
            default:
                return launchFlags;
            case 1:
                return launchFlags | DumpState.DUMP_FROZEN;
            case 2:
                return launchFlags | DumpState.DUMP_FROZEN;
            case 3:
                return launchFlags & (-134217729);
        }
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, int launchFlags, ActivityOptions aOptions) {
        TaskRecord task = r.getTask();
        ActivityStack stack = getLaunchStack(r, launchFlags, task, aOptions);
        if (stack != null) {
            return stack;
        }
        ActivityStack currentStack = task != null ? task.getStack() : null;
        if (currentStack != null) {
            if (this.mSupervisor.mFocusedStack != currentStack) {
                if (ActivityManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityManager", "computeStackFocus: Setting focused stack to r=" + r + " task=" + task);
                }
            } else if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityManager", "computeStackFocus: Focused stack already=" + this.mSupervisor.mFocusedStack);
            }
            return currentStack;
        } else if (canLaunchIntoFocusedStack(r, newTask)) {
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityManager", "computeStackFocus: Have a focused stack=" + this.mSupervisor.mFocusedStack);
            }
            return this.mSupervisor.mFocusedStack;
        } else {
            if (this.mPreferredDisplayId != 0 && (stack = this.mSupervisor.getValidLaunchStackOnDisplay(this.mPreferredDisplayId, r)) == null) {
                if (ActivityManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityManager", "computeStackFocus: Can't launch on mPreferredDisplayId=" + this.mPreferredDisplayId + ", looking on all displays.");
                }
                stack = this.mSupervisor.getNextValidLaunchStackLocked(r, this.mPreferredDisplayId);
            }
            if (stack == null) {
                ActivityDisplay display = this.mSupervisor.getDefaultDisplay();
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                    ActivityStack stack2 = display.getChildAt(stackNdx);
                    if (!stack2.isOnHomeDisplay()) {
                        if (ActivityManagerDebugConfig.DEBUG_STACK) {
                            Slog.d("ActivityManager", "computeStackFocus: Setting focused stack=" + stack2);
                        }
                        return stack2;
                    }
                }
                stack = this.mSupervisor.getLaunchStack(r, aOptions, task, true);
            }
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityManager", "computeStackFocus: New stack r=" + r + " stackId=" + stack.mStackId);
            }
            return stack;
        }
    }

    private boolean canLaunchIntoFocusedStack(ActivityRecord r, boolean newTask) {
        boolean canUseFocusedStack;
        ActivityStack focusedStack = this.mSupervisor.mFocusedStack;
        if (focusedStack.isActivityTypeAssistant()) {
            canUseFocusedStack = r.isActivityTypeAssistant();
        } else {
            int windowingMode = focusedStack.getWindowingMode();
            if (windowingMode == 1) {
                canUseFocusedStack = true;
            } else {
                switch (windowingMode) {
                    case 3:
                    case 4:
                        canUseFocusedStack = r.supportsSplitScreenWindowingMode();
                        break;
                    case 5:
                        canUseFocusedStack = r.supportsFreeform();
                        break;
                    default:
                        if (!focusedStack.isOnHomeDisplay() && r.canBeLaunchedOnDisplay(focusedStack.mDisplayId)) {
                            canUseFocusedStack = true;
                            break;
                        } else {
                            canUseFocusedStack = false;
                            break;
                        }
                }
            }
        }
        return canUseFocusedStack && !newTask && this.mPreferredDisplayId == focusedStack.mDisplayId;
    }

    private ActivityStack getLaunchStack(ActivityRecord r, int launchFlags, TaskRecord task, ActivityOptions aOptions) {
        if (this.mReuseTask != null) {
            return this.mReuseTask.getStack();
        }
        if ((launchFlags & 4096) == 0 || this.mPreferredDisplayId != 0) {
            int candidateDisplay = this.mPreferredDisplayId != 0 ? this.mPreferredDisplayId : -1;
            return this.mSupervisor.getLaunchStack(r, aOptions, task, true, candidateDisplay);
        }
        ActivityStack parentStack = task != null ? task.getStack() : this.mSupervisor.mFocusedStack;
        if (parentStack != this.mSupervisor.mFocusedStack) {
            return parentStack;
        }
        if (this.mSupervisor.mFocusedStack == null || task != this.mSupervisor.mFocusedStack.topTask()) {
            if (parentStack != null && parentStack.inSplitScreenPrimaryWindowingMode()) {
                int activityType = this.mSupervisor.resolveActivityType(r, this.mOptions, task);
                return parentStack.getDisplay().getOrCreateStack(4, activityType, true);
            }
            ActivityStack dockedStack = this.mSupervisor.getDefaultDisplay().getSplitScreenPrimaryStack();
            if (dockedStack != null && !dockedStack.shouldBeVisible(r)) {
                return this.mSupervisor.getLaunchStack(r, aOptions, task, true);
            }
            return dockedStack;
        }
        return this.mSupervisor.mFocusedStack;
    }

    private boolean isLaunchModeOneOf(int mode1, int mode2) {
        return mode1 == this.mLaunchMode || mode2 == this.mLaunchMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isDocumentLaunchesIntoExisting(int flags) {
        return (524288 & flags) != 0 && (134217728 & flags) == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setIntent(Intent intent) {
        this.mRequest.intent = intent;
        return this;
    }

    @VisibleForTesting
    Intent getIntent() {
        return this.mRequest.intent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setReason(String reason) {
        this.mRequest.reason = reason;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setCaller(IApplicationThread caller) {
        this.mRequest.caller = caller;
        return this;
    }

    ActivityStarter setEphemeralIntent(Intent intent) {
        this.mRequest.ephemeralIntent = intent;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setResolvedType(String type) {
        this.mRequest.resolvedType = type;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setActivityInfo(ActivityInfo info) {
        this.mRequest.activityInfo = info;
        return this;
    }

    ActivityStarter setResolveInfo(ResolveInfo info) {
        this.mRequest.resolveInfo = info;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setVoiceSession(IVoiceInteractionSession voiceSession) {
        this.mRequest.voiceSession = voiceSession;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setVoiceInteractor(IVoiceInteractor voiceInteractor) {
        this.mRequest.voiceInteractor = voiceInteractor;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setResultTo(IBinder resultTo) {
        this.mRequest.resultTo = resultTo;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setResultWho(String resultWho) {
        this.mRequest.resultWho = resultWho;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setRequestCode(int requestCode) {
        this.mRequest.requestCode = requestCode;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setCallingPid(int pid) {
        this.mRequest.callingPid = pid;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setCallingUid(int uid) {
        this.mRequest.callingUid = uid;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setCallingPackage(String callingPackage) {
        this.mRequest.callingPackage = callingPackage;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setRealCallingPid(int pid) {
        this.mRequest.realCallingPid = pid;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setRealCallingUid(int uid) {
        this.mRequest.realCallingUid = uid;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setStartFlags(int startFlags) {
        this.mRequest.startFlags = startFlags;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setActivityOptions(SafeActivityOptions options) {
        this.mRequest.activityOptions = options;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setActivityOptions(Bundle bOptions) {
        return setActivityOptions(SafeActivityOptions.fromBundle(bOptions));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setIgnoreTargetSecurity(boolean ignoreTargetSecurity) {
        this.mRequest.ignoreTargetSecurity = ignoreTargetSecurity;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setFilterCallingUid(int filterCallingUid) {
        this.mRequest.filterCallingUid = filterCallingUid;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setComponentSpecified(boolean componentSpecified) {
        this.mRequest.componentSpecified = componentSpecified;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setOutActivity(ActivityRecord[] outActivity) {
        this.mRequest.outActivity = outActivity;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setInTask(TaskRecord inTask) {
        this.mRequest.inTask = inTask;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setWaitResult(WaitResult result) {
        this.mRequest.waitResult = result;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setProfilerInfo(ProfilerInfo info) {
        this.mRequest.profilerInfo = info;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setGlobalConfiguration(Configuration config) {
        this.mRequest.globalConfig = config;
        return this;
    }

    ActivityStarter setUserId(int userId) {
        this.mRequest.userId = userId;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setMayWait(int userId) {
        this.mRequest.mayWait = true;
        this.mRequest.userId = userId;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setAllowPendingRemoteAnimationRegistryLookup(boolean allowLookup) {
        this.mRequest.allowPendingRemoteAnimationRegistryLookup = allowLookup;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStarter setOriginatingPendingIntent(PendingIntentRecord originatingPendingIntent) {
        this.mRequest.originatingPendingIntent = originatingPendingIntent;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        String prefix2 = prefix + "  ";
        pw.print(prefix2);
        pw.print("mCurrentUser=");
        pw.println(this.mSupervisor.mCurrentUser);
        pw.print(prefix2);
        pw.print("mLastStartReason=");
        pw.println(this.mLastStartReason);
        pw.print(prefix2);
        pw.print("mLastStartActivityTimeMs=");
        pw.println(DateFormat.getDateTimeInstance().format(new Date(this.mLastStartActivityTimeMs)));
        pw.print(prefix2);
        pw.print("mLastStartActivityResult=");
        pw.println(this.mLastStartActivityResult);
        ActivityRecord r = this.mLastStartActivityRecord[0];
        if (r != null) {
            pw.print(prefix2);
            pw.println("mLastStartActivityRecord:");
            r.dump(pw, prefix2 + "  ");
        }
        if (this.mStartActivity != null) {
            pw.print(prefix2);
            pw.println("mStartActivity:");
            this.mStartActivity.dump(pw, prefix2 + "  ");
        }
        if (this.mIntent != null) {
            pw.print(prefix2);
            pw.print("mIntent=");
            pw.println(this.mIntent);
        }
        if (this.mOptions != null) {
            pw.print(prefix2);
            pw.print("mOptions=");
            pw.println(this.mOptions);
        }
        pw.print(prefix2);
        pw.print("mLaunchSingleTop=");
        pw.print(1 == this.mLaunchMode);
        pw.print(" mLaunchSingleInstance=");
        pw.print(3 == this.mLaunchMode);
        pw.print(" mLaunchSingleTask=");
        pw.println(2 == this.mLaunchMode);
        pw.print(prefix2);
        pw.print("mLaunchFlags=0x");
        pw.print(Integer.toHexString(this.mLaunchFlags));
        pw.print(" mDoResume=");
        pw.print(this.mDoResume);
        pw.print(" mAddingToTask=");
        pw.println(this.mAddingToTask);
    }
}
