package com.android.server.policy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
import android.widget.OverScroller;
import com.android.server.usb.descriptors.UsbACInterface;
import com.xiaopeng.server.policy.xpSystemGesturesListener;
/* loaded from: classes.dex */
public class SystemGesturesPointerEventListener implements WindowManagerPolicyConstants.PointerEventListener {
    private static final boolean DEBUG = false;
    private static final int MAX_FLING_TIME_MILLIS = 5000;
    private static final int MAX_TRACKED_POINTERS = 32;
    public static final int SWIPE_FROM_BOTTOM = 2;
    public static final int SWIPE_FROM_LEFT = 4;
    public static final int SWIPE_FROM_RIGHT = 3;
    public static final int SWIPE_FROM_TOP = 1;
    public static final int SWIPE_NONE = 0;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final String TAG = "SystemGestures";
    private static final int UNTRACKED_POINTER = -1;
    private final Callbacks mCallbacks;
    private final Context mContext;
    private boolean mDebugFireable;
    private int mDownPointers;
    private GestureDetector mGestureDetector;
    private long mLastFlingTime;
    private boolean mMouseHoveringAtEdge;
    private OverScroller mOverscroller;
    private boolean mSwipeFireable;
    int screenHeight;
    int screenWidth;
    private final int[] mDownPointerId = new int[32];
    private final float[] mDownX = new float[32];
    private final float[] mDownY = new float[32];
    private final long[] mDownTime = new long[32];
    private xpSystemGesturesListener mSystemGestures = new xpSystemGesturesListener();
    private final int mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 80);
    private final int mSwipeDistanceThreshold = this.mSwipeStartThreshold;

    /* loaded from: classes.dex */
    public interface Callbacks {
        void onDebug();

        void onDown();

        void onFling(int i);

        void onMouseHoverAtBottom();

        void onMouseHoverAtTop();

        void onMouseLeaveFromEdge();

        void onSwipeFromBottom();

        void onSwipeFromLeft();

        void onSwipeFromMultiPointer(int i);

        void onSwipeFromRight();

        void onSwipeFromTop();

        void onUpOrCancel();
    }

    public SystemGesturesPointerEventListener(Context context, Callbacks callbacks) {
        this.mContext = context;
        this.mCallbacks = (Callbacks) checkNull("callbacks", callbacks);
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    public void systemReady() {
        Handler h = new Handler(Looper.myLooper());
        this.mGestureDetector = new GestureDetector(this.mContext, new FlingGestureDetector(), h);
        this.mOverscroller = new OverScroller(this.mContext);
    }

    public void onPointerEvent(MotionEvent event) {
        if (this.mGestureDetector != null && event.isTouchEvent()) {
            this.mGestureDetector.onTouchEvent(event);
        }
        this.mSystemGestures.screenWidth = this.screenWidth;
        this.mSystemGestures.screenHeight = this.screenHeight;
        int actionMasked = event.getActionMasked();
        if (actionMasked == 5) {
            captureDown(event, event.getActionIndex());
            if (this.mDebugFireable) {
                this.mDebugFireable = event.getPointerCount() < 5;
                if (!this.mDebugFireable) {
                    this.mCallbacks.onDebug();
                }
            }
        } else if (actionMasked != 7) {
            switch (actionMasked) {
                case 0:
                    this.mSwipeFireable = true;
                    this.mDebugFireable = true;
                    this.mDownPointers = 0;
                    captureDown(event, 0);
                    if (this.mMouseHoveringAtEdge) {
                        this.mMouseHoveringAtEdge = false;
                        this.mCallbacks.onMouseLeaveFromEdge();
                    }
                    this.mCallbacks.onDown();
                    break;
                case 1:
                case 3:
                    this.mSwipeFireable = false;
                    this.mDebugFireable = false;
                    this.mCallbacks.onUpOrCancel();
                    break;
                case 2:
                    if (this.mSwipeFireable) {
                        int swipe = detectSwipe(event);
                        this.mSwipeFireable = swipe == 0;
                        if (swipe == 1) {
                            this.mCallbacks.onSwipeFromTop();
                            break;
                        } else if (swipe == 2) {
                            this.mCallbacks.onSwipeFromBottom();
                            break;
                        } else if (swipe == 3) {
                            this.mCallbacks.onSwipeFromRight();
                            break;
                        } else if (swipe == 4) {
                            this.mCallbacks.onSwipeFromLeft();
                            break;
                        } else {
                            this.mSystemGestures.onMultiPointerEvent(event, this.mCallbacks, this.mDownX, this.mDownY, this.mDownTime);
                            break;
                        }
                    }
                    break;
            }
        } else if (event.isFromSource(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1)) {
            if (!this.mMouseHoveringAtEdge && event.getY() == 0.0f) {
                this.mCallbacks.onMouseHoverAtTop();
                this.mMouseHoveringAtEdge = true;
            } else if (!this.mMouseHoveringAtEdge && event.getY() >= this.screenHeight - 1) {
                this.mCallbacks.onMouseHoverAtBottom();
                this.mMouseHoveringAtEdge = true;
            } else if (this.mMouseHoveringAtEdge && event.getY() > 0.0f && event.getY() < this.screenHeight - 1) {
                this.mCallbacks.onMouseLeaveFromEdge();
                this.mMouseHoveringAtEdge = false;
            }
        }
        this.mSystemGestures.onPointerEvent(event);
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        int pointerId = event.getPointerId(pointerIndex);
        int i = findIndex(pointerId);
        if (i != -1) {
            this.mDownX[i] = event.getX(pointerIndex);
            this.mDownY[i] = event.getY(pointerIndex);
            this.mDownTime[i] = event.getEventTime();
        }
        this.mSystemGestures.captureDown(event, pointerIndex, this.mDownX, this.mDownY, this.mDownTime);
    }

    private int findIndex(int pointerId) {
        for (int i = 0; i < this.mDownPointers; i++) {
            if (this.mDownPointerId[i] == pointerId) {
                return i;
            }
        }
        int i2 = this.mDownPointers;
        if (i2 == 32 || pointerId == -1) {
            return -1;
        }
        int[] iArr = this.mDownPointerId;
        int i3 = this.mDownPointers;
        this.mDownPointers = i3 + 1;
        iArr[i3] = pointerId;
        return this.mDownPointers - 1;
    }

    private int detectSwipe(MotionEvent move) {
        int historySize = move.getHistorySize();
        int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            int pointerId = move.getPointerId(p);
            int i = findIndex(pointerId);
            if (i != -1) {
                int swipe = 0;
                while (true) {
                    int h = swipe;
                    if (h >= historySize) {
                        int swipe2 = detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
                        if (swipe2 != 0) {
                            return swipe2;
                        }
                    } else {
                        long time = move.getHistoricalEventTime(h);
                        float x = move.getHistoricalX(p, h);
                        float y = move.getHistoricalY(p, h);
                        int swipe3 = detectSwipe(i, time, x, y);
                        if (swipe3 == 0) {
                            swipe = h + 1;
                        } else {
                            return swipe3;
                        }
                    }
                }
            }
        }
        return 0;
    }

    private int detectSwipe(int i, long time, float x, float y) {
        float fromX = this.mDownX[i];
        float fromY = this.mDownY[i];
        long elapsed = time - this.mDownTime[i];
        if (fromY <= this.mSwipeStartThreshold && y > this.mSwipeDistanceThreshold + fromY && elapsed < 500) {
            return 1;
        }
        if (fromY >= this.screenHeight - this.mSwipeStartThreshold && y < fromY - this.mSwipeDistanceThreshold && elapsed < 500) {
            return 2;
        }
        if (fromX >= this.screenWidth - this.mSwipeStartThreshold && x < fromX - this.mSwipeDistanceThreshold && elapsed < 500) {
            return 3;
        }
        if (fromX <= this.mSwipeStartThreshold && x > this.mSwipeDistanceThreshold + fromX && elapsed < 500) {
            return 4;
        }
        return 0;
    }

    /* loaded from: classes.dex */
    private final class FlingGestureDetector extends GestureDetector.SimpleOnGestureListener {
        private FlingGestureDetector() {
        }

        @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
        public boolean onSingleTapUp(MotionEvent e) {
            if (!SystemGesturesPointerEventListener.this.mOverscroller.isFinished()) {
                SystemGesturesPointerEventListener.this.mOverscroller.forceFinished(true);
            }
            return true;
        }

        @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
        public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
            SystemGesturesPointerEventListener.this.mOverscroller.computeScrollOffset();
            long now = SystemClock.uptimeMillis();
            if (SystemGesturesPointerEventListener.this.mLastFlingTime != 0 && now > SystemGesturesPointerEventListener.this.mLastFlingTime + 5000) {
                SystemGesturesPointerEventListener.this.mOverscroller.forceFinished(true);
            }
            SystemGesturesPointerEventListener.this.mOverscroller.fling(0, 0, (int) velocityX, (int) velocityY, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int duration = SystemGesturesPointerEventListener.this.mOverscroller.getDuration();
            if (duration > SystemGesturesPointerEventListener.MAX_FLING_TIME_MILLIS) {
                duration = SystemGesturesPointerEventListener.MAX_FLING_TIME_MILLIS;
            }
            SystemGesturesPointerEventListener.this.mLastFlingTime = now;
            SystemGesturesPointerEventListener.this.mCallbacks.onFling(duration);
            return true;
        }
    }
}
