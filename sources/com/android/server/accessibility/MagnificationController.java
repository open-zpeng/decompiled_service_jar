package com.android.server.accessibility;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MagnificationSpec;
import android.view.animation.DecelerateInterpolator;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.accessibility.MagnificationController;
import com.android.server.wm.WindowManagerInternal;
import java.util.Locale;
import java.util.function.BiConsumer;

/* loaded from: classes.dex */
public class MagnificationController {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SET_MAGNIFICATION_SPEC = false;
    private static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;
    private static final String LOG_TAG = "MagnificationController";
    public static final float MAX_SCALE = 8.0f;
    public static final float MIN_SCALE = 1.0f;
    private final ControllerContext mControllerCtx;
    @GuardedBy({"mLock"})
    private final SparseArray<DisplayMagnification> mDisplays;
    private final Object mLock;
    private final long mMainThreadId;
    private final ScreenStateObserver mScreenStateObserver;
    private int mUserId;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DisplayMagnification implements WindowManagerInternal.MagnificationCallbacks {
        private static final int INVALID_ID = -1;
        private boolean mDeleteAfterUnregister;
        private final int mDisplayId;
        private boolean mRegistered;
        private final SpecAnimationBridge mSpecAnimationBridge;
        private boolean mUnregisterPending;
        private final MagnificationSpec mCurrentMagnificationSpec = MagnificationSpec.obtain();
        private final Region mMagnificationRegion = Region.obtain();
        private final Rect mMagnificationBounds = new Rect();
        private final Rect mTempRect = new Rect();
        private final Rect mTempRect1 = new Rect();
        private int mIdOfLastServiceToMagnify = -1;

        DisplayMagnification(int displayId) {
            this.mDisplayId = displayId;
            this.mSpecAnimationBridge = new SpecAnimationBridge(MagnificationController.this.mControllerCtx, MagnificationController.this.mLock, this.mDisplayId);
        }

        @GuardedBy({"mLock"})
        boolean register() {
            this.mRegistered = MagnificationController.this.mControllerCtx.getWindowManager().setMagnificationCallbacks(this.mDisplayId, this);
            if (!this.mRegistered) {
                Slog.w(MagnificationController.LOG_TAG, "set magnification callbacks fail, displayId:" + this.mDisplayId);
                return false;
            }
            this.mSpecAnimationBridge.setEnabled(true);
            MagnificationController.this.mControllerCtx.getWindowManager().getMagnificationRegion(this.mDisplayId, this.mMagnificationRegion);
            this.mMagnificationRegion.getBounds(this.mMagnificationBounds);
            return true;
        }

        @GuardedBy({"mLock"})
        void unregister(boolean delete) {
            if (this.mRegistered) {
                this.mSpecAnimationBridge.setEnabled(false);
                MagnificationController.this.mControllerCtx.getWindowManager().setMagnificationCallbacks(this.mDisplayId, null);
                this.mMagnificationRegion.setEmpty();
                this.mRegistered = false;
                MagnificationController.this.unregisterCallbackLocked(this.mDisplayId, delete);
            }
            this.mUnregisterPending = false;
        }

        @GuardedBy({"mLock"})
        void unregisterPending(boolean delete) {
            this.mDeleteAfterUnregister = delete;
            this.mUnregisterPending = true;
            reset(true);
        }

        boolean isRegistered() {
            return this.mRegistered;
        }

        boolean isMagnifying() {
            return this.mCurrentMagnificationSpec.scale > 1.0f;
        }

        float getScale() {
            return this.mCurrentMagnificationSpec.scale;
        }

        float getOffsetX() {
            return this.mCurrentMagnificationSpec.offsetX;
        }

        float getOffsetY() {
            return this.mCurrentMagnificationSpec.offsetY;
        }

        @GuardedBy({"mLock"})
        float getCenterX() {
            return (((this.mMagnificationBounds.width() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) + this.mMagnificationBounds.left) - getOffsetX()) / getScale();
        }

        @GuardedBy({"mLock"})
        float getCenterY() {
            return (((this.mMagnificationBounds.height() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) + this.mMagnificationBounds.top) - getOffsetY()) / getScale();
        }

        float getSentScale() {
            return this.mSpecAnimationBridge.mSentMagnificationSpec.scale;
        }

        float getSentOffsetX() {
            return this.mSpecAnimationBridge.mSentMagnificationSpec.offsetX;
        }

        float getSentOffsetY() {
            return this.mSpecAnimationBridge.mSentMagnificationSpec.offsetY;
        }

        @Override // com.android.server.wm.WindowManagerInternal.MagnificationCallbacks
        public void onMagnificationRegionChanged(Region magnificationRegion) {
            Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.accessibility.-$$Lambda$SP6uGJNthzczgi990Xl2SJhDOMs
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((MagnificationController.DisplayMagnification) obj).updateMagnificationRegion((Region) obj2);
                }
            }, this, Region.obtain(magnificationRegion));
            MagnificationController.this.mControllerCtx.getHandler().sendMessage(m);
        }

        @Override // com.android.server.wm.WindowManagerInternal.MagnificationCallbacks
        public void onRectangleOnScreenRequested(int left, int top, int right, int bottom) {
            Message m = PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.accessibility.-$$Lambda$iE9JplYHP8mrOjjadf_Oh8XKSE4
                public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                    ((MagnificationController.DisplayMagnification) obj).requestRectangleOnScreen(((Integer) obj2).intValue(), ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), ((Integer) obj5).intValue());
                }
            }, this, Integer.valueOf(left), Integer.valueOf(top), Integer.valueOf(right), Integer.valueOf(bottom));
            MagnificationController.this.mControllerCtx.getHandler().sendMessage(m);
        }

        @Override // com.android.server.wm.WindowManagerInternal.MagnificationCallbacks
        public void onRotationChanged(int rotation) {
            Message m = PooledLambda.obtainMessage($$Lambda$AbiCM6mjSOPpIPMT9CFGL4UAcKY.INSTANCE, MagnificationController.this, Integer.valueOf(this.mDisplayId), true);
            MagnificationController.this.mControllerCtx.getHandler().sendMessage(m);
        }

        @Override // com.android.server.wm.WindowManagerInternal.MagnificationCallbacks
        public void onUserContextChanged() {
            Message m = PooledLambda.obtainMessage($$Lambda$AbiCM6mjSOPpIPMT9CFGL4UAcKY.INSTANCE, MagnificationController.this, Integer.valueOf(this.mDisplayId), true);
            MagnificationController.this.mControllerCtx.getHandler().sendMessage(m);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void updateMagnificationRegion(Region magnified) {
            synchronized (MagnificationController.this.mLock) {
                if (this.mRegistered) {
                    if (!this.mMagnificationRegion.equals(magnified)) {
                        this.mMagnificationRegion.set(magnified);
                        this.mMagnificationRegion.getBounds(this.mMagnificationBounds);
                        if (updateCurrentSpecWithOffsetsLocked(this.mCurrentMagnificationSpec.offsetX, this.mCurrentMagnificationSpec.offsetY)) {
                            sendSpecToAnimation(this.mCurrentMagnificationSpec, false);
                        }
                        onMagnificationChangedLocked();
                    }
                    magnified.recycle();
                }
            }
        }

        void sendSpecToAnimation(MagnificationSpec spec, boolean animate) {
            if (Thread.currentThread().getId() == MagnificationController.this.mMainThreadId) {
                this.mSpecAnimationBridge.updateSentSpecMainThread(spec, animate);
                return;
            }
            Message m = PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.accessibility.-$$Lambda$CXn5BYHEDMuDgWNKCgknaVOAyJ8
                public final void accept(Object obj, Object obj2, Object obj3) {
                    ((MagnificationController.SpecAnimationBridge) obj).updateSentSpecMainThread((MagnificationSpec) obj2, ((Boolean) obj3).booleanValue());
                }
            }, this.mSpecAnimationBridge, spec, Boolean.valueOf(animate));
            MagnificationController.this.mControllerCtx.getHandler().sendMessage(m);
        }

        int getIdOfLastServiceToMagnify() {
            return this.mIdOfLastServiceToMagnify;
        }

        void onMagnificationChangedLocked() {
            MagnificationController.this.mControllerCtx.getAms().notifyMagnificationChanged(this.mDisplayId, this.mMagnificationRegion, getScale(), getCenterX(), getCenterY());
            if (this.mUnregisterPending && !isMagnifying()) {
                unregister(this.mDeleteAfterUnregister);
            }
        }

        @GuardedBy({"mLock"})
        boolean magnificationRegionContains(float x, float y) {
            return this.mMagnificationRegion.contains((int) x, (int) y);
        }

        @GuardedBy({"mLock"})
        void getMagnificationBounds(Rect outBounds) {
            outBounds.set(this.mMagnificationBounds);
        }

        @GuardedBy({"mLock"})
        void getMagnificationRegion(Region outRegion) {
            outRegion.set(this.mMagnificationRegion);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void requestRectangleOnScreen(int left, int top, int right, int bottom) {
            float scrollX;
            float scrollY;
            synchronized (MagnificationController.this.mLock) {
                Rect magnifiedFrame = this.mTempRect;
                getMagnificationBounds(magnifiedFrame);
                if (magnifiedFrame.intersects(left, top, right, bottom)) {
                    Rect magnifFrameInScreenCoords = this.mTempRect1;
                    getMagnifiedFrameInContentCoordsLocked(magnifFrameInScreenCoords);
                    if (right - left > magnifFrameInScreenCoords.width()) {
                        int direction = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
                        if (direction == 0) {
                            scrollX = left - magnifFrameInScreenCoords.left;
                        } else {
                            scrollX = right - magnifFrameInScreenCoords.right;
                        }
                    } else if (left < magnifFrameInScreenCoords.left) {
                        scrollX = left - magnifFrameInScreenCoords.left;
                    } else if (right > magnifFrameInScreenCoords.right) {
                        scrollX = right - magnifFrameInScreenCoords.right;
                    } else {
                        scrollX = 0.0f;
                    }
                    if (bottom - top > magnifFrameInScreenCoords.height()) {
                        scrollY = top - magnifFrameInScreenCoords.top;
                    } else if (top < magnifFrameInScreenCoords.top) {
                        scrollY = top - magnifFrameInScreenCoords.top;
                    } else if (bottom > magnifFrameInScreenCoords.bottom) {
                        scrollY = bottom - magnifFrameInScreenCoords.bottom;
                    } else {
                        scrollY = 0.0f;
                    }
                    float scale = getScale();
                    offsetMagnifiedRegion(scrollX * scale, scrollY * scale, -1);
                }
            }
        }

        void getMagnifiedFrameInContentCoordsLocked(Rect outFrame) {
            float scale = getSentScale();
            float offsetX = getSentOffsetX();
            float offsetY = getSentOffsetY();
            getMagnificationBounds(outFrame);
            outFrame.offset((int) (-offsetX), (int) (-offsetY));
            outFrame.scale(1.0f / scale);
        }

        @GuardedBy({"mLock"})
        void setForceShowMagnifiableBounds(boolean show) {
            if (this.mRegistered) {
                MagnificationController.this.mControllerCtx.getWindowManager().setForceShowMagnifiableBounds(this.mDisplayId, show);
            }
        }

        @GuardedBy({"mLock"})
        boolean reset(boolean animate) {
            if (!this.mRegistered) {
                return false;
            }
            MagnificationSpec spec = this.mCurrentMagnificationSpec;
            boolean changed = !spec.isNop();
            if (changed) {
                spec.clear();
                onMagnificationChangedLocked();
            }
            this.mIdOfLastServiceToMagnify = -1;
            sendSpecToAnimation(spec, animate);
            return changed;
        }

        @GuardedBy({"mLock"})
        boolean setScale(float scale, float pivotX, float pivotY, boolean animate, int id) {
            if (!this.mRegistered) {
                return false;
            }
            float scale2 = MathUtils.constrain(scale, 1.0f, 8.0f);
            Rect viewport = this.mTempRect;
            this.mMagnificationRegion.getBounds(viewport);
            MagnificationSpec spec = this.mCurrentMagnificationSpec;
            float oldScale = spec.scale;
            float oldCenterX = (((viewport.width() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) - spec.offsetX) + viewport.left) / oldScale;
            float oldCenterY = (((viewport.height() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) - spec.offsetY) + viewport.top) / oldScale;
            float normPivotX = (pivotX - spec.offsetX) / oldScale;
            float normPivotY = (pivotY - spec.offsetY) / oldScale;
            float offsetX = (oldCenterX - normPivotX) * (oldScale / scale2);
            float offsetY = (oldCenterY - normPivotY) * (oldScale / scale2);
            float centerX = normPivotX + offsetX;
            float centerY = normPivotY + offsetY;
            this.mIdOfLastServiceToMagnify = id;
            return setScaleAndCenter(scale2, centerX, centerY, animate, id);
        }

        @GuardedBy({"mLock"})
        boolean setScaleAndCenter(float scale, float centerX, float centerY, boolean animate, int id) {
            if (!this.mRegistered) {
                return false;
            }
            boolean changed = updateMagnificationSpecLocked(scale, centerX, centerY);
            sendSpecToAnimation(this.mCurrentMagnificationSpec, animate);
            if (isMagnifying() && id != -1) {
                this.mIdOfLastServiceToMagnify = id;
            }
            return changed;
        }

        boolean updateMagnificationSpecLocked(float scale, float centerX, float centerY) {
            if (Float.isNaN(centerX)) {
                centerX = getCenterX();
            }
            if (Float.isNaN(centerY)) {
                centerY = getCenterY();
            }
            if (Float.isNaN(scale)) {
                scale = getScale();
            }
            boolean changed = false;
            float normScale = MathUtils.constrain(scale, 1.0f, 8.0f);
            if (Float.compare(this.mCurrentMagnificationSpec.scale, normScale) != 0) {
                this.mCurrentMagnificationSpec.scale = normScale;
                changed = true;
            }
            float nonNormOffsetX = ((this.mMagnificationBounds.width() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) + this.mMagnificationBounds.left) - (centerX * normScale);
            float nonNormOffsetY = ((this.mMagnificationBounds.height() / MagnificationController.DEFAULT_MAGNIFICATION_SCALE) + this.mMagnificationBounds.top) - (centerY * normScale);
            boolean changed2 = changed | updateCurrentSpecWithOffsetsLocked(nonNormOffsetX, nonNormOffsetY);
            if (changed2) {
                onMagnificationChangedLocked();
            }
            return changed2;
        }

        @GuardedBy({"mLock"})
        void offsetMagnifiedRegion(float offsetX, float offsetY, int id) {
            if (!this.mRegistered) {
                return;
            }
            float nonNormOffsetX = this.mCurrentMagnificationSpec.offsetX - offsetX;
            float nonNormOffsetY = this.mCurrentMagnificationSpec.offsetY - offsetY;
            if (updateCurrentSpecWithOffsetsLocked(nonNormOffsetX, nonNormOffsetY)) {
                onMagnificationChangedLocked();
            }
            if (id != -1) {
                this.mIdOfLastServiceToMagnify = id;
            }
            sendSpecToAnimation(this.mCurrentMagnificationSpec, false);
        }

        boolean updateCurrentSpecWithOffsetsLocked(float nonNormOffsetX, float nonNormOffsetY) {
            boolean changed = false;
            float offsetX = MathUtils.constrain(nonNormOffsetX, getMinOffsetXLocked(), getMaxOffsetXLocked());
            if (Float.compare(this.mCurrentMagnificationSpec.offsetX, offsetX) != 0) {
                this.mCurrentMagnificationSpec.offsetX = offsetX;
                changed = true;
            }
            float offsetY = MathUtils.constrain(nonNormOffsetY, getMinOffsetYLocked(), getMaxOffsetYLocked());
            if (Float.compare(this.mCurrentMagnificationSpec.offsetY, offsetY) != 0) {
                this.mCurrentMagnificationSpec.offsetY = offsetY;
                return true;
            }
            return changed;
        }

        float getMinOffsetXLocked() {
            float viewportWidth = this.mMagnificationBounds.width();
            float viewportLeft = this.mMagnificationBounds.left;
            return (viewportLeft + viewportWidth) - ((viewportLeft + viewportWidth) * this.mCurrentMagnificationSpec.scale);
        }

        float getMaxOffsetXLocked() {
            return this.mMagnificationBounds.left - (this.mMagnificationBounds.left * this.mCurrentMagnificationSpec.scale);
        }

        float getMinOffsetYLocked() {
            float viewportHeight = this.mMagnificationBounds.height();
            float viewportTop = this.mMagnificationBounds.top;
            return (viewportTop + viewportHeight) - ((viewportTop + viewportHeight) * this.mCurrentMagnificationSpec.scale);
        }

        float getMaxOffsetYLocked() {
            return this.mMagnificationBounds.top - (this.mMagnificationBounds.top * this.mCurrentMagnificationSpec.scale);
        }

        public String toString() {
            return "DisplayMagnification[mCurrentMagnificationSpec=" + this.mCurrentMagnificationSpec + ", mMagnificationRegion=" + this.mMagnificationRegion + ", mMagnificationBounds=" + this.mMagnificationBounds + ", mDisplayId=" + this.mDisplayId + ", mIdOfLastServiceToMagnify=" + this.mIdOfLastServiceToMagnify + ", mRegistered=" + this.mRegistered + ", mUnregisterPending=" + this.mUnregisterPending + ']';
        }
    }

    public MagnificationController(Context context, AccessibilityManagerService ams, Object lock) {
        this(new ControllerContext(context, ams, (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class), new Handler(context.getMainLooper()), context.getResources().getInteger(17694722)), lock);
    }

    @VisibleForTesting
    public MagnificationController(ControllerContext ctx, Object lock) {
        this.mDisplays = new SparseArray<>(0);
        this.mControllerCtx = ctx;
        this.mLock = lock;
        this.mMainThreadId = this.mControllerCtx.getContext().getMainLooper().getThread().getId();
        this.mScreenStateObserver = new ScreenStateObserver(this.mControllerCtx.getContext(), this);
    }

    public void register(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                display = new DisplayMagnification(displayId);
            }
            if (display.isRegistered()) {
                return;
            }
            if (display.register()) {
                this.mDisplays.put(displayId, display);
                this.mScreenStateObserver.registerIfNecessary();
            }
        }
    }

    public void unregister(int displayId) {
        synchronized (this.mLock) {
            unregisterLocked(displayId, false);
        }
    }

    public void unregisterAll() {
        synchronized (this.mLock) {
            SparseArray<DisplayMagnification> displays = this.mDisplays.clone();
            for (int i = 0; i < displays.size(); i++) {
                unregisterLocked(displays.keyAt(i), false);
            }
        }
    }

    public void onDisplayRemoved(int displayId) {
        synchronized (this.mLock) {
            unregisterLocked(displayId, true);
        }
    }

    public boolean isRegistered(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isRegistered();
        }
    }

    public boolean isMagnifying(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.isMagnifying();
        }
    }

    public boolean magnificationRegionContains(int displayId, float x, float y) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.magnificationRegionContains(x, y);
        }
    }

    public void getMagnificationBounds(int displayId, Rect outBounds) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.getMagnificationBounds(outBounds);
        }
    }

    public void getMagnificationRegion(int displayId, Region outRegion) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.getMagnificationRegion(outRegion);
        }
    }

    public float getScale(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return 1.0f;
            }
            return display.getScale();
        }
    }

    public float getOffsetX(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getOffsetX();
        }
    }

    public float getCenterX(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getCenterX();
        }
    }

    public float getOffsetY(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getOffsetY();
        }
    }

    public float getCenterY(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return 0.0f;
            }
            return display.getCenterY();
        }
    }

    public boolean reset(int displayId, boolean animate) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.reset(animate);
        }
    }

    public boolean setScale(int displayId, float scale, float pivotX, float pivotY, boolean animate, int id) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScale(scale, pivotX, pivotY, animate, id);
        }
    }

    public boolean setCenter(int displayId, float centerX, float centerY, boolean animate, int id) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScaleAndCenter(Float.NaN, centerX, centerY, animate, id);
        }
    }

    public boolean setScaleAndCenter(int displayId, float scale, float centerX, float centerY, boolean animate, int id) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return false;
            }
            return display.setScaleAndCenter(scale, centerX, centerY, animate, id);
        }
    }

    public void offsetMagnifiedRegion(int displayId, float offsetX, float offsetY, int id) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.offsetMagnifiedRegion(offsetX, offsetY, id);
        }
    }

    public int getIdOfLastServiceToMagnify(int displayId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return -1;
            }
            return display.getIdOfLastServiceToMagnify();
        }
    }

    /* JADX WARN: Type inference failed for: r3v0, types: [com.android.server.accessibility.MagnificationController$1] */
    public void persistScale() {
        final float scale = getScale(0);
        final int userId = this.mUserId;
        new AsyncTask<Void, Void, Void>() { // from class: com.android.server.accessibility.MagnificationController.1
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // android.os.AsyncTask
            public Void doInBackground(Void... params) {
                MagnificationController.this.mControllerCtx.putMagnificationScale(scale, userId);
                return null;
            }
        }.execute(new Void[0]);
    }

    public float getPersistedScale() {
        return this.mControllerCtx.getMagnificationScale(this.mUserId);
    }

    public void setUserId(int userId) {
        if (this.mUserId == userId) {
            return;
        }
        this.mUserId = userId;
        resetAllIfNeeded(false);
    }

    public void resetAllIfNeeded(int connectionId) {
        synchronized (this.mLock) {
            for (int i = 0; i < this.mDisplays.size(); i++) {
                resetIfNeeded(this.mDisplays.keyAt(i), connectionId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resetIfNeeded(int displayId, boolean animate) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display != null && display.isMagnifying()) {
                display.reset(animate);
                return true;
            }
            return false;
        }
    }

    boolean resetIfNeeded(int displayId, int connectionId) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display != null && display.isMagnifying() && connectionId == display.getIdOfLastServiceToMagnify()) {
                display.reset(true);
                return true;
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForceShowMagnifiableBounds(int displayId, boolean show) {
        synchronized (this.mLock) {
            DisplayMagnification display = this.mDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.setForceShowMagnifiableBounds(show);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onScreenTurnedOff() {
        Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.accessibility.-$$Lambda$MagnificationController$UxSkaR2uzdX0ekJv4Wtodc8tuMY
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((MagnificationController) obj).resetAllIfNeeded(((Boolean) obj2).booleanValue());
            }
        }, this, false);
        this.mControllerCtx.getHandler().sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetAllIfNeeded(boolean animate) {
        synchronized (this.mLock) {
            for (int i = 0; i < this.mDisplays.size(); i++) {
                resetIfNeeded(this.mDisplays.keyAt(i), animate);
            }
        }
    }

    private void unregisterLocked(int displayId, boolean delete) {
        DisplayMagnification display = this.mDisplays.get(displayId);
        if (display == null) {
            return;
        }
        if (!display.isRegistered()) {
            if (delete) {
                this.mDisplays.remove(displayId);
            }
        } else if (!display.isMagnifying()) {
            display.unregister(delete);
        } else {
            display.unregisterPending(delete);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unregisterCallbackLocked(int displayId, boolean delete) {
        if (delete) {
            this.mDisplays.remove(displayId);
        }
        boolean hasRegister = false;
        for (int i = 0; i < this.mDisplays.size(); i++) {
            DisplayMagnification display = this.mDisplays.valueAt(i);
            hasRegister = display.isRegistered();
            if (hasRegister) {
                break;
            }
        }
        if (!hasRegister) {
            this.mScreenStateObserver.unregister();
        }
    }

    public String toString() {
        return "MagnificationController[mUserId=" + this.mUserId + ", mDisplays=" + this.mDisplays + "]";
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class SpecAnimationBridge implements ValueAnimator.AnimatorUpdateListener {
        private final ControllerContext mControllerCtx;
        private final int mDisplayId;
        @GuardedBy({"mLock"})
        private boolean mEnabled;
        private final MagnificationSpec mEndMagnificationSpec;
        private final Object mLock;
        private final MagnificationSpec mSentMagnificationSpec;
        private final MagnificationSpec mStartMagnificationSpec;
        private final MagnificationSpec mTmpMagnificationSpec;
        private final ValueAnimator mValueAnimator;

        private SpecAnimationBridge(ControllerContext ctx, Object lock, int displayId) {
            this.mSentMagnificationSpec = MagnificationSpec.obtain();
            this.mStartMagnificationSpec = MagnificationSpec.obtain();
            this.mEndMagnificationSpec = MagnificationSpec.obtain();
            this.mTmpMagnificationSpec = MagnificationSpec.obtain();
            this.mEnabled = false;
            this.mControllerCtx = ctx;
            this.mLock = lock;
            this.mDisplayId = displayId;
            long animationDuration = this.mControllerCtx.getAnimationDuration();
            this.mValueAnimator = this.mControllerCtx.newValueAnimator();
            this.mValueAnimator.setDuration(animationDuration);
            this.mValueAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
            this.mValueAnimator.setFloatValues(0.0f, 1.0f);
            this.mValueAnimator.addUpdateListener(this);
        }

        public void setEnabled(boolean enabled) {
            synchronized (this.mLock) {
                if (enabled != this.mEnabled) {
                    this.mEnabled = enabled;
                    if (!this.mEnabled) {
                        this.mSentMagnificationSpec.clear();
                        this.mControllerCtx.getWindowManager().setMagnificationSpec(this.mDisplayId, this.mSentMagnificationSpec);
                    }
                }
            }
        }

        public void updateSentSpecMainThread(MagnificationSpec spec, boolean animate) {
            if (this.mValueAnimator.isRunning()) {
                this.mValueAnimator.cancel();
            }
            synchronized (this.mLock) {
                boolean changed = !this.mSentMagnificationSpec.equals(spec);
                if (changed) {
                    if (animate) {
                        animateMagnificationSpecLocked(spec);
                    } else {
                        setMagnificationSpecLocked(spec);
                    }
                }
            }
        }

        @GuardedBy({"mLock"})
        private void setMagnificationSpecLocked(MagnificationSpec spec) {
            if (this.mEnabled) {
                this.mSentMagnificationSpec.setTo(spec);
                this.mControllerCtx.getWindowManager().setMagnificationSpec(this.mDisplayId, this.mSentMagnificationSpec);
            }
        }

        private void animateMagnificationSpecLocked(MagnificationSpec toSpec) {
            this.mEndMagnificationSpec.setTo(toSpec);
            this.mStartMagnificationSpec.setTo(this.mSentMagnificationSpec);
            this.mValueAnimator.start();
        }

        @Override // android.animation.ValueAnimator.AnimatorUpdateListener
        public void onAnimationUpdate(ValueAnimator animation) {
            synchronized (this.mLock) {
                if (this.mEnabled) {
                    float fract = animation.getAnimatedFraction();
                    this.mTmpMagnificationSpec.scale = this.mStartMagnificationSpec.scale + ((this.mEndMagnificationSpec.scale - this.mStartMagnificationSpec.scale) * fract);
                    this.mTmpMagnificationSpec.offsetX = this.mStartMagnificationSpec.offsetX + ((this.mEndMagnificationSpec.offsetX - this.mStartMagnificationSpec.offsetX) * fract);
                    this.mTmpMagnificationSpec.offsetY = this.mStartMagnificationSpec.offsetY + ((this.mEndMagnificationSpec.offsetY - this.mStartMagnificationSpec.offsetY) * fract);
                    setMagnificationSpecLocked(this.mTmpMagnificationSpec);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ScreenStateObserver extends BroadcastReceiver {
        private final Context mContext;
        private final MagnificationController mController;
        private boolean mRegistered = false;

        public ScreenStateObserver(Context context, MagnificationController controller) {
            this.mContext = context;
            this.mController = controller;
        }

        public void registerIfNecessary() {
            if (!this.mRegistered) {
                this.mContext.registerReceiver(this, new IntentFilter("android.intent.action.SCREEN_OFF"));
                this.mRegistered = true;
            }
        }

        public void unregister() {
            if (this.mRegistered) {
                this.mContext.unregisterReceiver(this);
                this.mRegistered = false;
            }
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            this.mController.onScreenTurnedOff();
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class ControllerContext {
        private final AccessibilityManagerService mAms;
        private final Long mAnimationDuration;
        private final Context mContext;
        private final Handler mHandler;
        private final WindowManagerInternal mWindowManager;

        public ControllerContext(Context context, AccessibilityManagerService ams, WindowManagerInternal windowManager, Handler handler, long animationDuration) {
            this.mContext = context;
            this.mAms = ams;
            this.mWindowManager = windowManager;
            this.mHandler = handler;
            this.mAnimationDuration = Long.valueOf(animationDuration);
        }

        public Context getContext() {
            return this.mContext;
        }

        public AccessibilityManagerService getAms() {
            return this.mAms;
        }

        public WindowManagerInternal getWindowManager() {
            return this.mWindowManager;
        }

        public Handler getHandler() {
            return this.mHandler;
        }

        public ValueAnimator newValueAnimator() {
            return new ValueAnimator();
        }

        public void putMagnificationScale(float value, int userId) {
            Settings.Secure.putFloatForUser(this.mContext.getContentResolver(), "accessibility_display_magnification_scale", value, userId);
        }

        public float getMagnificationScale(int userId) {
            return Settings.Secure.getFloatForUser(this.mContext.getContentResolver(), "accessibility_display_magnification_scale", MagnificationController.DEFAULT_MAGNIFICATION_SCALE, userId);
        }

        public long getAnimationDuration() {
            return this.mAnimationDuration.longValue();
        }
    }
}
