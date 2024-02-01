package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;

/* loaded from: classes2.dex */
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

    public void onPointerEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            int x = (int) motionEvent.getX();
            int y = (int) motionEvent.getY();
            synchronized (this) {
                if (!this.mTouchExcludeRegion.contains(x, y)) {
                    this.mService.mTaskPositioningController.handleTapOutsideTask(this.mDisplayContent, x, y);
                }
            }
        } else if (actionMasked != 7 && actionMasked != 9) {
            if (actionMasked == 10) {
                int x2 = (int) motionEvent.getX();
                int y2 = (int) motionEvent.getY();
                if (this.mPointerIconType != 1) {
                    this.mPointerIconType = 1;
                    this.mService.mH.removeMessages(55);
                    this.mService.mH.obtainMessage(55, x2, y2, this.mDisplayContent).sendToTarget();
                }
            }
        } else {
            int x3 = (int) motionEvent.getX();
            int y3 = (int) motionEvent.getY();
            Task task = this.mDisplayContent.findTaskForResizePoint(x3, y3);
            int iconType = 1;
            if (task != null) {
                task.getDimBounds(this.mTmpRect);
                if (!this.mTmpRect.isEmpty() && !this.mTmpRect.contains(x3, y3)) {
                    int i = 1014;
                    if (x3 < this.mTmpRect.left) {
                        if (y3 < this.mTmpRect.top) {
                            i = 1017;
                        } else if (y3 > this.mTmpRect.bottom) {
                            i = 1016;
                        }
                        iconType = i;
                    } else if (x3 > this.mTmpRect.right) {
                        if (y3 < this.mTmpRect.top) {
                            i = 1016;
                        } else if (y3 > this.mTmpRect.bottom) {
                            i = 1017;
                        }
                        iconType = i;
                    } else if (y3 < this.mTmpRect.top || y3 > this.mTmpRect.bottom) {
                        iconType = 1015;
                    }
                }
            }
            if (this.mPointerIconType != iconType) {
                this.mPointerIconType = iconType;
                if (this.mPointerIconType == 1) {
                    this.mService.mH.removeMessages(55);
                    this.mService.mH.obtainMessage(55, x3, y3, this.mDisplayContent).sendToTarget();
                    return;
                }
                InputManager.getInstance().setPointerIconType(this.mPointerIconType);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTouchExcludeRegion(Region newRegion) {
        synchronized (this) {
            this.mTouchExcludeRegion.set(newRegion);
        }
    }
}
