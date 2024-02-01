package com.android.server.wm;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
import android.widget.OverScroller;
import com.android.server.usb.descriptors.UsbACInterface;
import com.xiaopeng.server.policy.xpSystemGesturesListener;
import com.xiaopeng.view.SharedDisplayManager;

/* loaded from: classes2.dex */
public class SystemGesturesPointerEventListener implements WindowManagerPolicyConstants.PointerEventListener {
    private static final int MAX_FLING_TIME_MILLIS = 5000;
    private static final int MAX_TRACKED_POINTERS = 32;
    public static final int SWIPE_FROM_BOTTOM = 2;
    public static final int SWIPE_FROM_LEFT = 4;
    public static final int SWIPE_FROM_RIGHT = 3;
    public static final int SWIPE_FROM_TOP = 1;
    public static final int SWIPE_NONE = 0;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int UNTRACKED_POINTER = -1;
    private final Callbacks mCallbacks;
    private final Context mContext;
    private boolean mDebugFireable;
    private int mDisplayCutoutTouchableRegionSize;
    private int mDownPointers;
    private GestureDetector mGestureDetector;
    private final Handler mHandler;
    private long mLastFlingTime;
    private boolean mMouseHoveringAtEdge;
    private int mSwipeDistanceThreshold;
    private boolean mSwipeFireable;
    private int mSwipeStartThreshold;
    int screenHeight;
    int screenWidth;
    private static final String TAG = "SystemGestures";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private final int[] mDownPointerId = new int[32];
    private final int[] mDownScreenId = new int[32];
    private final float[] mDownX = new float[32];
    private final float[] mDownY = new float[32];
    private final long[] mDownTime = new long[32];
    private xpSystemGesturesListener mSystemGestures = new xpSystemGesturesListener();

    /* loaded from: classes2.dex */
    public interface Callbacks {
        void onDebug();

        void onDown();

        void onFling(int i);

        void onMouseHoverAtBottom();

        void onMouseHoverAtTop();

        void onMouseLeaveFromEdge();

        void onSwipeFromBottom(int i, int i2);

        void onSwipeFromLeft(int i, int i2);

        void onSwipeFromMultiPointer(int i, int i2, int i3, boolean z, String str);

        void onSwipeFromRight(int i, int i2);

        void onSwipeFromTop(int i, int i2);

