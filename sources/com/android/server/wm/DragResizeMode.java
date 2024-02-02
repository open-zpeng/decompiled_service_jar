package com.android.server.wm;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class DragResizeMode {
    static final int DRAG_RESIZE_MODE_DOCKED_DIVIDER = 1;
    static final int DRAG_RESIZE_MODE_FREEFORM = 0;

    DragResizeMode() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isResizeFreeformMode(int mode) {
        return mode == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isModeAllowedForStack(TaskStack stack, int mode) {
        switch (mode) {
            case 0:
                return false;
            case 1:
                return stack.inSplitScreenWindowingMode();
            default:
                return false;
        }
    }
}
