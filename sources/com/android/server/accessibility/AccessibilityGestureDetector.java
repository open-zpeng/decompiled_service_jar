package com.android.server.accessibility;

import android.content.Context;
import android.gesture.GesturePoint;
import android.graphics.PointF;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import java.util.ArrayList;

/* loaded from: classes.dex */
class AccessibilityGestureDetector extends GestureDetector.SimpleOnGestureListener {
    private static final float ANGLE_THRESHOLD = 0.0f;
    private static final long CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS = 150;
    private static final long CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS = 300;
    private static final boolean DEBUG = false;
    private static final int[][] DIRECTIONS_TO_GESTURE_ID = {new int[]{3, 5, 9, 10}, new int[]{6, 4, 11, 12}, new int[]{13, 14, 1, 7}, new int[]{15, 16, 8, 2}};
    private static final int DOWN = 3;
    private static final int GESTURE_CONFIRM_MM = 10;
    private static final int LEFT = 0;
    private static final String LOG_TAG = "AccessibilityGestureDetector";
    private static final float MIN_INCHES_BETWEEN_SAMPLES = 0.1f;
    private static final float MIN_PREDICTION_SCORE = 2.0f;
    private static final int RIGHT = 1;
    private static final int TOUCH_TOLERANCE = 3;
    private static final int UP = 2;
    private long mBaseTime;
    private float mBaseX;
    private float mBaseY;
    private final Context mContext;
    private boolean mDoubleTapDetected;
    private boolean mFirstTapDetected;
    private final float mGestureDetectionThreshold;
    private final GestureDetector mGestureDetector;
    private boolean mGestureStarted;
    private final Listener mListener;
    private final float mMinPixelsBetweenSamplesX;
    private final float mMinPixelsBetweenSamplesY;
    private int mPolicyFlags;
    private float mPreviousGestureX;
    private float mPreviousGestureY;
    private boolean mRecognizingGesture;
    private boolean mSecondFingerDoubleTap;
    private long mSecondPointerDownTime;
    private final ArrayList<GesturePoint> mStrokeBuffer;

    /* loaded from: classes.dex */
    public interface Listener {
        boolean onDoubleTap(MotionEvent motionEvent, int i);

        void onDoubleTapAndHold(MotionEvent motionEvent, int i);

        boolean onGestureCancelled(MotionEvent motionEvent, int i);

        boolean onGestureCompleted(int i);

        boolean onGestureStarted();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AccessibilityGestureDetector(Context context, Listener listener) {
        this(context, listener, null);
    }

    AccessibilityGestureDetector(Context context, Listener listener, GestureDetector detector) {
        this.mStrokeBuffer = new ArrayList<>(100);
        this.mListener = listener;
        this.mContext = context;
        if (detector == null) {
            this.mGestureDetector = new GestureDetector(context, this);
        } else {
            this.mGestureDetector = detector;
        }
        this.mGestureDetector.setOnDoubleTapListener(this);
        this.mGestureDetectionThreshold = TypedValue.applyDimension(5, 1.0f, context.getResources().getDisplayMetrics()) * 10.0f;
        float pixelsPerInchX = context.getResources().getDisplayMetrics().xdpi;
        float pixelsPerInchY = context.getResources().getDisplayMetrics().ydpi;
        this.mMinPixelsBetweenSamplesX = pixelsPerInchX * MIN_INCHES_BETWEEN_SAMPLES;
        this.mMinPixelsBetweenSamplesY = MIN_INCHES_BETWEEN_SAMPLES * pixelsPerInchY;
    }

