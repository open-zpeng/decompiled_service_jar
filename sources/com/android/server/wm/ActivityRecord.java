package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ResultInfo;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.MoveToDisplayItem;
import android.app.servertransaction.MultiWindowModeChangeItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.PipModeChangeItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.TopResumedActivityChangeItem;
import android.app.servertransaction.WindowVisibilityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.voice.IVoiceInteractionSession;
import android.util.EventLog;
import android.util.Log;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.DisplayCutout;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.util.XmlUtils;
import com.android.server.AttributeCache;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentRecord;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.uri.UriPermissionOwner;
import com.android.server.wm.ActivityMetricsLogger;
import com.android.server.wm.ActivityStack;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.policy.xpPhoneWindowManager;
import com.xiaopeng.server.wm.xpWindowManagerService;
import com.xiaopeng.view.xpWindowManager;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public final class ActivityRecord extends ConfigurationContainer {
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    private static final String ATTR_ID = "id";
    private static final String ATTR_LAUNCHEDFROMPACKAGE = "launched_from_package";
    private static final String ATTR_LAUNCHEDFROMUID = "launched_from_uid";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_USERID = "user_id";
    private static final String LEGACY_RECENTS_PACKAGE_NAME = "com.android.systemui.recents";
    private static final boolean SHOW_ACTIVITY_START_TIME = true;
    static final int STARTING_WINDOW_NOT_SHOWN = 0;
    static final int STARTING_WINDOW_REMOVED = 2;
    static final int STARTING_WINDOW_SHOWN = 1;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_CONFIGURATION = "ActivityTaskManager";
    private static final String TAG_FOCUS = "ActivityTaskManager";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String TAG_SAVED_STATE = "ActivityTaskManager";
    private static final String TAG_STATES = "ActivityTaskManager";
    private static final String TAG_SWITCH = "ActivityTaskManager";
    private static final String TAG_VISIBILITY = "ActivityTaskManager";
    WindowProcessController app;
    ApplicationInfo appInfo;
    AppTimeTracker appTimeTracker;
    final IApplicationToken.Stub appToken;
    CompatibilityInfo compat;
    private final boolean componentSpecified;
    int configChangeFlags;
    long cpuTimeAtResume;
    boolean deferRelaunchUntilPaused;
    boolean delayedResume;
    boolean finishing;
    boolean forceNewConfig;
    boolean frontOfTask;
    boolean frozenBeforeDestroy;
    boolean fullscreen;
    boolean hasBeenLaunched;
    final boolean hasWallpaper;
    boolean haveState;
    Bundle icicle;
    private int icon;
    boolean idle;
    boolean immersive;
    private boolean inHistory;
    final ActivityInfo info;
    final Intent intent;
    private boolean keysPaused;
    private int labelRes;
    long lastLaunchTime;
    long lastVisibleTime;
    int launchCount;
    boolean launchFailed;
    int launchMode;
    long launchTickTime;
    final String launchedFromPackage;
    final int launchedFromPid;
    final int launchedFromUid;
    int lockTaskLaunchMode;
    private int logo;
    final ComponentName mActivityComponent;
    AppWindowToken mAppWindowToken;
    final ActivityTaskManagerService mAtmService;
    boolean mClientVisibilityDeferred;
    private CompatDisplayInsets mCompatDisplayInsets;
    private int mConfigurationSeq;
    private boolean mDeferHidingClient;
    boolean mDrawn;
    @VisibleForTesting
    int mHandoverLaunchDisplayId;
    private int[] mHorizontalSizeConfigurations;
    private boolean mInheritShownWhenLocked;
    private MergedConfiguration mLastReportedConfiguration;
    private int mLastReportedDisplayId;
    private boolean mLastReportedMultiWindowMode;
    private boolean mLastReportedPictureInPictureMode;
    boolean mLaunchTaskBehind;
    final RootActivityContainer mRootActivityContainer;
    int mRotationAnimationHint;
    ActivityServiceConnectionsHolder mServiceConnectionsHolder;
    private boolean mShowWhenLocked;
    private int[] mSmallestSizeConfigurations;
    final ActivityStackSupervisor mStackSupervisor;
    private ActivityStack.ActivityState mState;
    private boolean mTurnScreenOn;
    final int mUserId;
    private int[] mVerticalSizeConfigurations;
    ArrayList<ReferrerIntent> newIntents;
    @VisibleForTesting
    boolean noDisplay;
    private CharSequence nonLocalizedLabel;
    boolean nowVisible;
    final String packageName;
    long pauseTime;
    ActivityOptions pendingOptions;
    HashSet<WeakReference<PendingIntentRecord>> pendingResults;
    boolean pendingVoiceInteractionStart;
    PersistableBundle persistentState;
    boolean preserveWindowOnDeferredRelaunch;
    final String processName;
    private int realTheme;
    final int requestCode;
    ComponentName requestedVrComponent;
    final String resolvedType;
    ActivityRecord resultTo;
    final String resultWho;
    ArrayList<ResultInfo> results;
    ActivityOptions returningOptions;
    final boolean rootVoiceInteraction;
    final String shortComponentName;
    boolean sleeping;
    final boolean stateNotNeeded;
    boolean stopped;
    String stringName;
    boolean supportsEnterPipOnTaskSwitch;
    private TaskRecord task;
    final String taskAffinity;
    ActivityManager.TaskDescription taskDescription;
    private int theme;
    long topResumedStateLossTime;
    UriPermissionOwner uriPermissions;
    boolean visible;
    boolean visibleIgnoringKeyguard;
    IVoiceInteractionSession voiceSession;
    private int windowFlags;
    private long createTime = System.currentTimeMillis();
    PictureInPictureParams pictureInPictureArgs = new PictureInPictureParams.Builder().build();
    int mStartingWindowState = 0;
    boolean mTaskOverlay = false;
    int mRelaunchReason = 0;
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpBounds = new Rect();
    final Binder assistToken = new Binder();

    private static String startingWindowStateToString(int state) {
        if (state != 0) {
            if (state != 1) {
                if (state == 2) {
                    return "STARTING_WINDOW_REMOVED";
                }
                return "unknown state=" + state;
            }
            return "STARTING_WINDOW_SHOWN";
        }
        return "STARTING_WINDOW_NOT_SHOWN";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        long now = SystemClock.uptimeMillis();
        pw.print(prefix);
        pw.print("packageName=");
        pw.print(this.packageName);
        pw.print(" processName=");
        pw.println(this.processName);
        pw.print(prefix);
        pw.print("launchedFromUid=");
        pw.print(this.launchedFromUid);
        pw.print(" launchedFromPackage=");
        pw.print(this.launchedFromPackage);
        pw.print(" userId=");
        pw.println(this.mUserId);
        pw.print(prefix);
        pw.print("app=");
        pw.println(this.app);
        pw.print(prefix);
        pw.println(this.intent.toInsecureStringWithClip());
        pw.print(prefix);
        pw.print("frontOfTask=");
        pw.print(this.frontOfTask);
        pw.print(" task=");
        pw.println(this.task);
        pw.print(prefix);
        pw.print("taskAffinity=");
        pw.println(this.taskAffinity);
        pw.print(prefix);
        pw.print("mActivityComponent=");
        pw.println(this.mActivityComponent.flattenToShortString());
        if (this.appInfo != null) {
            pw.print(prefix);
            pw.print("baseDir=");
            pw.println(this.appInfo.sourceDir);
            if (!Objects.equals(this.appInfo.sourceDir, this.appInfo.publicSourceDir)) {
                pw.print(prefix);
                pw.print("resDir=");
                pw.println(this.appInfo.publicSourceDir);
            }
            pw.print(prefix);
            pw.print("dataDir=");
            pw.println(this.appInfo.dataDir);
            if (this.appInfo.splitSourceDirs != null) {
                pw.print(prefix);
                pw.print("splitDir=");
                pw.println(Arrays.toString(this.appInfo.splitSourceDirs));
            }
        }
        pw.print(prefix);
        pw.print("stateNotNeeded=");
        pw.print(this.stateNotNeeded);
        pw.print(" componentSpecified=");
        pw.print(this.componentSpecified);
        pw.print(" mActivityType=");
        pw.println(WindowConfiguration.activityTypeToString(getActivityType()));
        if (this.rootVoiceInteraction) {
            pw.print(prefix);
            pw.print("rootVoiceInteraction=");
            pw.println(this.rootVoiceInteraction);
        }
        pw.print(prefix);
        pw.print("compat=");
        pw.print(this.compat);
        pw.print(" labelRes=0x");
        pw.print(Integer.toHexString(this.labelRes));
        pw.print(" icon=0x");
        pw.print(Integer.toHexString(this.icon));
        pw.print(" theme=0x");
        pw.println(Integer.toHexString(this.theme));
        pw.println(prefix + "mLastReportedConfigurations:");
        this.mLastReportedConfiguration.dump(pw, prefix + " ");
        pw.print(prefix);
        pw.print("CurrentConfiguration=");
        pw.println(getConfiguration());
        if (!getRequestedOverrideConfiguration().equals(Configuration.EMPTY)) {
            pw.println(prefix + "RequestedOverrideConfiguration=" + getRequestedOverrideConfiguration());
        }
        if (!getResolvedOverrideConfiguration().equals(getRequestedOverrideConfiguration())) {
            pw.println(prefix + "ResolvedOverrideConfiguration=" + getResolvedOverrideConfiguration());
        }
        if (!matchParentBounds()) {
            pw.println(prefix + "bounds=" + getBounds());
        }
        if (this.resultTo != null || this.resultWho != null) {
            pw.print(prefix);
            pw.print("resultTo=");
            pw.print(this.resultTo);
            pw.print(" resultWho=");
            pw.print(this.resultWho);
            pw.print(" resultCode=");
            pw.println(this.requestCode);
        }
        ActivityManager.TaskDescription taskDescription = this.taskDescription;
        if (taskDescription != null) {
            String iconFilename = taskDescription.getIconFilename();
            if (iconFilename != null || this.taskDescription.getLabel() != null || this.taskDescription.getPrimaryColor() != 0) {
                pw.print(prefix);
                pw.print("taskDescription:");
                pw.print(" label=\"");
                pw.print(this.taskDescription.getLabel());
                pw.print("\"");
                pw.print(" icon=");
                pw.print(this.taskDescription.getInMemoryIcon() != null ? this.taskDescription.getInMemoryIcon().getByteCount() + " bytes" : "null");
                pw.print(" iconResource=");
                pw.print(this.taskDescription.getIconResource());
                pw.print(" iconFilename=");
                pw.print(this.taskDescription.getIconFilename());
                pw.print(" primaryColor=");
                pw.println(Integer.toHexString(this.taskDescription.getPrimaryColor()));
                pw.print(prefix + " backgroundColor=");
                pw.println(Integer.toHexString(this.taskDescription.getBackgroundColor()));
                pw.print(prefix + " statusBarColor=");
                pw.println(Integer.toHexString(this.taskDescription.getStatusBarColor()));
                pw.print(prefix + " navigationBarColor=");
                pw.println(Integer.toHexString(this.taskDescription.getNavigationBarColor()));
            }
        }
        if (this.results != null) {
            pw.print(prefix);
            pw.print("results=");
            pw.println(this.results);
        }
        HashSet<WeakReference<PendingIntentRecord>> hashSet = this.pendingResults;
        if (hashSet != null && hashSet.size() > 0) {
            pw.print(prefix);
            pw.println("Pending Results:");
            Iterator<WeakReference<PendingIntentRecord>> it = this.pendingResults.iterator();
            while (it.hasNext()) {
                WeakReference<PendingIntentRecord> wpir = it.next();
                PendingIntentRecord pir = wpir != null ? wpir.get() : null;
                pw.print(prefix);
                pw.print("  - ");
                if (pir == null) {
                    pw.println("null");
                } else {
                    pw.println(pir);
                    pir.dump(pw, prefix + "    ");
                }
            }
        }
        ArrayList<ReferrerIntent> arrayList = this.newIntents;
        if (arrayList != null && arrayList.size() > 0) {
            pw.print(prefix);
            pw.println("Pending New Intents:");
            for (int i = 0; i < this.newIntents.size(); i++) {
                Intent intent = this.newIntents.get(i);
                pw.print(prefix);
                pw.print("  - ");
                if (intent == null) {
                    pw.println("null");
                } else {
                    pw.println(intent.toShortString(false, true, false, true));
                }
            }
        }
        if (this.pendingOptions != null) {
            pw.print(prefix);
            pw.print("pendingOptions=");
            pw.println(this.pendingOptions);
        }
        AppTimeTracker appTimeTracker = this.appTimeTracker;
        if (appTimeTracker != null) {
            appTimeTracker.dumpWithHeader(pw, prefix, false);
        }
        UriPermissionOwner uriPermissionOwner = this.uriPermissions;
        if (uriPermissionOwner != null) {
            uriPermissionOwner.dump(pw, prefix);
        }
        pw.print(prefix);
        pw.print("launchFailed=");
        pw.print(this.launchFailed);
        pw.print(" launchCount=");
        pw.print(this.launchCount);
        pw.print(" lastLaunchTime=");
        long j = this.lastLaunchTime;
        if (j == 0) {
            pw.print("0");
        } else {
            TimeUtils.formatDuration(j, now, pw);
        }
        pw.println();
        pw.print(prefix);
        pw.print("haveState=");
        pw.print(this.haveState);
        pw.print(" icicle=");
        pw.println(this.icicle);
        pw.print(prefix);
        pw.print("state=");
        pw.print(this.mState);
        pw.print(" stopped=");
        pw.print(this.stopped);
        pw.print(" delayedResume=");
        pw.print(this.delayedResume);
        pw.print(" finishing=");
        pw.println(this.finishing);
        pw.print(prefix);
        pw.print("keysPaused=");
        pw.print(this.keysPaused);
        pw.print(" inHistory=");
        pw.print(this.inHistory);
        pw.print(" visible=");
        pw.print(this.visible);
        pw.print(" sleeping=");
        pw.print(this.sleeping);
        pw.print(" idle=");
        pw.print(this.idle);
        pw.print(" mStartingWindowState=");
        pw.println(startingWindowStateToString(this.mStartingWindowState));
        pw.print(prefix);
        pw.print("fullscreen=");
        pw.print(this.fullscreen);
        pw.print(" noDisplay=");
        pw.print(this.noDisplay);
        pw.print(" immersive=");
        pw.print(this.immersive);
        pw.print(" launchMode=");
        pw.println(this.launchMode);
        pw.print(prefix);
        pw.print("frozenBeforeDestroy=");
        pw.print(this.frozenBeforeDestroy);
        pw.print(" forceNewConfig=");
        pw.println(this.forceNewConfig);
        pw.print(prefix);
        pw.print("mActivityType=");
        pw.println(WindowConfiguration.activityTypeToString(getActivityType()));
        if (this.requestedVrComponent != null) {
            pw.print(prefix);
            pw.print("requestedVrComponent=");
            pw.println(this.requestedVrComponent);
        }
        if (this.lastVisibleTime != 0 || this.nowVisible) {
            pw.print(prefix);
            pw.print(" nowVisible=");
            pw.print(this.nowVisible);
            pw.print(" lastVisibleTime=");
            long j2 = this.lastVisibleTime;
            if (j2 == 0) {
                pw.print("0");
            } else {
                TimeUtils.formatDuration(j2, now, pw);
            }
            pw.println();
        }
        if (this.mDeferHidingClient) {
            pw.println(prefix + "mDeferHidingClient=" + this.mDeferHidingClient);
        }
        if (this.deferRelaunchUntilPaused || this.configChangeFlags != 0) {
            pw.print(prefix);
            pw.print("deferRelaunchUntilPaused=");
            pw.print(this.deferRelaunchUntilPaused);
            pw.print(" configChangeFlags=");
            pw.println(Integer.toHexString(this.configChangeFlags));
        }
        if (this.mServiceConnectionsHolder != null) {
            pw.print(prefix);
            pw.print("connections=");
            pw.println(this.mServiceConnectionsHolder);
        }
        if (this.info != null) {
            pw.println(prefix + "resizeMode=" + ActivityInfo.resizeModeToString(this.info.resizeMode));
            pw.println(prefix + "mLastReportedMultiWindowMode=" + this.mLastReportedMultiWindowMode + " mLastReportedPictureInPictureMode=" + this.mLastReportedPictureInPictureMode);
            if (this.info.supportsPictureInPicture()) {
                pw.println(prefix + "supportsPictureInPicture=" + this.info.supportsPictureInPicture());
                pw.println(prefix + "supportsEnterPipOnTaskSwitch: " + this.supportsEnterPipOnTaskSwitch);
            }
            if (this.info.maxAspectRatio != 0.0f) {
                pw.println(prefix + "maxAspectRatio=" + this.info.maxAspectRatio);
            }
            if (this.info.minAspectRatio != 0.0f) {
                pw.println(prefix + "minAspectRatio=" + this.info.minAspectRatio);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateApplicationInfo(ApplicationInfo aInfo) {
        this.appInfo = aInfo;
        this.info.applicationInfo = aInfo;
    }

    private boolean crossesHorizontalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(this.mHorizontalSizeConfigurations, firstDp, secondDp);
    }

    private boolean crossesVerticalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(this.mVerticalSizeConfigurations, firstDp, secondDp);
    }

    private boolean crossesSmallestSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(this.mSmallestSizeConfigurations, firstDp, secondDp);
    }

    private static boolean crossesSizeThreshold(int[] thresholds, int firstDp, int secondDp) {
        if (thresholds == null) {
            return false;
        }
        for (int i = thresholds.length - 1; i >= 0; i--) {
            int threshold = thresholds[i];
            if ((firstDp < threshold && secondDp >= threshold) || (firstDp >= threshold && secondDp < threshold)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSizeConfigurations(int[] horizontalSizeConfiguration, int[] verticalSizeConfigurations, int[] smallestSizeConfigurations) {
        this.mHorizontalSizeConfigurations = horizontalSizeConfiguration;
        this.mVerticalSizeConfigurations = verticalSizeConfigurations;
        this.mSmallestSizeConfigurations = smallestSizeConfigurations;
    }

    private void scheduleActivityMovedToDisplay(int displayId, Configuration config) {
        if (!attachedToProcess()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.w("ActivityTaskManager", "Can't report activity moved to display - client not running, activityRecord=" + this + ", displayId=" + displayId);
                return;
            }
            return;
        }
        try {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Reporting activity moved to display, activityRecord=" + this + ", displayId=" + displayId + ", config=" + config);
            }
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) MoveToDisplayItem.obtain(displayId, config));
        } catch (RemoteException e) {
        }
    }

    private void scheduleConfigurationChanged(Configuration config) {
        if (!attachedToProcess()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.w("ActivityTaskManager", "Can't report activity configuration update - client not running, activityRecord=" + this);
                return;
            }
            return;
        }
        try {
            if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Sending new config to " + this + ", config: " + config);
            }
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) ActivityConfigurationChangeItem.obtain(config));
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean scheduleTopResumedActivityChanged(boolean onTop) {
        if (!attachedToProcess()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.w("ActivityTaskManager", "Can't report activity position update - client not running, activityRecord=" + this);
            }
            return false;
        }
        try {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Sending position change to " + this + ", onTop: " + onTop);
            }
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) TopResumedActivityChangeItem.obtain(onTop));
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateMultiWindowMode() {
        boolean inMultiWindowMode;
        TaskRecord taskRecord = this.task;
        if (taskRecord != null && taskRecord.getStack() != null && attachedToProcess() && !this.task.getStack().deferScheduleMultiWindowModeChanged() && (inMultiWindowMode = inMultiWindowMode()) != this.mLastReportedMultiWindowMode) {
            this.mLastReportedMultiWindowMode = inMultiWindowMode;
            scheduleMultiWindowModeChanged(getConfiguration());
        }
    }

    private void scheduleMultiWindowModeChanged(Configuration overrideConfig) {
        try {
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) MultiWindowModeChangeItem.obtain(this.mLastReportedMultiWindowMode, overrideConfig));
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePictureInPictureMode(Rect targetStackBounds, boolean forceUpdate) {
        TaskRecord taskRecord = this.task;
        if (taskRecord == null || taskRecord.getStack() == null || !attachedToProcess()) {
            return;
        }
        boolean inPictureInPictureMode = inPinnedWindowingMode() && targetStackBounds != null;
        if (inPictureInPictureMode != this.mLastReportedPictureInPictureMode || forceUpdate) {
            this.mLastReportedPictureInPictureMode = inPictureInPictureMode;
            this.mLastReportedMultiWindowMode = inPictureInPictureMode;
            Configuration newConfig = new Configuration();
            if (targetStackBounds != null && !targetStackBounds.isEmpty()) {
                newConfig.setTo(this.task.getRequestedOverrideConfiguration());
                Rect outBounds = newConfig.windowConfiguration.getBounds();
                this.task.adjustForMinimalTaskDimensions(outBounds, outBounds);
                TaskRecord taskRecord2 = this.task;
                taskRecord2.computeConfigResourceOverrides(newConfig, taskRecord2.getParent().getConfiguration());
            }
            schedulePictureInPictureModeChanged(newConfig);
            scheduleMultiWindowModeChanged(newConfig);
        }
    }

    private void schedulePictureInPictureModeChanged(Configuration overrideConfig) {
        try {
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) PipModeChangeItem.obtain(this.mLastReportedPictureInPictureMode, overrideConfig));
        } catch (Exception e) {
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected int getChildCount() {
        return 0;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getChildAt(int index) {
        return null;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return getTaskRecord();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord getTaskRecord() {
        return this.task;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTask(TaskRecord task) {
        setTask(task, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTask(TaskRecord task, boolean reparenting) {
        if (task != null && task == getTaskRecord()) {
            return;
        }
        ActivityStack oldStack = getActivityStack();
        ActivityStack newStack = task != null ? task.getStack() : null;
        if (oldStack != newStack) {
            if (!reparenting && oldStack != null) {
                oldStack.onActivityRemovedFromStack(this);
            }
            if (newStack != null) {
                newStack.onActivityAddedToStack(this);
            }
        }
        this.task = task;
        if (!reparenting) {
            onParentChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            return;
        }
        appWindowToken.setWillCloseOrEnterPip(willCloseOrEnterPip);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class Token extends IApplicationToken.Stub {
        private final String name;
        private final WeakReference<ActivityRecord> weakActivity;

        Token(ActivityRecord activity, Intent intent) {
            this.weakActivity = new WeakReference<>(activity);
            this.name = intent.getComponent().flattenToShortString();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static ActivityRecord tokenToActivityRecordLocked(Token token) {
            ActivityRecord r;
            if (token == null || (r = token.weakActivity.get()) == null || r.getActivityStack() == null) {
                return null;
            }
            return r;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Token{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            sb.append(this.weakActivity.get());
            sb.append('}');
            return sb.toString();
        }

        public String getName() {
            return this.name;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ActivityRecord forTokenLocked(IBinder token) {
        try {
            return Token.tokenToActivityRecordLocked((Token) token);
        } catch (ClassCastException e) {
            Slog.w("ActivityTaskManager", "Bad activity token: " + token, e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isResolverActivity(String className) {
        return ResolverActivity.class.getName().equals(className);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResolverOrDelegateActivity() {
        return isResolverActivity(this.mActivityComponent.getClassName()) || Objects.equals(this.mActivityComponent, this.mAtmService.mStackSupervisor.getSystemChooserActivity());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResolverOrChildActivity() {
        if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(this.packageName)) {
            try {
                return ResolverActivity.class.isAssignableFrom(Object.class.getClassLoader().loadClass(this.mActivityComponent.getClassName()));
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public ActivityRecord(ActivityTaskManagerService _service, WindowProcessController _caller, int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage, Intent _intent, String _resolvedType, ActivityInfo aInfo, Configuration _configuration, ActivityRecord _resultTo, String _resultWho, int _reqCode, boolean _componentSpecified, boolean _rootVoiceInteraction, ActivityStackSupervisor supervisor, ActivityOptions options, ActivityRecord sourceRecord) {
        boolean z;
        int i;
        this.mHandoverLaunchDisplayId = -1;
        this.mRotationAnimationHint = -1;
        this.mAtmService = _service;
        this.mRootActivityContainer = _service.mRootActivityContainer;
        this.appToken = new Token(this, _intent);
        this.info = aInfo;
        this.launchedFromPid = _launchedFromPid;
        this.launchedFromUid = _launchedFromUid;
        this.launchedFromPackage = _launchedFromPackage;
        this.mUserId = UserHandle.getUserId(aInfo.applicationInfo.uid);
        this.intent = _intent;
        this.shortComponentName = _intent.getComponent().flattenToShortString();
        this.resolvedType = _resolvedType;
        this.componentSpecified = _componentSpecified;
        this.rootVoiceInteraction = _rootVoiceInteraction;
        this.mLastReportedConfiguration = new MergedConfiguration(_configuration);
        this.resultTo = _resultTo;
        this.resultWho = _resultWho;
        this.requestCode = _reqCode;
        setState(ActivityStack.ActivityState.INITIALIZING, "ActivityRecord ctor");
        this.frontOfTask = false;
        this.launchFailed = false;
        this.stopped = false;
        this.delayedResume = false;
        this.finishing = false;
        this.deferRelaunchUntilPaused = false;
        this.keysPaused = false;
        this.inHistory = false;
        this.visible = false;
        this.nowVisible = false;
        this.mDrawn = false;
        this.idle = false;
        this.hasBeenLaunched = false;
        this.mStackSupervisor = supervisor;
        this.haveState = true;
        if (aInfo.targetActivity == null || (aInfo.targetActivity.equals(_intent.getComponent().getClassName()) && (aInfo.launchMode == 0 || aInfo.launchMode == 1))) {
            this.mActivityComponent = _intent.getComponent();
        } else {
            this.mActivityComponent = new ComponentName(aInfo.packageName, aInfo.targetActivity);
        }
        this.taskAffinity = aInfo.taskAffinity;
        this.stateNotNeeded = (aInfo.flags & 16) != 0;
        this.appInfo = aInfo.applicationInfo;
        this.nonLocalizedLabel = aInfo.nonLocalizedLabel;
        this.labelRes = aInfo.labelRes;
        if (this.nonLocalizedLabel == null && this.labelRes == 0) {
            ApplicationInfo app = aInfo.applicationInfo;
            this.nonLocalizedLabel = app.nonLocalizedLabel;
            this.labelRes = app.labelRes;
        }
        this.icon = aInfo.getIconResource();
        this.logo = aInfo.getLogoResource();
        this.theme = aInfo.getThemeResource();
        this.realTheme = this.theme;
        if (this.realTheme == 0) {
            this.realTheme = aInfo.applicationInfo.targetSdkVersion < 11 ? 16973829 : 16973931;
        }
        if ((aInfo.flags & 512) != 0) {
            this.windowFlags |= 16777216;
        }
        if ((aInfo.flags & 1) == 0 || _caller == null || (aInfo.applicationInfo.uid != 1000 && aInfo.applicationInfo.uid != _caller.mInfo.uid)) {
            this.processName = aInfo.processName;
        } else {
            this.processName = _caller.mName;
        }
        if ((aInfo.flags & 32) != 0) {
            this.intent.addFlags(DumpState.DUMP_VOLUMES);
        }
        this.packageName = aInfo.applicationInfo.packageName;
        this.launchMode = aInfo.launchMode;
        AttributeCache.Entry ent = AttributeCache.instance().get(this.packageName, this.realTheme, R.styleable.Window, this.mUserId);
        if (ent != null) {
            z = true;
            this.fullscreen = !ActivityInfo.isTranslucentOrFloating(ent.array);
            this.fullscreen = xpActivityManager.overrideToFloating(this.packageName) ? false : this.fullscreen;
            this.hasWallpaper = ent.array.getBoolean(14, false);
            this.noDisplay = ent.array.getBoolean(10, false);
        } else {
            z = true;
            this.hasWallpaper = false;
            this.noDisplay = false;
        }
        xpPhoneWindowManager.get(_service.mContext).setActivityLunchInfo(this.mActivityComponent, this.intent);
        int i2 = z;
        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);
        this.immersive = (aInfo.flags & 2048) != 0 ? i2 : false;
        this.requestedVrComponent = aInfo.requestedVrComponent == null ? null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);
        this.mShowWhenLocked = (aInfo.flags & DumpState.DUMP_VOLUMES) != 0 ? i2 : false;
        this.mInheritShownWhenLocked = (aInfo.privateFlags & i2) != 0 ? i2 : 0;
        this.mTurnScreenOn = (aInfo.flags & 16777216) != 0 ? i2 : 0;
        this.mRotationAnimationHint = aInfo.rotationAnimation;
        this.lockTaskLaunchMode = aInfo.lockTaskLaunchMode;
        if (this.appInfo.isPrivilegedApp() && ((i = this.lockTaskLaunchMode) == 2 || i == i2)) {
            this.lockTaskLaunchMode = 0;
        }
        if (options != null) {
            this.pendingOptions = options;
            this.mLaunchTaskBehind = options.getLaunchTaskBehind();
            int rotationAnimation = this.pendingOptions.getRotationAnimationHint();
            if (rotationAnimation >= 0) {
                this.mRotationAnimationHint = rotationAnimation;
            }
            PendingIntent usageReport = this.pendingOptions.getUsageTimeReport();
            if (usageReport != null) {
                this.appTimeTracker = new AppTimeTracker(usageReport);
            }
            boolean useLockTask = this.pendingOptions.getLockTaskMode();
            if (useLockTask && this.lockTaskLaunchMode == 0) {
                this.lockTaskLaunchMode = 3;
            }
            this.mHandoverLaunchDisplayId = options.getLaunchDisplayId();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setProcess(WindowProcessController proc) {
        this.app = proc;
        TaskRecord taskRecord = this.task;
        ActivityRecord root = taskRecord != null ? taskRecord.getRootActivity() : null;
        if (root == this) {
            this.task.setRootProcess(proc);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasProcess() {
        return this.app != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean attachedToProcess() {
        return hasProcess() && this.app.hasThread();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createAppWindowToken() {
        if (this.mAppWindowToken == null) {
            this.inHistory = true;
            this.task.updateOverrideConfigurationFromLaunchBounds();
            updateOverrideConfiguration();
            this.mAppWindowToken = this.mAtmService.mWindowManager.mRoot.getAppWindowToken(this.appToken.asBinder());
            if (this.mAppWindowToken != null) {
                Slog.w("ActivityTaskManager", "Attempted to add existing app token: " + this.appToken);
            } else {
                Task container = this.task.getTask();
                if (container == null) {
                    throw new IllegalArgumentException("createAppWindowToken: invalid task =" + this.task);
                }
                this.mAppWindowToken = createAppWindow(this.mAtmService.mWindowManager, this.appToken, this.task.voiceSession != null, container.getDisplayContent(), ActivityTaskManagerService.getInputDispatchingTimeoutLocked(this) * 1000000, this.fullscreen, (this.info.flags & 1024) != 0, this.appInfo.targetSdkVersion, this.info.screenOrientation, this.mRotationAnimationHint, this.mLaunchTaskBehind, isAlwaysFocusable());
                if (WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT || WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                    Slog.v("ActivityTaskManager", "addAppToken: " + this.mAppWindowToken + " task=" + container + " at 2147483647");
                }
                container.addChild(this.mAppWindowToken, Integer.MAX_VALUE);
            }
            this.task.addActivityToTop(this);
            this.mLastReportedMultiWindowMode = inMultiWindowMode();
            this.mLastReportedPictureInPictureMode = inPinnedWindowingMode();
            return;
        }
        throw new IllegalArgumentException("App Window Token=" + this.mAppWindowToken + " already created for r=" + this);
    }

    boolean addStartingWindow(String pkg, int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags, IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning, boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents) {
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v("ActivityTaskManager", "setAppStartingWindow: token=" + this.appToken + " pkg=" + pkg + " transferFrom=" + transferFrom + " newTask=" + newTask + " taskSwitch=" + taskSwitch + " processRunning=" + processRunning + " allowTaskSnapshot=" + allowTaskSnapshot);
        }
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            Slog.w("WindowManager", "Attempted to set icon of non-existing app token: " + this.appToken);
            return false;
        } else if (appWindowToken.getTask() == null) {
            Slog.w("WindowManager", "Attempted to start a window to an app token not having attached to any task: " + this.appToken);
            return false;
        } else {
            return this.mAppWindowToken.addStartingWindow(pkg, theme, compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags, transferFrom, newTask, taskSwitch, processRunning, allowTaskSnapshot, activityCreated, fromRecents);
        }
    }

    @VisibleForTesting
    AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token, boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation, int rotationAnimationHint, boolean launchTaskBehind, boolean alwaysFocusable) {
        return new AppWindowToken(service, token, this.mActivityComponent, voiceInteraction, dc, inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk, orientation, rotationAnimationHint, launchTaskBehind, alwaysFocusable, this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeWindowContainer() {
        if (this.mAtmService.mWindowManager.mRoot == null) {
            return;
        }
        DisplayContent dc = this.mAtmService.mWindowManager.mRoot.getDisplayContent(getDisplayId());
        if (dc == null) {
            Slog.w("ActivityTaskManager", "removeWindowContainer: Attempted to remove token: " + this.appToken + " from non-existing displayId=" + getDisplayId());
            return;
        }
        resumeKeyDispatchingLocked();
        dc.removeAppToken(this.appToken.asBinder());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(TaskRecord newTask, int position, String reason) {
        if (this.mAppWindowToken == null) {
            Slog.w("ActivityTaskManager", "reparent: Attempted to reparent non-existing app token: " + this.appToken);
            return;
        }
        TaskRecord prevTask = this.task;
        if (prevTask == newTask) {
            throw new IllegalArgumentException(reason + ": task=" + newTask + " is already the parent of r=" + this);
        } else if (prevTask != null && newTask != null && prevTask.getStack() != newTask.getStack()) {
            throw new IllegalArgumentException(reason + ": task=" + newTask + " is in a different stack (" + newTask.getStackId() + ") than the parent of r=" + this + " (" + prevTask.getStackId() + ")");
        } else {
            this.mAppWindowToken.reparent(newTask.getTask(), position);
            ActivityStack prevStack = prevTask.getStack();
            if (prevStack != newTask.getStack()) {
                prevStack.onActivityRemovedFromStack(this);
            }
            prevTask.removeActivity(this, true);
            newTask.addActivityAtIndex(position, this);
        }
    }

    private boolean isHomeIntent(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction()) && (intent.hasCategory("android.intent.category.HOME") || intent.hasCategory("android.intent.category.SECONDARY_HOME")) && intent.getCategories().size() == 1 && intent.getData() == null && intent.getType() == null;
    }

    static boolean isMainIntent(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction()) && intent.hasCategory("android.intent.category.LAUNCHER") && intent.getCategories().size() == 1 && intent.getData() == null && intent.getType() == null;
    }

    @VisibleForTesting
    boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
        if (uid == Process.myUid() || uid == 0) {
            return true;
        }
        RecentTasks recentTasks = this.mStackSupervisor.mService.getRecentTasks();
        if (recentTasks == null || !recentTasks.isCallerRecents(uid)) {
            return sourceRecord != null && sourceRecord.isResolverOrDelegateActivity();
        }
        return true;
    }

    private boolean canLaunchAssistActivity(String packageName) {
        ComponentName assistComponent = this.mAtmService.mActiveVoiceInteractionServiceComponent;
        if (assistComponent != null) {
            return assistComponent.getPackageName().equals(packageName);
        }
        return false;
    }

    private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent, ActivityOptions options, ActivityRecord sourceRecord) {
        int activityType = 0;
        if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord)) && isHomeIntent(intent) && !isResolverOrDelegateActivity()) {
            int flags = ActivityInfoManager.getActivityFlags(intent);
            boolean isHome = (flags & 536870912) == 536870912;
            boolean isSecondaryHome = (flags & 268435456) == 268435456;
            if (isHome || isSecondaryHome) {
                activityType = 2;
            } else {
                activityType = 1;
            }
            if (this.info.resizeMode == 4 || this.info.resizeMode == 1) {
                this.info.resizeMode = 0;
            }
        } else if (this.mActivityComponent.getClassName().contains(LEGACY_RECENTS_PACKAGE_NAME) || this.mAtmService.getRecentTasks().isRecentsComponent(this.mActivityComponent, this.appInfo.uid)) {
            activityType = 3;
        } else if (options != null && options.getLaunchActivityType() == 4 && canLaunchAssistActivity(this.launchedFromPackage)) {
            activityType = 4;
        }
        setActivityType(activityType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskToAffiliateWith(TaskRecord taskToAffiliateWith) {
        int i = this.launchMode;
        if (i != 3 && i != 2) {
            this.task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getActivityStack() {
        TaskRecord taskRecord = this.task;
        if (taskRecord != null) {
            return (T) taskRecord.getStack();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getStackId() {
        if (getActivityStack() != null) {
            return getActivityStack().mStackId;
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDisplay() {
        ActivityStack stack = getActivityStack();
        if (stack != null) {
            return stack.getDisplay();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean changeWindowTranslucency(boolean toOpaque) {
        if (this.fullscreen == toOpaque) {
            return false;
        }
        this.task.numFullscreen += toOpaque ? 1 : -1;
        this.fullscreen = toOpaque;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void takeFromHistory() {
        if (this.inHistory) {
            this.inHistory = false;
            if (this.task != null && !this.finishing) {
                this.task = null;
            }
            clearOptionsLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInHistory() {
        return this.inHistory;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInStackLocked() {
        ActivityStack stack = getActivityStack();
        return (stack == null || stack.isInStackLocked(this) == null) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPersistable() {
        Intent intent;
        return (this.info.persistableMode == 0 || this.info.persistableMode == 2) && ((intent = this.intent) == null || (intent.getFlags() & DumpState.DUMP_VOLUMES) == 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable() {
        return this.mRootActivityContainer.isFocusable(this, isAlwaysFocusable());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResizeable() {
        return ActivityInfo.isResizeableMode(this.info.resizeMode) || this.info.supportsPictureInPicture();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNonResizableOrForcedResizable() {
        return (this.info.resizeMode == 2 || this.info.resizeMode == 1) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean supportsPictureInPicture() {
        return this.mAtmService.mSupportsPictureInPicture && isActivityTypeStandardOrUndefined() && this.info.supportsPictureInPicture();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean supportsSplitScreenWindowingMode() {
        return super.supportsSplitScreenWindowingMode() && this.mAtmService.mSupportsSplitScreenMultiWindow && supportsResizeableMultiWindow();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean supportsFreeform() {
        return this.mAtmService.mSupportsFreeformWindowManagement && supportsResizeableMultiWindow();
    }

    private boolean supportsResizeableMultiWindow() {
        return this.mAtmService.mSupportsMultiWindow && !isActivityTypeHome() && (ActivityInfo.isResizeableMode(this.info.resizeMode) || this.mAtmService.mForceResizableActivities);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canBeLaunchedOnDisplay(int displayId) {
        return this.mAtmService.mStackSupervisor.canPlaceEntityOnDisplay(displayId, this.launchedFromPid, this.launchedFromUid, this.info);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkEnterPictureInPictureState(String caller, boolean beforeStopping) {
        if (supportsPictureInPicture() && checkEnterPictureInPictureAppOpsState() && !this.mAtmService.shouldDisableNonVrUiLocked()) {
            boolean isKeyguardLocked = this.mAtmService.isKeyguardLocked();
            boolean isCurrentAppLocked = this.mAtmService.getLockTaskModeState() != 0;
            ActivityDisplay display = getDisplay();
            boolean hasPinnedStack = display != null && display.hasPinnedStack();
            boolean isNotLockedOrOnKeyguard = (isKeyguardLocked || isCurrentAppLocked) ? false : true;
            if (beforeStopping && hasPinnedStack) {
                return false;
            }
            int i = AnonymousClass1.$SwitchMap$com$android$server$wm$ActivityStack$ActivityState[this.mState.ordinal()];
            if (i != 1) {
                return (i == 2 || i == 3) ? isNotLockedOrOnKeyguard && !hasPinnedStack && this.supportsEnterPipOnTaskSwitch : i == 4 && this.supportsEnterPipOnTaskSwitch && isNotLockedOrOnKeyguard && !hasPinnedStack;
            } else if (isCurrentAppLocked) {
                return false;
            } else {
                return this.supportsEnterPipOnTaskSwitch || !beforeStopping;
            }
        }
        return false;
    }

    /* renamed from: com.android.server.wm.ActivityRecord$1  reason: invalid class name */
    /* loaded from: classes2.dex */
    static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$com$android$server$wm$ActivityStack$ActivityState = new int[ActivityStack.ActivityState.values().length];

        static {
            try {
                $SwitchMap$com$android$server$wm$ActivityStack$ActivityState[ActivityStack.ActivityState.RESUMED.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$com$android$server$wm$ActivityStack$ActivityState[ActivityStack.ActivityState.PAUSING.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$com$android$server$wm$ActivityStack$ActivityState[ActivityStack.ActivityState.PAUSED.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
            try {
                $SwitchMap$com$android$server$wm$ActivityStack$ActivityState[ActivityStack.ActivityState.STOPPING.ordinal()] = 4;
            } catch (NoSuchFieldError e4) {
            }
        }
    }

    private boolean checkEnterPictureInPictureAppOpsState() {
        return this.mAtmService.getAppOpsService().checkOperation(67, this.appInfo.uid, this.packageName) == 0;
    }

    boolean isAlwaysFocusable() {
        return (this.info.flags & 262144) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean moveFocusableActivityToTop(String reason) {
        if (isFocusable()) {
            TaskRecord task = getTaskRecord();
            ActivityStack stack = getActivityStack();
            if (stack == null) {
                Slog.w("ActivityTaskManager", "moveActivityStackToFront: invalid task or stack: activity=" + this + " task=" + task);
                return false;
            }
            boolean noChecked = "setFocusedTaskNoChecked".equals(reason);
            if (noChecked || this.mRootActivityContainer.getTopResumedActivity() != this) {
                stack.moveToFront(reason, task);
                if (this.mRootActivityContainer.getTopResumedActivity() == this) {
                    this.mAtmService.setResumedActivityUncheckLocked(this, reason);
                    return true;
                }
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeFinishingLocked() {
        if (this.finishing) {
            return;
        }
        this.finishing = true;
        if (this.stopped) {
            clearOptionsLocked();
        }
        ActivityTaskManagerService activityTaskManagerService = this.mAtmService;
        if (activityTaskManagerService != null) {
            activityTaskManagerService.getTaskChangeNotificationController().notifyTaskStackChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UriPermissionOwner getUriPermissionsLocked() {
        if (this.uriPermissions == null) {
            this.uriPermissions = new UriPermissionOwner(this.mAtmService.mUgmInternal, this);
        }
        return this.uriPermissions;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addResultLocked(ActivityRecord from, String resultWho, int requestCode, int resultCode, Intent resultData) {
        ActivityResult r = new ActivityResult(from, resultWho, requestCode, resultCode, resultData);
        if (this.results == null) {
            this.results = new ArrayList<>();
        }
        this.results.add(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:19:0x002e  */
    /* JADX WARN: Removed duplicated region for block: B:27:0x0033 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void removeResultsLocked(com.android.server.wm.ActivityRecord r4, java.lang.String r5, int r6) {
        /*
            r3 = this;
            java.util.ArrayList<android.app.ResultInfo> r0 = r3.results
            if (r0 == 0) goto L36
            int r0 = r0.size()
            int r0 = r0 + (-1)
        La:
            if (r0 < 0) goto L36
            java.util.ArrayList<android.app.ResultInfo> r1 = r3.results
            java.lang.Object r1 = r1.get(r0)
            com.android.server.wm.ActivityResult r1 = (com.android.server.wm.ActivityResult) r1
            com.android.server.wm.ActivityRecord r2 = r1.mFrom
            if (r2 == r4) goto L19
            goto L33
        L19:
            java.lang.String r2 = r1.mResultWho
            if (r2 != 0) goto L20
            if (r5 == 0) goto L29
            goto L33
        L20:
            java.lang.String r2 = r1.mResultWho
            boolean r2 = r2.equals(r5)
            if (r2 != 0) goto L29
            goto L33
        L29:
            int r2 = r1.mRequestCode
            if (r2 == r6) goto L2e
            goto L33
        L2e:
            java.util.ArrayList<android.app.ResultInfo> r2 = r3.results
            r2.remove(r0)
        L33:
            int r0 = r0 + (-1)
            goto La
        L36:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityRecord.removeResultsLocked(com.android.server.wm.ActivityRecord, java.lang.String, int):void");
    }

    private void addNewIntentLocked(ReferrerIntent intent) {
        if (this.newIntents == null) {
            this.newIntents = new ArrayList<>();
        }
        this.newIntents.add(intent);
    }

    final boolean isSleeping() {
        ActivityStack stack = getActivityStack();
        return stack != null ? stack.shouldSleepActivities() : this.mAtmService.isSleepingLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void deliverNewIntentLocked(int callingUid, Intent intent, String referrer) {
        this.mAtmService.mUgmInternal.grantUriPermissionFromIntent(callingUid, this.packageName, intent, getUriPermissionsLocked(), this.mUserId);
        ReferrerIntent rintent = new ReferrerIntent(intent, referrer);
        boolean unsent = true;
        boolean isTopActivityWhileSleeping = isTopRunningActivity() && isSleeping();
        if ((this.mState == ActivityStack.ActivityState.RESUMED || this.mState == ActivityStack.ActivityState.PAUSED || isTopActivityWhileSleeping) && attachedToProcess()) {
            try {
                ArrayList<ReferrerIntent> ar = new ArrayList<>(1);
                ar.add(rintent);
                this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) NewIntentItem.obtain(ar, this.mState == ActivityStack.ActivityState.RESUMED));
                unsent = false;
            } catch (RemoteException e) {
                Slog.w("ActivityTaskManager", "Exception thrown sending new intent to " + this, e);
            } catch (NullPointerException e2) {
                Slog.w("ActivityTaskManager", "Exception thrown sending new intent to " + this, e2);
            }
        }
        if (unsent) {
            addNewIntentLocked(rintent);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateOptionsLocked(ActivityOptions options) {
        if (options != null) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                Slog.i("ActivityTaskManager", "Update options for " + this);
            }
            ActivityOptions activityOptions = this.pendingOptions;
            if (activityOptions != null) {
                activityOptions.abort();
            }
            this.pendingOptions = options;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyOptionsLocked() {
        ActivityOptions activityOptions = this.pendingOptions;
        if (activityOptions != null && activityOptions.getAnimationType() != 5) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                Slog.i("ActivityTaskManager", "Applying options for " + this);
            }
            applyOptionsLocked(this.pendingOptions, this.intent);
            TaskRecord taskRecord = this.task;
            if (taskRecord == null) {
                clearOptionsLocked(false);
            } else {
                taskRecord.clearAllPendingOptions();
            }
        }
    }

    void applyOptionsLocked(ActivityOptions pendingOptions, Intent intent) {
        int animationType = pendingOptions.getAnimationType();
        DisplayContent displayContent = this.mAppWindowToken.getDisplayContent();
        switch (animationType) {
            case 0:
                return;
            case 1:
                displayContent.mAppTransition.overridePendingAppTransition(pendingOptions.getPackageName(), pendingOptions.getCustomEnterResId(), pendingOptions.getCustomExitResId(), pendingOptions.getOnAnimationStartListener());
                return;
            case 2:
                displayContent.mAppTransition.overridePendingAppTransitionScaleUp(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getWidth(), pendingOptions.getHeight());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getStartX() + pendingOptions.getWidth(), pendingOptions.getStartY() + pendingOptions.getHeight()));
                    return;
                }
                return;
            case 3:
            case 4:
                boolean scaleUp = animationType == 3;
                GraphicBuffer buffer = pendingOptions.getThumbnail();
                displayContent.mAppTransition.overridePendingAppTransitionThumb(buffer, pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getOnAnimationStartListener(), scaleUp);
                if (intent.getSourceBounds() == null && buffer != null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getStartX() + buffer.getWidth(), pendingOptions.getStartY() + buffer.getHeight()));
                    return;
                }
                return;
            case 5:
            case 6:
            case 7:
            case 10:
            default:
                Slog.e("WindowManager", "applyOptionsLocked: Unknown animationType=" + animationType);
                return;
            case 8:
            case 9:
                AppTransitionAnimationSpec[] specs = pendingOptions.getAnimSpecs();
                IAppTransitionAnimationSpecsFuture specsFuture = pendingOptions.getSpecsFuture();
                if (specsFuture != null) {
                    displayContent.mAppTransition.overridePendingAppTransitionMultiThumbFuture(specsFuture, pendingOptions.getOnAnimationStartListener(), animationType == 8);
                    return;
                } else if (animationType == 9 && specs != null) {
                    displayContent.mAppTransition.overridePendingAppTransitionMultiThumb(specs, pendingOptions.getOnAnimationStartListener(), pendingOptions.getAnimationFinishedListener(), false);
                    return;
                } else {
                    displayContent.mAppTransition.overridePendingAppTransitionAspectScaledThumb(pendingOptions.getThumbnail(), pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getWidth(), pendingOptions.getHeight(), pendingOptions.getOnAnimationStartListener(), animationType == 8);
                    if (intent.getSourceBounds() == null) {
                        intent.setSourceBounds(new Rect(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getStartX() + pendingOptions.getWidth(), pendingOptions.getStartY() + pendingOptions.getHeight()));
                        return;
                    }
                    return;
                }
            case 11:
                displayContent.mAppTransition.overridePendingAppTransitionClipReveal(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getWidth(), pendingOptions.getHeight());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(), pendingOptions.getStartY(), pendingOptions.getStartX() + pendingOptions.getWidth(), pendingOptions.getStartY() + pendingOptions.getHeight()));
                    return;
                }
                return;
            case 12:
                displayContent.mAppTransition.overridePendingAppTransitionStartCrossProfileApps();
                return;
            case 13:
                displayContent.mAppTransition.overridePendingAppTransitionRemote(pendingOptions.getRemoteAnimationAdapter());
                return;
        }
    }

    ActivityOptions getOptionsForTargetActivityLocked() {
        ActivityOptions activityOptions = this.pendingOptions;
        if (activityOptions != null) {
            return activityOptions.forTargetActivity();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOptionsLocked() {
        clearOptionsLocked(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOptionsLocked(boolean withAbort) {
        ActivityOptions activityOptions;
        if (withAbort && (activityOptions = this.pendingOptions) != null) {
            activityOptions.abort();
        }
        this.pendingOptions = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityOptions takeOptionsLocked(boolean fromClient) {
        if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
            Slog.i("ActivityTaskManager", "Taking options for " + this + " callers=" + Debug.getCallers(6));
        }
        ActivityOptions opts = this.pendingOptions;
        if (!fromClient || opts == null || opts.getRemoteAnimationAdapter() == null) {
            this.pendingOptions = null;
        }
        return opts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUriPermissionsLocked() {
        UriPermissionOwner uriPermissionOwner = this.uriPermissions;
        if (uriPermissionOwner != null) {
            uriPermissionOwner.removeUriPermissions();
            this.uriPermissions = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pauseKeyDispatchingLocked() {
        if (!this.keysPaused) {
            this.keysPaused = true;
            AppWindowToken appWindowToken = this.mAppWindowToken;
            if (appWindowToken != null && appWindowToken.getDisplayContent() != null) {
                this.mAppWindowToken.getDisplayContent().getInputMonitor().pauseDispatchingLw(this.mAppWindowToken);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeKeyDispatchingLocked() {
        if (this.keysPaused) {
            this.keysPaused = false;
            AppWindowToken appWindowToken = this.mAppWindowToken;
            if (appWindowToken != null && appWindowToken.getDisplayContent() != null) {
                this.mAppWindowToken.getDisplayContent().getInputMonitor().resumeDispatchingLw(this.mAppWindowToken);
            }
        }
    }

    private void updateTaskDescription(CharSequence description) {
        this.task.lastDescription = description;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDeferHidingClient(boolean deferHidingClient) {
        if (this.mDeferHidingClient == deferHidingClient) {
            return;
        }
        this.mDeferHidingClient = deferHidingClient;
        if (!this.mDeferHidingClient && !this.visible) {
            setVisibility(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setVisibility(boolean visible) {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            Slog.w("WindowManager", "Attempted to set visibility of non-existing app token: " + this.appToken);
            return;
        }
        appWindowToken.setVisibility(visible, this.mDeferHidingClient);
        this.mStackSupervisor.getActivityMetricsLogger().notifyVisibilityChanged(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setVisible(boolean newVisible) {
        this.visible = newVisible;
        this.mDeferHidingClient = !this.visible && this.mDeferHidingClient;
        setVisibility(this.visible);
        this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setState(ActivityStack.ActivityState state, String reason) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "State movement: " + this + " from:" + getState() + " to:" + state + " reason:" + reason);
        }
        if (state == this.mState) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "State unchanged from:" + state);
                return;
            }
            return;
        }
        this.mState = state;
        TaskRecord parent = getTaskRecord();
        if (parent != null) {
            parent.onActivityStateChanged(this, state, reason);
        }
        if (state == ActivityStack.ActivityState.STOPPING && !isSleeping()) {
            AppWindowToken appWindowToken = this.mAppWindowToken;
            if (appWindowToken == null) {
                Slog.w("WindowManager", "Attempted to notify stopping on non-existing app token: " + this.appToken);
                return;
            }
            appWindowToken.detachChildren();
        }
        if (state == ActivityStack.ActivityState.RESUMED) {
            this.mAtmService.updateBatteryStats(this, true);
            this.mAtmService.updateActivityUsageStats(this, 1);
        } else if (state == ActivityStack.ActivityState.PAUSED) {
            this.mAtmService.updateBatteryStats(this, false);
            this.mAtmService.updateActivityUsageStats(this, 2);
        } else if (state == ActivityStack.ActivityState.STOPPED) {
            this.mAtmService.updateActivityUsageStats(this, 23);
        } else if (state == ActivityStack.ActivityState.DESTROYED) {
            this.mAtmService.updateActivityUsageStats(this, 24);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack.ActivityState getState() {
        return this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state) {
        return state == this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state1, ActivityStack.ActivityState state2) {
        ActivityStack.ActivityState activityState = this.mState;
        return state1 == activityState || state2 == activityState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state1, ActivityStack.ActivityState state2, ActivityStack.ActivityState state3) {
        ActivityStack.ActivityState activityState = this.mState;
        return state1 == activityState || state2 == activityState || state3 == activityState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state1, ActivityStack.ActivityState state2, ActivityStack.ActivityState state3, ActivityStack.ActivityState state4) {
        ActivityStack.ActivityState activityState = this.mState;
        return state1 == activityState || state2 == activityState || state3 == activityState || state4 == activityState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppResumed(boolean wasStopped) {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            Slog.w("WindowManager", "Attempted to notify resumed of non-existing app token: " + this.appToken);
            return;
        }
        appWindowToken.notifyAppResumed(wasStopped);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyUnknownVisibilityLaunched() {
        AppWindowToken appWindowToken;
        if (!this.noDisplay && (appWindowToken = this.mAppWindowToken) != null) {
            appWindowToken.getDisplayContent().mUnknownAppVisibilityController.notifyLaunched(this.mAppWindowToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBeVisibleIgnoringKeyguard(boolean behindFullscreenActivity) {
        if (okToShowLocked()) {
            return !behindFullscreenActivity || this.mLaunchTaskBehind;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBeVisible(boolean behindFullscreenActivity) {
        this.visibleIgnoringKeyguard = shouldBeVisibleIgnoringKeyguard(behindFullscreenActivity);
        ActivityStack stack = getActivityStack();
        if (stack == null) {
            return false;
        }
        boolean isDisplaySleeping = getDisplay().isSleeping() && getDisplayId() != 0;
        boolean isTop = this == stack.getTopActivity();
        boolean isTopNotPinnedStack = stack.isAttached() && stack.getDisplay().isTopNotPinnedStack(stack);
        boolean visibleIgnoringDisplayStatus = stack.checkKeyguardVisibility(this, this.visibleIgnoringKeyguard, isTop && isTopNotPinnedStack);
        return xpWindowManager.shouldAlwaysVisible(getActivityType()) ? !isDisplaySleeping : visibleIgnoringDisplayStatus && !isDisplaySleeping;
    }

    boolean shouldBeVisible() {
        ActivityStack stack = getActivityStack();
        if (stack == null) {
            return false;
        }
        return shouldBeVisible(!stack.shouldBeVisible(null));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeVisibleIfNeeded(ActivityRecord starting, boolean reportToClient) {
        if (this.mState == ActivityStack.ActivityState.RESUMED || this == starting) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.d("ActivityTaskManager", "Not making visible, r=" + this + " state=" + this.mState + " starting=" + starting);
                return;
            }
            return;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("ActivityTaskManager", "Making visible and scheduling visibility: " + this);
        }
        ActivityStack stack = getActivityStack();
        try {
            if (stack.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(this.returningOptions);
                stack.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisible(true);
            this.sleeping = false;
            this.app.postPendingUiCleanMsg(true);
            if (reportToClient) {
                makeClientVisible();
            } else {
                this.mClientVisibilityDeferred = true;
            }
            this.mStackSupervisor.mStoppingActivities.remove(this);
            this.mStackSupervisor.mGoingToSleepActivities.remove(this);
        } catch (Exception e) {
            Slog.w("ActivityTaskManager", "Exception thrown making visible: " + this.intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeClientVisible() {
        this.mClientVisibilityDeferred = false;
        try {
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ClientTransactionItem) WindowVisibilityItem.obtain(true));
            makeActiveIfNeeded(null);
            if (isState(ActivityStack.ActivityState.STOPPING, ActivityStack.ActivityState.STOPPED) && isFocusable()) {
                setState(ActivityStack.ActivityState.PAUSED, "makeClientVisible");
            }
        } catch (Exception e) {
            Slog.w("ActivityTaskManager", "Exception thrown sending visibility update: " + this.intent.getComponent(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean makeActiveIfNeeded(ActivityRecord activeActivity) {
        if (shouldResumeActivity(activeActivity)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("TAG_VISIBILITY", "Resume visible activity, " + this);
            }
            return getActivityStack().resumeTopActivityUncheckedLocked(activeActivity, null);
        }
        if (shouldPauseActivity(activeActivity)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("TAG_VISIBILITY", "Pause visible activity, " + this);
            }
            setState(ActivityStack.ActivityState.PAUSING, "makeVisibleIfNeeded");
            try {
                this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ActivityLifecycleItem) PauseActivityItem.obtain(this.finishing, false, this.configChangeFlags, false));
            } catch (Exception e) {
                Slog.w("ActivityTaskManager", "Exception thrown sending pause: " + this.intent.getComponent(), e);
            }
        }
        return false;
    }

    private boolean shouldPauseActivity(ActivityRecord activeActivity) {
        return (!shouldMakeActive(activeActivity) || isFocusable() || isState(ActivityStack.ActivityState.PAUSING, ActivityStack.ActivityState.PAUSED)) ? false : true;
    }

    @VisibleForTesting
    boolean shouldResumeActivity(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && isFocusable() && !isState(ActivityStack.ActivityState.RESUMED) && getActivityStack().getVisibility(activeActivity) == 0;
    }

    @VisibleForTesting
    boolean shouldMakeActive(ActivityRecord activeActivity) {
        if (isState(ActivityStack.ActivityState.RESUMED, ActivityStack.ActivityState.PAUSED, ActivityStack.ActivityState.STOPPED, ActivityStack.ActivityState.STOPPING) && getActivityStack().mTranslucentActivityWaiting == null && this != activeActivity && this.mStackSupervisor.readyToResume() && !this.mLaunchTaskBehind) {
            int positionInTask = this.task.mActivities.indexOf(this);
            if (positionInTask == -1) {
                throw new IllegalStateException("Activity not found in its task");
            }
            if (positionInTask == this.task.mActivities.size() - 1) {
                return true;
            }
            ActivityRecord activityAbove = this.task.mActivities.get(positionInTask + 1);
            return activityAbove.finishing && this.results == null;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleAlreadyVisible() {
        stopFreezingScreenLocked(false);
        try {
            if (this.returningOptions != null) {
                this.app.getThread().scheduleOnNewActivityOptions(this.appToken, this.returningOptions.toBundle());
            }
        } catch (RemoteException e) {
        }
        return this.mState == ActivityStack.ActivityState.RESUMED;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void activityResumedLocked(IBinder token) {
        ActivityRecord r = forTokenLocked(token);
        if (ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityTaskManager", "Resumed activity; dropping state of: " + r);
        }
        if (r == null) {
            return;
        }
        r.icicle = null;
        r.haveState = false;
        ActivityDisplay display = r.getDisplay();
        if (display != null) {
            display.handleActivitySizeCompatModeIfNeeded(r);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void completeResumeLocked() {
        boolean wasVisible = this.visible;
        setVisible(true);
        if (!wasVisible) {
            this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
        }
        this.idle = false;
        this.results = null;
        this.newIntents = null;
        this.stopped = false;
        if (isActivityTypeHome()) {
            this.mStackSupervisor.updateHomeProcess(this.task.mActivities.get(0).app);
        }
        if (this.nowVisible) {
            this.mStackSupervisor.stopWaitingForActivityVisible(this);
        }
        this.mStackSupervisor.scheduleIdleTimeoutLocked(this);
        this.mStackSupervisor.reportResumedActivityLocked(this);
        resumeKeyDispatchingLocked();
        ActivityStack stack = getActivityStack();
        this.mStackSupervisor.mNoAnimActivities.clear();
        if (hasProcess()) {
            this.cpuTimeAtResume = this.app.getCpuTime();
        } else {
            this.cpuTimeAtResume = 0L;
        }
        this.returningOptions = null;
        if (canTurnScreenOn()) {
            this.mStackSupervisor.wakeUp("turnScreenOnFlag");
        } else {
            stack.checkReadyForSleep();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void activityStoppedLocked(Bundle newIcicle, PersistableBundle newPersistentState, CharSequence description) {
        ActivityStack stack = getActivityStack();
        boolean isStopping = this.mState == ActivityStack.ActivityState.STOPPING;
        if (!isStopping && this.mState != ActivityStack.ActivityState.RESTARTING_PROCESS) {
            Slog.i("ActivityTaskManager", "Activity reported stop, but no longer stopping: " + this);
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_SELECT_MEDIA_FUNCTION, this);
            return;
        }
        if (newPersistentState != null) {
            this.persistentState = newPersistentState;
            this.mAtmService.notifyTaskPersisterLocked(this.task, false);
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityTaskManager", "Saving icicle of " + this + ": " + this.icicle);
        }
        if (newIcicle != null) {
            this.icicle = newIcicle;
            this.haveState = true;
            this.launchCount = 0;
            updateTaskDescription(description);
        }
        if (!this.stopped) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Moving to STOPPED: " + this + " (stop complete)");
            }
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_SELECT_MEDIA_FUNCTION, this);
            this.stopped = true;
            if (isStopping) {
                setState(ActivityStack.ActivityState.STOPPED, "activityStoppedLocked");
            }
            AppWindowToken appWindowToken = this.mAppWindowToken;
            if (appWindowToken != null) {
                appWindowToken.notifyAppStopped();
            }
            if (this.finishing) {
                clearOptionsLocked();
            } else if (this.deferRelaunchUntilPaused) {
                stack.destroyActivityLocked(this, true, "stop-config");
                this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            } else {
                this.mRootActivityContainer.updatePreviousProcess(this);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startLaunchTickingLocked() {
        if (!Build.IS_USER && this.launchTickTime == 0) {
            this.launchTickTime = SystemClock.uptimeMillis();
            continueLaunchTickingLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean continueLaunchTickingLocked() {
        ActivityStack stack;
        if (this.launchTickTime == 0 || (stack = getActivityStack()) == null) {
            return false;
        }
        Message msg = stack.mHandler.obtainMessage(HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION, this);
        stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION);
        stack.mHandler.sendMessageDelayed(msg, 500L);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishLaunchTickingLocked() {
        this.launchTickTime = 0L;
        ActivityStack stack = getActivityStack();
        if (stack != null) {
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION);
        }
    }

    public boolean mayFreezeScreenLocked(WindowProcessController app) {
        return (!hasProcess() || app.isCrashing() || app.isNotResponding()) ? false : true;
    }

    public void startFreezingScreenLocked(WindowProcessController app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            AppWindowToken appWindowToken = this.mAppWindowToken;
            if (appWindowToken == null) {
                Slog.w("WindowManager", "Attempted to freeze screen with non-existing app token: " + this.appToken);
                return;
            }
            int freezableConfigChanges = (-536870913) & configChanges;
            if (freezableConfigChanges == 0 && appWindowToken.okToDisplay()) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v("WindowManager", "Skipping set freeze of " + this.appToken);
                    return;
                }
                return;
            }
            Slog.w("WindowManager", "startFreezingScreenLocked app token: " + this.appToken + " configChanges=0x" + Integer.toHexString(configChanges) + " freezableConfigChanges=" + freezableConfigChanges);
            this.mAppWindowToken.startFreezingScreen();
        }
    }

    public void stopFreezingScreenLocked(boolean force) {
        if (force || this.frozenBeforeDestroy) {
            this.frozenBeforeDestroy = false;
            if (this.mAppWindowToken == null) {
                return;
            }
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v("WindowManager", "Clear freezing of " + this.appToken + ": hidden=" + this.mAppWindowToken.isHidden() + " freezing=" + this.mAppWindowToken.isFreezingScreen());
            }
            this.mAppWindowToken.stopFreezingScreen(true, force);
        }
    }

    public void reportFullyDrawnLocked(boolean restoredFromBundle) {
        ActivityMetricsLogger.WindowingModeTransitionInfoSnapshot info = this.mStackSupervisor.getActivityMetricsLogger().logAppTransitionReportedDrawn(this, restoredFromBundle);
        if (info != null) {
            this.mStackSupervisor.reportActivityLaunchedLocked(false, this, info.windowsFullyDrawnDelayMs, info.getLaunchState());
        }
    }

    public void onStartingWindowDrawn(long timestamp) {
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.getActivityMetricsLogger().notifyStartingWindowDrawn(getWindowingMode(), timestamp);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void onWindowsDrawn(boolean drawn, long timestamp) {
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mDrawn = drawn;
                if (!drawn) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityMetricsLogger.WindowingModeTransitionInfoSnapshot info = this.mStackSupervisor.getActivityMetricsLogger().notifyWindowsDrawn(getWindowingMode(), timestamp);
                int windowsDrawnDelayMs = info != null ? info.windowsDrawnDelayMs : -1;
                int launchState = info != null ? info.getLaunchState() : -1;
                this.mStackSupervisor.reportActivityLaunchedLocked(false, this, windowsDrawnDelayMs, launchState);
                this.mStackSupervisor.stopWaitingForActivityVisible(this);
                finishLaunchTickingLocked();
                if (this.task != null) {
                    this.task.hasBeenVisible = true;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void onWindowsVisible() {
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.stopWaitingForActivityVisible(this);
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                    Log.v("ActivityTaskManager", "windowsVisibleLocked(): " + this);
                }
                if (!this.nowVisible) {
                    this.nowVisible = true;
                    this.lastVisibleTime = SystemClock.uptimeMillis();
                    this.mAtmService.scheduleAppGcsLocked();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void onWindowsGone() {
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                    Log.v("ActivityTaskManager", "windowsGone(): " + this);
                }
                this.nowVisible = false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAnimationFinished() {
        if (this.mRootActivityContainer.allResumedActivitiesIdle() || this.mStackSupervisor.isStoppingNoHistoryActivity()) {
            if (this.mStackSupervisor.mStoppingActivities.contains(this)) {
                this.mStackSupervisor.scheduleIdleLocked();
                return;
            }
            return;
        }
        this.mStackSupervisor.processStoppingActivitiesLocked(null, false, true);
    }

    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        ActivityRecord anrActivity;
        WindowProcessController anrApp;
        boolean windowFromSameProcessAsActivity;
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                anrActivity = getWaitingHistoryRecordLocked();
                anrApp = this.app;
                if (hasProcess() && this.app.getPid() != windowPid && windowPid != -1) {
                    windowFromSameProcessAsActivity = false;
                }
                windowFromSameProcessAsActivity = true;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (windowFromSameProcessAsActivity) {
            return this.mAtmService.mAmInternal.inputDispatchingTimedOut(anrApp.mOwner, anrActivity.shortComponentName, anrActivity.appInfo, this.shortComponentName, this.app, false, reason);
        }
        return this.mAtmService.mAmInternal.inputDispatchingTimedOut(windowPid, false, reason) < 0;
    }

    private ActivityRecord getWaitingHistoryRecordLocked() {
        if (this.stopped) {
            ActivityStack stack = this.mRootActivityContainer.getTopDisplayFocusedStack();
            ActivityRecord r = stack.getResumedActivity();
            if (r == null) {
                r = stack.mPausingActivity;
            }
            if (r != null) {
                return r;
            }
        }
        return this;
    }

    public boolean okToShowLocked() {
        if (StorageManager.isUserKeyUnlocked(this.mUserId) || this.info.applicationInfo.isEncryptionAware()) {
            return (this.info.flags & 1024) != 0 || (this.mStackSupervisor.isCurrentProfileLocked(this.mUserId) && this.mAtmService.mAmInternal.isUserRunning(this.mUserId, 0));
        }
        return false;
    }

    public boolean isInterestingToUserLocked() {
        return this.visible || this.nowVisible || this.mState == ActivityStack.ActivityState.PAUSING || this.mState == ActivityStack.ActivityState.RESUMED;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSleeping(boolean _sleeping) {
        setSleeping(_sleeping, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSleeping(boolean _sleeping, boolean force) {
        if ((force || this.sleeping != _sleeping) && attachedToProcess()) {
            try {
                this.app.getThread().scheduleSleeping(this.appToken, _sleeping);
                if (_sleeping && !this.mStackSupervisor.mGoingToSleepActivities.contains(this)) {
                    this.mStackSupervisor.mGoingToSleepActivities.add(this);
                }
                this.sleeping = _sleeping;
            } catch (RemoteException e) {
                Slog.w("ActivityTaskManager", "Exception thrown when sleeping: " + this.intent.getComponent(), e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        ActivityRecord r = forTokenLocked(token);
        if (r == null) {
            return -1;
        }
        TaskRecord task = r.task;
        int activityNdx = task.mActivities.indexOf(r);
        if (activityNdx < 0 || (onlyRoot && activityNdx > task.findEffectiveRootIndex())) {
            return -1;
        }
        return task.taskId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ActivityRecord isInStackLocked(IBinder token) {
        ActivityRecord r = forTokenLocked(token);
        if (r != null) {
            return r.getActivityStack().isInStackLocked(r);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ActivityStack getStackLocked(IBinder token) {
        ActivityRecord r = isInStackLocked(token);
        if (r != null) {
            return r.getActivityStack();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDisplayId() {
        ActivityStack stack = getActivityStack();
        if (stack == null) {
            return -1;
        }
        return stack.mDisplayId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isDestroyable() {
        ActivityStack stack;
        return (this.finishing || !hasProcess() || (stack = getActivityStack()) == null || this == stack.getResumedActivity() || this == stack.mPausingActivity || !this.haveState || !this.stopped || this.visible) ? false : true;
    }

    private static String createImageFilename(long createTime, int taskId) {
        return String.valueOf(taskId) + ACTIVITY_ICON_SUFFIX + createTime + ".png";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskDescription(ActivityManager.TaskDescription _taskDescription) {
        Bitmap icon;
        if (_taskDescription.getIconFilename() == null && (icon = _taskDescription.getIcon()) != null) {
            String iconFilename = createImageFilename(this.createTime, this.task.taskId);
            File iconFile = new File(TaskPersister.getUserImagesDir(this.task.userId), iconFilename);
            String iconFilePath = iconFile.getAbsolutePath();
            this.mAtmService.getRecentTasks().saveImage(icon, iconFilePath);
            _taskDescription.setIconFilename(iconFilePath);
        }
        this.taskDescription = _taskDescription;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setVoiceSessionLocked(IVoiceInteractionSession session) {
        this.voiceSession = session;
        this.pendingVoiceInteractionStart = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearVoiceSessionLocked() {
        this.voiceSession = null;
        this.pendingVoiceInteractionStart = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch) {
        showStartingWindow(prev, newTask, taskSwitch, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch, boolean fromRecents) {
        if (this.mAppWindowToken == null || this.mTaskOverlay) {
            return;
        }
        ActivityOptions activityOptions = this.pendingOptions;
        if ((activityOptions != null && activityOptions.getAnimationType() == 5) || getWindowingMode() == 5) {
            return;
        }
        CompatibilityInfo compatInfo = this.mAtmService.compatibilityInfoForPackageLocked(this.info.applicationInfo);
        boolean shown = addStartingWindow(this.packageName, this.theme, compatInfo, this.nonLocalizedLabel, this.labelRes, this.icon, this.logo, this.windowFlags, prev != null ? prev.appToken : null, newTask, taskSwitch, isProcessRunning(), allowTaskSnapshot(), this.mState.ordinal() >= ActivityStack.ActivityState.RESUMED.ordinal() && this.mState.ordinal() <= ActivityStack.ActivityState.STOPPED.ordinal(), fromRecents);
        if (shown) {
            this.mStartingWindowState = 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeOrphanedStartingWindow(boolean behindFullscreenActivity) {
        if (this.mStartingWindowState == 1 && behindFullscreenActivity) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w("ActivityTaskManager", "Found orphaned starting window " + this);
            }
            this.mStartingWindowState = 2;
            this.mAppWindowToken.removeStartingWindow();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRequestedOrientation(int requestedOrientation) {
        setOrientation(requestedOrientation, mayFreezeScreenLocked(this.app));
        this.mAtmService.getTaskChangeNotificationController().notifyActivityRequestedOrientationChanged(this.task.taskId, requestedOrientation);
    }

    private void setOrientation(int requestedOrientation, boolean freezeScreenIfNeeded) {
        IApplicationToken.Stub stub;
        if (this.mAppWindowToken == null) {
            Slog.w("WindowManager", "Attempted to set orientation of non-existing app token: " + this.appToken);
            return;
        }
        IBinder binder = (!freezeScreenIfNeeded || (stub = this.appToken) == null) ? null : stub.asBinder();
        this.mAppWindowToken.setOrientation(requestedOrientation, binder, this);
        if (!getMergedOverrideConfiguration().equals(this.mLastReportedConfiguration.getMergedConfiguration())) {
            ensureActivityConfiguration(0, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getOrientation() {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            return this.info.screenOrientation;
        }
        return appWindowToken.getOrientationIgnoreVisibility();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisablePreviewScreenshots(boolean disable) {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            Slog.w("WindowManager", "Attempted to set disable screenshots of non-existing app token: " + this.appToken);
            return;
        }
        appWindowToken.setDisablePreviewScreenshots(disable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastReportedGlobalConfiguration(Configuration config) {
        this.mLastReportedConfiguration.setGlobalConfiguration(config);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastReportedConfiguration(MergedConfiguration config) {
        setLastReportedConfiguration(config.getGlobalConfiguration(), config.getOverrideConfiguration());
    }

    private void setLastReportedConfiguration(Configuration global, Configuration override) {
        this.mLastReportedConfiguration.setConfiguration(global, override);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRequestedConfigurationOrientation() {
        int screenOrientation = getOrientation();
        if (screenOrientation == 5) {
            ActivityDisplay display = getDisplay();
            if (display != null && display.mDisplayContent != null) {
                return display.mDisplayContent.getNaturalOrientation();
            }
            return 0;
        } else if (screenOrientation == 14) {
            return getConfiguration().orientation;
        } else {
            if (ActivityInfo.isFixedOrientationLandscape(screenOrientation)) {
                return 2;
            }
            if (ActivityInfo.isFixedOrientationPortrait(screenOrientation)) {
                return 1;
            }
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean inSizeCompatMode() {
        if (shouldUseSizeCompatMode()) {
            Configuration resolvedConfig = getResolvedOverrideConfiguration();
            Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
            if (resolvedAppBounds == null) {
                return false;
            }
            Configuration parentConfig = getParent().getConfiguration();
            if (parentConfig.densityDpi != resolvedConfig.densityDpi) {
                return true;
            }
            Rect parentAppBounds = parentConfig.windowConfiguration.getAppBounds();
            int appWidth = resolvedAppBounds.width();
            int appHeight = resolvedAppBounds.height();
            int parentAppWidth = parentAppBounds.width();
            int parentAppHeight = parentAppBounds.height();
            if (parentAppWidth == appWidth && parentAppHeight == appHeight) {
                return false;
            }
            if ((parentAppWidth <= appWidth || parentAppHeight <= appHeight) && parentAppWidth >= appWidth && parentAppHeight >= appHeight) {
                if (this.info.maxAspectRatio > 0.0f) {
                    float aspectRatio = (Math.max(appWidth, appHeight) + 0.5f) / Math.min(appWidth, appHeight);
                    if (aspectRatio >= this.info.maxAspectRatio) {
                        return false;
                    }
                }
                if (this.info.minAspectRatio > 0.0f) {
                    float parentAspectRatio = (Math.max(parentAppWidth, parentAppHeight) + 0.5f) / Math.min(parentAppWidth, parentAppHeight);
                    if (parentAspectRatio <= this.info.minAspectRatio) {
                        return false;
                    }
                }
                return true;
            }
            return true;
        }
        return false;
    }

    boolean shouldUseSizeCompatMode() {
        return !isResizeable() && (this.info.isFixedOrientation() || this.info.hasFixedAspectRatio()) && isActivityTypeStandard() && !this.mAtmService.mForceResizableActivities;
    }

    private void updateOverrideConfiguration() {
        Configuration overrideConfig = this.mTmpConfig;
        if (shouldUseSizeCompatMode()) {
            if (this.mCompatDisplayInsets != null) {
                return;
            }
            Configuration parentConfig = getParent().getConfiguration();
            if (!hasProcess() && !isConfigurationCompatible(parentConfig)) {
                return;
            }
            overrideConfig.unset();
            overrideConfig.colorMode = parentConfig.colorMode;
            overrideConfig.densityDpi = parentConfig.densityDpi;
            overrideConfig.screenLayout = parentConfig.screenLayout & 63;
            overrideConfig.smallestScreenWidthDp = parentConfig.smallestScreenWidthDp;
            ActivityDisplay display = getDisplay();
            if (display != null && display.mDisplayContent != null) {
                this.mCompatDisplayInsets = new CompatDisplayInsets(display.mDisplayContent);
            }
        } else {
            computeBounds(this.mTmpBounds, getParent().getWindowConfiguration().getAppBounds());
            if (this.mTmpBounds.equals(getRequestedOverrideBounds())) {
                return;
            }
            overrideConfig.unset();
            overrideConfig.windowConfiguration.setBounds(this.mTmpBounds);
        }
        onRequestedOverrideConfigurationChanged(overrideConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        if (this.mCompatDisplayInsets != null) {
            resolveSizeCompatModeConfiguration(newParentConfiguration);
        } else {
            super.resolveOverrideConfiguration(newParentConfiguration);
            if (!matchParentBounds()) {
                this.task.computeConfigResourceOverrides(getResolvedOverrideConfiguration(), newParentConfiguration);
            }
        }
        int i = this.mConfigurationSeq + 1;
        this.mConfigurationSeq = i;
        this.mConfigurationSeq = Math.max(i, 1);
        getResolvedOverrideConfiguration().seq = this.mConfigurationSeq;
    }

    private void resolveSizeCompatModeConfiguration(Configuration newParentConfiguration) {
        Configuration resolvedConfig = getResolvedOverrideConfiguration();
        Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        int parentRotation = newParentConfiguration.windowConfiguration.getRotation();
        int parentOrientation = newParentConfiguration.orientation;
        int orientation = getConfiguration().orientation;
        if (orientation != parentOrientation && isConfigurationCompatible(newParentConfiguration)) {
            orientation = parentOrientation;
        } else if (!resolvedBounds.isEmpty() && getWindowConfiguration().getRotation() == parentRotation) {
            return;
        } else {
            int requestedOrientation = getRequestedConfigurationOrientation();
            if (requestedOrientation != 0) {
                orientation = requestedOrientation;
            }
        }
        super.resolveOverrideConfiguration(newParentConfiguration);
        boolean useParentOverrideBounds = false;
        Rect displayBounds = this.mTmpBounds;
        Rect containingAppBounds = new Rect();
        if (this.task.handlesOrientationChangeFromDescendant()) {
            this.mCompatDisplayInsets.getDisplayBoundsByOrientation(displayBounds, orientation);
        } else {
            int baseOrientation = this.task.getParent().getConfiguration().orientation;
            this.mCompatDisplayInsets.getDisplayBoundsByOrientation(displayBounds, baseOrientation);
            this.task.computeFullscreenBounds(containingAppBounds, this, displayBounds, baseOrientation);
            useParentOverrideBounds = !containingAppBounds.isEmpty();
        }
        int baseOrientation2 = containingAppBounds.left;
        int containingOffsetY = containingAppBounds.top;
        if (!useParentOverrideBounds) {
            containingAppBounds.set(displayBounds);
        }
        if (parentRotation != -1) {
            TaskRecord.intersectWithInsetsIfFits(containingAppBounds, displayBounds, this.mCompatDisplayInsets.mNonDecorInsets[parentRotation]);
        }
        computeBounds(resolvedBounds, containingAppBounds);
        if (resolvedBounds.isEmpty()) {
            resolvedBounds.set(useParentOverrideBounds ? containingAppBounds : displayBounds);
        } else {
            resolvedBounds.left += baseOrientation2;
            resolvedBounds.top += containingOffsetY;
        }
        this.task.computeConfigResourceOverrides(resolvedConfig, newParentConfiguration, this.mCompatDisplayInsets);
        Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        Rect parentAppBounds = newParentConfiguration.windowConfiguration.getAppBounds();
        if (resolvedBounds.width() < parentAppBounds.width()) {
            resolvedBounds.right -= resolvedAppBounds.left;
        }
        if (resolvedConfig.screenWidthDp == resolvedConfig.screenHeightDp) {
            resolvedConfig.orientation = newParentConfiguration.orientation;
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        if (getMergedOverrideConfiguration().seq != getResolvedOverrideConfiguration().seq) {
            onMergedOverrideConfigurationChanged();
        }
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            return;
        }
        Configuration appWindowTokenRequestedOverrideConfig = appWindowToken.getRequestedOverrideConfiguration();
        if (appWindowTokenRequestedOverrideConfig.seq != getResolvedOverrideConfiguration().seq) {
            appWindowTokenRequestedOverrideConfig.seq = getResolvedOverrideConfiguration().seq;
            this.mAppWindowToken.onMergedOverrideConfigurationChanged();
        }
        ActivityDisplay display = getDisplay();
        if (display == null) {
            return;
        }
        if (this.visible) {
            display.handleActivitySizeCompatModeIfNeeded(this);
        } else if (shouldUseSizeCompatMode()) {
            int displayChanges = display.getLastOverrideConfigurationChanges();
            boolean hasNonOrienSizeChanged = hasResizeChange(displayChanges) && (displayChanges & 536872064) != 536872064;
            if (hasNonOrienSizeChanged || (displayChanges & 4096) != 0) {
                restartProcessIfVisible();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isConfigurationCompatible(Configuration config) {
        int orientation = getOrientation();
        if (!ActivityInfo.isFixedOrientationPortrait(orientation) || config.orientation == 1) {
            return !ActivityInfo.isFixedOrientationLandscape(orientation) || config.orientation == 2;
        }
        return false;
    }

    private void computeBounds(Rect outBounds, Rect containingAppBounds) {
        boolean adjustWidth;
        outBounds.setEmpty();
        float maxAspectRatio = this.info.maxAspectRatio;
        ActivityStack stack = getActivityStack();
        float minAspectRatio = this.info.minAspectRatio;
        TaskRecord taskRecord = this.task;
        if (taskRecord != null && stack != null && !taskRecord.inMultiWindowMode()) {
            if ((maxAspectRatio == 0.0f && minAspectRatio == 0.0f) || isInVrUiMode(getConfiguration())) {
                return;
            }
            int containingAppWidth = containingAppBounds.width();
            int containingAppHeight = containingAppBounds.height();
            float containingRatio = Math.max(containingAppWidth, containingAppHeight) / Math.min(containingAppWidth, containingAppHeight);
            int activityWidth = containingAppWidth;
            int activityHeight = containingAppHeight;
            if (containingRatio > maxAspectRatio && maxAspectRatio != 0.0f) {
                if (containingAppWidth < containingAppHeight) {
                    activityHeight = (int) ((activityWidth * maxAspectRatio) + 0.5f);
                } else {
                    activityWidth = (int) ((activityHeight * maxAspectRatio) + 0.5f);
                }
            } else if (containingRatio < minAspectRatio) {
                int requestedConfigurationOrientation = getRequestedConfigurationOrientation();
                if (requestedConfigurationOrientation == 1) {
                    adjustWidth = true;
                } else if (requestedConfigurationOrientation == 2) {
                    adjustWidth = false;
                } else if (containingAppWidth < containingAppHeight) {
                    adjustWidth = true;
                } else {
                    adjustWidth = false;
                }
                if (adjustWidth) {
                    activityWidth = (int) ((activityHeight / minAspectRatio) + 0.5f);
                } else {
                    activityHeight = (int) ((activityWidth / minAspectRatio) + 0.5f);
                }
            }
            if (containingAppWidth <= activityWidth && containingAppHeight <= activityHeight) {
                outBounds.set(getRequestedOverrideBounds());
            } else {
                outBounds.set(0, 0, containingAppBounds.left + activityWidth, containingAppBounds.top + activityHeight);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldUpdateConfigForDisplayChanged() {
        return this.mLastReportedDisplayId != getDisplayId();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow) {
        return ensureActivityConfiguration(globalChanges, preserveWindow, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow, boolean ignoreVisibility) {
        ActivityStack stack = getActivityStack();
        if (stack.mConfigWillChange) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Skipping config check (will change): " + this);
            }
            return true;
        } else if (this.finishing) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Configuration doesn't matter in finishing " + this);
            }
            stopFreezingScreenLocked(false);
            return true;
        } else if (!ignoreVisibility && (this.mState == ActivityStack.ActivityState.STOPPING || this.mState == ActivityStack.ActivityState.STOPPED || !shouldBeVisible())) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Skipping config check invisible: " + this);
            }
            return true;
        } else {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Ensuring correct configuration: " + this);
            }
            int newDisplayId = getDisplayId();
            boolean displayChanged = this.mLastReportedDisplayId != newDisplayId;
            if (displayChanged) {
                this.mLastReportedDisplayId = newDisplayId;
            }
            updateOverrideConfiguration();
            this.mTmpConfig.setTo(this.mLastReportedConfiguration.getMergedConfiguration());
            if (getConfiguration().equals(this.mTmpConfig) && !this.forceNewConfig && !displayChanged) {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityTaskManager", "Configuration & display unchanged in " + this);
                }
                return true;
            }
            int changes = getConfigurationChanges(this.mTmpConfig);
            Configuration newMergedOverrideConfig = getMergedOverrideConfiguration();
            setLastReportedConfiguration(this.mAtmService.getGlobalConfiguration(), newMergedOverrideConfig);
            if (this.mState == ActivityStack.ActivityState.INITIALIZING) {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityTaskManager", "Skipping config check for initializing activity: " + this);
                }
                return true;
            } else if (changes == 0 && !this.forceNewConfig) {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityTaskManager", "Configuration no differences in " + this);
                }
                if (displayChanged) {
                    scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig);
                } else {
                    scheduleConfigurationChanged(newMergedOverrideConfig);
                }
                return true;
            } else {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityTaskManager", "Configuration changes for " + this + ", allChanges=" + Configuration.configurationDiffToString(changes));
                }
                if (!attachedToProcess()) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                        Slog.v("ActivityTaskManager", "Configuration doesn't matter not running " + this);
                    }
                    stopFreezingScreenLocked(false);
                    this.forceNewConfig = false;
                    return true;
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityTaskManager", "Checking to restart " + this.info.name + ": changed=0x" + Integer.toHexString(changes) + ", handles=0x" + Integer.toHexString(this.info.getRealConfigChanged()) + ", mLastReportedConfiguration=" + this.mLastReportedConfiguration);
                }
                TaskRecord taskRecord = this.task;
                if (taskRecord != null && taskRecord.getTask() != null) {
                    boolean sharedResizing = this.task.getTask().isSharedResizing();
                    if (sharedResizing) {
                        return true;
                    }
                }
                if (shouldRelaunchLocked(changes, this.mTmpConfig) || this.forceNewConfig) {
                    this.configChangeFlags |= changes;
                    startFreezingScreenLocked(this.app, globalChanges);
                    this.forceNewConfig = false;
                    boolean preserveWindow2 = preserveWindow & isResizeOnlyChange(changes);
                    boolean hasResizeChange = hasResizeChange((~this.info.getRealConfigChanged()) & changes);
                    if (hasResizeChange) {
                        boolean isDragResizing = getTaskRecord().getTask().isDragResizing();
                        this.mRelaunchReason = isDragResizing ? 2 : 1;
                    } else {
                        this.mRelaunchReason = 0;
                    }
                    if (!attachedToProcess()) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityTaskManager", "Config is destroying non-running " + this);
                        }
                        stack.destroyActivityLocked(this, true, xpWindowManagerService.WindowConfigJson.KEY_CONFIG);
                    } else if (this.mState == ActivityStack.ActivityState.PAUSING) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityTaskManager", "Config is skipping already pausing " + this);
                        }
                        this.deferRelaunchUntilPaused = true;
                        this.preserveWindowOnDeferredRelaunch = preserveWindow2;
                        return true;
                    } else if (this.mState == ActivityStack.ActivityState.RESUMED) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityTaskManager", "Config is relaunching resumed " + this);
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_STATES && !this.visible) {
                            Slog.v("ActivityTaskManager", "Config is relaunching resumed invisible activity " + this + " called by " + Debug.getCallers(4));
                        }
                        relaunchActivityLocked(true, preserveWindow2);
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityTaskManager", "Config is relaunching non-resumed " + this);
                        }
                        relaunchActivityLocked(false, preserveWindow2);
                    }
                    return false;
                }
                if (displayChanged) {
                    scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig);
                } else {
                    scheduleConfigurationChanged(newMergedOverrideConfig);
                }
                stopFreezingScreenLocked(false);
                return true;
            }
        }
    }

    private boolean shouldRelaunchLocked(int changes, Configuration changesConfig) {
        int configChanged = this.info.getRealConfigChanged();
        boolean onlyVrUiModeChanged = onlyVrUiModeChanged(changes, changesConfig);
        if (this.appInfo.targetSdkVersion < 26 && this.requestedVrComponent != null && onlyVrUiModeChanged) {
            configChanged |= 512;
        }
        if (this.mState == ActivityStack.ActivityState.RESUMED || this.nowVisible) {
            if ((changes & 512) == 512) {
                return false;
            }
            configChanged |= 512;
            if ((changes & Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                return false;
            }
        }
        return (((changes & 32) == 32 && (changes & 16) == 16) || ((~configChanged) & changes) == 0) ? false : true;
    }

    private boolean onlyVrUiModeChanged(int changes, Configuration lastReportedConfig) {
        Configuration currentConfig = getConfiguration();
        return changes == 512 && isInVrUiMode(currentConfig) != isInVrUiMode(lastReportedConfig);
    }

    private int getConfigurationChanges(Configuration lastReportedConfig) {
        Configuration currentConfig = getConfiguration();
        int changes = lastReportedConfig.diff(currentConfig);
        if ((changes & 1024) != 0) {
            boolean crosses = crossesHorizontalSizeThreshold(lastReportedConfig.screenWidthDp, currentConfig.screenWidthDp) || crossesVerticalSizeThreshold(lastReportedConfig.screenHeightDp, currentConfig.screenHeightDp);
            if (!crosses) {
                changes &= -1025;
            }
        }
        if ((changes & 2048) != 0) {
            int oldSmallest = lastReportedConfig.smallestScreenWidthDp;
            int newSmallest = currentConfig.smallestScreenWidthDp;
            if (!crossesSmallestSizeThreshold(oldSmallest, newSmallest)) {
                changes &= -2049;
            }
        }
        if ((536870912 & changes) != 0) {
            return changes & (-536870913);
        }
        return changes;
    }

    private static boolean isResizeOnlyChange(int change) {
        return (change & (-3457)) == 0;
    }

    private static boolean hasResizeChange(int change) {
        return (change & 3456) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void relaunchActivityLocked(boolean andResume, boolean preserveWindow) {
        ResumeActivityItem obtain;
        if (this.mAtmService.mSuppressResizeConfigChanges && preserveWindow) {
            this.configChangeFlags = 0;
            return;
        }
        List<ResultInfo> pendingResults = null;
        List<ReferrerIntent> pendingNewIntents = null;
        if (andResume) {
            pendingResults = this.results;
            pendingNewIntents = this.newIntents;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.v("ActivityTaskManager", "Relaunching: " + this + " with results=" + pendingResults + " newIntents=" + pendingNewIntents + " andResume=" + andResume + " preserveWindow=" + preserveWindow);
        }
        EventLog.writeEvent(andResume ? EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY : EventLogTags.AM_RELAUNCH_ACTIVITY, Integer.valueOf(this.mUserId), Integer.valueOf(System.identityHashCode(this)), Integer.valueOf(this.task.taskId), this.shortComponentName);
        startFreezingScreenLocked(this.app, 0);
        try {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                StringBuilder sb = new StringBuilder();
                sb.append("Moving to ");
                sb.append(andResume ? "RESUMED" : "PAUSED");
                sb.append(" Relaunching ");
                sb.append(this);
                sb.append(" callers=");
                sb.append(Debug.getCallers(6));
                Slog.i("ActivityTaskManager", sb.toString());
            }
            this.forceNewConfig = false;
            this.mStackSupervisor.activityRelaunchingLocked(this);
            ActivityRelaunchItem obtain2 = ActivityRelaunchItem.obtain(pendingResults, pendingNewIntents, this.configChangeFlags, new MergedConfiguration(this.mAtmService.getGlobalConfiguration(), getMergedOverrideConfiguration()), preserveWindow);
            if (andResume) {
                obtain = ResumeActivityItem.obtain(getDisplay().mDisplayContent.isNextTransitionForward());
            } else {
                obtain = PauseActivityItem.obtain();
            }
            ClientTransaction transaction = ClientTransaction.obtain(this.app.getThread(), this.appToken);
            transaction.addCallback(obtain2);
            transaction.setLifecycleStateRequest(obtain);
            this.mAtmService.getLifecycleManager().scheduleTransaction(transaction);
        } catch (RemoteException e) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.i("ActivityTaskManager", "Relaunch failed", e);
            }
        }
        if (andResume) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.d("ActivityTaskManager", "Resumed after relaunch " + this);
            }
            this.results = null;
            this.newIntents = null;
            this.mAtmService.getAppWarningsLocked().onResumeActivity(this);
        } else {
            ActivityStack stack = getActivityStack();
            if (stack != null) {
                stack.mHandler.removeMessages(101, this);
            }
            setState(ActivityStack.ActivityState.PAUSED, "relaunchActivityLocked");
        }
        this.configChangeFlags = 0;
        this.deferRelaunchUntilPaused = false;
        this.preserveWindowOnDeferredRelaunch = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void restartProcessIfVisible() {
        Slog.i("ActivityTaskManager", "Request to restart process of " + this);
        getRequestedOverrideConfiguration().unset();
        getResolvedOverrideConfiguration().unset();
        this.mCompatDisplayInsets = null;
        if (this.visible) {
            updateOverrideConfiguration();
        }
        if (!attachedToProcess()) {
            return;
        }
        setState(ActivityStack.ActivityState.RESTARTING_PROCESS, "restartActivityProcess");
        if (!this.visible || this.haveState) {
            this.mAtmService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityRecord$rAbBM-9IZ5lau2L_lVdzsqLwNpA
                @Override // java.lang.Runnable
                public final void run() {
                    ActivityRecord.this.lambda$restartProcessIfVisible$0$ActivityRecord();
                }
            });
            return;
        }
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken != null) {
            appWindowToken.startFreezingScreen();
        }
        try {
            this.mAtmService.getLifecycleManager().scheduleTransaction(this.app.getThread(), (IBinder) this.appToken, (ActivityLifecycleItem) StopActivityItem.obtain(false, 0));
        } catch (RemoteException e) {
            Slog.w("ActivityTaskManager", "Exception thrown during restart " + this, e);
        }
        this.mStackSupervisor.scheduleRestartTimeout(this);
    }

    public /* synthetic */ void lambda$restartProcessIfVisible$0$ActivityRecord() {
        synchronized (this.mAtmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (hasProcess() && this.app.getReportedProcState() > 7) {
                    WindowProcessController wpc = this.app;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    this.mAtmService.mAmInternal.killProcess(wpc.mName, wpc.mUid, "resetConfig");
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private boolean isProcessRunning() {
        WindowProcessController proc = this.app;
        if (proc == null) {
            proc = (WindowProcessController) this.mAtmService.mProcessNames.get(this.processName, this.info.applicationInfo.uid);
        }
        return proc != null && proc.hasThread();
    }

    private boolean allowTaskSnapshot() {
        ArrayList<ReferrerIntent> arrayList = this.newIntents;
        if (arrayList == null) {
            return true;
        }
        for (int i = arrayList.size() - 1; i >= 0; i--) {
            Intent intent = this.newIntents.get(i);
            if (intent != null && !isMainIntent(intent)) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNoHistory() {
        return ((this.intent.getFlags() & 1073741824) == 0 && (this.info.flags & 128) == 0) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        out.attribute(null, ATTR_ID, String.valueOf(this.createTime));
        out.attribute(null, ATTR_LAUNCHEDFROMUID, String.valueOf(this.launchedFromUid));
        String str = this.launchedFromPackage;
        if (str != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMPACKAGE, str);
        }
        String str2 = this.resolvedType;
        if (str2 != null) {
            out.attribute(null, ATTR_RESOLVEDTYPE, str2);
        }
        out.attribute(null, ATTR_COMPONENTSPECIFIED, String.valueOf(this.componentSpecified));
        out.attribute(null, ATTR_USERID, String.valueOf(this.mUserId));
        ActivityManager.TaskDescription taskDescription = this.taskDescription;
        if (taskDescription != null) {
            taskDescription.saveToXml(out);
        }
        out.startTag(null, TAG_INTENT);
        this.intent.saveToXml(out);
        out.endTag(null, TAG_INTENT);
        if (isPersistable() && this.persistentState != null) {
            out.startTag(null, TAG_PERSISTABLEBUNDLE);
            this.persistentState.saveToXml(out);
            out.endTag(null, TAG_PERSISTABLEBUNDLE);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ActivityRecord restoreFromXml(XmlPullParser in, ActivityStackSupervisor stackSupervisor) throws IOException, XmlPullParserException {
        Intent intent;
        XmlPullParser xmlPullParser = in;
        Intent intent2 = null;
        PersistableBundle persistentState = null;
        int launchedFromUid = 0;
        String launchedFromPackage = null;
        String resolvedType = null;
        boolean componentSpecified = false;
        int userId = 0;
        long createTime = -1;
        int outerDepth = in.getDepth();
        ActivityManager.TaskDescription taskDescription = new ActivityManager.TaskDescription();
        int attrNdx = in.getAttributeCount() - 1;
        while (attrNdx >= 0) {
            String attrName = xmlPullParser.getAttributeName(attrNdx);
            String attrValue = xmlPullParser.getAttributeValue(attrNdx);
            if (ATTR_ID.equals(attrName)) {
                createTime = Long.parseLong(attrValue);
                intent = intent2;
            } else if (ATTR_LAUNCHEDFROMUID.equals(attrName)) {
                launchedFromUid = Integer.parseInt(attrValue);
                intent = intent2;
            } else if (ATTR_LAUNCHEDFROMPACKAGE.equals(attrName)) {
                launchedFromPackage = attrValue;
                intent = intent2;
            } else if (ATTR_RESOLVEDTYPE.equals(attrName)) {
                resolvedType = attrValue;
                intent = intent2;
            } else if (ATTR_COMPONENTSPECIFIED.equals(attrName)) {
                componentSpecified = Boolean.parseBoolean(attrValue);
                intent = intent2;
            } else if (ATTR_USERID.equals(attrName)) {
                userId = Integer.parseInt(attrValue);
                intent = intent2;
            } else if (attrName.startsWith("task_description_")) {
                taskDescription.restoreFromXml(attrName, attrValue);
                intent = intent2;
            } else {
                StringBuilder sb = new StringBuilder();
                intent = intent2;
                sb.append("Unknown ActivityRecord attribute=");
                sb.append(attrName);
                Log.d("ActivityTaskManager", sb.toString());
            }
            attrNdx--;
            xmlPullParser = in;
            intent2 = intent;
        }
        Intent intent3 = intent2;
        while (true) {
            int event = in.next();
            if (event == 1 || (event == 3 && in.getDepth() < outerDepth)) {
                break;
            } else if (event == 2) {
                String name = in.getName();
                if (TAG_INTENT.equals(name)) {
                    intent3 = Intent.restoreFromXml(in);
                } else if (TAG_PERSISTABLEBUNDLE.equals(name)) {
                    persistentState = PersistableBundle.restoreFromXml(in);
                } else {
                    Slog.w("ActivityTaskManager", "restoreActivity: unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }
        if (intent3 == null) {
            throw new XmlPullParserException("restoreActivity error intent=" + intent3);
        }
        ActivityTaskManagerService service = stackSupervisor.mService;
        ActivityInfo aInfo = stackSupervisor.resolveActivity(intent3, resolvedType, 0, null, userId, Binder.getCallingUid());
        if (aInfo == null) {
            throw new XmlPullParserException("restoreActivity resolver error. Intent=" + intent3 + " resolvedType=" + resolvedType);
        }
        ActivityRecord r = new ActivityRecord(service, null, 0, launchedFromUid, launchedFromPackage, intent3, resolvedType, aInfo, service.getConfiguration(), null, null, 0, componentSpecified, false, stackSupervisor, null, null);
        r.persistentState = persistentState;
        r.taskDescription = taskDescription;
        r.createTime = createTime;
        return r;
    }

    private static boolean isInVrUiMode(Configuration config) {
        return (config.uiMode & 15) == 7;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getUid() {
        return this.info.applicationInfo.uid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setShowWhenLocked(boolean showWhenLocked) {
        this.mShowWhenLocked = showWhenLocked;
        this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
        this.mInheritShownWhenLocked = inheritShowWhenLocked;
        this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowWhenLocked() {
        AppWindowToken appWindowToken;
        if (inPinnedWindowingMode() || (!this.mShowWhenLocked && ((appWindowToken = this.mAppWindowToken) == null || !appWindowToken.containsShowWhenLockedWindow()))) {
            if (this.mInheritShownWhenLocked) {
                ActivityRecord r = getActivityBelow();
                if (r != null && !r.inPinnedWindowingMode()) {
                    if (r.mShowWhenLocked) {
                        return true;
                    }
                    AppWindowToken appWindowToken2 = r.mAppWindowToken;
                    if (appWindowToken2 != null && appWindowToken2.containsShowWhenLockedWindow()) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
        return true;
    }

    private ActivityRecord getActivityBelow() {
        int pos = this.task.mActivities.indexOf(this);
        if (pos == -1) {
            throw new IllegalStateException("Activity not found in its task");
        }
        if (pos == 0) {
            return null;
        }
        return this.task.getChildAt(pos - 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTurnScreenOn(boolean turnScreenOn) {
        this.mTurnScreenOn = turnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canTurnScreenOn() {
        ActivityStack stack = getActivityStack();
        return this.mTurnScreenOn && stack != null && stack.checkKeyguardVisibility(this, true, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canResumeByCompat() {
        WindowProcessController windowProcessController = this.app;
        return windowProcessController == null || windowProcessController.updateTopResumingActivityInProcessIfNeeded(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getTurnScreenOnFlag() {
        return this.mTurnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopRunningActivity() {
        return this.mRootActivityContainer.topRunningActivity() == this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResumedActivityOnDisplay() {
        ActivityDisplay display = getDisplay();
        return display != null && this == display.getResumedActivity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        AppWindowToken appWindowToken = this.mAppWindowToken;
        if (appWindowToken == null) {
            Slog.w("WindowManager", "Attempted to register remote animations with non-existing app token: " + this.appToken);
            return;
        }
        appWindowToken.registerRemoteAnimations(definition);
    }

    public String toString() {
        if (this.stringName != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(this.stringName);
            sb.append(" t");
            TaskRecord taskRecord = this.task;
            sb.append(taskRecord == null ? -1 : taskRecord.taskId);
            sb.append(this.finishing ? " f}" : "}");
            return sb.toString();
        }
        StringBuilder sb2 = new StringBuilder(128);
        sb2.append("ActivityRecord{");
        sb2.append(Integer.toHexString(System.identityHashCode(this)));
        sb2.append(" u");
        sb2.append(this.mUserId);
        sb2.append(' ');
        sb2.append(this.intent.getComponent().flattenToShortString());
        sb2.append(' ');
        sb2.append("privateFlags=");
        sb2.append(this.intent.getPrivateFlags());
        sb2.append(' ');
        this.stringName = sb2.toString();
        return toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, System.identityHashCode(this));
        proto.write(1120986464258L, this.mUserId);
        proto.write(1138166333443L, this.intent.getComponent().flattenToShortString());
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto) {
        super.writeToProto(proto, 1146756268033L, 0);
        writeIdentifierToProto(proto, 1146756268034L);
        proto.write(1138166333443L, this.mState.toString());
        proto.write(1133871366148L, this.visible);
        proto.write(1133871366149L, this.frontOfTask);
        if (hasProcess()) {
            proto.write(1120986464262L, this.app.getPid());
        }
        proto.write(1133871366151L, !this.fullscreen);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        writeToProto(proto);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class CompatDisplayInsets {
        final int mDisplayHeight;
        final int mDisplayWidth;
        final Rect[] mNonDecorInsets = new Rect[4];
        final Rect[] mStableInsets = new Rect[4];

        CompatDisplayInsets(DisplayContent display) {
            this.mDisplayWidth = display.mBaseDisplayWidth;
            this.mDisplayHeight = display.mBaseDisplayHeight;
            DisplayPolicy policy = display.getDisplayPolicy();
            for (int rotation = 0; rotation < 4; rotation++) {
                this.mNonDecorInsets[rotation] = new Rect();
                this.mStableInsets[rotation] = new Rect();
                boolean z = true;
                if (rotation != 1 && rotation != 3) {
                    z = false;
                }
                boolean rotated = z;
                int dw = rotated ? this.mDisplayHeight : this.mDisplayWidth;
                int dh = rotated ? this.mDisplayWidth : this.mDisplayHeight;
                DisplayCutout cutout = display.calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
                policy.getNonDecorInsetsLw(rotation, dw, dh, cutout, this.mNonDecorInsets[rotation]);
                this.mStableInsets[rotation].set(this.mNonDecorInsets[rotation]);
                policy.convertNonDecorInsetsToStableInsets(this.mStableInsets[rotation], rotation);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void getDisplayBoundsByRotation(Rect outBounds, int rotation) {
            boolean rotated = true;
            if (rotation != 1 && rotation != 3) {
                rotated = false;
            }
            int dw = rotated ? this.mDisplayHeight : this.mDisplayWidth;
            int dh = rotated ? this.mDisplayWidth : this.mDisplayHeight;
            outBounds.set(0, 0, dw, dh);
        }

        void getDisplayBoundsByOrientation(Rect outBounds, int orientation) {
            int longSide = Math.max(this.mDisplayWidth, this.mDisplayHeight);
            int shortSide = Math.min(this.mDisplayWidth, this.mDisplayHeight);
            boolean isLandscape = orientation == 2;
            outBounds.set(0, 0, isLandscape ? longSide : shortSide, isLandscape ? shortSide : longSide);
        }
    }
}