        void onUpOrCancel();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SystemGesturesPointerEventListener(Context context, Handler handler, Callbacks callbacks) {
        this.mContext = (Context) checkNull("context", context);
        this.mHandler = handler;
        this.mCallbacks = (Callbacks) checkNull("callbacks", callbacks);
        onConfigurationChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onConfigurationChanged() {
        this.mSwipeStartThreshold = this.mContext.getResources().getDimensionPixelSize(17105438);
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(0);
        DisplayCutout displayCutout = display.getCutout();
        if (displayCutout != null) {
            Rect bounds = displayCutout.getBoundingRectTop();
            if (!bounds.isEmpty()) {
                this.mDisplayCutoutTouchableRegionSize = this.mContext.getResources().getDimensionPixelSize(17105144);
                this.mSwipeStartThreshold += this.mDisplayCutoutTouchableRegionSize;
            }
        }
        this.mSwipeStartThreshold = SystemProperties.getInt("persist.data.gestures.swipethreshold", 80);
        this.mSwipeDistanceThreshold = this.mSwipeStartThreshold;
        if (DEBUG) {
            Slog.d(TAG, "mSwipeStartThreshold=" + this.mSwipeStartThreshold + " mSwipeDistanceThreshold=" + this.mSwipeDistanceThreshold);
        }
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    public /* synthetic */ void lambda$systemReady$0$SystemGesturesPointerEventListener() {
        this.mGestureDetector = new GestureDetector(this.mContext, new FlingGestureDetector(), this.mHandler) { // from class: com.android.server.wm.SystemGesturesPointerEventListener.1
        };
    }

    public void systemReady() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SystemGesturesPointerEventListener$9Iw39fjTtjXO5kacgrpdxfxjuSY
            @Override // java.lang.Runnable
            public final void run() {
                SystemGesturesPointerEventListener.this.lambda$systemReady$0$SystemGesturesPointerEventListener();
            }
        });
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SystemGesturesPointerEventListener$waZSsxfrwP2gM2ekiPdlwAEdQ9c
            @Override // java.lang.Runnable
            public final void run() {
                SystemGesturesPointerEventListener.this.lambda$systemReady$1$SystemGesturesPointerEventListener();
            }
        });
    }

    public /* synthetic */ void lambda$systemReady$1$SystemGesturesPointerEventListener() {
        this.mSystemGestures.init();
    }

    public void onPointerEvent(MotionEvent event) {
        if (this.mGestureDetector != null && event.isTouchEvent()) {
            this.mGestureDetector.onTouchEvent(event);
        }
        xpSystemGesturesListener xpsystemgestureslistener = this.mSystemGestures;
        xpsystemgestureslistener.screenWidth = this.screenWidth;
        xpsystemgestureslistener.screenHeight = this.screenHeight;
        int actionMasked = event.getActionMasked();
        if (actionMasked == 0) {
            this.mDebugFireable = true;
            this.mDownPointers = 0;
            captureDown(event, 0, SharedDisplayManager.findScreenId(0, event));
            if (this.mMouseHoveringAtEdge) {
                this.mMouseHoveringAtEdge = false;
                this.mCallbacks.onMouseLeaveFromEdge();
            }
            this.mCallbacks.onDown();
        } else if (actionMasked == 1) {
            this.mSystemGestures.captureUpOrCancel(event, SharedDisplayManager.findScreenId(event.getActionIndex(), event), this.mCallbacks, this.mDownX, this.mDownY, this.mDownTime);
        } else if (actionMasked == 2) {
            int index = event.getActionIndex();
            int count = event.getPointerCount();
            for (int i = 0; i < count; i++) {
                int pointerId = event.getPointerId(i);
                int moveIndex = event.findPointerIndex(pointerId);
                int screenId = SharedDisplayManager.findScreenId(moveIndex, event);
                float moveX = event.getX(moveIndex);
                float moveY = event.getY(moveIndex);
                long moveTime = event.getEventTime();
                if (DEBUG) {
                    Slog.d(TAG, "SGPEL.onPointerEvent check when case MotionEvent.ACTION_MOVE i=" + i + " count=" + count + " index=" + index + " moveIndex=" + moveIndex + " pointerId=" + pointerId + " screenId=" + screenId + " moveX=" + moveX + " moveY=" + moveY + " moveTime=" + moveTime + " event=" + event);
                }
                this.mSystemGestures.onMultiPointerEvent(event, screenId, this.mCallbacks, this.mDownX, this.mDownY, this.mDownTime);
            }
        } else if (actionMasked == 3) {
            this.mSystemGestures.captureUpOrCancel(event, SharedDisplayManager.findScreenId(event.getActionIndex(), event), this.mCallbacks, this.mDownX, this.mDownY, this.mDownTime);
            this.mDebugFireable = false;
            this.mCallbacks.onUpOrCancel();
        } else if (actionMasked == 5) {
            captureDown(event, event.getActionIndex(), SharedDisplayManager.findScreenId(event.getActionIndex(), event));
            if (this.mDebugFireable) {
                this.mDebugFireable = event.getPointerCount() < 5;
                if (!this.mDebugFireable) {
                    if (DEBUG) {
                        Slog.d(TAG, "Firing debug");
                    }
                    this.mCallbacks.onDebug();
                }
            }
        } else if (actionMasked == 6) {
            this.mSystemGestures.captureUpOrCancel(event, SharedDisplayManager.findScreenId(event.getActionIndex(), event), this.mCallbacks, this.mDownX, this.mDownY, this.mDownTime);
        } else if (actionMasked == 7) {
            if (event.isFromSource(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1)) {
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
        } else if (DEBUG) {
            Slog.d(TAG, "Ignoring " + event);
        }
        this.mSystemGestures.onPointerEvent(this.mContext, event);
    }

    private void captureDown(MotionEvent event, int pointerIndex, int screenId) {
        int pointerId = event.getPointerId(pointerIndex);
        int i = findIndex(pointerId);
        if (DEBUG) {
            Slog.d(TAG, "pointer " + pointerId + " down pointerIndex=" + pointerIndex + " trackingIndex=" + i + " screenId=" + screenId);
        }
        if (i != -1) {
            this.mDownX[i] = event.getX(pointerIndex);
            this.mDownY[i] = event.getY(pointerIndex);
            this.mDownTime[i] = event.getEventTime();
            this.mDownScreenId[i] = screenId;
            if (DEBUG) {
                Slog.d(TAG, "pointer " + pointerId + " down x=" + this.mDownX[i] + " y=" + this.mDownY[i] + " t=" + this.mDownTime[i] + " down screenId=" + this.mDownScreenId[i]);
            }
        }
        this.mSystemGestures.captureDown(event, pointerIndex, screenId, this.mDownX, this.mDownY, this.mDownTime, this.mDownScreenId, this.mDownPointerId);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean currentGestureStartedInRegion(Region r) {
        return r.contains((int) this.mDownX[0], (int) this.mDownY[0]);
    }

    private int findIndex(int pointerId) {
        int i = 0;
        while (true) {
            int i2 = this.mDownPointers;
            if (i < i2) {
                if (this.mDownPointerId[i] != pointerId) {
                    i++;
                } else {
                    return i;
                }
            } else if (i2 == 32 || pointerId == -1) {
                return -1;
            } else {
                int[] iArr = this.mDownPointerId;
                this.mDownPointers = i2 + 1;
                iArr[i2] = pointerId;
                return this.mDownPointers - 1;
            }
        }
    }

    private int detectSwipe(MotionEvent move) {
        int historySize = move.getHistorySize();
        int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            int pointerId = move.getPointerId(p);
            int i = findIndex(pointerId);
            if (i != -1) {
                for (int h = 0; h < historySize; h++) {
                    long time = move.getHistoricalEventTime(h);
                    float x = move.getHistoricalX(p, h);
                    float y = move.getHistoricalY(p, h);
                    int swipe = detectSwipe(i, time, x, y);
                    if (swipe != 0) {
                        return swipe;
                    }
                }
                int swipe2 = detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
                if (swipe2 != 0) {
                    return swipe2;
                }
            }
        }
        return 0;
    }

    private int detectSwipe(int i, long time, float x, float y) {
        float fromX = this.mDownX[i];
        float fromY = this.mDownY[i];
        long elapsed = time - this.mDownTime[i];
        if (DEBUG) {
            Slog.d(TAG, "pointer " + this.mDownPointerId[i] + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        }
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

    /* loaded from: classes2.dex */
    private final class FlingGestureDetector extends GestureDetector.SimpleOnGestureListener {
        private OverScroller mOverscroller;

        FlingGestureDetector() {
            this.mOverscroller = new OverScroller(SystemGesturesPointerEventListener.this.mContext);
        }

        @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
        public boolean onSingleTapUp(MotionEvent e) {
            if (!this.mOverscroller.isFinished()) {
                this.mOverscroller.forceFinished(true);
            }
            return true;
        }

        @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
        public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
            this.mOverscroller.computeScrollOffset();
            long now = SystemClock.uptimeMillis();
            if (SystemGesturesPointerEventListener.this.mLastFlingTime != 0 && now > SystemGesturesPointerEventListener.this.mLastFlingTime + 5000) {
                this.mOverscroller.forceFinished(true);
            }
            this.mOverscroller.fling(0, 0, (int) velocityX, (int) velocityY, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int duration = this.mOverscroller.getDuration();
            if (duration > 5000) {
                duration = 5000;
            }
            SystemGesturesPointerEventListener.this.mLastFlingTime = now;
            SystemGesturesPointerEventListener.this.mCallbacks.onFling(duration);
            return true;
        }
    }
}
