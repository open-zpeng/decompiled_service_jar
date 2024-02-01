package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
/* loaded from: classes.dex */
public class TaskTapPointerEventListener implements WindowManagerPolicyConstants.PointerEventListener {
    private final DisplayContent mDisplayContent;
    private final WindowManagerService mService;
    private final Region mTouchExcludeRegion = new Region();
    private final Rect mTmpRect = new Rect();
    private int mPointerIconType = 1;

    public TaskTapPointerEventListener(WindowManagerService service, DisplayContent displayContent) {
        this.mService = service;
        this.mDisplayContent = displayContent;
    }

    public void onPointerEvent(MotionEvent motionEvent, int displayId) {
        if (displayId == getDisplayId()) {
            onPointerEvent(motionEvent);
        }
    }

    public void onPointerEvent(MotionEvent motionEvent) {
        int action = motionEvent.getAction();
        int i = action & 255;
        if (i == 0) {
            int x = (int) motionEvent.getX();
            int y = (int) motionEvent.getY();
            synchronized (this) {
                if (!this.mTouchExcludeRegion.contains(x, y)) {
                    this.mService.mTaskPositioningController.handleTapOutsideTask(this.mDisplayContent, x, y);
                }
            }
        } else if (i == 7) {
            int x2 = (int) motionEvent.getX();
            int y2 = (int) motionEvent.getY();
            Task task = this.mDisplayContent.findTaskForResizePoint(x2, y2);
            int iconType = 1;
            if (task != null) {
                task.getDimBounds(this.mTmpRect);
                if (!this.mTmpRect.isEmpty() && !this.mTmpRect.contains(x2, y2)) {
                    int i2 = 1014;
                    if (x2 < this.mTmpRect.left) {
                        if (y2 >= this.mTmpRect.top) {
                            if (y2 > this.mTmpRect.bottom) {
                                i2 = 1016;
                            }
                        } else {
                            i2 = 1017;
                        }
                        iconType = i2;
                    } else if (x2 > this.mTmpRect.right) {
                        if (y2 >= this.mTmpRect.top) {
                            if (y2 > this.mTmpRect.bottom) {
                                i2 = 1017;
                            }
                        } else {
                            i2 = 1016;
                        }
                        iconType = i2;
                    } else if (y2 < this.mTmpRect.top || y2 > this.mTmpRect.bottom) {
                        iconType = 1015;
                    }
                }
            }
            if (this.mPointerIconType != iconType) {
                this.mPointerIconType = iconType;
                if (this.mPointerIconType == 1) {
                    this.mService.mH.obtainMessage(55, x2, y2, this.mDisplayContent).sendToTarget();
                } else {
                    InputManager.getInstance().setPointerIconType(this.mPointerIconType);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTouchExcludeRegion(Region newRegion) {
        synchronized (this) {
            this.mTouchExcludeRegion.set(newRegion);
        }
    }

    private int getDisplayId() {
        return this.mDisplayContent.getDisplayId();
    }
}
