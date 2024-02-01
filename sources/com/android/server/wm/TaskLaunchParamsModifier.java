package com.android.server.wm;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.wm.LaunchParamsController;
import com.xiaopeng.view.SharedDisplayManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class TaskLaunchParamsModifier implements LaunchParamsController.LaunchParamsModifier {
    private static final int BOUNDS_CONFLICT_THRESHOLD = 4;
    private static final int CASCADING_OFFSET_DP = 75;
    private static final boolean DEBUG = false;
    private static final int DEFAULT_PORTRAIT_PHONE_HEIGHT_DP = 732;
    private static final int DEFAULT_PORTRAIT_PHONE_WIDTH_DP = 412;
    private static final int EPSILON = 2;
    private static final int MINIMAL_STEP = 1;
    private static final int STEP_DENOMINATOR = 16;
    private static final int SUPPORTS_SCREEN_RESIZEABLE_MASK = 539136;
    private static final String TAG = "ActivityTaskManager";
    private StringBuilder mLogBuilder;
    private final ActivityStackSupervisor mSupervisor;
    private final Rect mTmpBounds = new Rect();
    private final int[] mTmpDirections = new int[2];

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskLaunchParamsModifier(ActivityStackSupervisor supervisor) {
        this.mSupervisor = supervisor;
    }

    @VisibleForTesting
    int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout, ActivityRecord activity, ActivityRecord source, ActivityOptions options, LaunchParamsController.LaunchParams currentParams, LaunchParamsController.LaunchParams outParams) {
        return onCalculate(task, layout, activity, source, options, 2, currentParams, outParams);
    }

    @Override // com.android.server.wm.LaunchParamsController.LaunchParamsModifier
    public int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout, ActivityRecord activity, ActivityRecord source, ActivityOptions options, int phase, LaunchParamsController.LaunchParams currentParams, LaunchParamsController.LaunchParams outParams) {
        initLogBuilder(task, activity);
        int result = calculate(task, layout, activity, source, options, phase, currentParams, outParams);
        outputLog();
        return result;
    }

    /* JADX WARN: Removed duplicated region for block: B:70:0x00e9  */
    /* JADX WARN: Removed duplicated region for block: B:71:0x00eb  */
    /* JADX WARN: Removed duplicated region for block: B:74:0x00f1 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:75:0x00f2  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int calculate(com.android.server.wm.TaskRecord r26, android.content.pm.ActivityInfo.WindowLayout r27, com.android.server.wm.ActivityRecord r28, com.android.server.wm.ActivityRecord r29, android.app.ActivityOptions r30, int r31, com.android.server.wm.LaunchParamsController.LaunchParams r32, com.android.server.wm.LaunchParamsController.LaunchParams r33) {
        /*
            Method dump skipped, instructions count: 358
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.TaskLaunchParamsModifier.calculate(com.android.server.wm.TaskRecord, android.content.pm.ActivityInfo$WindowLayout, com.android.server.wm.ActivityRecord, com.android.server.wm.ActivityRecord, android.app.ActivityOptions, int, com.android.server.wm.LaunchParamsController$LaunchParams, com.android.server.wm.LaunchParamsController$LaunchParams):int");
    }

    private int getPreferredLaunchDisplay(TaskRecord task, ActivityOptions options, ActivityRecord source, LaunchParamsController.LaunchParams currentParams) {
        if (this.mSupervisor.mService.mSupportsMultiDisplay) {
            int displayId = -1;
            int optionLaunchId = options != null ? options.getLaunchDisplayId() : -1;
            if (optionLaunchId != -1) {
                displayId = optionLaunchId;
            }
            if (displayId == -1 && source != null && source.noDisplay) {
                displayId = source.mHandoverLaunchDisplayId;
            }
            ActivityStack stack = (displayId != -1 || task == null) ? null : task.getStack();
            if (stack != null) {
                displayId = stack.mDisplayId;
            }
            if (displayId == -1 && source != null) {
                int sourceDisplayId = source.getDisplayId();
                displayId = sourceDisplayId;
            }
            if (displayId != -1 && this.mSupervisor.mRootActivityContainer.getActivityDisplay(displayId) == null) {
                displayId = currentParams.mPreferredDisplayId;
            }
            int displayId2 = displayId == -1 ? currentParams.mPreferredDisplayId : displayId;
            if (displayId2 == -1 || this.mSupervisor.mRootActivityContainer.getActivityDisplay(displayId2) == null) {
                return 0;
            }
            return displayId2;
        }
        return 0;
    }

    private boolean canApplyFreeformWindowPolicy(ActivityDisplay display, int launchMode) {
        return this.mSupervisor.mService.mSupportsFreeformWindowManagement && (display.inFreeformWindowingMode() || launchMode == 5);
    }

    private boolean canApplyPipWindowPolicy(int launchMode) {
        return this.mSupervisor.mService.mSupportsPictureInPicture && launchMode == 2;
    }

    private void getLayoutBounds(ActivityDisplay display, ActivityRecord root, ActivityInfo.WindowLayout windowLayout, Rect outBounds) {
        int width;
        int height;
        float fractionOfHorizontalOffset;
        float fractionOfVerticalOffset;
        int verticalGravity = windowLayout.gravity & HdmiCecKeycode.UI_BROADCAST_DIGITAL_CABLE;
        int horizontalGravity = windowLayout.gravity & 7;
        if (!windowLayout.hasSpecifiedSize() && verticalGravity == 0 && horizontalGravity == 0) {
            outBounds.setEmpty();
            return;
        }
        Rect bounds = display.getBounds();
        int defaultWidth = bounds.width();
        int defaultHeight = bounds.height();
        if (!windowLayout.hasSpecifiedSize()) {
            outBounds.setEmpty();
            getTaskBounds(root, display, windowLayout, 5, false, outBounds);
            width = outBounds.width();
            height = outBounds.height();
        } else {
            width = defaultWidth;
            if (windowLayout.width > 0 && windowLayout.width < defaultWidth) {
                width = windowLayout.width;
            } else if (windowLayout.widthFraction > 0.0f && windowLayout.widthFraction < 1.0f) {
                width = (int) (width * windowLayout.widthFraction);
            }
            height = defaultHeight;
            if (windowLayout.height > 0 && windowLayout.height < defaultHeight) {
                height = windowLayout.height;
            } else if (windowLayout.heightFraction > 0.0f && windowLayout.heightFraction < 1.0f) {
                height = (int) (height * windowLayout.heightFraction);
            }
        }
        if (horizontalGravity == 3) {
            fractionOfHorizontalOffset = 0.0f;
        } else if (horizontalGravity == 5) {
            fractionOfHorizontalOffset = 1.0f;
        } else {
            fractionOfHorizontalOffset = 0.5f;
        }
        if (verticalGravity == 48) {
            fractionOfVerticalOffset = 0.0f;
        } else if (verticalGravity == 80) {
            fractionOfVerticalOffset = 1.0f;
        } else {
            fractionOfVerticalOffset = 0.5f;
        }
        outBounds.set(0, 0, width, height);
        int xOffset = (int) ((defaultWidth - width) * fractionOfHorizontalOffset);
        int yOffset = (int) ((defaultHeight - height) * fractionOfVerticalOffset);
        outBounds.offset(xOffset, yOffset);
    }

    private boolean isTaskForcedMaximized(ActivityRecord root) {
        if (root.appInfo.targetSdkVersion < 4 || (root.appInfo.flags & SUPPORTS_SCREEN_RESIZEABLE_MASK) == 0) {
            return true;
        }
        return !root.isResizeable();
    }

    private int resolveOrientation(ActivityRecord activity) {
        int orientation = activity.info.screenOrientation;
        if (orientation != 0) {
            if (orientation != 1) {
                if (orientation != 11) {
                    if (orientation != 12) {
                        if (orientation != 14) {
                            switch (orientation) {
                                case 5:
                                    break;
                                case 6:
                                case 8:
                                    break;
                                case 7:
                                case 9:
                                    break;
                                default:
                                    return -1;
                            }
                        }
                        return 14;
                    }
                }
            }
            return 1;
        }
        return 0;
    }

    private void cascadeBounds(Rect srcBounds, ActivityDisplay display, Rect outBounds) {
        outBounds.set(srcBounds);
        float density = display.getConfiguration().densityDpi / 160.0f;
        int defaultOffset = (int) ((75.0f * density) + 0.5f);
        display.getBounds(this.mTmpBounds);
        int dx = Math.min(defaultOffset, Math.max(0, this.mTmpBounds.right - srcBounds.right));
        int dy = Math.min(defaultOffset, Math.max(0, this.mTmpBounds.bottom - srcBounds.bottom));
        outBounds.offset(dx, dy);
    }

    private void getTaskBounds(ActivityRecord root, ActivityDisplay display, ActivityInfo.WindowLayout layout, int resolvedMode, boolean hasInitialBounds, Rect inOutBounds) {
        if (resolvedMode == 1) {
            inOutBounds.setEmpty();
        } else if (resolvedMode != 5) {
        } else {
            int orientation = resolveOrientation(root, display, inOutBounds);
            if (orientation != 1 && orientation != 0) {
                throw new IllegalStateException("Orientation must be one of portrait or landscape, but it's " + ActivityInfo.screenOrientationToString(orientation));
            }
            getDefaultFreeformSize(display, layout, orientation, this.mTmpBounds);
            if (hasInitialBounds || sizeMatches(inOutBounds, this.mTmpBounds)) {
                if (orientation != orientationFromBounds(inOutBounds)) {
                    centerBounds(display, inOutBounds.height(), inOutBounds.width(), inOutBounds);
                }
            } else {
                centerBounds(display, this.mTmpBounds.width(), this.mTmpBounds.height(), inOutBounds);
                adjustBoundsToFitInDisplay(display, inOutBounds);
            }
            adjustBoundsToAvoidConflictInDisplay(display, inOutBounds);
        }
    }

    private int convertOrientationToScreenOrientation(int orientation) {
        if (orientation != 1) {
            if (orientation == 2) {
                return 0;
            }
            return -1;
        }
        return 1;
    }

    private int resolveOrientation(ActivityRecord root, ActivityDisplay display, Rect bounds) {
        int orientationFromBounds;
        int orientation = resolveOrientation(root);
        if (orientation == 14) {
            if (bounds.isEmpty()) {
                orientationFromBounds = convertOrientationToScreenOrientation(display.getConfiguration().orientation);
            } else {
                orientationFromBounds = orientationFromBounds(bounds);
            }
            orientation = orientationFromBounds;
        }
        if (orientation == -1) {
            return bounds.isEmpty() ? 1 : orientationFromBounds(bounds);
        }
        return orientation;
    }

    private void getDefaultFreeformSize(ActivityDisplay display, ActivityInfo.WindowLayout layout, int orientation, Rect bounds) {
        int layoutMinWidth;
        Rect displayBounds = display.getBounds();
        int portraitHeight = Math.min(displayBounds.width(), displayBounds.height());
        int otherDimension = Math.max(displayBounds.width(), displayBounds.height());
        int portraitWidth = (portraitHeight * portraitHeight) / otherDimension;
        int defaultWidth = orientation == 0 ? portraitHeight : portraitWidth;
        int defaultHeight = orientation == 0 ? portraitWidth : portraitHeight;
        float density = display.getConfiguration().densityDpi / 160.0f;
        int phonePortraitWidth = (int) ((412.0f * density) + 0.5f);
        int phonePortraitHeight = (int) ((732.0f * density) + 0.5f);
        int phoneWidth = orientation == 0 ? phonePortraitHeight : phonePortraitWidth;
        int phoneHeight = orientation == 0 ? phonePortraitWidth : phonePortraitHeight;
        int layoutMinHeight = -1;
        if (layout != null) {
            layoutMinWidth = layout.minWidth;
        } else {
            layoutMinWidth = -1;
        }
        if (layout != null) {
            layoutMinHeight = layout.minHeight;
        }
        int width = Math.min(defaultWidth, Math.max(phoneWidth, layoutMinWidth));
        int height = Math.min(defaultHeight, Math.max(phoneHeight, layoutMinHeight));
        bounds.set(0, 0, width, height);
    }

    private void centerBounds(ActivityDisplay display, int width, int height, Rect inOutBounds) {
        if (inOutBounds.isEmpty()) {
            display.getBounds(inOutBounds);
        }
        int left = inOutBounds.centerX() - (width / 2);
        int top = inOutBounds.centerY() - (height / 2);
        inOutBounds.set(left, top, left + width, top + height);
    }

    private void adjustBoundsToFitInDisplay(ActivityDisplay display, Rect inOutBounds) {
        int left;
        int dx;
        int dy;
        Rect displayBounds = display.getBounds();
        if (displayBounds.width() < inOutBounds.width() || displayBounds.height() < inOutBounds.height()) {
            int layoutDirection = this.mSupervisor.mRootActivityContainer.getConfiguration().getLayoutDirection();
            if (layoutDirection == 1) {
                left = displayBounds.width() - inOutBounds.width();
            } else {
                left = 0;
            }
            inOutBounds.offsetTo(left, 0);
            return;
        }
        if (inOutBounds.right > displayBounds.right) {
            dx = displayBounds.right - inOutBounds.right;
        } else {
            int dx2 = inOutBounds.left;
            if (dx2 < displayBounds.left) {
                dx = displayBounds.left - inOutBounds.left;
            } else {
                dx = 0;
            }
        }
        if (inOutBounds.top < displayBounds.top) {
            dy = displayBounds.top - inOutBounds.top;
        } else {
            int dy2 = inOutBounds.bottom;
            if (dy2 > displayBounds.bottom) {
                dy = displayBounds.bottom - inOutBounds.bottom;
            } else {
                dy = 0;
            }
        }
        inOutBounds.offset(dx, dy);
    }

    private void adjustBoundsToAvoidConflictInDisplay(ActivityDisplay display, Rect inOutBounds) {
        List<Rect> taskBoundsToCheck = new ArrayList<>();
        for (int i = 0; i < display.getChildCount(); i++) {
            ActivityStack stack = display.getChildAt(i);
            if (stack.inFreeformWindowingMode()) {
                for (int j = 0; j < stack.getChildCount(); j++) {
                    taskBoundsToCheck.add(stack.getChildAt(j).getBounds());
                }
            }
        }
        adjustBoundsToAvoidConflict(display.getBounds(), taskBoundsToCheck, inOutBounds);
    }

    @VisibleForTesting
    void adjustBoundsToAvoidConflict(Rect displayBounds, List<Rect> taskBoundsToCheck, Rect inOutBounds) {
        int[] iArr;
        if (!displayBounds.contains(inOutBounds) || !boundsConflict(taskBoundsToCheck, inOutBounds)) {
            return;
        }
        calculateCandidateShiftDirections(displayBounds, inOutBounds);
        for (int direction : this.mTmpDirections) {
            if (direction != 0) {
                this.mTmpBounds.set(inOutBounds);
                while (boundsConflict(taskBoundsToCheck, this.mTmpBounds) && displayBounds.contains(this.mTmpBounds)) {
                    shiftBounds(direction, displayBounds, this.mTmpBounds);
                }
                if (!boundsConflict(taskBoundsToCheck, this.mTmpBounds) && displayBounds.contains(this.mTmpBounds)) {
                    if (SharedDisplayManager.enable()) {
                        return;
                    } else {
                        inOutBounds.set(this.mTmpBounds);
                        return;
                    }
                }
            } else {
                return;
            }
        }
    }

    private void calculateCandidateShiftDirections(Rect availableBounds, Rect initialBounds) {
        int i = 0;
        while (true) {
            int[] iArr = this.mTmpDirections;
            if (i >= iArr.length) {
                break;
            }
            iArr[i] = 0;
            i++;
        }
        int i2 = availableBounds.left;
        int oneThirdWidth = ((i2 * 2) + availableBounds.right) / 3;
        int twoThirdWidth = (availableBounds.left + (availableBounds.right * 2)) / 3;
        int centerX = initialBounds.centerX();
        if (centerX < oneThirdWidth) {
            this.mTmpDirections[0] = 5;
        } else if (centerX > twoThirdWidth) {
            this.mTmpDirections[0] = 3;
        } else {
            int oneThirdHeight = ((availableBounds.top * 2) + availableBounds.bottom) / 3;
            int twoThirdHeight = (availableBounds.top + (availableBounds.bottom * 2)) / 3;
            int centerY = initialBounds.centerY();
            if (centerY < oneThirdHeight || centerY > twoThirdHeight) {
                int[] iArr2 = this.mTmpDirections;
                iArr2[0] = 5;
                iArr2[1] = 3;
                return;
            }
            int[] iArr3 = this.mTmpDirections;
            iArr3[0] = 85;
            iArr3[1] = 51;
        }
    }

    private boolean boundsConflict(List<Rect> taskBoundsToCheck, Rect candidateBounds) {
        Iterator<Rect> it = taskBoundsToCheck.iterator();
        while (true) {
            if (!it.hasNext()) {
                return false;
            }
            Rect taskBounds = it.next();
            boolean leftClose = Math.abs(taskBounds.left - candidateBounds.left) < 4;
            boolean topClose = Math.abs(taskBounds.top - candidateBounds.top) < 4;
            boolean rightClose = Math.abs(taskBounds.right - candidateBounds.right) < 4;
            boolean bottomClose = Math.abs(taskBounds.bottom - candidateBounds.bottom) < 4;
            if ((!leftClose || !topClose) && ((!leftClose || !bottomClose) && ((!rightClose || !topClose) && (!rightClose || !bottomClose)))) {
            }
        }
        return true;
    }

    private void shiftBounds(int direction, Rect availableRect, Rect inOutBounds) {
        int horizontalOffset;
        int verticalOffset;
        int i = direction & 7;
        if (i == 3) {
            horizontalOffset = -Math.max(1, availableRect.width() / 16);
        } else if (i == 5) {
            int horizontalOffset2 = availableRect.width();
            horizontalOffset = Math.max(1, horizontalOffset2 / 16);
        } else {
            horizontalOffset = 0;
        }
        int i2 = direction & HdmiCecKeycode.UI_BROADCAST_DIGITAL_CABLE;
        if (i2 == 48) {
            int verticalOffset2 = availableRect.height();
            verticalOffset = -Math.max(1, verticalOffset2 / 16);
        } else if (i2 == 80) {
            int verticalOffset3 = availableRect.height();
            verticalOffset = Math.max(1, verticalOffset3 / 16);
        } else {
            verticalOffset = 0;
        }
        inOutBounds.offset(horizontalOffset, verticalOffset);
    }

    private void initLogBuilder(TaskRecord task, ActivityRecord activity) {
    }

    private void appendLog(String log) {
    }

    private void outputLog() {
    }

    private static int orientationFromBounds(Rect bounds) {
        return bounds.width() > bounds.height() ? 0 : 1;
    }

    private static boolean sizeMatches(Rect left, Rect right) {
        return Math.abs(right.width() - left.width()) < 2 && Math.abs(right.height() - left.height()) < 2;
    }
}
