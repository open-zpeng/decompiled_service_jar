package com.android.server.wm;

import android.app.ActivityManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import java.io.PrintWriter;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class Task extends WindowContainer<AppWindowToken> {
    static final String TAG = "WindowManager";
    private boolean mCanAffectSystemUiFlags;
    private boolean mDeferRemoval;
    private Dimmer mDimmer;
    private int mDragResizeMode;
    private boolean mDragResizing;
    final Rect mPreparedFrozenBounds;
    final Configuration mPreparedFrozenMergedConfig;
    private boolean mPreserveNonFloatingState;
    private int mResizeMode;
    private int mRotation;
    TaskStack mStack;
    private boolean mSupportsPictureInPicture;
    private ActivityManager.TaskDescription mTaskDescription;
    final int mTaskId;
    private final Rect mTempInsetBounds;
    private final Rect mTmpDimBoundsRect;
    private Rect mTmpRect;
    private Rect mTmpRect2;
    private Rect mTmpRect3;
    final int mUserId;

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task(int taskId, TaskStack stack, int userId, WindowManagerService service, int resizeMode, boolean supportsPictureInPicture, ActivityManager.TaskDescription taskDescription, TaskWindowContainerController controller) {
        super(service);
        this.mDeferRemoval = false;
        this.mPreparedFrozenBounds = new Rect();
        this.mPreparedFrozenMergedConfig = new Configuration();
        this.mTempInsetBounds = new Rect();
        this.mTmpRect = new Rect();
        this.mTmpRect2 = new Rect();
        this.mTmpRect3 = new Rect();
        this.mPreserveNonFloatingState = false;
        this.mDimmer = new Dimmer(this);
        this.mTmpDimBoundsRect = new Rect();
        this.mCanAffectSystemUiFlags = true;
        this.mTaskId = taskId;
        this.mStack = stack;
        this.mUserId = userId;
        this.mResizeMode = resizeMode;
        this.mSupportsPictureInPicture = supportsPictureInPicture;
        setController(controller);
        setBounds(getOverrideBounds());
        this.mTaskDescription = taskDescription;
        setOrientation(-2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getDisplayContent() {
        if (this.mStack != null) {
            return this.mStack.getDisplayContent();
        }
        return null;
    }

    private int getAdjustedAddPosition(int suggestedPosition) {
        int size = this.mChildren.size();
        if (suggestedPosition >= size) {
            return Math.min(size, suggestedPosition);
        }
        for (int pos = 0; pos < size && pos < suggestedPosition; pos++) {
            if (((AppWindowToken) this.mChildren.get(pos)).removed) {
                suggestedPosition++;
            }
        }
        int pos2 = Math.min(size, suggestedPosition);
        return pos2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void addChild(AppWindowToken wtoken, int position) {
        super.addChild((Task) wtoken, getAdjustedAddPosition(position));
        this.mDeferRemoval = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void positionChildAt(int position, AppWindowToken child, boolean includingParents) {
        super.positionChildAt(getAdjustedAddPosition(position), (int) child, includingParents);
        this.mDeferRemoval = false;
    }

    private boolean hasWindowsAlive() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            if (((AppWindowToken) this.mChildren.get(i)).hasWindowsAlive()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    boolean shouldDeferRemoval() {
        return hasWindowsAlive() && this.mStack.isSelfOrChildAnimating();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeIfPossible() {
        if (shouldDeferRemoval()) {
            if (WindowManagerDebugConfig.DEBUG_STACK) {
                Slog.i(TAG, "removeTask: deferring removing taskId=" + this.mTaskId);
            }
            this.mDeferRemoval = true;
            return;
        }
        removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeImmediately() {
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.i(TAG, "removeTask: removing taskId=" + this.mTaskId);
        }
        EventLog.writeEvent((int) EventLogTags.WM_TASK_REMOVED, Integer.valueOf(this.mTaskId), "removeTask");
        this.mDeferRemoval = false;
        super.removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(TaskStack stack, int position, boolean moveParents) {
        if (stack == this.mStack) {
            throw new IllegalArgumentException("task=" + this + " already child of stack=" + this.mStack);
        }
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.i(TAG, "reParentTask: removing taskId=" + this.mTaskId + " from stack=" + this.mStack);
        }
        EventLog.writeEvent((int) EventLogTags.WM_TASK_REMOVED, Integer.valueOf(this.mTaskId), "reParentTask");
        DisplayContent prevDisplayContent = getDisplayContent();
        if (stack.inPinnedWindowingMode()) {
            this.mPreserveNonFloatingState = true;
        } else {
            this.mPreserveNonFloatingState = false;
        }
        getParent().removeChild(this);
        stack.addTask(this, position, showForAllUsers(), moveParents);
        DisplayContent displayContent = stack.getDisplayContent();
        displayContent.setLayoutNeeded();
        if (prevDisplayContent != displayContent) {
            onDisplayChanged(displayContent);
            prevDisplayContent.setLayoutNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionAt(int position) {
        this.mStack.positionChildAt(position, this, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onParentSet() {
        super.onParentSet();
        updateDisplayInfo(getDisplayContent());
        if (getWindowConfiguration().windowsAreScaleable()) {
            forceWindowsScaleable(true);
        } else {
            forceWindowsScaleable(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(AppWindowToken token) {
        if (!this.mChildren.contains(token)) {
            Slog.e(TAG, "removeChild: token=" + this + " not found.");
            return;
        }
        super.removeChild((Task) token);
        if (this.mChildren.isEmpty()) {
            EventLog.writeEvent((int) EventLogTags.WM_TASK_REMOVED, Integer.valueOf(this.mTaskId), "removeAppToken: last token");
            if (this.mDeferRemoval) {
                removeIfPossible();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < this.mChildren.size(); appTokenNdx++) {
            ((AppWindowToken) this.mChildren.get(appTokenNdx)).sendingToBottom = toBottom;
        }
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        int boundsChanged = setBounds(bounds);
        if (forceResize && (boundsChanged & 2) != 2) {
            onResize();
            return 2 | boundsChanged;
        }
        return boundsChanged;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public int setBounds(Rect bounds) {
        int rotation = 0;
        DisplayContent displayContent = this.mStack.getDisplayContent();
        if (displayContent != null) {
            rotation = displayContent.getDisplayInfo().rotation;
        } else if (bounds == null) {
            return 0;
        }
        if (equivalentOverrideBounds(bounds)) {
            return 0;
        }
        int boundsChange = super.setBounds(bounds);
        this.mRotation = rotation;
        return boundsChange;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTempInsetBounds(Rect tempInsetBounds) {
        if (tempInsetBounds != null) {
            this.mTempInsetBounds.set(tempInsetBounds);
        } else {
            this.mTempInsetBounds.setEmpty();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTempInsetBounds(Rect out) {
        out.set(this.mTempInsetBounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResizeable(int resizeMode) {
        this.mResizeMode = resizeMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResizeable() {
        return ActivityInfo.isResizeableMode(this.mResizeMode) || this.mSupportsPictureInPicture || this.mService.mForceResizableTasks;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean preserveOrientationOnResize() {
        return this.mResizeMode == 6 || this.mResizeMode == 5 || this.mResizeMode == 7;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean cropWindowsToStackBounds() {
        return isResizeable();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareFreezingBounds() {
        this.mPreparedFrozenBounds.set(getBounds());
        this.mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void alignToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds, boolean alignBottom) {
        if (!isResizeable() || Configuration.EMPTY.equals(getOverrideConfiguration())) {
            return;
        }
        getBounds(this.mTmpRect2);
        if (alignBottom) {
            int offsetY = adjustedBounds.bottom - this.mTmpRect2.bottom;
            this.mTmpRect2.offset(0, offsetY);
        } else {
            this.mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
        }
        setTempInsetBounds(tempInsetBounds);
        setBounds(this.mTmpRect2, false);
    }

    private boolean useCurrentBounds() {
        DisplayContent displayContent = getDisplayContent();
        return matchParentBounds() || !inSplitScreenSecondaryWindowingMode() || displayContent == null || displayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void getBounds(Rect out) {
        if (useCurrentBounds()) {
            super.getBounds(out);
        } else {
            this.mStack.getDisplayContent().getBounds(out);
        }
    }

    boolean getMaxVisibleBounds(Rect out) {
        WindowState win;
        boolean foundTop = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            AppWindowToken token = (AppWindowToken) this.mChildren.get(i);
            if (!token.mIsExiting && !token.isClientHidden() && !token.hiddenRequested && (win = token.findMainWindow()) != null) {
                if (!foundTop) {
                    out.set(win.mVisibleFrame);
                    foundTop = true;
                } else {
                    if (win.mVisibleFrame.left < out.left) {
                        out.left = win.mVisibleFrame.left;
                    }
                    if (win.mVisibleFrame.top < out.top) {
                        out.top = win.mVisibleFrame.top;
                    }
                    if (win.mVisibleFrame.right > out.right) {
                        out.right = win.mVisibleFrame.right;
                    }
                    if (win.mVisibleFrame.bottom > out.bottom) {
                        out.bottom = win.mVisibleFrame.bottom;
                    }
                }
            }
        }
        return foundTop;
    }

    public void getDimBounds(Rect out) {
        DisplayContent displayContent = this.mStack.getDisplayContent();
        boolean dockedResizing = displayContent != null && displayContent.mDividerControllerLocked.isResizing();
        if (useCurrentBounds()) {
            if (inFreeformWindowingMode() && getMaxVisibleBounds(out)) {
                return;
            }
            if (!matchParentBounds()) {
                if (dockedResizing) {
                    this.mStack.getBounds(out);
                    return;
                }
                this.mStack.getBounds(this.mTmpRect);
                this.mTmpRect.intersect(getBounds());
                out.set(this.mTmpRect);
                return;
            }
            out.set(getBounds());
        } else if (displayContent != null) {
            displayContent.getBounds(out);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (this.mDragResizing == dragResizing || DragResizeMode.isResizeFreeformMode(dragResizeMode)) {
            return;
        }
        if (!DragResizeMode.isModeAllowedForStack(this.mStack, dragResizeMode)) {
            throw new IllegalArgumentException("Drag resize mode not allow for stack stackId=" + this.mStack.mStackId + " dragResizeMode=" + dragResizeMode);
        }
        this.mDragResizing = dragResizing;
        this.mDragResizeMode = dragResizeMode;
        resetDragResizingChangeReported();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDragResizing() {
        return this.mDragResizing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDragResizeMode() {
        return this.mDragResizeMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateDisplayInfo(DisplayContent displayContent) {
        TaskWindowContainerController controller;
        if (displayContent == null) {
            return;
        }
        if (matchParentBounds()) {
            setBounds(null);
            return;
        }
        int newRotation = displayContent.getDisplayInfo().rotation;
        if (this.mRotation == newRotation) {
            return;
        }
        this.mTmpRect2.set(getBounds());
        if (!getWindowConfiguration().canResizeTask()) {
            setBounds(this.mTmpRect2);
            return;
        }
        displayContent.rotateBounds(this.mRotation, newRotation, this.mTmpRect2);
        if (setBounds(this.mTmpRect2) != 0 && (controller = getController()) != null) {
            controller.requestResize(getBounds(), 1);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelTaskWindowTransition() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            ((AppWindowToken) this.mChildren.get(i)).cancelAnimation();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean showForAllUsers() {
        int tokensCount = this.mChildren.size();
        return tokensCount != 0 && ((AppWindowToken) this.mChildren.get(tokensCount + (-1))).mShowForAllUsers;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFloating() {
        return (!getWindowConfiguration().tasksAreFloating() || this.mStack.isAnimatingBoundsToFullscreen() || this.mPreserveNonFloatingState) ? false : true;
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl getAnimationLeashParent() {
        return getAppAnimationLayer(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTaskAnimating() {
        RecentsAnimationController recentsAnim = this.mService.getRecentsAnimationController();
        if (recentsAnim != null && recentsAnim.isAnimatingTask(this)) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getTopVisibleAppMainWindow() {
        AppWindowToken token = getTopVisibleAppToken();
        if (token != null) {
            return token.findMainWindow();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken getTopFullscreenAppToken() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            AppWindowToken token = (AppWindowToken) this.mChildren.get(i);
            WindowState win = token.findMainWindow();
            if (win != null && win.mAttrs.isFullscreen()) {
                return token;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken getTopVisibleAppToken() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            AppWindowToken token = (AppWindowToken) this.mChildren.get(i);
            if (!token.mIsExiting && !token.isClientHidden() && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFullscreen() {
        if (useCurrentBounds()) {
            return matchParentBounds();
        }
        return true;
    }

    void forceWindowsScaleable(boolean force) {
        this.mService.openSurfaceTransaction();
        try {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                ((AppWindowToken) this.mChildren.get(i)).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            this.mService.closeSurfaceTransaction("forceWindowsScaleable");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        this.mTaskDescription = taskDescription;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.TaskDescription getTaskDescription() {
        return this.mTaskDescription;
    }

    @Override // com.android.server.wm.WindowContainer
    boolean fillsParent() {
        return matchParentBounds() || !getWindowConfiguration().canResizeTask();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public TaskWindowContainerController getController() {
        return (TaskWindowContainerController) super.getController();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void forAllTasks(Consumer<Task> callback) {
        callback.accept(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        this.mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canAffectSystemUiFlags() {
        return this.mCanAffectSystemUiFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dontAnimateDimExit() {
        this.mDimmer.dontAnimateExit();
    }

    public String toString() {
        return "{taskId=" + this.mTaskId + " appTokens=" + this.mChildren + " mdr=" + this.mDeferRemoval + "}";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return toShortString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearPreserveNonFloatingState() {
        this.mPreserveNonFloatingState = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public Dimmer getDimmer() {
        return this.mDimmer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void prepareSurfaces() {
        this.mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(this.mTmpDimBoundsRect);
        this.mTmpDimBoundsRect.offsetTo(0, 0);
        if (this.mDimmer.updateDims(getPendingTransaction(), this.mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        proto.write(1120986464258L, this.mTaskId);
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            AppWindowToken appWindowToken = (AppWindowToken) this.mChildren.get(i);
            appWindowToken.writeToProto(proto, 2246267895811L, trim);
        }
        proto.write(1133871366148L, matchParentBounds());
        getBounds().writeToProto(proto, 1146756268037L);
        this.mTempInsetBounds.writeToProto(proto, 1146756268038L);
        proto.write(1133871366151L, this.mDeferRemoval);
        proto.end(token);
    }

    @Override // com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        String doublePrefix = prefix + "  ";
        pw.println(prefix + "taskId=" + this.mTaskId);
        pw.println(doublePrefix + "mBounds=" + getBounds().toShortString());
        pw.println(doublePrefix + "mdr=" + this.mDeferRemoval);
        pw.println(doublePrefix + "appTokens=" + this.mChildren);
        pw.println(doublePrefix + "mTempInsetBounds=" + this.mTempInsetBounds.toShortString());
        String triplePrefix = doublePrefix + "  ";
        String quadruplePrefix = triplePrefix + "  ";
        for (int i = this.mChildren.size() - 1; i >= 0; i += -1) {
            AppWindowToken wtoken = (AppWindowToken) this.mChildren.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + wtoken);
            wtoken.dump(pw, quadruplePrefix, dumpAll);
        }
    }

    String toShortString() {
        return "Task=" + this.mTaskId;
    }
}
