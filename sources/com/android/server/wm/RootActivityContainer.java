package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.IApplicationToken;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.server.LocalServices;
import com.android.server.UiModeManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.UserState;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.LaunchParamsController;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.view.xpDisplayInfo;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RootActivityContainer extends ConfigurationContainer implements DisplayManager.DisplayListener {
    static final int MATCH_TASK_IN_STACKS_ONLY = 0;
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS = 1;
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE = 2;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_RECENTS = "ActivityTaskManager";
    private static final String TAG_RELEASE = "ActivityTaskManager";
    static final String TAG_STATES = "ActivityTaskManager";
    static final String TAG_TASKS = "ActivityTaskManager";
    int mCurrentUser;
    private ActivityDisplay mDefaultDisplay;
    DisplayManager mDisplayManager;
    private DisplayManagerInternal mDisplayManagerInternal;
    boolean mIsDockMinimized;
    private boolean mPowerHintSent;
    private RootWindowContainer mRootWindowContainer;
    ActivityTaskManagerService mService;
    ActivityStackSupervisor mStackSupervisor;
    WindowManagerService mWindowManager;
    private final ArrayList<ActivityDisplay> mActivityDisplays = new ArrayList<>();
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();
    SparseIntArray mUserStackInFront = new SparseIntArray(2);
    final ArrayList<ActivityTaskManagerInternal.SleepToken> mSleepTokens = new ArrayList<>();
    int mDefaultMinSizeOfResizeableTaskDp = -1;
    private boolean mTaskLayersChanged = true;
    private final ArrayList<ActivityRecord> mTmpActivityList = new ArrayList<>();
    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    public @interface AnyTaskForIdMatchTaskMode {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class FindTaskResult {
        boolean mIdealMatch;
        ActivityRecord mRecord;

        /* JADX INFO: Access modifiers changed from: package-private */
        public void clear() {
            this.mRecord = null;
            this.mIdealMatch = false;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void setTo(FindTaskResult result) {
            this.mRecord = result.mRecord;
            this.mIdealMatch = result.mIdealMatch;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RootActivityContainer(ActivityTaskManagerService service) {
        this.mService = service;
        this.mStackSupervisor = service.mStackSupervisor;
        this.mStackSupervisor.mRootActivityContainer = this;
    }

    @VisibleForTesting
    void setWindowContainer(RootWindowContainer container) {
        this.mRootWindowContainer = container;
        this.mRootWindowContainer.setRootActivityContainer(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowManager(WindowManagerService wm) {
        this.mWindowManager = wm;
        setWindowContainer(this.mWindowManager.mRoot);
        this.mDisplayManager = (DisplayManager) this.mService.mContext.getSystemService(DisplayManager.class);
        this.mDisplayManager.registerDisplayListener(this, this.mService.mUiHandler);
        this.mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
        Display[] displays = this.mDisplayManager.getDisplays();
        for (Display display : displays) {
            ActivityDisplay activityDisplay = new ActivityDisplay(this, display);
            if (activityDisplay.mDisplayId == 0) {
                this.mDefaultDisplay = activityDisplay;
            }
            addChild(activityDisplay, Integer.MAX_VALUE);
        }
        calculateDefaultMinimalSizeOfResizeableTasks();
        ActivityDisplay defaultDisplay = getDefaultDisplay();
        defaultDisplay.getOrCreateStack(1, 2, true);
        positionChildAt(defaultDisplay, Integer.MAX_VALUE);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDefaultDisplay() {
        return this.mDefaultDisplay;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getActivityDisplay(String uniqueId) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            boolean isValid = display.mDisplay.isValid();
            if (isValid && display.mDisplay.getUniqueId().equals(uniqueId)) {
                return display;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getActivityDisplay(int displayId) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.get(i);
            if (activityDisplay.mDisplayId == displayId) {
                return activityDisplay;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getActivityDisplayOrCreate(int displayId) {
        Display display;
        ActivityDisplay activityDisplay = getActivityDisplay(displayId);
        if (activityDisplay != null) {
            return activityDisplay;
        }
        DisplayManager displayManager = this.mDisplayManager;
        if (displayManager == null || (display = displayManager.getDisplay(displayId)) == null) {
            return null;
        }
        ActivityDisplay activityDisplay2 = new ActivityDisplay(this, display);
        addChild(activityDisplay2, Integer.MIN_VALUE);
        return activityDisplay2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDisplayAdded(int displayId) {
        return getActivityDisplayOrCreate(displayId) != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getDefaultDisplayHomeActivity() {
        return getDefaultDisplayHomeActivityForUser(this.mCurrentUser);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getDefaultDisplayHomeActivityForUser(int userId) {
        return getActivityDisplay(0).getHomeActivityForUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startHomeOnAllDisplays(int userId, String reason) {
        boolean homeStarted = false;
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            int displayId = this.mActivityDisplays.get(i).mDisplayId;
            homeStarted |= startHomeOnDisplay(userId, reason, displayId);
        }
        return homeStarted;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startHomeOnEmptyDisplays(String reason) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            if (display.topRunningActivity() == null) {
                startHomeOnDisplay(this.mCurrentUser, reason, display.mDisplayId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startHomeOnDisplay(int userId, String reason, int displayId) {
        return startHomeOnDisplay(userId, reason, displayId, false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startHomeOnDisplay(int userId, String reason, int displayId, boolean allowInstrumenting, boolean fromHomeKey) {
        if (displayId == -1) {
            displayId = getTopDisplayFocusedStack().mDisplayId;
        }
        Intent homeIntent = null;
        ActivityInfo aInfo = null;
        if (displayId == 0) {
            homeIntent = this.mService.getHomeIntent();
            aInfo = resolveHomeActivity(userId, homeIntent);
        } else if (shouldPlaceSecondaryHomeOnDisplay(displayId)) {
            Pair<ActivityInfo, Intent> info = resolveSecondaryHomeActivity(userId, displayId);
            aInfo = (ActivityInfo) info.first;
            homeIntent = (Intent) info.second;
            if (homeIntent != null) {
                String pkg = (aInfo == null || aInfo.applicationInfo == null) ? "" : aInfo.applicationInfo.packageName;
                String cls = aInfo != null ? aInfo.name : "";
                homeIntent = ActivityInfoManager.appendIntentExtra(this.mService.mContext, xpActivityInfo.create(homeIntent, pkg, cls));
                homeIntent.addFlags(1024);
            }
        }
        if (aInfo == null || homeIntent == null || !canStartHomeOnDisplay(aInfo, displayId, allowInstrumenting)) {
            return false;
        }
        homeIntent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
        homeIntent.setFlags(homeIntent.getFlags() | 268435456);
        if (fromHomeKey) {
            homeIntent.putExtra("android.intent.extra.FROM_HOME_KEY", true);
        }
        String myReason = reason + ":" + userId + ":" + UserHandle.getUserId(aInfo.applicationInfo.uid) + ":" + displayId;
        this.mService.getActivityStartController().startHomeActivity(homeIntent, aInfo, myReason, displayId);
        return true;
    }

    @VisibleForTesting
    ActivityInfo resolveHomeActivity(int userId, Intent homeIntent) {
        ComponentName comp = homeIntent.getComponent();
        ActivityInfo aInfo = null;
        try {
            if (comp != null) {
                aInfo = AppGlobals.getPackageManager().getActivityInfo(comp, 1024, userId);
            } else {
                String resolvedType = homeIntent.resolveTypeIfNeeded(this.mService.mContext.getContentResolver());
                ResolveInfo info = AppGlobals.getPackageManager().resolveIntent(homeIntent, resolvedType, 1024, userId);
                if (info != null) {
                    aInfo = info.activityInfo;
                }
            }
        } catch (RemoteException e) {
        }
        if (aInfo == null) {
            Slog.wtf("ActivityTaskManager", "No home screen found for " + homeIntent, new Throwable());
            return null;
        }
        ActivityInfo aInfo2 = new ActivityInfo(aInfo);
        aInfo2.applicationInfo = this.mService.getAppInfoForUser(aInfo2.applicationInfo, userId);
        return aInfo2;
    }

    @VisibleForTesting
    Pair<ActivityInfo, Intent> resolveSecondaryHomeActivity(int userId, int displayId) {
        if (displayId == 0) {
            throw new IllegalArgumentException("resolveSecondaryHomeActivity: Should not be DEFAULT_DISPLAY");
        }
        Intent homeIntent = this.mService.getHomeIntent();
        ActivityInfo aInfo = resolveHomeActivity(userId, homeIntent);
        if (aInfo != null) {
            if (ResolverActivity.class.getName().equals(aInfo.name)) {
                aInfo = null;
            } else {
                homeIntent = this.mService.getSecondaryHomeIntent(aInfo.applicationInfo.packageName);
                List<ResolveInfo> resolutions = resolveActivities(userId, homeIntent);
                int size = resolutions.size();
                String targetName = aInfo.name;
                aInfo = null;
                int i = 0;
                while (true) {
                    if (i >= size) {
                        break;
                    }
                    ResolveInfo resolveInfo = resolutions.get(i);
                    if (!resolveInfo.activityInfo.name.equals(targetName)) {
                        i++;
                    } else {
                        aInfo = resolveInfo.activityInfo;
                        break;
                    }
                }
                if (aInfo == null && size > 0) {
                    aInfo = resolutions.get(0).activityInfo;
                }
            }
        }
        if (aInfo != null && !canStartHomeOnDisplay(aInfo, displayId, false)) {
            aInfo = null;
        }
        if (aInfo == null) {
            homeIntent = this.mService.getSecondaryHomeIntent(null);
            aInfo = resolveHomeActivity(userId, homeIntent);
        }
        return Pair.create(aInfo, homeIntent);
    }

    @VisibleForTesting
    List<ResolveInfo> resolveActivities(int userId, Intent homeIntent) {
        try {
            String resolvedType = homeIntent.resolveTypeIfNeeded(this.mService.mContext.getContentResolver());
            List<ResolveInfo> resolutions = AppGlobals.getPackageManager().queryIntentActivities(homeIntent, resolvedType, 1024, userId).getList();
            return resolutions;
        } catch (RemoteException e) {
            List<ResolveInfo> resolutions2 = new ArrayList<>();
            return resolutions2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeHomeActivity(ActivityRecord prev, String reason, int displayId) {
        if (!this.mService.isBooting() && !this.mService.isBooted()) {
            return false;
        }
        if (displayId == -1) {
            displayId = 0;
        }
        ActivityRecord r = getActivityDisplay(displayId).getHomeActivity();
        String myReason = reason + " resumeHomeActivity";
        if (r != null && !r.finishing) {
            r.moveFocusableActivityToTop(myReason);
            return resumeFocusedStacksTopActivities(r.getActivityStack(), prev, null);
        }
        return startHomeOnDisplay(this.mCurrentUser, myReason, displayId);
    }

    boolean shouldPlaceSecondaryHomeOnDisplay(int displayId) {
        ActivityDisplay display;
        if (displayId == 0) {
            throw new IllegalArgumentException("shouldPlaceSecondaryHomeOnDisplay: Should not be DEFAULT_DISPLAY");
        }
        if (displayId != -1 && this.mService.mSupportsMultiDisplay) {
            boolean deviceProvisioned = Settings.Global.getInt(this.mService.mContext.getContentResolver(), "device_provisioned", 0) != 0;
            if (deviceProvisioned || xpDisplayInfo.isIcmDisplay(displayId)) {
                return (StorageManager.isUserKeyUnlocked(this.mCurrentUser) || xpDisplayInfo.isIcmDisplay(displayId)) && (display = getActivityDisplay(displayId)) != null && !display.isRemoved() && display.supportsSystemDecorations();
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canStartHomeOnDisplay(ActivityInfo homeInfo, int displayId, boolean allowInstrumenting) {
        if (this.mService.mFactoryTest == 1 && this.mService.mTopAction == null) {
            return false;
        }
        WindowProcessController app = this.mService.getProcessController(homeInfo.processName, homeInfo.applicationInfo.uid);
        if (allowInstrumenting || app == null || !app.isInstrumenting()) {
            if (displayId == 0 || (displayId != -1 && displayId == this.mService.mVr2dDisplayId)) {
                return true;
            }
            if (shouldPlaceSecondaryHomeOnDisplay(displayId)) {
                boolean supportMultipleInstance = (homeInfo.launchMode == 2 || homeInfo.launchMode == 3) ? false : true;
                return supportMultipleInstance;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureVisibilityAndConfig(ActivityRecord starting, int displayId, boolean markFrozenIfConfigChanged, boolean deferResume) {
        IApplicationToken.Stub stub = null;
        ensureActivitiesVisible(null, 0, false, false);
        if (displayId == -1) {
            return true;
        }
        DisplayContent displayContent = this.mRootWindowContainer.getDisplayContent(displayId);
        Configuration config = null;
        if (displayContent != null) {
            Configuration displayOverrideConfiguration = getDisplayOverrideConfiguration(displayId);
            if (starting != null && starting.mayFreezeScreenLocked(starting.app)) {
                stub = starting.appToken;
            }
            config = displayContent.updateOrientationFromAppTokens(displayOverrideConfiguration, stub, true);
        }
        if (starting != null && markFrozenIfConfigChanged && config != null) {
            starting.frozenBeforeDestroy = true;
        }
        return this.mService.updateDisplayOverrideConfigurationLocked(config, starting, deferResume, displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<IBinder> getTopVisibleActivities() {
        ActivityRecord top;
        ArrayList<IBinder> topActivityTokens = new ArrayList<>();
        ActivityStack topFocusedStack = getTopDisplayFocusedStack();
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            for (int j = display.getChildCount() - 1; j >= 0; j--) {
                ActivityStack stack = display.getChildAt(j);
                if (stack.shouldBeVisible(null) && (top = stack.getTopActivity()) != null) {
                    if (stack == topFocusedStack) {
                        topActivityTokens.add(0, top.appToken);
                    } else {
                        topActivityTokens.add(top.appToken);
                    }
                }
            }
        }
        return topActivityTokens;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getTopDisplayFocusedStack() {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityStack focusedStack = this.mActivityDisplays.get(i).getFocusedStack();
            if (focusedStack != null) {
                return focusedStack;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getTopResumedActivity() {
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack == null) {
            return null;
        }
        ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity != null && resumedActivity.app != null) {
            return resumedActivity;
        }
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            ActivityRecord resumedActivityOnDisplay = display.getResumedActivity();
            if (resumedActivityOnDisplay != null) {
                return resumedActivityOnDisplay;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable(ConfigurationContainer container, boolean alwaysFocusable) {
        if (container.inSplitScreenPrimaryWindowingMode() && this.mIsDockMinimized) {
            return false;
        }
        return container.getWindowConfiguration().canReceiveKeys() || alwaysFocusable;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopDisplayFocusedStack(ActivityStack stack) {
        return stack != null && stack == getTopDisplayFocusedStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePreviousProcess(ActivityRecord r) {
        WindowProcessController fgApp = null;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            int stackNdx = display.getChildCount() - 1;
            while (true) {
                if (stackNdx >= 0) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    if (isTopDisplayFocusedStack(stack)) {
                        ActivityRecord resumedActivity = stack.getResumedActivity();
                        if (resumedActivity != null) {
                            fgApp = resumedActivity.app;
                        } else if (stack.mPausingActivity != null) {
                            fgApp = stack.mPausingActivity.app;
                        }
                    } else {
                        stackNdx--;
                    }
                }
            }
        }
        if (r.hasProcess() && fgApp != null && r.app != fgApp && r.lastVisibleTime > this.mService.mPreviousProcessVisibleTime && r.app != this.mService.mHomeProcess) {
            this.mService.mPreviousProcess = r.app;
            this.mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean attachApplication(WindowProcessController app) throws RemoteException {
        String processName = app.mName;
        boolean didSomething = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            ActivityStack stack = display.getFocusedStack();
            if (stack != null) {
                stack.getAllRunningVisibleActivitiesLocked(this.mTmpActivityList);
                ActivityRecord top = stack.topRunningActivityLocked();
                int size = this.mTmpActivityList.size();
                for (int i = 0; i < size; i++) {
                    ActivityRecord activity = this.mTmpActivityList.get(i);
                    if (activity.app == null && app.mUid == activity.info.applicationInfo.uid && processName.equals(activity.processName)) {
                        try {
                            if (this.mStackSupervisor.realStartActivityLocked(activity, app, top == activity, true)) {
                                didSomething = true;
                            }
                        } catch (RemoteException e) {
                            Slog.w("ActivityTaskManager", "Exception in new application when starting activity " + top.intent.getComponent().flattenToShortString(), e);
                            throw e;
                        }
                    }
                }
                continue;
            }
        }
        if (!didSomething) {
            ensureActivitiesVisible(null, 0, false);
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureActivitiesVisible(ActivityRecord starting, int configChanges, boolean preserveWindows) {
        ensureActivitiesVisible(starting, configChanges, preserveWindows, true);
    }

    void ensureActivitiesVisible(ActivityRecord starting, int configChanges, boolean preserveWindows, boolean notifyClients) {
        this.mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
                ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
                display.ensureActivitiesVisible(starting, configChanges, preserveWindows, notifyClients);
            }
        } finally {
            this.mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean switchUser(int userId, UserState uss) {
        int focusStackId = getTopDisplayFocusedStack().getStackId();
        ActivityStack dockedStack = getDefaultDisplay().getSplitScreenPrimaryStack();
        if (dockedStack != null) {
            this.mStackSupervisor.moveTasksToFullscreenStackLocked(dockedStack, dockedStack.isFocusedStackOnDisplay());
        }
        removeStacksInWindowingModes(2);
        this.mUserStackInFront.put(this.mCurrentUser, focusStackId);
        int restoreStackId = this.mUserStackInFront.get(userId, getDefaultDisplay().getHomeStack().mStackId);
        this.mCurrentUser = userId;
        this.mStackSupervisor.mStartingUsers.add(uss);
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.switchUserLocked(userId);
                TaskRecord task = stack.topTask();
                if (task != null) {
                    stack.positionChildWindowContainerAtTop(task);
                }
            }
        }
        ActivityStack stack2 = getStack(restoreStackId);
        if (stack2 == null) {
            stack2 = getDefaultDisplay().getHomeStack();
        }
        boolean homeInFront = stack2.isActivityTypeHome();
        if (stack2.isOnHomeDisplay()) {
            stack2.moveToFront("switchUserOnHomeDisplay");
        } else {
            resumeHomeActivity(null, "switchUserOnOtherDisplay", 0);
        }
        return homeInFront;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUser(int userId) {
        this.mUserStackInFront.delete(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateUserStack(int userId, ActivityStack stack) {
        if (userId != this.mCurrentUser) {
            this.mUserStackInFront.put(userId, stack != null ? stack.getStackId() : getDefaultDisplay().getHomeStack().mStackId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizeStack(ActivityStack stack, Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds, boolean preserveWindows, boolean allowResizeInDockedMode, boolean deferResume) {
        if (stack.inSplitScreenPrimaryWindowingMode()) {
            this.mStackSupervisor.resizeDockedStackLocked(bounds, tempTaskBounds, tempTaskInsetBounds, null, null, preserveWindows, deferResume);
            return;
        }
        boolean splitScreenActive = getDefaultDisplay().hasSplitScreenPrimaryStack();
        if (!allowResizeInDockedMode && !stack.getWindowConfiguration().tasksAreFloating() && splitScreenActive) {
            return;
        }
        Trace.traceBegin(64L, "am.resizeStack_" + stack.mStackId);
        this.mWindowManager.deferSurfaceLayout();
        try {
            if (stack.affectedBySplitScreenResize()) {
                if (bounds == null && stack.inSplitScreenWindowingMode()) {
                    stack.setWindowingMode(1);
                } else if (splitScreenActive) {
                    stack.setWindowingMode(4);
                }
            }
            stack.resize(bounds, tempTaskBounds, tempTaskInsetBounds);
            if (!deferResume) {
                try {
                    stack.ensureVisibleActivitiesConfigurationLocked(stack.topRunningActivityLocked(), preserveWindows);
                } catch (Throwable th) {
                    th = th;
                    this.mWindowManager.continueSurfaceLayout();
                    Trace.traceEnd(64L);
                    throw th;
                }
            }
            this.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
        } catch (Throwable th2) {
            th = th2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveStackToDisplay(int stackId, int displayId, boolean onTop) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown displayId=" + displayId);
        }
        ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown stackId=" + stackId);
        }
        ActivityDisplay currentDisplay = stack.getDisplay();
        if (currentDisplay == null) {
            throw new IllegalStateException("moveStackToDisplay: Stack with stack=" + stack + " is not attached to any display.");
        } else if (currentDisplay.mDisplayId == displayId) {
            throw new IllegalArgumentException("Trying to move stack=" + stack + " to its current displayId=" + displayId);
        } else if (activityDisplay.isSingleTaskInstance() && activityDisplay.getChildCount() > 0) {
            Slog.e("ActivityTaskManager", "Can not move stack=" + stack + " to single task instance display=" + activityDisplay);
        } else {
            stack.reparent(activityDisplay, onTop, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean moveTopStackActivityToPinnedStack(int stackId) {
        ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveTopStackActivityToPinnedStack: Unknown stackId=" + stackId);
        }
        ActivityRecord r = stack.topRunningActivityLocked();
        if (r == null) {
            Slog.w("ActivityTaskManager", "moveTopStackActivityToPinnedStack: No top running activity in stack=" + stack);
            return false;
        } else if (!this.mService.mForceResizableActivities && !r.supportsPictureInPicture()) {
            Slog.w("ActivityTaskManager", "moveTopStackActivityToPinnedStack: Picture-In-Picture not supported for  r=" + r);
            return false;
        } else {
            moveActivityToPinnedStack(r, null, 0.0f, "moveTopActivityToPinnedStack");
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveActivityToPinnedStack(ActivityRecord r, Rect sourceHintBounds, float aspectRatio, String reason) {
        ActivityStack stack;
        this.mWindowManager.deferSurfaceLayout();
        ActivityDisplay display = r.getActivityStack().getDisplay();
        ActivityStack stack2 = display.getPinnedStack();
        if (stack2 != null) {
            this.mStackSupervisor.moveTasksToFullscreenStackLocked(stack2, false);
        }
        ActivityStack stack3 = display.getOrCreateStack(2, r.getActivityType(), true);
        Rect destBounds = stack3.getDefaultPictureInPictureBounds(aspectRatio);
        try {
            TaskRecord task = r.getTaskRecord();
            try {
                resizeStack(stack3, task.getRequestedOverrideBounds(), null, null, false, true, false);
                if (task.mActivities.size() == 1) {
                    stack = stack3;
                    try {
                        task.reparent(stack3, true, 0, false, true, false, reason);
                    } catch (Throwable th) {
                        th = th;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                } else {
                    stack = stack3;
                    try {
                        TaskRecord newTask = task.getStack().createTaskRecord(this.mStackSupervisor.getNextTaskIdForUserLocked(r.mUserId), r.info, r.intent, null, null, true);
                        r.reparent(newTask, Integer.MAX_VALUE, "moveActivityToStack");
                        newTask.reparent(stack, true, 0, false, true, false, reason);
                    } catch (Throwable th2) {
                        th = th2;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                }
                r.supportsEnterPipOnTaskSwitch = false;
                this.mWindowManager.continueSurfaceLayout();
                stack.animateResizePinnedStack(sourceHintBounds, destBounds, -1, true);
                ensureActivitiesVisible(null, 0, false);
                resumeFocusedStacksTopActivities();
                this.mService.getTaskChangeNotificationController().notifyActivityPinned(r);
            } catch (Throwable th3) {
                th = th3;
            }
        } catch (Throwable th4) {
            th = th4;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void executeAppTransitionForAllDisplay() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            display.mDisplayContent.executeAppTransition();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDockedStackMinimized(boolean minimized) {
        ActivityStack current = getTopDisplayFocusedStack();
        this.mIsDockMinimized = minimized;
        if (this.mIsDockMinimized && current.inSplitScreenPrimaryWindowingMode()) {
            current.adjustFocusToNextFocusableStack("setDockedStackMinimized");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord findTask(ActivityRecord r, int preferredDisplayId) {
        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityTaskManager", "Looking for task of " + r);
        }
        this.mTmpFindTaskResult.clear();
        ActivityDisplay preferredDisplay = getActivityDisplay(preferredDisplayId);
        if (preferredDisplay != null) {
            preferredDisplay.findTaskLocked(r, true, this.mTmpFindTaskResult);
            if (this.mTmpFindTaskResult.mIdealMatch) {
                return this.mTmpFindTaskResult.mRecord;
            }
        }
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            if (display.mDisplayId != preferredDisplayId) {
                display.findTaskLocked(r, false, this.mTmpFindTaskResult);
                if (this.mTmpFindTaskResult.mIdealMatch) {
                    return this.mTmpFindTaskResult.mRecord;
                }
            }
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS && this.mTmpFindTaskResult.mRecord == null) {
            Slog.d("ActivityTaskManager", "No task found");
        }
        return this.mTmpFindTaskResult.mRecord;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int finishTopCrashedActivities(WindowProcessController app, String reason) {
        TaskRecord finishedTask = null;
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = 0; stackNdx < display.getChildCount(); stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                TaskRecord t = stack.finishTopCrashedActivityLocked(app, reason);
                if (stack == focusedStack || finishedTask == null) {
                    finishedTask = t;
                }
            }
        }
        if (finishedTask != null) {
            return finishedTask.taskId;
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeFocusedStacksTopActivities() {
        return resumeFocusedStacksTopActivities(null, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeFocusedStacksTopActivities(ActivityStack targetStack, ActivityRecord target, ActivityOptions targetOptions) {
        ActivityStack focusedStack;
        if (!this.mStackSupervisor.readyToResume()) {
            return false;
        }
        boolean result = false;
        if (targetStack != null && (targetStack.isTopStackOnDisplay() || getTopDisplayFocusedStack() == targetStack)) {
            result = targetStack.resumeTopActivityUncheckedLocked(target, targetOptions);
        }
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            boolean resumedOnDisplay = false;
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord topRunningActivity = stack.topRunningActivityLocked();
                if (stack.isFocusableAndVisible() && topRunningActivity != null) {
                    if (stack == targetStack) {
                        resumedOnDisplay |= result;
                    } else if (display.isTopStack(stack) && topRunningActivity.isState(ActivityStack.ActivityState.RESUMED)) {
                        stack.executeAppTransition(targetOptions);
                    } else {
                        resumedOnDisplay |= topRunningActivity.makeActiveIfNeeded(target);
                    }
                }
            }
            if (!resumedOnDisplay && (focusedStack = display.getFocusedStack()) != null) {
                focusedStack.resumeTopActivityUncheckedLocked(target, targetOptions);
            }
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applySleepTokens(boolean applyToStacks) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep != display.isSleeping()) {
                display.setIsSleeping(displayShouldSleep);
                if (applyToStacks) {
                    for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                        ActivityStack stack = display.getChildAt(stackNdx);
                        if (displayShouldSleep) {
                            stack.goToSleepIfPossible(false);
                        } else {
                            if (display.isSingleTaskInstance()) {
                                display.mDisplayContent.prepareAppTransition(28, false);
                            }
                            stack.awakeFromSleepingLocked();
                            if (stack.isFocusedStackOnDisplay() && !this.mStackSupervisor.getKeyguardController().isKeyguardOrAodShowing(display.mDisplayId)) {
                                resumeFocusedStacksTopActivities();
                            }
                        }
                    }
                    if (!displayShouldSleep && !this.mStackSupervisor.mGoingToSleepActivities.isEmpty()) {
                        Iterator<ActivityRecord> it = this.mStackSupervisor.mGoingToSleepActivities.iterator();
                        while (it.hasNext()) {
                            ActivityRecord r = it.next();
                            if (r.getDisplayId() == display.mDisplayId) {
                                it.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public <T extends ActivityStack> T getStack(int stackId) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            T stack = (T) this.mActivityDisplays.get(i).getStack(stackId);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            T stack = (T) this.mActivityDisplays.get(i).getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private ActivityManager.StackInfo getStackInfo(ActivityStack stack) {
        String str;
        int displayId = stack.mDisplayId;
        ActivityDisplay display = getActivityDisplay(displayId);
        ActivityManager.StackInfo info = new ActivityManager.StackInfo();
        stack.getWindowContainerBounds(info.bounds);
        info.displayId = displayId;
        info.stackId = stack.mStackId;
        info.userId = stack.mCurrentUser;
        info.visible = stack.shouldBeVisible(null);
        info.position = display != null ? display.getIndexOf(stack) : 0;
        info.configuration.setTo(stack.getConfiguration());
        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        int numTasks = tasks.size();
        int[] taskIds = new int[numTasks];
        String[] taskNames = new String[numTasks];
        Rect[] taskBounds = new Rect[numTasks];
        int[] taskUserIds = new int[numTasks];
        for (int i = 0; i < numTasks; i++) {
            TaskRecord task = tasks.get(i);
            taskIds[i] = task.taskId;
            if (task.origActivity != null) {
                str = task.origActivity.flattenToString();
            } else if (task.realActivity != null) {
                str = task.realActivity.flattenToString();
            } else {
                str = task.getTopActivity() != null ? task.getTopActivity().packageName : UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
            }
            taskNames[i] = str;
            taskBounds[i] = new Rect();
            task.getWindowContainerBounds(taskBounds[i]);
            taskUserIds[i] = task.userId;
        }
        info.taskIds = taskIds;
        info.taskNames = taskNames;
        info.taskBounds = taskBounds;
        info.taskUserIds = taskUserIds;
        ActivityRecord top = stack.topRunningActivityLocked();
        info.topActivity = top != null ? top.intent.getComponent() : null;
        return info;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.StackInfo getStackInfo(int stackId) {
        ActivityStack stack = getStack(stackId);
        if (stack != null) {
            return getStackInfo(stack);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType) {
        ActivityStack stack = getStack(windowingMode, activityType);
        if (stack != null) {
            return getStackInfo(stack);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<ActivityManager.StackInfo> getAllStackInfos() {
        ArrayList<ActivityManager.StackInfo> list = new ArrayList<>();
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                list.add(getStackInfo(stack));
            }
        }
        return list;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferUpdateBounds(int activityType) {
        ActivityStack stack = getStack(0, activityType);
        if (stack != null) {
            stack.deferUpdateBounds();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueUpdateBounds(int activityType) {
        ActivityStack stack = getStack(0, activityType);
        if (stack != null) {
            stack.continueUpdateBounds();
        }
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayAdded(int displayId) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityTaskManager", "Display added displayId=" + displayId);
        }
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityDisplay display = getActivityDisplayOrCreate(displayId);
                if (display == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (this.mService.isBooted() || this.mService.isBooting()) {
                    startSystemDecorations(display.mDisplayContent);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private void startSystemDecorations(DisplayContent displayContent) {
        startHomeOnDisplay(this.mCurrentUser, "displayAdded", displayContent.getDisplayId());
        displayContent.getDisplayPolicy().notifyDisplayReady();
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayRemoved(int displayId) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityTaskManager", "Display removed displayId=" + displayId);
        }
        if (displayId == 0) {
            throw new IllegalArgumentException("Can't remove the primary display.");
        }
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityDisplay activityDisplay = getActivityDisplay(displayId);
                if (activityDisplay == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                activityDisplay.remove();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayChanged(int displayId) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityTaskManager", "Display changed displayId=" + displayId);
        }
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityDisplay activityDisplay = getActivityDisplay(displayId);
                if (activityDisplay != null) {
                    activityDisplay.onDisplayChanged();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateUIDsPresentOnDisplay() {
        this.mDisplayAccessUIDs.clear();
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayNdx);
            if (activityDisplay.isPrivate()) {
                this.mDisplayAccessUIDs.append(activityDisplay.mDisplayId, activityDisplay.getPresentUIDs());
            }
        }
        this.mDisplayManagerInternal.setDisplayAccessUIDs(this.mDisplayAccessUIDs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack findStackBehind(ActivityStack stack) {
        ActivityDisplay display = getActivityDisplay(stack.mDisplayId);
        if (display != null) {
            for (int i = display.getChildCount() - 1; i >= 0; i--) {
                if (display.getChildAt(i) == stack && i > 0) {
                    return display.getChildAt(i - 1);
                }
            }
        }
        throw new IllegalStateException("Failed to find a stack behind stack=" + stack + " in=" + display);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mActivityDisplays.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ActivityDisplay getChildAt(int index) {
        return this.mActivityDisplays.get(index);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onChildPositionChanged(ActivityDisplay display, int position) {
        if (display != null) {
            positionChildAt(display, position);
        }
    }

    private void positionChildAt(ActivityDisplay display, int position) {
        if (position >= this.mActivityDisplays.size()) {
            position = this.mActivityDisplays.size() - 1;
        } else if (position < 0) {
            position = 0;
        }
        if (this.mActivityDisplays.isEmpty()) {
            this.mActivityDisplays.add(display);
        } else if (this.mActivityDisplays.get(position) != display) {
            this.mActivityDisplays.remove(display);
            this.mActivityDisplays.add(position, display);
        }
        this.mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    @VisibleForTesting
    void addChild(ActivityDisplay activityDisplay, int position) {
        positionChildAt(activityDisplay, position);
        this.mRootWindowContainer.positionChildAt(position, activityDisplay.mDisplayContent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeChild(ActivityDisplay activityDisplay) {
        this.mActivityDisplays.remove(activityDisplay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration getDisplayOverrideConfiguration(int displayId) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        return activityDisplay.getRequestedOverrideConfiguration();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayOverrideConfiguration(Configuration overrideConfiguration, int displayId) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        activityDisplay.onRequestedOverrideConfigurationChanged(overrideConfiguration);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareForShutdown() {
        for (int i = 0; i < this.mActivityDisplays.size(); i++) {
            createSleepToken("shutdown", this.mActivityDisplays.get(i).mDisplayId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityTaskManagerInternal.SleepToken createSleepToken(String tag, int displayId) {
        ActivityDisplay display = getActivityDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Invalid display: " + displayId);
        }
        SleepTokenImpl token = new SleepTokenImpl(tag, displayId);
        this.mSleepTokens.add(token);
        display.mAllSleepTokens.add(token);
        return token;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeSleepToken(SleepTokenImpl token) {
        this.mSleepTokens.remove(token);
        ActivityDisplay display = getActivityDisplay(token.mDisplayId);
        if (display != null) {
            display.mAllSleepTokens.remove(token);
            if (display.mAllSleepTokens.isEmpty()) {
                this.mService.updateSleepIfNeededLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.addStartingWindowsForVisibleActivities(taskSwitch);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void invalidateTaskLayers() {
        this.mTaskLayersChanged = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void rankTaskLayersIfNeeded() {
        if (!this.mTaskLayersChanged) {
            return;
        }
        this.mTaskLayersChanged = false;
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            int baseLayer = 0;
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                baseLayer += stack.rankTaskLayers(baseLayer);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.clearOtherAppTimeTrackers(except);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleDestroyAllActivities(WindowProcessController app, String reason) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.scheduleDestroyActivities(app, reason);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void releaseSomeActivitiesLocked(WindowProcessController app, String reason) {
        ArraySet<TaskRecord> tasks = app.getReleaseSomeActivitiesTasks();
        if (tasks == null) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                Slog.d("ActivityTaskManager", "Didn't find two or more tasks to release");
                return;
            }
            return;
        }
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            int stackCount = display.getChildCount();
            for (int stackNdx = 0; stackNdx < stackCount; stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (stack.releaseSomeActivitiesLocked(app, tasks, reason) > 0) {
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean putStacksToSleep(boolean allowDelay, boolean shuttingDown) {
        boolean allSleep = true;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                if (stackNdx < display.getChildCount()) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    if (allowDelay) {
                        allSleep &= stack.goToSleepIfPossible(shuttingDown);
                    } else {
                        stack.goToSleep();
                    }
                }
            }
        }
        return allSleep;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleAppCrash(WindowProcessController app) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.handleAppCrash(app);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord findActivity(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord ar = stack.findActivityLocked(intent, info, compareIntentFilters);
                if (ar != null) {
                    return ar;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasAwakeDisplay() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            if (!display.shouldSleep()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getLaunchStack(ActivityRecord r, ActivityOptions options, TaskRecord candidateTask, boolean onTop) {
        return (T) getLaunchStack(r, options, candidateTask, onTop, null, -1, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getLaunchStack(ActivityRecord r, ActivityOptions options, TaskRecord candidateTask, boolean onTop, LaunchParamsController.LaunchParams launchParams, int realCallingPid, int realCallingUid) {
        int taskId;
        int displayId;
        T stack;
        int displayId2;
        T stack2;
        int displayId3 = -1;
        if (options == null) {
            taskId = -1;
        } else {
            int taskId2 = options.getLaunchTaskId();
            displayId3 = options.getLaunchDisplayId();
            taskId = taskId2;
        }
        if (taskId != -1) {
            options.setLaunchTaskId(-1);
            TaskRecord task = anyTaskForId(taskId, 2, options, onTop);
            options.setLaunchTaskId(taskId);
            if (task != null) {
                return (T) task.getStack();
            }
        }
        int activityType = resolveActivityType(r, options, candidateTask);
        if (launchParams != null && launchParams.mPreferredDisplayId != -1) {
            int displayId4 = launchParams.mPreferredDisplayId;
            displayId = displayId4;
        } else {
            displayId = displayId3;
        }
        boolean canLaunchOnDisplayFromStartRequest = realCallingPid != 0 && realCallingUid > 0 && r != null && this.mStackSupervisor.canPlaceEntityOnDisplay(displayId, realCallingPid, realCallingUid, r.info);
        if (displayId != -1 && (canLaunchOnDisplay(r, displayId) || canLaunchOnDisplayFromStartRequest)) {
            if (r == null) {
                displayId2 = displayId;
            } else {
                displayId2 = displayId;
                T stack3 = (T) getValidLaunchStackOnDisplay(displayId, r, candidateTask, options, launchParams);
                if (stack3 != null) {
                    return stack3;
                }
            }
            ActivityDisplay display = getActivityDisplayOrCreate(displayId2);
            if (display != null && (stack2 = (T) display.getOrCreateStack(r, options, candidateTask, activityType, onTop)) != null) {
                return stack2;
            }
        }
        ActivityStack activityStack = null;
        ActivityDisplay display2 = null;
        if (candidateTask != null) {
            activityStack = candidateTask.getStack();
        }
        if (activityStack == null && r != null) {
            stack = (T) r.getActivityStack();
        } else {
            stack = (T) activityStack;
        }
        if (stack != null && (display2 = stack.getDisplay()) != null && canLaunchOnDisplay(r, display2.mDisplayId)) {
            int windowingMode = launchParams != null ? launchParams.mWindowingMode : 0;
            if (windowingMode == 0) {
                windowingMode = display2.resolveWindowingMode(r, options, candidateTask, activityType);
            }
            if (stack.isCompatible(windowingMode, activityType)) {
                return stack;
            }
            if (windowingMode == 4 && display2.getSplitScreenPrimaryStack() == stack && candidateTask == stack.topTask()) {
                return stack;
            }
        }
        return (T) ((display2 == null || !canLaunchOnDisplay(r, display2.mDisplayId)) ? getDefaultDisplay() : display2).getOrCreateStack(r, options, candidateTask, activityType, onTop);
    }

    private boolean canLaunchOnDisplay(ActivityRecord r, int displayId) {
        if (r == null) {
            return true;
        }
        return r.canBeLaunchedOnDisplay(displayId);
    }

    private ActivityStack getValidLaunchStackOnDisplay(int displayId, ActivityRecord r, TaskRecord candidateTask, ActivityOptions options, LaunchParamsController.LaunchParams launchParams) {
        int windowingMode;
        ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("Display with displayId=" + displayId + " not found.");
        } else if (r.canBeLaunchedOnDisplay(displayId)) {
            if (r.getDisplayId() == displayId && r.getTaskRecord() == candidateTask) {
                return candidateTask.getStack();
            }
            if (launchParams != null) {
                windowingMode = launchParams.mWindowingMode;
            } else {
                windowingMode = options != null ? options.getLaunchWindowingMode() : r.getWindowingMode();
            }
            int windowingMode2 = activityDisplay.validateWindowingMode(windowingMode, r, candidateTask, r.getActivityType());
            for (int i = activityDisplay.getChildCount() - 1; i >= 0; i--) {
                ActivityStack stack = activityDisplay.getChildAt(i);
                if (isValidLaunchStack(stack, r, windowingMode2)) {
                    return stack;
                }
            }
            if (displayId != 0) {
                int activityType = (options == null || options.getLaunchActivityType() == 0) ? r.getActivityType() : options.getLaunchActivityType();
                return activityDisplay.createStack(windowingMode2, activityType, true);
            }
            return null;
        } else {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getValidLaunchStackOnDisplay(int displayId, ActivityRecord r, ActivityOptions options, LaunchParamsController.LaunchParams launchParams) {
        return getValidLaunchStackOnDisplay(displayId, r, null, options, launchParams);
    }

    private boolean isValidLaunchStack(ActivityStack stack, ActivityRecord r, int windowingMode) {
        int activityType = stack.getActivityType();
        if (activityType != 2) {
            if (activityType != 3) {
                if (activityType == 4) {
                    return r.isActivityTypeAssistant();
                }
                if (stack.getWindowingMode() == 3 && r.supportsSplitScreenWindowingMode()) {
                    if (windowingMode == 3 || windowingMode == 0) {
                        return true;
                    }
                    return false;
                }
                return false;
            }
            return r.isActivityTypeRecents();
        }
        return r.isActivityTypeHome();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int resolveActivityType(ActivityRecord r, ActivityOptions options, TaskRecord task) {
        int activityType = r != null ? r.getActivityType() : 0;
        if (activityType == 0 && task != null) {
            activityType = task.getActivityType();
        }
        if (activityType != 0) {
            return activityType;
        }
        if (options != null) {
            activityType = options.getLaunchActivityType();
        }
        if (activityType != 0) {
            return activityType;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getNextFocusableStack(ActivityStack currentFocus, boolean ignoreCurrent) {
        ActivityStack nextFocusableStack;
        ActivityDisplay preferredDisplay = currentFocus.getDisplay();
        ActivityStack preferredFocusableStack = preferredDisplay.getNextFocusableStack(currentFocus, ignoreCurrent);
        if (preferredFocusableStack != null) {
            return preferredFocusableStack;
        }
        if (preferredDisplay.supportsSystemDecorations()) {
            return null;
        }
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            if (display != preferredDisplay && (nextFocusableStack = display.getNextFocusableStack(currentFocus, ignoreCurrent)) != null) {
                return nextFocusableStack;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getNextValidLaunchStack(ActivityRecord r, int currentFocus) {
        ActivityStack stack;
        int i = this.mActivityDisplays.size();
        while (true) {
            i--;
            if (i < 0) {
                return null;
            }
            ActivityDisplay display = this.mActivityDisplays.get(i);
            if (display.mDisplayId != currentFocus && (stack = getValidLaunchStackOnDisplay(display.mDisplayId, r, null, null)) != null) {
                return stack;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleAppDied(WindowProcessController app) {
        boolean hasVisibleActivities = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                hasVisibleActivities |= stack.handleAppDiedLocked(app);
            }
        }
        return hasVisibleActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeSystemDialogs() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.closeSystemDialogsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean finishDisabledPackageActivities(String packageName, Set<String> filterByClasses, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (stack.finishDisabledPackageActivitiesLocked(packageName, filterByClasses, doit, evenPersistent, userId)) {
                    didSomething = true;
                }
            }
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateActivityApplicationInfo(ApplicationInfo aInfo) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.updateActivityApplicationInfoLocked(aInfo);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishVoiceTask(IVoiceInteractionSession session) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            int numStacks = display.getChildCount();
            for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.finishVoiceTask(session);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksInWindowingModes(int... windowingModes) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            this.mActivityDisplays.get(i).removeStacksInWindowingModes(windowingModes);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksWithActivityTypes(int... activityTypes) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            this.mActivityDisplays.get(i).removeStacksWithActivityTypes(activityTypes);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivity() {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityRecord topActivity = this.mActivityDisplays.get(i).topRunningActivity();
            if (topActivity != null) {
                return topActivity;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResumedActivitiesIdle() {
        ActivityStack stack;
        ActivityRecord resumedActivity;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            if (!display.isSleeping() && (stack = display.getFocusedStack()) != null && stack.numActivities() != 0 && ((resumedActivity = stack.getResumedActivity()) == null || !resumedActivity.idle)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityTaskManager", "allResumedActivitiesIdle: stack=" + stack.mStackId + " " + resumedActivity + " not idle");
                    return false;
                } else {
                    return false;
                }
            }
        }
        sendPowerHintForLaunchEndIfNeeded();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResumedActivitiesVisible() {
        boolean foundResumed = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.getResumedActivity();
                if (r != null) {
                    if (!r.nowVisible) {
                        return false;
                    }
                    foundResumed = true;
                }
            }
        }
        return foundResumed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allPausedActivitiesComplete() {
        boolean pausing = true;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.mPausingActivity;
                if (r != null && !r.isState(ActivityStack.ActivityState.PAUSED, ActivityStack.ActivityState.STOPPED, ActivityStack.ActivityState.STOPPING)) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityTaskManager", "allPausedActivitiesComplete: r=" + r + " state=" + r.getState());
                        pausing = false;
                    } else {
                        return false;
                    }
                }
            }
        }
        return pausing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void lockAllProfileTasks(int userId) {
        this.mWindowManager.deferSurfaceLayout();
        try {
            for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
                ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    List<TaskRecord> tasks = stack.getAllTasks();
                    for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
                        TaskRecord task = tasks.get(taskNdx);
                        int activityNdx = task.mActivities.size() - 1;
                        while (true) {
                            if (activityNdx >= 0) {
                                ActivityRecord activity = task.mActivities.get(activityNdx);
                                if (!activity.finishing && activity.mUserId == userId) {
                                    this.mService.getTaskChangeNotificationController().notifyTaskProfileLocked(task.taskId, userId);
                                    break;
                                }
                                activityNdx--;
                            }
                        }
                    }
                }
            }
        } finally {
            this.mWindowManager.continueSurfaceLayout();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelInitializingActivities() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.cancelInitializingActivities();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord anyTaskForId(int id) {
        return anyTaskForId(id, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord anyTaskForId(int id, int matchMode) {
        return anyTaskForId(id, matchMode, null, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord anyTaskForId(int id, int matchMode, ActivityOptions aOptions, boolean onTop) {
        if (matchMode != 2 && aOptions != null) {
            throw new IllegalArgumentException("Should not specify activity options for non-restore lookup");
        }
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                TaskRecord task = stack.taskForIdLocked(id);
                if (task != null) {
                    if (aOptions == null) {
                        return task;
                    } else {
                        ActivityStack launchStack = getLaunchStack(null, aOptions, task, onTop);
                        if (launchStack == null || stack == launchStack) {
                            return task;
                        }
                        int reparentMode = onTop ? 0 : 2;
                        task.reparent(launchStack, onTop, reparentMode, true, true, "anyTaskForId");
                        return task;
                    }
                }
            }
        }
        if (matchMode == 0) {
            return null;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
            Slog.v("ActivityTaskManager", "Looking for task id=" + id + " in recents");
        }
        TaskRecord task2 = this.mStackSupervisor.mRecentTasks.getTask(id);
        if (task2 == null) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityTaskManager", "\tDidn't find task id=" + id + " in recents");
            }
            return null;
        } else if (matchMode == 1) {
            return task2;
        } else {
            if (!this.mStackSupervisor.restoreRecentTaskLocked(task2, aOptions, onTop)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.w("ActivityTaskManager", "Couldn't restore task id=" + id + " found in recents");
                }
                return null;
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.w("ActivityTaskManager", "Restored task id=" + id + " from in recents");
            }
            return task2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord isInAnyStack(IBinder token) {
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.isInStackLocked(token);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void getRunningTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, int callingUid, boolean allowed, boolean crossUser, ArraySet<Integer> profileIds) {
        this.mStackSupervisor.mRunningTasks.getTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, this.mActivityDisplays, callingUid, allowed, crossUser, profileIds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendPowerHintForLaunchStartIfNeeded(boolean forceSend, ActivityRecord targetActivity) {
        boolean sendHint = forceSend;
        boolean z = false;
        if (!sendHint) {
            sendHint = targetActivity == null || targetActivity.app == null;
        }
        if (!sendHint) {
            boolean noResumedActivities = true;
            boolean allFocusedProcessesDiffer = true;
            for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
                ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayNdx);
                ActivityRecord resumedActivity = activityDisplay.getResumedActivity();
                WindowProcessController resumedActivityProcess = resumedActivity == null ? null : resumedActivity.app;
                noResumedActivities &= resumedActivityProcess == null;
                if (resumedActivityProcess != null) {
                    allFocusedProcessesDiffer &= !resumedActivityProcess.equals(targetActivity.app);
                }
            }
            sendHint = (noResumedActivities || allFocusedProcessesDiffer) ? true : true;
        }
        if (sendHint && this.mService.mPowerManagerInternal != null) {
            this.mService.mPowerManagerInternal.powerHint(8, 1);
            this.mPowerHintSent = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendPowerHintForLaunchEndIfNeeded() {
        if (this.mPowerHintSent && this.mService.mPowerManagerInternal != null) {
            this.mService.mPowerManagerInternal.powerHint(8, 0);
            this.mPowerHintSent = false;
        }
    }

    private void calculateDefaultMinimalSizeOfResizeableTasks() {
        Resources res = this.mService.mContext.getResources();
        float minimalSize = res.getDimension(17105127);
        DisplayMetrics dm = res.getDisplayMetrics();
        this.mDefaultMinSizeOfResizeableTaskDp = (int) (minimalSize / dm.density);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<ActivityRecord> getDumpActivities(String name, boolean dumpVisibleStacksOnly, boolean dumpFocusedStackOnly) {
        if (dumpFocusedStackOnly) {
            return getTopDisplayFocusedStack().getDumpActivitiesLocked(name);
        }
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (!dumpVisibleStacksOnly || stack.shouldBeVisible(null)) {
                    activities.addAll(stack.getDumpActivitiesLocked(name));
                }
            }
        }
        return activities;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("topDisplayFocusedStack=" + getTopDisplayFocusedStack());
        for (int i = this.mActivityDisplays.size() + (-1); i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            display.dump(pw, prefix);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("Display override configurations:");
        int displayCount = this.mActivityDisplays.size();
        for (int i = 0; i < displayCount; i++) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.get(i);
            pw.print(prefix);
            pw.print("  ");
            pw.print(activityDisplay.mDisplayId);
            pw.print(": ");
            pw.println(activityDisplay.getRequestedOverrideConfiguration());
        }
    }

    public void dumpDisplays(PrintWriter pw) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i += -1) {
            ActivityDisplay display = this.mActivityDisplays.get(i);
            pw.print("[id:" + display.mDisplayId + " stacks:");
            display.dumpStacks(pw);
            pw.print("]");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        boolean printed = false;
        boolean needSep = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayNdx);
            pw.print("Display #");
            pw.print(activityDisplay.mDisplayId);
            pw.println(" (activities from top to bottom):");
            ActivityDisplay display = this.mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                pw.println();
                printed = stack.dump(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);
                needSep = printed;
            }
            ActivityStackSupervisor.printThisActivity(pw, activityDisplay.getResumedActivity(), dumpPackage, needSep, " ResumedActivity:");
        }
        return ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mStackSupervisor.mGoingToSleepActivities, "  ", "Sleep", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to sleep:", null) | printed | ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mStackSupervisor.mFinishingActivities, "  ", "Fin", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to finish:", null) | ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mStackSupervisor.mStoppingActivities, "  ", "Stop", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to stop:", null);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayNdx);
            activityDisplay.writeToProto(proto, 2246267895810L, logLevel);
        }
        this.mStackSupervisor.getKeyguardController().writeToProto(proto, 1146756268035L);
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack != null) {
            proto.write(1120986464260L, focusedStack.mStackId);
            ActivityRecord focusedActivity = focusedStack.getDisplay().getResumedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, 1146756268037L);
            }
        } else {
            proto.write(1120986464260L, -1);
        }
        proto.write(1133871366150L, this.mStackSupervisor.mRecentTasks.isRecentsComponentHomeActivity(this.mCurrentUser));
        this.mService.getActivityStartController().writeToProto(proto, 2246267895815L);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class SleepTokenImpl extends ActivityTaskManagerInternal.SleepToken {
        private final long mAcquireTime = SystemClock.uptimeMillis();
        private final int mDisplayId;
        private final String mTag;

        public SleepTokenImpl(String tag, int displayId) {
            this.mTag = tag;
            this.mDisplayId = displayId;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal.SleepToken
        public void release() {
            synchronized (RootActivityContainer.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    Slog.i("ActivityTaskManager", "sleep toke release" + toString());
                    RootActivityContainer.this.removeSleepToken(this);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        public String toString() {
            return "{\"" + this.mTag + "\", display " + this.mDisplayId + ", acquire at " + TimeUtils.formatUptime(this.mAcquireTime) + "}";
        }
    }
}
