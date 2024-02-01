package com.android.server.wm;

import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.IBinder;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.EventLogTags;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.RootActivityContainer;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class ActivityDisplay extends ConfigurationContainer<ActivityStack> implements WindowContainerListener {
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;
    static final int POSITION_TOP = Integer.MAX_VALUE;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_STACK = "ActivityTaskManager";
    private static int sNextFreeStackId = 0;
    Display mDisplay;
    int mDisplayId;
    private ActivityRecord mLastCompatModeActivity;
    private ActivityStack mLastFocusedStack;
    ActivityTaskManagerInternal.SleepToken mOffToken;
    private ActivityStack mPreferredTopFocusableStack;
    private boolean mRemoved;
    private RootActivityContainer mRootActivityContainer;
    private ActivityTaskManagerService mService;
    private boolean mSingleTaskInstance;
    private boolean mSleeping;
    private final ArrayList<ActivityStack> mStacks = new ArrayList<>();
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks = new ArrayList<>();
    private IntArray mDisplayAccessUIDs = new IntArray();
    final ArrayList<ActivityTaskManagerInternal.SleepToken> mAllSleepTokens = new ArrayList<>();
    private ActivityStack mHomeStack = null;
    private ActivityStack mRecentsStack = null;
    private ActivityStack mPinnedStack = null;
    private ActivityStack mSplitScreenPrimaryStack = null;
    private Point mTmpDisplaySize = new Point();
    private final RootActivityContainer.FindTaskResult mTmpFindTaskResult = new RootActivityContainer.FindTaskResult();
    DisplayContent mDisplayContent = createDisplayContent();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public interface OnStackOrderChangedListener {
        void onStackOrderChanged(ActivityStack activityStack);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay(RootActivityContainer root, Display display) {
        this.mRootActivityContainer = root;
        this.mService = root.mService;
        this.mDisplayId = display.getDisplayId();
        this.mDisplay = display;
        updateBounds();
    }

    protected DisplayContent createDisplayContent() {
        return this.mService.mWindowManager.mRoot.createDisplayContent(this.mDisplay, this);
    }

    private void updateBounds() {
        this.mDisplay.getRealSize(this.mTmpDisplaySize);
        setBounds(0, 0, this.mTmpDisplaySize.x, this.mTmpDisplaySize.y);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDisplayChanged() {
        ActivityTaskManagerInternal.SleepToken sleepToken;
        int displayId = this.mDisplay.getDisplayId();
        if (displayId != 0) {
            int displayState = this.mDisplay.getState();
            if (displayState == 1 && this.mOffToken == null) {
                this.mOffToken = this.mService.acquireSleepToken("Display-off", displayId);
            } else if (displayState == 2 && (sleepToken = this.mOffToken) != null) {
                sleepToken.release();
                this.mOffToken = null;
            }
        }
        updateBounds();
        DisplayContent displayContent = this.mDisplayContent;
        if (displayContent != null) {
            displayContent.updateDisplayInfo();
            this.mService.mWindowManager.requestTraversal();
        }
    }

    @Override // com.android.server.wm.WindowContainerListener
    public void onInitializeOverrideConfiguration(Configuration config) {
        getRequestedOverrideConfiguration().updateFrom(config);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addChild(ActivityStack stack, int position) {
        if (position == Integer.MIN_VALUE) {
            position = 0;
        } else if (position == POSITION_TOP) {
            position = this.mStacks.size();
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityTaskManager", "addChild: attaching " + stack + " to displayId=" + this.mDisplayId + " position=" + position);
        }
        addStackReferenceIfNeeded(stack);
        positionChildAt(stack, position);
        this.mService.updateSleepIfNeededLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeChild(ActivityStack stack) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityTaskManager", "removeChild: detaching " + stack + " from displayId=" + this.mDisplayId);
        }
        this.mStacks.remove(stack);
        if (this.mPreferredTopFocusableStack == stack) {
            this.mPreferredTopFocusableStack = null;
        }
        removeStackReferenceIfNeeded(stack);
        releaseSelfIfNeeded();
        this.mService.updateSleepIfNeededLocked();
        onStackOrderChanged(stack);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtTop(ActivityStack stack, boolean includingParents) {
        positionChildAtTop(stack, includingParents, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtTop(ActivityStack stack, boolean includingParents, String updateLastFocusedStackReason) {
        positionChildAt(stack, this.mStacks.size(), includingParents, updateLastFocusedStackReason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtBottom(ActivityStack stack) {
        positionChildAtBottom(stack, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtBottom(ActivityStack stack, String updateLastFocusedStackReason) {
        positionChildAt(stack, 0, false, updateLastFocusedStackReason);
    }

    private void positionChildAt(ActivityStack stack, int position) {
        positionChildAt(stack, position, false, null);
    }

    private void positionChildAt(ActivityStack stack, int position, boolean includingParents, String updateLastFocusedStackReason) {
        ActivityStack prevFocusedStack;
        DisplayContent displayContent;
        ActivityStack currentFocusedStack;
        if (updateLastFocusedStackReason == null) {
            prevFocusedStack = null;
        } else {
            prevFocusedStack = getFocusedStack();
        }
        boolean wasContained = this.mStacks.remove(stack);
        if (this.mSingleTaskInstance && getChildCount() > 0) {
            throw new IllegalStateException("positionChildAt: Can only have one child on display=" + this);
        }
        int insertPosition = getTopInsertPosition(stack, position);
        this.mStacks.add(insertPosition, stack);
        if (wasContained && position >= this.mStacks.size() - 1 && stack.isFocusableAndVisible()) {
            this.mPreferredTopFocusableStack = stack;
        } else if (this.mPreferredTopFocusableStack == stack) {
            this.mPreferredTopFocusableStack = null;
        }
        if (updateLastFocusedStackReason != null && (currentFocusedStack = getFocusedStack()) != prevFocusedStack) {
            this.mLastFocusedStack = prevFocusedStack;
            int i = this.mRootActivityContainer.mCurrentUser;
            int i2 = this.mDisplayId;
            int stackId = currentFocusedStack == null ? -1 : currentFocusedStack.getStackId();
            ActivityStack activityStack = this.mLastFocusedStack;
            EventLogTags.writeAmFocusedStack(i, i2, stackId, activityStack != null ? activityStack.getStackId() : -1, updateLastFocusedStackReason);
        }
        if (stack.getTaskStack() != null && (displayContent = this.mDisplayContent) != null) {
            displayContent.positionStackAt(insertPosition, stack.getTaskStack(), includingParents);
        }
        if (!wasContained) {
            stack.setParent(this);
        }
        onStackOrderChanged(stack);
    }

    private int getTopInsertPosition(ActivityStack stack, int candidatePosition) {
        int position = this.mStacks.size();
        if (stack.inPinnedWindowingMode()) {
            return Math.min(position, candidatePosition);
        }
        while (position > 0) {
            ActivityStack targetStack = this.mStacks.get(position - 1);
            if (!targetStack.isAlwaysOnTop() || (stack.isAlwaysOnTop() && !targetStack.inPinnedWindowingMode())) {
                break;
            }
            position--;
        }
        return Math.min(position, candidatePosition);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getStack(int stackId) {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            T t = (T) this.mStacks.get(i);
            if (t.mStackId == stackId) {
                return t;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        if (activityType == 2) {
            return (T) this.mHomeStack;
        }
        if (activityType == 3) {
            return (T) this.mRecentsStack;
        }
        if (windowingMode == 2) {
            return (T) this.mPinnedStack;
        }
        if (windowingMode == 3) {
            return (T) this.mSplitScreenPrimaryStack;
        }
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            T t = (T) this.mStacks.get(i);
            if (t.isCompatible(windowingMode, activityType)) {
                return t;
            }
        }
        return null;
    }

    private boolean alwaysCreateStack(int windowingMode, int activityType) {
        return activityType == 1 && (windowingMode == 1 || windowingMode == 5 || windowingMode == 4);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getOrCreateStack(int windowingMode, int activityType, boolean onTop) {
        T stack;
        if (!alwaysCreateStack(windowingMode, activityType) && (stack = (T) getStack(windowingMode, activityType)) != null) {
            return stack;
        }
        return (T) createStack(windowingMode, activityType, onTop);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getOrCreateStack(ActivityRecord r, ActivityOptions options, TaskRecord candidateTask, int activityType, boolean onTop) {
        int windowingMode = options != null ? options.getLaunchWindowingMode() : 0;
        return (T) getOrCreateStack(validateWindowingMode(windowingMode, r, candidateTask, activityType), activityType, onTop);
    }

    @VisibleForTesting
    int getNextStackId() {
        int i = sNextFreeStackId;
        sNextFreeStackId = i + 1;
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T createStack(int windowingMode, int activityType, boolean onTop) {
        ActivityStack stack;
        if (this.mSingleTaskInstance && getChildCount() > 0) {
            return (T) this.mRootActivityContainer.getDefaultDisplay().createStack(windowingMode, activityType, onTop);
        }
        if (activityType == 0) {
            activityType = 1;
        }
        if (activityType != 1 && (stack = getStack(0, activityType)) != null) {
            throw new IllegalArgumentException("Stack=" + stack + " of activityType=" + activityType + " already on display=" + this + ". Can't have multiple.");
        } else if (!isWindowingModeSupported(windowingMode, this.mService.mSupportsMultiWindow, this.mService.mSupportsSplitScreenMultiWindow, this.mService.mSupportsFreeformWindowManagement, this.mService.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode=" + windowingMode);
        } else {
            int stackId = getNextStackId();
            return (T) createStackUnchecked(windowingMode, activityType, stackId, onTop);
        }
    }

    @VisibleForTesting
    <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType, int stackId, boolean onTop) {
        if (windowingMode == 2 && activityType != 1) {
            throw new IllegalArgumentException("Stack with windowing mode cannot with non standard activity type.");
        }
        return (T) new ActivityStack(this, stackId, this.mRootActivityContainer.mStackSupervisor, windowingMode, activityType, onTop);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getFocusedStack() {
        ActivityStack activityStack = this.mPreferredTopFocusableStack;
        if (activityStack != null) {
            return activityStack;
        }
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            ActivityStack stack = this.mStacks.get(i);
            if (stack.isFocusableAndVisible()) {
                return stack;
            }
        }
        return null;
    }

    ActivityStack getNextFocusableStack() {
        return getNextFocusableStack(null, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x003d, code lost:
        if (r3.inSplitScreenSecondaryWindowingMode() == false) goto L25;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x003f, code lost:
        return r1;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public com.android.server.wm.ActivityStack getNextFocusableStack(com.android.server.wm.ActivityStack r6, boolean r7) {
        /*
            r5 = this;
            if (r6 == 0) goto L7
            int r0 = r6.getWindowingMode()
            goto L8
        L7:
            r0 = 0
        L8:
            r1 = 0
            java.util.ArrayList<com.android.server.wm.ActivityStack> r2 = r5.mStacks
            int r2 = r2.size()
            int r2 = r2 + (-1)
        L11:
            if (r2 < 0) goto L41
            java.util.ArrayList<com.android.server.wm.ActivityStack> r3 = r5.mStacks
            java.lang.Object r3 = r3.get(r2)
            com.android.server.wm.ActivityStack r3 = (com.android.server.wm.ActivityStack) r3
            if (r7 == 0) goto L20
            if (r3 != r6) goto L20
            goto L34
        L20:
            boolean r4 = r3.isFocusableAndVisible()
            if (r4 != 0) goto L27
            goto L34
        L27:
            r4 = 4
            if (r0 != r4) goto L37
            if (r1 != 0) goto L37
            boolean r4 = r3.inSplitScreenPrimaryWindowingMode()
            if (r4 == 0) goto L37
            r1 = r3
        L34:
            int r2 = r2 + (-1)
            goto L11
        L37:
            if (r1 == 0) goto L40
            boolean r4 = r3.inSplitScreenSecondaryWindowingMode()
            if (r4 == 0) goto L40
            return r1
        L40:
            return r3
        L41:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityDisplay.getNextFocusableStack(com.android.server.wm.ActivityStack, boolean):com.android.server.wm.ActivityStack");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getResumedActivity() {
        ActivityStack focusedStack = getFocusedStack();
        if (focusedStack == null) {
            return null;
        }
        ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            ActivityRecord resumedActivity2 = focusedStack.mPausingActivity;
            if (resumedActivity2 == null || resumedActivity2.app == null) {
                return focusedStack.topRunningActivityLocked(true);
            }
            return resumedActivity2;
        }
        return resumedActivity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getLastFocusedStack() {
        return this.mLastFocusedStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResumedActivitiesComplete() {
        for (int stackNdx = this.mStacks.size() - 1; stackNdx >= 0; stackNdx--) {
            ActivityRecord r = this.mStacks.get(stackNdx).getResumedActivity();
            if (r != null && !r.isState(ActivityStack.ActivityState.RESUMED)) {
                return false;
            }
        }
        ActivityStack currentFocusedStack = getFocusedStack();
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityTaskManager", "allResumedActivitiesComplete: mLastFocusedStack changing from=" + this.mLastFocusedStack + " to=" + currentFocusedStack);
        }
        this.mLastFocusedStack = currentFocusedStack;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming, boolean dontWait) {
        boolean someActivityPaused = false;
        for (int stackNdx = this.mStacks.size() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = this.mStacks.get(stackNdx);
            ActivityRecord resumedActivity = stack.getResumedActivity();
            if (resumedActivity != null && (stack.getVisibility(resuming) != 0 || !stack.isFocusable())) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityTaskManager", "pauseBackStacks: stack=" + stack + " mResumedActivity=" + resumedActivity);
                }
                someActivityPaused |= stack.startPausingLocked(userLeaving, false, resuming, dontWait);
            }
        }
        return someActivityPaused;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskLocked(ActivityRecord r, boolean isPreferredDisplay, RootActivityContainer.FindTaskResult result) {
        this.mTmpFindTaskResult.clear();
        for (int stackNdx = getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = getChildAt(stackNdx);
            if (!r.hasCompatibleActivityType(stack)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Skipping stack: (mismatch activity/stack) " + stack);
                }
            } else {
                stack.findTaskLocked(r, this.mTmpFindTaskResult);
                if (this.mTmpFindTaskResult.mRecord == null) {
                    continue;
                } else if (this.mTmpFindTaskResult.mIdealMatch) {
                    result.setTo(this.mTmpFindTaskResult);
                    return;
                } else if (isPreferredDisplay) {
                    result.setTo(this.mTmpFindTaskResult);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }
        ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = windowingModes.length - 1; j >= 0; j--) {
            int windowingMode = windowingModes[j];
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack stack = this.mStacks.get(i);
                if (stack.isActivityTypeStandardOrUndefined() && stack.getWindowingMode() == windowingMode) {
                    stacks.add(stack);
                }
            }
        }
        int j2 = stacks.size();
        for (int i2 = j2 - 1; i2 >= 0; i2--) {
            this.mRootActivityContainer.mStackSupervisor.removeStack(stacks.get(i2));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }
        ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = activityTypes.length - 1; j >= 0; j--) {
            int activityType = activityTypes[j];
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack stack = this.mStacks.get(i);
                if (stack.getActivityType() == activityType) {
                    stacks.add(stack);
                }
            }
        }
        int j2 = stacks.size();
        for (int i2 = j2 - 1; i2 >= 0; i2--) {
            this.mRootActivityContainer.mStackSupervisor.removeStack(stacks.get(i2));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStackWindowingModeChanged(ActivityStack stack) {
        removeStackReferenceIfNeeded(stack);
        addStackReferenceIfNeeded(stack);
    }

    private void addStackReferenceIfNeeded(ActivityStack stack) {
        int activityType = stack.getActivityType();
        int windowingMode = stack.getWindowingMode();
        if (activityType == 2) {
            ActivityStack activityStack = this.mHomeStack;
            if (activityStack != null && activityStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack=" + this.mHomeStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mHomeStack = stack;
        } else if (activityType == 3) {
            ActivityStack activityStack2 = this.mRecentsStack;
            if (activityStack2 != null && activityStack2 != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: recents stack=" + this.mRecentsStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mRecentsStack = stack;
        }
        if (windowingMode == 2) {
            ActivityStack activityStack3 = this.mPinnedStack;
            if (activityStack3 != null && activityStack3 != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack=" + this.mPinnedStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mPinnedStack = stack;
        } else if (windowingMode == 3) {
            ActivityStack activityStack4 = this.mSplitScreenPrimaryStack;
            if (activityStack4 != null && activityStack4 != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: split-screen-primary stack=" + this.mSplitScreenPrimaryStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mSplitScreenPrimaryStack = stack;
            onSplitScreenModeActivated();
        }
    }

    private void removeStackReferenceIfNeeded(ActivityStack stack) {
        if (stack == this.mHomeStack) {
            this.mHomeStack = null;
        } else if (stack == this.mRecentsStack) {
            this.mRecentsStack = null;
        } else if (stack == this.mPinnedStack) {
            this.mPinnedStack = null;
        } else if (stack == this.mSplitScreenPrimaryStack) {
            this.mSplitScreenPrimaryStack = null;
            onSplitScreenModeDismissed();
        }
    }

    private void onSplitScreenModeDismissed() {
        ActivityStack activityStack;
        this.mRootActivityContainer.mWindowManager.deferSurfaceLayout();
        try {
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack otherStack = this.mStacks.get(i);
                if (otherStack.inSplitScreenSecondaryWindowingMode()) {
                    otherStack.setWindowingMode(0, false, false, false, true, false);
                }
            }
        } finally {
            ActivityStack topFullscreenStack = getTopStackInWindowingMode(1);
            if (topFullscreenStack != null && (activityStack = this.mHomeStack) != null && !isTopStack(activityStack)) {
                this.mHomeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
            }
            this.mRootActivityContainer.mWindowManager.continueSurfaceLayout();
        }
    }

    private void onSplitScreenModeActivated() {
        this.mRootActivityContainer.mWindowManager.deferSurfaceLayout();
        try {
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack otherStack = this.mStacks.get(i);
                if (otherStack != this.mSplitScreenPrimaryStack && otherStack.affectedBySplitScreenResize()) {
                    otherStack.setWindowingMode(4, false, false, true, true, false);
                }
            }
        } finally {
            this.mRootActivityContainer.mWindowManager.continueSurfaceLayout();
        }
    }

    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow, boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip, int activityType) {
        if (windowingMode == 0 || windowingMode == 1) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }
        int displayWindowingMode = getWindowingMode();
        if (windowingMode == 3 || windowingMode == 4) {
            if (supportsSplitScreen && WindowConfiguration.supportSplitScreenWindowingMode(activityType) && displayWindowingMode != 5) {
                return true;
            }
            return false;
        } else if (!supportsFreeform && windowingMode == 5) {
            return false;
        } else {
            if (supportsPip || windowingMode != 2) {
                return true;
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int resolveWindowingMode(ActivityRecord r, ActivityOptions options, TaskRecord task, int activityType) {
        int windowingMode = options != null ? options.getLaunchWindowingMode() : 0;
        if (windowingMode == 0) {
            if (task != null) {
                windowingMode = task.getWindowingMode();
            }
            if (windowingMode == 0 && r != null) {
                windowingMode = r.getWindowingMode();
            }
            if (windowingMode == 0) {
                windowingMode = getWindowingMode();
            }
        }
        int windowingMode2 = validateWindowingMode(windowingMode, r, task, activityType);
        if (windowingMode2 != 0) {
            return windowingMode2;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int validateWindowingMode(int windowingMode, ActivityRecord r, TaskRecord task, int activityType) {
        boolean supportsMultiWindow;
        boolean supportsSplitScreen;
        boolean supportsFreeform;
        boolean supportsPip;
        boolean inSplitScreenMode;
        int windowingMode2;
        boolean supportsMultiWindow2 = this.mService.mSupportsMultiWindow;
        boolean supportsSplitScreen2 = this.mService.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform2 = this.mService.mSupportsFreeformWindowManagement;
        boolean supportsPip2 = this.mService.mSupportsPictureInPicture;
        if (supportsMultiWindow2) {
            if (task != null) {
                boolean supportsMultiWindow3 = task.isResizeable();
                boolean supportsSplitScreen3 = task.supportsSplitScreenWindowingMode();
                supportsMultiWindow = supportsMultiWindow3;
                supportsSplitScreen = supportsSplitScreen3;
                supportsFreeform = supportsFreeform2;
                supportsPip = supportsPip2;
            } else if (r != null) {
                boolean supportsMultiWindow4 = r.isResizeable();
                boolean supportsSplitScreen4 = r.supportsSplitScreenWindowingMode();
                supportsMultiWindow = supportsMultiWindow4;
                supportsSplitScreen = supportsSplitScreen4;
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
            }
            inSplitScreenMode = hasSplitScreenPrimaryStack();
            if (inSplitScreenMode && windowingMode == 4) {
                windowingMode2 = 0;
            } else if (!inSplitScreenMode && ((windowingMode == 1 || windowingMode == 0) && supportsSplitScreen)) {
                windowingMode2 = 4;
            } else {
                windowingMode2 = windowingMode;
            }
            if (windowingMode2 == 0 && isWindowingModeSupported(windowingMode2, supportsMultiWindow, supportsSplitScreen, supportsFreeform, supportsPip, activityType)) {
                return windowingMode2;
            }
        }
        supportsMultiWindow = supportsMultiWindow2;
        supportsSplitScreen = supportsSplitScreen2;
        supportsFreeform = supportsFreeform2;
        supportsPip = supportsPip2;
        inSplitScreenMode = hasSplitScreenPrimaryStack();
        if (inSplitScreenMode) {
        }
        if (!inSplitScreenMode) {
        }
        windowingMode2 = windowingMode;
        return windowingMode2 == 0 ? 0 : 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getTopStack() {
        if (this.mStacks.isEmpty()) {
            return null;
        }
        ArrayList<ActivityStack> arrayList = this.mStacks;
        return arrayList.get(arrayList.size() - 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopStack(ActivityStack stack) {
        return stack == getTopStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopNotPinnedStack(ActivityStack stack) {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            ActivityStack current = this.mStacks.get(i);
            if (!current.inPinnedWindowingMode()) {
                return current == stack;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getTopStackInWindowingMode(int windowingMode) {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            ActivityStack current = this.mStacks.get(i);
            if (windowingMode == current.getWindowingMode()) {
                return current;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivity() {
        return topRunningActivity(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivity(boolean considerKeyguardState) {
        ActivityRecord topRunning = null;
        ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            topRunning = focusedStack.topRunningActivityLocked();
        }
        if (topRunning == null) {
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack stack = this.mStacks.get(i);
                if (stack != focusedStack && stack.isFocusable() && (topRunning = stack.topRunningActivityLocked()) != null) {
                    break;
                }
            }
        }
        if (topRunning != null && considerKeyguardState && this.mRootActivityContainer.mStackSupervisor.getKeyguardController().isKeyguardLocked() && !topRunning.canShowWhenLocked()) {
            return null;
        }
        return topRunning;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getIndexOf(ActivityStack stack) {
        return this.mStacks.indexOf(stack);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        DisplayContent displayContent;
        int currRotation = getRequestedOverrideConfiguration().windowConfiguration.getRotation();
        if (currRotation != -1 && currRotation != overrideConfiguration.windowConfiguration.getRotation() && (displayContent = this.mDisplayContent) != null) {
            displayContent.applyRotationLocked(currRotation, overrideConfiguration.windowConfiguration.getRotation());
        }
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        if (this.mDisplayContent != null) {
            this.mService.mWindowManager.setNewDisplayOverrideConfiguration(overrideConfiguration, this.mDisplayContent);
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        DisplayContent displayContent = this.mDisplayContent;
        if (displayContent != null) {
            displayContent.preOnConfigurationChanged();
        }
        super.onConfigurationChanged(newParentConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskPackagesUpdated() {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            this.mStacks.get(i).onLockTaskPackagesUpdated();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onExitingSplitScreenMode() {
        this.mSplitScreenPrimaryStack = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleActivitySizeCompatModeIfNeeded(ActivityRecord r) {
        if (!r.isState(ActivityStack.ActivityState.RESUMED) || r.getWindowingMode() != 1) {
            return;
        }
        if (!r.inSizeCompatMode()) {
            if (this.mLastCompatModeActivity != null) {
                this.mService.getTaskChangeNotificationController().notifySizeCompatModeActivityChanged(this.mDisplayId, null);
            }
            this.mLastCompatModeActivity = null;
        } else if (this.mLastCompatModeActivity == r) {
        } else {
            this.mLastCompatModeActivity = r;
            this.mService.getTaskChangeNotificationController().notifySizeCompatModeActivityChanged(this.mDisplayId, r.appToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getSplitScreenPrimaryStack() {
        return this.mSplitScreenPrimaryStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSplitScreenPrimaryStack() {
        return this.mSplitScreenPrimaryStack != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getPinnedStack() {
        return this.mPinnedStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasPinnedStack() {
        return this.mPinnedStack != null;
    }

    public String toString() {
        return "ActivityDisplay={" + this.mDisplayId + " numStacks=" + this.mStacks.size() + "}";
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mStacks.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.android.server.wm.ConfigurationContainer
    public ActivityStack getChildAt(int index) {
        int size = this.mStacks.size();
        return (index < size || size <= 0) ? this.mStacks.get(index) : this.mStacks.get(size - 1);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return this.mRootActivityContainer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPrivate() {
        return (this.mDisplay.getFlags() & 4) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUidPresent(int uid) {
        Iterator<ActivityStack> it = this.mStacks.iterator();
        while (it.hasNext()) {
            ActivityStack stack = it.next();
            if (stack.isUidPresent(uid)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRemoved() {
        return this.mRemoved;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void remove() {
        int windowingMode;
        boolean destroyContentOnRemoval = shouldDestroyContentOnRemove();
        ActivityStack lastReparentedStack = null;
        this.mPreferredTopFocusableStack = null;
        ActivityDisplay toDisplay = this.mRootActivityContainer.getDefaultDisplay();
        this.mRootActivityContainer.mStackSupervisor.beginDeferResume();
        try {
            int numStacks = this.mStacks.size();
            int stackNdx = 0;
            while (stackNdx < numStacks) {
                ActivityStack stack = this.mStacks.get(stackNdx);
                if (!destroyContentOnRemoval && stack.isActivityTypeStandardOrUndefined()) {
                    if (toDisplay.hasSplitScreenPrimaryStack()) {
                        windowingMode = 4;
                    } else {
                        windowingMode = 0;
                    }
                    stack.reparent(toDisplay, true, true);
                    stack.setWindowingMode(windowingMode);
                    lastReparentedStack = stack;
                    int stackNdx2 = stackNdx - (numStacks - this.mStacks.size());
                    numStacks = this.mStacks.size();
                    stackNdx = stackNdx2 + 1;
                }
                stack.finishAllActivitiesLocked(true);
                int stackNdx22 = stackNdx - (numStacks - this.mStacks.size());
                numStacks = this.mStacks.size();
                stackNdx = stackNdx22 + 1;
            }
            this.mRootActivityContainer.mStackSupervisor.endDeferResume();
            this.mRemoved = true;
            if (lastReparentedStack != null) {
                lastReparentedStack.postReparent();
            }
            releaseSelfIfNeeded();
            if (!this.mAllSleepTokens.isEmpty()) {
                this.mRootActivityContainer.mSleepTokens.removeAll(this.mAllSleepTokens);
                this.mAllSleepTokens.clear();
                this.mService.updateSleepIfNeededLocked();
            }
        } catch (Throwable th) {
            this.mRootActivityContainer.mStackSupervisor.endDeferResume();
            throw th;
        }
    }

    private void releaseSelfIfNeeded() {
        if (!this.mRemoved || this.mDisplayContent == null) {
            return;
        }
        ActivityStack stack = this.mStacks.size() == 1 ? this.mStacks.get(0) : null;
        if (stack != null && stack.isActivityTypeHome() && stack.getAllTasks().isEmpty()) {
            stack.remove();
        } else if (this.mStacks.isEmpty()) {
            this.mDisplayContent.removeIfPossible();
            this.mDisplayContent = null;
            this.mRootActivityContainer.removeChild(this);
            this.mRootActivityContainer.mStackSupervisor.getKeyguardController().onDisplayRemoved(this.mDisplayId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntArray getPresentUIDs() {
        this.mDisplayAccessUIDs.clear();
        Iterator<ActivityStack> it = this.mStacks.iterator();
        while (it.hasNext()) {
            ActivityStack stack = it.next();
            stack.getPresentUIDs(this.mDisplayAccessUIDs);
        }
        return this.mDisplayAccessUIDs;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean supportsSystemDecorations() {
        return this.mDisplayContent.supportsSystemDecorations();
    }

    @VisibleForTesting
    boolean shouldDestroyContentOnRemove() {
        return this.mDisplay.getRemoveMode() == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleep() {
        return (this.mStacks.isEmpty() || !this.mAllSleepTokens.isEmpty()) && this.mService.mRunningVoice == null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusedApp(ActivityRecord r, boolean moveFocusNow) {
        AppWindowToken newFocus;
        if (this.mDisplayContent == null) {
            return;
        }
        IBinder token = r.appToken;
        if (token == null) {
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v("WindowManager", "Clearing focused app, displayId=" + this.mDisplayId);
            }
            newFocus = null;
        } else {
            AppWindowToken newFocus2 = this.mService.mWindowManager.mRoot.getAppWindowToken(token);
            if (newFocus2 == null) {
                Slog.w("WindowManager", "Attempted to set focus to non-existing app token: " + token + ", displayId=" + this.mDisplayId);
            }
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v("WindowManager", "Set focused app to: " + newFocus2 + " moveFocusNow=" + moveFocusNow + " displayId=" + this.mDisplayId);
            }
            newFocus = newFocus2;
        }
        boolean changed = this.mDisplayContent.setFocusedApp(newFocus);
        if (moveFocusNow && changed) {
            this.mService.mWindowManager.updateFocusedWindowLocked(0, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getStackAbove(ActivityStack stack) {
        int stackIndex = this.mStacks.indexOf(stack) + 1;
        if (stackIndex < this.mStacks.size()) {
            return this.mStacks.get(stackIndex);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveStackBehindBottomMostVisibleStack(ActivityStack stack) {
        if (stack.shouldBeVisible(null)) {
            return;
        }
        positionChildAtBottom(stack);
        int numStacks = this.mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            ActivityStack s = this.mStacks.get(stackNdx);
            if (s != stack) {
                int winMode = s.getWindowingMode();
                boolean isValidWindowingMode = true;
                if (winMode != 1 && winMode != 4) {
                    isValidWindowingMode = false;
                }
                if (s.shouldBeVisible(null) && isValidWindowingMode) {
                    positionChildAt(stack, Math.max(0, stackNdx - 1));
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveStackBehindStack(ActivityStack stack, ActivityStack behindStack) {
        if (behindStack == null || behindStack == stack) {
            return;
        }
        int stackIndex = this.mStacks.indexOf(stack);
        int behindStackIndex = this.mStacks.indexOf(behindStack);
        int insertIndex = stackIndex <= behindStackIndex ? behindStackIndex - 1 : behindStackIndex;
        positionChildAt(stack, Math.max(0, insertIndex));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureActivitiesVisible(ActivityRecord starting, int configChanges, boolean preserveWindows, boolean notifyClients) {
        for (int stackNdx = getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = getChildAt(stackNdx);
            stack.ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows, notifyClients);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveHomeStackToFront(String reason) {
        ActivityStack activityStack = this.mHomeStack;
        if (activityStack != null) {
            activityStack.moveToFront(reason);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveHomeActivityToTop(String reason) {
        ActivityRecord top = getHomeActivity();
        if (top == null) {
            moveHomeStackToFront(reason);
        } else {
            top.moveFocusableActivityToTop(reason);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getHomeStack() {
        return this.mHomeStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(this.mRootActivityContainer.mCurrentUser);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getHomeActivityForUser(int userId) {
        ActivityStack activityStack = this.mHomeStack;
        if (activityStack == null) {
            return null;
        }
        ArrayList<TaskRecord> tasks = activityStack.getAllTasks();
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = tasks.get(taskNdx);
            if (task.isActivityTypeHome()) {
                ArrayList<ActivityRecord> activities = task.mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                    ActivityRecord r = activities.get(activityNdx);
                    if (r.isActivityTypeHome() && (userId == -1 || r.mUserId == userId)) {
                        return r;
                    }
                }
                continue;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSleeping() {
        return this.mSleeping;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setIsSleeping(boolean asleep) {
        this.mSleeping = asleep;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerStackOrderChangedListener(OnStackOrderChangedListener listener) {
        if (!this.mStackOrderChangedCallbacks.contains(listener)) {
            this.mStackOrderChangedCallbacks.add(listener);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterStackOrderChangedListener(OnStackOrderChangedListener listener) {
        this.mStackOrderChangedCallbacks.remove(listener);
    }

    private void onStackOrderChanged(ActivityStack stack) {
        for (int i = this.mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            this.mStackOrderChangedCallbacks.get(i).onStackOrderChanged(stack);
        }
    }

    public void deferUpdateImeTarget() {
        DisplayContent displayContent = this.mDisplayContent;
        if (displayContent != null) {
            displayContent.deferUpdateImeTarget();
        }
    }

    public void continueUpdateImeTarget() {
        DisplayContent displayContent = this.mDisplayContent;
        if (displayContent != null) {
            displayContent.continueUpdateImeTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayToSingleTaskInstance() {
        int childCount = getChildCount();
        if (childCount > 1) {
            throw new IllegalArgumentException("Display already has multiple stacks. display=" + this);
        }
        if (childCount > 0) {
            ActivityStack stack = getChildAt(0);
            if (stack.getChildCount() > 1) {
                throw new IllegalArgumentException("Display stack already has multiple tasks. display=" + this + " stack=" + stack);
            }
        }
        this.mSingleTaskInstance = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSingleTaskInstance() {
        return this.mSingleTaskInstance;
    }

    public void dump(PrintWriter pw, String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append("displayId=");
        sb.append(this.mDisplayId);
        sb.append(" stacks=");
        sb.append(this.mStacks.size());
        sb.append(this.mSingleTaskInstance ? " mSingleTaskInstance" : "");
        pw.println(sb.toString());
        String myPrefix = prefix + " ";
        if (this.mHomeStack != null) {
            pw.println(myPrefix + "mHomeStack=" + this.mHomeStack);
        }
        if (this.mRecentsStack != null) {
            pw.println(myPrefix + "mRecentsStack=" + this.mRecentsStack);
        }
        if (this.mPinnedStack != null) {
            pw.println(myPrefix + "mPinnedStack=" + this.mPinnedStack);
        }
        if (this.mSplitScreenPrimaryStack != null) {
            pw.println(myPrefix + "mSplitScreenPrimaryStack=" + this.mSplitScreenPrimaryStack);
        }
        if (this.mPreferredTopFocusableStack != null) {
            pw.println(myPrefix + "mPreferredTopFocusableStack=" + this.mPreferredTopFocusableStack);
        }
        if (this.mLastFocusedStack != null) {
            pw.println(myPrefix + "mLastFocusedStack=" + this.mLastFocusedStack);
        }
    }

    public void dumpStacks(PrintWriter pw) {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            pw.print(this.mStacks.get(i).mStackId);
            if (i > 0) {
                pw.print(",");
            }
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        proto.write(1120986464258L, this.mDisplayId);
        proto.write(1133871366150L, this.mSingleTaskInstance);
        ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            proto.write(1120986464260L, focusedStack.mStackId);
            ActivityRecord focusedActivity = focusedStack.getDisplay().getResumedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, 1146756268037L);
            }
        } else {
            proto.write(1120986464260L, -1);
        }
        for (int stackNdx = this.mStacks.size() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = this.mStacks.get(stackNdx);
            stack.writeToProto(proto, 2246267895811L, logLevel);
        }
        proto.end(token);
    }

    public ArrayList<ActivityStack> getStacks(int windowingType) {
        ArrayList<ActivityStack> stacks = new ArrayList<>();
        ArrayList<ActivityStack> arrayList = this.mStacks;
        if (arrayList != null) {
            Iterator<ActivityStack> it = arrayList.iterator();
            while (it.hasNext()) {
                ActivityStack stack = it.next();
                if (stack != null && stack.getWindowingMode() == 5) {
                    stacks.add(stack);
                }
            }
        }
        return stacks;
    }
}
