package com.android.server.am;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.DisplayWindowController;
import com.android.server.wm.WindowContainerListener;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ActivityDisplay extends ConfigurationContainer<ActivityStack> implements WindowContainerListener {
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;
    static final int POSITION_TOP = Integer.MAX_VALUE;
    private static final String TAG = "ActivityManager";
    private static final String TAG_STACK = "ActivityManager";
    private static int sNextFreeStackId = 0;
    final ArrayList<ActivityManagerInternal.SleepToken> mAllSleepTokens;
    Display mDisplay;
    private IntArray mDisplayAccessUIDs;
    int mDisplayId;
    private ActivityStack mHomeStack;
    ActivityManagerInternal.SleepToken mOffToken;
    private ActivityStack mPinnedStack;
    private ActivityStack mRecentsStack;
    private boolean mSleeping;
    private ActivityStack mSplitScreenPrimaryStack;
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks;
    private final ArrayList<ActivityStack> mStacks;
    private ActivityStackSupervisor mSupervisor;
    private Point mTmpDisplaySize;
    private DisplayWindowController mWindowContainerController;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface OnStackOrderChangedListener {
        void onStackOrderChanged();
    }

    @VisibleForTesting
    ActivityDisplay(ActivityStackSupervisor supervisor, int displayId) {
        this(supervisor, supervisor.mDisplayManager.getDisplay(displayId));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
        this.mStacks = new ArrayList<>();
        this.mStackOrderChangedCallbacks = new ArrayList<>();
        this.mDisplayAccessUIDs = new IntArray();
        this.mAllSleepTokens = new ArrayList<>();
        this.mHomeStack = null;
        this.mRecentsStack = null;
        this.mPinnedStack = null;
        this.mSplitScreenPrimaryStack = null;
        this.mTmpDisplaySize = new Point();
        this.mSupervisor = supervisor;
        this.mDisplayId = display.getDisplayId();
        this.mDisplay = display;
        this.mWindowContainerController = createWindowContainerController();
        updateBounds();
    }

    protected DisplayWindowController createWindowContainerController() {
        return new DisplayWindowController(this.mDisplay, this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBounds() {
        this.mDisplay.getSize(this.mTmpDisplaySize);
        setBounds(0, 0, this.mTmpDisplaySize.x, this.mTmpDisplaySize.y);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addChild(ActivityStack stack, int position) {
        if (position == Integer.MIN_VALUE) {
            position = 0;
        } else if (position == POSITION_TOP) {
            position = this.mStacks.size();
        }
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityManager", "addChild: attaching " + stack + " to displayId=" + this.mDisplayId + " position=" + position);
        }
        addStackReferenceIfNeeded(stack);
        positionChildAt(stack, position);
        this.mSupervisor.mService.updateSleepIfNeededLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeChild(ActivityStack stack) {
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityManager", "removeChild: detaching " + stack + " from displayId=" + this.mDisplayId);
        }
        this.mStacks.remove(stack);
        removeStackReferenceIfNeeded(stack);
        this.mSupervisor.mService.updateSleepIfNeededLocked();
        onStackOrderChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtTop(ActivityStack stack) {
        int position = this.mStacks.size();
        this.mStacks.remove(stack);
        int insertPosition = getTopInsertPosition(stack, position);
        this.mStacks.add(insertPosition, stack);
        this.mWindowContainerController.positionChildAt(stack.getWindowContainerController(), POSITION_TOP);
        onStackOrderChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtBottom(ActivityStack stack) {
        positionChildAt(stack, 0);
    }

    private void positionChildAt(ActivityStack stack, int position) {
        this.mStacks.remove(stack);
        int insertPosition = getTopInsertPosition(stack, position);
        this.mStacks.add(insertPosition, stack);
        this.mWindowContainerController.positionChildAt(stack.getWindowContainerController(), insertPosition);
        onStackOrderChanged();
    }

    private int getTopInsertPosition(ActivityStack stack, int candidatePosition) {
        int position = this.mStacks.size();
        if (position > 0) {
            ActivityStack topStack = this.mStacks.get(position - 1);
            if (topStack.getWindowConfiguration().isAlwaysOnTop() && topStack != stack) {
                position--;
            }
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
        int windowingMode = resolveWindowingMode(r, options, candidateTask, activityType);
        return (T) getOrCreateStack(windowingMode, activityType, onTop);
    }

    private int getNextStackId() {
        int i = sNextFreeStackId;
        sNextFreeStackId = i + 1;
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T createStack(int windowingMode, int activityType, boolean onTop) {
        ActivityStack stack;
        if (activityType == 0) {
            activityType = 1;
        }
        if (activityType != 1 && (stack = getStack(0, activityType)) != null) {
            throw new IllegalArgumentException("Stack=" + stack + " of activityType=" + activityType + " already on display=" + this + ". Can't have multiple.");
        }
        ActivityManagerService service = this.mSupervisor.mService;
        if (!isWindowingModeSupported(windowingMode, service.mSupportsMultiWindow, service.mSupportsSplitScreenMultiWindow, service.mSupportsFreeformWindowManagement, service.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode=" + windowingMode);
        }
        if (windowingMode == 0 && (windowingMode = getWindowingMode()) == 0) {
            windowingMode = 1;
        }
        int stackId = getNextStackId();
        return (T) createStackUnchecked(windowingMode, activityType, stackId, onTop);
    }

    @VisibleForTesting
    <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType, int stackId, boolean onTop) {
        if (windowingMode == 2) {
            return new PinnedActivityStack(this, stackId, this.mSupervisor, onTop);
        }
        return (T) new ActivityStack(this, stackId, this.mSupervisor, windowingMode, activityType, onTop);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }
        for (int j = windowingModes.length - 1; j >= 0; j--) {
            int windowingMode = windowingModes[j];
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack stack = this.mStacks.get(i);
                if (stack.isActivityTypeStandardOrUndefined() && stack.getWindowingMode() == windowingMode) {
                    this.mSupervisor.removeStack(stack);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }
        for (int j = activityTypes.length - 1; j >= 0; j--) {
            int activityType = activityTypes[j];
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack stack = this.mStacks.get(i);
                if (stack.getActivityType() == activityType) {
                    this.mSupervisor.removeStack(stack);
                }
            }
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
            if (this.mHomeStack != null && this.mHomeStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack=" + this.mHomeStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mHomeStack = stack;
        } else if (activityType == 3) {
            if (this.mRecentsStack != null && this.mRecentsStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: recents stack=" + this.mRecentsStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mRecentsStack = stack;
        }
        if (windowingMode == 2) {
            if (this.mPinnedStack != null && this.mPinnedStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack=" + this.mPinnedStack + " already exist on display=" + this + " stack=" + stack);
            }
            this.mPinnedStack = stack;
        } else if (windowingMode == 3) {
            if (this.mSplitScreenPrimaryStack != null && this.mSplitScreenPrimaryStack != stack) {
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
        this.mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack otherStack = this.mStacks.get(i);
                if (otherStack.inSplitScreenSecondaryWindowingMode()) {
                    otherStack.setWindowingMode(1, false, false, false, true);
                }
            }
        } finally {
            ActivityStack topFullscreenStack = getTopStackInWindowingMode(1);
            if (topFullscreenStack != null && this.mHomeStack != null && !isTopStack(this.mHomeStack)) {
                this.mHomeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
            }
            this.mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    private void onSplitScreenModeActivated() {
        this.mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            for (int i = this.mStacks.size() - 1; i >= 0; i--) {
                ActivityStack otherStack = this.mStacks.get(i);
                if (otherStack != this.mSplitScreenPrimaryStack && otherStack.affectedBySplitScreenResize()) {
                    otherStack.setWindowingMode(4, false, false, true, true);
                }
            }
        } finally {
            this.mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow, boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip, int activityType) {
        if (windowingMode == 0 || windowingMode == 1) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }
        if (windowingMode == 3 || windowingMode == 4) {
            if (supportsSplitScreen && WindowConfiguration.supportSplitScreenWindowingMode(activityType)) {
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
        int i;
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
        ActivityManagerService service = this.mSupervisor.mService;
        boolean supportsMultiWindow = service.mSupportsMultiWindow;
        boolean supportsSplitScreen = service.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform = service.mSupportsFreeformWindowManagement;
        boolean supportsPip = service.mSupportsPictureInPicture;
        if (supportsMultiWindow) {
            if (task != null) {
                supportsMultiWindow = task.isResizeable();
                supportsSplitScreen = task.supportsSplitScreenWindowingMode();
            } else if (r != null) {
                supportsMultiWindow = r.isResizeable();
                supportsSplitScreen = r.supportsSplitScreenWindowingMode();
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
            }
        }
        boolean supportsMultiWindow2 = supportsMultiWindow;
        boolean supportsSplitScreen2 = supportsSplitScreen;
        boolean supportsFreeform2 = supportsFreeform;
        boolean supportsPip2 = supportsPip;
        boolean inSplitScreenMode = hasSplitScreenPrimaryStack();
        if (!inSplitScreenMode && windowingMode == 4) {
            windowingMode = 1;
        } else if (inSplitScreenMode && windowingMode == 1 && supportsSplitScreen2) {
            windowingMode = 4;
        }
        if (windowingMode != 0) {
            i = 1;
            if (isWindowingModeSupported(windowingMode, supportsMultiWindow2, supportsSplitScreen2, supportsFreeform2, supportsPip2, activityType)) {
                return windowingMode;
            }
        } else {
            i = 1;
        }
        int windowingMode2 = getWindowingMode();
        return windowingMode2 != 0 ? windowingMode2 : i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getTopStack() {
        if (this.mStacks.isEmpty()) {
            return null;
        }
        return this.mStacks.get(this.mStacks.size() - 1);
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
    public int getIndexOf(ActivityStack stack) {
        return this.mStacks.indexOf(stack);
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
    public ActivityStack getSplitScreenPrimaryStack() {
        return this.mSplitScreenPrimaryStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSplitScreenPrimaryStack() {
        return this.mSplitScreenPrimaryStack != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PinnedActivityStack getPinnedStack() {
        return (PinnedActivityStack) this.mPinnedStack;
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
        return this.mStacks.get(index);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return this.mSupervisor;
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
    public void remove() {
        boolean destroyContentOnRemoval = shouldDestroyContentOnRemove();
        while (getChildCount() > 0) {
            ActivityStack stack = getChildAt(0);
            if (!destroyContentOnRemoval) {
                this.mSupervisor.moveTasksToFullscreenStackLocked(stack, true);
            } else {
                stack.onOverrideConfigurationChanged(stack.getConfiguration());
                this.mSupervisor.moveStackToDisplayLocked(stack.mStackId, 0, false);
                stack.finishAllActivitiesLocked(true);
            }
        }
        this.mWindowContainerController.removeContainer();
        this.mWindowContainerController = null;
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

    private boolean shouldDestroyContentOnRemove() {
        return this.mDisplay.getRemoveMode() == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleep() {
        return (this.mStacks.isEmpty() || !this.mAllSleepTokens.isEmpty()) && this.mSupervisor.mService.mRunningVoice == null;
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

    private void onStackOrderChanged() {
        for (int i = this.mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            this.mStackOrderChangedCallbacks.get(i).onStackOrderChanged();
        }
    }

    public void deferUpdateImeTarget() {
        this.mWindowContainerController.deferUpdateImeTarget();
    }

    public void continueUpdateImeTarget() {
        this.mWindowContainerController.continueUpdateImeTarget();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "displayId=" + this.mDisplayId + " stacks=" + this.mStacks.size());
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append(" ");
        String myPrefix = sb.toString();
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
    }

    public void dumpStacks(PrintWriter pw) {
        for (int i = this.mStacks.size() - 1; i >= 0; i--) {
            pw.print(this.mStacks.get(i).mStackId);
            if (i > 0) {
                pw.print(",");
            }
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, false);
        proto.write(1120986464258L, this.mDisplayId);
        for (int stackNdx = this.mStacks.size() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = this.mStacks.get(stackNdx);
            stack.writeToProto(proto, 2246267895811L);
        }
        proto.end(token);
    }

    public ArrayList<ActivityStack> getStacks(int windowingType) {
        ArrayList<ActivityStack> stacks = new ArrayList<>();
        if (this.mStacks != null) {
            Iterator<ActivityStack> it = this.mStacks.iterator();
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
