package com.android.server.wm;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import com.android.internal.os.SomeArgs;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.pm.DumpState;
import com.android.server.wm.AccessibilityController;
import com.android.server.wm.WindowManagerInternal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public final class AccessibilityController {
    private static final float[] sTempFloats = new float[9];
    private SparseArray<DisplayMagnifier> mDisplayMagnifiers = new SparseArray<>();
    private final WindowManagerService mService;
    private WindowsForAccessibilityObserver mWindowsForAccessibilityObserver;

    public AccessibilityController(WindowManagerService service) {
        this.mService = service;
    }

    public boolean setMagnificationCallbacksLocked(int displayId, WindowManagerInternal.MagnificationCallbacks callbacks) {
        Display display;
        if (callbacks != null) {
            if (this.mDisplayMagnifiers.get(displayId) != null) {
                throw new IllegalStateException("Magnification callbacks already set!");
            }
            DisplayContent dc = this.mService.mRoot.getDisplayContent(displayId);
            if (dc == null || (display = dc.getDisplay()) == null || display.getType() == 4) {
                return false;
            }
            this.mDisplayMagnifiers.put(displayId, new DisplayMagnifier(this.mService, dc, display, callbacks));
            return true;
        }
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier == null) {
            throw new IllegalStateException("Magnification callbacks already cleared!");
        }
        displayMagnifier.destroyLocked();
        this.mDisplayMagnifiers.remove(displayId);
        return true;
    }

    public void setWindowsForAccessibilityCallback(WindowManagerInternal.WindowsForAccessibilityCallback callback) {
        if (callback != null) {
            if (this.mWindowsForAccessibilityObserver != null) {
                throw new IllegalStateException("Windows for accessibility callback already set!");
            }
            this.mWindowsForAccessibilityObserver = new WindowsForAccessibilityObserver(this.mService, callback);
        } else if (this.mWindowsForAccessibilityObserver == null) {
            throw new IllegalStateException("Windows for accessibility callback already cleared!");
        } else {
            this.mWindowsForAccessibilityObserver = null;
        }
    }

    public void performComputeChangedWindowsNotLocked(boolean forceSend) {
        WindowsForAccessibilityObserver observer;
        synchronized (this.mService) {
            observer = this.mWindowsForAccessibilityObserver;
        }
        if (observer != null) {
            observer.performComputeChangedWindowsNotLocked(forceSend);
        }
    }

    public void setMagnificationSpecLocked(int displayId, MagnificationSpec spec) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setMagnificationSpecLocked(spec);
        }
        WindowsForAccessibilityObserver windowsForAccessibilityObserver = this.mWindowsForAccessibilityObserver;
        if (windowsForAccessibilityObserver != null && displayId == 0) {
            windowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void getMagnificationRegionLocked(int displayId, Region outMagnificationRegion) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.getMagnificationRegionLocked(outMagnificationRegion);
        }
    }

    public void onRectangleOnScreenRequestedLocked(int displayId, Rect rectangle) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onRectangleOnScreenRequestedLocked(rectangle);
        }
    }

    public void onWindowLayersChangedLocked(int displayId) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowLayersChangedLocked();
        }
        WindowsForAccessibilityObserver windowsForAccessibilityObserver = this.mWindowsForAccessibilityObserver;
        if (windowsForAccessibilityObserver != null && displayId == 0) {
            windowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onRotationChangedLocked(DisplayContent displayContent) {
        int displayId = displayContent.getDisplayId();
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onRotationChangedLocked(displayContent);
        }
        WindowsForAccessibilityObserver windowsForAccessibilityObserver = this.mWindowsForAccessibilityObserver;
        if (windowsForAccessibilityObserver != null && displayId == 0) {
            windowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onAppWindowTransitionLocked(WindowState windowState, int transition) {
        int displayId = windowState.getDisplayId();
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onAppWindowTransitionLocked(windowState, transition);
        }
    }

    public void onWindowTransitionLocked(WindowState windowState, int transition) {
        int displayId = windowState.getDisplayId();
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowTransitionLocked(windowState, transition);
        }
        WindowsForAccessibilityObserver windowsForAccessibilityObserver = this.mWindowsForAccessibilityObserver;
        if (windowsForAccessibilityObserver != null && displayId == 0) {
            windowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onWindowFocusChangedNotLocked() {
        WindowsForAccessibilityObserver observer;
        synchronized (this.mService) {
            observer = this.mWindowsForAccessibilityObserver;
        }
        if (observer != null) {
            observer.performComputeChangedWindowsNotLocked(false);
        }
    }

    public void onSomeWindowResizedOrMovedLocked() {
        WindowsForAccessibilityObserver windowsForAccessibilityObserver = this.mWindowsForAccessibilityObserver;
        if (windowsForAccessibilityObserver != null) {
            windowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void drawMagnifiedRegionBorderIfNeededLocked(int displayId) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.drawMagnifiedRegionBorderIfNeededLocked();
        }
    }

    public MagnificationSpec getMagnificationSpecForWindowLocked(WindowState windowState) {
        int displayId = windowState.getDisplayId();
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            return displayMagnifier.getMagnificationSpecForWindowLocked(windowState);
        }
        return null;
    }

    public boolean hasCallbacksLocked() {
        return this.mDisplayMagnifiers.size() > 0 || this.mWindowsForAccessibilityObserver != null;
    }

    public void setForceShowMagnifiableBoundsLocked(int displayId, boolean show) {
        DisplayMagnifier displayMagnifier = this.mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setForceShowMagnifiableBoundsLocked(show);
            displayMagnifier.showMagnificationBoundsIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void populateTransformationMatrixLocked(WindowState windowState, Matrix outMatrix) {
        windowState.getTransformationMatrix(sTempFloats, outMatrix);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class DisplayMagnifier {
        private static final boolean DEBUG_LAYERS = false;
        private static final boolean DEBUG_RECTANGLE_REQUESTED = false;
        private static final boolean DEBUG_ROTATION = false;
        private static final boolean DEBUG_VIEWPORT_WINDOW = false;
        private static final boolean DEBUG_WINDOW_TRANSITIONS = false;
        private static final String LOG_TAG = "WindowManager";
        private final WindowManagerInternal.MagnificationCallbacks mCallbacks;
        private final Context mContext;
        private final Display mDisplay;
        private final DisplayContent mDisplayContent;
        private final Handler mHandler;
        private final long mLongAnimationDuration;
        private final WindowManagerService mService;
        private final Rect mTempRect1 = new Rect();
        private final Rect mTempRect2 = new Rect();
        private final Region mTempRegion1 = new Region();
        private final Region mTempRegion2 = new Region();
        private final Region mTempRegion3 = new Region();
        private final Region mTempRegion4 = new Region();
        private boolean mForceShowMagnifiableBounds = false;
        private final MagnifiedViewport mMagnifedViewport = new MagnifiedViewport();

        public DisplayMagnifier(WindowManagerService windowManagerService, DisplayContent displayContent, Display display, WindowManagerInternal.MagnificationCallbacks callbacks) {
            this.mContext = windowManagerService.mContext;
            this.mService = windowManagerService;
            this.mCallbacks = callbacks;
            this.mDisplayContent = displayContent;
            this.mDisplay = display;
            this.mHandler = new MyHandler(this.mService.mH.getLooper());
            this.mLongAnimationDuration = this.mContext.getResources().getInteger(17694722);
        }

        public void setMagnificationSpecLocked(MagnificationSpec spec) {
            this.mMagnifedViewport.updateMagnificationSpecLocked(spec);
            this.mMagnifedViewport.recomputeBoundsLocked();
            this.mService.applyMagnificationSpecLocked(this.mDisplay.getDisplayId(), spec);
            this.mService.scheduleAnimationLocked();
        }

        public void setForceShowMagnifiableBoundsLocked(boolean show) {
            this.mForceShowMagnifiableBounds = show;
            this.mMagnifedViewport.setMagnifiedRegionBorderShownLocked(show, true);
        }

        public boolean isForceShowingMagnifiableBoundsLocked() {
            return this.mForceShowMagnifiableBounds;
        }

        public void onRectangleOnScreenRequestedLocked(Rect rectangle) {
            if (!this.mMagnifedViewport.isMagnifyingLocked()) {
                return;
            }
            Rect magnifiedRegionBounds = this.mTempRect2;
            this.mMagnifedViewport.getMagnifiedFrameInContentCoordsLocked(magnifiedRegionBounds);
            if (magnifiedRegionBounds.contains(rectangle)) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = rectangle.left;
            args.argi2 = rectangle.top;
            args.argi3 = rectangle.right;
            args.argi4 = rectangle.bottom;
            this.mHandler.obtainMessage(2, args).sendToTarget();
        }

        public void onWindowLayersChangedLocked() {
            this.mMagnifedViewport.recomputeBoundsLocked();
            this.mService.scheduleAnimationLocked();
        }

        public void onRotationChangedLocked(DisplayContent displayContent) {
            this.mMagnifedViewport.onRotationChangedLocked();
            this.mHandler.sendEmptyMessage(4);
        }

        public void onAppWindowTransitionLocked(WindowState windowState, int transition) {
            boolean magnifying = this.mMagnifedViewport.isMagnifyingLocked();
            if (magnifying) {
                switch (transition) {
                    case 6:
                    case 8:
                    case 10:
                    case 12:
                    case 13:
                    case 14:
                        this.mHandler.sendEmptyMessage(3);
                        return;
                    case 7:
                    case 9:
                    case 11:
                    default:
                        return;
                }
            }
        }

        public void onWindowTransitionLocked(WindowState windowState, int transition) {
            boolean magnifying = this.mMagnifedViewport.isMagnifyingLocked();
            int type = windowState.mAttrs.type;
            if ((transition == 1 || transition == 3) && magnifying) {
                if (type != 2 && type != 4 && type != 1005 && type != 2020 && type != 2024 && type != 2035 && type != 2038) {
                    switch (type) {
                        case 1000:
                        case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE /* 1001 */:
                        case 1002:
                        case 1003:
                            break;
                        default:
                            switch (type) {
                                case 2001:
                                case 2002:
                                case 2003:
                                    break;
                                default:
                                    switch (type) {
                                        case 2005:
                                        case 2006:
                                        case 2007:
                                        case 2008:
                                        case 2009:
                                        case 2010:
                                            break;
                                        default:
                                            return;
                                    }
                            }
                    }
                }
                Rect magnifiedRegionBounds = this.mTempRect2;
                this.mMagnifedViewport.getMagnifiedFrameInContentCoordsLocked(magnifiedRegionBounds);
                Rect touchableRegionBounds = this.mTempRect1;
                windowState.getTouchableRegion(this.mTempRegion1);
                this.mTempRegion1.getBounds(touchableRegionBounds);
                if (!magnifiedRegionBounds.intersect(touchableRegionBounds)) {
                    this.mCallbacks.onRectangleOnScreenRequested(touchableRegionBounds.left, touchableRegionBounds.top, touchableRegionBounds.right, touchableRegionBounds.bottom);
                }
            }
        }

        public MagnificationSpec getMagnificationSpecForWindowLocked(WindowState windowState) {
            MagnificationSpec spec = this.mMagnifedViewport.getMagnificationSpecLocked();
            if (spec != null && !spec.isNop() && !windowState.shouldMagnify()) {
                return null;
            }
            return spec;
        }

        public void getMagnificationRegionLocked(Region outMagnificationRegion) {
            this.mMagnifedViewport.recomputeBoundsLocked();
            this.mMagnifedViewport.getMagnificationRegionLocked(outMagnificationRegion);
        }

        public void destroyLocked() {
            this.mMagnifedViewport.destroyWindow();
        }

        public void showMagnificationBoundsIfNeeded() {
            this.mHandler.obtainMessage(5).sendToTarget();
        }

        public void drawMagnifiedRegionBorderIfNeededLocked() {
            this.mMagnifedViewport.drawWindowIfNeededLocked();
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes2.dex */
        public final class MagnifiedViewport {
            private final float mBorderWidth;
            private final Path mCircularPath;
            private final int mDrawBorderInset;
            private boolean mFullRedrawNeeded;
            private final int mHalfBorderWidth;
            private final ViewportWindow mWindow;
            private final WindowManager mWindowManager;
            private final SparseArray<WindowState> mTempWindowStates = new SparseArray<>();
            private final RectF mTempRectF = new RectF();
            private final Point mTempPoint = new Point();
            private final Matrix mTempMatrix = new Matrix();
            private final Region mMagnificationRegion = new Region();
            private final Region mOldMagnificationRegion = new Region();
            private final MagnificationSpec mMagnificationSpec = MagnificationSpec.obtain();
            private int mTempLayer = 0;

            public MagnifiedViewport() {
                this.mWindowManager = (WindowManager) DisplayMagnifier.this.mContext.getSystemService("window");
                this.mBorderWidth = DisplayMagnifier.this.mContext.getResources().getDimension(17104904);
                this.mHalfBorderWidth = (int) Math.ceil(this.mBorderWidth / 2.0f);
                this.mDrawBorderInset = ((int) this.mBorderWidth) / 2;
                this.mWindow = new ViewportWindow(DisplayMagnifier.this.mContext);
                if (DisplayMagnifier.this.mContext.getResources().getConfiguration().isScreenRound()) {
                    this.mCircularPath = new Path();
                    DisplayMagnifier.this.mDisplay.getRealSize(this.mTempPoint);
                    int centerXY = this.mTempPoint.x / 2;
                    this.mCircularPath.addCircle(centerXY, centerXY, centerXY, Path.Direction.CW);
                } else {
                    this.mCircularPath = null;
                }
                recomputeBoundsLocked();
            }

            public void getMagnificationRegionLocked(Region outMagnificationRegion) {
                outMagnificationRegion.set(this.mMagnificationRegion);
            }

            public void updateMagnificationSpecLocked(MagnificationSpec spec) {
                if (spec != null) {
                    this.mMagnificationSpec.initialize(spec.scale, spec.offsetX, spec.offsetY);
                } else {
                    this.mMagnificationSpec.clear();
                }
                if (!DisplayMagnifier.this.mHandler.hasMessages(5)) {
                    setMagnifiedRegionBorderShownLocked(isMagnifyingLocked() || DisplayMagnifier.this.isForceShowingMagnifiableBoundsLocked(), true);
                }
            }

            public void recomputeBoundsLocked() {
                DisplayMagnifier.this.mDisplay.getRealSize(this.mTempPoint);
                int screenWidth = this.mTempPoint.x;
                int screenHeight = this.mTempPoint.y;
                this.mMagnificationRegion.set(0, 0, 0, 0);
                Region availableBounds = DisplayMagnifier.this.mTempRegion1;
                availableBounds.set(0, 0, screenWidth, screenHeight);
                Path path = this.mCircularPath;
                if (path != null) {
                    availableBounds.setPath(path, availableBounds);
                }
                Region nonMagnifiedBounds = DisplayMagnifier.this.mTempRegion4;
                nonMagnifiedBounds.set(0, 0, 0, 0);
                SparseArray<WindowState> visibleWindows = this.mTempWindowStates;
                visibleWindows.clear();
                populateWindowsOnScreenLocked(visibleWindows);
                int visibleWindowCount = visibleWindows.size();
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    WindowState windowState = visibleWindows.valueAt(i);
                    if (windowState.mAttrs.type != 2027 && (windowState.mAttrs.privateFlags & DumpState.DUMP_DEXOPT) == 0) {
                        Matrix matrix = this.mTempMatrix;
                        AccessibilityController.populateTransformationMatrixLocked(windowState, matrix);
                        Region touchableRegion = DisplayMagnifier.this.mTempRegion3;
                        windowState.getTouchableRegion(touchableRegion);
                        Rect touchableFrame = DisplayMagnifier.this.mTempRect1;
                        touchableRegion.getBounds(touchableFrame);
                        RectF windowFrame = this.mTempRectF;
                        windowFrame.set(touchableFrame);
                        windowFrame.offset(-windowState.getFrameLw().left, -windowState.getFrameLw().top);
                        matrix.mapRect(windowFrame);
                        Region windowBounds = DisplayMagnifier.this.mTempRegion2;
                        windowBounds.set((int) windowFrame.left, (int) windowFrame.top, (int) windowFrame.right, (int) windowFrame.bottom);
                        Region portionOfWindowAlreadyAccountedFor = DisplayMagnifier.this.mTempRegion3;
                        portionOfWindowAlreadyAccountedFor.set(this.mMagnificationRegion);
                        portionOfWindowAlreadyAccountedFor.op(nonMagnifiedBounds, Region.Op.UNION);
                        windowBounds.op(portionOfWindowAlreadyAccountedFor, Region.Op.DIFFERENCE);
                        if (windowState.shouldMagnify()) {
                            this.mMagnificationRegion.op(windowBounds, Region.Op.UNION);
                            this.mMagnificationRegion.op(availableBounds, Region.Op.INTERSECT);
                        } else {
                            nonMagnifiedBounds.op(windowBounds, Region.Op.UNION);
                            availableBounds.op(windowBounds, Region.Op.DIFFERENCE);
                        }
                        if (windowState.isLetterboxedForDisplayCutoutLw()) {
                            Region letterboxBounds = getLetterboxBounds(windowState);
                            nonMagnifiedBounds.op(letterboxBounds, Region.Op.UNION);
                            availableBounds.op(letterboxBounds, Region.Op.DIFFERENCE);
                        }
                        Region accountedBounds = DisplayMagnifier.this.mTempRegion2;
                        accountedBounds.set(this.mMagnificationRegion);
                        accountedBounds.op(nonMagnifiedBounds, Region.Op.UNION);
                        accountedBounds.op(0, 0, screenWidth, screenHeight, Region.Op.INTERSECT);
                        if (accountedBounds.isRect()) {
                            Rect accountedFrame = DisplayMagnifier.this.mTempRect1;
                            accountedBounds.getBounds(accountedFrame);
                            if (accountedFrame.width() == screenWidth && accountedFrame.height() == screenHeight) {
                                break;
                            }
                        }
                    }
                }
                visibleWindows.clear();
                Region region = this.mMagnificationRegion;
                int i2 = this.mDrawBorderInset;
                region.op(i2, i2, screenWidth - i2, screenHeight - i2, Region.Op.INTERSECT);
                boolean magnifiedChanged = !this.mOldMagnificationRegion.equals(this.mMagnificationRegion);
                if (magnifiedChanged) {
                    this.mWindow.setBounds(this.mMagnificationRegion);
                    Rect dirtyRect = DisplayMagnifier.this.mTempRect1;
                    if (!this.mFullRedrawNeeded) {
                        Region dirtyRegion = DisplayMagnifier.this.mTempRegion3;
                        dirtyRegion.set(this.mMagnificationRegion);
                        dirtyRegion.op(this.mOldMagnificationRegion, Region.Op.UNION);
                        dirtyRegion.op(nonMagnifiedBounds, Region.Op.INTERSECT);
                        dirtyRegion.getBounds(dirtyRect);
                        this.mWindow.invalidate(dirtyRect);
                    } else {
                        this.mFullRedrawNeeded = false;
                        int i3 = this.mDrawBorderInset;
                        dirtyRect.set(i3, i3, screenWidth - i3, screenHeight - i3);
                        this.mWindow.invalidate(dirtyRect);
                    }
                    this.mOldMagnificationRegion.set(this.mMagnificationRegion);
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = Region.obtain(this.mMagnificationRegion);
                    DisplayMagnifier.this.mHandler.obtainMessage(1, args).sendToTarget();
                }
            }

            private Region getLetterboxBounds(WindowState windowState) {
                AppWindowToken appToken = windowState.mAppToken;
                if (appToken != null) {
                    DisplayMagnifier.this.mDisplay.getRealSize(this.mTempPoint);
                    Rect letterboxInsets = appToken.getLetterboxInsets();
                    int screenWidth = this.mTempPoint.x;
                    int screenHeight = this.mTempPoint.y;
                    Rect nonLetterboxRect = DisplayMagnifier.this.mTempRect1;
                    Region letterboxBounds = DisplayMagnifier.this.mTempRegion3;
                    nonLetterboxRect.set(0, 0, screenWidth, screenHeight);
                    nonLetterboxRect.inset(letterboxInsets);
                    letterboxBounds.set(0, 0, screenWidth, screenHeight);
                    letterboxBounds.op(nonLetterboxRect, Region.Op.DIFFERENCE);
                    return letterboxBounds;
                }
                return new Region();
            }

            public void onRotationChangedLocked() {
                if (isMagnifyingLocked() || DisplayMagnifier.this.isForceShowingMagnifiableBoundsLocked()) {
                    setMagnifiedRegionBorderShownLocked(false, false);
                    long delay = ((float) DisplayMagnifier.this.mLongAnimationDuration) * DisplayMagnifier.this.mService.getWindowAnimationScaleLocked();
                    Message message = DisplayMagnifier.this.mHandler.obtainMessage(5);
                    DisplayMagnifier.this.mHandler.sendMessageDelayed(message, delay);
                }
                recomputeBoundsLocked();
                this.mWindow.updateSize();
            }

            public void setMagnifiedRegionBorderShownLocked(boolean shown, boolean animate) {
                if (shown) {
                    this.mFullRedrawNeeded = true;
                    this.mOldMagnificationRegion.set(0, 0, 0, 0);
                }
                this.mWindow.setShown(shown, animate);
            }

            public void getMagnifiedFrameInContentCoordsLocked(Rect rect) {
                MagnificationSpec spec = this.mMagnificationSpec;
                this.mMagnificationRegion.getBounds(rect);
                rect.offset((int) (-spec.offsetX), (int) (-spec.offsetY));
                rect.scale(1.0f / spec.scale);
            }

            public boolean isMagnifyingLocked() {
                return this.mMagnificationSpec.scale > 1.0f;
            }

            public MagnificationSpec getMagnificationSpecLocked() {
                return this.mMagnificationSpec;
            }

            public void drawWindowIfNeededLocked() {
                recomputeBoundsLocked();
                this.mWindow.drawIfNeeded();
            }

            public void destroyWindow() {
                this.mWindow.releaseSurface();
            }

            private void populateWindowsOnScreenLocked(final SparseArray<WindowState> outWindows) {
                this.mTempLayer = 0;
                DisplayMagnifier.this.mDisplayContent.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$AccessibilityController$DisplayMagnifier$MagnifiedViewport$ZNyFGy-UXiWV1D2yZGvH-9qN0AA
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        AccessibilityController.DisplayMagnifier.MagnifiedViewport.this.lambda$populateWindowsOnScreenLocked$0$AccessibilityController$DisplayMagnifier$MagnifiedViewport(outWindows, (WindowState) obj);
                    }
                }, false);
            }

            public /* synthetic */ void lambda$populateWindowsOnScreenLocked$0$AccessibilityController$DisplayMagnifier$MagnifiedViewport(SparseArray outWindows, WindowState w) {
                if (w.isOnScreen() && w.isVisibleLw() && w.mAttrs.alpha != 0.0f && !w.mWinAnimator.mEnterAnimationPending) {
                    this.mTempLayer++;
                    outWindows.put(this.mTempLayer, w);
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            /* loaded from: classes2.dex */
            public final class ViewportWindow {
                private static final String SURFACE_TITLE = "Magnification Overlay";
                private int mAlpha;
                private final AnimationController mAnimationController;
                private boolean mInvalidated;
                private boolean mShown;
                private final SurfaceControl mSurfaceControl;
                private final Region mBounds = new Region();
                private final Rect mDirtyRect = new Rect();
                private final Paint mPaint = new Paint();
                private final Surface mSurface = new Surface();

                public ViewportWindow(Context context) {
                    SurfaceControl surfaceControl = null;
                    try {
                        DisplayMagnifier.this.mDisplay.getRealSize(MagnifiedViewport.this.mTempPoint);
                        surfaceControl = DisplayMagnifier.this.mDisplayContent.makeOverlay().setName(SURFACE_TITLE).setBufferSize(MagnifiedViewport.this.mTempPoint.x, MagnifiedViewport.this.mTempPoint.y).setFormat(-3).build();
                    } catch (Surface.OutOfResourcesException e) {
                    }
                    this.mSurfaceControl = surfaceControl;
                    this.mSurfaceControl.setLayer(DisplayMagnifier.this.mService.mPolicy.getWindowLayerFromTypeLw(2027) * 10000);
                    this.mSurfaceControl.setPosition(0.0f, 0.0f);
                    this.mSurface.copyFrom(this.mSurfaceControl);
                    this.mAnimationController = new AnimationController(context, DisplayMagnifier.this.mService.mH.getLooper());
                    TypedValue typedValue = new TypedValue();
                    context.getTheme().resolveAttribute(16843664, typedValue, true);
                    int borderColor = context.getColor(typedValue.resourceId);
                    this.mPaint.setStyle(Paint.Style.STROKE);
                    this.mPaint.setStrokeWidth(MagnifiedViewport.this.mBorderWidth);
                    this.mPaint.setColor(borderColor);
                    this.mInvalidated = true;
                }

                public void setShown(boolean shown, boolean animate) {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (this.mShown == shown) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            }
                            this.mShown = shown;
                            this.mAnimationController.onFrameShownStateChanged(shown, animate);
                            WindowManagerService.resetPriorityAfterLockedSection();
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                }

                public int getAlpha() {
                    int i;
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            i = this.mAlpha;
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return i;
                }

                public void setAlpha(int alpha) {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (this.mAlpha == alpha) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            }
                            this.mAlpha = alpha;
                            invalidate(null);
                            WindowManagerService.resetPriorityAfterLockedSection();
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                }

                public void setBounds(Region bounds) {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (this.mBounds.equals(bounds)) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            }
                            this.mBounds.set(bounds);
                            invalidate(this.mDirtyRect);
                            WindowManagerService.resetPriorityAfterLockedSection();
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                }

                public void updateSize() {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            MagnifiedViewport.this.mWindowManager.getDefaultDisplay().getRealSize(MagnifiedViewport.this.mTempPoint);
                            this.mSurfaceControl.setBufferSize(MagnifiedViewport.this.mTempPoint.x, MagnifiedViewport.this.mTempPoint.y);
                            invalidate(this.mDirtyRect);
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }

                public void invalidate(Rect dirtyRect) {
                    if (dirtyRect != null) {
                        this.mDirtyRect.set(dirtyRect);
                    } else {
                        this.mDirtyRect.setEmpty();
                    }
                    this.mInvalidated = true;
                    DisplayMagnifier.this.mService.scheduleAnimationLocked();
                }

                public void drawIfNeeded() {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (!this.mInvalidated) {
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            }
                            this.mInvalidated = false;
                            if (this.mAlpha > 0) {
                                Canvas canvas = null;
                                try {
                                    if (this.mDirtyRect.isEmpty()) {
                                        this.mBounds.getBounds(this.mDirtyRect);
                                    }
                                    this.mDirtyRect.inset(-MagnifiedViewport.this.mHalfBorderWidth, -MagnifiedViewport.this.mHalfBorderWidth);
                                    canvas = this.mSurface.lockCanvas(this.mDirtyRect);
                                } catch (Surface.OutOfResourcesException e) {
                                } catch (IllegalArgumentException e2) {
                                }
                                if (canvas == null) {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    return;
                                }
                                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                                this.mPaint.setAlpha(this.mAlpha);
                                Path path = this.mBounds.getBoundaryPath();
                                canvas.drawPath(path, this.mPaint);
                                this.mSurface.unlockCanvasAndPost(canvas);
                                this.mSurfaceControl.show();
                            } else {
                                this.mSurfaceControl.hide();
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                }

                public void releaseSurface() {
                    DisplayMagnifier.this.mService.mTransactionFactory.make().remove(this.mSurfaceControl).apply();
                    this.mSurface.release();
                }

                /* JADX INFO: Access modifiers changed from: private */
                /* loaded from: classes2.dex */
                public final class AnimationController extends Handler {
                    private static final int MAX_ALPHA = 255;
                    private static final int MIN_ALPHA = 0;
                    private static final int MSG_FRAME_SHOWN_STATE_CHANGED = 1;
                    private static final String PROPERTY_NAME_ALPHA = "alpha";
                    private final ValueAnimator mShowHideFrameAnimator;

                    public AnimationController(Context context, Looper looper) {
                        super(looper);
                        this.mShowHideFrameAnimator = ObjectAnimator.ofInt(ViewportWindow.this, PROPERTY_NAME_ALPHA, 0, 255);
                        Interpolator interpolator = new DecelerateInterpolator(2.5f);
                        long longAnimationDuration = context.getResources().getInteger(17694722);
                        this.mShowHideFrameAnimator.setInterpolator(interpolator);
                        this.mShowHideFrameAnimator.setDuration(longAnimationDuration);
                    }

                    public void onFrameShownStateChanged(boolean shown, boolean animate) {
                        obtainMessage(1, shown ? 1 : 0, animate ? 1 : 0).sendToTarget();
                    }

                    @Override // android.os.Handler
                    public void handleMessage(Message message) {
                        if (message.what == 1) {
                            boolean shown = message.arg1 == 1;
                            boolean animate = message.arg2 == 1;
                            if (animate) {
                                if (this.mShowHideFrameAnimator.isRunning()) {
                                    this.mShowHideFrameAnimator.reverse();
                                    return;
                                } else if (shown) {
                                    this.mShowHideFrameAnimator.start();
                                    return;
                                } else {
                                    this.mShowHideFrameAnimator.reverse();
                                    return;
                                }
                            }
                            this.mShowHideFrameAnimator.cancel();
                            if (shown) {
                                ViewportWindow.this.setAlpha(255);
                            } else {
                                ViewportWindow.this.setAlpha(0);
                            }
                        }
                    }
                }
            }
        }

        /* loaded from: classes2.dex */
        private class MyHandler extends Handler {
            public static final int MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED = 1;
            public static final int MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED = 2;
            public static final int MESSAGE_NOTIFY_ROTATION_CHANGED = 4;
            public static final int MESSAGE_NOTIFY_USER_CONTEXT_CHANGED = 3;
            public static final int MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED = 5;

            public MyHandler(Looper looper) {
                super(looper);
            }

            @Override // android.os.Handler
            public void handleMessage(Message message) {
                int i = message.what;
                if (i == 1) {
                    Region magnifiedBounds = (Region) ((SomeArgs) message.obj).arg1;
                    DisplayMagnifier.this.mCallbacks.onMagnificationRegionChanged(magnifiedBounds);
                    magnifiedBounds.recycle();
                } else if (i == 2) {
                    SomeArgs args = (SomeArgs) message.obj;
                    int left = args.argi1;
                    int top = args.argi2;
                    int right = args.argi3;
                    int bottom = args.argi4;
                    DisplayMagnifier.this.mCallbacks.onRectangleOnScreenRequested(left, top, right, bottom);
                    args.recycle();
                } else if (i == 3) {
                    DisplayMagnifier.this.mCallbacks.onUserContextChanged();
                } else if (i == 4) {
                    int rotation = message.arg1;
                    DisplayMagnifier.this.mCallbacks.onRotationChanged(rotation);
                } else if (i == 5) {
                    synchronized (DisplayMagnifier.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (DisplayMagnifier.this.mMagnifedViewport.isMagnifyingLocked() || DisplayMagnifier.this.isForceShowingMagnifiableBoundsLocked()) {
                                DisplayMagnifier.this.mMagnifedViewport.setMagnifiedRegionBorderShownLocked(true, true);
                                DisplayMagnifier.this.mService.scheduleAnimationLocked();
                            }
                        } catch (Throwable th) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class WindowsForAccessibilityObserver {
        private static final boolean DEBUG = false;
        private static final String LOG_TAG = "WindowManager";
        private final WindowManagerInternal.WindowsForAccessibilityCallback mCallback;
        private final Context mContext;
        private final Handler mHandler;
        private final WindowManagerService mService;
        private final SparseArray<WindowState> mTempWindowStates = new SparseArray<>();
        private final List<WindowInfo> mOldWindows = new ArrayList();
        private final Set<IBinder> mTempBinderSet = new ArraySet();
        private final RectF mTempRectF = new RectF();
        private final Matrix mTempMatrix = new Matrix();
        private final Point mTempPoint = new Point();
        private final Rect mTempRect = new Rect();
        private final Region mTempRegion = new Region();
        private final Region mTempRegion1 = new Region();
        private final long mRecurringAccessibilityEventsIntervalMillis = ViewConfiguration.getSendRecurringAccessibilityEventsInterval();

        public WindowsForAccessibilityObserver(WindowManagerService windowManagerService, WindowManagerInternal.WindowsForAccessibilityCallback callback) {
            this.mContext = windowManagerService.mContext;
            this.mService = windowManagerService;
            this.mCallback = callback;
            this.mHandler = new MyHandler(this.mService.mH.getLooper());
            computeChangedWindows(true);
        }

        public void performComputeChangedWindowsNotLocked(boolean forceSend) {
            this.mHandler.removeMessages(1);
            computeChangedWindows(forceSend);
        }

        public void scheduleComputeChangedWindowsLocked() {
            if (!this.mHandler.hasMessages(1)) {
                this.mHandler.sendEmptyMessageDelayed(1, this.mRecurringAccessibilityEventsIntervalMillis);
            }
        }

        public void computeChangedWindows(boolean forceSend) {
            boolean windowsChanged;
            boolean windowsChanged2;
            boolean windowsChanged3;
            boolean windowsChanged4 = false;
            List<WindowInfo> windows = new ArrayList<>();
            synchronized (this.mService.mGlobalLock) {
                try {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (!isCurrentFocusWindowOnDefaultDisplay()) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        WindowManager windowManager = (WindowManager) this.mContext.getSystemService("window");
                        windowManager.getDefaultDisplay().getRealSize(this.mTempPoint);
                        int screenWidth = this.mTempPoint.x;
                        int screenHeight = this.mTempPoint.y;
                        Region unaccountedSpace = this.mTempRegion;
                        unaccountedSpace.set(0, 0, screenWidth, screenHeight);
                        SparseArray<WindowState> visibleWindows = this.mTempWindowStates;
                        populateVisibleWindowsOnScreenLocked(visibleWindows);
                        Set<IBinder> addedWindows = this.mTempBinderSet;
                        addedWindows.clear();
                        boolean focusedWindowAdded = false;
                        int visibleWindowCount = visibleWindows.size();
                        HashSet<Integer> skipRemainingWindowsForTasks = new HashSet<>();
                        for (int i = visibleWindowCount - 1; i >= 0; i--) {
                            WindowState windowState = visibleWindows.valueAt(i);
                            Rect boundsInScreen = this.mTempRect;
                            computeWindowBoundsInScreen(windowState, boundsInScreen);
                            if (windowMattersToAccessibility(windowState, boundsInScreen, unaccountedSpace, skipRemainingWindowsForTasks)) {
                                addPopulatedWindowInfo(windowState, boundsInScreen, windows, addedWindows);
                                updateUnaccountedSpace(windowState, boundsInScreen, unaccountedSpace, skipRemainingWindowsForTasks);
                                focusedWindowAdded |= windowState.isFocused();
                            }
                            if (unaccountedSpace.isEmpty() && focusedWindowAdded) {
                                break;
                            }
                        }
                        int windowCount = windows.size();
                        int i2 = 0;
                        while (i2 < windowCount) {
                            WindowInfo window = windows.get(i2);
                            WindowManager windowManager2 = windowManager;
                            if (!addedWindows.contains(window.parentToken)) {
                                window.parentToken = null;
                            }
                            if (window.childTokens == null) {
                                windowsChanged3 = windowsChanged4;
                            } else {
                                int childTokenCount = window.childTokens.size();
                                int childTokenCount2 = childTokenCount - 1;
                                while (childTokenCount2 >= 0) {
                                    windowsChanged = windowsChanged4;
                                    try {
                                        if (!addedWindows.contains(window.childTokens.get(childTokenCount2))) {
                                            window.childTokens.remove(childTokenCount2);
                                        }
                                        childTokenCount2--;
                                        windowsChanged4 = windowsChanged;
                                    } catch (Throwable th) {
                                        th = th;
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                                windowsChanged3 = windowsChanged4;
                            }
                            i2++;
                            windowManager = windowManager2;
                            windowsChanged4 = windowsChanged3;
                        }
                        windowsChanged = windowsChanged4;
                        visibleWindows.clear();
                        addedWindows.clear();
                        if (!forceSend) {
                            if (this.mOldWindows.size() != windows.size()) {
                                windowsChanged2 = true;
                            } else if (!this.mOldWindows.isEmpty() || !windows.isEmpty()) {
                                for (int i3 = 0; i3 < windowCount; i3++) {
                                    WindowInfo oldWindow = this.mOldWindows.get(i3);
                                    WindowInfo newWindow = windows.get(i3);
                                    if (windowChangedNoLayer(oldWindow, newWindow)) {
                                        windowsChanged2 = true;
                                        break;
                                    }
                                }
                            }
                            if (!forceSend || windowsChanged2) {
                                cacheWindows(windows);
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            if (!forceSend || windowsChanged2) {
                                this.mCallback.onWindowsForAccessibilityChanged(windows);
                            }
                            clearAndRecycleWindows(windows);
                        }
                        windowsChanged2 = windowsChanged;
                        if (!forceSend) {
                        }
                        cacheWindows(windows);
                        WindowManagerService.resetPriorityAfterLockedSection();
                        if (!forceSend) {
                        }
                        this.mCallback.onWindowsForAccessibilityChanged(windows);
                        clearAndRecycleWindows(windows);
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        }

        private boolean windowMattersToAccessibility(WindowState windowState, Rect boundsInScreen, Region unaccountedSpace, HashSet<Integer> skipRemainingWindowsForTasks) {
            if (windowState.isFocused()) {
                return true;
            }
            Task task = windowState.getTask();
            if (task == null || !skipRemainingWindowsForTasks.contains(Integer.valueOf(task.mTaskId))) {
                return ((windowState.mAttrs.flags & 16) == 0 || windowState.mAttrs.type == 2034) && !unaccountedSpace.quickReject(boundsInScreen) && isReportedWindowType(windowState.mAttrs.type);
            }
            return false;
        }

        private void updateUnaccountedSpace(WindowState windowState, Rect boundsInScreen, Region unaccountedSpace, HashSet<Integer> skipRemainingWindowsForTasks) {
            if (windowState.mAttrs.type != 2032) {
                unaccountedSpace.op(boundsInScreen, unaccountedSpace, Region.Op.REVERSE_DIFFERENCE);
                if ((windowState.mAttrs.flags & 40) == 0) {
                    unaccountedSpace.op(windowState.getDisplayFrameLw(), unaccountedSpace, Region.Op.REVERSE_DIFFERENCE);
                    Task task = windowState.getTask();
                    if (task != null) {
                        skipRemainingWindowsForTasks.add(Integer.valueOf(task.mTaskId));
                    } else {
                        unaccountedSpace.setEmpty();
                    }
                }
            }
        }

        private void computeWindowBoundsInScreen(WindowState windowState, Rect outBounds) {
            Region touchableRegion = this.mTempRegion1;
            windowState.getTouchableRegion(touchableRegion);
            Rect touchableFrame = this.mTempRect;
            touchableRegion.getBounds(touchableFrame);
            RectF windowFrame = this.mTempRectF;
            windowFrame.set(touchableFrame);
            windowFrame.offset(-windowState.getFrameLw().left, -windowState.getFrameLw().top);
            Matrix matrix = this.mTempMatrix;
            AccessibilityController.populateTransformationMatrixLocked(windowState, matrix);
            matrix.mapRect(windowFrame);
            outBounds.set((int) windowFrame.left, (int) windowFrame.top, (int) windowFrame.right, (int) windowFrame.bottom);
        }

        private static void addPopulatedWindowInfo(WindowState windowState, Rect boundsInScreen, List<WindowInfo> out, Set<IBinder> tokenOut) {
            WindowInfo window = windowState.getWindowInfo();
            window.boundsInScreen.set(boundsInScreen);
            window.layer = tokenOut.size();
            out.add(window);
            tokenOut.add(window.token);
        }

        private void cacheWindows(List<WindowInfo> windows) {
            int oldWindowCount = this.mOldWindows.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                this.mOldWindows.remove(i).recycle();
            }
            int newWindowCount = windows.size();
            for (int i2 = 0; i2 < newWindowCount; i2++) {
                WindowInfo newWindow = windows.get(i2);
                this.mOldWindows.add(WindowInfo.obtain(newWindow));
            }
        }

        private boolean windowChangedNoLayer(WindowInfo oldWindow, WindowInfo newWindow) {
            if (oldWindow == newWindow) {
                return false;
            }
            if (oldWindow == null || newWindow == null || oldWindow.type != newWindow.type || oldWindow.focused != newWindow.focused) {
                return true;
            }
            if (oldWindow.token == null) {
                if (newWindow.token != null) {
                    return true;
                }
            } else if (!oldWindow.token.equals(newWindow.token)) {
                return true;
            }
            if (oldWindow.parentToken == null) {
                if (newWindow.parentToken != null) {
                    return true;
                }
            } else if (!oldWindow.parentToken.equals(newWindow.parentToken)) {
                return true;
            }
            if (!oldWindow.boundsInScreen.equals(newWindow.boundsInScreen)) {
                return true;
            }
            if ((oldWindow.childTokens == null || newWindow.childTokens == null || oldWindow.childTokens.equals(newWindow.childTokens)) && TextUtils.equals(oldWindow.title, newWindow.title) && oldWindow.accessibilityIdOfAnchor == newWindow.accessibilityIdOfAnchor) {
                return false;
            }
            return true;
        }

        private static void clearAndRecycleWindows(List<WindowInfo> windows) {
            int windowCount = windows.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                windows.remove(i).recycle();
            }
        }

        private static boolean isReportedWindowType(int windowType) {
            return (windowType == 2013 || windowType == 2021 || windowType == 2026 || windowType == 2016 || windowType == 2022 || windowType == 2018 || windowType == 2027 || windowType == 1004 || windowType == 2015 || windowType == 2030) ? false : true;
        }

        private void populateVisibleWindowsOnScreenLocked(SparseArray<WindowState> outWindows) {
            final List<WindowState> tempWindowStatesList = new ArrayList<>();
            DisplayContent dc = this.mService.getDefaultDisplayContentLocked();
            dc.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$AccessibilityController$WindowsForAccessibilityObserver$QYRk0AAI0zjoN_c0XnK_ofCGyDY
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AccessibilityController.WindowsForAccessibilityObserver.lambda$populateVisibleWindowsOnScreenLocked$0(tempWindowStatesList, (WindowState) obj);
                }
            }, false);
            this.mService.mRoot.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$AccessibilityController$WindowsForAccessibilityObserver$bKHWPOU3I5X7rQfrh2lckkzSOfQ
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AccessibilityController.WindowsForAccessibilityObserver.this.lambda$populateVisibleWindowsOnScreenLocked$1$AccessibilityController$WindowsForAccessibilityObserver(tempWindowStatesList, (WindowState) obj);
                }
            }, true);
            for (int i = 0; i < tempWindowStatesList.size(); i++) {
                outWindows.put(i, tempWindowStatesList.get(i));
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$populateVisibleWindowsOnScreenLocked$0(List tempWindowStatesList, WindowState w) {
            if (w.isVisibleLw()) {
                tempWindowStatesList.add(w);
            }
        }

        public /* synthetic */ void lambda$populateVisibleWindowsOnScreenLocked$1$AccessibilityController$WindowsForAccessibilityObserver(List tempWindowStatesList, WindowState w) {
            WindowState parentWindow = findRootDisplayParentWindow(w);
            if (parentWindow != null && w.isVisibleLw() && parentWindow.getDisplayContent().isDefaultDisplay && parentWindow.hasTapExcludeRegion() && tempWindowStatesList.contains(parentWindow)) {
                tempWindowStatesList.add(tempWindowStatesList.lastIndexOf(parentWindow) + 1, w);
            }
        }

        private WindowState findRootDisplayParentWindow(WindowState win) {
            WindowState displayParentWindow = win.getDisplayContent().getParentWindow();
            if (displayParentWindow == null) {
                return null;
            }
            WindowState candidate = displayParentWindow;
            while (candidate != null) {
                displayParentWindow = candidate;
                candidate = displayParentWindow.getDisplayContent().getParentWindow();
            }
            return displayParentWindow;
        }

        private boolean isCurrentFocusWindowOnDefaultDisplay() {
            WindowState focusedWindow = this.mService.mRoot.getTopFocusedDisplayContent().mCurrentFocus;
            if (focusedWindow == null) {
                return false;
            }
            WindowState rootDisplayParentWindow = findRootDisplayParentWindow(focusedWindow);
            if (!focusedWindow.isDefaultDisplay()) {
                if (rootDisplayParentWindow == null || !rootDisplayParentWindow.isDefaultDisplay()) {
                    return false;
                }
                return true;
            }
            return true;
        }

        /* loaded from: classes2.dex */
        private class MyHandler extends Handler {
            public static final int MESSAGE_COMPUTE_CHANGED_WINDOWS = 1;

            public MyHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override // android.os.Handler
            public void handleMessage(Message message) {
                if (message.what == 1) {
                    WindowsForAccessibilityObserver.this.computeChangedWindows(false);
                }
            }
        }
    }
}
