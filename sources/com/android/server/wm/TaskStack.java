package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.EventLogTags;
import com.android.server.wm.DisplayContent;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class TaskStack extends WindowContainer<Task> implements BoundsAnimationTarget {
    private static final float ADJUSTED_STACK_FRACTION_MIN = 0.3f;
    private static final float IME_ADJUST_DIM_AMOUNT = 0.25f;
    private float mAdjustDividerAmount;
    private float mAdjustImeAmount;
    private final Rect mAdjustedBounds;
    private boolean mAdjustedForIme;
    private final AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry;
    private WindowStateAnimator mAnimationBackgroundAnimator;
    private SurfaceControl mAnimationBackgroundSurface;
    private boolean mAnimationBackgroundSurfaceIsShown;
    private final Rect mBoundsAfterRotation;
    private boolean mBoundsAnimating;
    private boolean mBoundsAnimatingRequested;
    private boolean mBoundsAnimatingToFullscreen;
    private Rect mBoundsAnimationSourceHintBounds;
    private Rect mBoundsAnimationTarget;
    private boolean mCancelCurrentBoundsAnimation;
    boolean mDeferRemoval;
    private int mDensity;
    private Dimmer mDimmer;
    private DisplayContent mDisplayContent;
    private final int mDockedStackMinimizeThickness;
    final AppTokenList mExitingAppTokens;
    private final Rect mFullyAdjustedImeBounds;
    private boolean mImeGoingAway;
    private WindowState mImeWin;
    private final Point mLastSurfaceSize;
    private float mMinimizeAmount;
    Rect mPreAnimationBounds;
    private int mRotation;
    final int mStackId;
    private final Rect mTmpAdjustedBounds;
    final AppTokenList mTmpAppTokens;
    final Rect mTmpDimBoundsRect;
    private Rect mTmpRect;
    private Rect mTmpRect2;
    private Rect mTmpRect3;

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
    public /* bridge */ /* synthetic */ void onAnimationLeashDestroyed(SurfaceControl.Transaction transaction) {
        super.onAnimationLeashDestroyed(transaction);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public /* bridge */ /* synthetic */ void onOverrideConfigurationChanged(Configuration configuration) {
        super.onOverrideConfigurationChanged(configuration);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack(WindowManagerService service, int stackId, StackWindowController controller) {
        super(service);
        this.mTmpRect = new Rect();
        this.mTmpRect2 = new Rect();
        this.mTmpRect3 = new Rect();
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
        this.mBoundsAfterRotation = new Rect();
        this.mPreAnimationBounds = new Rect();
        this.mDimmer = new Dimmer(this);
        this.mTmpDimBoundsRect = new Rect();
        this.mLastSurfaceSize = new Point();
        this.mAnimatingAppWindowTokenRegistry = new AnimatingAppWindowTokenRegistry();
        this.mStackId = stackId;
        setController(controller);
        this.mDockedStackMinimizeThickness = service.mContext.getResources().getDimensionPixelSize(17105055);
        EventLog.writeEvent((int) EventLogTags.WM_STACK_CREATED, stackId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getDisplayContent() {
        return this.mDisplayContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task findHomeTask() {
        if (!isActivityTypeHome() || this.mChildren.isEmpty()) {
            return null;
        }
        return (Task) this.mChildren.get(this.mChildren.size() - 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setBounds(Rect stackBounds, SparseArray<Rect> taskBounds, SparseArray<Rect> taskTempInsetBounds) {
        setBounds(stackBounds);
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            Task task = (Task) this.mChildren.get(taskNdx);
            task.setBounds(taskBounds.get(task.mTaskId), false);
            task.setTempInsetBounds(taskTempInsetBounds != null ? taskTempInsetBounds.get(task.mTaskId) : null);
        }
        return true;
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
        int taskNdx = this.mChildren.size() - 1;
        while (true) {
            int taskNdx2 = taskNdx;
            if (taskNdx2 >= 0) {
                Task task = (Task) this.mChildren.get(taskNdx2);
                task.alignToAdjustedBounds(adjustedBounds, tempInsetBounds, alignBottom);
                taskNdx = taskNdx2 - 1;
            } else {
                return;
            }
        }
    }

    private void updateAnimationBackgroundBounds() {
        if (this.mAnimationBackgroundSurface == null) {
            return;
        }
        getRawBounds(this.mTmpRect);
        Rect stackBounds = getBounds();
        getPendingTransaction().setSize(this.mAnimationBackgroundSurface, this.mTmpRect.width(), this.mTmpRect.height()).setPosition(this.mAnimationBackgroundSurface, this.mTmpRect.left - stackBounds.left, this.mTmpRect.top - stackBounds.top);
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
        return setBounds(getOverrideBounds(), bounds);
    }

    private int setBounds(Rect existing, Rect bounds) {
        int rotation = 0;
        int density = 0;
        if (this.mDisplayContent != null) {
            this.mDisplayContent.getBounds(this.mTmpRect);
            rotation = this.mDisplayContent.getDisplayInfo().rotation;
            density = this.mDisplayContent.getDisplayInfo().logicalDensityDpi;
        }
        if (equivalentBounds(existing, bounds) && this.mRotation == rotation) {
            return 0;
        }
        int result = super.setBounds(bounds);
        if (this.mDisplayContent != null) {
            updateAnimationBackgroundBounds();
        }
        this.mRotation = rotation;
        this.mDensity = density;
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

    private boolean useCurrentBounds() {
        if (matchParentBounds() || !inSplitScreenSecondaryWindowingMode() || this.mDisplayContent == null || this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null) {
            return true;
        }
        return false;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Rect getBounds() {
        if (useCurrentBounds()) {
            if (!this.mAdjustedBounds.isEmpty()) {
                return this.mAdjustedBounds;
            }
            return super.getBounds();
        }
        return this.mDisplayContent.getBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAnimationFinalBounds(Rect sourceHintBounds, Rect destBounds, boolean toFullscreen) {
        this.mBoundsAnimatingRequested = true;
        this.mBoundsAnimatingToFullscreen = toFullscreen;
        if (destBounds != null) {
            this.mBoundsAnimationTarget.set(destBounds);
        } else {
            this.mBoundsAnimationTarget.setEmpty();
        }
        if (sourceHintBounds != null) {
            this.mBoundsAnimationSourceHintBounds.set(sourceHintBounds);
        } else {
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
    public void updateDisplayInfo(Rect bounds) {
        if (this.mDisplayContent == null) {
            return;
        }
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            ((Task) this.mChildren.get(taskNdx)).updateDisplayInfo(this.mDisplayContent);
        }
        if (bounds != null) {
            setBounds(bounds);
        } else if (matchParentBounds()) {
            setBounds(null);
        } else {
            this.mTmpRect2.set(getRawBounds());
            int newRotation = this.mDisplayContent.getDisplayInfo().rotation;
            int newDensity = this.mDisplayContent.getDisplayInfo().logicalDensityDpi;
            if (this.mRotation == newRotation && this.mDensity == newDensity) {
                setBounds(this.mTmpRect2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateBoundsAfterConfigChange() {
        int i = 0;
        if (this.mDisplayContent == null) {
            return false;
        }
        if (inPinnedWindowingMode()) {
            getAnimationOrCurrentBounds(this.mTmpRect2);
            boolean updated = this.mDisplayContent.mPinnedStackControllerLocked.onTaskStackBoundsChanged(this.mTmpRect2, this.mTmpRect3);
            if (updated) {
                this.mBoundsAfterRotation.set(this.mTmpRect3);
                this.mBoundsAnimationTarget.setEmpty();
                this.mBoundsAnimationSourceHintBounds.setEmpty();
                this.mCancelCurrentBoundsAnimation = true;
                return true;
            }
        }
        int newRotation = getDisplayInfo().rotation;
        int newDensity = getDisplayInfo().logicalDensityDpi;
        if (this.mRotation == newRotation && this.mDensity == newDensity) {
            return false;
        }
        if (matchParentBounds()) {
            setBounds(null);
            return false;
        }
        this.mTmpRect2.set(getRawBounds());
        this.mDisplayContent.rotateBounds(this.mRotation, newRotation, this.mTmpRect2);
        if (inSplitScreenPrimaryWindowingMode()) {
            repositionPrimarySplitScreenStackAfterRotation(this.mTmpRect2);
            snapDockedStackAfterRotation(this.mTmpRect2);
            int newDockSide = getDockSide(this.mTmpRect2);
            WindowManagerService windowManagerService = this.mService;
            if (newDockSide != 1 && newDockSide != 2) {
                i = 1;
            }
            windowManagerService.setDockedStackCreateStateLocked(i, null);
            this.mDisplayContent.getDockedDividerController().notifyDockSideChanged(newDockSide);
        }
        this.mBoundsAfterRotation.set(this.mTmpRect2);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getBoundsForNewConfiguration(Rect outBounds) {
        outBounds.set(this.mBoundsAfterRotation);
        this.mBoundsAfterRotation.setEmpty();
    }

    private void repositionPrimarySplitScreenStackAfterRotation(Rect inOutBounds) {
        int dockSide = getDockSide(inOutBounds);
        if (this.mDisplayContent.getDockedDividerController().canPrimaryStackDockTo(dockSide)) {
            return;
        }
        this.mDisplayContent.getBounds(this.mTmpRect);
        switch (DockedDividerUtils.invertDockSide(dockSide)) {
            case 1:
                int movement = inOutBounds.left;
                inOutBounds.left -= movement;
                inOutBounds.right -= movement;
                return;
            case 2:
                int movement2 = inOutBounds.top;
                inOutBounds.top -= movement2;
                inOutBounds.bottom -= movement2;
                return;
            case 3:
                int movement3 = this.mTmpRect.right - inOutBounds.right;
                inOutBounds.left += movement3;
                inOutBounds.right += movement3;
                return;
            case 4:
                int movement4 = this.mTmpRect.bottom - inOutBounds.bottom;
                inOutBounds.top += movement4;
                inOutBounds.bottom += movement4;
                return;
            default:
                return;
        }
    }

    private void snapDockedStackAfterRotation(Rect outBounds) {
        DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
        int dividerSize = this.mDisplayContent.getDockedDividerController().getContentWidth();
        int dockSide = getDockSide(outBounds);
        int dividerPosition = DockedDividerUtils.calculatePositionForBounds(outBounds, dockSide, dividerSize);
        int displayWidth = displayInfo.logicalWidth;
        int displayHeight = displayInfo.logicalHeight;
        int rotation = displayInfo.rotation;
        int orientation = this.mDisplayContent.getConfiguration().orientation;
        this.mService.mPolicy.getStableInsetsLw(rotation, displayWidth, displayHeight, displayInfo.displayCutout, outBounds);
        DividerSnapAlgorithm algorithm = new DividerSnapAlgorithm(this.mService.mContext.getResources(), displayWidth, displayHeight, dividerSize, orientation == 1, outBounds, getDockSide(), isMinimizedDockAndHomeStackResizable());
        DividerSnapAlgorithm.SnapTarget target = algorithm.calculateNonDismissingSnapTarget(dividerPosition);
        DockedDividerUtils.calculateBoundsForPosition(target.position, dockSide, outBounds, displayInfo.logicalWidth, displayInfo.logicalHeight, dividerSize);
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

    private int findPositionForTask(Task task, int targetPosition, boolean showForAllUsers, boolean addingNew) {
        boolean canShowTask = showForAllUsers || this.mService.isCurrentProfileLocked(task.mUserId);
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
            boolean canShowTmpTask = tmpTask.showForAllUsers() || this.mService.isCurrentProfileLocked(tmpTask.mUserId);
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
            boolean canShowTmpTask = tmpTask.showForAllUsers() || this.mService.isCurrentProfileLocked(tmpTask.mUserId);
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
        if (this.mDisplayContent == null || prevWindowingMode == windowingMode) {
            return;
        }
        this.mDisplayContent.onStackWindowingModeChanged(this);
        updateBoundsForWindowModeChange();
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
            WindowManagerService windowManagerService = this.mService;
            return (int) Math.ceil(WindowManagerService.dipToPixel(5, displayMetrics) * 2);
        }
        return 0;
    }

    private void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (this.mSurfaceControl == null) {
            return;
        }
        Rect stackBounds = getBounds();
        int width = stackBounds.width();
        int height = stackBounds.height();
        int outset = getStackOutset();
        int width2 = width + (2 * outset);
        int height2 = height + (2 * outset);
        if (width2 == this.mLastSurfaceSize.x && height2 == this.mLastSurfaceSize.y) {
            return;
        }
        transaction.setSize(this.mSurfaceControl, width2, height2);
        this.mLastSurfaceSize.set(width2, height2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onDisplayChanged(DisplayContent dc) {
        if (this.mDisplayContent != null) {
            throw new IllegalStateException("onDisplayChanged: Already attached");
        }
        this.mDisplayContent = dc;
        updateBoundsForWindowModeChange();
        SurfaceControl.Builder colorLayer = makeChildSurface(null).setColorLayer(true);
        this.mAnimationBackgroundSurface = colorLayer.setName("animation background stackId=" + this.mStackId).build();
        super.onDisplayChanged(dc);
    }

    private void updateBoundsForWindowModeChange() {
        Rect bounds = calculateBoundsForWindowModeChange();
        if (inSplitScreenSecondaryWindowingMode()) {
            forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskStack$0Cm5zc_NsRa5nGarFvrp2KYfUYU
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((WindowState) obj).mWinAnimator.setOffsetPositionForStackResize(true);
                }
            }, true);
        }
        updateDisplayInfo(bounds);
        updateSurfaceBounds();
    }

    private Rect calculateBoundsForWindowModeChange() {
        boolean inSplitScreenPrimary = inSplitScreenPrimaryWindowingMode();
        TaskStack splitScreenStack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (inSplitScreenPrimary || (splitScreenStack != null && inSplitScreenSecondaryWindowingMode() && !splitScreenStack.fillsParent())) {
            Rect bounds = new Rect();
            this.mDisplayContent.getBounds(this.mTmpRect);
            this.mTmpRect2.setEmpty();
            if (splitScreenStack != null) {
                if (inSplitScreenSecondaryWindowingMode() && this.mDisplayContent.mDividerControllerLocked.isMinimizedDock() && splitScreenStack.getTopChild() != null) {
                    splitScreenStack.getTopChild().getBounds(this.mTmpRect2);
                } else {
                    splitScreenStack.getRawBounds(this.mTmpRect2);
                }
            }
            boolean dockedOnTopOrLeft = this.mService.mDockedStackCreateMode == 0;
            getStackDockedModeBounds(this.mTmpRect, bounds, this.mTmpRect2, this.mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
            return bounds;
        } else if (inPinnedWindowingMode()) {
            getAnimationOrCurrentBounds(this.mTmpRect2);
            if (this.mDisplayContent.mPinnedStackControllerLocked.onTaskStackBoundsChanged(this.mTmpRect2, this.mTmpRect3)) {
                return new Rect(this.mTmpRect3);
            }
            return null;
        } else {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStackDockedModeBoundsLocked(Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds, boolean ignoreVisibility) {
        outTempTaskBounds.setEmpty();
        if (isActivityTypeHome()) {
            Task homeTask = findHomeTask();
            if (homeTask != null && homeTask.isResizeable()) {
                getDisplayContent().mDividerControllerLocked.getHomeStackBoundsInDockedMode(outStackBounds);
            } else {
                outStackBounds.setEmpty();
            }
            outTempTaskBounds.set(outStackBounds);
        } else if (isMinimizedDockAndHomeStackResizable() && currentTempTaskBounds != null) {
            outStackBounds.set(currentTempTaskBounds);
        } else if (!inSplitScreenWindowingMode() || this.mDisplayContent == null) {
            outStackBounds.set(getRawBounds());
        } else {
            TaskStack dockedStack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            if (dockedStack == null) {
                throw new IllegalStateException("Calling getStackDockedModeBoundsLocked() when there is no docked stack.");
            }
            if (!ignoreVisibility && !dockedStack.isVisible()) {
                this.mDisplayContent.getBounds(outStackBounds);
                return;
            }
            int dockedSide = dockedStack.getDockSide();
            if (dockedSide == -1) {
                Slog.e("WindowManager", "Failed to get valid docked side for docked stack=" + dockedStack);
                outStackBounds.set(getRawBounds());
                return;
            }
            this.mDisplayContent.getBounds(this.mTmpRect);
            dockedStack.getRawBounds(this.mTmpRect2);
            boolean z = true;
            if (dockedSide != 2 && dockedSide != 1) {
                z = false;
            }
            boolean dockedOnTopOrLeft = z;
            getStackDockedModeBounds(this.mTmpRect, outStackBounds, this.mTmpRect2, this.mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
        }
    }

    private void getStackDockedModeBounds(Rect displayRect, Rect outBounds, Rect dockedBounds, int dockDividerWidth, boolean dockOnTopOrLeft) {
        boolean dockedStack = inSplitScreenPrimaryWindowingMode();
        boolean splitHorizontally = displayRect.width() > displayRect.height();
        outBounds.set(displayRect);
        if (dockedStack) {
            if (this.mService.mDockedStackCreateBounds != null) {
                outBounds.set(this.mService.mDockedStackCreateBounds);
                return;
            }
            DisplayInfo di = this.mDisplayContent.getDisplayInfo();
            this.mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, di.displayCutout, this.mTmpRect2);
            int position = new DividerSnapAlgorithm(this.mService.mContext.getResources(), di.logicalWidth, di.logicalHeight, dockDividerWidth, this.mDisplayContent.getConfiguration().orientation == 1, this.mTmpRect2).getMiddleTarget().position;
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
        this.mService.mDockedStackCreateBounds = null;
        Rect bounds = new Rect();
        Rect tempBounds = new Rect();
        getStackDockedModeBoundsLocked(null, bounds, tempBounds, true);
        getController().requestResize(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public StackWindowController getController() {
        return (StackWindowController) super.getController();
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
    public void onParentSet() {
        super.onParentSet();
        if (getParent() != null || this.mDisplayContent == null) {
            return;
        }
        EventLog.writeEvent((int) EventLogTags.WM_STACK_REMOVED, this.mStackId);
        if (this.mAnimationBackgroundSurface != null) {
            this.mAnimationBackgroundSurface.destroy();
            this.mAnimationBackgroundSurface = null;
        }
        this.mDisplayContent = null;
        this.mService.mWindowPlacerLocked.requestTraversal();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetAnimationBackgroundAnimator() {
        this.mAnimationBackgroundAnimator = null;
        hideAnimationSurface();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAnimationBackground(WindowStateAnimator winAnimator, int color) {
        int animLayer = winAnimator.mAnimLayer;
        if (this.mAnimationBackgroundAnimator == null || animLayer < this.mAnimationBackgroundAnimator.mAnimLayer) {
            this.mAnimationBackgroundAnimator = winAnimator;
            this.mDisplayContent.getLayerForAnimationBackground(winAnimator);
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
            if (this.mService.isCurrentProfileLocked(task.mUserId) || task.showForAllUsers()) {
                this.mChildren.remove(taskNdx);
                this.mChildren.add(task);
                top--;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAdjustedForIme(WindowState imeWin, boolean forceUpdate) {
        this.mImeWin = imeWin;
        this.mImeGoingAway = false;
        if (!this.mAdjustedForIme || forceUpdate) {
            this.mAdjustedForIme = true;
            this.mAdjustImeAmount = 0.0f;
            this.mAdjustDividerAmount = 0.0f;
            updateAdjustForIme(0.0f, 0.0f, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAdjustedForIme() {
        return this.mAdjustedForIme;
    }

    boolean isAnimatingForIme() {
        return this.mImeWin != null && this.mImeWin.isAnimatingLw();
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
            this.mService.setResizeDimLayer(false, getWindowingMode(), 1.0f);
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
                this.mTmpAdjustedBounds.bottom = (int) ((this.mAdjustImeAmount * bottom) + ((1.0f - this.mAdjustImeAmount) * getRawBounds().bottom));
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
            this.mFullyAdjustedImeBounds.top = top;
            this.mFullyAdjustedImeBounds.bottom = getRawBounds().height() + top;
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
            this.mService.getStableInsetsLocked(0, this.mTmpRect);
            int topInset = this.mTmpRect.top;
            this.mTmpAdjustedBounds.set(getRawBounds());
            this.mTmpAdjustedBounds.bottom = (int) ((topInset * minimizeAmount) + ((1.0f - minimizeAmount) * getRawBounds().bottom));
        } else if (dockSide == 1) {
            this.mTmpAdjustedBounds.set(getRawBounds());
            int width = getRawBounds().width();
            this.mTmpAdjustedBounds.right = (int) ((this.mDockedStackMinimizeThickness * minimizeAmount) + ((1.0f - minimizeAmount) * getRawBounds().right));
            this.mTmpAdjustedBounds.left = this.mTmpAdjustedBounds.right - width;
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
            this.mService.getStableInsetsLocked(0, this.mTmpRect);
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
        if (this.mMinimizeAmount != 0.0f) {
            adjust = adjustForMinimizedDockedStack(this.mMinimizeAmount);
        } else if (this.mAdjustedForIme) {
            adjust = adjustForIME(this.mImeWin);
        }
        if (!adjust) {
            this.mTmpAdjustedBounds.setEmpty();
        }
        setAdjustedBounds(this.mTmpAdjustedBounds);
        boolean isImeTarget = this.mService.getImeFocusStackLocked() == this;
        if (this.mAdjustedForIme && adjust && !isImeTarget) {
            float alpha = Math.max(this.mAdjustImeAmount, this.mAdjustDividerAmount) * IME_ADJUST_DIM_AMOUNT;
            this.mService.setResizeDimLayer(true, getWindowingMode(), alpha);
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
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        proto.write(1120986464258L, this.mStackId);
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            ((Task) this.mChildren.get(taskNdx)).writeToProto(proto, 2246267895811L, trim);
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
        if (useCurrentBounds()) {
            return matchParentBounds();
        }
        return true;
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
        return getDockSide(getRawBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDockSideForDisplay(DisplayContent dc) {
        return getDockSide(dc, getRawBounds());
    }

    private int getDockSide(Rect bounds) {
        if (this.mDisplayContent == null) {
            return -1;
        }
        return getDockSide(this.mDisplayContent, bounds);
    }

    private int getDockSide(DisplayContent dc, Rect bounds) {
        if (!inSplitScreenWindowingMode()) {
            return -1;
        }
        dc.getBounds(this.mTmpRect);
        int orientation = dc.getConfiguration().orientation;
        return dc.getDockedDividerController().getDockSide(bounds, this.mTmpRect, orientation);
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
    public int taskIdFromPoint(int x, int y) {
        getBounds(this.mTmpRect);
        if (!this.mTmpRect.contains(x, y) || isAdjustedForMinimizedDockedStack()) {
            return -1;
        }
        for (int taskNdx = this.mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            Task task = (Task) this.mChildren.get(taskNdx);
            WindowState win = task.getTopVisibleAppMainWindow();
            if (win != null) {
                task.getDimBounds(this.mTmpRect);
                if (this.mTmpRect.contains(x, y)) {
                    return task.mTaskId;
                }
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskForResizePoint(int x, int y, int delta, DisplayContent.TaskForResizePointSearchResult results) {
        if (!getWindowConfiguration().canResizeTask()) {
            results.searchDone = true;
            return;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            Task task = (Task) this.mChildren.get(i);
            if (task.isFullscreen()) {
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
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mCancelCurrentBoundsAnimation) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                try {
                    this.mService.mActivityManager.resizePinnedStack(stackBounds, tempTaskBounds);
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
        this.mService.mBoundsAnimationController.onAllWindowsDrawn();
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public void onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate) {
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mBoundsAnimatingRequested = false;
                this.mBoundsAnimating = true;
                this.mCancelCurrentBoundsAnimation = false;
                if (schedulePipModeChangedCallback) {
                    forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskStack$n0sDe5GcitIQB-Orca4W45Hcc98
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            ((WindowState) obj).mWinAnimator.resetDrawState();
                        }
                    }, false);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (inPinnedWindowingMode()) {
            try {
                this.mService.mActivityManager.notifyPinnedStackAnimationStarted();
            } catch (RemoteException e) {
            }
            PinnedStackWindowController controller = (PinnedStackWindowController) getController();
            if (schedulePipModeChangedCallback && controller != null) {
                controller.updatePictureInPictureModeForPinnedStackAnimation(null, forceUpdate);
            }
        }
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackSize, boolean moveToFullscreen) {
        if (inPinnedWindowingMode()) {
            PinnedStackWindowController controller = (PinnedStackWindowController) getController();
            if (schedulePipModeChangedCallback && controller != null) {
                controller.updatePictureInPictureModeForPinnedStackAnimation(this.mBoundsAnimationTarget, false);
            }
            if (finalStackSize != null) {
                setPinnedStackSize(finalStackSize, null);
            } else {
                onPipAnimationEndResize();
            }
            try {
                this.mService.mActivityManager.notifyPinnedStackAnimationEnded();
                if (moveToFullscreen) {
                    this.mService.mActivityManager.moveTasksToFullscreenStack(this.mStackId, true);
                    return;
                }
                return;
            } catch (RemoteException e) {
                return;
            }
        }
        onPipAnimationEndResize();
    }

    public void onPipAnimationEndResize() {
        this.mBoundsAnimating = false;
        for (int i = 0; i < this.mChildren.size(); i++) {
            Task t = (Task) this.mChildren.get(i);
            t.clearPreserveNonFloatingState();
        }
        this.mService.requestTraversal();
    }

    @Override // com.android.server.wm.BoundsAnimationTarget
    public boolean shouldDeferStartOnMoveToFullscreen() {
        Task homeTask;
        TaskStack homeStack = this.mDisplayContent.getHomeStack();
        if (homeStack == null || (homeTask = homeStack.getTopChild()) == null) {
            return true;
        }
        AppWindowToken homeApp = homeTask.getTopVisibleAppToken();
        if (!homeTask.isVisible() || homeApp == null) {
            return true;
        }
        return true ^ homeApp.allDrawn;
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
    @Override // com.android.server.wm.WindowContainer
    public void getRelativePosition(Point outPos) {
        super.getRelativePosition(outPos);
        int outset = getStackOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AnimatingAppWindowTokenRegistry getAnimatingAppWindowTokenRegistry() {
        return this.mAnimatingAppWindowTokenRegistry;
    }
}
