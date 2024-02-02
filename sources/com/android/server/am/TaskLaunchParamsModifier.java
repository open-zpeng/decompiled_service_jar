package com.android.server.am;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.LaunchParamsController;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskLaunchParamsModifier implements LaunchParamsController.LaunchParamsModifier {
    private static final boolean ALLOW_RESTART = true;
    private static final int BOUNDS_CONFLICT_MIN_DISTANCE = 4;
    private static final int MARGIN_SIZE_DENOMINATOR = 4;
    private static final int MINIMAL_STEP = 1;
    private static final int SHIFT_POLICY_DIAGONAL_DOWN = 1;
    private static final int SHIFT_POLICY_HORIZONTAL_LEFT = 3;
    private static final int SHIFT_POLICY_HORIZONTAL_RIGHT = 2;
    private static final int STEP_DENOMINATOR = 16;
    private static final String TAG = "ActivityManager";
    private static final int WINDOW_SIZE_DENOMINATOR = 2;
    private final Rect mAvailableRect = new Rect();
    private final Rect mTmpProposal = new Rect();
    private final Rect mTmpOriginal = new Rect();

    @Override // com.android.server.am.LaunchParamsController.LaunchParamsModifier
    public int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout, ActivityRecord activity, ActivityRecord source, ActivityOptions options, LaunchParamsController.LaunchParams currentParams, LaunchParamsController.LaunchParams outParams) {
        if (task == null || task.getStack() == null || !task.inFreeformWindowingMode()) {
            return 0;
        }
        ArrayList<TaskRecord> tasks = task.getStack().getAllTasks();
        this.mAvailableRect.set(task.getParent().getBounds());
        Rect resultBounds = outParams.mBounds;
        if (layout == null) {
            positionCenter(tasks, this.mAvailableRect, getFreeformWidth(this.mAvailableRect), getFreeformHeight(this.mAvailableRect), resultBounds);
            return 2;
        }
        int width = getFinalWidth(layout, this.mAvailableRect);
        int height = getFinalHeight(layout, this.mAvailableRect);
        int verticalGravity = layout.gravity & 112;
        int horizontalGravity = layout.gravity & 7;
        if (verticalGravity == 48) {
            if (horizontalGravity == 5) {
                positionTopRight(tasks, this.mAvailableRect, width, height, resultBounds);
                return 2;
            }
            positionTopLeft(tasks, this.mAvailableRect, width, height, resultBounds);
            return 2;
        } else if (verticalGravity == 80) {
            if (horizontalGravity == 5) {
                positionBottomRight(tasks, this.mAvailableRect, width, height, resultBounds);
                return 2;
            }
            positionBottomLeft(tasks, this.mAvailableRect, width, height, resultBounds);
            return 2;
        } else {
            Slog.w(TAG, "Received unsupported gravity: " + layout.gravity + ", positioning in the center instead.");
            positionCenter(tasks, this.mAvailableRect, width, height, resultBounds);
            return 2;
        }
    }

    @VisibleForTesting
    static int getFreeformStartLeft(Rect bounds) {
        return bounds.left + (bounds.width() / 4);
    }

    @VisibleForTesting
    static int getFreeformStartTop(Rect bounds) {
        return bounds.top + (bounds.height() / 4);
    }

    @VisibleForTesting
    static int getFreeformWidth(Rect bounds) {
        return bounds.width() / 2;
    }

    @VisibleForTesting
    static int getFreeformHeight(Rect bounds) {
        return bounds.height() / 2;
    }

    @VisibleForTesting
    static int getHorizontalStep(Rect bounds) {
        return Math.max(bounds.width() / 16, 1);
    }

    @VisibleForTesting
    static int getVerticalStep(Rect bounds) {
        return Math.max(bounds.height() / 16, 1);
    }

    private int getFinalWidth(ActivityInfo.WindowLayout windowLayout, Rect availableRect) {
        int width = getFreeformWidth(availableRect);
        if (windowLayout.width > 0) {
            width = windowLayout.width;
        }
        if (windowLayout.widthFraction > 0.0f) {
            int width2 = (int) (availableRect.width() * windowLayout.widthFraction);
            return width2;
        }
        return width;
    }

    private int getFinalHeight(ActivityInfo.WindowLayout windowLayout, Rect availableRect) {
        int height = getFreeformHeight(availableRect);
        if (windowLayout.height > 0) {
            height = windowLayout.height;
        }
        if (windowLayout.heightFraction > 0.0f) {
            int height2 = (int) (availableRect.height() * windowLayout.heightFraction);
            return height2;
        }
        return height;
    }

    private void positionBottomLeft(ArrayList<TaskRecord> tasks, Rect availableRect, int width, int height, Rect result) {
        this.mTmpProposal.set(availableRect.left, availableRect.bottom - height, availableRect.left + width, availableRect.bottom);
        position(tasks, availableRect, this.mTmpProposal, false, 2, result);
    }

    private void positionBottomRight(ArrayList<TaskRecord> tasks, Rect availableRect, int width, int height, Rect result) {
        this.mTmpProposal.set(availableRect.right - width, availableRect.bottom - height, availableRect.right, availableRect.bottom);
        position(tasks, availableRect, this.mTmpProposal, false, 3, result);
    }

    private void positionTopLeft(ArrayList<TaskRecord> tasks, Rect availableRect, int width, int height, Rect result) {
        this.mTmpProposal.set(availableRect.left, availableRect.top, availableRect.left + width, availableRect.top + height);
        position(tasks, availableRect, this.mTmpProposal, false, 2, result);
    }

    private void positionTopRight(ArrayList<TaskRecord> tasks, Rect availableRect, int width, int height, Rect result) {
        this.mTmpProposal.set(availableRect.right - width, availableRect.top, availableRect.right, availableRect.top + height);
        position(tasks, availableRect, this.mTmpProposal, false, 3, result);
    }

    private void positionCenter(ArrayList<TaskRecord> tasks, Rect availableRect, int width, int height, Rect result) {
        int defaultFreeformLeft = getFreeformStartLeft(availableRect);
        int defaultFreeformTop = getFreeformStartTop(availableRect);
        this.mTmpProposal.set(defaultFreeformLeft, defaultFreeformTop, defaultFreeformLeft + width, defaultFreeformTop + height);
        position(tasks, availableRect, this.mTmpProposal, true, 1, result);
    }

    private void position(ArrayList<TaskRecord> tasks, Rect availableRect, Rect proposal, boolean allowRestart, int shiftPolicy, Rect result) {
        this.mTmpOriginal.set(proposal);
        boolean restarted = false;
        while (true) {
            if (!boundsConflict(proposal, tasks)) {
                break;
            }
            shiftStartingPoint(proposal, availableRect, shiftPolicy);
            if (shiftedTooFar(proposal, availableRect, shiftPolicy)) {
                if (!allowRestart) {
                    proposal.set(this.mTmpOriginal);
                    break;
                } else {
                    proposal.set(availableRect.left, availableRect.top, availableRect.left + proposal.width(), availableRect.top + proposal.height());
                    restarted = true;
                }
            }
            if (!restarted || (proposal.left <= getFreeformStartLeft(availableRect) && proposal.top <= getFreeformStartTop(availableRect))) {
            }
        }
        proposal.set(this.mTmpOriginal);
        result.set(proposal);
    }

    private boolean shiftedTooFar(Rect start, Rect availableRect, int shiftPolicy) {
        switch (shiftPolicy) {
            case 2:
                return start.right > availableRect.right;
            case 3:
                return start.left < availableRect.left;
            default:
                return start.right > availableRect.right || start.bottom > availableRect.bottom;
        }
    }

    private void shiftStartingPoint(Rect posposal, Rect availableRect, int shiftPolicy) {
        int defaultFreeformStepHorizontal = getHorizontalStep(availableRect);
        int defaultFreeformStepVertical = getVerticalStep(availableRect);
        switch (shiftPolicy) {
            case 2:
                posposal.offset(defaultFreeformStepHorizontal, 0);
                return;
            case 3:
                posposal.offset(-defaultFreeformStepHorizontal, 0);
                return;
            default:
                posposal.offset(defaultFreeformStepHorizontal, defaultFreeformStepVertical);
                return;
        }
    }

    private static boolean boundsConflict(Rect proposal, ArrayList<TaskRecord> tasks) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            TaskRecord task = tasks.get(i);
            if (!task.mActivities.isEmpty() && !task.matchParentBounds()) {
                Rect bounds = task.getOverrideBounds();
                if (closeLeftTopCorner(proposal, bounds) || closeRightTopCorner(proposal, bounds) || closeLeftBottomCorner(proposal, bounds) || closeRightBottomCorner(proposal, bounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final boolean closeLeftTopCorner(Rect first, Rect second) {
        return Math.abs(first.left - second.left) < 4 && Math.abs(first.top - second.top) < 4;
    }

    private static final boolean closeRightTopCorner(Rect first, Rect second) {
        return Math.abs(first.right - second.right) < 4 && Math.abs(first.top - second.top) < 4;
    }

    private static final boolean closeLeftBottomCorner(Rect first, Rect second) {
        return Math.abs(first.left - second.left) < 4 && Math.abs(first.bottom - second.bottom) < 4;
    }

    private static final boolean closeRightBottomCorner(Rect first, Rect second) {
        return Math.abs(first.right - second.right) < 4 && Math.abs(first.bottom - second.bottom) < 4;
    }
}
