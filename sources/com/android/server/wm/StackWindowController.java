package com.android.server.wm;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import com.android.internal.annotations.VisibleForTesting;
import java.lang.ref.WeakReference;
/* loaded from: classes.dex */
public class StackWindowController extends WindowContainerController<TaskStack, StackWindowListener> {
    private final H mHandler;
    private final int mStackId;
    private final Rect mTmpDisplayBounds;
    private final Rect mTmpNonDecorInsets;
    private final Rect mTmpRect;
    private final Rect mTmpStableInsets;

    @Override // com.android.server.wm.WindowContainerController, com.android.server.wm.ConfigurationContainerListener
    public /* bridge */ /* synthetic */ void onOverrideConfigurationChanged(Configuration configuration) {
        super.onOverrideConfigurationChanged(configuration);
    }

    public StackWindowController(int stackId, StackWindowListener listener, int displayId, boolean onTop, Rect outBounds) {
        this(stackId, listener, displayId, onTop, outBounds, WindowManagerService.getInstance());
    }

    @VisibleForTesting
    public StackWindowController(int stackId, StackWindowListener listener, int displayId, boolean onTop, Rect outBounds, WindowManagerService service) {
        super(listener, service);
        this.mTmpRect = new Rect();
        this.mTmpStableInsets = new Rect();
        this.mTmpNonDecorInsets = new Rect();
        this.mTmpDisplayBounds = new Rect();
        this.mStackId = stackId;
        this.mHandler = new H(new WeakReference(this), service.mH.getLooper());
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Trying to add stackId=" + stackId + " to unknown displayId=" + displayId);
                }
                dc.createStack(stackId, onTop, this);
                getRawBounds(outBounds);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowContainerController
    public void removeContainer() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    ((TaskStack) this.mContainer).removeIfPossible();
                    super.removeContainer();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void reparent(int displayId, Rect outStackBounds, boolean onTop) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    throw new IllegalArgumentException("Trying to move unknown stackId=" + this.mStackId + " to displayId=" + displayId);
                }
                DisplayContent targetDc = this.mRoot.getDisplayContent(displayId);
                if (targetDc == null) {
                    throw new IllegalArgumentException("Trying to move stackId=" + this.mStackId + " to unknown displayId=" + displayId);
                }
                targetDc.moveStackToDisplay((TaskStack) this.mContainer, onTop);
                getRawBounds(outStackBounds);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void positionChildAt(TaskWindowContainerController child, int position) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_STACK) {
                    Slog.i("WindowManager", "positionChildAt: positioning task=" + child + " at " + position);
                }
                if (child.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "positionChildAt: could not find task=" + this);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "positionChildAt: could not find stack for task=" + this.mContainer);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else {
                    ((Task) child.mContainer).positionAt(position);
                    ((TaskStack) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void positionChildAtTop(TaskWindowContainerController child, boolean includingParents) {
        if (child == null) {
            return;
        }
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Task childTask = (Task) child.mContainer;
                if (childTask == null) {
                    Slog.e("WindowManager", "positionChildAtTop: task=" + child + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((TaskStack) this.mContainer).positionChildAt(Integer.MAX_VALUE, childTask, includingParents);
                if (this.mService.mAppTransition.isTransitionSet()) {
                    childTask.setSendingToBottom(false);
                }
                ((TaskStack) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void positionChildAtBottom(TaskWindowContainerController child, boolean includingParents) {
        if (child == null) {
            return;
        }
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Task childTask = (Task) child.mContainer;
                if (childTask == null) {
                    Slog.e("WindowManager", "positionChildAtBottom: task=" + child + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((TaskStack) this.mContainer).positionChildAt(Integer.MIN_VALUE, childTask, includingParents);
                if (this.mService.mAppTransition.isTransitionSet()) {
                    childTask.setSendingToBottom(true);
                }
                ((TaskStack) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void resize(Rect bounds, SparseArray<Rect> taskBounds, SparseArray<Rect> taskTempInsetBounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    throw new IllegalArgumentException("resizeStack: stack " + this + " not found.");
                }
                ((TaskStack) this.mContainer).prepareFreezingTaskBounds();
                if (((TaskStack) this.mContainer).setBounds(bounds, taskBounds, taskTempInsetBounds) && ((TaskStack) this.mContainer).isVisible()) {
                    ((TaskStack) this.mContainer).getDisplayContent().setLayoutNeeded();
                    this.mService.mWindowPlacerLocked.performSurfacePlacement();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void onPipAnimationEndResize() {
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ((TaskStack) this.mContainer).onPipAnimationEndResize();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void getStackDockedModeBounds(Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds, boolean ignoreVisibility) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    ((TaskStack) this.mContainer).getStackDockedModeBoundsLocked(currentTempTaskBounds, outStackBounds, outTempTaskBounds, ignoreVisibility);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                outStackBounds.setEmpty();
                outTempTaskBounds.setEmpty();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void prepareFreezingTaskBounds() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    throw new IllegalArgumentException("prepareFreezingTaskBounds: stack " + this + " not found.");
                }
                ((TaskStack) this.mContainer).prepareFreezingTaskBounds();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void getRawBounds(Rect outBounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (((TaskStack) this.mContainer).matchParentBounds()) {
                    outBounds.setEmpty();
                } else {
                    ((TaskStack) this.mContainer).getRawBounds(outBounds);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void getBounds(Rect outBounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    ((TaskStack) this.mContainer).getBounds(outBounds);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                outBounds.setEmpty();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void getBoundsForNewConfiguration(Rect outBounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ((TaskStack) this.mContainer).getBoundsForNewConfiguration(outBounds);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void adjustConfigurationForBounds(Rect bounds, Rect insetBounds, Rect nonDecorBounds, Rect stableBounds, boolean overrideWidth, boolean overrideHeight, float density, Configuration config, Configuration parentConfig, int windowingMode) {
        Rect parentAppBounds;
        int width;
        int height;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                TaskStack stack = (TaskStack) this.mContainer;
                DisplayContent displayContent = stack.getDisplayContent();
                DisplayInfo di = displayContent.getDisplayInfo();
                DisplayCutout displayCutout = di.displayCutout;
                this.mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, displayCutout, this.mTmpStableInsets);
                this.mService.mPolicy.getNonDecorInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, displayCutout, this.mTmpNonDecorInsets);
                this.mTmpDisplayBounds.set(0, 0, di.logicalWidth, di.logicalHeight);
                Rect parentAppBounds2 = parentConfig.windowConfiguration.getAppBounds();
                config.windowConfiguration.setBounds(bounds);
                config.windowConfiguration.setAppBounds(!bounds.isEmpty() ? bounds : null);
                boolean intersectParentBounds = false;
                if (WindowConfiguration.isFloating(windowingMode)) {
                    if (windowingMode == 2) {
                        try {
                            if (bounds.width() == this.mTmpDisplayBounds.width() && bounds.height() == this.mTmpDisplayBounds.height()) {
                                try {
                                    stableBounds.inset(this.mTmpStableInsets);
                                    nonDecorBounds.inset(this.mTmpNonDecorInsets);
                                    config.windowConfiguration.getAppBounds().offsetTo(0, 0);
                                    intersectParentBounds = true;
                                    width = (int) (stableBounds.width() / density);
                                    height = (int) (stableBounds.height() / density);
                                    parentAppBounds = parentAppBounds2;
                                } catch (Throwable th) {
                                    th = th;
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    throw th;
                                }
                            }
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                    width = (int) (stableBounds.width() / density);
                    height = (int) (stableBounds.height() / density);
                    parentAppBounds = parentAppBounds2;
                } else {
                    parentAppBounds = parentAppBounds2;
                    intersectDisplayBoundsExcludeInsets(nonDecorBounds, insetBounds != null ? insetBounds : bounds, this.mTmpNonDecorInsets, this.mTmpDisplayBounds, overrideWidth, overrideHeight);
                    intersectDisplayBoundsExcludeInsets(stableBounds, insetBounds != null ? insetBounds : bounds, this.mTmpStableInsets, this.mTmpDisplayBounds, overrideWidth, overrideHeight);
                    width = Math.min((int) (stableBounds.width() / density), parentConfig.screenWidthDp);
                    height = Math.min((int) (stableBounds.height() / density), parentConfig.screenHeightDp);
                    intersectParentBounds = true;
                }
                if (intersectParentBounds && config.windowConfiguration.getAppBounds() != null) {
                    config.windowConfiguration.getAppBounds().intersect(parentAppBounds);
                }
                config.screenWidthDp = width;
                config.screenHeightDp = height;
                config.smallestScreenWidthDp = getSmallestWidthForTaskBounds(insetBounds != null ? insetBounds : bounds, density, windowingMode);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private void intersectDisplayBoundsExcludeInsets(Rect inOutBounds, Rect inInsetBounds, Rect stableInsets, Rect displayBounds, boolean overrideWidth, boolean overrideHeight) {
        this.mTmpRect.set(inInsetBounds);
        this.mService.intersectDisplayInsetBounds(displayBounds, stableInsets, this.mTmpRect);
        int leftInset = this.mTmpRect.left - inInsetBounds.left;
        int topInset = this.mTmpRect.top - inInsetBounds.top;
        int rightInset = overrideWidth ? 0 : inInsetBounds.right - this.mTmpRect.right;
        int bottomInset = overrideHeight ? 0 : inInsetBounds.bottom - this.mTmpRect.bottom;
        inOutBounds.inset(leftInset, topInset, rightInset, bottomInset);
    }

    private int getSmallestWidthForTaskBounds(Rect bounds, float density, int windowingMode) {
        DisplayContent displayContent = ((TaskStack) this.mContainer).getDisplayContent();
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (bounds == null || (bounds.width() == displayInfo.logicalWidth && bounds.height() == displayInfo.logicalHeight)) {
            return displayContent.getConfiguration().smallestScreenWidthDp;
        }
        if (WindowConfiguration.isFloating(windowingMode)) {
            return (int) (Math.min(bounds.width(), bounds.height()) / density);
        }
        return displayContent.getDockedDividerController().getSmallestWidthDpForBounds(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestResize(Rect bounds) {
        this.mHandler.obtainMessage(0, bounds).sendToTarget();
    }

    public String toString() {
        return "{StackWindowController stackId=" + this.mStackId + "}";
    }

    /* loaded from: classes.dex */
    private static final class H extends Handler {
        static final int REQUEST_RESIZE = 0;
        private final WeakReference<StackWindowController> mController;

        H(WeakReference<StackWindowController> controller, Looper looper) {
            super(looper);
            this.mController = controller;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            StackWindowController controller = this.mController.get();
            StackWindowListener listener = controller != null ? (StackWindowListener) controller.mListener : null;
            if (listener != null && msg.what == 0) {
                listener.requestResize((Rect) msg.obj);
            }
        }
    }
}
