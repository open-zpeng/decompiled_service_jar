package com.android.server.wm;

import android.app.RemoteAction;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.EventLogTags;
import com.android.server.wm.BoundsAnimationController;
import com.android.server.wm.DisplayContent;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
public class TaskStack extends WindowContainer<Task> implements BoundsAnimationTarget, ConfigurationContainerListener {
    private static final float ADJUSTED_STACK_FRACTION_MIN = 0.3f;
    private static final float IME_ADJUST_DIM_AMOUNT = 0.25f;
    ActivityStack mActivityStack;
    private float mAdjustDividerAmount;
    private float mAdjustImeAmount;
    private final Rect mAdjustedBounds;
    private boolean mAdjustedForIme;
    private final AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry;
    private WindowStateAnimator mAnimationBackgroundAnimator;
    private SurfaceControl mAnimationBackgroundSurface;
    private boolean mAnimationBackgroundSurfaceIsShown;
    @BoundsAnimationController.AnimationType
    private int mAnimationType;
    private boolean mBoundsAnimating;
    private boolean mBoundsAnimatingRequested;
    private boolean mBoundsAnimatingToFullscreen;
    private Rect mBoundsAnimationSourceHintBounds;
    private Rect mBoundsAnimationTarget;
    private boolean mCancelCurrentBoundsAnimation;
    boolean mDeferRemoval;
    private Dimmer mDimmer;
    private final int mDockedStackMinimizeThickness;
    final AppTokenList mExitingAppTokens;
    private final Rect mFullyAdjustedImeBounds;
    private boolean mImeGoingAway;
    private WindowState mImeWin;
    private final Point mLastSurfaceSize;
    private float mMinimizeAmount;
    Rect mPreAnimationBounds;
    final int mStackId;
    private final Rect mTmpAdjustedBounds;
    final AppTokenList mTmpAppTokens;
    final Rect mTmpDimBoundsRect;
    private Rect mTmpFromBounds;
    private Rect mTmpRect;
    private Rect mTmpRect2;
    private Rect mTmpRect3;
    private Rect mTmpToBounds;

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ void commitPendingTransaction() {
        super.commitPendingTransaction();
    }