    public boolean onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        long threshold;
        float x = rawEvent.getX();
        float y = rawEvent.getY();
        long time = rawEvent.getEventTime();
        this.mPolicyFlags = policyFlags;
        int actionMasked = rawEvent.getActionMasked();
        if (actionMasked == 0) {
            this.mDoubleTapDetected = false;
            this.mSecondFingerDoubleTap = false;
            this.mRecognizingGesture = true;
            this.mGestureStarted = false;
            this.mPreviousGestureX = x;
            this.mPreviousGestureY = y;
            this.mStrokeBuffer.clear();
            this.mStrokeBuffer.add(new GesturePoint(x, y, time));
            this.mBaseX = x;
            this.mBaseY = y;
            this.mBaseTime = time;
        } else if (actionMasked != 1) {
            if (actionMasked != 2) {
                if (actionMasked == 3) {
                    clear();
                } else if (actionMasked == 5) {
                    cancelGesture();
                    if (rawEvent.getPointerCount() == 2) {
                        this.mSecondFingerDoubleTap = true;
                        this.mSecondPointerDownTime = time;
                    } else {
                        this.mSecondFingerDoubleTap = false;
                    }
                } else if (actionMasked == 6 && this.mSecondFingerDoubleTap && this.mDoubleTapDetected) {
                    return finishDoubleTap(rawEvent, policyFlags);
                }
            } else if (this.mRecognizingGesture) {
                float deltaX = this.mBaseX - x;
                float deltaY = this.mBaseY - y;
                double moveDelta = Math.hypot(deltaX, deltaY);
                if (moveDelta > this.mGestureDetectionThreshold) {
                    this.mBaseX = x;
                    this.mBaseY = y;
                    this.mBaseTime = time;
                    this.mFirstTapDetected = false;
                    this.mDoubleTapDetected = false;
                    if (!this.mGestureStarted) {
                        this.mGestureStarted = true;
                        return this.mListener.onGestureStarted();
                    }
                } else if (!this.mFirstTapDetected) {
                    long timeDelta = time - this.mBaseTime;
                    if (this.mGestureStarted) {
                        threshold = CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS;
                    } else {
                        threshold = CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS;
                    }
                    if (timeDelta > threshold) {
                        cancelGesture();
                        return this.mListener.onGestureCancelled(rawEvent, policyFlags);
                    }
                }
                float dX = Math.abs(x - this.mPreviousGestureX);
                float dY = Math.abs(y - this.mPreviousGestureY);
                if (dX >= this.mMinPixelsBetweenSamplesX || dY >= this.mMinPixelsBetweenSamplesY) {
                    this.mPreviousGestureX = x;
                    this.mPreviousGestureY = y;
                    this.mStrokeBuffer.add(new GesturePoint(x, y, time));
                }
            }
        } else if (this.mDoubleTapDetected) {
            return finishDoubleTap(rawEvent, policyFlags);
        } else {
            if (this.mGestureStarted) {
                float dX2 = Math.abs(x - this.mPreviousGestureX);
                float dY2 = Math.abs(y - this.mPreviousGestureY);
                if (dX2 >= this.mMinPixelsBetweenSamplesX || dY2 >= this.mMinPixelsBetweenSamplesY) {
                    this.mStrokeBuffer.add(new GesturePoint(x, y, time));
                }
                return recognizeGesture(rawEvent, policyFlags);
            }
        }
        if (this.mSecondFingerDoubleTap) {
            MotionEvent newEvent = mapSecondPointerToFirstPointer(rawEvent);
            if (newEvent == null) {
                return false;
            }
            boolean handled = this.mGestureDetector.onTouchEvent(newEvent);
            newEvent.recycle();
            return handled;
        } else if (this.mRecognizingGesture) {
            return this.mGestureDetector.onTouchEvent(event);
        } else {
            return false;
        }
    }

    public void clear() {
        this.mFirstTapDetected = false;
        this.mDoubleTapDetected = false;
        this.mSecondFingerDoubleTap = false;
        this.mGestureStarted = false;
        this.mGestureDetector.onTouchEvent(MotionEvent.obtain(0L, 0L, 3, ANGLE_THRESHOLD, ANGLE_THRESHOLD, 0));
        cancelGesture();
    }

    public boolean firstTapDetected() {
        return this.mFirstTapDetected;
    }

    @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
    public void onLongPress(MotionEvent e) {
        maybeSendLongPress(e, this.mPolicyFlags);
    }

    @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
    public boolean onSingleTapUp(MotionEvent event) {
        this.mFirstTapDetected = true;
        return false;
    }

    @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnDoubleTapListener
    public boolean onSingleTapConfirmed(MotionEvent event) {
        clear();
        return false;
    }

    @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnDoubleTapListener
    public boolean onDoubleTap(MotionEvent event) {
        this.mDoubleTapDetected = true;
        return false;
    }

    private void maybeSendLongPress(MotionEvent event, int policyFlags) {
        if (!this.mDoubleTapDetected) {
            return;
        }
        clear();
        this.mListener.onDoubleTapAndHold(event, policyFlags);
    }

    private boolean finishDoubleTap(MotionEvent event, int policyFlags) {
        clear();
        return this.mListener.onDoubleTap(event, policyFlags);
    }

    private void cancelGesture() {
        this.mRecognizingGesture = false;
        this.mGestureStarted = false;
        this.mStrokeBuffer.clear();
    }

