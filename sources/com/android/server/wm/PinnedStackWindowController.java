package com.android.server.wm;

import android.app.RemoteAction;
import android.graphics.Rect;
import java.util.List;
/* loaded from: classes.dex */
public class PinnedStackWindowController extends StackWindowController {
    private Rect mTmpFromBounds;
    private Rect mTmpToBounds;

    public PinnedStackWindowController(int stackId, PinnedStackWindowListener listener, int displayId, boolean onTop, Rect outBounds, WindowManagerService service) {
        super(stackId, listener, displayId, onTop, outBounds, service);
        this.mTmpFromBounds = new Rect();
        this.mTmpToBounds = new Rect();
    }

    public Rect getPictureInPictureBounds(float aspectRatio, Rect stackBounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mService.mSupportsPictureInPicture && this.mContainer != 0) {
                    DisplayContent displayContent = ((TaskStack) this.mContainer).getDisplayContent();
                    if (displayContent == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    }
                    PinnedStackController pinnedStackController = displayContent.getPinnedStackController();
                    if (stackBounds == null) {
                        stackBounds = pinnedStackController.getDefaultOrLastSavedBounds();
                    }
                    if (!pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return stackBounds;
                    }
                    Rect transformBoundsToAspectRatio = pinnedStackController.transformBoundsToAspectRatio(stackBounds, aspectRatio, true);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return transformBoundsToAspectRatio;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void animateResizePinnedStack(Rect toBounds, Rect sourceHintBounds, final int animationDuration, final boolean fromFullscreen) {
        Rect toBounds2;
        int schedulePipModeChangedState;
        Rect toBounds3;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    try {
                        throw new IllegalArgumentException("Pinned stack container not found :(");
                    } catch (Throwable th) {
                        th = th;
                        while (true) {
                            break;
                            break;
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                final Rect fromBounds = new Rect();
                ((TaskStack) this.mContainer).getBounds(fromBounds);
                final boolean toFullscreen = toBounds == null;
                if (toFullscreen) {
                    try {
                        if (fromFullscreen) {
                            throw new IllegalArgumentException("Should not defer scheduling PiP mode change on animation to fullscreen.");
                        }
                        this.mService.getStackBounds(1, 1, this.mTmpToBounds);
                        if (this.mTmpToBounds.isEmpty()) {
                            toBounds2 = new Rect();
                            try {
                                ((TaskStack) this.mContainer).getDisplayContent().getBounds(toBounds2);
                            } catch (Throwable th2) {
                                th = th2;
                                while (true) {
                                    try {
                                        break;
                                    } catch (Throwable th3) {
                                        th = th3;
                                    }
                                }
                                WindowManagerService.resetPriorityAfterLockedSection();
                                throw th;
                            }
                        } else {
                            toBounds2 = new Rect(this.mTmpToBounds);
                        }
                        schedulePipModeChangedState = 1;
                        toBounds3 = toBounds2;
                    } catch (Throwable th4) {
                        th = th4;
                    }
                } else {
                    int schedulePipModeChangedState2 = fromFullscreen ? 2 : 0;
                    toBounds3 = toBounds;
                    schedulePipModeChangedState = schedulePipModeChangedState2;
                }
                try {
                    try {
                        ((TaskStack) this.mContainer).setAnimationFinalBounds(sourceHintBounds, toBounds3, toFullscreen);
                        final Rect finalToBounds = toBounds3;
                        final int finalSchedulePipModeChangedState = schedulePipModeChangedState;
                        this.mService.mBoundsAnimationController.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$PinnedStackWindowController$x7R9b-0MaS9BJmen-irckXpBNyg
                            @Override // java.lang.Runnable
                            public final void run() {
                                PinnedStackWindowController.lambda$animateResizePinnedStack$0(PinnedStackWindowController.this, fromBounds, finalToBounds, animationDuration, finalSchedulePipModeChangedState, fromFullscreen, toFullscreen);
                            }
                        });
                        WindowManagerService.resetPriorityAfterLockedSection();
                    } catch (Throwable th5) {
                        th = th5;
                        while (true) {
                            break;
                            break;
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                } catch (Throwable th6) {
                    th = th6;
                }
            } catch (Throwable th7) {
                th = th7;
            }
        }
    }

    public static /* synthetic */ void lambda$animateResizePinnedStack$0(PinnedStackWindowController pinnedStackWindowController, Rect fromBounds, Rect finalToBounds, int animationDuration, int finalSchedulePipModeChangedState, boolean fromFullscreen, boolean toFullscreen) {
        if (pinnedStackWindowController.mContainer == 0) {
            return;
        }
        pinnedStackWindowController.mService.mBoundsAnimationController.animateBounds((BoundsAnimationTarget) pinnedStackWindowController.mContainer, fromBounds, finalToBounds, animationDuration, finalSchedulePipModeChangedState, fromFullscreen, toFullscreen);
    }

    public void setPictureInPictureAspectRatio(float aspectRatio) {
        float f;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mService.mSupportsPictureInPicture && this.mContainer != 0) {
                    PinnedStackController pinnedStackController = ((TaskStack) this.mContainer).getDisplayContent().getPinnedStackController();
                    if (Float.compare(aspectRatio, pinnedStackController.getAspectRatio()) != 0) {
                        ((TaskStack) this.mContainer).getAnimationOrCurrentBounds(this.mTmpFromBounds);
                        this.mTmpToBounds.set(this.mTmpFromBounds);
                        getPictureInPictureBounds(aspectRatio, this.mTmpToBounds);
                        if (!this.mTmpToBounds.equals(this.mTmpFromBounds)) {
                            animateResizePinnedStack(this.mTmpToBounds, null, -1, false);
                        }
                        if (pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)) {
                            f = aspectRatio;
                        } else {
                            f = -1.0f;
                        }
                        pinnedStackController.setAspectRatio(f);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setPictureInPictureActions(List<RemoteAction> actions) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mService.mSupportsPictureInPicture && this.mContainer != 0) {
                    ((TaskStack) this.mContainer).getDisplayContent().getPinnedStackController().setActions(actions);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean deferScheduleMultiWindowModeChanged() {
        boolean deferScheduleMultiWindowModeChanged;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                deferScheduleMultiWindowModeChanged = ((TaskStack) this.mContainer).deferScheduleMultiWindowModeChanged();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return deferScheduleMultiWindowModeChanged;
    }

    public boolean isAnimatingBoundsToFullscreen() {
        boolean isAnimatingBoundsToFullscreen;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                isAnimatingBoundsToFullscreen = ((TaskStack) this.mContainer).isAnimatingBoundsToFullscreen();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return isAnimatingBoundsToFullscreen;
    }

    public boolean pinnedStackResizeDisallowed() {
        boolean pinnedStackResizeDisallowed;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                pinnedStackResizeDisallowed = ((TaskStack) this.mContainer).pinnedStackResizeDisallowed();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return pinnedStackResizeDisallowed;
    }

    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds, boolean forceUpdate) {
        if (this.mListener != 0) {
            PinnedStackWindowListener listener = (PinnedStackWindowListener) this.mListener;
            listener.updatePictureInPictureModeForPinnedStackAnimation(targetStackBounds, forceUpdate);
        }
    }
}
