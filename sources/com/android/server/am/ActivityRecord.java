package com.android.server.am;

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
import android.os.IRemoteCallback;
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
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import com.android.internal.R;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.util.XmlUtils;
import com.android.server.AttributeCache;
import com.android.server.am.ActivityMetricsLogger;
import com.android.server.am.ActivityStack;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.AppWindowContainerListener;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.TaskWindowContainerController;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.policy.xpPhoneWindowManager;
import com.xiaopeng.server.wm.xpWindowManagerService;
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
/* loaded from: classes.dex */
public final class ActivityRecord extends ConfigurationContainer implements AppWindowContainerListener {
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
    private static final String TAG = "ActivityManager";
    private static final String TAG_CONFIGURATION = "ActivityManager";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String TAG_SAVED_STATE = "ActivityManager";
    private static final String TAG_STATES = "ActivityManager";
    private static final String TAG_SWITCH = "ActivityManager";
    private static final String TAG_VISIBILITY = "ActivityManager";
    ProcessRecord app;
    ApplicationInfo appInfo;
    AppTimeTracker appTimeTracker;
    final IApplicationToken.Stub appToken;
    CompatibilityInfo compat;
    private final boolean componentSpecified;
    int configChangeFlags;
    HashSet<ConnectionRecord> connections;
    long cpuTimeAtResume;
    boolean deferRelaunchUntilPaused;
    boolean delayedResume;
    boolean drawn;
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
    boolean mClientVisibilityDeferred;
    private boolean mDeferHidingClient;
    private int[] mHorizontalSizeConfigurations;
    private MergedConfiguration mLastReportedConfiguration;
    private int mLastReportedDisplayId;
    private boolean mLastReportedMultiWindowMode;
    private boolean mLastReportedPictureInPictureMode;
    boolean mLaunchTaskBehind;
    int mRotationAnimationHint;
    private boolean mShowWhenLocked;
    private int[] mSmallestSizeConfigurations;
    final ActivityStackSupervisor mStackSupervisor;
    private ActivityStack.ActivityState mState;
    private boolean mTurnScreenOn;
    private int[] mVerticalSizeConfigurations;
    AppWindowContainerController mWindowContainerController;
    ArrayList<ReferrerIntent> newIntents;
    final boolean noDisplay;
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
    final ComponentName realActivity;
    private int realTheme;
    final int requestCode;
    ComponentName requestedVrComponent;
    final String resolvedType;
    ActivityRecord resultTo;
    final String resultWho;
    ArrayList<ResultInfo> results;
    ActivityOptions returningOptions;
    final boolean rootVoiceInteraction;
    final ActivityManagerService service;
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
    UriPermissionOwner uriPermissions;
    final int userId;
    boolean visible;
    boolean visibleIgnoringKeyguard;
    IVoiceInteractionSession voiceSession;
    private int windowFlags;
    private long createTime = System.currentTimeMillis();
    PictureInPictureParams pictureInPictureArgs = new PictureInPictureParams.Builder().build();
    int mStartingWindowState = 0;
    boolean mTaskOverlay = false;
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpBounds = new Rect();

