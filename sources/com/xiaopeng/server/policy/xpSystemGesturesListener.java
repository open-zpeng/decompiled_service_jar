package com.xiaopeng.server.policy;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import com.android.server.policy.SystemGesturesPointerEventListener;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.bi.BiDataManager;
import com.xiaopeng.input.xpInputManager;
import com.xiaopeng.util.xpLogger;
/* loaded from: classes.dex */
public class xpSystemGesturesListener {
    private static final int MAX_MOVE_ANGLE_FORM_BOTTOM = 120;
    private static final int MAX_MOVE_ANGLE_FORM_TOP = 60;
    private static final int MULTI_POINTER = 3;
    private static final int MULTI_POINTER_DISTANCE_MAX = 12;
    private static final int MULTI_POINTER_DISTANCE_MIN = 1;
    public static final int MULTI_SWIPE_FROM_BOTTOM = 2;
    public static final int MULTI_SWIPE_FROM_LEFT = 4;
    public static final int MULTI_SWIPE_FROM_NONE = 0;
    public static final int MULTI_SWIPE_FROM_RIGHT = 3;
    public static final int MULTI_SWIPE_FROM_TOP = 1;
    private boolean mMultiPointer = false;
    public int screenHeight;
    public int screenWidth;
    private static final String TAG = "xpSystemGesturesListener";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    /* loaded from: classes.dex */
    public static final class Pointer {
        public float x;
        public float y;
    }

    public void onPointerEvent(MotionEvent event) {
        int actionMasked = event.getActionMasked();
        if (actionMasked == 1 || actionMasked == 3 || actionMasked == 6) {
            this.mMultiPointer = false;
        }
    }

    public void captureDown(MotionEvent event, int pointerIndex, float[] downX, float[] downY, long[] downTime) {
        boolean multiPointerValid = event != null && event.getPointerCount() == 3;
        boolean isTouchDistanceValid = isTouchDistanceValid(downX, downY);
        boolean isTouchTimeValid = isTouchTimeValid(downTime);
        if (DEBUG) {
            xpLogger.i(TAG, "captureDown multiPointerValid=" + multiPointerValid + " isTouchDistanceValid=" + isTouchDistanceValid + " isTouchTimeValid=" + isTouchTimeValid);
        }
        if (multiPointerValid && isTouchDistanceValid && isTouchTimeValid) {
            this.mMultiPointer = true;
        } else {
            this.mMultiPointer = false;
        }
    }

    public void onMultiPointerEvent(MotionEvent event, SystemGesturesPointerEventListener.Callbacks callbacks, float[] downX, float[] downY, long[] downTime) {
        if (this.mMultiPointer) {
            int direction = getSwipeDirection(event, downX, downY);
            boolean isSwipeAngleValid = direction > 0;
            boolean isSwipeDistanceValid = isSwipeDistanceValid(event, downX, downY);
            if (DEBUG) {
                xpLogger.i(TAG, "onMultiPointerEvent direction=" + direction + " isSwipeAngleValid=" + isSwipeAngleValid + " isSwipeDistanceValid=" + isSwipeDistanceValid);
            }
            if (isSwipeAngleValid && isSwipeDistanceValid) {
                this.mMultiPointer = false;
                callbacks.onSwipeFromMultiPointer(direction);
            }
        }
    }

    private boolean isTouchTimeValid(long[] downTime) {
        return downTime != null && downTime.length >= 3 && downTime[2] - downTime[0] < 50 && downTime[1] - downTime[0] < 50 && downTime[2] - downTime[0] >= 0 && downTime[1] - downTime[0] >= 0;
    }

    private boolean isTouchDistanceValid(float[] downX, float[] downY) {
        double pixel = centimeterToPixel(this.screenHeight, 12);
        double distance0 = getDistance(getPointer(0, downX, downY), getPointer(1, downX, downY));
        double distance1 = getDistance(getPointer(0, downX, downY), getPointer(2, downX, downY));
        double distance2 = getDistance(getPointer(1, downX, downY), getPointer(2, downX, downY));
        if (DEBUG) {
            xpLogger.i(TAG, "isPointerDistanceValid distance0=" + distance0 + " distance1=" + distance1 + " distance2=" + distance2 + " pixel=" + pixel);
        }
        return distance0 < pixel && distance1 < pixel && distance2 < pixel;
    }

