package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.XmlUtils;
import com.android.server.am.ActivityStack;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.DumpState;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.StackWindowController;
import com.android.server.wm.TaskWindowContainerController;
import com.android.server.wm.TaskWindowContainerListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskRecord extends ConfigurationContainer implements TaskWindowContainerListener {
    private static final String ATTR_AFFINITY = "affinity";
    private static final String ATTR_ASKEDCOMPATMODE = "asked_compat_mode";
    private static final String ATTR_AUTOREMOVERECENTS = "auto_remove_recents";
    private static final String ATTR_CALLING_PACKAGE = "calling_package";
    private static final String ATTR_CALLING_UID = "calling_uid";
    private static final String ATTR_EFFECTIVE_UID = "effective_uid";
    private static final String ATTR_LASTDESCRIPTION = "last_description";
    private static final String ATTR_LASTTIMEMOVED = "last_time_moved";
    private static final String ATTR_MIN_HEIGHT = "min_height";
    private static final String ATTR_MIN_WIDTH = "min_width";
    private static final String ATTR_NEVERRELINQUISH = "never_relinquish_identity";
    private static final String ATTR_NEXT_AFFILIATION = "next_affiliation";
    private static final String ATTR_NON_FULLSCREEN_BOUNDS = "non_fullscreen_bounds";
    private static final String ATTR_ORIGACTIVITY = "orig_activity";
    private static final String ATTR_PERSIST_TASK_VERSION = "persist_task_version";
    private static final String ATTR_PREV_AFFILIATION = "prev_affiliation";
    private static final String ATTR_REALACTIVITY = "real_activity";
    private static final String ATTR_REALACTIVITY_SUSPENDED = "real_activity_suspended";
    private static final String ATTR_RESIZE_MODE = "resize_mode";
    private static final String ATTR_ROOTHASRESET = "root_has_reset";
    private static final String ATTR_ROOT_AFFINITY = "root_affinity";
    private static final String ATTR_SUPPORTS_PICTURE_IN_PICTURE = "supports_picture_in_picture";
    private static final String ATTR_TASKID = "task_id";
    @Deprecated
    private static final String ATTR_TASKTYPE = "task_type";
    private static final String ATTR_TASK_AFFILIATION = "task_affiliation";
    private static final String ATTR_TASK_AFFILIATION_COLOR = "task_affiliation_color";
    private static final String ATTR_USERID = "user_id";
    private static final String ATTR_USER_SETUP_COMPLETE = "user_setup_complete";
    private static final int INVALID_MIN_SIZE = -1;
    static final int INVALID_TASK_ID = -1;
    static final int LOCK_TASK_AUTH_DONT_LOCK = 0;
    static final int LOCK_TASK_AUTH_LAUNCHABLE = 2;
    static final int LOCK_TASK_AUTH_LAUNCHABLE_PRIV = 4;
    static final int LOCK_TASK_AUTH_PINNABLE = 1;
    static final int LOCK_TASK_AUTH_WHITELISTED = 3;
    private static final int PERSIST_TASK_VERSION = 1;
    static final int REPARENT_KEEP_STACK_AT_FRONT = 1;
    static final int REPARENT_LEAVE_STACK_IN_PLACE = 2;
    static final int REPARENT_MOVE_STACK_TO_FRONT = 0;
    private static final String TAG = "ActivityManager";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_ADD_REMOVE = "ActivityManager";
    private static final String TAG_AFFINITYINTENT = "affinity_intent";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_LOCKTASK = "ActivityManager";
    private static final String TAG_RECENTS = "ActivityManager";
    private static final String TAG_TASKS = "ActivityManager";
    private static TaskRecordFactory sTaskRecordFactory;
    String affinity;
    Intent affinityIntent;
    boolean askedCompatMode;
    boolean autoRemoveRecents;
    int effectiveUid;
    boolean hasBeenVisible;
    boolean inRecents;
    Intent intent;
    boolean isAvailable;
    boolean isPersistable;
    long lastActiveTime;
    CharSequence lastDescription;
    ActivityManager.TaskDescription lastTaskDescription;
    final ArrayList<ActivityRecord> mActivities;
    int mAffiliatedTaskColor;
    int mAffiliatedTaskId;
    String mCallingPackage;
    int mCallingUid;
    Rect mLastNonFullscreenBounds;
    long mLastTimeMoved;
    int mLayerRank;
    int mLockTaskAuth;
    int mLockTaskUid;
    int mMinHeight;
    int mMinWidth;
    private boolean mNeverRelinquishIdentity;
    TaskRecord mNextAffiliate;
    int mNextAffiliateTaskId;
    TaskRecord mPrevAffiliate;
    int mPrevAffiliateTaskId;
    int mResizeMode;
    private boolean mReuseTask;
    private ProcessRecord mRootProcess;
    final ActivityManagerService mService;
    private ActivityStack mStack;
    private boolean mSupportsPictureInPicture;
    private Configuration mTmpConfig;
    private final Rect mTmpNonDecorBounds;
    private final Rect mTmpRect;
    private final Rect mTmpStableBounds;
    boolean mUserSetupComplete;
    private TaskWindowContainerController mWindowContainerController;
    int maxRecents;
    int numFullscreen;
    ComponentName origActivity;
    ComponentName realActivity;
    boolean realActivitySuspended;
    String rootAffinity;
    boolean rootWasReset;
    String stringName;
    final int taskId;
    int userId;
    final IVoiceInteractor voiceInteractor;
    final IVoiceInteractionSession voiceSession;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface ReparentMoveStackMode {
    }

    TaskRecord(ActivityManagerService service, int _taskId, ActivityInfo info, Intent _intent, IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor) {
        this.mLockTaskAuth = 1;
        this.mLockTaskUid = -1;
        this.lastTaskDescription = new ActivityManager.TaskDescription();
        this.isPersistable = false;
        this.mLastTimeMoved = System.currentTimeMillis();
        this.mNeverRelinquishIdentity = true;
        this.mReuseTask = false;
        this.mPrevAffiliateTaskId = -1;
        this.mNextAffiliateTaskId = -1;
        this.mTmpStableBounds = new Rect();
        this.mTmpNonDecorBounds = new Rect();
        this.mTmpRect = new Rect();
        this.mLastNonFullscreenBounds = null;
        this.mLayerRank = -1;
        this.mTmpConfig = new Configuration();
        this.mService = service;
        this.userId = UserHandle.getUserId(info.applicationInfo.uid);
        this.taskId = _taskId;
        this.lastActiveTime = SystemClock.elapsedRealtime();
        this.mAffiliatedTaskId = _taskId;
        this.voiceSession = _voiceSession;
        this.voiceInteractor = _voiceInteractor;
        this.isAvailable = true;
        this.mActivities = new ArrayList<>();
        this.mCallingUid = info.applicationInfo.uid;
        this.mCallingPackage = info.packageName;
        setIntent(_intent, info);
        setMinDimensions(info);
        touchActiveTime();
        this.mService.mTaskChangeNotificationController.notifyTaskCreated(_taskId, this.realActivity);
    }

    TaskRecord(ActivityManagerService service, int _taskId, ActivityInfo info, Intent _intent, ActivityManager.TaskDescription _taskDescription) {
        this.mLockTaskAuth = 1;
        this.mLockTaskUid = -1;
        this.lastTaskDescription = new ActivityManager.TaskDescription();
        this.isPersistable = false;
        this.mLastTimeMoved = System.currentTimeMillis();
        this.mNeverRelinquishIdentity = true;
        this.mReuseTask = false;
        this.mPrevAffiliateTaskId = -1;
        this.mNextAffiliateTaskId = -1;
        this.mTmpStableBounds = new Rect();
        this.mTmpNonDecorBounds = new Rect();
        this.mTmpRect = new Rect();
        this.mLastNonFullscreenBounds = null;
        this.mLayerRank = -1;
        this.mTmpConfig = new Configuration();
        this.mService = service;
        this.userId = UserHandle.getUserId(info.applicationInfo.uid);
        this.taskId = _taskId;
        this.lastActiveTime = SystemClock.elapsedRealtime();
        this.mAffiliatedTaskId = _taskId;
        this.voiceSession = null;
        this.voiceInteractor = null;
        this.isAvailable = true;
        this.mActivities = new ArrayList<>();
        this.mCallingUid = info.applicationInfo.uid;
        this.mCallingPackage = info.packageName;
        setIntent(_intent, info);
        setMinDimensions(info);
        this.isPersistable = true;
        this.maxRecents = Math.min(Math.max(info.maxRecents, 1), ActivityManager.getMaxAppRecentsLimitStatic());
        this.lastTaskDescription = _taskDescription;
        touchActiveTime();
        this.mService.mTaskChangeNotificationController.notifyTaskCreated(_taskId, this.realActivity);
    }

    TaskRecord(ActivityManagerService service, int _taskId, Intent _intent, Intent _affinityIntent, String _affinity, String _rootAffinity, ComponentName _realActivity, ComponentName _origActivity, boolean _rootWasReset, boolean _autoRemoveRecents, boolean _askedCompatMode, int _userId, int _effectiveUid, String _lastDescription, ArrayList<ActivityRecord> activities, long lastTimeMoved, boolean neverRelinquishIdentity, ActivityManager.TaskDescription _lastTaskDescription, int taskAffiliation, int prevTaskId, int nextTaskId, int taskAffiliationColor, int callingUid, String callingPackage, int resizeMode, boolean supportsPictureInPicture, boolean _realActivitySuspended, boolean userSetupComplete, int minWidth, int minHeight) {
        this.mLockTaskAuth = 1;
        this.mLockTaskUid = -1;
        this.lastTaskDescription = new ActivityManager.TaskDescription();
        this.isPersistable = false;
        this.mLastTimeMoved = System.currentTimeMillis();
        this.mNeverRelinquishIdentity = true;
        this.mReuseTask = false;
        this.mPrevAffiliateTaskId = -1;
        this.mNextAffiliateTaskId = -1;
        this.mTmpStableBounds = new Rect();
        this.mTmpNonDecorBounds = new Rect();
        this.mTmpRect = new Rect();
        this.mLastNonFullscreenBounds = null;
        this.mLayerRank = -1;
        this.mTmpConfig = new Configuration();
        this.mService = service;
        this.taskId = _taskId;
        this.intent = _intent;
        this.affinityIntent = _affinityIntent;
        this.affinity = _affinity;
        this.rootAffinity = _rootAffinity;
        this.voiceSession = null;
        this.voiceInteractor = null;
        this.realActivity = _realActivity;
        this.realActivitySuspended = _realActivitySuspended;
        this.origActivity = _origActivity;
        this.rootWasReset = _rootWasReset;
        this.isAvailable = true;
        this.autoRemoveRecents = _autoRemoveRecents;
        this.askedCompatMode = _askedCompatMode;
        this.userId = _userId;
        this.mUserSetupComplete = userSetupComplete;
        this.effectiveUid = _effectiveUid;
        this.lastActiveTime = SystemClock.elapsedRealtime();
        this.lastDescription = _lastDescription;
        this.mActivities = activities;
        this.mLastTimeMoved = lastTimeMoved;
        this.mNeverRelinquishIdentity = neverRelinquishIdentity;
        this.lastTaskDescription = _lastTaskDescription;
        this.mAffiliatedTaskId = taskAffiliation;
        this.mAffiliatedTaskColor = taskAffiliationColor;
        this.mPrevAffiliateTaskId = prevTaskId;
        this.mNextAffiliateTaskId = nextTaskId;
        this.mCallingUid = callingUid;
        this.mCallingPackage = callingPackage;
        this.mResizeMode = resizeMode;
        this.mSupportsPictureInPicture = supportsPictureInPicture;
        this.mMinWidth = minWidth;
        this.mMinHeight = minHeight;
        this.mService.mTaskChangeNotificationController.notifyTaskCreated(_taskId, this.realActivity);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskWindowContainerController getWindowContainerController() {
        return this.mWindowContainerController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createWindowContainer(boolean onTop, boolean showForAllUsers) {
        if (this.mWindowContainerController != null) {
            throw new IllegalArgumentException("Window container=" + this.mWindowContainerController + " already created for task=" + this);
        }
        Rect bounds = updateOverrideConfigurationFromLaunchBounds();
        setWindowContainerController(new TaskWindowContainerController(this.taskId, this, getStack().getWindowContainerController(), this.userId, bounds, this.mResizeMode, this.mSupportsPictureInPicture, onTop, showForAllUsers, this.lastTaskDescription));
    }

    @VisibleForTesting
    protected void setWindowContainerController(TaskWindowContainerController controller) {
        if (this.mWindowContainerController != null) {
            throw new IllegalArgumentException("Window container=" + this.mWindowContainerController + " already created for task=" + this);
        }
        this.mWindowContainerController = controller;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeWindowContainer() {
        this.mService.getLockTaskController().clearLockedTask(this);
        this.mWindowContainerController.removeContainer();
        if (!getWindowConfiguration().persistTaskBounds()) {
            updateOverrideConfiguration(null);
        }
        this.mService.mTaskChangeNotificationController.notifyTaskRemoved(this.taskId);
        this.mWindowContainerController = null;
    }

    @Override // com.android.server.wm.TaskWindowContainerListener
    public void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {
        this.mService.mTaskChangeNotificationController.notifyTaskSnapshotChanged(this.taskId, snapshot);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResizeMode(int resizeMode) {
        if (this.mResizeMode == resizeMode) {
            return;
        }
        this.mResizeMode = resizeMode;
        this.mWindowContainerController.setResizeable(resizeMode);
        this.mService.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
        this.mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskDockedResizing(boolean resizing) {
        this.mWindowContainerController.setTaskDockedResizing(resizing);
    }

    @Override // com.android.server.wm.TaskWindowContainerListener
    public void requestResize(Rect bounds, int resizeMode) {
        this.mService.resizeTask(this.taskId, bounds, resizeMode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resize(Rect bounds, int resizeMode, boolean preserveWindow, boolean deferResume) {
        ActivityRecord r;
        this.mService.mWindowManager.deferSurfaceLayout();
        try {
            if (!isResizeable()) {
                Slog.w("ActivityManager", "resizeTask: task " + this + " not resizeable.");
                return true;
            }
            boolean forced = (resizeMode & 2) != 0;
            if (!equivalentOverrideBounds(bounds) || forced) {
                if (this.mWindowContainerController == null) {
                    updateOverrideConfiguration(bounds);
                    if (!inFreeformWindowingMode()) {
                        this.mService.mStackSupervisor.restoreRecentTaskLocked(this, null, false);
                    }
                    return true;
                } else if (canResizeToBounds(bounds)) {
                    Trace.traceBegin(64L, "am.resizeTask_" + this.taskId);
                    boolean updatedConfig = updateOverrideConfiguration(bounds);
                    boolean kept = true;
                    if (updatedConfig && (r = topRunningActivityLocked()) != null && !deferResume) {
                        kept = r.ensureActivityConfiguration(0, preserveWindow);
                        this.mService.mStackSupervisor.ensureActivitiesVisibleLocked(r, 0, false);
                        if (!kept) {
                            this.mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                        }
                    }
                    this.mWindowContainerController.resize(kept, forced);
                    Trace.traceEnd(64L);
                    return kept;
                } else {
                    return true;
                }
            }
            return true;
        } finally {
            this.mService.mWindowManager.continueSurfaceLayout();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizeWindowContainer() {
        this.mWindowContainerController.resize(false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getWindowContainerBounds(Rect bounds) {
        this.mWindowContainerController.getBounds(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reparent(ActivityStack preferredStack, boolean toTop, int moveStackMode, boolean animate, boolean deferResume, String reason) {
        return reparent(preferredStack, toTop ? Integer.MAX_VALUE : 0, moveStackMode, animate, deferResume, true, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reparent(ActivityStack preferredStack, boolean toTop, int moveStackMode, boolean animate, boolean deferResume, boolean schedulePictureInPictureModeChange, String reason) {
        return reparent(preferredStack, toTop ? Integer.MAX_VALUE : 0, moveStackMode, animate, deferResume, schedulePictureInPictureModeChange, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reparent(ActivityStack preferredStack, int position, int moveStackMode, boolean animate, boolean deferResume, String reason) {
        return reparent(preferredStack, position, moveStackMode, animate, deferResume, true, reason);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:108:0x014f  */
    /* JADX WARN: Removed duplicated region for block: B:112:0x0159  */
    /* JADX WARN: Removed duplicated region for block: B:113:0x015b  */
    /* JADX WARN: Removed duplicated region for block: B:116:0x0162  */
    /* JADX WARN: Removed duplicated region for block: B:122:0x0175  */
    /* JADX WARN: Removed duplicated region for block: B:123:0x0176  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x01cd  */
    /* JADX WARN: Removed duplicated region for block: B:157:0x01d9  */
    /* JADX WARN: Removed duplicated region for block: B:161:0x01e7  */
    /* JADX WARN: Removed duplicated region for block: B:164:0x01f3 A[ORIG_RETURN, RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:185:0x0138 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:193:0x00a7 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:195:0x008f A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:198:0x010f A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:204:0x00f9 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:207:? A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:35:0x0081 A[Catch: all -> 0x0071, TRY_LEAVE, TryCatch #5 {all -> 0x0071, blocks: (B:25:0x0063, B:27:0x0069, B:35:0x0081), top: B:191:0x0063 }] */
    /* JADX WARN: Removed duplicated region for block: B:62:0x00d3  */
    /* JADX WARN: Removed duplicated region for block: B:63:0x00d5  */
    /* JADX WARN: Removed duplicated region for block: B:66:0x00db  */
    /* JADX WARN: Removed duplicated region for block: B:72:0x00e5  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x00ec  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x00ee  */
    /* JADX WARN: Removed duplicated region for block: B:93:0x011c  */
    /* JADX WARN: Removed duplicated region for block: B:98:0x012e  */
    /* JADX WARN: Type inference failed for: r3v10 */
    /* JADX WARN: Type inference failed for: r3v7 */
    /* JADX WARN: Type inference failed for: r3v8, types: [int, boolean] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    boolean reparent(com.android.server.am.ActivityStack r28, int r29, int r30, boolean r31, boolean r32, boolean r33, java.lang.String r34) {
        /*
            Method dump skipped, instructions count: 561
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.TaskRecord.reparent(com.android.server.am.ActivityStack, int, int, boolean, boolean, boolean, java.lang.String):boolean");
    }

    private static boolean replaceWindowsOnTaskMove(int sourceWindowingMode, int targetWindowingMode) {
        return sourceWindowingMode == 5 || targetWindowingMode == 5;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelWindowTransition() {
        this.mWindowContainerController.cancelWindowTransition();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.TaskSnapshot getSnapshot(boolean reducedResolution) {
        return this.mService.mWindowManager.getTaskSnapshot(this.taskId, this.userId, reducedResolution);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void touchActiveTime() {
        this.lastActiveTime = SystemClock.elapsedRealtime();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getInactiveDuration() {
        return SystemClock.elapsedRealtime() - this.lastActiveTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setIntent(ActivityRecord r) {
        this.mCallingUid = r.launchedFromUid;
        this.mCallingPackage = r.launchedFromPackage;
        setIntent(r.intent, r.info);
        setLockTaskAuth(r);
    }

    private void setIntent(Intent _intent, ActivityInfo info) {
        if (this.intent == null) {
            this.mNeverRelinquishIdentity = (info.flags & 4096) == 0;
        } else if (this.mNeverRelinquishIdentity) {
            return;
        }
        this.affinity = info.taskAffinity;
        if (this.intent == null) {
            this.rootAffinity = this.affinity;
        }
        this.effectiveUid = info.applicationInfo.uid;
        this.stringName = null;
        if (info.targetActivity == null) {
            if (_intent != null && (_intent.getSelector() != null || _intent.getSourceBounds() != null)) {
                _intent = new Intent(_intent);
                _intent.setSelector(null);
                _intent.setSourceBounds(null);
            }
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.v("ActivityManager", "Setting Intent of " + this + " to " + _intent);
            }
            this.intent = _intent;
            this.realActivity = _intent != null ? _intent.getComponent() : null;
            this.origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setComponent(targetComponent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.v("ActivityManager", "Setting Intent of " + this + " to target " + targetIntent);
                }
                this.intent = targetIntent;
                this.realActivity = targetComponent;
                this.origActivity = _intent.getComponent();
            } else {
                this.intent = null;
                this.realActivity = targetComponent;
                this.origActivity = new ComponentName(info.packageName, info.name);
            }
        }
        int intentFlags = this.intent == null ? 0 : this.intent.getFlags();
        if ((2097152 & intentFlags) != 0) {
            this.rootWasReset = true;
        }
        this.userId = UserHandle.getUserId(info.applicationInfo.uid);
        this.mUserSetupComplete = Settings.Secure.getIntForUser(this.mService.mContext.getContentResolver(), ATTR_USER_SETUP_COMPLETE, 0, this.userId) != 0;
        if ((info.flags & 8192) != 0) {
            this.autoRemoveRecents = true;
        } else if ((532480 & intentFlags) == 524288) {
            if (info.documentLaunchMode != 0) {
                this.autoRemoveRecents = false;
            } else {
                this.autoRemoveRecents = true;
            }
        } else {
            this.autoRemoveRecents = false;
        }
        this.mResizeMode = info.resizeMode;
        this.mSupportsPictureInPicture = info.supportsPictureInPicture();
    }

    private void setMinDimensions(ActivityInfo info) {
        if (info != null && info.windowLayout != null) {
            this.mMinWidth = info.windowLayout.minWidth;
            this.mMinHeight = info.windowLayout.minHeight;
            return;
        }
        this.mMinWidth = -1;
        this.mMinHeight = -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSameIntentFilter(ActivityRecord r) {
        Intent intent = new Intent(r.intent);
        intent.setComponent(r.realActivity);
        return intent.filterEquals(this.intent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean returnsToHomeStack() {
        return this.intent != null && (this.intent.getFlags() & 268451840) == 268451840;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPrevAffiliate(TaskRecord prevAffiliate) {
        this.mPrevAffiliate = prevAffiliate;
        this.mPrevAffiliateTaskId = prevAffiliate == null ? -1 : prevAffiliate.taskId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setNextAffiliate(TaskRecord nextAffiliate) {
        this.mNextAffiliate = nextAffiliate;
        this.mNextAffiliateTaskId = nextAffiliate == null ? -1 : nextAffiliate.taskId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getStack() {
        return (T) this.mStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStack(ActivityStack stack) {
        if (stack != null && !stack.isInStackLocked(this)) {
            throw new IllegalStateException("Task must be added as a Stack child first.");
        }
        ActivityStack oldStack = this.mStack;
        this.mStack = stack;
        if (oldStack != this.mStack) {
            for (int i = getChildCount() - 1; i >= 0; i--) {
                ActivityRecord activity = getChildAt(i);
                if (oldStack != null) {
                    oldStack.onActivityRemovedFromStack(activity);
                }
                if (this.mStack != null) {
                    stack.onActivityAddedToStack(activity);
                }
            }
        }
        onParentChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getStackId() {
        if (this.mStack != null) {
            return this.mStack.mStackId;
        }
        return -1;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected int getChildCount() {
        return this.mActivities.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ActivityRecord getChildAt(int index) {
        return this.mActivities.get(index);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ConfigurationContainer getParent() {
        return this.mStack;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        super.onParentChanged();
        this.mService.mStackSupervisor.updateUIDsPresentOnDisplay();
    }

    private void closeRecentsChain() {
        if (this.mPrevAffiliate != null) {
            this.mPrevAffiliate.setNextAffiliate(this.mNextAffiliate);
        }
        if (this.mNextAffiliate != null) {
            this.mNextAffiliate.setPrevAffiliate(this.mPrevAffiliate);
        }
        setPrevAffiliate(null);
        setNextAffiliate(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removedFromRecents() {
        closeRecentsChain();
        if (this.inRecents) {
            this.inRecents = false;
            this.mService.notifyTaskPersisterLocked(this, false);
        }
        clearRootProcess();
        this.mService.mWindowManager.notifyTaskRemovedFromRecents(this.taskId, this.userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskToAffiliateWith(TaskRecord taskToAffiliateWith) {
        closeRecentsChain();
        this.mAffiliatedTaskId = taskToAffiliateWith.mAffiliatedTaskId;
        this.mAffiliatedTaskColor = taskToAffiliateWith.mAffiliatedTaskColor;
        while (true) {
            if (taskToAffiliateWith.mNextAffiliate == null) {
                break;
            }
            TaskRecord nextRecents = taskToAffiliateWith.mNextAffiliate;
            if (nextRecents.mAffiliatedTaskId != this.mAffiliatedTaskId) {
                Slog.e("ActivityManager", "setTaskToAffiliateWith: nextRecents=" + nextRecents + " affilTaskId=" + nextRecents.mAffiliatedTaskId + " should be " + this.mAffiliatedTaskId);
                if (nextRecents.mPrevAffiliate == taskToAffiliateWith) {
                    nextRecents.setPrevAffiliate(null);
                }
                taskToAffiliateWith.setNextAffiliate(null);
            } else {
                taskToAffiliateWith = nextRecents;
            }
        }
        taskToAffiliateWith.setNextAffiliate(this);
        setPrevAffiliate(taskToAffiliateWith);
        setNextAffiliate(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Intent getBaseIntent() {
        return this.intent != null ? this.intent : this.affinityIntent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getRootActivity() {
        for (int i = 0; i < this.mActivities.size(); i++) {
            ActivityRecord r = this.mActivities.get(i);
            if (!r.finishing) {
                return r;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getTopActivity() {
        return getTopActivity(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getTopActivity(boolean includeOverlays) {
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = this.mActivities.get(i);
            if (!r.finishing && (includeOverlays || !r.mTaskOverlay)) {
                return r;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityLocked() {
        if (this.mStack != null) {
            for (int activityNdx = this.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = this.mActivities.get(activityNdx);
                if (!r.finishing && r.okToShowLocked()) {
                    return r;
                }
            }
            return null;
        }
        return null;
    }

    boolean isVisible() {
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = this.mActivities.get(i);
            if (r.visible) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getAllRunningVisibleActivitiesLocked(ArrayList<ActivityRecord> outActivities) {
        if (this.mStack != null) {
            for (int activityNdx = this.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = this.mActivities.get(activityNdx);
                ActivityStackSupervisor supervisor = this.mService.mStackSupervisor;
                if (supervisor.isPhoneStack(this.mStack)) {
                    if (!r.finishing && r.okToShowLocked()) {
                        outActivities.add(r);
                    }
                } else if (!r.finishing && r.okToShowLocked() && r.visibleIgnoringKeyguard) {
                    outActivities.add(r);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityWithStartingWindowLocked() {
        if (this.mStack != null) {
            for (int activityNdx = this.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = this.mActivities.get(activityNdx);
                if (r.mStartingWindowState == 1 && !r.finishing && r.okToShowLocked()) {
                    return r;
                }
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getNumRunningActivities(TaskActivitiesReport reportOut) {
        reportOut.reset();
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = this.mActivities.get(i);
            if (!r.finishing) {
                reportOut.base = r;
                reportOut.numActivities++;
                if (reportOut.top == null || reportOut.top.isState(ActivityStack.ActivityState.INITIALIZING)) {
                    reportOut.top = r;
                    reportOut.numRunning = 0;
                }
                if (r.app != null && r.app.thread != null) {
                    reportOut.numRunning++;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToShowLocked() {
        return this.mService.mStackSupervisor.isCurrentProfileLocked(this.userId) || topRunningActivityLocked() != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void setFrontOfTask() {
        int numActivities = this.mActivities.size();
        boolean foundFront = false;
        for (int activityNdx = 0; activityNdx < numActivities; activityNdx++) {
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (foundFront || r.finishing) {
                r.frontOfTask = false;
            } else {
                r.frontOfTask = true;
                foundFront = true;
            }
        }
        if (!foundFront && numActivities > 0) {
            this.mActivities.get(0).frontOfTask = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void moveActivityToFrontLocked(ActivityRecord newTop) {
        if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i("ActivityManager", "Removing and adding activity " + newTop + " to stack at top callers=" + Debug.getCallers(4));
        }
        this.mActivities.remove(newTop);
        this.mActivities.add(newTop);
        this.mWindowContainerController.positionChildAtTop(newTop.mWindowContainerController);
        updateEffectiveIntent();
        setFrontOfTask();
    }

    void addActivityAtBottom(ActivityRecord r) {
        addActivityAtIndex(0, r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addActivityToTop(ActivityRecord r) {
        addActivityAtIndex(this.mActivities.size(), r);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public int getActivityType() {
        int applicationType = super.getActivityType();
        if (applicationType != 0 || this.mActivities.isEmpty()) {
            return applicationType;
        }
        return this.mActivities.get(0).getActivityType();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addActivityAtIndex(int index, ActivityRecord r) {
        TaskRecord task = r.getTask();
        if (task != null && task != this) {
            throw new IllegalArgumentException("Can not add r= to task=" + this + " current parent=" + task);
        }
        r.setTask(this);
        if (!this.mActivities.remove(r) && r.fullscreen) {
            this.numFullscreen++;
        }
        if (this.mActivities.isEmpty()) {
            if (r.getActivityType() == 0) {
                r.setActivityType(1);
            }
            setActivityType(r.getActivityType());
            this.isPersistable = r.isPersistable();
            this.mCallingUid = r.launchedFromUid;
            this.mCallingPackage = r.launchedFromPackage;
            this.maxRecents = Math.min(Math.max(r.info.maxRecents, 1), ActivityManager.getMaxAppRecentsLimitStatic());
        } else {
            r.setActivityType(getActivityType());
        }
        int size = this.mActivities.size();
        if (index == size && size > 0) {
            ActivityRecord top = this.mActivities.get(size - 1);
            if (top.mTaskOverlay) {
                index--;
            }
        }
        int index2 = Math.min(size, index);
        this.mActivities.add(index2, r);
        updateEffectiveIntent();
        if (r.isPersistable()) {
            this.mService.notifyTaskPersisterLocked(this, false);
        }
        updateOverrideConfigurationFromLaunchBounds();
        AppWindowContainerController appController = r.getWindowContainerController();
        if (appController != null) {
            this.mWindowContainerController.positionChildAt(appController, index2);
        }
        this.mService.mStackSupervisor.updateUIDsPresentOnDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeActivity(ActivityRecord r) {
        return removeActivity(r, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeActivity(ActivityRecord r, boolean reparenting) {
        if (r.getTask() != this) {
            throw new IllegalArgumentException("Activity=" + r + " does not belong to task=" + this);
        }
        r.setTask(null, reparenting);
        if (this.mActivities.remove(r) && r.fullscreen) {
            this.numFullscreen--;
        }
        if (r.isPersistable()) {
            this.mService.notifyTaskPersisterLocked(this, false);
        }
        if (inPinnedWindowingMode()) {
            this.mService.mTaskChangeNotificationController.notifyTaskStackChanged();
        }
        if (this.mActivities.isEmpty()) {
            return !this.mReuseTask;
        }
        updateEffectiveIntent();
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onlyHasTaskOverlayActivities(boolean excludeFinishing) {
        int count = 0;
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = this.mActivities.get(i);
            if (!excludeFinishing || !r.finishing) {
                if (!r.mTaskOverlay) {
                    return false;
                }
                count++;
            }
        }
        return count > 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean autoRemoveFromRecents() {
        return this.autoRemoveRecents || (this.mActivities.isEmpty() && !this.hasBeenVisible);
    }

    final void performClearTaskAtIndexLocked(int activityNdx, boolean pauseImmediately, String reason) {
        int numActivities = this.mActivities.size();
        while (activityNdx < numActivities) {
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (!r.finishing) {
                if (this.mStack == null) {
                    r.takeFromHistory();
                    this.mActivities.remove(activityNdx);
                    activityNdx--;
                    numActivities--;
                } else if (this.mStack.finishActivityLocked(r, 0, null, reason, false, pauseImmediately)) {
                    activityNdx--;
                    numActivities--;
                }
            }
            activityNdx++;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performClearTaskLocked() {
        this.mReuseTask = true;
        performClearTaskAtIndexLocked(0, false, "clear-task-all");
        this.mReuseTask = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord performClearTaskForReuseLocked(ActivityRecord newR, int launchFlags) {
        this.mReuseTask = true;
        ActivityRecord result = performClearTaskLocked(newR, launchFlags);
        this.mReuseTask = false;
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord performClearTaskLocked(ActivityRecord newR, int launchFlags) {
        int numActivities = this.mActivities.size();
        int activityNdx = numActivities - 1;
        while (activityNdx >= 0) {
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (r.finishing || !r.realActivity.equals(newR.realActivity)) {
                activityNdx--;
            } else {
                while (true) {
                    activityNdx++;
                    if (activityNdx >= numActivities) {
                        break;
                    }
                    ActivityRecord r2 = this.mActivities.get(activityNdx);
                    if (!r2.finishing) {
                        ActivityOptions opts = r2.takeOptionsLocked();
                        if (opts != null) {
                            r.updateOptionsLocked(opts);
                        }
                        if (this.mStack != null && this.mStack.finishActivityLocked(r2, 0, null, "clear-task-stack", false)) {
                            activityNdx--;
                            numActivities--;
                        }
                    }
                }
                if (r.launchMode != 0 || (536870912 & launchFlags) != 0 || ActivityStarter.isDocumentLaunchesIntoExisting(launchFlags) || r.finishing) {
                    return r;
                }
                if (this.mStack != null) {
                    this.mStack.finishActivityLocked(r, 0, null, "clear-task-top", false);
                }
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeTaskActivitiesLocked(boolean pauseImmediately, String reason) {
        performClearTaskAtIndexLocked(0, pauseImmediately, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String lockTaskAuthToString() {
        switch (this.mLockTaskAuth) {
            case 0:
                return "LOCK_TASK_AUTH_DONT_LOCK";
            case 1:
                return "LOCK_TASK_AUTH_PINNABLE";
            case 2:
                return "LOCK_TASK_AUTH_LAUNCHABLE";
            case 3:
                return "LOCK_TASK_AUTH_WHITELISTED";
            case 4:
                return "LOCK_TASK_AUTH_LAUNCHABLE_PRIV";
            default:
                return "unknown=" + this.mLockTaskAuth;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLockTaskAuth() {
        setLockTaskAuth(getRootActivity());
    }

    private void setLockTaskAuth(ActivityRecord r) {
        int i = 1;
        if (r == null) {
            this.mLockTaskAuth = 1;
            return;
        }
        String pkg = this.realActivity != null ? this.realActivity.getPackageName() : null;
        LockTaskController lockTaskController = this.mService.getLockTaskController();
        switch (r.lockTaskLaunchMode) {
            case 0:
                if (lockTaskController.isPackageWhitelisted(this.userId, pkg)) {
                    i = 3;
                }
                this.mLockTaskAuth = i;
                break;
            case 1:
                this.mLockTaskAuth = 0;
                break;
            case 2:
                this.mLockTaskAuth = 4;
                break;
            case 3:
                if (lockTaskController.isPackageWhitelisted(this.userId, pkg)) {
                    i = 2;
                }
                this.mLockTaskAuth = i;
                break;
        }
        if (ActivityManagerDebugConfig.DEBUG_LOCKTASK) {
            Slog.d("ActivityManager", "setLockTaskAuth: task=" + this + " mLockTaskAuth=" + lockTaskAuthToString());
        }
    }

    private boolean isResizeable(boolean checkSupportsPip) {
        return this.mService.mForceResizableActivities || ActivityInfo.isResizeableMode(this.mResizeMode) || (checkSupportsPip && this.mSupportsPictureInPicture);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResizeable() {
        return isResizeable(true);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean supportsSplitScreenWindowingMode() {
        if (super.supportsSplitScreenWindowingMode() && this.mService.mSupportsSplitScreenMultiWindow) {
            return this.mService.mForceResizableActivities || (isResizeable(false) && !ActivityInfo.isPreserveOrientationMode(this.mResizeMode));
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canBeLaunchedOnDisplay(int displayId) {
        return this.mService.mStackSupervisor.canPlaceEntityOnDisplay(displayId, isResizeable(false), -1, -1, null);
    }

    private boolean canResizeToBounds(Rect bounds) {
        if (inFreeformWindowingMode()) {
            return false;
        }
        if (bounds == null || !inFreeformWindowingMode()) {
            return true;
        }
        boolean landscape = bounds.width() > bounds.height();
        Rect configBounds = getOverrideBounds();
        if (this.mResizeMode != 7) {
            return !(this.mResizeMode == 6 && landscape) && (this.mResizeMode != 5 || landscape);
        } else if (configBounds.isEmpty()) {
            return true;
        } else {
            return landscape == (configBounds.width() > configBounds.height());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClearingToReuseTask() {
        return this.mReuseTask;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord findActivityInHistoryLocked(ActivityRecord r) {
        ComponentName realActivity = r.realActivity;
        for (int activityNdx = this.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
            ActivityRecord candidate = this.mActivities.get(activityNdx);
            if (!candidate.finishing && candidate.realActivity.equals(realActivity)) {
                return candidate;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTaskDescription() {
        int numActivities = this.mActivities.size();
        boolean relinquish = false;
        if (numActivities != 0 && (this.mActivities.get(0).info.flags & 4096) != 0) {
            relinquish = true;
        }
        int activityNdx = Math.min(numActivities, 1);
        while (true) {
            if (activityNdx >= numActivities) {
                break;
            }
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (relinquish && (r.info.flags & 4096) == 0) {
                activityNdx++;
                break;
            } else if (r.intent != null && (r.intent.getFlags() & DumpState.DUMP_FROZEN) != 0) {
                break;
            } else {
                activityNdx++;
            }
        }
        if (activityNdx > 0) {
            String label = null;
            String iconFilename = null;
            int iconResource = -1;
            int colorPrimary = 0;
            int colorBackground = 0;
            int statusBarColor = 0;
            int navigationBarColor = 0;
            boolean topActivity = true;
            for (int activityNdx2 = activityNdx - 1; activityNdx2 >= 0; activityNdx2--) {
                ActivityRecord r2 = this.mActivities.get(activityNdx2);
                if (!r2.mTaskOverlay) {
                    if (r2.taskDescription != null) {
                        if (label == null) {
                            String label2 = r2.taskDescription.getLabel();
                            label = label2;
                        }
                        if (iconResource == -1) {
                            int iconResource2 = r2.taskDescription.getIconResource();
                            iconResource = iconResource2;
                        }
                        if (iconFilename == null) {
                            String iconFilename2 = r2.taskDescription.getIconFilename();
                            iconFilename = iconFilename2;
                        }
                        if (colorPrimary == 0) {
                            int colorPrimary2 = r2.taskDescription.getPrimaryColor();
                            colorPrimary = colorPrimary2;
                        }
                        if (topActivity) {
                            colorBackground = r2.taskDescription.getBackgroundColor();
                            statusBarColor = r2.taskDescription.getStatusBarColor();
                            navigationBarColor = r2.taskDescription.getNavigationBarColor();
                        }
                    }
                    topActivity = false;
                }
            }
            this.lastTaskDescription = new ActivityManager.TaskDescription(label, null, iconResource, iconFilename, colorPrimary, colorBackground, statusBarColor, navigationBarColor);
            if (this.mWindowContainerController != null) {
                this.mWindowContainerController.setTaskDescription(this.lastTaskDescription);
            }
            if (this.taskId == this.mAffiliatedTaskId) {
                this.mAffiliatedTaskColor = this.lastTaskDescription.getPrimaryColor();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int findEffectiveRootIndex() {
        int effectiveNdx = 0;
        int topActivityNdx = this.mActivities.size() - 1;
        for (int activityNdx = 0; activityNdx <= topActivityNdx; activityNdx++) {
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (!r.finishing) {
                effectiveNdx = activityNdx;
                if ((r.info.flags & 4096) == 0) {
                    break;
                }
            }
        }
        return effectiveNdx;
    }

    void updateEffectiveIntent() {
        int effectiveRootIndex = findEffectiveRootIndex();
        ActivityRecord r = this.mActivities.get(effectiveRootIndex);
        setIntent(r);
        updateTaskDescription();
    }

    private void adjustForMinimalTaskDimensions(Rect bounds) {
        if (bounds == null) {
            return;
        }
        int minWidth = this.mMinWidth;
        int minHeight = this.mMinHeight;
        if (!inPinnedWindowingMode()) {
            if (minWidth == -1) {
                minWidth = this.mService.mStackSupervisor.mDefaultMinSizeOfResizeableTask;
            }
            if (minHeight == -1) {
                minHeight = this.mService.mStackSupervisor.mDefaultMinSizeOfResizeableTask;
            }
        }
        boolean adjustWidth = minWidth > bounds.width();
        boolean adjustHeight = minHeight > bounds.height();
        if (!adjustWidth && !adjustHeight) {
            return;
        }
        Rect configBounds = getOverrideBounds();
        if (adjustWidth) {
            if (!configBounds.isEmpty() && bounds.right == configBounds.right) {
                bounds.left = bounds.right - minWidth;
            } else {
                bounds.right = bounds.left + minWidth;
            }
        }
        if (adjustHeight) {
            if (!configBounds.isEmpty() && bounds.bottom == configBounds.bottom) {
                bounds.top = bounds.bottom - minHeight;
            } else {
                bounds.bottom = bounds.top + minHeight;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration computeNewOverrideConfigurationForBounds(Rect bounds, Rect insetBounds) {
        Configuration newOverrideConfig = new Configuration();
        if (bounds != null) {
            newOverrideConfig.setTo(getOverrideConfiguration());
            this.mTmpRect.set(bounds);
            adjustForMinimalTaskDimensions(this.mTmpRect);
            computeOverrideConfiguration(newOverrideConfig, this.mTmpRect, insetBounds, this.mTmpRect.right != bounds.right, this.mTmpRect.bottom != bounds.bottom);
        }
        return newOverrideConfig;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateOverrideConfiguration(Rect bounds) {
        return updateOverrideConfiguration(bounds, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateOverrideConfiguration(Rect bounds, Rect insetBounds) {
        if (equivalentOverrideBounds(bounds)) {
            return false;
        }
        Rect currentBounds = getOverrideBounds();
        this.mTmpConfig.setTo(getOverrideConfiguration());
        Configuration newConfig = getOverrideConfiguration();
        boolean matchParentBounds = bounds == null || bounds.isEmpty();
        boolean persistBounds = getWindowConfiguration().persistTaskBounds();
        if (matchParentBounds) {
            if (!currentBounds.isEmpty() && persistBounds) {
                this.mLastNonFullscreenBounds = currentBounds;
            }
            setBounds(null);
            newConfig.unset();
        } else {
            this.mTmpRect.set(bounds);
            adjustForMinimalTaskDimensions(this.mTmpRect);
            setBounds(this.mTmpRect);
            if (this.mStack == null || persistBounds) {
                this.mLastNonFullscreenBounds = getOverrideBounds();
            }
            computeOverrideConfiguration(newConfig, this.mTmpRect, insetBounds, this.mTmpRect.right != bounds.right, this.mTmpRect.bottom != bounds.bottom);
        }
        onOverrideConfigurationChanged(newConfig);
        return !this.mTmpConfig.equals(newConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityStateChanged(ActivityRecord record, ActivityStack.ActivityState state, String reason) {
        ActivityStack parent = getStack();
        if (parent != null) {
            parent.onActivityStateChanged(record, state, reason);
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        boolean wasInMultiWindowMode = inMultiWindowMode();
        super.onConfigurationChanged(newParentConfig);
        if (wasInMultiWindowMode != inMultiWindowMode()) {
            this.mService.mStackSupervisor.scheduleUpdateMultiWindowMode(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void computeOverrideConfiguration(Configuration config, Rect bounds, Rect insetBounds, boolean overrideWidth, boolean overrideHeight) {
        int i;
        this.mTmpNonDecorBounds.set(bounds);
        this.mTmpStableBounds.set(bounds);
        config.unset();
        Configuration parentConfig = getParent().getConfiguration();
        float density = parentConfig.densityDpi * 0.00625f;
        if (this.mStack != null) {
            StackWindowController stackController = this.mStack.getWindowContainerController();
            stackController.adjustConfigurationForBounds(bounds, insetBounds, this.mTmpNonDecorBounds, this.mTmpStableBounds, overrideWidth, overrideHeight, density, config, parentConfig, getWindowingMode());
            if (config.screenWidthDp <= config.screenHeightDp) {
                i = 1;
            } else {
                i = 2;
            }
            config.orientation = i;
            int compatScreenWidthDp = (int) (this.mTmpNonDecorBounds.width() / density);
            int compatScreenHeightDp = (int) (this.mTmpNonDecorBounds.height() / density);
            int longSize = Math.max(compatScreenHeightDp, compatScreenWidthDp);
            int shortSize = Math.min(compatScreenHeightDp, compatScreenWidthDp);
            config.screenLayout = Configuration.reduceScreenLayout(36, longSize, shortSize);
            return;
        }
        throw new IllegalArgumentException("Expected stack when calculating override config");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect updateOverrideConfigurationFromLaunchBounds() {
        Rect bounds = getLaunchBounds();
        updateOverrideConfiguration(bounds);
        if (bounds != null && !bounds.isEmpty()) {
            bounds.set(getOverrideBounds());
        }
        return bounds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateOverrideConfigurationForStack(ActivityStack inStack) {
        if (this.mStack != null && this.mStack == inStack) {
            return;
        }
        if (inStack.inFreeformWindowingMode()) {
            if (!isResizeable()) {
                throw new IllegalArgumentException("Can not position non-resizeable task=" + this + " in stack=" + inStack);
            } else if (!matchParentBounds()) {
                return;
            } else {
                if (this.mLastNonFullscreenBounds != null) {
                    updateOverrideConfiguration(this.mLastNonFullscreenBounds);
                    return;
                } else {
                    this.mService.mStackSupervisor.getLaunchParamsController().layoutTask(this, null);
                    return;
                }
            }
        }
        updateOverrideConfiguration(inStack.getOverrideBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getLaunchBounds() {
        if (this.mStack == null) {
            return null;
        }
        int windowingMode = getWindowingMode();
        if (!isActivityTypeStandardOrUndefined() || windowingMode == 1 || (windowingMode == 3 && !isResizeable())) {
            if (isResizeable()) {
                return this.mStack.getOverrideBounds();
            }
            return null;
        } else if (!getWindowConfiguration().persistTaskBounds()) {
            return this.mStack.getOverrideBounds();
        } else {
            return this.mLastNonFullscreenBounds;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int activityNdx = this.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
            ActivityRecord r = this.mActivities.get(activityNdx);
            if (r.visible) {
                r.showStartingWindow(null, false, taskSwitch);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRootProcess(ProcessRecord proc) {
        clearRootProcess();
        if (this.intent != null && (this.intent.getFlags() & DumpState.DUMP_VOLUMES) == 0) {
            this.mRootProcess = proc;
            proc.recentTasks.add(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearRootProcess() {
        if (this.mRootProcess != null) {
            this.mRootProcess.recentTasks.remove(this);
            this.mRootProcess = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearAllPendingOptions() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            getChildAt(i).clearOptionsLocked(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("userId=");
        pw.print(this.userId);
        pw.print(" effectiveUid=");
        UserHandle.formatUid(pw, this.effectiveUid);
        pw.print(" mCallingUid=");
        UserHandle.formatUid(pw, this.mCallingUid);
        pw.print(" mUserSetupComplete=");
        pw.print(this.mUserSetupComplete);
        pw.print(" mCallingPackage=");
        pw.println(this.mCallingPackage);
        if (this.affinity != null || this.rootAffinity != null) {
            pw.print(prefix);
            pw.print("affinity=");
            pw.print(this.affinity);
            if (this.affinity == null || !this.affinity.equals(this.rootAffinity)) {
                pw.print(" root=");
                pw.println(this.rootAffinity);
            } else {
                pw.println();
            }
        }
        if (this.voiceSession != null || this.voiceInteractor != null) {
            pw.print(prefix);
            pw.print("VOICE: session=0x");
            pw.print(Integer.toHexString(System.identityHashCode(this.voiceSession)));
            pw.print(" interactor=0x");
            pw.println(Integer.toHexString(System.identityHashCode(this.voiceInteractor)));
        }
        if (this.intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix);
            sb.append("intent={");
            this.intent.toShortString(sb, false, true, false, true);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (this.affinityIntent != null) {
            StringBuilder sb2 = new StringBuilder(128);
            sb2.append(prefix);
            sb2.append("affinityIntent={");
            this.affinityIntent.toShortString(sb2, false, true, false, true);
            sb2.append('}');
            pw.println(sb2.toString());
        }
        if (this.origActivity != null) {
            pw.print(prefix);
            pw.print("origActivity=");
            pw.println(this.origActivity.flattenToShortString());
        }
        if (this.realActivity != null) {
            pw.print(prefix);
            pw.print("realActivity=");
            pw.println(this.realActivity.flattenToShortString());
        }
        if (this.autoRemoveRecents || this.isPersistable || !isActivityTypeStandard() || this.numFullscreen != 0) {
            pw.print(prefix);
            pw.print("autoRemoveRecents=");
            pw.print(this.autoRemoveRecents);
            pw.print(" isPersistable=");
            pw.print(this.isPersistable);
            pw.print(" numFullscreen=");
            pw.print(this.numFullscreen);
            pw.print(" activityType=");
            pw.println(getActivityType());
        }
        if (this.rootWasReset || this.mNeverRelinquishIdentity || this.mReuseTask || this.mLockTaskAuth != 1) {
            pw.print(prefix);
            pw.print("rootWasReset=");
            pw.print(this.rootWasReset);
            pw.print(" mNeverRelinquishIdentity=");
            pw.print(this.mNeverRelinquishIdentity);
            pw.print(" mReuseTask=");
            pw.print(this.mReuseTask);
            pw.print(" mLockTaskAuth=");
            pw.println(lockTaskAuthToString());
        }
        if (this.mAffiliatedTaskId != this.taskId || this.mPrevAffiliateTaskId != -1 || this.mPrevAffiliate != null || this.mNextAffiliateTaskId != -1 || this.mNextAffiliate != null) {
            pw.print(prefix);
            pw.print("affiliation=");
            pw.print(this.mAffiliatedTaskId);
            pw.print(" prevAffiliation=");
            pw.print(this.mPrevAffiliateTaskId);
            pw.print(" (");
            if (this.mPrevAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(this.mPrevAffiliate)));
            }
            pw.print(") nextAffiliation=");
            pw.print(this.mNextAffiliateTaskId);
            pw.print(" (");
            if (this.mNextAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(this.mNextAffiliate)));
            }
            pw.println(")");
        }
        pw.print(prefix);
        pw.print("Activities=");
        pw.println(this.mActivities);
        if (!this.askedCompatMode || !this.inRecents || !this.isAvailable) {
            pw.print(prefix);
            pw.print("askedCompatMode=");
            pw.print(this.askedCompatMode);
            pw.print(" inRecents=");
            pw.print(this.inRecents);
            pw.print(" isAvailable=");
            pw.println(this.isAvailable);
        }
        if (this.lastDescription != null) {
            pw.print(prefix);
            pw.print("lastDescription=");
            pw.println(this.lastDescription);
        }
        if (this.mRootProcess != null) {
            pw.print(prefix);
            pw.print("mRootProcess=");
            pw.println(this.mRootProcess);
        }
        pw.print(prefix);
        pw.print("stackId=");
        pw.println(getStackId());
        pw.print(prefix + "hasBeenVisible=" + this.hasBeenVisible);
        StringBuilder sb3 = new StringBuilder();
        sb3.append(" mResizeMode=");
        sb3.append(ActivityInfo.resizeModeToString(this.mResizeMode));
        pw.print(sb3.toString());
        pw.print(" mSupportsPictureInPicture=" + this.mSupportsPictureInPicture);
        pw.print(" isResizeable=" + isResizeable());
        pw.print(" lastActiveTime=" + this.lastActiveTime);
        pw.println(" (inactive for " + (getInactiveDuration() / 1000) + "s)");
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        if (this.stringName != null) {
            sb.append(this.stringName);
            sb.append(" U=");
            sb.append(this.userId);
            sb.append(" StackId=");
            sb.append(getStackId());
            sb.append(" sz=");
            sb.append(this.mActivities.size());
            sb.append('}');
            return sb.toString();
        }
        sb.append("TaskRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(this.taskId);
        if (this.affinity != null) {
            sb.append(" A=");
            sb.append(this.affinity);
        } else if (this.intent != null) {
            sb.append(" I=");
            sb.append(this.intent.getComponent().flattenToShortString());
        } else if (this.affinityIntent != null && this.affinityIntent.getComponent() != null) {
            sb.append(" aI=");
            sb.append(this.affinityIntent.getComponent().flattenToShortString());
        } else {
            sb.append(" ??");
        }
        this.stringName = sb.toString();
        return toString();
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, false);
        proto.write(1120986464258L, this.taskId);
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord activity = this.mActivities.get(i);
            activity.writeToProto(proto, 2246267895811L);
        }
        proto.write(1120986464260L, this.mStack.mStackId);
        if (this.mLastNonFullscreenBounds != null) {
            this.mLastNonFullscreenBounds.writeToProto(proto, 1146756268037L);
        }
        if (this.realActivity != null) {
            proto.write(1138166333446L, this.realActivity.flattenToShortString());
        }
        if (this.origActivity != null) {
            proto.write(1138166333447L, this.origActivity.flattenToShortString());
        }
        proto.write(1120986464264L, getActivityType());
        proto.write(1120986464265L, this.mResizeMode);
        proto.write(1133871366154L, matchParentBounds());
        if (!matchParentBounds()) {
            Rect bounds = getOverrideBounds();
            bounds.writeToProto(proto, 1146756268043L);
        }
        proto.write(1120986464268L, this.mMinWidth);
        proto.write(1120986464269L, this.mMinHeight);
        proto.end(token);
    }

    /* loaded from: classes.dex */
    static class TaskActivitiesReport {
        ActivityRecord base;
        int numActivities;
        int numRunning;
        ActivityRecord top;

        void reset() {
            this.numActivities = 0;
            this.numRunning = 0;
            this.base = null;
            this.top = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
            Slog.i("ActivityManager", "Saving task=" + this);
        }
        out.attribute(null, ATTR_TASKID, String.valueOf(this.taskId));
        if (this.realActivity != null) {
            out.attribute(null, ATTR_REALACTIVITY, this.realActivity.flattenToShortString());
        }
        out.attribute(null, ATTR_REALACTIVITY_SUSPENDED, String.valueOf(this.realActivitySuspended));
        if (this.origActivity != null) {
            out.attribute(null, ATTR_ORIGACTIVITY, this.origActivity.flattenToShortString());
        }
        if (this.affinity != null) {
            out.attribute(null, ATTR_AFFINITY, this.affinity);
            if (!this.affinity.equals(this.rootAffinity)) {
                out.attribute(null, ATTR_ROOT_AFFINITY, this.rootAffinity != null ? this.rootAffinity : "@");
            }
        } else if (this.rootAffinity != null) {
            out.attribute(null, ATTR_ROOT_AFFINITY, this.rootAffinity != null ? this.rootAffinity : "@");
        }
        out.attribute(null, ATTR_ROOTHASRESET, String.valueOf(this.rootWasReset));
        out.attribute(null, ATTR_AUTOREMOVERECENTS, String.valueOf(this.autoRemoveRecents));
        out.attribute(null, ATTR_ASKEDCOMPATMODE, String.valueOf(this.askedCompatMode));
        out.attribute(null, ATTR_USERID, String.valueOf(this.userId));
        out.attribute(null, ATTR_USER_SETUP_COMPLETE, String.valueOf(this.mUserSetupComplete));
        out.attribute(null, ATTR_EFFECTIVE_UID, String.valueOf(this.effectiveUid));
        out.attribute(null, ATTR_LASTTIMEMOVED, String.valueOf(this.mLastTimeMoved));
        out.attribute(null, ATTR_NEVERRELINQUISH, String.valueOf(this.mNeverRelinquishIdentity));
        if (this.lastDescription != null) {
            out.attribute(null, ATTR_LASTDESCRIPTION, this.lastDescription.toString());
        }
        if (this.lastTaskDescription != null) {
            this.lastTaskDescription.saveToXml(out);
        }
        out.attribute(null, ATTR_TASK_AFFILIATION_COLOR, String.valueOf(this.mAffiliatedTaskColor));
        out.attribute(null, ATTR_TASK_AFFILIATION, String.valueOf(this.mAffiliatedTaskId));
        out.attribute(null, ATTR_PREV_AFFILIATION, String.valueOf(this.mPrevAffiliateTaskId));
        out.attribute(null, ATTR_NEXT_AFFILIATION, String.valueOf(this.mNextAffiliateTaskId));
        out.attribute(null, ATTR_CALLING_UID, String.valueOf(this.mCallingUid));
        out.attribute(null, ATTR_CALLING_PACKAGE, this.mCallingPackage == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : this.mCallingPackage);
        out.attribute(null, ATTR_RESIZE_MODE, String.valueOf(this.mResizeMode));
        out.attribute(null, ATTR_SUPPORTS_PICTURE_IN_PICTURE, String.valueOf(this.mSupportsPictureInPicture));
        if (this.mLastNonFullscreenBounds != null) {
            out.attribute(null, ATTR_NON_FULLSCREEN_BOUNDS, this.mLastNonFullscreenBounds.flattenToString());
        }
        out.attribute(null, ATTR_MIN_WIDTH, String.valueOf(this.mMinWidth));
        out.attribute(null, ATTR_MIN_HEIGHT, String.valueOf(this.mMinHeight));
        out.attribute(null, ATTR_PERSIST_TASK_VERSION, String.valueOf(1));
        if (this.affinityIntent != null) {
            out.startTag(null, TAG_AFFINITYINTENT);
            this.affinityIntent.saveToXml(out);
            out.endTag(null, TAG_AFFINITYINTENT);
        }
        if (this.intent != null) {
            out.startTag(null, TAG_INTENT);
            this.intent.saveToXml(out);
            out.endTag(null, TAG_INTENT);
        }
        ArrayList<ActivityRecord> activities = this.mActivities;
        int numActivities = activities.size();
        for (int activityNdx = 0; activityNdx < numActivities; activityNdx++) {
            ActivityRecord r = activities.get(activityNdx);
            if (r.info.persistableMode != 0 && r.isPersistable()) {
                if (((r.intent.getFlags() & DumpState.DUMP_FROZEN) | 8192) != 524288 || activityNdx <= 0) {
                    out.startTag(null, TAG_ACTIVITY);
                    r.saveToXml(out);
                    out.endTag(null, TAG_ACTIVITY);
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    @VisibleForTesting
    static TaskRecordFactory getTaskRecordFactory() {
        if (sTaskRecordFactory == null) {
            setTaskRecordFactory(new TaskRecordFactory());
        }
        return sTaskRecordFactory;
    }

    static void setTaskRecordFactory(TaskRecordFactory factory) {
        sTaskRecordFactory = factory;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        return getTaskRecordFactory().create(service, taskId, info, intent, voiceSession, voiceInteractor);
    }

    static TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info, Intent intent, ActivityManager.TaskDescription taskDescription) {
        return getTaskRecordFactory().create(service, taskId, info, intent, taskDescription);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static TaskRecord restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor) throws IOException, XmlPullParserException {
        return getTaskRecordFactory().restoreFromXml(in, stackSupervisor);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class TaskRecordFactory {
        TaskRecordFactory() {
        }

        TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
            return new TaskRecord(service, taskId, info, intent, voiceSession, voiceInteractor);
        }

        TaskRecord create(ActivityManagerService service, int taskId, ActivityInfo info, Intent intent, ActivityManager.TaskDescription taskDescription) {
            return new TaskRecord(service, taskId, info, intent, taskDescription);
        }

        TaskRecord create(ActivityManagerService service, int taskId, Intent intent, Intent affinityIntent, String affinity, String rootAffinity, ComponentName realActivity, ComponentName origActivity, boolean rootWasReset, boolean autoRemoveRecents, boolean askedCompatMode, int userId, int effectiveUid, String lastDescription, ArrayList<ActivityRecord> activities, long lastTimeMoved, boolean neverRelinquishIdentity, ActivityManager.TaskDescription lastTaskDescription, int taskAffiliation, int prevTaskId, int nextTaskId, int taskAffiliationColor, int callingUid, String callingPackage, int resizeMode, boolean supportsPictureInPicture, boolean realActivitySuspended, boolean userSetupComplete, int minWidth, int minHeight) {
            return new TaskRecord(service, taskId, intent, affinityIntent, affinity, rootAffinity, realActivity, origActivity, rootWasReset, autoRemoveRecents, askedCompatMode, userId, effectiveUid, lastDescription, activities, lastTimeMoved, neverRelinquishIdentity, lastTaskDescription, taskAffiliation, prevTaskId, nextTaskId, taskAffiliationColor, callingUid, callingPackage, resizeMode, supportsPictureInPicture, realActivitySuspended, userSetupComplete, minWidth, minHeight);
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        /* JADX WARN: Removed duplicated region for block: B:162:0x033c  */
        /* JADX WARN: Removed duplicated region for block: B:175:0x038b  */
        /* JADX WARN: Removed duplicated region for block: B:178:0x0390  */
        /* JADX WARN: Removed duplicated region for block: B:182:0x0397  */
        /* JADX WARN: Removed duplicated region for block: B:188:0x0405 A[LOOP:2: B:187:0x0403->B:188:0x0405, LOOP_END] */
        /* JADX WARN: Removed duplicated region for block: B:191:0x0415  */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        com.android.server.am.TaskRecord restoreFromXml(org.xmlpull.v1.XmlPullParser r70, com.android.server.am.ActivityStackSupervisor r71) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
            /*
                Method dump skipped, instructions count: 1244
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.TaskRecord.TaskRecordFactory.restoreFromXml(org.xmlpull.v1.XmlPullParser, com.android.server.am.ActivityStackSupervisor):com.android.server.am.TaskRecord");
        }

        void handleUnknownTag(String name, XmlPullParser in) throws IOException, XmlPullParserException {
            Slog.e("ActivityManager", "restoreTask: Unexpected name=" + name);
            XmlUtils.skipCurrentTag(in);
        }
    }
}