    private static String startingWindowStateToString(int state) {
        switch (state) {
            case 0:
                return "STARTING_WINDOW_NOT_SHOWN";
            case 1:
                return "STARTING_WINDOW_SHOWN";
            case 2:
                return "STARTING_WINDOW_REMOVED";
            default:
                return "unknown state=" + state;
        }
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
        pw.println(this.userId);
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
        pw.print("realActivity=");
        pw.println(this.realActivity.flattenToShortString());
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
        if (!getOverrideConfiguration().equals(Configuration.EMPTY)) {
            pw.println(prefix + "OverrideConfiguration=" + getOverrideConfiguration());
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
        if (this.taskDescription != null) {
            String iconFilename = this.taskDescription.getIconFilename();
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
        if (this.pendingResults != null && this.pendingResults.size() > 0) {
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
        if (this.newIntents != null && this.newIntents.size() > 0) {
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
        if (this.appTimeTracker != null) {
            this.appTimeTracker.dumpWithHeader(pw, prefix, false);
        }
        if (this.uriPermissions != null) {
            this.uriPermissions.dump(pw, prefix);
        }
        pw.print(prefix);
        pw.print("launchFailed=");
        pw.print(this.launchFailed);
        pw.print(" launchCount=");
        pw.print(this.launchCount);
        pw.print(" lastLaunchTime=");
        if (this.lastLaunchTime == 0) {
            pw.print("0");
        } else {
            TimeUtils.formatDuration(this.lastLaunchTime, now, pw);
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
        boolean waitingVisible = this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(this);
        if (this.lastVisibleTime != 0 || waitingVisible || this.nowVisible) {
            pw.print(prefix);
            pw.print("waitingVisible=");
            pw.print(waitingVisible);
            pw.print(" nowVisible=");
            pw.print(this.nowVisible);
            pw.print(" lastVisibleTime=");
            if (this.lastVisibleTime == 0) {
                pw.print("0");
            } else {
                TimeUtils.formatDuration(this.lastVisibleTime, now, pw);
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
        if (this.connections != null) {
            pw.print(prefix);
            pw.print("connections=");
            pw.println(this.connections);
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
        if (this.app == null || this.app.thread == null) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.w("ActivityManager", "Can't report activity moved to display - client not running, activityRecord=" + this + ", displayId=" + displayId);
                return;
            }
            return;
        }
        try {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Reporting activity moved to display, activityRecord=" + this + ", displayId=" + displayId + ", config=" + config);
            }
            this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) MoveToDisplayItem.obtain(displayId, config));
        } catch (RemoteException e) {
        }
    }

    private void scheduleConfigurationChanged(Configuration config) {
        if (this.app == null || this.app.thread == null) {
            if (ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.w("ActivityManager", "Can't report activity configuration update - client not running, activityRecord=" + this);
                return;
            }
            return;
        }
        try {
            if (ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Sending new config to " + this + ", config: " + config);
            }
            this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) ActivityConfigurationChangeItem.obtain(config));
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateMultiWindowMode() {
        boolean inMultiWindowMode;
        if (this.task != null && this.task.getStack() != null && this.app != null && this.app.thread != null && !this.task.getStack().deferScheduleMultiWindowModeChanged() && (inMultiWindowMode = inMultiWindowMode()) != this.mLastReportedMultiWindowMode) {
            this.mLastReportedMultiWindowMode = inMultiWindowMode;
            scheduleMultiWindowModeChanged(getConfiguration());
        }
    }

    private void scheduleMultiWindowModeChanged(Configuration overrideConfig) {
        try {
            this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) MultiWindowModeChangeItem.obtain(this.mLastReportedMultiWindowMode, overrideConfig));
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePictureInPictureMode(Rect targetStackBounds, boolean forceUpdate) {
        if (this.task == null || this.task.getStack() == null || this.app == null || this.app.thread == null) {
            return;
        }
        boolean inPictureInPictureMode = inPinnedWindowingMode() && targetStackBounds != null;
        if (inPictureInPictureMode != this.mLastReportedPictureInPictureMode || forceUpdate) {
            this.mLastReportedPictureInPictureMode = inPictureInPictureMode;
            this.mLastReportedMultiWindowMode = inPictureInPictureMode;
            Configuration newConfig = this.task.computeNewOverrideConfigurationForBounds(targetStackBounds, null);
            schedulePictureInPictureModeChanged(newConfig);
            scheduleMultiWindowModeChanged(newConfig);
        }
    }

    private void schedulePictureInPictureModeChanged(Configuration overrideConfig) {
        try {
            this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) PipModeChangeItem.obtain(this.mLastReportedPictureInPictureMode, overrideConfig));
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
        return getTask();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord getTask() {
        return this.task;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTask(TaskRecord task) {
        setTask(task, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTask(TaskRecord task, boolean reparenting) {
        if (task != null && task == getTask()) {
            return;
        }
        ActivityStack oldStack = getStack();
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
        getWindowContainerController().setWillCloseOrEnterPip(willCloseOrEnterPip);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
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
            if (token == null || (r = token.weakActivity.get()) == null || r.getStack() == null) {
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
            Slog.w("ActivityManager", "Bad activity token: " + token, e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResolverActivity() {
        return ResolverActivity.class.getName().equals(this.realActivity.getClassName());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResolverOrChildActivity() {
        if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(this.packageName)) {
            try {
                return ResolverActivity.class.isAssignableFrom(Object.class.getClassLoader().loadClass(this.realActivity.getClassName()));
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord(ActivityManagerService _service, ProcessRecord _caller, int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage, Intent _intent, String _resolvedType, ActivityInfo aInfo, Configuration _configuration, ActivityRecord _resultTo, String _resultWho, int _reqCode, boolean _componentSpecified, boolean _rootVoiceInteraction, ActivityStackSupervisor supervisor, ActivityOptions options, ActivityRecord sourceRecord) {
        boolean z;
        this.mRotationAnimationHint = -1;
        this.service = _service;
        this.appToken = new Token(this, _intent);
        this.info = aInfo;
        this.launchedFromPid = _launchedFromPid;
        this.launchedFromUid = _launchedFromUid;
        this.launchedFromPackage = _launchedFromPackage;
        this.userId = UserHandle.getUserId(aInfo.applicationInfo.uid);
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
        this.drawn = false;
        this.idle = false;
        this.hasBeenLaunched = false;
        this.mStackSupervisor = supervisor;
        this.haveState = true;
        if (aInfo.targetActivity == null || (aInfo.targetActivity.equals(_intent.getComponent().getClassName()) && (aInfo.launchMode == 0 || aInfo.launchMode == 1))) {
            this.realActivity = _intent.getComponent();
        } else {
            this.realActivity = new ComponentName(aInfo.packageName, aInfo.targetActivity);
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
        if ((aInfo.flags & 1) != 0 && _caller != null && (aInfo.applicationInfo.uid == 1000 || aInfo.applicationInfo.uid == _caller.info.uid)) {
            this.processName = _caller.processName;
        } else {
            this.processName = aInfo.processName;
        }
        if ((aInfo.flags & 32) != 0) {
            this.intent.addFlags(DumpState.DUMP_VOLUMES);
        }
        this.packageName = aInfo.applicationInfo.packageName;
        this.launchMode = aInfo.launchMode;
        AttributeCache.Entry ent = AttributeCache.instance().get(this.packageName, this.realTheme, R.styleable.Window, this.userId);
        if (ent != null) {
            z = true;
            this.fullscreen = !ActivityInfo.isTranslucentOrFloating(ent.array);
            this.hasWallpaper = ent.array.getBoolean(14, false);
            this.noDisplay = ent.array.getBoolean(10, false);
        } else {
            z = true;
            this.hasWallpaper = false;
            this.noDisplay = false;
        }
        xpPhoneWindowManager.get(this.service.mContext).setActivityLunchInfo(this.realActivity, this.intent);
        int i = z;
        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);
        this.immersive = (aInfo.flags & 2048) != 0 ? i : false;
        this.requestedVrComponent = aInfo.requestedVrComponent == null ? null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);
        this.mShowWhenLocked = (aInfo.flags & DumpState.DUMP_VOLUMES) != 0 ? i : false;
        this.mTurnScreenOn = (aInfo.flags & 16777216) != 0 ? i : false;
        this.mRotationAnimationHint = aInfo.rotationAnimation;
        this.lockTaskLaunchMode = aInfo.lockTaskLaunchMode;
        if (this.appInfo.isPrivilegedApp() && (this.lockTaskLaunchMode == 2 || this.lockTaskLaunchMode == i)) {
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
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setProcess(ProcessRecord proc) {
        this.app = proc;
        ActivityRecord root = this.task != null ? this.task.getRootActivity() : null;
        if (root == this) {
            this.task.setRootProcess(proc);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowContainerController getWindowContainerController() {
        return this.mWindowContainerController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createWindowContainer() {
        if (this.mWindowContainerController == null) {
            this.inHistory = true;
            TaskWindowContainerController taskController = this.task.getWindowContainerController();
            this.task.updateOverrideConfigurationFromLaunchBounds();
            updateOverrideConfiguration();
            this.mWindowContainerController = new AppWindowContainerController(taskController, this.appToken, this, Integer.MAX_VALUE, this.info.screenOrientation, this.fullscreen, (this.info.flags & 1024) != 0, this.info.configChanges, this.task.voiceSession != null, this.mLaunchTaskBehind, isAlwaysFocusable(), this.appInfo.targetSdkVersion, this.mRotationAnimationHint, 1000000 * ActivityManagerService.getInputDispatchingTimeoutLocked(this));
            this.task.addActivityToTop(this);
            this.mLastReportedMultiWindowMode = inMultiWindowMode();
            this.mLastReportedPictureInPictureMode = inPinnedWindowingMode();
            return;
        }
        throw new IllegalArgumentException("Window container=" + this.mWindowContainerController + " already created for r=" + this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeWindowContainer() {
        if (this.mWindowContainerController == null) {
            return;
        }
        resumeKeyDispatchingLocked();
        this.mWindowContainerController.removeContainer(getDisplayId());
        this.mWindowContainerController = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(TaskRecord newTask, int position, String reason) {
        TaskRecord prevTask = this.task;
        if (prevTask == newTask) {
            throw new IllegalArgumentException(reason + ": task=" + newTask + " is already the parent of r=" + this);
        } else if (prevTask != null && newTask != null && prevTask.getStack() != newTask.getStack()) {
            throw new IllegalArgumentException(reason + ": task=" + newTask + " is in a different stack (" + newTask.getStackId() + ") than the parent of r=" + this + " (" + prevTask.getStackId() + ")");
        } else {
            this.mWindowContainerController.reparent(newTask.getWindowContainerController(), position);
            ActivityStack prevStack = prevTask.getStack();
            if (prevStack != newTask.getStack()) {
                prevStack.onActivityRemovedFromStack(this);
            }
            prevTask.removeActivity(this, true);
            newTask.addActivityAtIndex(position, this);
        }
    }

    private boolean isHomeIntent(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction()) && intent.hasCategory("android.intent.category.HOME") && intent.getCategories().size() == 1 && intent.getData() == null && intent.getType() == null;
    }

    static boolean isMainIntent(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction()) && intent.hasCategory("android.intent.category.LAUNCHER") && intent.getCategories().size() == 1 && intent.getData() == null && intent.getType() == null;
    }

    private boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
        if (uid == Process.myUid() || uid == 0) {
            return true;
        }
        RecentTasks recentTasks = this.mStackSupervisor.mService.getRecentTasks();
        if (recentTasks == null || !recentTasks.isCallerRecents(uid)) {
            return sourceRecord != null && sourceRecord.isResolverActivity();
        }
        return true;
    }

    private boolean canLaunchAssistActivity(String packageName) {
        ComponentName assistComponent = this.service.mActiveVoiceInteractionServiceComponent;
        if (assistComponent != null) {
            return assistComponent.getPackageName().equals(packageName);
        }
        return false;
    }

    private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent, ActivityOptions options, ActivityRecord sourceRecord) {
        int activityType = 0;
        if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord)) && isHomeIntent(intent) && !isResolverActivity()) {
            int flags = ActivityInfoManager.getActivityFlags(intent);
            boolean isHome = (flags & 536870912) == 536870912;
            if (isHome) {
                activityType = 2;
            } else {
                activityType = 1;
            }
            if (this.info.resizeMode == 4 || this.info.resizeMode == 1) {
                this.info.resizeMode = 0;
            }
        } else if (this.realActivity.getClassName().contains(LEGACY_RECENTS_PACKAGE_NAME) || this.service.getRecentTasks().isRecentsComponent(this.realActivity, this.appInfo.uid)) {
            activityType = 3;
        } else if (options != null && options.getLaunchActivityType() == 4 && canLaunchAssistActivity(this.launchedFromPackage)) {
            activityType = 4;
        }
        setActivityType(activityType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskToAffiliateWith(TaskRecord taskToAffiliateWith) {
        if (this.launchMode != 3 && this.launchMode != 2) {
            this.task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getStack() {
        if (this.task != null) {
            return (T) this.task.getStack();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getStackId() {
        if (getStack() != null) {
            return getStack().mStackId;
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDisplay() {
        ActivityStack stack = getStack();
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
        ActivityStack stack = getStack();
        return (stack == null || stack.isInStackLocked(this) == null) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPersistable() {
        return (this.info.persistableMode == 0 || this.info.persistableMode == 2) && (this.intent == null || (this.intent.getFlags() & DumpState.DUMP_VOLUMES) == 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable() {
        return this.mStackSupervisor.isFocusable(this, isAlwaysFocusable());
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
        return this.service.mSupportsPictureInPicture && isActivityTypeStandardOrUndefined() && this.info.supportsPictureInPicture();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean supportsSplitScreenWindowingMode() {
        return super.supportsSplitScreenWindowingMode() && this.service.mSupportsSplitScreenMultiWindow && supportsResizeableMultiWindow();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean supportsFreeform() {
        return this.service.mSupportsFreeformWindowManagement && supportsResizeableMultiWindow();
    }

    private boolean supportsResizeableMultiWindow() {
        return this.service.mSupportsMultiWindow && !isActivityTypeHome() && (ActivityInfo.isResizeableMode(this.info.resizeMode) || this.service.mForceResizableActivities);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canBeLaunchedOnDisplay(int displayId) {
        TaskRecord task = getTask();
        boolean resizeable = task != null ? task.isResizeable() : supportsResizeableMultiWindow();
        return this.service.mStackSupervisor.canPlaceEntityOnDisplay(displayId, resizeable, this.launchedFromPid, this.launchedFromUid, this.info);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public boolean checkEnterPictureInPictureState(String caller, boolean beforeStopping) {
        if (supportsPictureInPicture() && checkEnterPictureInPictureAppOpsState() && !this.service.shouldDisableNonVrUiLocked()) {
            boolean isKeyguardLocked = this.service.isKeyguardLocked();
            boolean isCurrentAppLocked = this.service.getLockTaskModeState() != 0;
            ActivityDisplay display = getDisplay();
            boolean hasPinnedStack = display != null && display.hasPinnedStack();
            boolean isNotLockedOrOnKeyguard = (isKeyguardLocked || isCurrentAppLocked) ? false : true;
            if (beforeStopping && hasPinnedStack) {
                return false;
            }
            switch (this.mState) {
                case RESUMED:
                    if (isCurrentAppLocked) {
                        return false;
                    }
                    return this.supportsEnterPipOnTaskSwitch || !beforeStopping;
                case PAUSING:
                case PAUSED:
                    return isNotLockedOrOnKeyguard && !hasPinnedStack && this.supportsEnterPipOnTaskSwitch;
                case STOPPING:
                    return this.supportsEnterPipOnTaskSwitch && isNotLockedOrOnKeyguard && !hasPinnedStack;
            }
            return false;
        }
        return false;
    }

    private boolean checkEnterPictureInPictureAppOpsState() {
        if (xpActivityManager.isSystemApplication(this.packageName)) {
            try {
                return this.service.getAppOpsService().checkOperation(67, this.appInfo.uid, this.packageName) == 0;
            } catch (RemoteException e) {
                return false;
            }
        }
        return false;
    }

    boolean isAlwaysFocusable() {
        return (this.info.flags & DumpState.DUMP_DOMAIN_PREFERRED) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasDismissKeyguardWindows() {
        return this.service.mWindowManager.containsDismissKeyguardWindow(this.appToken);
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
        if (this.service != null) {
            this.service.mTaskChangeNotificationController.notifyTaskStackChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UriPermissionOwner getUriPermissionsLocked() {
        if (this.uriPermissions == null) {
            this.uriPermissions = new UriPermissionOwner(this.service, this);
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
    /* JADX WARN: Removed duplicated region for block: B:19:0x0030  */
    /* JADX WARN: Removed duplicated region for block: B:27:0x0035 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void removeResultsLocked(com.android.server.am.ActivityRecord r4, java.lang.String r5, int r6) {
        /*
            r3 = this;
            java.util.ArrayList<android.app.ResultInfo> r0 = r3.results
            if (r0 == 0) goto L38
            java.util.ArrayList<android.app.ResultInfo> r0 = r3.results
            int r0 = r0.size()
            int r0 = r0 + (-1)
        Lc:
            if (r0 < 0) goto L38
            java.util.ArrayList<android.app.ResultInfo> r1 = r3.results
            java.lang.Object r1 = r1.get(r0)
            com.android.server.am.ActivityResult r1 = (com.android.server.am.ActivityResult) r1
            com.android.server.am.ActivityRecord r2 = r1.mFrom
            if (r2 == r4) goto L1b
            goto L35
        L1b:
            java.lang.String r2 = r1.mResultWho
            if (r2 != 0) goto L22
            if (r5 == 0) goto L2b
            goto L35
        L22:
            java.lang.String r2 = r1.mResultWho
            boolean r2 = r2.equals(r5)
            if (r2 != 0) goto L2b
            goto L35
        L2b:
            int r2 = r1.mRequestCode
            if (r2 == r6) goto L30
            goto L35
        L30:
            java.util.ArrayList<android.app.ResultInfo> r2 = r3.results
            r2.remove(r0)
        L35:
            int r0 = r0 + (-1)
            goto Lc
        L38:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityRecord.removeResultsLocked(com.android.server.am.ActivityRecord, java.lang.String, int):void");
    }

    private void addNewIntentLocked(ReferrerIntent intent) {
        if (this.newIntents == null) {
            this.newIntents = new ArrayList<>();
        }
        this.newIntents.add(intent);
    }

    final boolean isSleeping() {
        ActivityStack stack = getStack();
        return stack != null ? stack.shouldSleepActivities() : this.service.isSleepingLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void deliverNewIntentLocked(int callingUid, Intent intent, String referrer) {
        this.service.grantUriPermissionFromIntentLocked(callingUid, this.packageName, intent, getUriPermissionsLocked(), this.userId);
        ReferrerIntent rintent = new ReferrerIntent(intent, referrer);
        boolean unsent = true;
        boolean isTopActivityWhileSleeping = isTopRunningActivity() && isSleeping();
        if ((this.mState == ActivityStack.ActivityState.RESUMED || this.mState == ActivityStack.ActivityState.PAUSED || isTopActivityWhileSleeping) && this.app != null && this.app.thread != null) {
            try {
                ArrayList<ReferrerIntent> ar = new ArrayList<>(1);
                ar.add(rintent);
                this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) NewIntentItem.obtain(ar, this.mState == ActivityStack.ActivityState.PAUSED));
                unsent = false;
            } catch (RemoteException e) {
                Slog.w("ActivityManager", "Exception thrown sending new intent to " + this, e);
            } catch (NullPointerException e2) {
                Slog.w("ActivityManager", "Exception thrown sending new intent to " + this, e2);
            }
        }
        if (unsent) {
            addNewIntentLocked(rintent);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateOptionsLocked(ActivityOptions options) {
        if (options != null) {
            if (this.pendingOptions != null) {
                this.pendingOptions.abort();
            }
            this.pendingOptions = options;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyOptionsLocked() {
        if (this.pendingOptions != null && this.pendingOptions.getAnimationType() != 5) {
            int animationType = this.pendingOptions.getAnimationType();
            boolean z = true;
            switch (animationType) {
                case 1:
                    this.service.mWindowManager.overridePendingAppTransition(this.pendingOptions.getPackageName(), this.pendingOptions.getCustomEnterResId(), this.pendingOptions.getCustomExitResId(), this.pendingOptions.getOnAnimationStartListener());
                    break;
                case 2:
                    this.service.mWindowManager.overridePendingAppTransitionScaleUp(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getWidth(), this.pendingOptions.getHeight());
                    if (this.intent.getSourceBounds() == null) {
                        this.intent.setSourceBounds(new Rect(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getStartX() + this.pendingOptions.getWidth(), this.pendingOptions.getStartY() + this.pendingOptions.getHeight()));
                        break;
                    }
                    break;
                case 3:
                case 4:
                    boolean scaleUp = animationType == 3;
                    GraphicBuffer buffer = this.pendingOptions.getThumbnail();
                    this.service.mWindowManager.overridePendingAppTransitionThumb(buffer, this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getOnAnimationStartListener(), scaleUp);
                    if (this.intent.getSourceBounds() == null && buffer != null) {
                        this.intent.setSourceBounds(new Rect(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getStartX() + buffer.getWidth(), this.pendingOptions.getStartY() + buffer.getHeight()));
                        break;
                    }
                    break;
                case 5:
                case 6:
                case 7:
                case 10:
                default:
                    Slog.e("ActivityManager", "applyOptionsLocked: Unknown animationType=" + animationType);
                    break;
                case 8:
                case 9:
                    AppTransitionAnimationSpec[] specs = this.pendingOptions.getAnimSpecs();
                    IAppTransitionAnimationSpecsFuture specsFuture = this.pendingOptions.getSpecsFuture();
                    if (specsFuture != null) {
                        WindowManagerService windowManagerService = this.service.mWindowManager;
                        IRemoteCallback onAnimationStartListener = this.pendingOptions.getOnAnimationStartListener();
                        if (animationType != 8) {
                            z = false;
                        }
                        windowManagerService.overridePendingAppTransitionMultiThumbFuture(specsFuture, onAnimationStartListener, z);
                        break;
                    } else if (animationType == 9 && specs != null) {
                        this.service.mWindowManager.overridePendingAppTransitionMultiThumb(specs, this.pendingOptions.getOnAnimationStartListener(), this.pendingOptions.getAnimationFinishedListener(), false);
                        break;
                    } else {
                        this.service.mWindowManager.overridePendingAppTransitionAspectScaledThumb(this.pendingOptions.getThumbnail(), this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getWidth(), this.pendingOptions.getHeight(), this.pendingOptions.getOnAnimationStartListener(), animationType == 8);
                        if (this.intent.getSourceBounds() == null) {
                            this.intent.setSourceBounds(new Rect(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getStartX() + this.pendingOptions.getWidth(), this.pendingOptions.getStartY() + this.pendingOptions.getHeight()));
                            break;
                        }
                    }
                    break;
                case 11:
                    this.service.mWindowManager.overridePendingAppTransitionClipReveal(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getWidth(), this.pendingOptions.getHeight());
                    if (this.intent.getSourceBounds() == null) {
                        this.intent.setSourceBounds(new Rect(this.pendingOptions.getStartX(), this.pendingOptions.getStartY(), this.pendingOptions.getStartX() + this.pendingOptions.getWidth(), this.pendingOptions.getStartY() + this.pendingOptions.getHeight()));
                        break;
                    }
                    break;
                case 12:
                    this.service.mWindowManager.overridePendingAppTransitionStartCrossProfileApps();
                    break;
                case 13:
                    this.service.mWindowManager.overridePendingAppTransitionRemote(this.pendingOptions.getRemoteAnimationAdapter());
                    break;
            }
            if (this.task == null) {
                clearOptionsLocked(false);
            } else {
                this.task.clearAllPendingOptions();
            }
        }
    }

    ActivityOptions getOptionsForTargetActivityLocked() {
        if (this.pendingOptions != null) {
            return this.pendingOptions.forTargetActivity();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOptionsLocked() {
        clearOptionsLocked(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOptionsLocked(boolean withAbort) {
        if (withAbort && this.pendingOptions != null) {
            this.pendingOptions.abort();
        }
        this.pendingOptions = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityOptions takeOptionsLocked() {
        ActivityOptions opts = this.pendingOptions;
        this.pendingOptions = null;
        return opts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUriPermissionsLocked() {
        if (this.uriPermissions != null) {
            this.uriPermissions.removeUriPermissionsLocked();
            this.uriPermissions = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pauseKeyDispatchingLocked() {
        if (!this.keysPaused) {
            this.keysPaused = true;
            if (this.mWindowContainerController != null) {
                this.mWindowContainerController.pauseKeyDispatching();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeKeyDispatchingLocked() {
        if (this.keysPaused) {
            this.keysPaused = false;
            if (this.mWindowContainerController != null) {
                this.mWindowContainerController.resumeKeyDispatching();
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
        this.mWindowContainerController.setVisibility(visible, this.mDeferHidingClient);
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
        if (ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityManager", "State movement: " + this + " from:" + getState() + " to:" + state + " reason:" + reason);
        }
        if (state == this.mState) {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "State unchanged from:" + state);
                return;
            }
            return;
        }
        this.mState = state;
        TaskRecord parent = getTask();
        if (parent != null) {
            parent.onActivityStateChanged(this, state, reason);
        }
        if (state == ActivityStack.ActivityState.STOPPING && !isSleeping()) {
            this.mWindowContainerController.notifyAppStopping();
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
        return state1 == this.mState || state2 == this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state1, ActivityStack.ActivityState state2, ActivityStack.ActivityState state3) {
        return state1 == this.mState || state2 == this.mState || state3 == this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isState(ActivityStack.ActivityState state1, ActivityStack.ActivityState state2, ActivityStack.ActivityState state3, ActivityStack.ActivityState state4) {
        return state1 == this.mState || state2 == this.mState || state3 == this.mState || state4 == this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppResumed(boolean wasStopped) {
        this.mWindowContainerController.notifyAppResumed(wasStopped);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyUnknownVisibilityLaunched() {
        if (!this.noDisplay) {
            this.mWindowContainerController.notifyUnknownVisibilityLaunched();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBeVisibleIgnoringKeyguard(boolean behindFullscreenActivity) {
        if (okToShowLocked()) {
            if (this.task == null || !this.mStackSupervisor.isPhoneStack(this.task.getStack())) {
                return !behindFullscreenActivity || this.mLaunchTaskBehind;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeVisibleIfNeeded(ActivityRecord starting, boolean reportToClient) {
        if (this.mState == ActivityStack.ActivityState.RESUMED || this == starting) {
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.d("ActivityManager", "Not making visible, r=" + this + " state=" + this.mState + " starting=" + starting);
                return;
            }
            return;
        }
        if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("ActivityManager", "Making visible and scheduling visibility: " + this);
        }
        ActivityStack stack = getStack();
        try {
            if (stack.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(this.returningOptions);
                stack.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisible(true);
            this.sleeping = false;
            this.app.pendingUiClean = true;
            if (reportToClient) {
                makeClientVisible();
            } else {
                this.mClientVisibilityDeferred = true;
            }
            this.mStackSupervisor.mStoppingActivities.remove(this);
            this.mStackSupervisor.mGoingToSleepActivities.remove(this);
        } catch (Exception e) {
            Slog.w("ActivityManager", "Exception thrown making visible: " + this.intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeClientVisible() {
        this.mClientVisibilityDeferred = false;
        try {
            this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ClientTransactionItem) WindowVisibilityItem.obtain(true));
            if (shouldPauseWhenBecomingVisible()) {
                setState(ActivityStack.ActivityState.PAUSING, "makeVisibleIfNeeded");
                this.service.getLifecycleManager().scheduleTransaction(this.app.thread, (IBinder) this.appToken, (ActivityLifecycleItem) PauseActivityItem.obtain(this.finishing, false, this.configChangeFlags, false));
            }
        } catch (Exception e) {
            Slog.w("ActivityManager", "Exception thrown sending visibility update: " + this.intent.getComponent(), e);
        }
    }

    private boolean shouldPauseWhenBecomingVisible() {
        if (isState(ActivityStack.ActivityState.STOPPED, ActivityStack.ActivityState.STOPPING) && getStack().mTranslucentActivityWaiting == null && this.mStackSupervisor.getResumedActivityLocked() != this) {
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
                this.app.thread.scheduleOnNewActivityOptions(this.appToken, this.returningOptions.toBundle());
            }
        } catch (RemoteException e) {
        }
        return this.mState == ActivityStack.ActivityState.RESUMED;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void activityResumedLocked(IBinder token) {
        ActivityRecord r = forTokenLocked(token);
        if (ActivityManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityManager", "Resumed activity; dropping state of: " + r);
        }
        if (r != null) {
            r.icicle = null;
            r.haveState = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void completeResumeLocked() {
        ProcessRecord app;
        boolean wasVisible = this.visible;
        setVisible(true);
        if (!wasVisible) {
            this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
        }
        this.idle = false;
        this.results = null;
        this.newIntents = null;
        this.stopped = false;
        if (isActivityTypeHome() && (app = this.task.mActivities.get(0).app) != null && app != this.service.mHomeProcess) {
            this.service.mHomeProcess = app;
        }
        if (this.nowVisible) {
            this.mStackSupervisor.reportActivityVisibleLocked(this);
        }
        this.mStackSupervisor.scheduleIdleTimeoutLocked(this);
        this.mStackSupervisor.reportResumedActivityLocked(this);
        resumeKeyDispatchingLocked();
        ActivityStack stack = getStack();
        this.mStackSupervisor.mNoAnimActivities.clear();
        if (this.app != null) {
            this.cpuTimeAtResume = this.service.mProcessCpuTracker.getCpuTimeForPid(this.app.pid);
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
        ActivityStack stack = getStack();
        if (this.mState != ActivityStack.ActivityState.STOPPING) {
            Slog.i("ActivityManager", "Activity reported stop, but no longer stopping: " + this);
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_SELECT_MEDIA_FUNCTION, this);
            return;
        }
        if (newPersistentState != null) {
            this.persistentState = newPersistentState;
            this.service.notifyTaskPersisterLocked(this.task, false);
        }
        if (ActivityManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityManager", "Saving icicle of " + this + ": " + this.icicle);
        }
        if (newIcicle != null) {
            this.icicle = newIcicle;
            this.haveState = true;
            this.launchCount = 0;
            updateTaskDescription(description);
        }
        if (!this.stopped) {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Moving to STOPPED: " + this + " (stop complete)");
            }
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_SELECT_MEDIA_FUNCTION, this);
            this.stopped = true;
            setState(ActivityStack.ActivityState.STOPPED, "activityStoppedLocked");
            this.mWindowContainerController.notifyAppStopped();
            if (this.finishing) {
                clearOptionsLocked();
            } else if (this.deferRelaunchUntilPaused) {
                stack.destroyActivityLocked(this, true, "stop-config");
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            } else {
                this.mStackSupervisor.updatePreviousProcessLocked(this);
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
        if (this.launchTickTime == 0 || (stack = getStack()) == null) {
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
        ActivityStack stack = getStack();
        if (stack != null) {
            stack.mHandler.removeMessages(HdmiCecKeycode.CEC_KEYCODE_TUNE_FUNCTION);
        }
    }

    public boolean mayFreezeScreenLocked(ProcessRecord app) {
        return (app == null || app.crashing || app.notResponding) ? false : true;
    }

    public void startFreezingScreenLocked(ProcessRecord app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            this.mWindowContainerController.startFreezingScreen(configChanges);
        }
    }

    public void stopFreezingScreenLocked(boolean force) {
        if (force || this.frozenBeforeDestroy) {
            this.frozenBeforeDestroy = false;
            this.mWindowContainerController.stopFreezingScreen(force);
        }
    }

    public void reportFullyDrawnLocked(boolean restoredFromBundle) {
        ActivityMetricsLogger.WindowingModeTransitionInfoSnapshot info = this.mStackSupervisor.getActivityMetricsLogger().logAppTransitionReportedDrawn(this, restoredFromBundle);
        if (info != null) {
            this.mStackSupervisor.reportActivityLaunchedLocked(false, this, info.windowsFullyDrawnDelayMs);
        }
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public void onStartingWindowDrawn(long timestamp) {
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.getActivityMetricsLogger().notifyStartingWindowDrawn(getWindowingMode(), timestamp);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public void onWindowsDrawn(long timestamp) {
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.drawn = true;
                ActivityMetricsLogger.WindowingModeTransitionInfoSnapshot info = this.mStackSupervisor.getActivityMetricsLogger().notifyWindowsDrawn(getWindowingMode(), timestamp);
                int windowsDrawnDelayMs = info != null ? info.windowsDrawnDelayMs : -1;
                this.mStackSupervisor.reportActivityLaunchedLocked(false, this, windowsDrawnDelayMs);
                this.mStackSupervisor.sendWaitingVisibleReportLocked(this);
                finishLaunchTickingLocked();
                if (this.task != null) {
                    this.task.hasBeenVisible = true;
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public void onWindowsNotDrawn(long timestamp) {
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.drawn = false;
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public void onWindowsVisible() {
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.reportActivityVisibleLocked(this);
                if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                    Log.v("ActivityManager", "windowsVisibleLocked(): " + this);
                }
                if (!this.nowVisible) {
                    this.nowVisible = true;
                    this.lastVisibleTime = SystemClock.uptimeMillis();
                    int i = 0;
                    if (!this.idle && !this.mStackSupervisor.isStoppingNoHistoryActivity()) {
                        this.mStackSupervisor.processStoppingActivitiesLocked(null, false, true);
                        this.service.scheduleAppGcsLocked();
                    }
                    int size = this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.size();
                    if (size > 0) {
                        while (true) {
                            int i2 = i;
                            if (i2 >= size) {
                                break;
                            }
                            ActivityRecord r = this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.get(i2);
                            if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                                Log.v("ActivityManager", "Was waiting for visible: " + r);
                            }
                            i = i2 + 1;
                        }
                        this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.clear();
                        this.mStackSupervisor.scheduleIdleLocked();
                    }
                    this.service.scheduleAppGcsLocked();
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public void onWindowsGone() {
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                    Log.v("ActivityManager", "windowsGone(): " + this);
                }
                this.nowVisible = false;
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.AppWindowContainerListener
    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        ActivityRecord anrActivity;
        ProcessRecord anrApp;
        boolean windowFromSameProcessAsActivity;
        synchronized (this.service) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                anrActivity = getWaitingHistoryRecordLocked();
                anrApp = this.app;
                if (this.app != null && this.app.pid != windowPid && windowPid != -1) {
                    windowFromSameProcessAsActivity = false;
                }
                windowFromSameProcessAsActivity = true;
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        if (windowFromSameProcessAsActivity) {
            return this.service.inputDispatchingTimedOut(anrApp, anrActivity, this, false, reason);
        }
        return this.service.inputDispatchingTimedOut(windowPid, false, reason) < 0;
    }

    private ActivityRecord getWaitingHistoryRecordLocked() {
        if (this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(this) || this.stopped) {
            ActivityStack stack = this.mStackSupervisor.getFocusedStack();
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
        if (StorageManager.isUserKeyUnlocked(this.userId) || this.info.applicationInfo.isEncryptionAware()) {
            return (this.info.flags & 1024) != 0 || (this.mStackSupervisor.isCurrentProfileLocked(this.userId) && this.service.mUserController.isUserRunning(this.userId, 0));
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
        if ((force || this.sleeping != _sleeping) && this.app != null && this.app.thread != null) {
            try {
                this.app.thread.scheduleSleeping(this.appToken, _sleeping);
                if (_sleeping && !this.mStackSupervisor.mGoingToSleepActivities.contains(this)) {
                    this.mStackSupervisor.mGoingToSleepActivities.add(this);
                }
                this.sleeping = _sleeping;
            } catch (RemoteException e) {
                Slog.w("ActivityManager", "Exception thrown when sleeping: " + this.intent.getComponent(), e);
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
            return r.getStack().isInStackLocked(r);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ActivityStack getStackLocked(IBinder token) {
        ActivityRecord r = isInStackLocked(token);
        if (r != null) {
            return r.getStack();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDisplayId() {
        ActivityStack stack = getStack();
        if (stack == null) {
            return -1;
        }
        return stack.mDisplayId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isDestroyable() {
        ActivityStack stack;
        return (this.finishing || this.app == null || (stack = getStack()) == null || this == stack.getResumedActivity() || this == stack.mPausingActivity || !this.haveState || !this.stopped || this.visible) ? false : true;
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
            this.service.getRecentTasks().saveImage(icon, iconFilePath);
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
        if (this.mWindowContainerController == null || this.mTaskOverlay || getWindowingMode() == 5) {
            return;
        }
        CompatibilityInfo compatInfo = this.service.compatibilityInfoForPackageLocked(this.info.applicationInfo);
        boolean shown = this.mWindowContainerController.addStartingWindow(this.packageName, this.theme, compatInfo, this.nonLocalizedLabel, this.labelRes, this.icon, this.logo, this.windowFlags, prev != null ? prev.appToken : null, newTask, taskSwitch, isProcessRunning(), allowTaskSnapshot(), this.mState.ordinal() >= ActivityStack.ActivityState.RESUMED.ordinal() && this.mState.ordinal() <= ActivityStack.ActivityState.STOPPED.ordinal(), fromRecents);
        if (shown) {
            this.mStartingWindowState = 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeOrphanedStartingWindow(boolean behindFullscreenActivity) {
        if (this.mStartingWindowState == 1 && behindFullscreenActivity) {
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w("ActivityManager", "Found orphaned starting window " + this);
            }
            this.mStartingWindowState = 2;
            this.mWindowContainerController.removeStartingWindow();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRequestedOrientation() {
        return this.mWindowContainerController.getOrientation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRequestedOrientation(int requestedOrientation) {
        int displayId = getDisplayId();
        Configuration displayConfig = this.mStackSupervisor.getDisplayOverrideConfiguration(displayId);
        Configuration config = this.mWindowContainerController.setOrientation(requestedOrientation, displayId, displayConfig, mayFreezeScreenLocked(this.app));
        if (config != null) {
            this.frozenBeforeDestroy = true;
            if (!this.service.updateDisplayOverrideConfigurationLocked(config, this, false, displayId)) {
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
        }
        this.service.mTaskChangeNotificationController.notifyActivityRequestedOrientationChanged(this.task.taskId, requestedOrientation);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisablePreviewScreenshots(boolean disable) {
        this.mWindowContainerController.setDisablePreviewScreenshots(disable);
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

    private void updateOverrideConfiguration() {
        this.mTmpConfig.unset();
        computeBounds(this.mTmpBounds);
        if (this.mTmpBounds.equals(getOverrideBounds())) {
            return;
        }
        setBounds(this.mTmpBounds);
        Rect updatedBounds = getOverrideBounds();
        if (!matchParentBounds()) {
            this.task.computeOverrideConfiguration(this.mTmpConfig, updatedBounds, null, false, false);
        }
        onOverrideConfigurationChanged(this.mTmpConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isConfigurationCompatible(Configuration config) {
        int orientation = this.mWindowContainerController != null ? this.mWindowContainerController.getOrientation() : this.info.screenOrientation;
        if (!ActivityInfo.isFixedOrientationPortrait(orientation) || config.orientation == 1) {
            return !ActivityInfo.isFixedOrientationLandscape(orientation) || config.orientation == 2;
        }
        return false;
    }

    private void computeBounds(Rect outBounds) {
        outBounds.setEmpty();
        float maxAspectRatio = this.info.maxAspectRatio;
        ActivityStack stack = getStack();
        if (this.task == null || stack == null || this.task.inMultiWindowMode() || maxAspectRatio == 0.0f || isInVrUiMode(getConfiguration())) {
            return;
        }
        Rect appBounds = getParent().getWindowConfiguration().getAppBounds();
        int containingAppWidth = appBounds.width();
        int containingAppHeight = appBounds.height();
        int maxActivityWidth = containingAppWidth;
        int maxActivityHeight = containingAppHeight;
        if (containingAppWidth < containingAppHeight) {
            maxActivityHeight = (int) ((maxActivityWidth * maxAspectRatio) + 0.5f);
        } else {
            maxActivityWidth = (int) ((maxActivityHeight * maxAspectRatio) + 0.5f);
        }
        if (containingAppWidth <= maxActivityWidth && containingAppHeight <= maxActivityHeight) {
            outBounds.set(getOverrideBounds());
            return;
        }
        outBounds.set(0, 0, appBounds.left + maxActivityWidth, appBounds.top + maxActivityHeight);
        if (this.service.mWindowManager.getNavBarPosition() == 1) {
            outBounds.left = appBounds.right - maxActivityWidth;
            outBounds.right = appBounds.right;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow) {
        return ensureActivityConfiguration(globalChanges, preserveWindow, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow, boolean ignoreStopState) {
        ActivityStack stack = getStack();
        if (stack.mConfigWillChange) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Skipping config check (will change): " + this);
            }
            return true;
        } else if (this.finishing) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Configuration doesn't matter in finishing " + this);
            }
            stopFreezingScreenLocked(false);
            return true;
        } else if (!ignoreStopState && (this.mState == ActivityStack.ActivityState.STOPPING || this.mState == ActivityStack.ActivityState.STOPPED)) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Skipping config check stopped or stopping: " + this);
            }
            return true;
        } else if (!stack.shouldBeVisible(null)) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Skipping config check invisible stack: " + this);
            }
            return true;
        } else {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityManager", "Ensuring correct configuration: " + this);
            }
            int newDisplayId = getDisplayId();
            boolean displayChanged = this.mLastReportedDisplayId != newDisplayId;
            if (displayChanged) {
                this.mLastReportedDisplayId = newDisplayId;
            }
            updateOverrideConfiguration();
            this.mTmpConfig.setTo(this.mLastReportedConfiguration.getMergedConfiguration());
            if (getConfiguration().equals(this.mTmpConfig) && !this.forceNewConfig && !displayChanged) {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityManager", "Configuration & display unchanged in " + this);
                }
                return true;
            }
            int changes = getConfigurationChanges(this.mTmpConfig);
            Configuration newMergedOverrideConfig = getMergedOverrideConfiguration();
            setLastReportedConfiguration(this.service.getGlobalConfiguration(), newMergedOverrideConfig);
            if (this.mState == ActivityStack.ActivityState.INITIALIZING) {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityManager", "Skipping config check for initializing activity: " + this);
                }
                return true;
            } else if (changes == 0 && !this.forceNewConfig) {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityManager", "Configuration no differences in " + this);
                }
                if (displayChanged) {
                    scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig);
                } else {
                    scheduleConfigurationChanged(newMergedOverrideConfig);
                }
                return true;
            } else {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityManager", "Configuration changes for " + this + ", allChanges=" + Configuration.configurationDiffToString(changes));
                }
                if (this.app == null || this.app.thread == null) {
                    if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                        Slog.v("ActivityManager", "Configuration doesn't matter not running " + this);
                    }
                    stopFreezingScreenLocked(false);
                    this.forceNewConfig = false;
                    return true;
                }
                if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.v("ActivityManager", "Checking to restart " + this.info.name + ": changed=0x" + Integer.toHexString(changes) + ", handles=0x" + Integer.toHexString(this.info.getRealConfigChanged()) + ", mLastReportedConfiguration=" + this.mLastReportedConfiguration);
                }
                if (shouldRelaunchLocked(changes, this.mTmpConfig) || this.forceNewConfig) {
                    this.configChangeFlags |= changes;
                    startFreezingScreenLocked(this.app, globalChanges);
                    this.forceNewConfig = false;
                    boolean preserveWindow2 = preserveWindow & isResizeOnlyChange(changes);
                    if (this.app == null || this.app.thread == null) {
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityManager", "Config is destroying non-running " + this);
                        }
                        stack.destroyActivityLocked(this, true, xpWindowManagerService.WindowConfigJson.KEY_CONFIG);
                    } else if (this.mState == ActivityStack.ActivityState.PAUSING) {
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityManager", "Config is skipping already pausing " + this);
                        }
                        this.deferRelaunchUntilPaused = true;
                        this.preserveWindowOnDeferredRelaunch = preserveWindow2;
                        return true;
                    } else if (this.mState == ActivityStack.ActivityState.RESUMED) {
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityManager", "Config is relaunching resumed " + this);
                        }
                        if (ActivityManagerDebugConfig.DEBUG_STATES && !this.visible) {
                            Slog.v("ActivityManager", "Config is relaunching resumed invisible activity " + this + " called by " + Debug.getCallers(4));
                        }
                        relaunchActivityLocked(true, preserveWindow2);
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.v("ActivityManager", "Config is relaunching non-resumed " + this);
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
            configChanged |= 512;
        }
        return ((~configChanged) & changes) != 0;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void relaunchActivityLocked(boolean andResume, boolean preserveWindow) {
        ResumeActivityItem obtain;
        if (this.service.mSuppressResizeConfigChanges && preserveWindow) {
            this.configChangeFlags = 0;
            return;
        }
        List<ResultInfo> pendingResults = null;
        List<ReferrerIntent> pendingNewIntents = null;
        if (andResume) {
            pendingResults = this.results;
            pendingNewIntents = this.newIntents;
        }
        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
            Slog.v("ActivityManager", "Relaunching: " + this + " with results=" + pendingResults + " newIntents=" + pendingNewIntents + " andResume=" + andResume + " preserveWindow=" + preserveWindow);
        }
        EventLog.writeEvent(andResume ? EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY : EventLogTags.AM_RELAUNCH_ACTIVITY, Integer.valueOf(this.userId), Integer.valueOf(System.identityHashCode(this)), Integer.valueOf(this.task.taskId), this.shortComponentName);
        startFreezingScreenLocked(this.app, 0);
        try {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_STATES) {
                StringBuilder sb = new StringBuilder();
                sb.append("Moving to ");
                sb.append(andResume ? "RESUMED" : "PAUSED");
                sb.append(" Relaunching ");
                sb.append(this);
                sb.append(" callers=");
                sb.append(Debug.getCallers(6));
                Slog.i("ActivityManager", sb.toString());
            }
            this.forceNewConfig = false;
            this.mStackSupervisor.activityRelaunchingLocked(this);
            ActivityRelaunchItem obtain2 = ActivityRelaunchItem.obtain(pendingResults, pendingNewIntents, this.configChangeFlags, new MergedConfiguration(this.service.getGlobalConfiguration(), getMergedOverrideConfiguration()), preserveWindow);
            if (andResume) {
                obtain = ResumeActivityItem.obtain(this.service.isNextTransitionForward());
            } else {
                obtain = PauseActivityItem.obtain();
            }
            ClientTransaction transaction = ClientTransaction.obtain(this.app.thread, this.appToken);
            transaction.addCallback(obtain2);
            transaction.setLifecycleStateRequest(obtain);
            this.service.getLifecycleManager().scheduleTransaction(transaction);
        } catch (RemoteException e) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.i("ActivityManager", "Relaunch failed", e);
            }
        }
        if (andResume) {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.d("ActivityManager", "Resumed after relaunch " + this);
            }
            this.results = null;
            this.newIntents = null;
            this.service.getAppWarningsLocked().onResumeActivity(this);
            this.service.showAskCompatModeDialogLocked(this);
        } else {
            this.service.mHandler.removeMessages(101, this);
            setState(ActivityStack.ActivityState.PAUSED, "relaunchActivityLocked");
        }
        this.configChangeFlags = 0;
        this.deferRelaunchUntilPaused = false;
        this.preserveWindowOnDeferredRelaunch = false;
    }

    private boolean isProcessRunning() {
        ProcessRecord proc = this.app;
        if (proc == null) {
            proc = (ProcessRecord) this.service.mProcessNames.get(this.processName, this.info.applicationInfo.uid);
        }
        return (proc == null || proc.thread == null) ? false : true;
    }

    private boolean allowTaskSnapshot() {
        if (this.newIntents == null) {
            return true;
        }
        for (int i = this.newIntents.size() - 1; i >= 0; i--) {
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
        if (this.launchedFromPackage != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMPACKAGE, this.launchedFromPackage);
        }
        if (this.resolvedType != null) {
            out.attribute(null, ATTR_RESOLVEDTYPE, this.resolvedType);
        }
        out.attribute(null, ATTR_COMPONENTSPECIFIED, String.valueOf(this.componentSpecified));
        out.attribute(null, ATTR_USERID, String.valueOf(this.userId));
        if (this.taskDescription != null) {
            this.taskDescription.saveToXml(out);
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
        int outerDepth;
        Intent intent;
        PersistableBundle persistentState;
        Intent intent2 = null;
        PersistableBundle persistentState2 = null;
        int launchedFromUid = 0;
        String launchedFromPackage = null;
        String resolvedType = null;
        boolean componentSpecified = false;
        int userId = 0;
        long createTime = -1;
        int outerDepth2 = in.getDepth();
        ActivityManager.TaskDescription taskDescription = new ActivityManager.TaskDescription();
        int attrNdx = in.getAttributeCount() - 1;
        while (attrNdx >= 0) {
            String attrName = in.getAttributeName(attrNdx);
            String attrValue = in.getAttributeValue(attrNdx);
            if (ATTR_ID.equals(attrName)) {
                createTime = Long.parseLong(attrValue);
            } else if (ATTR_LAUNCHEDFROMUID.equals(attrName)) {
                launchedFromUid = Integer.parseInt(attrValue);
            } else if (ATTR_LAUNCHEDFROMPACKAGE.equals(attrName)) {
                launchedFromPackage = attrValue;
            } else if (ATTR_RESOLVEDTYPE.equals(attrName)) {
                resolvedType = attrValue;
            } else if (ATTR_COMPONENTSPECIFIED.equals(attrName)) {
                componentSpecified = Boolean.parseBoolean(attrValue);
            } else if (ATTR_USERID.equals(attrName)) {
                userId = Integer.parseInt(attrValue);
            } else if (attrName.startsWith("task_description_")) {
                taskDescription.restoreFromXml(attrName, attrValue);
            } else {
                intent = intent2;
                StringBuilder sb = new StringBuilder();
                persistentState = persistentState2;
                sb.append("Unknown ActivityRecord attribute=");
                sb.append(attrName);
                Log.d("ActivityManager", sb.toString());
                attrNdx--;
                intent2 = intent;
                persistentState2 = persistentState;
            }
            intent = intent2;
            persistentState = persistentState2;
            attrNdx--;
            intent2 = intent;
            persistentState2 = persistentState;
        }
        while (true) {
            int event = in.next();
            if (event != 1 && (event != 3 || in.getDepth() >= outerDepth2)) {
                if (event == 2) {
                    String name = in.getName();
                    if (TAG_INTENT.equals(name)) {
                        intent2 = Intent.restoreFromXml(in);
                    } else if (TAG_PERSISTABLEBUNDLE.equals(name)) {
                        persistentState2 = PersistableBundle.restoreFromXml(in);
                    } else {
                        StringBuilder sb2 = new StringBuilder();
                        outerDepth = outerDepth2;
                        sb2.append("restoreActivity: unexpected name=");
                        sb2.append(name);
                        Slog.w("ActivityManager", sb2.toString());
                        XmlUtils.skipCurrentTag(in);
                        outerDepth2 = outerDepth;
                    }
                    outerDepth = outerDepth2;
                    outerDepth2 = outerDepth;
                }
            }
        }
        if (intent2 == null) {
            throw new XmlPullParserException("restoreActivity error intent=" + intent2);
        }
        ActivityManagerService service = stackSupervisor.mService;
        ActivityInfo aInfo = stackSupervisor.resolveActivity(intent2, resolvedType, 0, null, userId, Binder.getCallingUid());
        if (aInfo == null) {
            throw new XmlPullParserException("restoreActivity resolver error. Intent=" + intent2 + " resolvedType=" + resolvedType);
        }
        ActivityRecord r = new ActivityRecord(service, null, 0, launchedFromUid, launchedFromPackage, intent2, resolvedType, aInfo, service.getConfiguration(), null, null, 0, componentSpecified, false, stackSupervisor, null, null);
        r.persistentState = persistentState2;
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
        this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowWhenLocked() {
        return !inPinnedWindowingMode() && (this.mShowWhenLocked || this.service.mWindowManager.containsShowWhenLockedWindow(this.appToken));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTurnScreenOn(boolean turnScreenOn) {
        this.mTurnScreenOn = turnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canTurnScreenOn() {
        ActivityStack stack = getStack();
        return this.mTurnScreenOn && stack != null && stack.checkKeyguardVisibility(this, true, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getTurnScreenOnFlag() {
        return this.mTurnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopRunningActivity() {
        return this.mStackSupervisor.topRunningActivityLocked() == this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        this.mWindowContainerController.registerRemoteAnimations(definition);
    }

    public String toString() {
        if (this.stringName != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(this.stringName);
            sb.append(" t");
            sb.append(this.task == null ? -1 : this.task.taskId);
            sb.append(this.finishing ? " f}" : "}");
            return sb.toString();
        }
        StringBuilder sb2 = new StringBuilder(128);
        sb2.append("ActivityRecord{");
        sb2.append(Integer.toHexString(System.identityHashCode(this)));
        sb2.append(" u");
        sb2.append(this.userId);
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
        proto.write(1120986464258L, this.userId);
        proto.write(1138166333443L, this.intent.getComponent().flattenToShortString());
        proto.end(token);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, false);
        writeIdentifierToProto(proto, 1146756268034L);
        proto.write(1138166333443L, this.mState.toString());
        proto.write(1133871366148L, this.visible);
        proto.write(1133871366149L, this.frontOfTask);
        if (this.app != null) {
            proto.write(1120986464262L, this.app.pid);
        }
        proto.write(1133871366151L, !this.fullscreen);
        proto.end(token);
    }
}
