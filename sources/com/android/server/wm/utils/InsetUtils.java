package com.android.server.wm.utils;

import android.graphics.Rect;
/* loaded from: classes.dex */
public class InsetUtils {
    private InsetUtils() {
    }

    public static void rotateInsets(Rect inOutInsets, int rotationDelta) {
        switch (rotationDelta) {
            case 0:
                return;
            case 1:
                inOutInsets.set(inOutInsets.top, inOutInsets.right, inOutInsets.bottom, inOutInsets.left);
                return;
            case 2:
                inOutInsets.set(inOutInsets.right, inOutInsets.bottom, inOutInsets.left, inOutInsets.top);
                return;
            case 3:
                inOutInsets.set(inOutInsets.bottom, inOutInsets.left, inOutInsets.top, inOutInsets.right);
                return;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotationDelta);
        }
    }

    public static void addInsets(Rect inOutInsets, Rect insetsToAdd) {
        inOutInsets.left += insetsToAdd.left;
        inOutInsets.top += insetsToAdd.top;
        inOutInsets.right += insetsToAdd.right;
        inOutInsets.bottom += insetsToAdd.bottom;
    }
}