    @Override // com.android.server.wm.WindowContainer
    public /* bridge */ /* synthetic */ int compareTo(WindowContainer windowContainer) {
        return super.compareTo(windowContainer);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ SurfaceControl getAnimationLeashParent() {
        return super.getAnimationLeashParent();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ SurfaceControl getParentSurfaceControl() {
        return super.getParentSurfaceControl();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ SurfaceControl.Transaction getPendingTransaction() {
        return super.getPendingTransaction();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ SurfaceControl getSurfaceControl() {
        return super.getSurfaceControl();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ int getSurfaceHeight() {
        return super.getSurfaceHeight();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ int getSurfaceWidth() {
        return super.getSurfaceWidth();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ SurfaceControl.Builder makeAnimationLeash() {
        return super.makeAnimationLeash();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ void onAnimationLeashCreated(SurfaceControl.Transaction transaction, SurfaceControl surfaceControl) {
        super.onAnimationLeashCreated(transaction, surfaceControl);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public /* bridge */ /* synthetic */ void onAnimationLeashLost(SurfaceControl.Transaction transaction) {
        super.onAnimationLeashLost(transaction);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public /* bridge */ /* synthetic */ void onRequestedOverrideConfigurationChanged(Configuration configuration) {
        super.onRequestedOverrideConfigurationChanged(configuration);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack(WindowManagerService service, int stackId, ActivityStack activityStack) {
        super(service);
        this.mTmpRect = new Rect();
        this.mTmpRect2 = new Rect();
        this.mTmpRect3 = new Rect();
        this.mTmpFromBounds = new Rect();
        this.mTmpToBounds = new Rect();
        this.mAdjustedBounds = new Rect();
        this.mFullyAdjustedImeBounds = new Rect();
        this.mAnimationBackgroundSurfaceIsShown = false;
        this.mExitingAppTokens = new AppTokenList();
        this.mTmpAppTokens = new AppTokenList();
        this.mTmpAdjustedBounds = new Rect();
        this.mBoundsAnimating = false;
        this.mBoundsAnimatingRequested = false;
        this.mBoundsAnimatingToFullscreen = false;
        this.mCancelCurrentBoundsAnimation = false;
        this.mBoundsAnimationTarget = new Rect();
        this.mBoundsAnimationSourceHintBounds = new Rect();
        this.mPreAnimationBounds = new Rect();
        this.mDimmer = new Dimmer(this);
        this.mTmpDimBoundsRect = new Rect();
        this.mLastSurfaceSize = new Point();
        this.mAnimatingAppWindowTokenRegistry = new AnimatingAppWindowTokenRegistry();
        this.mStackId = stackId;
        this.mActivityStack = activityStack;
        activityStack.registerConfigurationChangeListener(this);
        this.mDockedStackMinimizeThickness = service.mContext.getResources().getDimensionPixelSize(17105147);
        EventLog.writeEvent((int) EventLogTags.WM_STACK_CREATED, stackId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task findHomeTask() {
        if (!isActivityTypeHome() || this.mChildren.isEmpty()) {
            return null;
        }
        return (Task) this.mChildren.get(this.mChildren.size() - 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareFreezingTaskBounds() {
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            Task task = (Task) this.mChildren.get(taskNdx);
            task.prepareFreezingBounds();
        }
    }

    private void setAdjustedBounds(Rect bounds) {
        if (this.mAdjustedBounds.equals(bounds) && !isAnimatingForIme()) {
            return;
        }
        this.mAdjustedBounds.set(bounds);
        boolean adjusted = !this.mAdjustedBounds.isEmpty();
        Rect insetBounds = null;
        if (adjusted && isAdjustedForMinimizedDockedStack()) {
            insetBounds = getRawBounds();
        } else if (adjusted && this.mAdjustedForIme) {
            insetBounds = this.mImeGoingAway ? getRawBounds() : this.mFullyAdjustedImeBounds;
        }
        alignTasksToAdjustedBounds(adjusted ? this.mAdjustedBounds : getRawBounds(), insetBounds);
        this.mDisplayContent.setLayoutNeeded();
        updateSurfaceBounds();
    }

    private void alignTasksToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds) {
        if (matchParentBounds()) {
            return;
        }
        boolean alignBottom = this.mAdjustedForIme && getDockSide() == 2;
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            Task task = (Task) this.mChildren.get(taskNdx);
            task.alignToAdjustedBounds(adjustedBounds, tempInsetBounds, alignBottom);
        }
    }

    private void updateAnimationBackgroundBounds() {
        if (this.mAnimationBackgroundSurface == null) {
            return;
        }
        getRawBounds(this.mTmpRect);
        Rect stackBounds = getBounds();
        getPendingTransaction().setWindowCrop(this.mAnimationBackgroundSurface, this.mTmpRect.width(), this.mTmpRect.height()).setPosition(this.mAnimationBackgroundSurface, this.mTmpRect.left - stackBounds.left, this.mTmpRect.top - stackBounds.top);
        scheduleAnimation();
    }

    private void hideAnimationSurface() {
        if (this.mAnimationBackgroundSurface == null) {
            return;
        }
        getPendingTransaction().hide(this.mAnimationBackgroundSurface);
        this.mAnimationBackgroundSurfaceIsShown = false;
        scheduleAnimation();
    }

    private void showAnimationSurface(float alpha) {
        if (this.mAnimationBackgroundSurface == null) {
            return;
        }
        getPendingTransaction().setLayer(this.mAnimationBackgroundSurface, Integer.MIN_VALUE).setAlpha(this.mAnimationBackgroundSurface, alpha).show(this.mAnimationBackgroundSurface);
        this.mAnimationBackgroundSurfaceIsShown = true;
        scheduleAnimation();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public int setBounds(Rect bounds) {
        return setBounds(getRequestedOverrideBounds(), bounds);
    }

    private int setBounds(Rect existing, Rect bounds) {
        if (equivalentBounds(existing, bounds)) {
            return 0;
        }
        int result = super.setBounds(bounds);
        if (getParent() != null) {
            updateAnimationBackgroundBounds();
        }
        updateAdjustedBounds();
        updateSurfaceBounds();
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getRawBounds(Rect out) {
        out.set(getRawBounds());
    }

    Rect getRawBounds() {
        return super.getBounds();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Rect getBounds() {
        if (!this.mAdjustedBounds.isEmpty()) {
            return this.mAdjustedBounds;
        }
        return super.getBounds();
    }

    private void setAnimationFinalBounds(Rect sourceHintBounds, Rect destBounds, boolean toFullscreen) {
        this.mBoundsAnimatingRequested = true;
        this.mBoundsAnimatingToFullscreen = toFullscreen;
        if (destBounds != null) {
            this.mBoundsAnimationTarget.set(destBounds);
        } else {
            this.mBoundsAnimationTarget.setEmpty();
        }
        if (sourceHintBounds != null) {
            this.mBoundsAnimationSourceHintBounds.set(sourceHintBounds);
        } else if (!this.mBoundsAnimating) {
            this.mBoundsAnimationSourceHintBounds.setEmpty();
        }
        this.mPreAnimationBounds.set(getRawBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getFinalAnimationBounds(Rect outBounds) {
        outBounds.set(this.mBoundsAnimationTarget);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getFinalAnimationSourceHintBounds(Rect outBounds) {
        outBounds.set(this.mBoundsAnimationSourceHintBounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getAnimationOrCurrentBounds(Rect outBounds) {
        if ((this.mBoundsAnimatingRequested || this.mBoundsAnimating) && !this.mBoundsAnimationTarget.isEmpty()) {
            getFinalAnimationBounds(outBounds);
        } else {
            getBounds(outBounds);
        }
    }

    public void getDimBounds(Rect out) {
        getBounds(out);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean calculatePinnedBoundsForConfigChange(Rect inOutBounds) {
        boolean animating = false;
        if ((this.mBoundsAnimatingRequested || this.mBoundsAnimating) && !this.mBoundsAnimationTarget.isEmpty()) {
            animating = true;
            getFinalAnimationBounds(this.mTmpRect2);
        } else {
            this.mTmpRect2.set(inOutBounds);
        }
        boolean updated = this.mDisplayContent.mPinnedStackControllerLocked.onTaskStackBoundsChanged(this.mTmpRect2, this.mTmpRect3);
        if (updated) {
            inOutBounds.set(this.mTmpRect3);
            if (animating && !inOutBounds.equals(this.mBoundsAnimationTarget)) {
                final DisplayContent displayContent = getDisplayContent();
                displayContent.mBoundsAnimationController.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskStack$LbFVWgYTv7giS6WqQc5168AJCDQ
                    @Override // java.lang.Runnable
                    public final void run() {
                        TaskStack.this.lambda$calculatePinnedBoundsForConfigChange$0$TaskStack(displayContent);
                    }
                });
            }
            this.mBoundsAnimationTarget.setEmpty();
            this.mBoundsAnimationSourceHintBounds.setEmpty();
            this.mCancelCurrentBoundsAnimation = true;
        }
        return updated;
    }

    public /* synthetic */ void lambda$calculatePinnedBoundsForConfigChange$0$TaskStack(DisplayContent displayContent) {
        displayContent.mBoundsAnimationController.cancel(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void calculateDockedBoundsForConfigChange(Configuration parentConfig, Rect inOutBounds) {
        int i = 0;
        boolean primary = getRequestedOverrideWindowingMode() == 3;
        repositionSplitScreenStackAfterRotation(parentConfig, primary, inOutBounds);
        DisplayCutout cutout = this.mDisplayContent.getDisplayInfo().displayCutout;
        snapDockedStackAfterRotation(parentConfig, cutout, inOutBounds);
        if (primary) {
            int newDockSide = getDockSide(parentConfig, inOutBounds);
            WindowManagerService windowManagerService = this.mWmService;
            if (newDockSide != 1 && newDockSide != 2) {
                i = 1;
            }
            windowManagerService.setDockedStackCreateStateLocked(i, null);
            this.mDisplayContent.getDockedDividerController().notifyDockSideChanged(newDockSide);
        }
    }

    void repositionSplitScreenStackAfterRotation(Configuration parentConfig, boolean primary, Rect inOutBounds) {
        int dockSide = getDockSide(this.mDisplayContent, parentConfig, inOutBounds);
        int otherDockSide = DockedDividerUtils.invertDockSide(dockSide);
        int primaryDockSide = primary ? dockSide : otherDockSide;
        if (this.mDisplayContent.getDockedDividerController().canPrimaryStackDockTo(primaryDockSide, parentConfig.windowConfiguration.getBounds(), parentConfig.windowConfiguration.getRotation())) {
            return;
        }
        Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        if (otherDockSide == 1) {
            int movement = inOutBounds.left;
            inOutBounds.left -= movement;
            inOutBounds.right -= movement;
        } else if (otherDockSide == 2) {
            int movement2 = inOutBounds.top;
            inOutBounds.top -= movement2;
            inOutBounds.bottom -= movement2;
        } else if (otherDockSide == 3) {
            int movement3 = parentBounds.right - inOutBounds.right;
            inOutBounds.left += movement3;
            inOutBounds.right += movement3;
        } else if (otherDockSide == 4) {
            int movement4 = parentBounds.bottom - inOutBounds.bottom;
            inOutBounds.top += movement4;
            inOutBounds.bottom += movement4;
        }
    }

    void snapDockedStackAfterRotation(Configuration parentConfig, DisplayCutout displayCutout, Rect outBounds) {
        int dividerSize = this.mDisplayContent.getDockedDividerController().getContentWidth();
        int dockSide = getDockSide(parentConfig, outBounds);
        int dividerPosition = DockedDividerUtils.calculatePositionForBounds(outBounds, dockSide, dividerSize);
        int displayWidth = parentConfig.windowConfiguration.getBounds().width();
        int displayHeight = parentConfig.windowConfiguration.getBounds().height();
        int rotation = parentConfig.windowConfiguration.getRotation();
        int orientation = parentConfig.orientation;
        this.mDisplayContent.getDisplayPolicy().getStableInsetsLw(rotation, displayWidth, displayHeight, displayCutout, outBounds);
        DividerSnapAlgorithm algorithm = new DividerSnapAlgorithm(this.mWmService.mContext.getResources(), displayWidth, displayHeight, dividerSize, orientation == 1, outBounds, getDockSide(), isMinimizedDockAndHomeStackResizable());
        DividerSnapAlgorithm.SnapTarget target = algorithm.calculateNonDismissingSnapTarget(dividerPosition);
        DockedDividerUtils.calculateBoundsForPosition(target.position, dockSide, outBounds, displayWidth, displayHeight, dividerSize);
    }

    void addTask(Task task, int position) {
        addTask(task, position, task.showForAllUsers(), true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addTask(Task task, int position, boolean showForAllUsers, boolean moveParents) {
        TaskStack currentStack = task.mStack;
        if (currentStack != null && currentStack.mStackId != this.mStackId) {
            throw new IllegalStateException("Trying to add taskId=" + task.mTaskId + " to stackId=" + this.mStackId + ", but it is already attached to stackId=" + task.mStack.mStackId);
        }
        task.mStack = this;
        addChild((TaskStack) task, (Comparator<TaskStack>) null);
        positionChildAt(position, task, moveParents, showForAllUsers);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAt(Task child, int position) {
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.i("WindowManager", "positionChildAt: positioning task=" + child + " at " + position);
        }
        if (child == null) {
            if (WindowManagerDebugConfig.DEBUG_STACK) {
                Slog.i("WindowManager", "positionChildAt: could not find task=" + this);
                return;
            }
            return;
        }
        child.positionAt(position);
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtTop(Task child, boolean includingParents) {
        if (child == null) {
            return;
        }
        positionChildAt(Integer.MAX_VALUE, child, includingParents);
        DisplayContent displayContent = getDisplayContent();
        if (displayContent.mAppTransition.isTransitionSet()) {
            child.setSendingToBottom(false);
        }
        displayContent.layoutAndAssignWindowLayersIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAtBottom(Task child, boolean includingParents) {
        if (child == null) {
            return;
        }
        positionChildAt(Integer.MIN_VALUE, child, includingParents);
        if (getDisplayContent().mAppTransition.isTransitionSet()) {
            child.setSendingToBottom(true);
        }
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void positionChildAt(int position, Task child, boolean includingParents) {
        positionChildAt(position, child, includingParents, child.showForAllUsers());
    }

    private void positionChildAt(int position, Task child, boolean includingParents, boolean showForAllUsers) {
        int targetPosition = findPositionForTask(child, position, showForAllUsers, false);
        super.positionChildAt(targetPosition, (int) child, includingParents);
        if (WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT) {
            Slog.d("WindowManager", "positionTask: task=" + this + " position=" + position);
        }
        int toTop = targetPosition == this.mChildren.size() - 1 ? 1 : 0;
        EventLog.writeEvent((int) EventLogTags.WM_TASK_MOVED, Integer.valueOf(child.mTaskId), Integer.valueOf(toTop), Integer.valueOf(targetPosition));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(int displayId, Rect outStackBounds, boolean onTop) {
        DisplayContent targetDc = this.mWmService.mRoot.getDisplayContent(displayId);
        if (targetDc == null) {
            throw new IllegalArgumentException("Trying to move stackId=" + this.mStackId + " to unknown displayId=" + displayId);
        }
        targetDc.moveStackToDisplay(this, onTop);
        if (matchParentBounds()) {
            outStackBounds.setEmpty();
        } else {
            getRawBounds(outStackBounds);
        }
    }

    private int findPositionForTask(Task task, int targetPosition, boolean showForAllUsers, boolean addingNew) {
        boolean canShowTask = showForAllUsers || this.mWmService.isCurrentProfileLocked(task.mUserId);
        int stackSize = this.mChildren.size();
        int minPosition = 0;
        int maxPosition = addingNew ? stackSize : stackSize - 1;
        if (canShowTask) {
            minPosition = computeMinPosition(0, stackSize);
        } else {
            maxPosition = computeMaxPosition(maxPosition);
        }
        if (targetPosition == Integer.MIN_VALUE && minPosition == 0) {
            return Integer.MIN_VALUE;
        }
        if (targetPosition == Integer.MAX_VALUE) {
            if (maxPosition == (addingNew ? stackSize : stackSize - 1)) {
                return Integer.MAX_VALUE;
            }
        }
        return Math.min(Math.max(targetPosition, minPosition), maxPosition);
    }

    private int computeMinPosition(int minPosition, int size) {
        while (minPosition < size) {
            Task tmpTask = (Task) this.mChildren.get(minPosition);
            boolean canShowTmpTask = tmpTask.showForAllUsers() || this.mWmService.isCurrentProfileLocked(tmpTask.mUserId);
            if (canShowTmpTask) {
                break;
            }
            minPosition++;
        }
        return minPosition;
    }

    private int computeMaxPosition(int maxPosition) {
        while (maxPosition > 0) {
            Task tmpTask = (Task) this.mChildren.get(maxPosition);
            boolean canShowTmpTask = tmpTask.showForAllUsers() || this.mWmService.isCurrentProfileLocked(tmpTask.mUserId);
            if (!canShowTmpTask) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(Task task) {
        if (WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT) {
            Slog.d("WindowManager", "removeChild: task=" + task);
        }
        super.removeChild((TaskStack) task);
        task.mStack = null;
        if (this.mDisplayContent != null) {
            if (this.mChildren.isEmpty()) {
                getParent().positionChildAt(Integer.MIN_VALUE, this, false);
            }
            this.mDisplayContent.setLayoutNeeded();
        }
        for (int appNdx = this.mExitingAppTokens.size() - 1; appNdx >= 0; appNdx--) {
            AppWindowToken wtoken = this.mExitingAppTokens.get(appNdx);
            if (wtoken.getTask() == task) {
                wtoken.mIsExiting = false;
                this.mExitingAppTokens.remove(appNdx);
            }
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        int prevWindowingMode = getWindowingMode();
        super.onConfigurationChanged(newParentConfig);
        updateSurfaceSize(getPendingTransaction());
        int windowingMode = getWindowingMode();
        isAlwaysOnTop();
        if (this.mDisplayContent != null && prevWindowingMode != windowingMode) {
            this.mDisplayContent.onStackWindowingModeChanged(this);
            if (inSplitScreenSecondaryWindowingMode()) {
                forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskStack$PVMhxGhbT6eBbe3ARm5uodEqxDE
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ((WindowState) obj).mWinAnimator.setOffsetPositionForStackResize(true);
                    }
                }, true);
            }
        }
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getPendingTransaction());
        updateSurfacePosition();
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getStackOutset() {
        DisplayContent displayContent = getDisplayContent();
        if (inPinnedWindowingMode() && displayContent != null) {
            DisplayMetrics displayMetrics = displayContent.getDisplayMetrics();
            WindowManagerService windowManagerService = this.mWmService;
            return (int) Math.ceil(WindowManagerService.dipToPixel(5, displayMetrics) * 2);
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void getRelativeDisplayedPosition(Point outPos) {
        super.getRelativeDisplayedPosition(outPos);
        int outset = getStackOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    private void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (this.mSurfaceControl == null) {
            return;
        }
        Rect stackBounds = getDisplayedBounds();
        int width = stackBounds.width();
        int height = stackBounds.height();
        int outset = getStackOutset();
        int width2 = width + (outset * 2);
        int height2 = height + (outset * 2);
        if (width2 == this.mLastSurfaceSize.x && height2 == this.mLastSurfaceSize.y) {
            return;
        }
        if (getWindowConfiguration().tasksAreFloating()) {
            transaction.setWindowCrop(this.mSurfaceControl, -1, -1);
        } else {
            transaction.setWindowCrop(this.mSurfaceControl, width2, height2);
        }
        this.mLastSurfaceSize.set(width2, height2);
    }

    @VisibleForTesting
    Point getLastSurfaceSize() {
        return this.mLastSurfaceSize;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onDisplayChanged(DisplayContent dc) {
        if (this.mDisplayContent != null && this.mDisplayContent != dc) {
            throw new IllegalStateException("onDisplayChanged: Already attached");
        }
        super.onDisplayChanged(dc);
        updateSurfaceBounds();
        if (this.mAnimationBackgroundSurface == null) {
            SurfaceControl.Builder colorLayer = makeChildSurface(null).setColorLayer();
            this.mAnimationBackgroundSurface = colorLayer.setName("animation background stackId=" + this.mStackId).build();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStackDockedModeBoundsLocked(Configuration parentConfig, Rect dockedBounds, Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds) {
        outTempTaskBounds.setEmpty();
        if (dockedBounds != null && !dockedBounds.isEmpty()) {
            int dockedSide = getDockSide(parentConfig, dockedBounds);
            if (isActivityTypeHome()) {
                Task homeTask = findHomeTask();
                if (homeTask != null && homeTask.isResizeable()) {
                    getDisplayContent().mDividerControllerLocked.getHomeStackBoundsInDockedMode(parentConfig, dockedSide, outStackBounds);
                } else {
                    outStackBounds.setEmpty();
                }
                outTempTaskBounds.set(outStackBounds);
                return;
            } else if (isMinimizedDockAndHomeStackResizable() && currentTempTaskBounds != null) {
                outStackBounds.set(currentTempTaskBounds);
                return;
            } else if (dockedSide == -1) {
                Slog.e("WindowManager", "Failed to get valid docked side for docked stack");
                outStackBounds.set(getRawBounds());
                return;
            } else {
                boolean dockedOnTopOrLeft = dockedSide == 2 || dockedSide == 1;
                getStackDockedModeBounds(parentConfig, false, outStackBounds, dockedBounds, this.mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
                return;
            }
        }
        boolean dockedOnTopOrLeft2 = this.mWmService.mDockedStackCreateMode == 0;
        getStackDockedModeBounds(parentConfig, true, outStackBounds, dockedBounds, this.mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft2);
    }

    private void getStackDockedModeBounds(Configuration parentConfig, boolean primary, Rect outBounds, Rect dockedBounds, int dockDividerWidth, boolean dockOnTopOrLeft) {
        Rect displayRect = parentConfig.windowConfiguration.getBounds();
        boolean splitHorizontally = displayRect.width() > displayRect.height();
        outBounds.set(displayRect);
        if (primary) {
            if (this.mWmService.mDockedStackCreateBounds != null) {
                outBounds.set(this.mWmService.mDockedStackCreateBounds);
                return;
            }
            DisplayCutout displayCutout = this.mDisplayContent.getDisplayInfo().displayCutout;
            this.mDisplayContent.getDisplayPolicy().getStableInsetsLw(parentConfig.windowConfiguration.getRotation(), displayRect.width(), displayRect.height(), displayCutout, this.mTmpRect2);
            int position = new DividerSnapAlgorithm(this.mWmService.mContext.getResources(), displayRect.width(), displayRect.height(), dockDividerWidth, parentConfig.orientation == 1, this.mTmpRect2).getMiddleTarget().position;
            if (dockOnTopOrLeft) {
                if (splitHorizontally) {
                    outBounds.right = position;
                    return;
                } else {
                    outBounds.bottom = position;
                    return;
                }
            } else if (splitHorizontally) {
                outBounds.left = position + dockDividerWidth;
                return;
            } else {
                outBounds.top = position + dockDividerWidth;
                return;
            }
        }
        if (!dockOnTopOrLeft) {
            if (splitHorizontally) {
                outBounds.right = dockedBounds.left - dockDividerWidth;
            } else {
                outBounds.bottom = dockedBounds.top - dockDividerWidth;
            }
        } else if (splitHorizontally) {
            outBounds.left = dockedBounds.right + dockDividerWidth;
        } else {
            outBounds.top = dockedBounds.bottom + dockDividerWidth;
        }
        DockedDividerUtils.sanitizeStackBounds(outBounds, !dockOnTopOrLeft);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetDockedStackToMiddle() {
        if (inSplitScreenPrimaryWindowingMode()) {
            throw new IllegalStateException("Not a docked stack=" + this);
        }
        Rect rect = null;
        this.mWmService.mDockedStackCreateBounds = null;
        Rect bounds = new Rect();
        Rect tempBounds = new Rect();
        TaskStack dockedStack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (dockedStack != null && dockedStack != this) {
            rect = dockedStack.getRawBounds();
        }
        Rect dockedBounds = rect;
        getStackDockedModeBoundsLocked(this.mDisplayContent.getConfiguration(), dockedBounds, null, bounds, tempBounds);
        this.mActivityStack.requestResize(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeIfPossible() {
        if (isSelfOrChildAnimating()) {
            this.mDeferRemoval = true;
        } else {
            removeImmediately();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeImmediately() {
        ActivityStack activityStack = this.mActivityStack;
        if (activityStack != null) {
            activityStack.unregisterConfigurationChangeListener(this);
        }
        super.removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        super.onParentChanged();
        if (getParent() != null || this.mDisplayContent == null) {
            return;
        }
        EventLog.writeEvent((int) EventLogTags.WM_STACK_REMOVED, this.mStackId);
        if (this.mAnimationBackgroundSurface != null) {
            this.mWmService.mTransactionFactory.make().remove(this.mAnimationBackgroundSurface).apply();
            this.mAnimationBackgroundSurface = null;
        }
        this.mDisplayContent = null;
        this.mWmService.mWindowPlacerLocked.requestTraversal();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetAnimationBackgroundAnimator() {
        this.mAnimationBackgroundAnimator = null;
        hideAnimationSurface();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAnimationBackground(WindowStateAnimator winAnimator, int color) {
        if (this.mAnimationBackgroundAnimator == null) {
            this.mAnimationBackgroundAnimator = winAnimator;
            showAnimationSurface(((color >> 24) & 255) / 255.0f);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void switchUser() {
        super.switchUser();
        int top = this.mChildren.size();
        for (int taskNdx = 0; taskNdx < top; taskNdx++) {
            Task task = (Task) this.mChildren.get(taskNdx);
            if (this.mWmService.isCurrentProfileLocked(task.mUserId) || task.showForAllUsers()) {
                this.mChildren.remove(taskNdx);
                this.mChildren.add(task);
                top--;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAdjustedForIme(WindowState imeWin, boolean keepLastAmount) {
        this.mImeWin = imeWin;
        this.mImeGoingAway = false;
        if (!this.mAdjustedForIme || keepLastAmount) {
            this.mAdjustedForIme = true;
            DockedStackDividerController controller = getDisplayContent().mDividerControllerLocked;
            float adjustImeAmount = keepLastAmount ? controller.mLastAnimationProgress : 0.0f;
            float adjustDividerAmount = keepLastAmount ? controller.mLastDividerProgress : 0.0f;
            updateAdjustForIme(adjustImeAmount, adjustDividerAmount, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAdjustedForIme() {
        return this.mAdjustedForIme;
    }

    boolean isAnimatingForIme() {
        WindowState windowState = this.mImeWin;
        return windowState != null && windowState.isAnimatingLw();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateAdjustForIme(float adjustAmount, float adjustDividerAmount, boolean force) {
        if (adjustAmount != this.mAdjustImeAmount || adjustDividerAmount != this.mAdjustDividerAmount || force) {
            this.mAdjustImeAmount = adjustAmount;
            this.mAdjustDividerAmount = adjustDividerAmount;
            updateAdjustedBounds();
            return isVisible();
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetAdjustedForIme(boolean adjustBoundsNow) {
        if (adjustBoundsNow) {
            this.mImeWin = null;
            this.mImeGoingAway = false;
            this.mAdjustImeAmount = 0.0f;
            this.mAdjustDividerAmount = 0.0f;
            if (!this.mAdjustedForIme) {
                return;
            }
            this.mAdjustedForIme = false;
            updateAdjustedBounds();
            this.mWmService.setResizeDimLayer(false, getWindowingMode(), 1.0f);
            return;
        }
        this.mImeGoingAway |= this.mAdjustedForIme;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setAdjustedForMinimizedDock(float minimizeAmount) {
        if (minimizeAmount != this.mMinimizeAmount) {
            this.mMinimizeAmount = minimizeAmount;
            updateAdjustedBounds();
            return isVisible();
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldIgnoreInput() {
        return isAdjustedForMinimizedDockedStack() || (inSplitScreenPrimaryWindowingMode() && isMinimizedDockAndHomeStackResizable());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void beginImeAdjustAnimation() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            Task task = (Task) this.mChildren.get(j);
            if (task.hasContentToDisplay()) {
                task.setDragResizing(true, 1);
                task.setWaitingForDrawnIfResizingChanged();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endImeAdjustAnimation() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            ((Task) this.mChildren.get(j)).setDragResizing(false, 1);
        }
    }

    int getMinTopStackBottom(Rect displayContentRect, int originalStackBottom) {
        return displayContentRect.top + ((int) ((originalStackBottom - displayContentRect.top) * ADJUSTED_STACK_FRACTION_MIN));
    }

    private boolean adjustForIME(WindowState imeWin) {
        if (getDisplayContent().mAppTransition.isRunning()) {
            return false;
        }
        int dockedSide = getDockSide();
        boolean dockedTopOrBottom = dockedSide == 2 || dockedSide == 4;
        if (imeWin != null && dockedTopOrBottom) {
            Rect displayStableRect = this.mTmpRect;
            Rect contentBounds = this.mTmpRect2;
            getDisplayContent().getStableRect(displayStableRect);
            contentBounds.set(displayStableRect);
            int imeTop = Math.max(imeWin.getFrameLw().top, contentBounds.top) + imeWin.getGivenContentInsetsLw().top;
            if (contentBounds.bottom > imeTop) {
                contentBounds.bottom = imeTop;
            }
            int yOffset = displayStableRect.bottom - contentBounds.bottom;
            int dividerWidth = getDisplayContent().mDividerControllerLocked.getContentWidth();
            int dividerWidthInactive = getDisplayContent().mDividerControllerLocked.getContentWidthInactive();
            if (dockedSide == 2) {
                int minTopStackBottom = getMinTopStackBottom(displayStableRect, getRawBounds().bottom);
                int bottom = Math.max(((getRawBounds().bottom - yOffset) + dividerWidth) - dividerWidthInactive, minTopStackBottom);
                this.mTmpAdjustedBounds.set(getRawBounds());
                Rect rect = this.mTmpAdjustedBounds;
                float f = this.mAdjustImeAmount;
                rect.bottom = (int) ((bottom * f) + ((1.0f - f) * getRawBounds().bottom));
                this.mFullyAdjustedImeBounds.set(getRawBounds());
                return true;
            }
            int dividerWidthDelta = dividerWidthInactive - dividerWidth;
            int topBeforeImeAdjust = (getRawBounds().top - dividerWidth) + dividerWidthInactive;
            int minTopStackBottom2 = getMinTopStackBottom(displayStableRect, getRawBounds().top - dividerWidth);
            int top = Math.max(getRawBounds().top - yOffset, minTopStackBottom2 + dividerWidthInactive);
            this.mTmpAdjustedBounds.set(getRawBounds());
            this.mTmpAdjustedBounds.top = getRawBounds().top + ((int) ((this.mAdjustImeAmount * (top - topBeforeImeAdjust)) + (this.mAdjustDividerAmount * dividerWidthDelta)));
            this.mFullyAdjustedImeBounds.set(getRawBounds());
            Rect rect2 = this.mFullyAdjustedImeBounds;
            rect2.top = top;
            rect2.bottom = getRawBounds().height() + top;
            return true;
        }
        return false;
    }

    private boolean adjustForMinimizedDockedStack(float minimizeAmount) {
        int dockSide = getDockSide();
        if (dockSide == -1 && !this.mTmpAdjustedBounds.isEmpty()) {
            return false;
        }
        if (dockSide == 2) {
            this.mWmService.getStableInsetsLocked(0, this.mTmpRect);
            int topInset = this.mTmpRect.top;
            this.mTmpAdjustedBounds.set(getRawBounds());
            this.mTmpAdjustedBounds.bottom = (int) ((topInset * minimizeAmount) + ((1.0f - minimizeAmount) * getRawBounds().bottom));
        } else if (dockSide == 1) {
            this.mTmpAdjustedBounds.set(getRawBounds());
            int width = getRawBounds().width();
            this.mTmpAdjustedBounds.right = (int) ((this.mDockedStackMinimizeThickness * minimizeAmount) + ((1.0f - minimizeAmount) * getRawBounds().right));
            Rect rect = this.mTmpAdjustedBounds;
            rect.left = rect.right - width;
        } else if (dockSide == 3) {
            this.mTmpAdjustedBounds.set(getRawBounds());
            this.mTmpAdjustedBounds.left = (int) (((getRawBounds().right - this.mDockedStackMinimizeThickness) * minimizeAmount) + ((1.0f - minimizeAmount) * getRawBounds().left));
        }
        return true;
    }

    private boolean isMinimizedDockAndHomeStackResizable() {
        return this.mDisplayContent.mDividerControllerLocked.isMinimizedDock() && this.mDisplayContent.mDividerControllerLocked.isHomeStackResizable();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getMinimizeDistance() {
        int dockSide = getDockSide();
        if (dockSide == -1) {
            return 0;
        }
        if (dockSide == 2) {
            this.mWmService.getStableInsetsLocked(0, this.mTmpRect);
            int topInset = this.mTmpRect.top;
            return getRawBounds().bottom - topInset;
        } else if (dockSide != 1 && dockSide != 3) {
            return 0;
        } else {
            return getRawBounds().width() - this.mDockedStackMinimizeThickness;
        }
    }

    private void updateAdjustedBounds() {
        boolean adjust = false;
        float f = this.mMinimizeAmount;
        if (f != 0.0f) {
            adjust = adjustForMinimizedDockedStack(f);
        } else if (this.mAdjustedForIme) {
            adjust = adjustForIME(this.mImeWin);
        }
        if (!adjust) {
            this.mTmpAdjustedBounds.setEmpty();
        }
        setAdjustedBounds(this.mTmpAdjustedBounds);
        boolean isImeTarget = this.mWmService.getImeFocusStackLocked() == this;
        if (this.mAdjustedForIme && adjust && !isImeTarget) {
            float alpha = Math.max(this.mAdjustImeAmount, this.mAdjustDividerAmount) * IME_ADJUST_DIM_AMOUNT;
            this.mWmService.setResizeDimLayer(true, getWindowingMode(), alpha);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyAdjustForImeIfNeeded(Task task) {
        if (this.mMinimizeAmount != 0.0f || !this.mAdjustedForIme || this.mAdjustedBounds.isEmpty()) {
            return;
        }
        Rect insetBounds = this.mImeGoingAway ? getRawBounds() : this.mFullyAdjustedImeBounds;
        task.alignToAdjustedBounds(this.mAdjustedBounds, insetBounds, getDockSide() == 2);
        this.mDisplayContent.setLayoutNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAdjustedForMinimizedDockedStack() {
        return this.mMinimizeAmount != 0.0f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTaskAnimating() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            Task task = (Task) this.mChildren.get(j);
            if (task.isTaskAnimating()) {
                return true;
            }
        }
        return false;
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel == 2 && !isVisible()) {
            return;
        }
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        proto.write(1120986464258L, this.mStackId);
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            ((Task) this.mChildren.get(taskNdx)).writeToProto(proto, 2246267895811L, logLevel);
        }
        proto.write(1133871366148L, matchParentBounds());
        getRawBounds().writeToProto(proto, 1146756268037L);
        proto.write(1133871366150L, this.mAnimationBackgroundSurfaceIsShown);
        proto.write(1133871366151L, this.mDeferRemoval);
        proto.write(1108101562376L, this.mMinimizeAmount);
        proto.write(1133871366153L, this.mAdjustedForIme);
        proto.write(1108101562378L, this.mAdjustImeAmount);
        proto.write(1108101562379L, this.mAdjustDividerAmount);
        this.mAdjustedBounds.writeToProto(proto, 1146756268044L);
        proto.write(1133871366157L, this.mBoundsAnimating);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "mStackId=" + this.mStackId);
        pw.println(prefix + "mDeferRemoval=" + this.mDeferRemoval);
        pw.println(prefix + "mBounds=" + getRawBounds().toShortString());
        if (this.mMinimizeAmount != 0.0f) {
            pw.println(prefix + "mMinimizeAmount=" + this.mMinimizeAmount);
        }
        if (this.mAdjustedForIme) {
            pw.println(prefix + "mAdjustedForIme=true");
            pw.println(prefix + "mAdjustImeAmount=" + this.mAdjustImeAmount);
            pw.println(prefix + "mAdjustDividerAmount=" + this.mAdjustDividerAmount);
        }
        if (!this.mAdjustedBounds.isEmpty()) {
            pw.println(prefix + "mAdjustedBounds=" + this.mAdjustedBounds.toShortString());
        }
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx += -1) {
            ((Task) this.mChildren.get(taskNdx)).dump(pw, prefix + "  ", dumpAll);
        }
        if (this.mAnimationBackgroundSurfaceIsShown) {
            pw.println(prefix + "mWindowAnimationBackgroundSurface is shown");
        }
        if (!this.mExitingAppTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i = this.mExitingAppTokens.size() - 1; i >= 0; i--) {
                WindowToken token = this.mExitingAppTokens.get(i);
                pw.print("  Exiting App #");
                pw.print(i);
                pw.print(' ');
                pw.print(token);
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            }
        }
        this.mAnimatingAppWindowTokenRegistry.dump(pw, "AnimatingApps:", prefix);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean fillsParent() {
        return matchParentBounds();
    }

    public String toString() {
        return "{stackId=" + this.mStackId + " tasks=" + this.mChildren + "}";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return toShortString();
    }

    public String toShortString() {
        return "Stack=" + this.mStackId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDockSide() {
        return getDockSide(this.mDisplayContent.getConfiguration(), getRawBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDockSideForDisplay(DisplayContent dc) {
        return getDockSide(dc, dc.getConfiguration(), getRawBounds());
    }

    int getDockSide(Configuration parentConfig, Rect bounds) {
        if (this.mDisplayContent == null) {
            return -1;
        }
        return getDockSide(this.mDisplayContent, parentConfig, bounds);
    }

    private int getDockSide(DisplayContent dc, Configuration parentConfig, Rect bounds) {
        return dc.getDockedDividerController().getDockSide(bounds, parentConfig.windowConfiguration.getBounds(), parentConfig.orientation, parentConfig.windowConfiguration.getRotation());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasTaskForUser(int userId) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            Task task = (Task) this.mChildren.get(i);
            if (task.mUserId == userId) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskForResizePoint(int x, int y, int delta, DisplayContent.TaskForResizePointSearchResult results) {
        if (!getWindowConfiguration().canResizeTask()) {
            results.searchDone = true;
            return;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            Task task = (Task) this.mChildren.get(i);
            if (task.getWindowingMode() == 1) {
                results.searchDone = true;
                return;
            }
            task.getDimBounds(this.mTmpRect);
            this.mTmpRect.inset(-delta, -delta);
            if (this.mTmpRect.contains(x, y)) {
                this.mTmpRect.inset(delta, delta);
                results.searchDone = true;
                if (!this.mTmpRect.contains(x, y)) {
                    results.taskForResize = task;
                    return;
                }
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTouchExcludeRegion(Task focusedTask, int delta, Region touchExcludeRegion, Rect contentRect, Rect postExclude) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            Task task = (Task) this.mChildren.get(i);
            AppWindowToken token = task.getTopVisibleAppToken();
            if (token != null && token.hasContentToDisplay()) {
                if (task.isActivityTypeHome() && isMinimizedDockAndHomeStackResizable()) {
                    this.mDisplayContent.getBounds(this.mTmpRect);
                } else {
                    task.getDimBounds(this.mTmpRect);
                }
                if (task == focusedTask) {
                    postExclude.set(this.mTmpRect);
                }
                boolean isFreeformed = task.inFreeformWindowingMode();
                if (task != focusedTask || isFreeformed) {
                    if (isFreeformed) {
                        this.mTmpRect.inset(-delta, -delta);
                        this.mTmpRect.intersect(contentRect);
                    }
                    touchExcludeRegion.op(this.mTmpRect, Region.Op.DIFFERENCE);
                }
            }
        }
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean setPinnedStackSize(Rect stackBounds, Rect tempTaskBounds) {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mCancelCurrentBoundsAnimation) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                try {
                    this.mWmService.mActivityTaskManager.resizePinnedStack(stackBounds, tempTaskBounds);
                    return true;
                } catch (RemoteException e) {
                    return true;
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAllWindowsDrawn() {
        if (!this.mBoundsAnimating && !this.mBoundsAnimatingRequested) {
            return;
        }
        getDisplayContent().mBoundsAnimationController.onAllWindowsDrawn();
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate, @BoundsAnimationController.AnimationType int animationType) {
        ActivityStack activityStack;
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!isAttached()) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                this.mBoundsAnimatingRequested = false;
                this.mBoundsAnimating = true;
                this.mAnimationType = animationType;
                if (schedulePipModeChangedCallback) {
                    forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskStack$NPerlV3pAikqmRCCx3JO0qCLTyw
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            ((WindowState) obj).mWinAnimator.resetDrawState();
                        }
                    }, false);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                if (inPinnedWindowingMode()) {
                    try {
                        this.mWmService.mActivityTaskManager.notifyPinnedStackAnimationStarted();
                    } catch (RemoteException e) {
                    }
                    if ((schedulePipModeChangedCallback || animationType == 1) && (activityStack = this.mActivityStack) != null) {
                        activityStack.updatePictureInPictureModeForPinnedStackAnimation(null, forceUpdate);
                    }
                }
                return true;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackSize, boolean moveToFullscreen) {
        if (inPinnedWindowingMode()) {
            if (schedulePipModeChangedCallback) {
                this.mActivityStack.updatePictureInPictureModeForPinnedStackAnimation(this.mBoundsAnimationTarget, false);
            }
            if (this.mAnimationType == 1) {
                setPinnedStackAlpha(1.0f);
                this.mActivityStack.mService.notifyPinnedStackAnimationEnded();
                return;
            }
            if (finalStackSize != null && !this.mCancelCurrentBoundsAnimation) {
                setPinnedStackSize(finalStackSize, null);
            } else {
                onPipAnimationEndResize();
            }
            this.mActivityStack.mService.notifyPinnedStackAnimationEnded();
            if (moveToFullscreen) {
                this.mActivityStack.mService.moveTasksToFullscreenStack(this.mStackId, true);
                return;
            }
            return;
        }
        onPipAnimationEndResize();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getPictureInPictureBounds(float aspectRatio, Rect stackBounds) {
        DisplayContent displayContent;
        if (this.mWmService.mSupportsPictureInPicture && (displayContent = getDisplayContent()) != null && inPinnedWindowingMode()) {
            PinnedStackController pinnedStackController = displayContent.getPinnedStackController();
            if (stackBounds == null) {
                stackBounds = pinnedStackController.getDefaultOrLastSavedBounds();
            }
            if (pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)) {
                return pinnedStackController.transformBoundsToAspectRatio(stackBounds, aspectRatio, true);
            }
            return stackBounds;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void animateResizePinnedStack(Rect toBounds, Rect sourceHintBounds, final int animationDuration, final boolean fromFullscreen) {
        Rect toBounds2;
        int schedulePipModeChangedState;
        int intendedAnimationType;
        if (!inPinnedWindowingMode()) {
            return;
        }
        final Rect fromBounds = new Rect();
        getBounds(fromBounds);
        final boolean toFullscreen = toBounds == null;
        if (toFullscreen) {
            if (!fromFullscreen) {
                this.mWmService.getStackBounds(1, 1, this.mTmpToBounds);
                if (!this.mTmpToBounds.isEmpty()) {
                    schedulePipModeChangedState = 1;
                    toBounds2 = new Rect(this.mTmpToBounds);
                } else {
                    Rect toBounds3 = new Rect();
                    getDisplayContent().getBounds(toBounds3);
                    schedulePipModeChangedState = 1;
                    toBounds2 = toBounds3;
                }
            } else {
                throw new IllegalArgumentException("Should not defer scheduling PiP mode change on animation to fullscreen.");
            }
        } else if (!fromFullscreen) {
            toBounds2 = toBounds;
            schedulePipModeChangedState = 0;
        } else {
            toBounds2 = toBounds;
            schedulePipModeChangedState = 2;
        }
        setAnimationFinalBounds(sourceHintBounds, toBounds2, toFullscreen);
        final Rect finalToBounds = toBounds2;
        final int finalSchedulePipModeChangedState = schedulePipModeChangedState;
        final DisplayContent displayContent = getDisplayContent();
        int intendedAnimationType2 = displayContent.mBoundsAnimationController.getAnimationType();
        if (intendedAnimationType2 == 1) {
            if (fromFullscreen) {
                setPinnedStackAlpha(0.0f);
            }
            if (toBounds2.width() == fromBounds.width() && toBounds2.height() == fromBounds.height()) {
                intendedAnimationType = 0;
            } else if (!fromFullscreen && !toBounds2.equals(fromBounds)) {
                intendedAnimationType = 0;
            }
            final int animationType = intendedAnimationType;
            this.mCancelCurrentBoundsAnimation = false;
            displayContent.mBoundsAnimationController.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskStack$Vzix6ElfYqr96C0Kgjxo_MdVpAg
                @Override // java.lang.Runnable
                public final void run() {
                    TaskStack.this.lambda$animateResizePinnedStack$3$TaskStack(displayContent, fromBounds, finalToBounds, animationDuration, finalSchedulePipModeChangedState, fromFullscreen, toFullscreen, animationType);
                }
            });
        }
        intendedAnimationType = intendedAnimationType2;
        final int animationType2 = intendedAnimationType;
        this.mCancelCurrentBoundsAnimation = false;
        displayContent.mBoundsAnimationController.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskStack$Vzix6ElfYqr96C0Kgjxo_MdVpAg
            @Override // java.lang.Runnable
            public final void run() {
                TaskStack.this.lambda$animateResizePinnedStack$3$TaskStack(displayContent, fromBounds, finalToBounds, animationDuration, finalSchedulePipModeChangedState, fromFullscreen, toFullscreen, animationType2);
            }
        });
    }

    public /* synthetic */ void lambda$animateResizePinnedStack$3$TaskStack(DisplayContent displayContent, Rect fromBounds, Rect finalToBounds, int animationDuration, int finalSchedulePipModeChangedState, boolean fromFullscreen, boolean toFullscreen, int animationType) {
        displayContent.mBoundsAnimationController.animateBounds(this, fromBounds, finalToBounds, animationDuration, finalSchedulePipModeChangedState, fromFullscreen, toFullscreen, animationType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureAspectRatio(float aspectRatio) {
        if (!this.mWmService.mSupportsPictureInPicture || !inPinnedWindowingMode()) {
            return;
        }
        PinnedStackController pinnedStackController = getDisplayContent().getPinnedStackController();
        if (Float.compare(aspectRatio, pinnedStackController.getAspectRatio()) == 0) {
            return;
        }
        getAnimationOrCurrentBounds(this.mTmpFromBounds);
        this.mTmpToBounds.set(this.mTmpFromBounds);
        getPictureInPictureBounds(aspectRatio, this.mTmpToBounds);
        if (!this.mTmpToBounds.equals(this.mTmpFromBounds)) {
            animateResizePinnedStack(this.mTmpToBounds, null, -1, false);
        }
        pinnedStackController.setAspectRatio(pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio) ? aspectRatio : -1.0f);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureActions(List<RemoteAction> actions) {
        if (!this.mWmService.mSupportsPictureInPicture || !inPinnedWindowingMode()) {
            return;
        }
        getDisplayContent().getPinnedStackController().setActions(actions);
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean isAttached() {
        boolean z;
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                z = this.mDisplayContent != null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public void onPipAnimationEndResize() {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mBoundsAnimating = false;
                for (int i = 0; i < this.mChildren.size(); i++) {
                    Task t = (Task) this.mChildren.get(i);
                    t.clearPreserveNonFloatingState();
                }
                this.mWmService.requestTraversal();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean shouldDeferStartOnMoveToFullscreen() {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!isAttached()) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                TaskStack homeStack = this.mDisplayContent.getHomeStack();
                if (homeStack == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                }
                Task homeTask = homeStack.getTopChild();
                if (homeTask == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                }
                AppWindowToken homeApp = homeTask.getTopVisibleAppToken();
                if (homeTask.isVisible() && homeApp != null) {
                    boolean z = homeApp.allDrawn ? false : true;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return z;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return true;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean deferScheduleMultiWindowModeChanged() {
        if (inPinnedWindowingMode()) {
            return this.mBoundsAnimatingRequested || this.mBoundsAnimating;
        }
        return false;
    }

    public boolean isForceScaled() {
        return this.mBoundsAnimating;
    }

    public boolean isAnimatingBounds() {
        return this.mBoundsAnimating;
    }

    public boolean lastAnimatingBoundsWasToFullscreen() {
        return this.mBoundsAnimatingToFullscreen;
    }

    public boolean isAnimatingBoundsToFullscreen() {
        return isAnimatingBounds() && lastAnimatingBoundsWasToFullscreen();
    }

    public boolean pinnedStackResizeDisallowed() {
        if (this.mBoundsAnimating && this.mCancelCurrentBoundsAnimation) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean checkCompleteDeferredRemoval() {
        if (isSelfOrChildAnimating()) {
            return true;
        }
        if (this.mDeferRemoval) {
            removeImmediately();
        }
        return super.checkCompleteDeferredRemoval();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public int getOrientation() {
        if (canSpecifyOrientation()) {
            return super.getOrientation();
        }
        return -2;
    }

    private boolean canSpecifyOrientation() {
        int windowingMode = getWindowingMode();
        int activityType = getActivityType();
        return windowingMode == 1 || activityType == 2 || activityType == 3 || activityType == 4;
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

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean setPinnedStackAlpha(float alpha) {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                SurfaceControl sc = getSurfaceControl();
                if (sc != null && sc.isValid()) {
                    getPendingTransaction().setAlpha(sc, this.mCancelCurrentBoundsAnimation ? 1.0f : alpha);
                    scheduleAnimation();
                    boolean z = this.mCancelCurrentBoundsAnimation ? false : true;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return z;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public DisplayInfo getDisplayInfo() {
        return this.mDisplayContent.getDisplayInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dim(float alpha) {
        this.mDimmer.dimAbove(getPendingTransaction(), alpha);
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopDimming() {
        this.mDimmer.stopDim(getPendingTransaction());
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AnimatingAppWindowTokenRegistry getAnimatingAppWindowTokenRegistry() {
        return this.mAnimatingAppWindowTokenRegistry;
    }
}