    private boolean isSwipeDistanceValid(MotionEvent event, float[] downX, float[] downY) {
        if (event == null || event.getPointerCount() != 3) {
            return false;
        }
        double pixel = centimeterToPixel(this.screenHeight, 1);
        double distance0 = getDistance(getPointer(0, event), getPointer(0, downX, downY));
        double distance1 = getDistance(getPointer(1, event), getPointer(1, downX, downY));
        double distance2 = getDistance(getPointer(2, event), getPointer(2, downX, downY));
        if (DEBUG) {
            xpLogger.i(TAG, "isSwipeDistanceValid distance0=" + distance0 + " distance1=" + distance1 + " distance2=" + distance2 + " pixel=" + pixel);
        }
        return distance0 > pixel && distance1 > pixel && distance2 > pixel;
    }

    private int getSwipeDirection(MotionEvent event, float[] downX, float[] downY) {
        boolean swipeFromTop;
        boolean swipeFromBottom;
        Pointer[] p = new Pointer[3];
        Pointer[] dp = new Pointer[3];
        if (event != null && downX != null && downY != null) {
            for (int i = 0; i < 3; i++) {
                try {
                    p[i] = getPointer(i, event);
                    dp[i] = getPointer(i, downX, downY);
                } catch (Exception e) {
                }
            }
        }
        int angle0 = getAngle(p[0], dp[0]);
        int angle1 = getAngle(p[1], dp[1]);
        int angle2 = getAngle(p[2], dp[2]);
        if (p[0].y <= dp[0].y || p[1].y <= dp[1].y || p[2].y <= dp[2].y || angle0 > 60 || angle1 > 60 || angle2 > 60) {
            swipeFromTop = false;
        } else {
            swipeFromTop = true;
        }
        if (p[0].y >= dp[0].y || p[1].y >= dp[1].y || p[2].y >= dp[2].y || angle0 > MAX_MOVE_ANGLE_FORM_BOTTOM || angle1 > MAX_MOVE_ANGLE_FORM_BOTTOM || angle2 > MAX_MOVE_ANGLE_FORM_BOTTOM) {
            swipeFromBottom = false;
        } else {
            swipeFromBottom = true;
        }
        if (swipeFromTop) {
            return 1;
        }
        if (!swipeFromBottom) {
            return 0;
        }
        return 2;
    }

    public static int getAngle(Pointer start, Pointer end) {
        if (start != null && end != null) {
            float x = Math.abs(end.x - start.x);
            float y = Math.abs(end.y - start.y);
            double distance = Math.sqrt((x * x) + (y * y));
            return Math.round((float) ((Math.asin(x / distance) / 3.141592653589793d) * 180.0d));
        }
        return 0;
    }

    public static double getDistance(Pointer start, Pointer end) {
        if (start != null && end != null) {
            return Math.sqrt(Math.pow(end.x - start.x, 2.0d) + Math.pow(end.y - start.y, 2.0d));
        }
        return 0.0d;
    }

    public static Pointer getPointer(int index, MotionEvent event) {
        Pointer pointer = new Pointer();
        if (event != null && index >= 0) {
            int count = event.getPointerCount();
            pointer.x = event.getX(index < count ? index : count - 1);
            pointer.y = event.getY(index < count ? index : count - 1);
        }
        return pointer;
    }

    public static Pointer getPointer(int index, float[] x, float[] y) {
        Pointer pointer = new Pointer();
        if (index < x.length && index < y.length) {
            pointer.x = x[index];
            pointer.y = y[index];
        }
        return pointer;
    }

    public static double centimeterToPixel(double screenHeight, int centimeter) {
        double height = (screenHeight / DisplayMetrics.DENSITY_DEVICE_STABLE) * 2.53d;
        return (centimeter / height) * screenHeight;
    }

    public static void handleSystemGestureEvent(int direction, boolean multi, Context context, WindowManagerPolicy.WindowState focusWindow) {
        if (multi) {
            if (xpPhoneWindowManager.isHighLevelActivity(context, focusWindow)) {
                return;
            }
            switch (direction) {
                case 1:
                    xpInputManager.sendEvent(3);
                    BiDataManager.sendStatData(10001);
                    return;
                case 2:
                    xpActivityManager.zoomTopActivity(context);
                    return;
                default:
                    return;
            }
        }
        switch (direction) {
            case 2:
                xpActivityManager.stopTopActivity(context);
                return;
            case 3:
                xpActivityManager.zoomTopActivity(context);
                return;
            default:
                return;
        }
    }
}
