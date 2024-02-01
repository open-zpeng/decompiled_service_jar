package com.android.server.wm;

import android.os.Looper;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class DragInputEventReceiver extends InputEventReceiver {
    private final DragDropController mDragDropController;
    private boolean mIsStartEvent;
    private boolean mMuteInput;
    private boolean mStylusButtonDownAtStart;

    /* JADX INFO: Access modifiers changed from: package-private */
    public DragInputEventReceiver(InputChannel inputChannel, Looper looper, DragDropController controller) {
        super(inputChannel, looper);
        this.mIsStartEvent = true;
        this.mMuteInput = false;
        this.mDragDropController = controller;
    }

    public void onInputEvent(InputEvent event, int displayId) {
        boolean handled = false;
        try {
            try {
            } catch (Exception e) {
                Slog.e("WindowManager", "Exception caught by drag handleMotion", e);
            }
            if ((event instanceof MotionEvent) && (event.getSource() & 2) != 0 && !this.mMuteInput) {
                MotionEvent motionEvent = (MotionEvent) event;
                float newX = motionEvent.getRawX();
                float newY = motionEvent.getRawY();
                boolean isStylusButtonDown = (motionEvent.getButtonState() & 32) != 0;
                if (this.mIsStartEvent) {
                    this.mStylusButtonDownAtStart = isStylusButtonDown;
                    this.mIsStartEvent = false;
                }
                switch (motionEvent.getAction()) {
                    case 0:
                        if (WindowManagerDebugConfig.DEBUG_DRAG) {
                            Slog.w("WindowManager", "Unexpected ACTION_DOWN in drag layer");
                        }
                        return;
                    case 1:
                        if (WindowManagerDebugConfig.DEBUG_DRAG) {
                            Slog.d("WindowManager", "Got UP on move channel; dropping at " + newX + "," + newY);
                        }
                        this.mMuteInput = true;
                        break;
                    case 2:
                        if (this.mStylusButtonDownAtStart && !isStylusButtonDown) {
                            if (WindowManagerDebugConfig.DEBUG_DRAG) {
                                Slog.d("WindowManager", "Button no longer pressed; dropping at " + newX + "," + newY);
                            }
                            this.mMuteInput = true;
                            break;
                        }
                        break;
                    case 3:
                        if (WindowManagerDebugConfig.DEBUG_DRAG) {
                            Slog.d("WindowManager", "Drag cancelled!");
                        }
                        this.mMuteInput = true;
                        break;
                    default:
                        return;
                }
                this.mDragDropController.handleMotionEvent(true ^ this.mMuteInput, newX, newY);
                handled = true;
            }
        } finally {
            finishInputEvent(event, false);
        }
    }
}
