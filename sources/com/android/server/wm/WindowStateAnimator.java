package com.android.server.wm;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.os.Trace;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.CoordinateTransforms;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowStateAnimator {
    static final int COMMIT_DRAW_PENDING = 2;
    static final int DRAW_PENDING = 1;
    static final int HAS_DRAWN = 4;
    static final int NO_SURFACE = 0;
    static final int READY_TO_SHOW = 3;
    static final int STACK_CLIP_AFTER_ANIM = 0;
    static final int STACK_CLIP_BEFORE_ANIM = 1;
    static final int STACK_CLIP_NONE = 2;
    static final String TAG = "WindowManager";
    static final int WINDOW_FREEZE_LAYER = 2000000;
    int mAnimLayer;
    boolean mAnimationIsEntrance;
    private boolean mAnimationStartDelayed;
    final WindowAnimator mAnimator;
    int mAttrType;
    final Context mContext;
    private boolean mDestroyPreservedSurfaceUponRedraw;
    int mDrawState;
    boolean mEnterAnimationPending;
    boolean mEnteringAnimation;
    boolean mForceScaleUntilResize;
    boolean mHaveMatrix;
    final boolean mIsWallpaper;
    boolean mLastHidden;
    int mLastLayer;
    private boolean mOffsetPositionForStackResize;
    private WindowSurfaceController mPendingDestroySurface;
    final WindowManagerPolicy mPolicy;
    boolean mReportSurfaceResized;
    final WindowManagerService mService;
    final Session mSession;
    WindowSurfaceController mSurfaceController;
    boolean mSurfaceDestroyDeferred;
    int mSurfaceFormat;
    boolean mSurfaceResized;
    private final WallpaperController mWallpaperControllerLocked;
    final WindowState mWin;
    float mShownAlpha = 0.0f;
    float mAlpha = 0.0f;
    float mLastAlpha = 0.0f;
    Rect mTmpClipRect = new Rect();
    Rect mTmpFinalClipRect = new Rect();
    Rect mLastClipRect = new Rect();
    Rect mLastFinalClipRect = new Rect();
    Rect mTmpStackBounds = new Rect();
    private Rect mTmpAnimatingBounds = new Rect();
    private Rect mTmpSourceBounds = new Rect();
    private final Rect mSystemDecorRect = new Rect();
    float mDsDx = 1.0f;
    float mDtDx = 0.0f;
    float mDsDy = 0.0f;
    float mDtDy = 1.0f;
    private float mLastDsDx = 1.0f;
    private float mLastDtDx = 0.0f;
    private float mLastDsDy = 0.0f;
    private float mLastDtDy = 1.0f;
    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();
    float mExtraHScale = 1.0f;
    float mExtraVScale = 1.0f;
    int mXOffset = 0;
    int mYOffset = 0;
    private final Rect mTmpSize = new Rect();
    private final SurfaceControl.Transaction mReparentTransaction = new SurfaceControl.Transaction();
    boolean mChildrenDetached = false;
    boolean mPipAnimationStarted = false;
    private final Point mTmpPos = new Point();

    /* JADX INFO: Access modifiers changed from: package-private */
    public String drawStateToString() {
        switch (this.mDrawState) {
            case 0:
                return "NO_SURFACE";
            case 1:
                return "DRAW_PENDING";
            case 2:
                return "COMMIT_DRAW_PENDING";
            case 3:
                return "READY_TO_SHOW";
            case 4:
                return "HAS_DRAWN";
            default:
                return Integer.toString(this.mDrawState);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowStateAnimator(WindowState win) {
        WindowManagerService service = win.mService;
        this.mService = service;
        this.mAnimator = service.mAnimator;
        this.mPolicy = service.mPolicy;
        this.mContext = service.mContext;
        this.mWin = win;
        this.mSession = win.mSession;
        this.mAttrType = win.mAttrs.type;
        this.mIsWallpaper = win.mIsWallpaper;
        this.mWallpaperControllerLocked = this.mService.mRoot.mWallpaperController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimationSet() {
        return this.mWin.isAnimating();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelExitAnimationForNextAnimationLocked() {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.d(TAG, "cancelExitAnimationForNextAnimationLocked: " + this.mWin);
        }
        this.mWin.cancelAnimation();
        this.mWin.destroySurfaceUnchecked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAnimationFinished() {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            StringBuilder sb = new StringBuilder();
            sb.append("Animation done in ");
            sb.append(this);
            sb.append(": exiting=");
            sb.append(this.mWin.mAnimatingExit);
            sb.append(", reportedVisible=");
            sb.append(this.mWin.mAppToken != null ? this.mWin.mAppToken.reportedVisible : false);
            Slog.v(TAG, sb.toString());
        }
        if (this.mAnimator.mWindowDetachedWallpaper == this.mWin) {
            this.mAnimator.mWindowDetachedWallpaper = null;
        }
        this.mWin.checkPolicyVisibilityChange();
        DisplayContent displayContent = this.mWin.getDisplayContent();
        if (this.mAttrType == 2000 && this.mWin.mPolicyVisibility && displayContent != null) {
            displayContent.setLayoutNeeded();
        }
        this.mWin.onExitAnimationDone();
        int displayId = this.mWin.getDisplayId();
        int pendingLayoutChanges = 8;
        if (displayContent.mWallpaperController.isWallpaperTarget(this.mWin)) {
            pendingLayoutChanges = 8 | 4;
        }
        this.mAnimator.setPendingLayoutChanges(displayId, pendingLayoutChanges);
        if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
            this.mService.mWindowPlacerLocked.debugLayoutRepeats("WindowStateAnimator", this.mAnimator.getPendingLayoutChanges(displayId));
        }
        if (this.mWin.mAppToken != null) {
            this.mWin.mAppToken.updateReportedVisibilityLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hide(SurfaceControl.Transaction transaction, String reason) {
        if (!this.mLastHidden) {
            this.mLastHidden = true;
            markPreservedSurfaceForDestroy();
            if (this.mSurfaceController != null) {
                this.mSurfaceController.hide(transaction, reason);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hide(String reason) {
        hide(this.mTmpTransaction, reason);
        SurfaceControl.mergeToGlobalTransaction(this.mTmpTransaction);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean finishDrawingLocked() {
        boolean startingWindow = this.mWin.mAttrs.type == 3;
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW && startingWindow) {
            Slog.v(TAG, "Finishing drawing window " + this.mWin + ": mDrawState=" + drawStateToString());
        }
        if (this.mDrawState != 1) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_ANIM || WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "finishDrawingLocked: mDrawState=COMMIT_DRAW_PENDING " + this.mWin + " in " + this.mSurfaceController);
        }
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW && startingWindow) {
            Slog.v(TAG, "Draw state now committed in " + this.mWin);
        }
        this.mDrawState = 2;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean commitFinishDrawingLocked() {
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE && this.mWin.mAttrs.type == 3) {
            Slog.i(TAG, "commitFinishDrawingLocked: " + this.mWin + " cur mDrawState=" + drawStateToString());
        }
        if (this.mDrawState != 2 && this.mDrawState != 3) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.i(TAG, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW " + this.mSurfaceController);
        }
        this.mDrawState = 3;
        AppWindowToken atoken = this.mWin.mAppToken;
        if (atoken != null && !atoken.canShowWindows() && this.mWin.mAttrs.type != 3) {
            return false;
        }
        boolean result = this.mWin.performShowLocked();
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void preserveSurfaceLocked() {
        if (this.mDestroyPreservedSurfaceUponRedraw) {
            this.mSurfaceDestroyDeferred = false;
            destroySurfaceLocked();
            this.mSurfaceDestroyDeferred = true;
            return;
        }
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            WindowManagerService.logSurface(this.mWin, "SET FREEZE LAYER", false);
        }
        if (this.mSurfaceController != null) {
            this.mSurfaceController.mSurfaceControl.setLayer(1);
        }
        this.mDestroyPreservedSurfaceUponRedraw = true;
        this.mSurfaceDestroyDeferred = true;
        destroySurfaceLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroyPreservedSurfaceLocked() {
        if (!this.mDestroyPreservedSurfaceUponRedraw) {
            return;
        }
        if (this.mSurfaceController != null && this.mPendingDestroySurface != null && (this.mWin.mAppToken == null || !this.mWin.mAppToken.isRelaunching())) {
            this.mReparentTransaction.reparentChildren(this.mPendingDestroySurface.mSurfaceControl, this.mSurfaceController.mSurfaceControl.getHandle()).apply();
        }
        destroyDeferredSurfaceLocked();
        this.mDestroyPreservedSurfaceUponRedraw = false;
    }

    void markPreservedSurfaceForDestroy() {
        if (this.mDestroyPreservedSurfaceUponRedraw && !this.mService.mDestroyPreservedSurface.contains(this.mWin)) {
            this.mService.mDestroyPreservedSurface.add(this.mWin);
        }
    }

    private int getLayerStack() {
        return this.mWin.getDisplayContent().getDisplay().getLayerStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetDrawState() {
        this.mDrawState = 1;
        if (this.mWin.mAppToken == null) {
            return;
        }
        if (this.mWin.mAppToken.isSelfAnimating()) {
            this.mWin.mAppToken.deferClearAllDrawn = true;
        } else {
            this.mWin.mAppToken.clearAllDrawn();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Can't wrap try/catch for region: R(16:6|(1:8)(1:112)|9|(1:111)|13|(1:15)|16|(1:18)|19|(8:20|21|(1:23)(1:106)|(1:25)(1:105)|26|(3:87|88|(2:96|(1:98)))|28|(4:29|30|31|(2:32|33)))|(11:(3:69|70|(9:72|42|(1:44)|45|(1:47)(1:53)|48|(1:50)|51|52))|40|41|42|(0)|45|(0)(0)|48|(0)|51|52)|35|36|37|38|39) */
    /* JADX WARN: Code restructure failed: missing block: B:80:0x028f, code lost:
        r0 = e;
     */
    /* JADX WARN: Removed duplicated region for block: B:66:0x01d0  */
    /* JADX WARN: Removed duplicated region for block: B:69:0x020e  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x025f  */
    /* JADX WARN: Removed duplicated region for block: B:73:0x0269  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public com.android.server.wm.WindowSurfaceController createSurfaceLocked(int r23, int r24) {
        /*
            Method dump skipped, instructions count: 746
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowStateAnimator.createSurfaceLocked(int, int):com.android.server.wm.WindowSurfaceController");
    }

    private void calculateSurfaceBounds(WindowState w, WindowManager.LayoutParams attrs) {
        if ((attrs.flags & 16384) != 0) {
            this.mTmpSize.right = this.mTmpSize.left + w.mRequestedWidth;
            this.mTmpSize.bottom = this.mTmpSize.top + w.mRequestedHeight;
        } else if (w.isDragResizing()) {
            if (w.getResizeMode() == 0) {
                this.mTmpSize.left = 0;
                this.mTmpSize.top = 0;
            }
            DisplayInfo displayInfo = w.getDisplayInfo();
            this.mTmpSize.right = this.mTmpSize.left + displayInfo.logicalWidth;
            this.mTmpSize.bottom = this.mTmpSize.top + displayInfo.logicalHeight;
        } else {
            this.mTmpSize.right = this.mTmpSize.left + w.mCompatFrame.width();
            this.mTmpSize.bottom = this.mTmpSize.top + w.mCompatFrame.height();
        }
        if (this.mTmpSize.width() < 1) {
            this.mTmpSize.right = this.mTmpSize.left + 1;
        }
        if (this.mTmpSize.height() < 1) {
            this.mTmpSize.bottom = this.mTmpSize.top + 1;
        }
        this.mTmpSize.left -= attrs.surfaceInsets.left;
        this.mTmpSize.top -= attrs.surfaceInsets.top;
        this.mTmpSize.right += attrs.surfaceInsets.right;
        this.mTmpSize.bottom += attrs.surfaceInsets.bottom;
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Log.d(TAG, "calculateSurfaceBounds mini type=" + attrs.type + " w.mCompatFrame=" + w.mCompatFrame + " w.mFrame=" + w.mFrame + " w.mVisibleFrame=" + w.mVisibleFrame + " w.mDisplayFrame=" + w.mDisplayFrame + " w.getBounds=" + w.getBounds() + " w.getDisplayFrameLw=" + w.getDisplayFrameLw() + " w.getDisplayContent=" + w.getDisplayContent());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSurface() {
        return this.mSurfaceController != null && this.mSurfaceController.hasSurface();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySurfaceLocked() {
        AppWindowToken wtoken = this.mWin.mAppToken;
        if (wtoken != null && this.mWin == wtoken.startingWindow) {
            wtoken.startingDisplayed = false;
        }
        if (this.mSurfaceController == null) {
            return;
        }
        if (!this.mDestroyPreservedSurfaceUponRedraw) {
            this.mWin.mHidden = true;
        }
        try {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                WindowManagerService.logWithStack(TAG, "Window " + this + " destroying surface " + this.mSurfaceController + ", session " + this.mSession);
            }
            if (this.mSurfaceDestroyDeferred) {
                if (this.mSurfaceController != null && this.mPendingDestroySurface != this.mSurfaceController) {
                    if (this.mPendingDestroySurface != null) {
                        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                            WindowManagerService.logSurface(this.mWin, "DESTROY PENDING", true);
                        }
                        this.mPendingDestroySurface.destroyNotInTransaction();
                    }
                    this.mPendingDestroySurface = this.mSurfaceController;
                }
            } else {
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(this.mWin, "DESTROY", true);
                }
                destroySurface();
            }
            if (!this.mDestroyPreservedSurfaceUponRedraw) {
                this.mWallpaperControllerLocked.hideWallpapers(this.mWin);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this + " surface " + this.mSurfaceController + " session " + this.mSession + ": " + e.toString());
        }
        this.mWin.setHasSurface(false);
        if (this.mSurfaceController != null) {
            this.mSurfaceController.setShown(false);
        }
        this.mSurfaceController = null;
        this.mDrawState = 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroyDeferredSurfaceLocked() {
        try {
            if (this.mPendingDestroySurface != null) {
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(this.mWin, "DESTROY PENDING", true);
                }
                this.mPendingDestroySurface.destroyNotInTransaction();
                if (!this.mDestroyPreservedSurfaceUponRedraw) {
                    this.mWallpaperControllerLocked.hideWallpapers(this.mWin);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this + " surface " + this.mPendingDestroySurface + " session " + this.mSession + ": " + e.toString());
        }
        this.mSurfaceDestroyDeferred = false;
        this.mPendingDestroySurface = null;
    }

    void computeShownFrameLocked() {
        int displayId = this.mWin.getDisplayId();
        ScreenRotationAnimation screenRotationAnimation = this.mAnimator.getScreenRotationAnimationLocked(displayId);
        boolean windowParticipatesInScreenRotationAnimation = !this.mWin.mForceSeamlesslyRotate;
        boolean screenAnimation = screenRotationAnimation != null && screenRotationAnimation.isAnimating() && windowParticipatesInScreenRotationAnimation;
        if (screenAnimation) {
            Rect frame = this.mWin.mFrame;
            float[] tmpFloats = this.mService.mTmpFloats;
            Matrix tmpMatrix = this.mWin.mTmpMatrix;
            if (screenRotationAnimation.isRotating()) {
                float w = frame.width();
                float h = frame.height();
                if (w >= 1.0f && h >= 1.0f) {
                    tmpMatrix.setScale((2.0f / w) + 1.0f, 1.0f + (2.0f / h), w / 2.0f, h / 2.0f);
                } else {
                    tmpMatrix.reset();
                }
            } else {
                tmpMatrix.reset();
            }
            tmpMatrix.postScale(this.mWin.mGlobalScale, this.mWin.mGlobalScale);
            tmpMatrix.postTranslate(this.mWin.mAttrs.surfaceInsets.left, this.mWin.mAttrs.surfaceInsets.top);
            this.mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            this.mDsDx = tmpFloats[0];
            this.mDtDx = tmpFloats[3];
            this.mDtDy = tmpFloats[1];
            this.mDsDy = tmpFloats[4];
            this.mShownAlpha = this.mAlpha;
            if ((!this.mService.mLimitedAlphaCompositing || !PixelFormat.formatHasAlpha(this.mWin.mAttrs.format) || this.mWin.isIdentityMatrix(this.mDsDx, this.mDtDx, this.mDtDy, this.mDsDy)) && screenAnimation) {
                this.mShownAlpha *= screenRotationAnimation.getEnterTransformation().getAlpha();
            }
            if (WindowManagerDebugConfig.DEBUG_ANIM || WindowManagerService.localLOGV) {
                if (this.mShownAlpha == 1.0d || this.mShownAlpha == 0.0d) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("computeShownFrameLocked: Animating ");
                    sb.append(this);
                    sb.append(" mAlpha=");
                    sb.append(this.mAlpha);
                    sb.append(" screen=");
                    sb.append(screenAnimation ? Float.valueOf(screenRotationAnimation.getEnterTransformation().getAlpha()) : "null");
                    Slog.v(TAG, sb.toString());
                }
            }
        } else if ((this.mIsWallpaper && this.mService.mRoot.mWallpaperActionPending) || this.mWin.isDragResizeChanged()) {
        } else {
            if (WindowManagerService.localLOGV) {
                Slog.v(TAG, "computeShownFrameLocked: " + this + " not attached, mAlpha=" + this.mAlpha);
            }
            this.mShownAlpha = this.mAlpha;
            this.mHaveMatrix = false;
            this.mDsDx = this.mWin.mGlobalScale;
            this.mDtDx = 0.0f;
            this.mDtDy = 0.0f;
            this.mDsDy = this.mWin.mGlobalScale;
        }
    }

    private boolean calculateCrop(Rect clipRect) {
        WindowState w = this.mWin;
        DisplayContent displayContent = w.getDisplayContent();
        clipRect.setEmpty();
        if (displayContent == null || w.inPinnedWindowingMode() || w.mForceSeamlesslyRotate || w.mAttrs.type == 2013) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_WINDOW_CROP) {
            Slog.d(TAG, "Updating crop win=" + w + " mLastCrop=" + this.mLastClipRect);
        }
        w.calculatePolicyCrop(this.mSystemDecorRect);
        if (WindowManagerDebugConfig.DEBUG_WINDOW_CROP) {
            Slog.d(TAG, "Applying decor to crop win=" + w + " mDecorFrame=" + w.mDecorFrame + " mSystemDecorRect=" + this.mSystemDecorRect);
        }
        Task task = w.getTask();
        boolean fullscreen = w.fillsDisplay() || (task != null && task.isFullscreen());
        if (!w.isDragResizing() || w.getResizeMode() == 0) {
        }
        clipRect.set(this.mSystemDecorRect);
        if (WindowManagerDebugConfig.DEBUG_WINDOW_CROP) {
            Slog.d(TAG, "win=" + w + " Initial clip rect: " + clipRect + " fullscreen=" + fullscreen);
        }
        w.expandForSurfaceInsets(clipRect);
        clipRect.offset(w.mAttrs.surfaceInsets.left, w.mAttrs.surfaceInsets.top);
        if (WindowManagerDebugConfig.DEBUG_WINDOW_CROP) {
            Slog.d(TAG, "win=" + w + " Clip rect after stack adjustment=" + clipRect);
        }
        w.transformClipRectFromScreenToSurfaceSpace(clipRect);
        return true;
    }

    private void applyCrop(Rect clipRect, boolean recoveringMemory) {
        if (WindowManagerDebugConfig.DEBUG_WINDOW_CROP) {
            Slog.d(TAG, "applyCrop: win=" + this.mWin + " clipRect=" + clipRect);
        }
        if (clipRect != null) {
            if (!clipRect.equals(this.mLastClipRect)) {
                this.mLastClipRect.set(clipRect);
                this.mSurfaceController.setCropInTransaction(clipRect, recoveringMemory);
                return;
            }
            return;
        }
        this.mSurfaceController.clearCropInTransaction(recoveringMemory);
    }

    void setSurfaceBoundariesLocked(boolean recoveringMemory) {
        boolean wasForceScaled;
        boolean wasSeamlesslyRotated;
        Rect clipRect;
        Rect clipRect2;
        Rect clipRect3;
        int posX;
        int posY;
        float th;
        if (this.mSurfaceController == null) {
            return;
        }
        WindowState w = this.mWin;
        WindowManager.LayoutParams attrs = this.mWin.getAttrs();
        Task task = w.getTask();
        this.mTmpSize.set(0, 0, 0, 0);
        calculateSurfaceBounds(w, attrs);
        this.mExtraHScale = 1.0f;
        this.mExtraVScale = 1.0f;
        boolean wasForceScaled2 = this.mForceScaleUntilResize;
        boolean wasSeamlesslyRotated2 = w.mSeamlesslyRotated;
        boolean relayout = !w.mRelayoutCalled || w.mInRelayout;
        if (relayout) {
            this.mSurfaceResized = this.mSurfaceController.setSizeInTransaction(this.mTmpSize.width(), this.mTmpSize.height(), recoveringMemory);
        } else {
            this.mSurfaceResized = false;
        }
        this.mForceScaleUntilResize = this.mForceScaleUntilResize && !this.mSurfaceResized;
        this.mService.markForSeamlessRotation(w, w.mSeamlesslyRotated && !this.mSurfaceResized);
        Rect clipRect4 = null;
        if (calculateCrop(this.mTmpClipRect)) {
            clipRect4 = this.mTmpClipRect;
        }
        float surfaceWidth = this.mSurfaceController.getWidth();
        float surfaceHeight = this.mSurfaceController.getHeight();
        Rect insets = attrs.surfaceInsets;
        if (isForceScaled()) {
            int hInsets = insets.left + insets.right;
            int vInsets = insets.top + insets.bottom;
            float surfaceContentWidth = surfaceWidth - hInsets;
            float surfaceContentHeight = surfaceHeight - vInsets;
            if (!this.mForceScaleUntilResize) {
                this.mSurfaceController.forceScaleableInTransaction(true);
            }
            wasSeamlesslyRotated = wasSeamlesslyRotated2;
            wasForceScaled = wasForceScaled2;
            task.mStack.getDimBounds(this.mTmpStackBounds);
            boolean allowStretching = false;
            task.mStack.getFinalAnimationSourceHintBounds(this.mTmpSourceBounds);
            if (this.mTmpSourceBounds.isEmpty() && ((this.mWin.mLastRelayoutContentInsets.width() > 0 || this.mWin.mLastRelayoutContentInsets.height() > 0) && !task.mStack.lastAnimatingBoundsWasToFullscreen())) {
                this.mTmpSourceBounds.set(task.mStack.mPreAnimationBounds);
                this.mTmpSourceBounds.inset(this.mWin.mLastRelayoutContentInsets);
                allowStretching = true;
            }
            this.mTmpStackBounds.intersectUnchecked(w.mParentFrame);
            this.mTmpSourceBounds.intersectUnchecked(w.mParentFrame);
            this.mTmpAnimatingBounds.intersectUnchecked(w.mParentFrame);
            if (!this.mTmpSourceBounds.isEmpty()) {
                task.mStack.getFinalAnimationBounds(this.mTmpAnimatingBounds);
                float finalWidth = this.mTmpAnimatingBounds.width();
                float initialWidth = this.mTmpSourceBounds.width();
                Rect clipRect5 = this.mTmpAnimatingBounds;
                float tw = (surfaceContentWidth - this.mTmpStackBounds.width()) / (surfaceContentWidth - clipRect5.width());
                float th2 = (initialWidth + ((finalWidth - initialWidth) * tw)) / initialWidth;
                this.mExtraHScale = th2;
                if (allowStretching) {
                    float finalHeight = this.mTmpAnimatingBounds.height();
                    float initialHeight = this.mTmpSourceBounds.height();
                    th = (surfaceContentHeight - this.mTmpStackBounds.height()) / (surfaceContentHeight - this.mTmpAnimatingBounds.height());
                    this.mExtraVScale = (((finalHeight - initialHeight) * tw) + initialHeight) / initialHeight;
                } else {
                    this.mExtraVScale = this.mExtraHScale;
                    th = tw;
                }
                int posX2 = 0 - ((int) ((this.mExtraHScale * tw) * this.mTmpSourceBounds.left));
                int posY2 = 0 - ((int) ((this.mExtraVScale * th) * this.mTmpSourceBounds.top));
                clipRect3 = this.mTmpClipRect;
                int i = insets.top;
                posX = posX2;
                int posX3 = this.mTmpSourceBounds.top;
                int i2 = insets.left;
                posY = posY2;
                int posY3 = this.mTmpSourceBounds.right;
                clipRect3.set((int) ((insets.left + this.mTmpSourceBounds.left) * tw), (int) ((i + posX3) * th), i2 + ((int) (surfaceWidth - ((surfaceWidth - posY3) * tw))), insets.top + ((int) (surfaceHeight - ((surfaceHeight - this.mTmpSourceBounds.bottom) * th))));
            } else {
                Rect clipRect6 = this.mTmpStackBounds;
                this.mExtraHScale = clipRect6.width() / surfaceContentWidth;
                this.mExtraVScale = this.mTmpStackBounds.height() / surfaceContentHeight;
                clipRect3 = null;
                posX = 0;
                posY = 0;
            }
            int posX4 = attrs.x;
            this.mSurfaceController.setPositionInTransaction((float) Math.floor((int) ((posX - ((int) (posX4 * (1.0f - this.mExtraHScale)))) + (insets.left * (1.0f - this.mExtraHScale)))), (float) Math.floor((int) ((posY - ((int) (attrs.y * (1.0f - this.mExtraVScale)))) + (insets.top * (1.0f - this.mExtraVScale)))), recoveringMemory);
            if (!this.mPipAnimationStarted) {
                this.mForceScaleUntilResize = true;
                this.mPipAnimationStarted = true;
            }
            clipRect2 = clipRect3;
        } else {
            Rect clipRect7 = clipRect4;
            wasForceScaled = wasForceScaled2;
            wasSeamlesslyRotated = wasSeamlesslyRotated2;
            this.mPipAnimationStarted = false;
            if (!w.mSeamlesslyRotated) {
                int xOffset = this.mXOffset;
                int yOffset = this.mYOffset;
                if (this.mOffsetPositionForStackResize) {
                    if (relayout) {
                        setOffsetPositionForStackResize(false);
                        this.mSurfaceController.deferTransactionUntil(this.mSurfaceController.getHandle(), this.mWin.getFrameNumber());
                        clipRect = clipRect7;
                    } else {
                        TaskStack stack = this.mWin.getStack();
                        this.mTmpPos.x = 0;
                        this.mTmpPos.y = 0;
                        if (stack != null) {
                            stack.getRelativePosition(this.mTmpPos);
                        }
                        xOffset = -this.mTmpPos.x;
                        yOffset = -this.mTmpPos.y;
                        if (clipRect7 != null) {
                            clipRect = clipRect7;
                            clipRect.right += this.mTmpPos.x;
                            clipRect.bottom += this.mTmpPos.y;
                        }
                    }
                    this.mSurfaceController.setPositionInTransaction(xOffset, yOffset, recoveringMemory);
                }
                clipRect = clipRect7;
                this.mSurfaceController.setPositionInTransaction(xOffset, yOffset, recoveringMemory);
            } else {
                clipRect = clipRect7;
            }
            clipRect2 = clipRect;
        }
        if ((wasForceScaled && !this.mForceScaleUntilResize) || (wasSeamlesslyRotated && !w.mSeamlesslyRotated)) {
            this.mSurfaceController.setGeometryAppliesWithResizeInTransaction(true);
            this.mSurfaceController.forceScaleableInTransaction(false);
        }
        if (!w.mSeamlesslyRotated) {
            applyCrop(clipRect2, recoveringMemory);
            this.mSurfaceController.setMatrixInTransaction(this.mDsDx * w.mHScale * this.mExtraHScale, this.mDtDx * w.mVScale * this.mExtraVScale, this.mDtDy * w.mHScale * this.mExtraHScale, this.mDsDy * w.mVScale * this.mExtraVScale, recoveringMemory);
        }
        if (this.mSurfaceResized) {
            this.mReportSurfaceResized = true;
            this.mAnimator.setPendingLayoutChanges(w.getDisplayId(), 4);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getContainerRect(Rect rect) {
        Task task = this.mWin.getTask();
        if (task != null) {
            task.getDimBounds(rect);
            return;
        }
        rect.bottom = 0;
        rect.right = 0;
        rect.top = 0;
        rect.left = 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareSurfaceLocked(boolean recoveringMemory) {
        WindowState w = this.mWin;
        if (!hasSurface()) {
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.setOrientationChanging(false);
                return;
            }
            return;
        }
        boolean displayed = false;
        computeShownFrameLocked();
        setSurfaceBoundariesLocked(recoveringMemory);
        if (this.mIsWallpaper && !w.mWallpaperVisible) {
            hide("prepareSurfaceLocked");
        } else if (w.isParentWindowHidden() || !w.isOnScreen()) {
            hide("prepareSurfaceLocked");
            this.mWallpaperControllerLocked.hideWallpapers(w);
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                w.setOrientationChanging(false);
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
            }
        } else if (this.mLastLayer != this.mAnimLayer || this.mLastAlpha != this.mShownAlpha || this.mLastDsDx != this.mDsDx || this.mLastDtDx != this.mDtDx || this.mLastDsDy != this.mDsDy || this.mLastDtDy != this.mDtDy || w.mLastHScale != w.mHScale || w.mLastVScale != w.mVScale || this.mLastHidden) {
            displayed = true;
            this.mLastAlpha = this.mShownAlpha;
            this.mLastLayer = this.mAnimLayer;
            this.mLastDsDx = this.mDsDx;
            this.mLastDtDx = this.mDtDx;
            this.mLastDsDy = this.mDsDy;
            this.mLastDtDy = this.mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                WindowManagerService.logSurface(w, "controller=" + this.mSurfaceController + "alpha=" + this.mShownAlpha + " layer=" + this.mAnimLayer + " matrix=[" + this.mDsDx + "*" + w.mHScale + "," + this.mDtDx + "*" + w.mVScale + "][" + this.mDtDy + "*" + w.mHScale + "," + this.mDsDy + "*" + w.mVScale + "]", false);
            }
            boolean prepared = this.mSurfaceController.prepareToShowInTransaction(this.mShownAlpha, this.mExtraHScale * this.mDsDx * w.mHScale, this.mExtraVScale * this.mDtDx * w.mVScale, this.mExtraHScale * this.mDtDy * w.mHScale, this.mExtraVScale * this.mDsDy * w.mVScale, recoveringMemory);
            if (prepared && this.mDrawState == 4 && this.mLastHidden) {
                if (showSurfaceRobustlyLocked()) {
                    markPreservedSurfaceForDestroy();
                    this.mAnimator.requestRemovalOfReplacedWindows(w);
                    this.mLastHidden = false;
                    if (this.mIsWallpaper) {
                        w.dispatchWallpaperVisibility(true);
                    }
                    if (!w.getDisplayContent().getLastHasContent()) {
                        this.mAnimator.setPendingLayoutChanges(w.getDisplayId(), 8);
                        if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                            this.mService.mWindowPlacerLocked.debugLayoutRepeats("showSurfaceRobustlyLocked " + w, this.mAnimator.getPendingLayoutChanges(w.getDisplayId()));
                        }
                    }
                } else {
                    w.setOrientationChanging(false);
                }
            }
            if (hasSurface()) {
                w.mToken.hasVisible = true;
            }
        } else {
            if (WindowManagerDebugConfig.DEBUG_ANIM && isAnimationSet()) {
                Slog.v(TAG, "prepareSurface: No changes in animation for " + this);
            }
            displayed = true;
        }
        if (w.getOrientationChanging()) {
            if (!w.isDrawnLw()) {
                this.mAnimator.mBulkUpdateParams &= -9;
                this.mAnimator.mLastWindowFreezeSource = w;
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation continue waiting for draw in " + w);
                }
            } else {
                w.setOrientationChanging(false);
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change complete in " + w);
                }
            }
        }
        if (displayed) {
            w.mToken.hasVisible = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTransparentRegionHintLocked(Region region) {
        if (this.mSurfaceController == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
        } else {
            this.mSurfaceController.setTransparentRegionHint(region);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setWallpaperOffset(int dx, int dy) {
        if (this.mXOffset == dx && this.mYOffset == dy) {
            return false;
        }
        this.mXOffset = dx;
        this.mYOffset = dy;
        try {
            try {
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION setWallpaperOffset");
                }
                this.mService.openSurfaceTransaction();
                this.mSurfaceController.setPositionInTransaction(dx, dy, false);
                applyCrop(null, false);
                this.mService.closeSurfaceTransaction("setWallpaperOffset");
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION setWallpaperOffset");
                }
                return true;
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + this.mWin + " pos=(" + dx + "," + dy + ")", e);
                this.mService.closeSurfaceTransaction("setWallpaperOffset");
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION setWallpaperOffset");
                }
                return true;
            }
        } catch (Throwable th) {
            this.mService.closeSurfaceTransaction("setWallpaperOffset");
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setWallpaperOffset");
            }
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean tryChangeFormatInPlaceLocked() {
        if (this.mSurfaceController == null) {
            return false;
        }
        WindowManager.LayoutParams attrs = this.mWin.getAttrs();
        boolean isHwAccelerated = (attrs.flags & 16777216) != 0;
        int format = isHwAccelerated ? -3 : attrs.format;
        if (format == this.mSurfaceFormat) {
            setOpaqueLocked(!PixelFormat.formatHasAlpha(attrs.format));
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOpaqueLocked(boolean isOpaque) {
        if (this.mSurfaceController == null) {
            return;
        }
        this.mSurfaceController.setOpaque(isOpaque);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSecureLocked(boolean isSecure) {
        if (this.mSurfaceController == null) {
            return;
        }
        this.mSurfaceController.setSecure(isSecure);
    }

    private boolean showSurfaceRobustlyLocked() {
        if (this.mWin.getWindowConfiguration().windowsAreScaleable()) {
            this.mSurfaceController.forceScaleableInTransaction(true);
        }
        boolean shown = this.mSurfaceController.showRobustlyInTransaction();
        if (!shown) {
            return false;
        }
        if (this.mPendingDestroySurface != null && this.mDestroyPreservedSurfaceUponRedraw) {
            this.mPendingDestroySurface.mSurfaceControl.hide();
            this.mPendingDestroySurface.reparentChildrenInTransaction(this.mSurfaceController);
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyEnterAnimationLocked() {
        int transit;
        if (this.mWin.mSkipEnterAnimationForSeamlessReplacement) {
            return;
        }
        if (this.mEnterAnimationPending) {
            this.mEnterAnimationPending = false;
            transit = 1;
        } else {
            transit = 3;
        }
        applyAnimationLocked(transit, true);
        if (this.mService.mAccessibilityController != null && this.mWin.getDisplayId() == 0) {
            this.mService.mAccessibilityController.onWindowTransitionLocked(this.mWin, transit);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean applyAnimationLocked(int transit, boolean isEntrance) {
        if (this.mWin.isSelfAnimating() && this.mAnimationIsEntrance == isEntrance) {
            return true;
        }
        if (isEntrance && this.mWin.mAttrs.type == 2011) {
            this.mWin.getDisplayContent().adjustForImeIfNeeded();
            this.mWin.setDisplayLayoutNeeded();
            this.mService.mWindowPlacerLocked.requestTraversal();
        }
        Trace.traceBegin(32L, "WSA#applyAnimationLocked");
        if (this.mWin.mToken.okToAnimate()) {
            int anim = this.mPolicy.selectAnimationLw(this.mWin, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = anim != -1 ? AnimationUtils.loadAnimation(this.mContext, anim) : null;
            } else {
                switch (transit) {
                    case 1:
                        attr = 0;
                        break;
                    case 2:
                        attr = 1;
                        break;
                    case 3:
                        attr = 2;
                        break;
                    case 4:
                        attr = 3;
                        break;
                }
                if (attr >= 0) {
                    a = this.mService.mAppTransition.loadAnimationAttr(this.mWin.mAttrs, attr, 0);
                }
            }
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "applyAnimation: win=" + this + " anim=" + anim + " attr=0x" + Integer.toHexString(attr) + " a=" + a + " transit=" + transit + " isEntrance=" + isEntrance + " Callers " + Debug.getCallers(3));
            }
            if (a != null) {
                if (WindowManagerDebugConfig.DEBUG_ANIM) {
                    WindowManagerService.logWithStack(TAG, "Loaded animation " + a + " for " + this);
                }
                this.mWin.startAnimation(a);
                this.mAnimationIsEntrance = isEntrance;
            }
        } else {
            this.mWin.cancelAnimation();
        }
        if (!isEntrance && this.mWin.mAttrs.type == 2011) {
            this.mWin.getDisplayContent().adjustForImeIfNeeded();
        }
        Trace.traceEnd(32L);
        return isAnimationSet();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        this.mLastClipRect.writeToProto(proto, 1146756268033L);
        if (this.mSurfaceController != null) {
            this.mSurfaceController.writeToProto(proto, 1146756268034L);
        }
        proto.write(1159641169923L, this.mDrawState);
        this.mSystemDecorRect.writeToProto(proto, 1146756268036L);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (this.mAnimationIsEntrance) {
            pw.print(prefix);
            pw.print(" mAnimationIsEntrance=");
            pw.print(this.mAnimationIsEntrance);
        }
        if (this.mSurfaceController != null) {
            this.mSurfaceController.dump(pw, prefix, dumpAll);
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mDrawState=");
            pw.print(drawStateToString());
            pw.print(prefix);
            pw.print(" mLastHidden=");
            pw.println(this.mLastHidden);
            pw.print(prefix);
            pw.print("mSystemDecorRect=");
            this.mSystemDecorRect.printShortString(pw);
            pw.print(" mLastClipRect=");
            this.mLastClipRect.printShortString(pw);
            if (!this.mLastFinalClipRect.isEmpty()) {
                pw.print(" mLastFinalClipRect=");
                this.mLastFinalClipRect.printShortString(pw);
            }
            pw.println();
        }
        if (this.mPendingDestroySurface != null) {
            pw.print(prefix);
            pw.print("mPendingDestroySurface=");
            pw.println(this.mPendingDestroySurface);
        }
        if (this.mSurfaceResized || this.mSurfaceDestroyDeferred) {
            pw.print(prefix);
            pw.print("mSurfaceResized=");
            pw.print(this.mSurfaceResized);
            pw.print(" mSurfaceDestroyDeferred=");
            pw.println(this.mSurfaceDestroyDeferred);
        }
        if (this.mShownAlpha != 1.0f || this.mAlpha != 1.0f || this.mLastAlpha != 1.0f) {
            pw.print(prefix);
            pw.print("mShownAlpha=");
            pw.print(this.mShownAlpha);
            pw.print(" mAlpha=");
            pw.print(this.mAlpha);
            pw.print(" mLastAlpha=");
            pw.println(this.mLastAlpha);
        }
        if (this.mHaveMatrix || this.mWin.mGlobalScale != 1.0f) {
            pw.print(prefix);
            pw.print("mGlobalScale=");
            pw.print(this.mWin.mGlobalScale);
            pw.print(" mDsDx=");
            pw.print(this.mDsDx);
            pw.print(" mDtDx=");
            pw.print(this.mDtDx);
            pw.print(" mDtDy=");
            pw.print(this.mDtDy);
            pw.print(" mDsDy=");
            pw.println(this.mDsDy);
        }
        if (this.mAnimationStartDelayed) {
            pw.print(prefix);
            pw.print("mAnimationStartDelayed=");
            pw.print(this.mAnimationStartDelayed);
        }
    }

    public String toString() {
        StringBuffer sb = new StringBuffer("WindowStateAnimator{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(this.mWin.mAttrs.getTitle());
        sb.append('}');
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reclaimSomeSurfaceMemory(String operation, boolean secure) {
        this.mService.mRoot.reclaimSomeSurfaceMemory(this, operation, secure);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getShown() {
        if (this.mSurfaceController != null) {
            return this.mSurfaceController.getShown();
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySurface() {
        try {
            try {
                if (this.mSurfaceController != null) {
                    this.mSurfaceController.destroyNotInTransaction();
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Exception thrown when destroying surface " + this + " surface " + this.mSurfaceController + " session " + this.mSession + ": " + e);
            }
        } finally {
            this.mWin.setHasSurface(false);
            this.mSurfaceController = null;
            this.mDrawState = 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void seamlesslyRotate(SurfaceControl.Transaction t, int oldRotation, int newRotation) {
        WindowState w = this.mWin;
        Matrix transform = this.mService.mTmpTransform;
        CoordinateTransforms.transformToRotation(oldRotation, newRotation, w.mFrame.width(), w.mFrame.height(), transform);
        transform.getValues(this.mService.mTmpFloats);
        float DsDx = this.mService.mTmpFloats[0];
        float DtDx = this.mService.mTmpFloats[3];
        float DtDy = this.mService.mTmpFloats[1];
        float DsDy = this.mService.mTmpFloats[4];
        float nx = this.mService.mTmpFloats[2];
        float ny = this.mService.mTmpFloats[5];
        this.mSurfaceController.setPosition(t, nx, ny, false);
        this.mSurfaceController.setMatrix(t, w.mHScale * DsDx, DtDx * w.mVScale, DtDy * w.mHScale, DsDy * w.mVScale, false);
    }

    boolean isForceScaled() {
        Task task = this.mWin.getTask();
        if (task != null && task.mStack.isForceScaled()) {
            return true;
        }
        return this.mForceScaleUntilResize;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void detachChildren() {
        if (this.mSurfaceController != null) {
            this.mSurfaceController.detachChildren();
        }
        this.mChildrenDetached = true;
    }

    int getLayer() {
        return this.mLastLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOffsetPositionForStackResize(boolean offsetPositionForStackResize) {
        this.mOffsetPositionForStackResize = offsetPositionForStackResize;
    }
}
