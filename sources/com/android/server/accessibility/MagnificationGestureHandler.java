package com.android.server.accessibility;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.usb.descriptors.UsbACInterface;
import java.util.Queue;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class MagnificationGestureHandler extends BaseEventStreamTransformation {
    private static final boolean DEBUG_ALL = false;
    private static final boolean DEBUG_DETECTING = false;
    private static final boolean DEBUG_EVENT_STREAM = false;
    private static final boolean DEBUG_PANNING_SCALING = false;
    private static final boolean DEBUG_STATE_TRANSITIONS = false;
    private static final String LOG_TAG = "MagnificationGestureHandler";
    private static final float MAX_SCALE = 5.0f;
    private static final float MIN_SCALE = 2.0f;
    @VisibleForTesting
    State mCurrentState;
    private final Queue<MotionEvent> mDebugInputEventHistory;
    private final Queue<MotionEvent> mDebugOutputEventHistory;
    final boolean mDetectShortcutTrigger;
    final boolean mDetectTripleTap;
    @VisibleForTesting
    final DetectingState mDetectingState;
    @VisibleForTesting
    final MagnificationController mMagnificationController;
    @VisibleForTesting
    final PanningScalingState mPanningScalingState;
    @VisibleForTesting
    State mPreviousState;
    private final ScreenStateReceiver mScreenStateReceiver;
    private MotionEvent.PointerCoords[] mTempPointerCoords;
    private MotionEvent.PointerProperties[] mTempPointerProperties;
    @VisibleForTesting
    final DelegatingState mDelegatingState = new DelegatingState();
    @VisibleForTesting
    final ViewportDraggingState mViewportDraggingState = new ViewportDraggingState();

    public MagnificationGestureHandler(Context context, MagnificationController magnificationController, boolean detectTripleTap, boolean detectShortcutTrigger) {
        this.mMagnificationController = magnificationController;
        this.mDetectingState = new DetectingState(context);
        this.mPanningScalingState = new PanningScalingState(context);
        this.mDetectTripleTap = detectTripleTap;
        this.mDetectShortcutTrigger = detectShortcutTrigger;
        if (this.mDetectShortcutTrigger) {
            this.mScreenStateReceiver = new ScreenStateReceiver(context, this);
            this.mScreenStateReceiver.register();
        } else {
            this.mScreenStateReceiver = null;
        }
        this.mDebugInputEventHistory = null;
        this.mDebugOutputEventHistory = null;
        transitionTo(this.mDetectingState);
    }

    @Override // com.android.server.accessibility.EventStreamTransformation
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        onMotionEventInternal(event, rawEvent, policyFlags);
    }

    private void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if ((!this.mDetectTripleTap && !this.mDetectShortcutTrigger) || !event.isFromSource(UsbACInterface.FORMAT_II_AC3)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
        } else {
            handleEventWith(this.mCurrentState, event, rawEvent, policyFlags);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleEventWith(State stateHandler, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        this.mPanningScalingState.mScrollGestureDetector.onTouchEvent(event);
        this.mPanningScalingState.mScaleGestureDetector.onTouchEvent(event);
        stateHandler.onMotionEvent(event, rawEvent, policyFlags);
    }

    @Override // com.android.server.accessibility.EventStreamTransformation
    public void clearEvents(int inputSource) {
        if (inputSource == 4098) {
            clearAndTransitionToStateDetecting();
        }
        super.clearEvents(inputSource);
    }

    @Override // com.android.server.accessibility.EventStreamTransformation
    public void onDestroy() {
        if (this.mScreenStateReceiver != null) {
            this.mScreenStateReceiver.unregister();
        }
        clearAndTransitionToStateDetecting();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyShortcutTriggered() {
        if (this.mDetectShortcutTrigger) {
            boolean wasMagnifying = this.mMagnificationController.resetIfNeeded(true);
            if (wasMagnifying) {
                clearAndTransitionToStateDetecting();
            } else {
                this.mDetectingState.toggleShortcutTriggered();
            }
        }
    }

    void clearAndTransitionToStateDetecting() {
        this.mCurrentState = this.mDetectingState;
        this.mDetectingState.clear();
        this.mViewportDraggingState.clear();
        this.mPanningScalingState.clear();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        MotionEvent event2 = event;
        if (this.mMagnificationController.isMagnifying() && event2.isFromSource(UsbACInterface.FORMAT_II_AC3) && this.mMagnificationController.magnificationRegionContains(event.getX(), event.getY())) {
            float scale = this.mMagnificationController.getScale();
            float scaledOffsetX = this.mMagnificationController.getOffsetX();
            float scaledOffsetY = this.mMagnificationController.getOffsetY();
            int pointerCount = event.getPointerCount();
            MotionEvent.PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
            MotionEvent.PointerProperties[] properties = getTempPointerPropertiesWithMinSize(pointerCount);
            for (int i = 0; i < pointerCount; i++) {
                event2.getPointerCoords(i, coords[i]);
                coords[i].x = (coords[i].x - scaledOffsetX) / scale;
                coords[i].y = (coords[i].y - scaledOffsetY) / scale;
                event2.getPointerProperties(i, properties[i]);
            }
            event2 = MotionEvent.obtain(event.getDownTime(), event.getEventTime(), event.getAction(), pointerCount, properties, coords, 0, 0, 1.0f, 1.0f, event.getDeviceId(), 0, event.getSource(), event.getFlags());
        }
        super.onMotionEvent(event2, rawEvent, policyFlags);
    }

    private static void storeEventInto(Queue<MotionEvent> queue, MotionEvent event) {
        queue.add(MotionEvent.obtain(event));
        while (!queue.isEmpty() && event.getEventTime() - queue.peek().getEventTime() > 5000) {
            queue.remove().recycle();
        }
    }

    private MotionEvent.PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        int oldSize = this.mTempPointerCoords != null ? this.mTempPointerCoords.length : 0;
        if (oldSize < size) {
            MotionEvent.PointerCoords[] oldTempPointerCoords = this.mTempPointerCoords;
            this.mTempPointerCoords = new MotionEvent.PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, this.mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            this.mTempPointerCoords[i] = new MotionEvent.PointerCoords();
        }
        return this.mTempPointerCoords;
    }

    private MotionEvent.PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        int oldSize = this.mTempPointerProperties != null ? this.mTempPointerProperties.length : 0;
        if (oldSize < size) {
            MotionEvent.PointerProperties[] oldTempPointerProperties = this.mTempPointerProperties;
            this.mTempPointerProperties = new MotionEvent.PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, this.mTempPointerProperties, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            this.mTempPointerProperties[i] = new MotionEvent.PointerProperties();
        }
        return this.mTempPointerProperties;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void transitionTo(State state) {
        this.mPreviousState = this.mCurrentState;
        this.mCurrentState = state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface State {
        void onMotionEvent(MotionEvent motionEvent, MotionEvent motionEvent2, int i);

        default void clear() {
        }

        default String name() {
            return getClass().getSimpleName();
        }

        static String nameOf(State s) {
            return s != null ? s.name() : "null";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class PanningScalingState extends GestureDetector.SimpleOnGestureListener implements ScaleGestureDetector.OnScaleGestureListener, State {
        float mInitialScaleFactor = -1.0f;
        private final ScaleGestureDetector mScaleGestureDetector;
        boolean mScaling;
        final float mScalingThreshold;
        private final GestureDetector mScrollGestureDetector;

        public PanningScalingState(Context context) {
            TypedValue scaleValue = new TypedValue();
            context.getResources().getValue(17104994, scaleValue, false);
            this.mScalingThreshold = scaleValue.getFloat();
            this.mScaleGestureDetector = new ScaleGestureDetector(context, this, Handler.getMain());
            this.mScaleGestureDetector.setQuickScaleEnabled(false);
            this.mScrollGestureDetector = new GestureDetector(context, this, Handler.getMain());
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            int action = event.getActionMasked();
            if (action == 6 && event.getPointerCount() == 2 && MagnificationGestureHandler.this.mPreviousState == MagnificationGestureHandler.this.mViewportDraggingState) {
                persistScaleAndTransitionTo(MagnificationGestureHandler.this.mViewportDraggingState);
            } else if (action == 1 || action == 3) {
                persistScaleAndTransitionTo(MagnificationGestureHandler.this.mDetectingState);
            }
        }

        public void persistScaleAndTransitionTo(State state) {
            MagnificationGestureHandler.this.mMagnificationController.persistScale();
            clear();
            MagnificationGestureHandler.this.transitionTo(state);
        }

        @Override // android.view.GestureDetector.SimpleOnGestureListener, android.view.GestureDetector.OnGestureListener
        public boolean onScroll(MotionEvent first, MotionEvent second, float distanceX, float distanceY) {
            if (MagnificationGestureHandler.this.mCurrentState != MagnificationGestureHandler.this.mPanningScalingState) {
                return true;
            }
            MagnificationGestureHandler.this.mMagnificationController.offsetMagnifiedRegion(distanceX, distanceY, 0);
            return true;
        }

        @Override // android.view.ScaleGestureDetector.OnScaleGestureListener
        public boolean onScale(ScaleGestureDetector detector) {
            float scale;
            float scale2;
            if (!this.mScaling) {
                if (this.mInitialScaleFactor < 0.0f) {
                    this.mInitialScaleFactor = detector.getScaleFactor();
                    return false;
                }
                float deltaScale = detector.getScaleFactor() - this.mInitialScaleFactor;
                this.mScaling = Math.abs(deltaScale) > this.mScalingThreshold;
                return this.mScaling;
            }
            float initialScale = MagnificationGestureHandler.this.mMagnificationController.getScale();
            float targetScale = detector.getScaleFactor() * initialScale;
            if (targetScale > 5.0f && targetScale > initialScale) {
                scale2 = 5.0f;
            } else if (targetScale < MagnificationGestureHandler.MIN_SCALE && targetScale < initialScale) {
                scale2 = MagnificationGestureHandler.MIN_SCALE;
            } else {
                scale = targetScale;
                float pivotX = detector.getFocusX();
                float pivotY = detector.getFocusY();
                MagnificationGestureHandler.this.mMagnificationController.setScale(scale, pivotX, pivotY, false, 0);
                return true;
            }
            scale = scale2;
            float pivotX2 = detector.getFocusX();
            float pivotY2 = detector.getFocusY();
            MagnificationGestureHandler.this.mMagnificationController.setScale(scale, pivotX2, pivotY2, false, 0);
            return true;
        }

        @Override // android.view.ScaleGestureDetector.OnScaleGestureListener
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return MagnificationGestureHandler.this.mCurrentState == MagnificationGestureHandler.this.mPanningScalingState;
        }

        @Override // android.view.ScaleGestureDetector.OnScaleGestureListener
        public void onScaleEnd(ScaleGestureDetector detector) {
            clear();
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void clear() {
            this.mInitialScaleFactor = -1.0f;
            this.mScaling = false;
        }

        public String toString() {
            return "PanningScalingState{mInitialScaleFactor=" + this.mInitialScaleFactor + ", mScaling=" + this.mScaling + '}';
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ViewportDraggingState implements State {
        private boolean mLastMoveOutsideMagnifiedRegion;
        boolean mZoomedInBeforeDrag;

        ViewportDraggingState() {
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            int action = event.getActionMasked();
            switch (action) {
                case 0:
                case 6:
                    throw new IllegalArgumentException("Unexpected event type: " + MotionEvent.actionToString(action));
                case 1:
                case 3:
                    if (!this.mZoomedInBeforeDrag) {
                        MagnificationGestureHandler.this.zoomOff();
                    }
                    clear();
                    MagnificationGestureHandler.this.transitionTo(MagnificationGestureHandler.this.mDetectingState);
                    return;
                case 2:
                    if (event.getPointerCount() != 1) {
                        throw new IllegalStateException("Should have one pointer down.");
                    }
                    float eventX = event.getX();
                    float eventY = event.getY();
                    if (MagnificationGestureHandler.this.mMagnificationController.magnificationRegionContains(eventX, eventY)) {
                        MagnificationGestureHandler.this.mMagnificationController.setCenter(eventX, eventY, this.mLastMoveOutsideMagnifiedRegion, 0);
                        this.mLastMoveOutsideMagnifiedRegion = false;
                        return;
                    }
                    this.mLastMoveOutsideMagnifiedRegion = true;
                    return;
                case 4:
                default:
                    return;
                case 5:
                    clear();
                    MagnificationGestureHandler.this.transitionTo(MagnificationGestureHandler.this.mPanningScalingState);
                    return;
            }
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void clear() {
            this.mLastMoveOutsideMagnifiedRegion = false;
        }

        public String toString() {
            return "ViewportDraggingState{mZoomedInBeforeDrag=" + this.mZoomedInBeforeDrag + ", mLastMoveOutsideMagnifiedRegion=" + this.mLastMoveOutsideMagnifiedRegion + '}';
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class DelegatingState implements State {
        public long mLastDelegatedDownEventTime;

        DelegatingState() {
        }

        /* JADX WARN: Removed duplicated region for block: B:10:0x002d  */
        /* JADX WARN: Removed duplicated region for block: B:12:? A[RETURN, SYNTHETIC] */
        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void onMotionEvent(android.view.MotionEvent r3, android.view.MotionEvent r4, int r5) {
            /*
                r2 = this;
                int r0 = r3.getActionMasked()
                r1 = 3
                if (r0 == r1) goto L1b
                switch(r0) {
                    case 0: goto Lb;
                    case 1: goto L1b;
                    default: goto La;
                }
            La:
                goto L25
            Lb:
                com.android.server.accessibility.MagnificationGestureHandler r0 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.MagnificationGestureHandler r1 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.MagnificationGestureHandler$DelegatingState r1 = r1.mDelegatingState
                com.android.server.accessibility.MagnificationGestureHandler.access$200(r0, r1)
                long r0 = r3.getDownTime()
                r2.mLastDelegatedDownEventTime = r0
                goto L25
            L1b:
                com.android.server.accessibility.MagnificationGestureHandler r0 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.MagnificationGestureHandler r1 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.MagnificationGestureHandler$DetectingState r1 = r1.mDetectingState
                com.android.server.accessibility.MagnificationGestureHandler.access$200(r0, r1)
            L25:
                com.android.server.accessibility.MagnificationGestureHandler r0 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.EventStreamTransformation r0 = r0.getNext()
                if (r0 == 0) goto L37
                long r0 = r2.mLastDelegatedDownEventTime
                r3.setDownTime(r0)
                com.android.server.accessibility.MagnificationGestureHandler r0 = com.android.server.accessibility.MagnificationGestureHandler.this
                com.android.server.accessibility.MagnificationGestureHandler.access$400(r0, r3, r4, r5)
            L37:
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.accessibility.MagnificationGestureHandler.DelegatingState.onMotionEvent(android.view.MotionEvent, android.view.MotionEvent, int):void");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class DetectingState implements State, Handler.Callback {
        private static final int MESSAGE_ON_TRIPLE_TAP_AND_HOLD = 1;
        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;
        private MotionEventInfo mDelayedEventQueue;
        MotionEvent mLastDown;
        private MotionEvent mLastUp;
        final int mMultiTapMaxDelay;
        final int mMultiTapMaxDistance;
        private MotionEvent mPreLastDown;
        private MotionEvent mPreLastUp;
        @VisibleForTesting
        boolean mShortcutTriggered;
        final int mSwipeMinDistance;
        @VisibleForTesting
        Handler mHandler = new Handler(Looper.getMainLooper(), this);
        final int mLongTapMinDelay = ViewConfiguration.getLongPressTimeout();

        public DetectingState(Context context) {
            this.mMultiTapMaxDelay = ViewConfiguration.getDoubleTapTimeout() + context.getResources().getInteger(17694863);
            this.mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();
            this.mMultiTapMaxDistance = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        }

        @Override // android.os.Handler.Callback
        public boolean handleMessage(Message message) {
            int type = message.what;
            switch (type) {
                case 1:
                    MotionEvent down = (MotionEvent) message.obj;
                    transitionToViewportDraggingStateAndClear(down);
                    down.recycle();
                    return true;
                case 2:
                    transitionToDelegatingStateAndClear();
                    return true;
                default:
                    throw new IllegalArgumentException("Unknown message type: " + type);
            }
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            int actionMasked = event.getActionMasked();
            if (actionMasked != 5) {
                switch (actionMasked) {
                    case 0:
                        this.mHandler.removeMessages(2);
                        if (!MagnificationGestureHandler.this.mMagnificationController.magnificationRegionContains(event.getX(), event.getY())) {
                            transitionToDelegatingStateAndClear();
                            return;
                        } else if (isMultiTapTriggered(2)) {
                            afterLongTapTimeoutTransitionToDraggingState(event);
                            return;
                        } else if (MagnificationGestureHandler.this.mDetectTripleTap || MagnificationGestureHandler.this.mMagnificationController.isMagnifying()) {
                            afterMultiTapTimeoutTransitionToDelegatingState();
                            return;
                        } else {
                            transitionToDelegatingStateAndClear();
                            return;
                        }
                    case 1:
                        this.mHandler.removeMessages(1);
                        if (!MagnificationGestureHandler.this.mMagnificationController.magnificationRegionContains(event.getX(), event.getY())) {
                            transitionToDelegatingStateAndClear();
                            return;
                        } else if (isMultiTapTriggered(3)) {
                            onTripleTap(event);
                            return;
                        } else if (isFingerDown()) {
                            if (timeBetween(this.mLastDown, this.mLastUp) >= this.mLongTapMinDelay || GestureUtils.distance(this.mLastDown, this.mLastUp) >= this.mSwipeMinDistance) {
                                transitionToDelegatingStateAndClear();
                                return;
                            }
                            return;
                        } else {
                            return;
                        }
                    case 2:
                        if (isFingerDown() && GestureUtils.distance(this.mLastDown, event) > this.mSwipeMinDistance) {
                            if (isMultiTapTriggered(2)) {
                                transitionToViewportDraggingStateAndClear(event);
                                return;
                            } else {
                                transitionToDelegatingStateAndClear();
                                return;
                            }
                        }
                        return;
                    default:
                        return;
                }
            } else if (MagnificationGestureHandler.this.mMagnificationController.isMagnifying()) {
                MagnificationGestureHandler.this.transitionTo(MagnificationGestureHandler.this.mPanningScalingState);
                clear();
            } else {
                transitionToDelegatingStateAndClear();
            }
        }

        public boolean isMultiTapTriggered(int numTaps) {
            return this.mShortcutTriggered ? tapCount() + 2 >= numTaps : MagnificationGestureHandler.this.mDetectTripleTap && tapCount() >= numTaps && isMultiTap(this.mPreLastDown, this.mLastDown) && isMultiTap(this.mPreLastUp, this.mLastUp);
        }

        private boolean isMultiTap(MotionEvent first, MotionEvent second) {
            return GestureUtils.isMultiTap(first, second, this.mMultiTapMaxDelay, this.mMultiTapMaxDistance);
        }

        public boolean isFingerDown() {
            return this.mLastDown != null;
        }

        private long timeBetween(MotionEvent a, MotionEvent b) {
            if (a == null && b == null) {
                return 0L;
            }
            return Math.abs(timeOf(a) - timeOf(b));
        }

        private long timeOf(MotionEvent event) {
            if (event != null) {
                return event.getEventTime();
            }
            return Long.MIN_VALUE;
        }

        public int tapCount() {
            return MotionEventInfo.countOf(this.mDelayedEventQueue, 1);
        }

        public void afterMultiTapTimeoutTransitionToDelegatingState() {
            this.mHandler.sendEmptyMessageDelayed(2, this.mMultiTapMaxDelay);
        }

        public void afterLongTapTimeoutTransitionToDraggingState(MotionEvent event) {
            this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(1, MotionEvent.obtain(event)), ViewConfiguration.getLongPressTimeout());
        }

        @Override // com.android.server.accessibility.MagnificationGestureHandler.State
        public void clear() {
            setShortcutTriggered(false);
            removePendingDelayedMessages();
            clearDelayedMotionEvents();
        }

        private void removePendingDelayedMessages() {
            this.mHandler.removeMessages(1);
            this.mHandler.removeMessages(2);
        }

        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            if (event.getActionMasked() == 0) {
                this.mPreLastDown = this.mLastDown;
                this.mLastDown = MotionEvent.obtain(event);
            } else if (event.getActionMasked() == 1) {
                this.mPreLastUp = this.mLastUp;
                this.mLastUp = MotionEvent.obtain(event);
            }
            MotionEventInfo info = MotionEventInfo.obtain(event, rawEvent, policyFlags);
            if (this.mDelayedEventQueue == null) {
                this.mDelayedEventQueue = info;
                return;
            }
            MotionEventInfo tail = this.mDelayedEventQueue;
            while (tail.mNext != null) {
                tail = tail.mNext;
            }
            tail.mNext = info;
        }

        private void sendDelayedMotionEvents() {
            while (this.mDelayedEventQueue != null) {
                MotionEventInfo info = this.mDelayedEventQueue;
                this.mDelayedEventQueue = info.mNext;
                MagnificationGestureHandler.this.handleEventWith(MagnificationGestureHandler.this.mDelegatingState, info.event, info.rawEvent, info.policyFlags);
                info.recycle();
            }
        }

        private void clearDelayedMotionEvents() {
            while (this.mDelayedEventQueue != null) {
                MotionEventInfo info = this.mDelayedEventQueue;
                this.mDelayedEventQueue = info.mNext;
                info.recycle();
            }
            this.mPreLastDown = null;
            this.mPreLastUp = null;
            this.mLastDown = null;
            this.mLastUp = null;
        }

        void transitionToDelegatingStateAndClear() {
            MagnificationGestureHandler.this.transitionTo(MagnificationGestureHandler.this.mDelegatingState);
            sendDelayedMotionEvents();
            removePendingDelayedMessages();
        }

        private void onTripleTap(MotionEvent up) {
            clear();
            if (MagnificationGestureHandler.this.mMagnificationController.isMagnifying()) {
                MagnificationGestureHandler.this.zoomOff();
            } else {
                MagnificationGestureHandler.this.zoomOn(up.getX(), up.getY());
            }
        }

        void transitionToViewportDraggingStateAndClear(MotionEvent down) {
            clear();
            MagnificationGestureHandler.this.mViewportDraggingState.mZoomedInBeforeDrag = MagnificationGestureHandler.this.mMagnificationController.isMagnifying();
            MagnificationGestureHandler.this.zoomOn(down.getX(), down.getY());
            MagnificationGestureHandler.this.transitionTo(MagnificationGestureHandler.this.mViewportDraggingState);
        }

        public String toString() {
            return "DetectingState{tapCount()=" + tapCount() + ", mShortcutTriggered=" + this.mShortcutTriggered + ", mDelayedEventQueue=" + MotionEventInfo.toString(this.mDelayedEventQueue) + '}';
        }

        void toggleShortcutTriggered() {
            setShortcutTriggered(!this.mShortcutTriggered);
        }

        void setShortcutTriggered(boolean state) {
            if (this.mShortcutTriggered == state) {
                return;
            }
            this.mShortcutTriggered = state;
            MagnificationGestureHandler.this.mMagnificationController.setForceShowMagnifiableBounds(state);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void zoomOn(float centerX, float centerY) {
        float scale = MathUtils.constrain(this.mMagnificationController.getPersistedScale(), (float) MIN_SCALE, 5.0f);
        this.mMagnificationController.setScaleAndCenter(scale, centerX, centerY, true, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void zoomOff() {
        this.mMagnificationController.reset(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static MotionEvent recycleAndNullify(MotionEvent event) {
        if (event != null) {
            event.recycle();
            return null;
        }
        return null;
    }

    public String toString() {
        return "MagnificationGesture{mDetectingState=" + this.mDetectingState + ", mDelegatingState=" + this.mDelegatingState + ", mMagnifiedInteractionState=" + this.mPanningScalingState + ", mViewportDraggingState=" + this.mViewportDraggingState + ", mDetectTripleTap=" + this.mDetectTripleTap + ", mDetectShortcutTrigger=" + this.mDetectShortcutTrigger + ", mCurrentState=" + State.nameOf(this.mCurrentState) + ", mPreviousState=" + State.nameOf(this.mPreviousState) + ", mMagnificationController=" + this.mMagnificationController + '}';
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class MotionEventInfo {
        private static final int MAX_POOL_SIZE = 10;
        private static final Object sLock = new Object();
        private static MotionEventInfo sPool;
        private static int sPoolSize;
        public MotionEvent event;
        private boolean mInPool;
        private MotionEventInfo mNext;
        public int policyFlags;
        public MotionEvent rawEvent;

        private MotionEventInfo() {
        }

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            MotionEventInfo info;
            synchronized (sLock) {
                info = obtainInternal();
                info.initialize(event, rawEvent, policyFlags);
            }
            return info;
        }

        private static MotionEventInfo obtainInternal() {
            if (sPoolSize > 0) {
                sPoolSize--;
                MotionEventInfo info = sPool;
                sPool = info.mNext;
                info.mNext = null;
                info.mInPool = false;
                return info;
            }
            return new MotionEventInfo();
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            this.event = MotionEvent.obtain(event);
            this.rawEvent = MotionEvent.obtain(rawEvent);
            this.policyFlags = policyFlags;
        }

        public void recycle() {
            synchronized (sLock) {
                if (this.mInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < 10) {
                    sPoolSize++;
                    this.mNext = sPool;
                    sPool = this;
                    this.mInPool = true;
                }
            }
        }

        private void clear() {
            this.event = MagnificationGestureHandler.recycleAndNullify(this.event);
            this.rawEvent = MagnificationGestureHandler.recycleAndNullify(this.rawEvent);
            this.policyFlags = 0;
        }

        static int countOf(MotionEventInfo info, int eventType) {
            if (info == null) {
                return 0;
            }
            return (info.event.getAction() == eventType ? 1 : 0) + countOf(info.mNext, eventType);
        }

        public static String toString(MotionEventInfo info) {
            if (info == null) {
                return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            }
            return MotionEvent.actionToString(info.event.getAction()).replace("ACTION_", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS) + " " + toString(info.mNext);
        }
    }

    /* loaded from: classes.dex */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final MagnificationGestureHandler mGestureHandler;

        public ScreenStateReceiver(Context context, MagnificationGestureHandler gestureHandler) {
            this.mContext = context;
            this.mGestureHandler = gestureHandler;
        }

        public void register() {
            this.mContext.registerReceiver(this, new IntentFilter("android.intent.action.SCREEN_OFF"));
        }

        public void unregister() {
            this.mContext.unregisterReceiver(this);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            this.mGestureHandler.mDetectingState.setShortcutTriggered(false);
        }
    }
}
