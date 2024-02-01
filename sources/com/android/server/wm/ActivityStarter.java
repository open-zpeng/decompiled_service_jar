package com.android.server.wm;

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
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Pools;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentRecord;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.DumpState;
import com.android.server.pm.InstantAppResolver;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.LaunchParamsController;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class ActivityStarter {
    private static final int INVALID_LAUNCH_MODE = -1;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_CONFIGURATION = "ActivityTaskManager";
    private static final String TAG_FOCUS = "ActivityTaskManager";
    private static final String TAG_RESULTS = "ActivityTaskManager";
    private static final String TAG_USER_LEAVING = "ActivityTaskManager";
    private boolean mAddingToTask;
    private boolean mAvoidMoveToFront;
    private int mCallingUid;
    private final ActivityStartController mController;
    private boolean mDoResume;
    private boolean mFrozeTaskList;
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
    private boolean mRestrictedBgActivity;
    private TaskRecord mReuseTask;
    private final RootActivityContainer mRootActivityContainer;
    private final ActivityTaskManagerService mService;
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
    /* loaded from: classes2.dex */
    public interface Factory {
        ActivityStarter obtain();

        void recycle(ActivityStarter activityStarter);

        void setController(ActivityStartController activityStartController);
    }

    /* loaded from: classes2.dex */
    static class DefaultFactory implements Factory {
        private ActivityStartController mController;
        private ActivityStartInterceptor mInterceptor;
        private ActivityTaskManagerService mService;
        private ActivityStackSupervisor mSupervisor;
        private final int MAX_STARTER_COUNT = 3;
        private Pools.SynchronizedPool<ActivityStarter> mStarterPool = new Pools.SynchronizedPool<>(3);

        /* JADX INFO: Access modifiers changed from: package-private */
        public DefaultFactory(ActivityTaskManagerService service, ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
            this.mService = service;
            this.mSupervisor = supervisor;
            this.mInterceptor = interceptor;
        }

        @Override // com.android.server.wm.ActivityStarter.Factory
        public void setController(ActivityStartController controller) {
            this.mController = controller;
        }

        @Override // com.android.server.wm.ActivityStarter.Factory
        public ActivityStarter obtain() {
            ActivityStarter starter = (ActivityStarter) this.mStarterPool.acquire();
            if (starter == null) {
                return new ActivityStarter(this.mController, this.mService, this.mSupervisor, this.mInterceptor);
            }
            return starter;
        }

        @Override // com.android.server.wm.ActivityStarter.Factory
        public void recycle(ActivityStarter starter) {
            starter.reset(true);
            this.mStarterPool.release(starter);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class Request {
        private static final int DEFAULT_CALLING_PID = 0;
        private static final int DEFAULT_CALLING_UID = -1;
        static final int DEFAULT_REAL_CALLING_PID = 0;
        static final int DEFAULT_REAL_CALLING_UID = -1;
        ActivityInfo activityInfo;
        SafeActivityOptions activityOptions;
        boolean allowBackgroundActivityStart;
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
        int realCallingUid = -1;

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
            this.realCallingUid = -1;
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
            this.filterCallingUid = -10000;
            this.originatingPendingIntent = null;
            this.allowBackgroundActivityStart = false;
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
            this.allowBackgroundActivityStart = request.allowBackgroundActivityStart;
        }
    }

    ActivityStarter(ActivityStartController controller, ActivityTaskManagerService service, ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
        this.mController = controller;
        this.mService = service;
        this.mRootActivityContainer = service.mRootActivityContainer;
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
        this.mRestrictedBgActivity = starter.mRestrictedBgActivity;
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
        this.mFrozeTaskList = starter.mFrozeTaskList;
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
        ActivityRecord activityRecord;
        ActivityRecord[] activityRecordArr = this.mLastStartActivityRecord;
        return (activityRecordArr[0] != null && packageName.equals(activityRecordArr[0].packageName)) || ((activityRecord = this.mStartActivity) != null && packageName.equals(activityRecord.packageName));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int execute() {
        try {
            if (this.mRequest.mayWait) {
                return startActivityMayWait(this.mRequest.caller, this.mRequest.callingUid, this.mRequest.callingPackage, this.mRequest.realCallingPid, this.mRequest.realCallingUid, this.mRequest.intent, this.mRequest.resolvedType, this.mRequest.voiceSession, this.mRequest.voiceInteractor, this.mRequest.resultTo, this.mRequest.resultWho, this.mRequest.requestCode, this.mRequest.startFlags, this.mRequest.profilerInfo, this.mRequest.waitResult, this.mRequest.globalConfig, this.mRequest.activityOptions, this.mRequest.ignoreTargetSecurity, this.mRequest.userId, this.mRequest.inTask, this.mRequest.reason, this.mRequest.allowPendingRemoteAnimationRegistryLookup, this.mRequest.originatingPendingIntent, this.mRequest.allowBackgroundActivityStart);
            }
            return startActivity(this.mRequest.caller, this.mRequest.intent, this.mRequest.ephemeralIntent, this.mRequest.resolvedType, this.mRequest.activityInfo, this.mRequest.resolveInfo, this.mRequest.voiceSession, this.mRequest.voiceInteractor, this.mRequest.resultTo, this.mRequest.resultWho, this.mRequest.requestCode, this.mRequest.callingPid, this.mRequest.callingUid, this.mRequest.callingPackage, this.mRequest.realCallingPid, this.mRequest.realCallingUid, this.mRequest.startFlags, this.mRequest.activityOptions, this.mRequest.ignoreTargetSecurity, this.mRequest.componentSpecified, this.mRequest.outActivity, this.mRequest.inTask, this.mRequest.reason, this.mRequest.allowPendingRemoteAnimationRegistryLookup, this.mRequest.originatingPendingIntent, this.mRequest.allowBackgroundActivityStart);
        } finally {
            onExecutionComplete();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int startResolvedActivity(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask) {
        try {
            this.mSupervisor.getActivityMetricsLogger().notifyActivityLaunching(r.intent);
            this.mLastStartReason = "startResolvedActivity";
            this.mLastStartActivityTimeMs = System.currentTimeMillis();
            this.mLastStartActivityRecord[0] = r;
            this.mLastStartActivityResult = startActivity(r, sourceRecord, voiceSession, voiceInteractor, startFlags, doResume, options, inTask, this.mLastStartActivityRecord, false);
            this.mSupervisor.getActivityMetricsLogger().notifyActivityLaunched(this.mLastStartActivityResult, this.mLastStartActivityRecord[0]);
            return this.mLastStartActivityResult;
        } finally {
            onExecutionComplete();
        }
    }

    private int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent, String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid, String callingPackage, int realCallingPid, int realCallingUid, int startFlags, SafeActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified, ActivityRecord[] outActivity, TaskRecord inTask, String reason, boolean allowPendingRemoteAnimationRegistryLookup, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
        if (TextUtils.isEmpty(reason)) {
            throw new IllegalArgumentException("Need to specify a reason.");
        }
        this.mLastStartReason = reason;
        this.mLastStartActivityTimeMs = System.currentTimeMillis();
        ActivityRecord[] activityRecordArr = this.mLastStartActivityRecord;
        activityRecordArr[0] = null;
        this.mLastStartActivityResult = startActivity(caller, intent, ephemeralIntent, resolvedType, aInfo, rInfo, voiceSession, voiceInteractor, resultTo, resultWho, requestCode, callingPid, callingUid, callingPackage, realCallingPid, realCallingUid, startFlags, options, ignoreTargetSecurity, componentSpecified, activityRecordArr, inTask, allowPendingRemoteAnimationRegistryLookup, originatingPendingIntent, allowBackgroundActivityStart);
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

    /* JADX WARN: Removed duplicated region for block: B:133:0x033a  */
    /* JADX WARN: Removed duplicated region for block: B:134:0x0361  */
    /* JADX WARN: Removed duplicated region for block: B:136:0x036f  */
    /* JADX WARN: Removed duplicated region for block: B:140:0x0388  */
    /* JADX WARN: Removed duplicated region for block: B:170:0x04e8  */
    /* JADX WARN: Removed duplicated region for block: B:171:0x04eb  */
    /* JADX WARN: Removed duplicated region for block: B:174:0x0515  */
    /* JADX WARN: Removed duplicated region for block: B:175:0x0519  */
    /* JADX WARN: Removed duplicated region for block: B:178:0x051e  */
    /* JADX WARN: Removed duplicated region for block: B:181:0x0527  */
    /* JADX WARN: Removed duplicated region for block: B:184:0x0531  */
    /* JADX WARN: Removed duplicated region for block: B:201:0x058a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int startActivity(android.app.IApplicationThread r64, android.content.Intent r65, android.content.Intent r66, java.lang.String r67, android.content.pm.ActivityInfo r68, android.content.pm.ResolveInfo r69, android.service.voice.IVoiceInteractionSession r70, com.android.internal.app.IVoiceInteractor r71, android.os.IBinder r72, java.lang.String r73, int r74, int r75, int r76, java.lang.String r77, int r78, int r79, int r80, com.android.server.wm.SafeActivityOptions r81, boolean r82, boolean r83, com.android.server.wm.ActivityRecord[] r84, com.android.server.wm.TaskRecord r85, boolean r86, com.android.server.am.PendingIntentRecord r87, boolean r88) {
        /*
            Method dump skipped, instructions count: 1476
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStarter.startActivity(android.app.IApplicationThread, android.content.Intent, android.content.Intent, java.lang.String, android.content.pm.ActivityInfo, android.content.pm.ResolveInfo, android.service.voice.IVoiceInteractionSession, com.android.internal.app.IVoiceInteractor, android.os.IBinder, java.lang.String, int, int, int, java.lang.String, int, int, int, com.android.server.wm.SafeActivityOptions, boolean, boolean, com.android.server.wm.ActivityRecord[], com.android.server.wm.TaskRecord, boolean, com.android.server.am.PendingIntentRecord, boolean):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldAbortBackgroundActivityStart(int callingUid, int callingPid, String callingPackage, int realCallingUid, int realCallingPid, WindowProcessController callerApp, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart, Intent intent) {
        int realCallingUidProcState;
        boolean isAnyNonToastWindowVisibleForUid;
        boolean z;
        boolean isRealCallingUidPersistentSystemProcess;
        boolean z2;
        WindowProcessController callerApp2;
        int callerAppUid;
        int realCallingUidProcState2;
        int callingUserId;
        boolean z3;
        int callingAppId = UserHandle.getAppId(callingUid);
        if (callingUid == 0 || callingAppId == 1000) {
            return false;
        }
        if (callingAppId == 1027) {
            return false;
        }
        int callingUidProcState = this.mService.getUidState(callingUid);
        boolean callingUidHasAnyVisibleWindow = this.mService.mWindowManager.mRoot.isAnyNonToastWindowVisibleForUid(callingUid);
        boolean isCallingUidForeground = callingUidHasAnyVisibleWindow || callingUidProcState == 2 || callingUidProcState == 4;
        boolean isCallingUidPersistentSystemProcess = callingUidProcState <= 1;
        if (!callingUidHasAnyVisibleWindow && !isCallingUidPersistentSystemProcess) {
            if (callingUid == realCallingUid) {
                realCallingUidProcState = callingUidProcState;
            } else {
                realCallingUidProcState = this.mService.getUidState(realCallingUid);
            }
            if (callingUid == realCallingUid) {
                isAnyNonToastWindowVisibleForUid = callingUidHasAnyVisibleWindow;
            } else {
                isAnyNonToastWindowVisibleForUid = this.mService.mWindowManager.mRoot.isAnyNonToastWindowVisibleForUid(realCallingUid);
            }
            boolean realCallingUidHasAnyVisibleWindow = isAnyNonToastWindowVisibleForUid;
            if (callingUid == realCallingUid) {
                z = isCallingUidForeground;
            } else {
                z = realCallingUidHasAnyVisibleWindow || realCallingUidProcState == 2;
            }
            boolean isRealCallingUidForeground = z;
            int realCallingAppId = UserHandle.getAppId(realCallingUid);
            if (callingUid == realCallingUid) {
                isRealCallingUidPersistentSystemProcess = isCallingUidPersistentSystemProcess;
            } else {
                isRealCallingUidPersistentSystemProcess = realCallingAppId == 1000 || realCallingUidProcState <= 1;
            }
            if (realCallingUid == callingUid) {
                z2 = false;
            } else if (realCallingUidHasAnyVisibleWindow) {
                return false;
            } else {
                z2 = false;
                if (isRealCallingUidPersistentSystemProcess && allowBackgroundActivityStart) {
                    return false;
                }
                ActivityTaskManagerService activityTaskManagerService = this.mService;
                int realCallingAppId2 = UserHandle.getUserId(realCallingUid);
                if (activityTaskManagerService.isAssociatedCompanionApp(realCallingAppId2, realCallingUid)) {
                    return false;
                }
            }
            ActivityTaskManagerService activityTaskManagerService2 = this.mService;
            if (ActivityTaskManagerService.checkPermission("android.permission.START_ACTIVITIES_FROM_BACKGROUND", callingPid, callingUid) == 0) {
                return z2;
            }
            if (this.mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
                return z2;
            }
            if (this.mService.isDeviceOwner(callingUid)) {
                return z2;
            }
            int callingUserId2 = UserHandle.getUserId(callingUid);
            if (this.mService.isAssociatedCompanionApp(callingUserId2, callingUid)) {
                return z2;
            }
            if (callerApp == null) {
                callerApp2 = this.mService.getProcessController(realCallingPid, realCallingUid);
                callerAppUid = realCallingUid;
            } else {
                callerApp2 = callerApp;
                callerAppUid = callingUid;
            }
            if (callerApp2 == null) {
                realCallingUidProcState2 = realCallingUidProcState;
                callingUserId = callingUserId2;
                z3 = true;
            } else if (callerApp2.areBackgroundActivityStartsAllowed()) {
                return false;
            } else {
                realCallingUidProcState2 = realCallingUidProcState;
                ArraySet<WindowProcessController> uidProcesses = this.mService.mProcessMap.getProcesses(callerAppUid);
                if (uidProcesses != null) {
                    z3 = true;
                    callingUserId = callingUserId2;
                    int callingUserId3 = uidProcesses.size() - 1;
                    while (callingUserId3 >= 0) {
                        ArraySet<WindowProcessController> uidProcesses2 = uidProcesses;
                        WindowProcessController proc = uidProcesses.valueAt(callingUserId3);
                        if (proc == callerApp2 || !proc.areBackgroundActivityStartsAllowed()) {
                            callingUserId3--;
                            uidProcesses = uidProcesses2;
                        } else {
                            return false;
                        }
                    }
                } else {
                    callingUserId = callingUserId2;
                    z3 = true;
                }
            }
            if (this.mService.hasSystemAlertWindowPermission(callingUid, callingPid, callingPackage)) {
                Slog.w("ActivityTaskManager", "Background activity start for " + callingPackage + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
                return false;
            }
            Slog.w("ActivityTaskManager", "Background activity start [callingPackage: " + callingPackage + "; callingUid: " + callingUid + "; isCallingUidForeground: " + isCallingUidForeground + "; isCallingUidPersistentSystemProcess: " + isCallingUidPersistentSystemProcess + "; realCallingUid: " + realCallingUid + "; isRealCallingUidForeground: " + isRealCallingUidForeground + "; isRealCallingUidPersistentSystemProcess: " + isRealCallingUidPersistentSystemProcess + "; originatingPendingIntent: " + originatingPendingIntent + "; isBgStartWhitelisted: " + allowBackgroundActivityStart + "; intent: " + intent + "; callerApp: " + callerApp2 + "]");
            if (this.mService.isActivityStartsLoggingEnabled()) {
                boolean z4 = z3;
                this.mSupervisor.getActivityMetricsLogger().logAbortedBgActivityStart(intent, callerApp2, callingUid, callingPackage, callingUidProcState, callingUidHasAnyVisibleWindow, realCallingUid, realCallingUidProcState2, realCallingUidHasAnyVisibleWindow, originatingPendingIntent != null ? z3 : false);
                return z4;
            }
            return z3;
        }
        return false;
    }

    private Intent createLaunchIntent(AuxiliaryResolveInfo auxiliaryResponse, Intent originalIntent, String callingPackage, Bundle verificationBundle, String resolvedType, int userId) {
        Intent intent;
        ComponentName componentName;
        String str;
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
        if (auxiliaryResponse != null) {
            str = auxiliaryResponse.token;
        } else {
            str = null;
        }
        boolean z = auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo;
        if (auxiliaryResponse != null) {
            list = auxiliaryResponse.filters;
        }
        return InstantAppResolver.buildEphemeralInstallerIntent(originalIntent, sanitizeIntent, intent, callingPackage, verificationBundle, resolvedType, userId, componentName, str, z, list);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postStartActivityProcessing(ActivityRecord r, int result, ActivityStack startedActivityStack) {
        ActivityStack homeStack;
        if (!ActivityManager.isStartResultSuccessful(result) && this.mFrozeTaskList) {
            this.mSupervisor.mRecentTasks.resetFreezeTaskListReorderingOnTimeout();
        }
        if (ActivityManager.isStartResultFatalError(result)) {
            return;
        }
        this.mSupervisor.reportWaitingActivityLaunchedIfNeeded(r, result);
        if (startedActivityStack == null) {
            return;
        }
        boolean clearedTask = (this.mLaunchFlags & 268468224) == 268468224 && this.mReuseTask != null;
        if (result == 2 || result == 3 || clearedTask) {
            int windowingMode = startedActivityStack.getWindowingMode();
            if (windowingMode != 2) {
                if (windowingMode == 3 && (homeStack = startedActivityStack.getDisplay().getHomeStack()) != null && homeStack.shouldBeVisible(null)) {
                    this.mService.mWindowManager.showRecentApps();
                    return;
                }
                return;
            }
            this.mService.getTaskChangeNotificationController().notifyPinnedActivityRestartAttempt(clearedTask);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:83:0x01a4
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private int startActivityMayWait(android.app.IApplicationThread r45, int r46, java.lang.String r47, int r48, int r49, android.content.Intent r50, java.lang.String r51, android.service.voice.IVoiceInteractionSession r52, com.android.internal.app.IVoiceInteractor r53, android.os.IBinder r54, java.lang.String r55, int r56, int r57, android.app.ProfilerInfo r58, android.app.WaitResult r59, android.content.res.Configuration r60, com.android.server.wm.SafeActivityOptions r61, boolean r62, int r63, com.android.server.wm.TaskRecord r64, java.lang.String r65, boolean r66, com.android.server.am.PendingIntentRecord r67, boolean r68) {
        /*
            Method dump skipped, instructions count: 1508
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStarter.startActivityMayWait(android.app.IApplicationThread, int, java.lang.String, int, int, android.content.Intent, java.lang.String, android.service.voice.IVoiceInteractionSession, com.android.internal.app.IVoiceInteractor, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.app.WaitResult, android.content.res.Configuration, com.android.server.wm.SafeActivityOptions, boolean, int, com.android.server.wm.TaskRecord, java.lang.String, boolean, com.android.server.am.PendingIntentRecord, boolean):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int computeResolveFilterUid(int customCallingUid, int actualCallingUid, int filterCallingUid) {
        if (filterCallingUid != -10000) {
            return filterCallingUid;
        }
        return customCallingUid >= 0 ? customCallingUid : actualCallingUid;
    }

    /* JADX WARN: Finally extract failed */
    private int startActivity(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity, boolean restrictedBgActivity) {
        ActivityRecord currentTop;
        ActivityRecord currentTop2;
        try {
            this.mService.mWindowManager.deferSurfaceLayout();
            int result = startActivityUnchecked(r, sourceRecord, voiceSession, voiceInteractor, startFlags, doResume, options, inTask, outActivity, restrictedBgActivity);
            ActivityStack currentStack = r.getActivityStack();
            ActivityStack startedActivityStack = currentStack != null ? currentStack : this.mTargetStack;
            if (!ActivityManager.isStartResultSuccessful(result)) {
                ActivityStack stack = this.mStartActivity.getActivityStack();
                if (stack != null) {
                    stack.finishActivityLocked(this.mStartActivity, 0, null, "startActivity", true);
                }
                if (startedActivityStack != null && startedActivityStack.isAttached() && startedActivityStack.numActivities() == 0 && !startedActivityStack.isActivityTypeHome()) {
                    startedActivityStack.remove();
                }
            } else if (startedActivityStack != null && (currentTop2 = startedActivityStack.topRunningActivityLocked()) != null && currentTop2.shouldUpdateConfigForDisplayChanged()) {
                this.mRootActivityContainer.ensureVisibilityAndConfig(currentTop2, currentTop2.getDisplayId(), true, false);
            }
            this.mService.mWindowManager.continueSurfaceLayout();
            postStartActivityProcessing(r, result, startedActivityStack);
            return result;
        } catch (Throwable th) {
            ActivityStack currentStack2 = r.getActivityStack();
            ActivityStack startedActivityStack2 = currentStack2 != null ? currentStack2 : this.mTargetStack;
            if (!ActivityManager.isStartResultSuccessful(-96)) {
                ActivityStack stack2 = this.mStartActivity.getActivityStack();
                if (stack2 != null) {
                    stack2.finishActivityLocked(this.mStartActivity, 0, null, "startActivity", true);
                }
                if (startedActivityStack2 != null && startedActivityStack2.isAttached() && startedActivityStack2.numActivities() == 0 && !startedActivityStack2.isActivityTypeHome()) {
                    startedActivityStack2.remove();
                }
            } else if (startedActivityStack2 != null && (currentTop = startedActivityStack2.topRunningActivityLocked()) != null && currentTop.shouldUpdateConfigForDisplayChanged()) {
                this.mRootActivityContainer.ensureVisibilityAndConfig(currentTop, currentTop.getDisplayId(), true, false);
            }
            this.mService.mWindowManager.continueSurfaceLayout();
            throw th;
        }
    }

    private boolean handleBackgroundActivityAbort(ActivityRecord r) {
        boolean abort = !this.mService.isBackgroundActivityStartsEnabled();
        if (!abort) {
            return false;
        }
        ActivityRecord resultRecord = r.resultTo;
        String resultWho = r.resultWho;
        int requestCode = r.requestCode;
        if (resultRecord != null) {
            ActivityStack resultStack = resultRecord.getActivityStack();
            resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode, 0, null);
        }
        ActivityOptions.abort(r.pendingOptions);
        return true;
    }

    private int startActivityUnchecked(ActivityRecord r, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask, ActivityRecord[] outActivity, boolean restrictedBgActivity) {
        int i;
        boolean newTask;
        int result;
        ActivityRecord activityRecord;
        setInitialState(r, options, inTask, doResume, startFlags, sourceRecord, voiceSession, voiceInteractor, restrictedBgActivity);
        int preferredWindowingMode = this.mLaunchParams.mWindowingMode;
        computeLaunchingTaskFlags();
        computeSourceStack();
        this.mIntent.setFlags(this.mLaunchFlags);
        ActivityRecord reusedActivity = getReusableIntentActivity();
        this.mSupervisor.getLaunchParamsController().calculate(reusedActivity != null ? reusedActivity.getTaskRecord() : this.mInTask, r.info.windowLayout, r, sourceRecord, options, 2, this.mLaunchParams);
        if (this.mLaunchParams.hasPreferredDisplay()) {
            i = this.mLaunchParams.mPreferredDisplayId;
        } else {
            i = 0;
        }
        this.mPreferredDisplayId = i;
        ActivityOptions activityOptions = this.mOptions;
        if (activityOptions != null && activityOptions.freezeRecentTasksReordering() && this.mSupervisor.mRecentTasks.isCallerRecents(r.launchedFromUid) && !this.mSupervisor.mRecentTasks.isFreezeTaskListReorderingSet()) {
            this.mFrozeTaskList = true;
            this.mSupervisor.mRecentTasks.setFreezeTaskListReordering();
        }
        if (r.isActivityTypeHome() && !this.mRootActivityContainer.canStartHomeOnDisplay(r.info, this.mPreferredDisplayId, true)) {
            Slog.w("ActivityTaskManager", "Cannot launch home on display " + this.mPreferredDisplayId);
            return -96;
        }
        if (reusedActivity != null) {
            if (this.mService.getLockTaskController().isLockTaskModeViolation(reusedActivity.getTaskRecord(), (this.mLaunchFlags & 268468224) == 268468224)) {
                Slog.e("ActivityTaskManager", "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return 101;
            }
            boolean clearTopAndResetStandardLaunchMode = (this.mLaunchFlags & 69206016) == 69206016 && this.mLaunchMode == 0;
            if (this.mStartActivity.getTaskRecord() == null && !clearTopAndResetStandardLaunchMode) {
                this.mStartActivity.setTask(reusedActivity.getTaskRecord());
            }
            if (reusedActivity.getTaskRecord().intent == null) {
                reusedActivity.getTaskRecord().setIntent(this.mStartActivity);
            } else {
                boolean taskOnHome = (this.mStartActivity.intent.getFlags() & 16384) != 0;
                if (taskOnHome) {
                    reusedActivity.getTaskRecord().intent.addFlags(16384);
                } else {
                    reusedActivity.getTaskRecord().intent.removeFlags(16384);
                }
            }
            int i2 = this.mLaunchFlags;
            if ((67108864 & i2) != 0 || isDocumentLaunchesIntoExisting(i2) || isLaunchModeOneOf(3, 2)) {
                TaskRecord task = reusedActivity.getTaskRecord();
                ActivityRecord top = task.performClearTaskForReuseLocked(this.mStartActivity, this.mLaunchFlags);
                if (reusedActivity.getTaskRecord() == null) {
                    reusedActivity.setTask(task);
                }
                if (top != null) {
                    if (top.frontOfTask) {
                        top.getTaskRecord().setIntent(this.mStartActivity);
                    }
                    deliverNewIntent(top);
                }
            }
            this.mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(false, reusedActivity);
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
                        outActivity[0] = reusedActivity2.finishing ? reusedActivity2.getTaskRecord().getTopActivity() : reusedActivity2;
                    }
                    return this.mMovedToFront ? 2 : 3;
                }
            }
        }
        if (this.mStartActivity.packageName == null) {
            ActivityStack sourceStack = this.mStartActivity.resultTo != null ? this.mStartActivity.resultTo.getActivityStack() : null;
            if (sourceStack != null) {
                sourceStack.sendActivityResultLocked(-1, this.mStartActivity.resultTo, this.mStartActivity.resultWho, this.mStartActivity.requestCode, 0, null);
            }
            ActivityOptions.abort(this.mOptions);
            return -92;
        }
        ActivityStack topStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        ActivityRecord topFocused = topStack.getTopActivity();
        ActivityRecord top2 = topStack.topRunningNonDelayedActivityLocked(this.mNotTop);
        boolean dontStart = top2 != null && this.mStartActivity.resultTo == null && top2.mActivityComponent.equals(this.mStartActivity.mActivityComponent) && top2.mUserId == this.mStartActivity.mUserId && top2.attachedToProcess() && ((this.mLaunchFlags & 536870912) != 0 || isLaunchModeOneOf(1, 2)) && (!top2.isActivityTypeHome() || top2.getDisplayId() == this.mPreferredDisplayId);
        if (dontStart) {
            topStack.mLastPausedActivity = null;
            if (this.mDoResume) {
                this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            ActivityOptions.abort(this.mOptions);
            if ((this.mStartFlags & 1) != 0) {
                return 1;
            }
            deliverNewIntent(top2);
            this.mSupervisor.handleNonResizableTaskIfNeeded(top2.getTaskRecord(), preferredWindowingMode, this.mPreferredDisplayId, topStack);
            return 3;
        }
        TaskRecord taskToAffiliate = (!this.mLaunchTaskBehind || (activityRecord = this.mSourceRecord) == null) ? null : activityRecord.getTaskRecord();
        if (this.mStartActivity.resultTo == null && this.mInTask == null && !this.mAddingToTask && (this.mLaunchFlags & 268435456) != 0) {
            int result2 = setTaskFromReuseOrCreateNewTask(taskToAffiliate);
            newTask = true;
            result = result2;
        } else if (this.mSourceRecord != null) {
            int result3 = setTaskFromSourceRecord();
            newTask = false;
            result = result3;
        } else if (this.mInTask != null) {
            int result4 = setTaskFromInTask();
            newTask = false;
            result = result4;
        } else {
            int result5 = setTaskToCurrentTopOrCreateNewTask();
            newTask = false;
            result = result5;
        }
        if (result != 0) {
            return result;
        }
        this.mService.mUgmInternal.grantUriPermissionFromIntent(this.mCallingUid, this.mStartActivity.packageName, this.mIntent, this.mStartActivity.getUriPermissionsLocked(), this.mStartActivity.mUserId);
        this.mService.getPackageManagerInternalLocked().grantEphemeralAccess(this.mStartActivity.mUserId, this.mIntent, UserHandle.getAppId(this.mStartActivity.appInfo.uid), UserHandle.getAppId(this.mCallingUid));
        if (newTask) {
            EventLog.writeEvent((int) EventLogTags.AM_CREATE_TASK, Integer.valueOf(this.mStartActivity.mUserId), Integer.valueOf(this.mStartActivity.getTaskRecord().taskId));
        }
        ActivityRecord activityRecord2 = this.mStartActivity;
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, activityRecord2, activityRecord2.getTaskRecord());
        this.mTargetStack.mLastPausedActivity = null;
        this.mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(false, this.mStartActivity);
        this.mTargetStack.startActivityLocked(this.mStartActivity, topFocused, newTask, this.mKeepCurTransition, this.mOptions);
        if (this.mDoResume) {
            ActivityRecord topTaskActivity = this.mStartActivity.getTaskRecord().topRunningActivityLocked();
            if (!this.mTargetStack.isFocusable() || (topTaskActivity != null && topTaskActivity.mTaskOverlay && this.mStartActivity != topTaskActivity)) {
                this.mTargetStack.ensureActivitiesVisibleLocked(this.mStartActivity, 0, false);
                this.mTargetStack.getDisplay().mDisplayContent.executeAppTransition();
            } else {
                if (this.mTargetStack.isFocusable() && !this.mRootActivityContainer.isTopDisplayFocusedStack(this.mTargetStack)) {
                    this.mTargetStack.moveToFront("startActivityUnchecked");
                }
                this.mRootActivityContainer.resumeFocusedStacksTopActivities(this.mTargetStack, this.mStartActivity, this.mOptions);
            }
        } else if (this.mStartActivity != null) {
            this.mSupervisor.mRecentTasks.add(this.mStartActivity.getTaskRecord());
        }
        this.mRootActivityContainer.updateUserStack(this.mStartActivity.mUserId, this.mTargetStack);
        this.mSupervisor.handleNonResizableTaskIfNeeded(this.mStartActivity.getTaskRecord(), preferredWindowingMode, this.mPreferredDisplayId, this.mTargetStack);
        return 0;
    }

    void reset(boolean clearRequest) {
        this.mStartActivity = null;
        this.mIntent = null;
        this.mCallingUid = -1;
        this.mOptions = null;
        this.mRestrictedBgActivity = false;
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
        this.mFrozeTaskList = false;
        this.mVoiceSession = null;
        this.mVoiceInteractor = null;
        this.mIntentDelivered = false;
        if (clearRequest) {
            this.mRequest.reset();
        }
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, TaskRecord inTask, boolean doResume, int startFlags, ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, boolean restrictedBgActivity) {
        int i;
        reset(false);
        this.mStartActivity = r;
        this.mIntent = r.intent;
        this.mOptions = options;
        this.mCallingUid = r.launchedFromUid;
        this.mSourceRecord = sourceRecord;
        this.mVoiceSession = voiceSession;
        this.mVoiceInteractor = voiceInteractor;
        this.mRestrictedBgActivity = restrictedBgActivity;
        this.mLaunchParams.reset();
        this.mSupervisor.getLaunchParamsController().calculate(inTask, r.info.windowLayout, r, sourceRecord, options, 0, this.mLaunchParams);
        if (this.mLaunchParams.hasPreferredDisplay()) {
            i = this.mLaunchParams.mPreferredDisplayId;
        } else {
            i = 0;
        }
        this.mPreferredDisplayId = i;
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
        this.mSupervisor.mUserLeaving = (this.mLaunchFlags & 262144) == 0;
        if (ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING) {
            Slog.v("ActivityTaskManager", "startActivity() => mUserLeaving=" + this.mSupervisor.mUserLeaving);
        }
        this.mDoResume = doResume;
        if (!doResume || !r.okToShowLocked()) {
            r.delayedResume = true;
            this.mDoResume = false;
        }
        ActivityOptions activityOptions = this.mOptions;
        if (activityOptions != null) {
            if (activityOptions.getLaunchTaskId() != -1 && this.mOptions.getTaskOverlay()) {
                r.mTaskOverlay = true;
                if (!this.mOptions.canTaskOverlayResume()) {
                    TaskRecord task = this.mRootActivityContainer.anyTaskForId(this.mOptions.getLaunchTaskId());
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
        this.mNotTop = (this.mLaunchFlags & 16777216) != 0 ? sourceRecord : null;
        this.mInTask = inTask;
        if (inTask != null && !inTask.inRecents) {
            Slog.w("ActivityTaskManager", "Starting activity in task not in recents: " + inTask);
            this.mInTask = null;
        }
        this.mStartFlags = startFlags;
        if ((startFlags & 1) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = this.mRootActivityContainer.getTopDisplayFocusedStack().topRunningNonDelayedActivityLocked(this.mNotTop);
            }
            if (!checkedCaller.mActivityComponent.equals(r.mActivityComponent)) {
                this.mStartFlags &= -2;
            }
        }
        this.mNoAnimation = (this.mLaunchFlags & 65536) != 0;
        if (this.mRestrictedBgActivity && !this.mService.isBackgroundActivityStartsEnabled()) {
            this.mAvoidMoveToFront = true;
            this.mDoResume = false;
        }
    }

    private void sendNewTaskResultRequestIfNeeded() {
        ActivityStack sourceStack = this.mStartActivity.resultTo != null ? this.mStartActivity.resultTo.getActivityStack() : null;
        if (sourceStack != null && (this.mLaunchFlags & 268435456) != 0) {
            Slog.w("ActivityTaskManager", "Activity is launching as a new task, so cancelling activity result.");
            sourceStack.sendActivityResultLocked(-1, this.mStartActivity.resultTo, this.mStartActivity.resultWho, this.mStartActivity.requestCode, 0, null);
            this.mStartActivity.resultTo = null;
        }
    }

    private void computeLaunchingTaskFlags() {
        ActivityRecord activityRecord;
        TaskRecord taskRecord;
        if (this.mSourceRecord == null && (taskRecord = this.mInTask) != null && taskRecord.getStack() != null) {
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
            if ((this.mStartActivity.isResolverOrDelegateActivity() || this.mStartActivity.noDisplay) && (activityRecord = this.mSourceRecord) != null && activityRecord.inFreeformWindowingMode()) {
                this.mAddingToTask = true;
            }
        }
        TaskRecord taskRecord2 = this.mInTask;
        if (taskRecord2 == null) {
            ActivityRecord activityRecord2 = this.mSourceRecord;
            if (activityRecord2 == null) {
                if ((this.mLaunchFlags & 268435456) == 0 && taskRecord2 == null) {
                    Slog.w("ActivityTaskManager", "startActivity called from non-Activity context; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: " + this.mIntent);
                    this.mLaunchFlags = this.mLaunchFlags | 268435456;
                }
            } else if (activityRecord2.launchMode == 3) {
                this.mLaunchFlags |= 268435456;
            } else if (isLaunchModeOneOf(3, 2)) {
                this.mLaunchFlags |= 268435456;
            }
        }
    }

    private void computeSourceStack() {
        ActivityRecord activityRecord = this.mSourceRecord;
        if (activityRecord == null) {
            this.mSourceStack = null;
        } else if (!activityRecord.finishing) {
            this.mSourceStack = this.mSourceRecord.getActivityStack();
        } else {
            if ((this.mLaunchFlags & 268435456) == 0) {
                Slog.w("ActivityTaskManager", "startActivity called from finishing " + this.mSourceRecord + "; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: " + this.mIntent);
                this.mLaunchFlags = this.mLaunchFlags | 268435456;
                this.mNewTaskInfo = this.mSourceRecord.info;
                TaskRecord sourceTask = this.mSourceRecord.getTaskRecord();
                this.mNewTaskIntent = sourceTask != null ? sourceTask.intent : null;
            }
            this.mSourceRecord = null;
            this.mSourceStack = null;
        }
    }

    private ActivityRecord getReusableIntentActivity() {
        int i = this.mLaunchFlags;
        boolean putIntoExistingTask = ((268435456 & i) != 0 && (i & 134217728) == 0) || isLaunchModeOneOf(3, 2);
        boolean putIntoExistingTask2 = putIntoExistingTask & (this.mInTask == null && this.mStartActivity.resultTo == null);
        ActivityRecord intentActivity = null;
        ActivityOptions activityOptions = this.mOptions;
        if (activityOptions != null && activityOptions.getLaunchTaskId() != -1) {
            TaskRecord task = this.mRootActivityContainer.anyTaskForId(this.mOptions.getLaunchTaskId());
            intentActivity = task != null ? task.getTopActivity() : null;
        } else if (putIntoExistingTask2) {
            if (3 == this.mLaunchMode) {
                intentActivity = this.mRootActivityContainer.findActivity(this.mIntent, this.mStartActivity.info, this.mStartActivity.isActivityTypeHome());
            } else if ((this.mLaunchFlags & 4096) != 0) {
                intentActivity = this.mRootActivityContainer.findActivity(this.mIntent, this.mStartActivity.info, 2 != this.mLaunchMode);
            } else {
                intentActivity = this.mRootActivityContainer.findTask(this.mStartActivity, this.mPreferredDisplayId);
            }
        }
        if (intentActivity != null) {
            if ((this.mStartActivity.isActivityTypeHome() || intentActivity.isActivityTypeHome()) && intentActivity.getDisplayId() != this.mPreferredDisplayId) {
                return null;
            }
            return intentActivity;
        }
        return intentActivity;
    }

    private ActivityRecord setTargetStackAndMoveToFrontIfNeeded(ActivityRecord intentActivity) {
        boolean differentTopTask;
        ActivityRecord activityRecord;
        ActivityRecord curTop;
        this.mTargetStack = intentActivity.getActivityStack();
        ActivityStack activityStack = this.mTargetStack;
        activityStack.mLastPausedActivity = null;
        if (this.mPreferredDisplayId == activityStack.mDisplayId) {
            ActivityStack focusStack = this.mTargetStack.getDisplay().getFocusedStack();
            if (focusStack != null) {
                curTop = focusStack.topRunningNonDelayedActivityLocked(this.mNotTop);
            } else {
                curTop = null;
            }
            TaskRecord topTask = curTop != null ? curTop.getTaskRecord() : null;
            differentTopTask = (topTask == intentActivity.getTaskRecord() && (focusStack == null || topTask == focusStack.topTask())) ? false : true;
        } else {
            differentTopTask = true;
        }
        if (differentTopTask && !this.mAvoidMoveToFront) {
            this.mStartActivity.intent.addFlags(DumpState.DUMP_CHANGES);
            if (this.mSourceRecord == null || (this.mSourceStack.getTopActivity() != null && this.mSourceStack.getTopActivity().getTaskRecord() == this.mSourceRecord.getTaskRecord())) {
                if (this.mLaunchTaskBehind && (activityRecord = this.mSourceRecord) != null) {
                    intentActivity.setTaskToAffiliateWith(activityRecord.getTaskRecord());
                }
                boolean willClearTask = (this.mLaunchFlags & 268468224) == 268468224;
                if (!willClearTask) {
                    ActivityRecord activityRecord2 = this.mStartActivity;
                    ActivityStack launchStack = getLaunchStack(activityRecord2, this.mLaunchFlags, activityRecord2.getTaskRecord(), this.mOptions);
                    TaskRecord intentTask = intentActivity.getTaskRecord();
                    if (launchStack == null || launchStack == this.mTargetStack) {
                        this.mTargetStack.moveTaskToFrontLocked(intentTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "bringingFoundTaskToFront");
                        this.mMovedToFront = true;
                    } else if (!launchStack.inSplitScreenWindowingMode()) {
                        if (launchStack.mDisplayId != this.mTargetStack.mDisplayId) {
                            intentActivity.getTaskRecord().reparent(launchStack, true, 0, true, true, "reparentToDisplay");
                            this.mMovedToFront = true;
                        } else if (launchStack.isActivityTypeHome() && !this.mTargetStack.isActivityTypeHome()) {
                            intentActivity.getTaskRecord().reparent(launchStack, true, 0, true, true, "reparentingHome");
                            this.mMovedToFront = true;
                        }
                    } else {
                        if ((this.mLaunchFlags & 4096) == 0) {
                            this.mTargetStack.moveTaskToFrontLocked(intentTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "bringToFrontInsteadOfAdjacentLaunch");
                        } else {
                            intentTask.reparent(launchStack, true, 0, true, true, "launchToSide");
                        }
                        this.mMovedToFront = launchStack != launchStack.getDisplay().getTopStackInWindowingMode(launchStack.getWindowingMode());
                    }
                    this.mOptions = null;
                    intentActivity.showStartingWindow(null, false, true);
                }
            }
        }
        this.mTargetStack = intentActivity.getActivityStack();
        if (!this.mMovedToFront && this.mDoResume) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Bring to front target: " + this.mTargetStack + " from " + intentActivity);
            }
            this.mTargetStack.moveToFront("intentActivityFound");
        }
        this.mSupervisor.handleNonResizableTaskIfNeeded(intentActivity.getTaskRecord(), 0, 0, this.mTargetStack);
        return (this.mLaunchFlags & DumpState.DUMP_COMPILER_STATS) != 0 ? this.mTargetStack.resetTaskIfNeededLocked(intentActivity, this.mStartActivity) : intentActivity;
    }

    private void setTaskFromIntentActivity(ActivityRecord intentActivity) {
        int i = this.mLaunchFlags;
        if ((i & 268468224) == 268468224) {
            TaskRecord task = intentActivity.getTaskRecord();
            task.performClearTaskLocked();
            this.mReuseTask = task;
            this.mReuseTask.setIntent(this.mStartActivity);
        } else if ((i & 67108864) != 0 || isLaunchModeOneOf(3, 2)) {
            ActivityRecord top = intentActivity.getTaskRecord().performClearTaskLocked(this.mStartActivity, this.mLaunchFlags);
            if (top == null) {
                this.mAddingToTask = true;
                this.mStartActivity.setTask(null);
                this.mSourceRecord = intentActivity;
                TaskRecord task2 = this.mSourceRecord.getTaskRecord();
                if (task2 != null && task2.getStack() == null) {
                    this.mTargetStack = computeStackFocus(this.mSourceRecord, false, this.mLaunchFlags, this.mOptions);
                    this.mTargetStack.addTask(task2, true ^ this.mLaunchTaskBehind, "startActivityUnchecked");
                }
            }
        } else if (this.mStartActivity.mActivityComponent.equals(intentActivity.getTaskRecord().realActivity)) {
            if (((this.mLaunchFlags & 536870912) != 0 || 1 == this.mLaunchMode) && intentActivity.mActivityComponent.equals(this.mStartActivity.mActivityComponent)) {
                if (intentActivity.frontOfTask) {
                    intentActivity.getTaskRecord().setIntent(this.mStartActivity);
                }
                deliverNewIntent(intentActivity);
            } else if (!intentActivity.getTaskRecord().isSameIntentFilter(this.mStartActivity)) {
                this.mAddingToTask = true;
                this.mSourceRecord = intentActivity;
            }
        } else if ((this.mLaunchFlags & DumpState.DUMP_COMPILER_STATS) == 0) {
            this.mAddingToTask = true;
            this.mSourceRecord = intentActivity;
        } else if (!intentActivity.getTaskRecord().rootWasReset) {
            intentActivity.getTaskRecord().setIntent(this.mStartActivity);
        }
    }

    private void resumeTargetStackIfNeeded() {
        if (this.mDoResume) {
            this.mRootActivityContainer.resumeFocusedStacksTopActivities(this.mTargetStack, null, this.mOptions);
        } else {
            ActivityOptions.abort(this.mOptions);
        }
        this.mRootActivityContainer.updateUserStack(this.mStartActivity.mUserId, this.mTargetStack);
    }

    private int setTaskFromReuseOrCreateNewTask(TaskRecord taskToAffiliate) {
        TaskRecord taskRecord;
        if (this.mRestrictedBgActivity && (((taskRecord = this.mReuseTask) == null || !taskRecord.containsAppUid(this.mCallingUid)) && handleBackgroundActivityAbort(this.mStartActivity))) {
            return HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION;
        }
        this.mTargetStack = computeStackFocus(this.mStartActivity, true, this.mLaunchFlags, this.mOptions);
        TaskRecord taskRecord2 = this.mReuseTask;
        if (taskRecord2 == null) {
            boolean toTop = (this.mLaunchTaskBehind || this.mAvoidMoveToFront) ? false : true;
            ActivityStack activityStack = this.mTargetStack;
            int nextTaskIdForUserLocked = this.mSupervisor.getNextTaskIdForUserLocked(this.mStartActivity.mUserId);
            ActivityInfo activityInfo = this.mNewTaskInfo;
            if (activityInfo == null) {
                activityInfo = this.mStartActivity.info;
            }
            ActivityInfo activityInfo2 = activityInfo;
            Intent intent = this.mNewTaskIntent;
            if (intent == null) {
                intent = this.mIntent;
            }
            TaskRecord task = activityStack.createTaskRecord(nextTaskIdForUserLocked, activityInfo2, intent, this.mVoiceSession, this.mVoiceInteractor, toTop, this.mStartActivity, this.mSourceRecord, this.mOptions);
            addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask - mReuseTask");
            updateBounds(this.mStartActivity.getTaskRecord(), this.mLaunchParams.mBounds);
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityTaskManager", "Starting new activity " + this.mStartActivity + " in new task " + this.mStartActivity.getTaskRecord());
            }
        } else {
            addOrReparentStartingActivity(taskRecord2, "setTaskFromReuseOrCreateNewTask");
        }
        if (taskToAffiliate != null) {
            this.mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
        }
        if (this.mService.getLockTaskController().isLockTaskModeViolation(this.mStartActivity.getTaskRecord())) {
            Slog.e("ActivityTaskManager", "Attempted Lock Task Mode violation mStartActivity=" + this.mStartActivity);
            return 101;
        }
        if (this.mDoResume) {
            this.mTargetStack.moveToFront("reuseOrNewTask");
        }
        return 0;
    }

    private void deliverNewIntent(ActivityRecord activity) {
        if (this.mIntentDelivered) {
            return;
        }
        ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, activity, activity.getTaskRecord());
        activity.deliverNewIntentLocked(this.mCallingUid, this.mStartActivity.intent, this.mStartActivity.launchedFromPackage);
        this.mIntentDelivered = true;
    }

    /* JADX WARN: Removed duplicated region for block: B:73:0x015b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int setTaskFromSourceRecord() {
        /*
            Method dump skipped, instructions count: 394
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStarter.setTaskFromSourceRecord():int");
    }

    private int setTaskFromInTask() {
        if (this.mService.getLockTaskController().isLockTaskModeViolation(this.mInTask)) {
            Slog.e("ActivityTaskManager", "Attempted Lock Task Mode violation mStartActivity=" + this.mStartActivity);
            return 101;
        }
        this.mTargetStack = this.mInTask.getStack();
        ActivityRecord top = this.mInTask.getTopActivity();
        if (top != null && top.mActivityComponent.equals(this.mStartActivity.mActivityComponent) && top.mUserId == this.mStartActivity.mUserId && ((this.mLaunchFlags & 536870912) != 0 || isLaunchModeOneOf(1, 2))) {
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
                ActivityStack stack = this.mRootActivityContainer.getLaunchStack(null, null, this.mInTask, true);
                if (stack != this.mInTask.getStack()) {
                    this.mInTask.reparent(stack, true, 1, false, true, "inTaskToFront");
                    this.mTargetStack = this.mInTask.getStack();
                }
                updateBounds(this.mInTask, this.mLaunchParams.mBounds);
            }
            this.mTargetStack.moveTaskToFrontLocked(this.mInTask, this.mNoAnimation, this.mOptions, this.mStartActivity.appTimeTracker, "inTaskToFront");
            addOrReparentStartingActivity(this.mInTask, "setTaskFromInTask");
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityTaskManager", "Starting new activity " + this.mStartActivity + " in explicit task " + this.mStartActivity.getTaskRecord());
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

    private int setTaskToCurrentTopOrCreateNewTask() {
        this.mTargetStack = computeStackFocus(this.mStartActivity, false, this.mLaunchFlags, this.mOptions);
        if (this.mDoResume) {
            this.mTargetStack.moveToFront("addingToTopTask");
        }
        ActivityRecord prev = this.mTargetStack.getTopActivity();
        if (this.mRestrictedBgActivity && prev == null && handleBackgroundActivityAbort(this.mStartActivity)) {
            return HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION;
        }
        TaskRecord task = prev != null ? prev.getTaskRecord() : this.mTargetStack.createTaskRecord(this.mSupervisor.getNextTaskIdForUserLocked(this.mStartActivity.mUserId), this.mStartActivity.info, this.mIntent, null, null, true, this.mStartActivity, this.mSourceRecord, this.mOptions);
        if (!this.mRestrictedBgActivity || prev == null || task.containsAppUid(this.mCallingUid) || !handleBackgroundActivityAbort(this.mStartActivity)) {
            addOrReparentStartingActivity(task, "setTaskToCurrentTopOrCreateNewTask");
            this.mTargetStack.positionChildWindowContainerAtTop(task);
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityTaskManager", "Starting new activity " + this.mStartActivity + " in new guessed " + this.mStartActivity.getTaskRecord());
            }
            return 0;
        }
        return HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION;
    }

    private void addOrReparentStartingActivity(TaskRecord parent, String reason) {
        if (this.mStartActivity.getTaskRecord() == null || this.mStartActivity.getTaskRecord() == parent) {
            parent.addActivityToTop(this.mStartActivity);
        } else {
            this.mStartActivity.reparent(parent, parent.mActivities.size(), reason);
        }
    }

    private int adjustLaunchFlagsToDocumentMode(ActivityRecord r, boolean launchSingleInstance, boolean launchSingleTask, int launchFlags) {
        if ((launchFlags & DumpState.DUMP_FROZEN) != 0 && (launchSingleInstance || launchSingleTask)) {
            Slog.i("ActivityTaskManager", "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is \"singleInstance\" or \"singleTask\"");
            return launchFlags & (-134742017);
        }
        int i = r.info.documentLaunchMode;
        if (i != 0) {
            if (i != 1) {
                if (i != 2) {
                    if (i == 3) {
                        return launchFlags & (-134217729);
                    }
                    return launchFlags;
                }
                return launchFlags | DumpState.DUMP_FROZEN;
            }
            return launchFlags | DumpState.DUMP_FROZEN;
        }
        return launchFlags;
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, int launchFlags, ActivityOptions aOptions) {
        TaskRecord task = r.getTaskRecord();
        ActivityStack stack = getLaunchStack(r, launchFlags, task, aOptions);
        if (stack != null) {
            return stack;
        }
        ActivityStack currentStack = task != null ? task.getStack() : null;
        ActivityStack focusedStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        if (currentStack != null) {
            if (focusedStack != currentStack) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "computeStackFocus: Setting focused stack to r=" + r + " task=" + task);
                }
            } else if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityTaskManager", "computeStackFocus: Focused stack already=" + focusedStack);
            }
            return currentStack;
        } else if (canLaunchIntoFocusedStack(r, newTask)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityTaskManager", "computeStackFocus: Have a focused stack=" + focusedStack);
            }
            return focusedStack;
        } else {
            int i = this.mPreferredDisplayId;
            if (i != 0 && (stack = this.mRootActivityContainer.getValidLaunchStackOnDisplay(i, r, aOptions, this.mLaunchParams)) == null) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "computeStackFocus: Can't launch on mPreferredDisplayId=" + this.mPreferredDisplayId + ", looking on all displays.");
                }
                stack = this.mRootActivityContainer.getNextValidLaunchStack(r, this.mPreferredDisplayId);
            }
            if (stack == null) {
                stack = this.mRootActivityContainer.getLaunchStack(r, aOptions, task, true);
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityTaskManager", "computeStackFocus: New stack r=" + r + " stackId=" + stack.mStackId);
            }
            return stack;
        }
    }

    private boolean canLaunchIntoFocusedStack(ActivityRecord r, boolean newTask) {
        boolean canUseFocusedStack;
        ActivityStack focusedStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        if (focusedStack.isActivityTypeAssistant()) {
            canUseFocusedStack = r.isActivityTypeAssistant();
        } else {
            int windowingMode = focusedStack.getWindowingMode();
            if (windowingMode == 1) {
                canUseFocusedStack = true;
            } else if (windowingMode == 3 || windowingMode == 4) {
                canUseFocusedStack = r.supportsSplitScreenWindowingMode();
            } else if (windowingMode == 5) {
                canUseFocusedStack = r.supportsFreeform();
            } else {
                canUseFocusedStack = !focusedStack.isOnHomeDisplay() && r.canBeLaunchedOnDisplay(focusedStack.mDisplayId);
            }
        }
        return canUseFocusedStack && !newTask && this.mPreferredDisplayId == focusedStack.mDisplayId;
    }

    private ActivityStack getLaunchStack(ActivityRecord r, int launchFlags, TaskRecord task, ActivityOptions aOptions) {
        TaskRecord taskRecord = this.mReuseTask;
        if (taskRecord != null) {
            return taskRecord.getStack();
        }
        boolean z = true;
        if ((launchFlags & 4096) == 0 || this.mPreferredDisplayId != 0) {
            if (aOptions != null && aOptions.getAvoidMoveToFront()) {
                z = false;
            }
            boolean onTop = z;
            ActivityStack stack = this.mRootActivityContainer.getLaunchStack(r, aOptions, task, onTop, this.mLaunchParams, this.mRequest.realCallingPid, this.mRequest.realCallingUid);
            return stack;
        }
        ActivityStack focusedStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        ActivityStack parentStack = task != null ? task.getStack() : focusedStack;
        if (parentStack != focusedStack) {
            return parentStack;
        }
        if (focusedStack != null && task == focusedStack.topTask()) {
            return focusedStack;
        }
        if (parentStack != null && parentStack.inSplitScreenPrimaryWindowingMode()) {
            int activityType = this.mRootActivityContainer.resolveActivityType(r, this.mOptions, task);
            return parentStack.getDisplay().getOrCreateStack(4, activityType, true);
        }
        ActivityStack dockedStack = this.mRootActivityContainer.getDefaultDisplay().getSplitScreenPrimaryStack();
        if (dockedStack != null && !dockedStack.shouldBeVisible(r)) {
            return this.mRootActivityContainer.getLaunchStack(r, aOptions, task, true);
        }
        return dockedStack;
    }

    private boolean isLaunchModeOneOf(int mode1, int mode2) {
        int i = this.mLaunchMode;
        return mode1 == i || mode2 == i;
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

    @VisibleForTesting
    int getCallingUid() {
        return this.mRequest.callingUid;
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
        Request request = this.mRequest;
        request.mayWait = true;
        request.userId = userId;
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
    public ActivityStarter setAllowBackgroundActivityStart(boolean allowBackgroundActivityStart) {
        this.mRequest.allowBackgroundActivityStart = allowBackgroundActivityStart;
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        String prefix2 = prefix + "  ";
        pw.print(prefix2);
        pw.print("mCurrentUser=");
        pw.println(this.mRootActivityContainer.mCurrentUser);
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
