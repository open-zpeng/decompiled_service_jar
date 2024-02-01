package com.android.server.wm;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.MotionEvent;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskPositioner {
    private static final int CTRL_BOTTOM = 8;
    private static final int CTRL_LEFT = 1;
    private static final int CTRL_NONE = 0;
    private static final int CTRL_RIGHT = 2;
    private static final int CTRL_TOP = 4;
    private static final boolean DEBUG_ORIENTATION_VIOLATIONS = false;
    @VisibleForTesting
    static final float MIN_ASPECT = 1.2f;
    public static final float RESIZING_HINT_ALPHA = 0.5f;
    public static final int RESIZING_HINT_DURATION_MS = 0;
    static final int SIDE_MARGIN_DIP = 100;
    private static final String TAG = "WindowManager";
    private static final String TAG_LOCAL = "TaskPositioner";
    private static Factory sFactory;
    InputChannel mClientChannel;
    private Display mDisplay;
    InputApplicationHandle mDragApplicationHandle;
    InputWindowHandle mDragWindowHandle;
    private WindowPositionerEventReceiver mInputEventReceiver;
    private int mMinVisibleHeight;
    private int mMinVisibleWidth;
    private boolean mPreserveOrientation;
    private boolean mResizing;
    InputChannel mServerChannel;
    private final WindowManagerService mService;
    private int mSideMargin;
    private float mStartDragX;
    private float mStartDragY;
    private boolean mStartOrientationWasLandscape;
    private Task mTask;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private Rect mTmpRect = new Rect();
    private final Rect mWindowOriginalBounds = new Rect();
    private final Rect mWindowDragBounds = new Rect();
    private final Point mMaxVisibleSize = new Point();
    private int mCtrlType = 0;
    private boolean mDragEnded = false;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface CtrlType {
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class WindowPositionerEventReceiver extends BatchedInputEventReceiver {
        public WindowPositionerEventReceiver(InputChannel inputChannel, Looper looper, Choreographer choreographer) {
            super(inputChannel, looper, choreographer);
        }

        public void onInputEvent(InputEvent event, int displayId) {
            if (!(event instanceof MotionEvent) || (event.getSource() & 2) == 0) {
                return;
            }
            MotionEvent motionEvent = (MotionEvent) event;
            boolean handled = false;
            try {
                try {
                } catch (Exception e) {
                    Slog.e(TaskPositioner.TAG, "Exception caught by drag handleMotion", e);
                }
                if (TaskPositioner.this.mDragEnded) {
                    handled = true;
                    return;
                }
                float newX = motionEvent.getRawX();
                float newY = motionEvent.getRawY();
                switch (motionEvent.getAction()) {
                    case 0:
                        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                            Slog.w(TaskPositioner.TAG, "ACTION_DOWN @ {" + newX + ", " + newY + "}");
                            break;
                        }
                        break;
                    case 1:
                        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                            Slog.w(TaskPositioner.TAG, "ACTION_UP @ {" + newX + ", " + newY + "}");
                        }
                        TaskPositioner.this.mDragEnded = true;
                        break;
                    case 2:
                        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                            Slog.w(TaskPositioner.TAG, "ACTION_MOVE @ {" + newX + ", " + newY + "}");
                        }
                        synchronized (TaskPositioner.this.mService.mWindowMap) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                TaskPositioner.this.mDragEnded = TaskPositioner.this.notifyMoveLocked(newX, newY);
                                TaskPositioner.this.mTask.getDimBounds(TaskPositioner.this.mTmpRect);
                            } finally {
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        if (!TaskPositioner.this.mTmpRect.equals(TaskPositioner.this.mWindowDragBounds)) {
                            Trace.traceBegin(32L, "wm.TaskPositioner.resizeTask");
                            try {
                                TaskPositioner.this.mService.mActivityManager.resizeTask(TaskPositioner.this.mTask.mTaskId, TaskPositioner.this.mWindowDragBounds, 1);
                            } catch (RemoteException e2) {
                            }
                            Trace.traceEnd(32L);
                            break;
                        }
                        break;
                    case 3:
                        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                            Slog.w(TaskPositioner.TAG, "ACTION_CANCEL @ {" + newX + ", " + newY + "}");
                        }
                        TaskPositioner.this.mDragEnded = true;
                        break;
                }
                if (TaskPositioner.this.mDragEnded) {
                    boolean wasResizing = TaskPositioner.this.mResizing;
                    synchronized (TaskPositioner.this.mService.mWindowMap) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            TaskPositioner.this.endDragLocked();
                            TaskPositioner.this.mTask.getDimBounds(TaskPositioner.this.mTmpRect);
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (wasResizing) {
                        try {
                            if (!TaskPositioner.this.mTmpRect.equals(TaskPositioner.this.mWindowDragBounds)) {
                                TaskPositioner.this.mService.mActivityManager.resizeTask(TaskPositioner.this.mTask.mTaskId, TaskPositioner.this.mWindowDragBounds, 3);
                            }
                        } catch (RemoteException e3) {
                        }
                    }
                    TaskPositioner.this.mService.mTaskPositioningController.finishTaskPositioning();
                }
                handled = true;
            } finally {
                finishInputEvent(event, false);
            }
        }
    }

    TaskPositioner(WindowManagerService service) {
        this.mService = service;
    }

    @VisibleForTesting
    Rect getWindowDragBounds() {
        return this.mWindowDragBounds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void register(DisplayContent displayContent) {
        Display display = displayContent.getDisplay();
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Registering task positioner");
        }
        if (this.mClientChannel != null) {
            Slog.e(TAG, "Task positioner already registered");
            return;
        }
        this.mDisplay = display;
        this.mDisplay.getMetrics(this.mDisplayMetrics);
        InputChannel[] channels = InputChannel.openInputChannelPair(TAG);
        this.mServerChannel = channels[0];
        this.mClientChannel = channels[1];
        this.mService.mInputManager.registerInputChannel(this.mServerChannel, null);
        this.mInputEventReceiver = new WindowPositionerEventReceiver(this.mClientChannel, this.mService.mAnimationHandler.getLooper(), this.mService.mAnimator.getChoreographer());
        this.mDragApplicationHandle = new InputApplicationHandle(null);
        this.mDragApplicationHandle.name = TAG;
        this.mDragApplicationHandle.dispatchingTimeoutNanos = 5000000000L;
        this.mDragWindowHandle = new InputWindowHandle(this.mDragApplicationHandle, null, null, this.mDisplay.getDisplayId());
        this.mDragWindowHandle.name = TAG;
        this.mDragWindowHandle.inputChannel = this.mServerChannel;
        this.mDragWindowHandle.layer = this.mService.getDragLayerLocked();
        this.mDragWindowHandle.layoutParamsFlags = 0;
        this.mDragWindowHandle.layoutParamsType = 2016;
        this.mDragWindowHandle.dispatchingTimeoutNanos = 5000000000L;
        this.mDragWindowHandle.visible = true;
        this.mDragWindowHandle.canReceiveKeys = false;
        this.mDragWindowHandle.hasFocus = true;
        this.mDragWindowHandle.hasWallpaper = false;
        this.mDragWindowHandle.paused = false;
        this.mDragWindowHandle.ownerPid = Process.myPid();
        this.mDragWindowHandle.ownerUid = Process.myUid();
        this.mDragWindowHandle.inputFeatures = 0;
        this.mDragWindowHandle.scaleFactor = 1.0f;
        this.mDragWindowHandle.touchableRegion.setEmpty();
        this.mDragWindowHandle.frameLeft = 0;
        this.mDragWindowHandle.frameTop = 0;
        Point p = new Point();
        this.mDisplay.getRealSize(p);
        this.mDragWindowHandle.frameRight = p.x;
        this.mDragWindowHandle.frameBottom = p.y;
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.d(TAG, "Pausing rotation during re-position");
        }
        this.mService.pauseRotationLocked();
        this.mSideMargin = WindowManagerService.dipToPixel(100, this.mDisplayMetrics);
        this.mMinVisibleWidth = WindowManagerService.dipToPixel(48, this.mDisplayMetrics);
        this.mMinVisibleHeight = WindowManagerService.dipToPixel(32, this.mDisplayMetrics);
        this.mDisplay.getRealSize(this.mMaxVisibleSize);
        this.mDragEnded = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregister() {
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Unregistering task positioner");
        }
        if (this.mClientChannel == null) {
            Slog.e(TAG, "Task positioner not registered");
            return;
        }
        this.mService.mInputManager.unregisterInputChannel(this.mServerChannel);
        this.mInputEventReceiver.dispose();
        this.mInputEventReceiver = null;
        this.mClientChannel.dispose();
        this.mServerChannel.dispose();
        this.mClientChannel = null;
        this.mServerChannel = null;
        this.mDragWindowHandle = null;
        this.mDragApplicationHandle = null;
        this.mDisplay = null;
        this.mDragEnded = true;
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.d(TAG, "Resuming rotation after re-position");
        }
        this.mService.resumeRotationLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startDrag(WindowState win, boolean resize, boolean preserveOrientation, float startX, float startY) {
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "startDrag: win=" + win + ", resize=" + resize + ", preserveOrientation=" + preserveOrientation + ", {" + startX + ", " + startY + "}");
        }
        this.mTask = win.getTask();
        this.mTask.getDimBounds(this.mTmpRect);
        startDrag(resize, preserveOrientation, startX, startY, this.mTmpRect);
    }

    @VisibleForTesting
    void startDrag(boolean resize, boolean preserveOrientation, float startX, float startY, final Rect startBounds) {
        boolean z;
        boolean z2 = false;
        this.mCtrlType = 0;
        this.mStartDragX = startX;
        this.mStartDragY = startY;
        this.mPreserveOrientation = preserveOrientation;
        if (resize) {
            if (startX < startBounds.left) {
                this.mCtrlType |= 1;
            }
            if (startX > startBounds.right) {
                this.mCtrlType |= 2;
            }
            if (startY < startBounds.top) {
                this.mCtrlType |= 4;
            }
            if (startY > startBounds.bottom) {
                this.mCtrlType |= 8;
            }
            if (this.mCtrlType == 0) {
                z = false;
            } else {
                z = true;
            }
            this.mResizing = z;
        }
        if (startBounds.width() >= startBounds.height()) {
            z2 = true;
        }
        this.mStartOrientationWasLandscape = z2;
        this.mWindowOriginalBounds.set(startBounds);
        if (this.mResizing) {
            synchronized (this.mService.mWindowMap) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    notifyMoveLocked(startX, startY);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskPositioner$TE0EjYzJeOSFARmUlY6wF3y3c2U
                @Override // java.lang.Runnable
                public final void run() {
                    r0.mService.mActivityManager.resizeTask(TaskPositioner.this.mTask.mTaskId, startBounds, 3);
                }
            });
        }
        this.mWindowDragBounds.set(startBounds);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void endDragLocked() {
        this.mResizing = false;
        this.mTask.setDragResizing(false, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean notifyMoveLocked(float x, float y) {
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "notifyMoveLocked: {" + x + "," + y + "}");
        }
        if (this.mCtrlType != 0) {
            resizeDrag(x, y);
            this.mTask.setDragResizing(true, 0);
            return false;
        }
        this.mTask.mStack.getDimBounds(this.mTmpRect);
        int nX = (int) x;
        int nY = (int) y;
        if (!this.mTmpRect.contains(nX, nY)) {
            nX = Math.min(Math.max(nX, this.mTmpRect.left), this.mTmpRect.right);
            nY = Math.min(Math.max(nY, this.mTmpRect.top), this.mTmpRect.bottom);
        }
        updateWindowDragBounds(nX, nY, this.mTmpRect);
        return false;
    }

    @VisibleForTesting
    void resizeDrag(float x, float y) {
        char c;
        int width;
        int height;
        int height1;
        int height12;
        int width2;
        int width1;
        int height13;
        int height2;
        int deltaX = Math.round(x - this.mStartDragX);
        int deltaY = Math.round(y - this.mStartDragY);
        int left = this.mWindowOriginalBounds.left;
        int top = this.mWindowOriginalBounds.top;
        int right = this.mWindowOriginalBounds.right;
        int bottom = this.mWindowOriginalBounds.bottom;
        if (!this.mPreserveOrientation) {
            c = 0;
        } else {
            c = this.mStartOrientationWasLandscape ? (char) 39322 : (char) 21845;
        }
        int width3 = right - left;
        int height3 = bottom - top;
        if ((this.mCtrlType & 1) != 0) {
            width3 = Math.max(this.mMinVisibleWidth, width3 - deltaX);
        } else if ((this.mCtrlType & 2) != 0) {
            width3 = Math.max(this.mMinVisibleWidth, width3 + deltaX);
        }
        if ((this.mCtrlType & 4) != 0) {
            height3 = Math.max(this.mMinVisibleHeight, height3 - deltaY);
        } else if ((this.mCtrlType & 8) != 0) {
            height3 = Math.max(this.mMinVisibleHeight, height3 + deltaY);
        }
        float aspect = width3 / height3;
        if (!this.mPreserveOrientation || ((!this.mStartOrientationWasLandscape || aspect >= MIN_ASPECT) && (this.mStartOrientationWasLandscape || aspect <= 0.8333333002196431d))) {
            width = width3;
        } else {
            if (this.mStartOrientationWasLandscape) {
                int width12 = Math.max(this.mMinVisibleWidth, Math.min(this.mMaxVisibleSize.x, width3));
                int height14 = Math.min(height3, Math.round(width12 / MIN_ASPECT));
                if (height14 < this.mMinVisibleHeight) {
                    height14 = this.mMinVisibleHeight;
                    width12 = Math.max(this.mMinVisibleWidth, Math.min(this.mMaxVisibleSize.x, Math.round(height14 * MIN_ASPECT)));
                }
                int height22 = Math.max(this.mMinVisibleHeight, Math.min(this.mMaxVisibleSize.y, height3));
                width2 = Math.max(width3, Math.round(height22 * MIN_ASPECT));
                if (width2 >= this.mMinVisibleWidth) {
                    height2 = height22;
                    width1 = width12;
                    height13 = height14;
                } else {
                    width2 = this.mMinVisibleWidth;
                    height2 = Math.max(this.mMinVisibleHeight, Math.min(this.mMaxVisibleSize.y, Math.round(width2 / MIN_ASPECT)));
                    width1 = width12;
                    height13 = height14;
                }
            } else {
                int width13 = Math.max(this.mMinVisibleWidth, Math.min(this.mMaxVisibleSize.x, width3));
                int height15 = Math.max(height3, Math.round(width13 * MIN_ASPECT));
                if (height15 < this.mMinVisibleHeight) {
                    int height16 = this.mMinVisibleHeight;
                    height1 = height16;
                    height12 = Math.max(this.mMinVisibleWidth, Math.min(this.mMaxVisibleSize.x, Math.round(height16 / MIN_ASPECT)));
                } else {
                    height1 = height15;
                    height12 = width13;
                }
                int height23 = Math.max(this.mMinVisibleHeight, Math.min(this.mMaxVisibleSize.y, height3));
                width2 = Math.min(width3, Math.round(height23 / MIN_ASPECT));
                if (width2 < this.mMinVisibleWidth) {
                    width2 = this.mMinVisibleWidth;
                    width1 = height12;
                    height13 = height1;
                    height2 = Math.max(this.mMinVisibleHeight, Math.min(this.mMaxVisibleSize.y, Math.round(width2 * MIN_ASPECT)));
                } else {
                    width1 = height12;
                    height13 = height1;
                    height2 = height23;
                }
            }
            boolean grows = width3 > right - left || height3 > bottom - top;
            if (grows == (width1 * height13 > width2 * height2)) {
                height3 = height13;
                width = width1;
            } else {
                width = width2;
                height = height2;
                updateDraggedBounds(left, top, right, bottom, width, height);
            }
        }
        height = height3;
        updateDraggedBounds(left, top, right, bottom, width, height);
    }

    void updateDraggedBounds(int left, int top, int right, int bottom, int newWidth, int newHeight) {
        if ((this.mCtrlType & 1) != 0) {
            left = right - newWidth;
        } else {
            right = left + newWidth;
        }
        if ((this.mCtrlType & 4) != 0) {
            top = bottom - newHeight;
        } else {
            bottom = top + newHeight;
        }
        this.mWindowDragBounds.set(left, top, right, bottom);
        checkBoundsForOrientationViolations(this.mWindowDragBounds);
    }

    private void checkBoundsForOrientationViolations(Rect bounds) {
    }

    private void updateWindowDragBounds(int x, int y, Rect stackBounds) {
        int offsetX = Math.round(x - this.mStartDragX);
        int offsetY = Math.round(y - this.mStartDragY);
        this.mWindowDragBounds.set(this.mWindowOriginalBounds);
        int maxLeft = stackBounds.right - this.mMinVisibleWidth;
        int minLeft = (stackBounds.left + this.mMinVisibleWidth) - this.mWindowOriginalBounds.width();
        int minTop = stackBounds.top;
        int maxTop = stackBounds.bottom - this.mMinVisibleHeight;
        this.mWindowDragBounds.offsetTo(Math.min(Math.max(this.mWindowOriginalBounds.left + offsetX, minLeft), maxLeft), Math.min(Math.max(this.mWindowOriginalBounds.top + offsetY, minTop), maxTop));
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "updateWindowDragBounds: " + this.mWindowDragBounds);
        }
    }

    public String toShortString() {
        return TAG;
    }

    static void setFactory(Factory factory) {
        sFactory = factory;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static TaskPositioner create(WindowManagerService service) {
        if (sFactory == null) {
            sFactory = new Factory() { // from class: com.android.server.wm.TaskPositioner.1
            };
        }
        return sFactory.create(service);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface Factory {
        default TaskPositioner create(WindowManagerService service) {
            return new TaskPositioner(service);
        }
    }
}