    private boolean recognizeGesture(MotionEvent event, int policyFlags) {
        float dX;
        float dY;
        int count;
        float dX2;
        float dX3;
        int count2;
        if (this.mStrokeBuffer.size() < 2) {
            return this.mListener.onGestureCancelled(event, policyFlags);
        }
        ArrayList<PointF> path = new ArrayList<>();
        PointF lastDelimiter = new PointF(this.mStrokeBuffer.get(0).x, this.mStrokeBuffer.get(0).y);
        path.add(lastDelimiter);
        float dX4 = ANGLE_THRESHOLD;
        float currentDY = ANGLE_THRESHOLD;
        int count3 = 0;
        float length = ANGLE_THRESHOLD;
        PointF next = new PointF();
        int i = 1;
        while (i < this.mStrokeBuffer.size()) {
            next = new PointF(this.mStrokeBuffer.get(i).x, this.mStrokeBuffer.get(i).y);
            if (count3 <= 0) {
                dX = dX4;
                dY = currentDY;
                count = count3;
            } else {
                float currentDX = dX4 / count3;
                float currentDY2 = currentDY / count3;
                dX = dX4;
                float dX5 = lastDelimiter.y;
                PointF newDelimiter = new PointF((length * currentDX) + lastDelimiter.x, (length * currentDY2) + dX5);
                float nextDX = next.x - newDelimiter.x;
                float nextDY = next.y - newDelimiter.y;
                dY = currentDY;
                count = count3;
                float nextLength = (float) Math.sqrt((nextDX * nextDX) + (nextDY * nextDY));
                float dot = (currentDX * (nextDX / nextLength)) + (currentDY2 * (nextDY / nextLength));
                if (dot < ANGLE_THRESHOLD) {
                    path.add(newDelimiter);
                    lastDelimiter = newDelimiter;
                    dX2 = ANGLE_THRESHOLD;
                    dX3 = ANGLE_THRESHOLD;
                    count2 = 0;
                    float currentDX2 = next.x - lastDelimiter.x;
                    float currentDY3 = next.y - lastDelimiter.y;
                    length = (float) Math.sqrt((currentDX2 * currentDX2) + (currentDY3 * currentDY3));
                    count3 = count2 + 1;
                    currentDY = dX3 + (currentDY3 / length);
                    i++;
                    dX4 = (currentDX2 / length) + dX2;
                }
            }
            dX2 = dX;
            dX3 = dY;
            count2 = count;
            float currentDX22 = next.x - lastDelimiter.x;
            float currentDY32 = next.y - lastDelimiter.y;
            length = (float) Math.sqrt((currentDX22 * currentDX22) + (currentDY32 * currentDY32));
            count3 = count2 + 1;
            currentDY = dX3 + (currentDY32 / length);
            i++;
            dX4 = (currentDX22 / length) + dX2;
        }
        path.add(next);
        Slog.i(LOG_TAG, "path=" + path.toString());
        return recognizeGesturePath(event, policyFlags, path);
    }

    private boolean recognizeGesturePath(MotionEvent event, int policyFlags, ArrayList<PointF> path) {
        if (path.size() == 2) {
            PointF start = path.get(0);
            PointF end = path.get(1);
            float dX = end.x - start.x;
            float dY = end.y - start.y;
            int direction = toDirection(dX, dY);
            if (direction == 0) {
                return this.mListener.onGestureCompleted(3);
            }
            if (direction == 1) {
                return this.mListener.onGestureCompleted(4);
            }
            if (direction == 2) {
                return this.mListener.onGestureCompleted(1);
            }
            if (direction == 3) {
                return this.mListener.onGestureCompleted(2);
            }
        } else if (path.size() == 3) {
            PointF start2 = path.get(0);
            PointF mid = path.get(1);
            PointF end2 = path.get(2);
            float dX0 = mid.x - start2.x;
            float dY0 = mid.y - start2.y;
            float dX1 = end2.x - mid.x;
            float dY1 = end2.y - mid.y;
            int segmentDirection0 = toDirection(dX0, dY0);
            int segmentDirection1 = toDirection(dX1, dY1);
            int gestureId = DIRECTIONS_TO_GESTURE_ID[segmentDirection0][segmentDirection1];
            return this.mListener.onGestureCompleted(gestureId);
        }
        return this.mListener.onGestureCancelled(event, policyFlags);
    }

    private static int toDirection(float dX, float dY) {
        return Math.abs(dX) > Math.abs(dY) ? dX < ANGLE_THRESHOLD ? 0 : 1 : dY < ANGLE_THRESHOLD ? 2 : 3;
    }

    private MotionEvent mapSecondPointerToFirstPointer(MotionEvent event) {
        if (event.getPointerCount() == 2) {
            if (event.getActionMasked() != 5 && event.getActionMasked() != 6 && event.getActionMasked() != 2) {
                return null;
            }
            int action = event.getActionMasked();
            if (action == 5) {
                action = 0;
            } else if (action == 6) {
                action = 1;
            }
            return MotionEvent.obtain(this.mSecondPointerDownTime, event.getEventTime(), action, event.getX(1), event.getY(1), event.getPressure(1), event.getSize(1), event.getMetaState(), event.getXPrecision(), event.getYPrecision(), event.getDeviceId(), event.getEdgeFlags());
        }
        return null;
    }
}
