package com.android.server.wm;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class DragResizeMode {
    static final int DRAG_RESIZE_MODE_DOCKED_DIVIDER = 1;
    static final int DRAG_RESIZE_MODE_FREEFORM = 0;

    DragResizeMode() {
    }

    static boolean isResizeFreeformMode(int mode) {
        return mode == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isModeAllowedForStack(TaskStack stack, int mode) {
        if (mode == 0) {
            return stack.getWindowingMode() == 5;
        } else if (mode != 1) {
            return false;
        } else {
            return stack.inSplitScreenWindowingMode();
        }
    }
}
