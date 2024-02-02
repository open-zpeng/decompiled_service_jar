package com.android.server.am;

import android.app.RemoteAction;
import android.content.res.Configuration;
import android.graphics.Rect;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.PinnedStackWindowListener;
import java.util.ArrayList;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PinnedActivityStack extends ActivityStack<PinnedStackWindowController> implements PinnedStackWindowListener {
    /* JADX INFO: Access modifiers changed from: package-private */
    public PinnedActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor, boolean onTop) {
        super(display, stackId, supervisor, 2, 1, onTop);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.android.server.am.ActivityStack
    public PinnedStackWindowController createStackWindowController(int displayId, boolean onTop, Rect outBounds) {
        return new PinnedStackWindowController(this.mStackId, this, displayId, onTop, outBounds, this.mStackSupervisor.mWindowManager);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getDefaultPictureInPictureBounds(float aspectRatio) {
        return getWindowContainerController().getPictureInPictureBounds(aspectRatio, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void animateResizePinnedStack(Rect sourceHintBounds, Rect toBounds, int animationDuration, boolean fromFullscreen) {
        if (skipResizeAnimation(toBounds == null)) {
            this.mService.moveTasksToFullscreenStack(this.mStackId, true);
        } else {
            getWindowContainerController().animateResizePinnedStack(toBounds, sourceHintBounds, animationDuration, fromFullscreen);
        }
    }

    private boolean skipResizeAnimation(boolean toFullscreen) {
        if (!toFullscreen) {
            return false;
        }
        Configuration parentConfig = getParent().getConfiguration();
        ActivityRecord top = topRunningNonOverlayTaskActivity();
        return (top == null || top.isConfigurationCompatible(parentConfig)) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureAspectRatio(float aspectRatio) {
        getWindowContainerController().setPictureInPictureAspectRatio(aspectRatio);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureActions(List<RemoteAction> actions) {
        getWindowContainerController().setPictureInPictureActions(actions);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimatingBoundsToFullscreen() {
        return getWindowContainerController().isAnimatingBoundsToFullscreen();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.am.ActivityStack
    public boolean deferScheduleMultiWindowModeChanged() {
        return ((PinnedStackWindowController) this.mWindowContainerController).deferScheduleMultiWindowModeChanged();
    }

    @Override // com.android.server.wm.PinnedStackWindowListener
    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds, boolean forceUpdate) {
        synchronized (this) {
            ArrayList<TaskRecord> tasks = getAllTasks();
            for (int i = 0; i < tasks.size(); i++) {
                this.mStackSupervisor.updatePictureInPictureMode(tasks.get(i), targetStackBounds, forceUpdate);
            }
        }
    }
}
