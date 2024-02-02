package com.android.server.accessibility;

import android.util.MathUtils;
import android.view.MotionEvent;
/* loaded from: classes.dex */
final class GestureUtils {
    private GestureUtils() {
    }

    public static boolean isMultiTap(MotionEvent firstUp, MotionEvent secondUp, int multiTapTimeSlop, int multiTapDistanceSlop) {
        if (firstUp == null || secondUp == null) {
            return false;
        }
        return eventsWithinTimeAndDistanceSlop(firstUp, secondUp, multiTapTimeSlop, multiTapDistanceSlop);
    }

    private static boolean eventsWithinTimeAndDistanceSlop(MotionEvent first, MotionEvent second, int timeout, int distance) {
        if (isTimedOut(first, second, timeout)) {
            return false;
        }
        double deltaMove = distance(first, second);
        return deltaMove < ((double) distance);
    }

    public static double distance(MotionEvent first, MotionEvent second) {
        return MathUtils.dist(first.getX(), first.getY(), second.getX(), second.getY());
    }

    public static boolean isTimedOut(MotionEvent firstUp, MotionEvent secondUp, int timeout) {
        long deltaTime = secondUp.getEventTime() - firstUp.getEventTime();
        return deltaTime >= ((long) timeout);
    }

    public static boolean isDraggingGesture(float firstPtrDownX, float firstPtrDownY, float secondPtrDownX, float secondPtrDownY, float firstPtrX, float firstPtrY, float secondPtrX, float secondPtrY, float maxDraggingAngleCos) {
        float firstDeltaX = firstPtrX - firstPtrDownX;
        float firstDeltaY = firstPtrY - firstPtrDownY;
        if (firstDeltaX == 0.0f && firstDeltaY == 0.0f) {
            return true;
        }
        float firstMagnitude = (float) Math.hypot(firstDeltaX, firstDeltaY);
        float firstXNormalized = firstMagnitude > 0.0f ? firstDeltaX / firstMagnitude : firstDeltaX;
        float firstYNormalized = firstMagnitude > 0.0f ? firstDeltaY / firstMagnitude : firstDeltaY;
        float secondDeltaX = secondPtrX - secondPtrDownX;
        float secondDeltaY = secondPtrY - secondPtrDownY;
        if (secondDeltaX == 0.0f && secondDeltaY == 0.0f) {
            return true;
        }
        float secondMagnitude = (float) Math.hypot(secondDeltaX, secondDeltaY);
        float secondXNormalized = secondMagnitude > 0.0f ? secondDeltaX / secondMagnitude : secondDeltaX;
        float secondYNormalized = secondMagnitude > 0.0f ? secondDeltaY / secondMagnitude : secondDeltaY;
        float angleCos = (firstXNormalized * secondXNormalized) + (firstYNormalized * secondYNormalized);
        if (angleCos < maxDraggingAngleCos) {
            return false;
        }
        return true;
    }
}
