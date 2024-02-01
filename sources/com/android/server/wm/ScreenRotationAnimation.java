package com.android.server.wm;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;
import com.android.server.job.controllers.JobStatus;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class ScreenRotationAnimation {
    static final boolean DEBUG_STATE = false;
    static final boolean DEBUG_TRANSFORMS = false;
    static final int SCREEN_FREEZE_LAYER_BASE = 2010000;
    static final int SCREEN_FREEZE_LAYER_CUSTOM = 2010003;
    static final int SCREEN_FREEZE_LAYER_ENTER = 2010000;
    static final int SCREEN_FREEZE_LAYER_EXIT = 2010002;
    static final int SCREEN_FREEZE_LAYER_SCREENSHOT = 2010001;
    static final String TAG = "WindowManager";
    static final boolean TWO_PHASE_ANIMATION = false;
    static final boolean USE_CUSTOM_BLACK_FRAME = false;
    boolean mAnimRunning;
    final Context mContext;
    int mCurRotation;
    BlackFrame mCustomBlackFrame;
    final DisplayContent mDisplayContent;
    BlackFrame mEnteringBlackFrame;
    BlackFrame mExitingBlackFrame;
    boolean mFinishAnimReady;
    long mFinishAnimStartTime;
    Animation mFinishEnterAnimation;
    Animation mFinishExitAnimation;
    Animation mFinishFrameAnimation;
    boolean mForceDefaultOrientation;
    long mHalfwayPoint;
    int mHeight;
    Animation mLastRotateEnterAnimation;
    Animation mLastRotateExitAnimation;
    Animation mLastRotateFrameAnimation;
    private boolean mMoreFinishEnter;
    private boolean mMoreFinishExit;
    private boolean mMoreFinishFrame;
    private boolean mMoreRotateEnter;
    private boolean mMoreRotateExit;
    private boolean mMoreRotateFrame;
    private boolean mMoreStartEnter;
    private boolean mMoreStartExit;
    private boolean mMoreStartFrame;
    int mOriginalHeight;
    int mOriginalRotation;
    int mOriginalWidth;
    Animation mRotateEnterAnimation;
    Animation mRotateExitAnimation;
    Animation mRotateFrameAnimation;
    private final WindowManagerService mService;
    Animation mStartEnterAnimation;
    Animation mStartExitAnimation;
    Animation mStartFrameAnimation;
    boolean mStarted;
    SurfaceControl mSurfaceControl;
    int mWidth;
    Rect mOriginalDisplayRect = new Rect();
    Rect mCurrentDisplayRect = new Rect();
    final Transformation mStartExitTransformation = new Transformation();
    final Transformation mStartEnterTransformation = new Transformation();
    final Transformation mStartFrameTransformation = new Transformation();
    final Transformation mFinishExitTransformation = new Transformation();
    final Transformation mFinishEnterTransformation = new Transformation();
    final Transformation mFinishFrameTransformation = new Transformation();
    final Transformation mRotateExitTransformation = new Transformation();
    final Transformation mRotateEnterTransformation = new Transformation();
    final Transformation mRotateFrameTransformation = new Transformation();
    final Transformation mLastRotateExitTransformation = new Transformation();
    final Transformation mLastRotateEnterTransformation = new Transformation();
    final Transformation mLastRotateFrameTransformation = new Transformation();
    final Transformation mExitTransformation = new Transformation();
    final Transformation mEnterTransformation = new Transformation();
    final Transformation mFrameTransformation = new Transformation();
    final Matrix mFrameInitialMatrix = new Matrix();
    final Matrix mSnapshotInitialMatrix = new Matrix();
    final Matrix mSnapshotFinalMatrix = new Matrix();
    final Matrix mExitFrameFinalMatrix = new Matrix();
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("mSurface=");
        pw.print(this.mSurfaceControl);
        pw.print(" mWidth=");
        pw.print(this.mWidth);
        pw.print(" mHeight=");
        pw.println(this.mHeight);
        pw.print(prefix);
        pw.print("mExitingBlackFrame=");
        pw.println(this.mExitingBlackFrame);
        BlackFrame blackFrame = this.mExitingBlackFrame;
        if (blackFrame != null) {
            blackFrame.printTo(prefix + "  ", pw);
        }
        pw.print(prefix);
        pw.print("mEnteringBlackFrame=");
        pw.println(this.mEnteringBlackFrame);
        BlackFrame blackFrame2 = this.mEnteringBlackFrame;
        if (blackFrame2 != null) {
            blackFrame2.printTo(prefix + "  ", pw);
        }
        pw.print(prefix);
        pw.print("mCurRotation=");
        pw.print(this.mCurRotation);
        pw.print(" mOriginalRotation=");
        pw.println(this.mOriginalRotation);
        pw.print(prefix);
        pw.print("mOriginalWidth=");
        pw.print(this.mOriginalWidth);
        pw.print(" mOriginalHeight=");
        pw.println(this.mOriginalHeight);
        pw.print(prefix);
        pw.print("mStarted=");
        pw.print(this.mStarted);
        pw.print(" mAnimRunning=");
        pw.print(this.mAnimRunning);
        pw.print(" mFinishAnimReady=");
        pw.print(this.mFinishAnimReady);
        pw.print(" mFinishAnimStartTime=");
        pw.println(this.mFinishAnimStartTime);
        pw.print(prefix);
        pw.print("mStartExitAnimation=");
        pw.print(this.mStartExitAnimation);
        pw.print(" ");
        this.mStartExitTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mStartEnterAnimation=");
        pw.print(this.mStartEnterAnimation);
        pw.print(" ");
        this.mStartEnterTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mStartFrameAnimation=");
        pw.print(this.mStartFrameAnimation);
        pw.print(" ");
        this.mStartFrameTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mFinishExitAnimation=");
        pw.print(this.mFinishExitAnimation);
        pw.print(" ");
        this.mFinishExitTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mFinishEnterAnimation=");
        pw.print(this.mFinishEnterAnimation);
        pw.print(" ");
        this.mFinishEnterTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mFinishFrameAnimation=");
        pw.print(this.mFinishFrameAnimation);
        pw.print(" ");
        this.mFinishFrameTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mRotateExitAnimation=");
        pw.print(this.mRotateExitAnimation);
        pw.print(" ");
        this.mRotateExitTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mRotateEnterAnimation=");
        pw.print(this.mRotateEnterAnimation);
        pw.print(" ");
        this.mRotateEnterTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mRotateFrameAnimation=");
        pw.print(this.mRotateFrameAnimation);
        pw.print(" ");
        this.mRotateFrameTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mExitTransformation=");
        this.mExitTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mEnterTransformation=");
        this.mEnterTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mFrameTransformation=");
        this.mFrameTransformation.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mFrameInitialMatrix=");
        this.mFrameInitialMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mSnapshotInitialMatrix=");
        this.mSnapshotInitialMatrix.printShortString(pw);
        pw.print(" mSnapshotFinalMatrix=");
        this.mSnapshotFinalMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mExitFrameFinalMatrix=");
        this.mExitFrameFinalMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix);
        pw.print("mForceDefaultOrientation=");
        pw.print(this.mForceDefaultOrientation);
        if (this.mForceDefaultOrientation) {
            pw.print(" mOriginalDisplayRect=");
            pw.print(this.mOriginalDisplayRect.toShortString());
            pw.print(" mCurrentDisplayRect=");
            pw.println(this.mCurrentDisplayRect.toShortString());
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1133871366145L, this.mStarted);
        proto.write(1133871366146L, this.mAnimRunning);
        proto.end(token);
    }

    public ScreenRotationAnimation(Context context, DisplayContent displayContent, boolean fixedToUserRotation, boolean isSecure, WindowManagerService service) {
        int originalHeight;
        int originalWidth;
        this.mService = service;
        this.mContext = context;
        this.mDisplayContent = displayContent;
        displayContent.getBounds(this.mOriginalDisplayRect);
        Display display = displayContent.getDisplay();
        int originalRotation = display.getRotation();
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (fixedToUserRotation) {
            this.mForceDefaultOrientation = true;
            int originalWidth2 = displayContent.mBaseDisplayWidth;
            originalHeight = displayContent.mBaseDisplayHeight;
            originalWidth = originalWidth2;
        } else {
            int originalWidth3 = displayInfo.logicalWidth;
            originalHeight = displayInfo.logicalHeight;
            originalWidth = originalWidth3;
        }
        if (originalRotation == 1 || originalRotation == 3) {
            this.mWidth = originalHeight;
            this.mHeight = originalWidth;
        } else {
            this.mWidth = originalWidth;
            this.mHeight = originalHeight;
        }
        this.mOriginalRotation = originalRotation;
        this.mOriginalWidth = originalWidth;
        this.mOriginalHeight = originalHeight;
        SurfaceControl.Transaction t = this.mService.mTransactionFactory.make();
        try {
            try {
                this.mSurfaceControl = displayContent.makeOverlay().setName("ScreenshotSurface").setBufferSize(this.mWidth, this.mHeight).setSecure(isSecure).build();
                SurfaceControl.Transaction t2 = this.mService.mTransactionFactory.make();
                t2.setOverrideScalingMode(this.mSurfaceControl, 1);
                t2.apply(true);
                int displayId = display.getDisplayId();
                Surface surface = this.mService.mSurfaceFactory.make();
                surface.copyFrom(this.mSurfaceControl);
                SurfaceControl.ScreenshotGraphicBuffer gb = this.mService.mDisplayManagerInternal.screenshot(displayId);
                if (gb != null) {
                    try {
                        surface.attachAndQueueBufferWithColorSpace(gb.getGraphicBuffer(), gb.getColorSpace());
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Failed to attach screenshot - " + e.getMessage());
                    }
                    if (gb.containsSecureLayers()) {
                        t.setSecure(this.mSurfaceControl, true);
                    }
                    t.setLayer(this.mSurfaceControl, SCREEN_FREEZE_LAYER_SCREENSHOT);
                    t.setAlpha(this.mSurfaceControl, 0.0f);
                    t.show(this.mSurfaceControl);
                } else {
                    Slog.w(TAG, "Unable to take screenshot of display " + displayId);
                }
                surface.destroy();
            } catch (Surface.OutOfResourcesException e2) {
                e = e2;
                Slog.w(TAG, "Unable to allocate freeze surface", e);
                if (!WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                }
                Slog.i(TAG, "  FREEZE " + this.mSurfaceControl + ": CREATE");
                setRotation(t, originalRotation);
                t.apply();
            }
        } catch (Surface.OutOfResourcesException e3) {
            e = e3;
        }
        if (!WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
            Slog.i(TAG, "  FREEZE " + this.mSurfaceControl + ": CREATE");
        }
        setRotation(t, originalRotation);
        t.apply();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasScreenshot() {
        return this.mSurfaceControl != null;
    }

    private void setSnapshotTransform(SurfaceControl.Transaction t, Matrix matrix, float alpha) {
        if (this.mSurfaceControl != null) {
            matrix.getValues(this.mTmpFloats);
            float[] fArr = this.mTmpFloats;
            float x = fArr[2];
            float y = fArr[5];
            if (this.mForceDefaultOrientation) {
                this.mDisplayContent.getBounds(this.mCurrentDisplayRect);
                x -= this.mCurrentDisplayRect.left;
                y -= this.mCurrentDisplayRect.top;
            }
            t.setPosition(this.mSurfaceControl, x, y);
            SurfaceControl surfaceControl = this.mSurfaceControl;
            float[] fArr2 = this.mTmpFloats;
            t.setMatrix(surfaceControl, fArr2[0], fArr2[3], fArr2[1], fArr2[4]);
            t.setAlpha(this.mSurfaceControl, alpha);
        }
    }

    public static void createRotationMatrix(int rotation, int width, int height, Matrix outMatrix) {
        if (rotation == 0) {
            outMatrix.reset();
        } else if (rotation == 1) {
            outMatrix.setRotate(90.0f, 0.0f, 0.0f);
            outMatrix.postTranslate(height, 0.0f);
        } else if (rotation == 2) {
            outMatrix.setRotate(180.0f, 0.0f, 0.0f);
            outMatrix.postTranslate(width, height);
        } else if (rotation == 3) {
            outMatrix.setRotate(270.0f, 0.0f, 0.0f);
            outMatrix.postTranslate(0.0f, width);
        }
    }

    private void setRotation(SurfaceControl.Transaction t, int rotation) {
        this.mCurRotation = rotation;
        int delta = DisplayContent.deltaRotation(rotation, 0);
        createRotationMatrix(delta, this.mWidth, this.mHeight, this.mSnapshotInitialMatrix);
        setSnapshotTransform(t, this.mSnapshotInitialMatrix, 1.0f);
    }

    public boolean setRotation(SurfaceControl.Transaction t, int rotation, long maxAnimationDuration, float animationScale, int finalWidth, int finalHeight) {
        setRotation(t, rotation);
        return false;
    }

    private boolean startAnimation(SurfaceControl.Transaction t, long maxAnimationDuration, float animationScale, int finalWidth, int finalHeight, boolean dismissing, int exitAnim, int enterAnim) {
        boolean customAnim;
        Rect outer;
        Rect inner;
        if (this.mSurfaceControl == null) {
            return false;
        }
        if (this.mStarted) {
            return true;
        }
        this.mStarted = true;
        int delta = DisplayContent.deltaRotation(this.mCurRotation, this.mOriginalRotation);
        if (exitAnim != 0 && enterAnim != 0) {
            customAnim = true;
            this.mRotateExitAnimation = AnimationUtils.loadAnimation(this.mContext, exitAnim);
            this.mRotateEnterAnimation = AnimationUtils.loadAnimation(this.mContext, enterAnim);
        } else {
            customAnim = false;
            if (delta == 0) {
                this.mRotateExitAnimation = AnimationUtils.loadAnimation(this.mContext, 17432711);
                this.mRotateEnterAnimation = AnimationUtils.loadAnimation(this.mContext, 17432710);
            } else if (delta == 1) {
                this.mRotateExitAnimation = AnimationUtils.loadAnimation(this.mContext, 17432723);
                this.mRotateEnterAnimation = AnimationUtils.loadAnimation(this.mContext, 17432722);
            } else if (delta == 2) {
                this.mRotateExitAnimation = AnimationUtils.loadAnimation(this.mContext, 17432714);
                this.mRotateEnterAnimation = AnimationUtils.loadAnimation(this.mContext, 17432713);
            } else if (delta == 3) {
                this.mRotateExitAnimation = AnimationUtils.loadAnimation(this.mContext, 17432720);
                this.mRotateEnterAnimation = AnimationUtils.loadAnimation(this.mContext, 17432719);
            }
        }
        this.mRotateEnterAnimation.initialize(finalWidth, finalHeight, this.mOriginalWidth, this.mOriginalHeight);
        this.mRotateExitAnimation.initialize(finalWidth, finalHeight, this.mOriginalWidth, this.mOriginalHeight);
        this.mAnimRunning = false;
        this.mFinishAnimReady = false;
        this.mFinishAnimStartTime = -1L;
        this.mRotateExitAnimation.restrictDuration(maxAnimationDuration);
        this.mRotateExitAnimation.scaleCurrentDuration(animationScale);
        this.mRotateEnterAnimation.restrictDuration(maxAnimationDuration);
        this.mRotateEnterAnimation.scaleCurrentDuration(animationScale);
        this.mDisplayContent.getDisplay().getLayerStack();
        if (!customAnim && this.mExitingBlackFrame == null) {
            try {
                createRotationMatrix(delta, this.mOriginalWidth, this.mOriginalHeight, this.mFrameInitialMatrix);
                if (this.mForceDefaultOrientation) {
                    outer = this.mCurrentDisplayRect;
                    inner = this.mOriginalDisplayRect;
                } else {
                    outer = new Rect((-this.mOriginalWidth) * 1, (-this.mOriginalHeight) * 1, this.mOriginalWidth * 2, this.mOriginalHeight * 2);
                    inner = new Rect(0, 0, this.mOriginalWidth, this.mOriginalHeight);
                }
                this.mExitingBlackFrame = new BlackFrame(this.mService.mTransactionFactory, t, outer, inner, SCREEN_FREEZE_LAYER_EXIT, this.mDisplayContent, this.mForceDefaultOrientation);
            } catch (Surface.OutOfResourcesException e) {
                e = e;
            }
            try {
                this.mExitingBlackFrame.setMatrix(t, this.mFrameInitialMatrix);
            } catch (Surface.OutOfResourcesException e2) {
                e = e2;
                Slog.w(TAG, "Unable to allocate black surface", e);
                return !customAnim ? true : true;
            }
        }
        if (!customAnim && this.mEnteringBlackFrame == null) {
            try {
                Rect outer2 = new Rect((-finalWidth) * 1, (-finalHeight) * 1, finalWidth * 2, finalHeight * 2);
                Rect inner2 = new Rect(0, 0, finalWidth, finalHeight);
                this.mEnteringBlackFrame = new BlackFrame(this.mService.mTransactionFactory, t, outer2, inner2, 2010000, this.mDisplayContent, false);
                return true;
            } catch (Surface.OutOfResourcesException e3) {
                Slog.w(TAG, "Unable to allocate black surface", e3);
                return true;
            }
        }
    }

    public boolean dismiss(SurfaceControl.Transaction t, long maxAnimationDuration, float animationScale, int finalWidth, int finalHeight, int exitAnim, int enterAnim) {
        if (this.mSurfaceControl == null) {
            return false;
        }
        if (!this.mStarted) {
            startAnimation(t, maxAnimationDuration, animationScale, finalWidth, finalHeight, true, exitAnim, enterAnim);
        }
        if (this.mStarted) {
            this.mFinishAnimReady = true;
            return true;
        }
        return false;
    }

    public void kill() {
        if (this.mSurfaceControl != null) {
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                Slog.i(TAG, "  FREEZE " + this.mSurfaceControl + ": DESTROY");
            }
            this.mService.mTransactionFactory.make().remove(this.mSurfaceControl).apply();
            this.mSurfaceControl = null;
        }
        BlackFrame blackFrame = this.mCustomBlackFrame;
        if (blackFrame != null) {
            blackFrame.kill();
            this.mCustomBlackFrame = null;
        }
        BlackFrame blackFrame2 = this.mExitingBlackFrame;
        if (blackFrame2 != null) {
            blackFrame2.kill();
            this.mExitingBlackFrame = null;
        }
        BlackFrame blackFrame3 = this.mEnteringBlackFrame;
        if (blackFrame3 != null) {
            blackFrame3.kill();
            this.mEnteringBlackFrame = null;
        }
        Animation animation = this.mRotateExitAnimation;
        if (animation != null) {
            animation.cancel();
            this.mRotateExitAnimation = null;
        }
        Animation animation2 = this.mRotateEnterAnimation;
        if (animation2 != null) {
            animation2.cancel();
            this.mRotateEnterAnimation = null;
        }
    }

    public boolean isAnimating() {
        return hasAnimations();
    }

    public boolean isRotating() {
        return this.mCurRotation != this.mOriginalRotation;
    }

    private boolean hasAnimations() {
        return (this.mRotateEnterAnimation == null && this.mRotateExitAnimation == null) ? false : true;
    }

    private boolean stepAnimation(long now) {
        Animation animation;
        Animation animation2;
        if (now > this.mHalfwayPoint) {
            this.mHalfwayPoint = JobStatus.NO_LATEST_RUNTIME;
        }
        if (this.mFinishAnimReady && this.mFinishAnimStartTime < 0) {
            this.mFinishAnimStartTime = now;
        }
        long j = this.mFinishAnimReady ? now - this.mFinishAnimStartTime : 0L;
        boolean more = false;
        this.mMoreRotateExit = false;
        Animation animation3 = this.mRotateExitAnimation;
        if (animation3 != null) {
            this.mMoreRotateExit = animation3.getTransformation(now, this.mRotateExitTransformation);
        }
        this.mMoreRotateEnter = false;
        Animation animation4 = this.mRotateEnterAnimation;
        if (animation4 != null) {
            this.mMoreRotateEnter = animation4.getTransformation(now, this.mRotateEnterTransformation);
        }
        if (!this.mMoreRotateExit && (animation2 = this.mRotateExitAnimation) != null) {
            animation2.cancel();
            this.mRotateExitAnimation = null;
            this.mRotateExitTransformation.clear();
        }
        if (!this.mMoreRotateEnter && (animation = this.mRotateEnterAnimation) != null) {
            animation.cancel();
            this.mRotateEnterAnimation = null;
            this.mRotateEnterTransformation.clear();
        }
        this.mExitTransformation.set(this.mRotateExitTransformation);
        this.mEnterTransformation.set(this.mRotateEnterTransformation);
        more = (this.mMoreRotateEnter || this.mMoreRotateExit || !this.mFinishAnimReady) ? true : true;
        this.mSnapshotFinalMatrix.setConcat(this.mExitTransformation.getMatrix(), this.mSnapshotInitialMatrix);
        return more;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateSurfaces(SurfaceControl.Transaction t) {
        if (!this.mStarted) {
            return;
        }
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null && !this.mMoreStartExit && !this.mMoreFinishExit && !this.mMoreRotateExit) {
            t.hide(surfaceControl);
        }
        BlackFrame blackFrame = this.mCustomBlackFrame;
        if (blackFrame != null) {
            if (!this.mMoreStartFrame && !this.mMoreFinishFrame && !this.mMoreRotateFrame) {
                blackFrame.hide(t);
            } else {
                this.mCustomBlackFrame.setMatrix(t, this.mFrameTransformation.getMatrix());
            }
        }
        BlackFrame blackFrame2 = this.mExitingBlackFrame;
        if (blackFrame2 != null) {
            if (!this.mMoreStartExit && !this.mMoreFinishExit && !this.mMoreRotateExit) {
                blackFrame2.hide(t);
            } else {
                this.mExitFrameFinalMatrix.setConcat(this.mExitTransformation.getMatrix(), this.mFrameInitialMatrix);
                this.mExitingBlackFrame.setMatrix(t, this.mExitFrameFinalMatrix);
                if (this.mForceDefaultOrientation) {
                    this.mExitingBlackFrame.setAlpha(t, this.mExitTransformation.getAlpha());
                }
            }
        }
        BlackFrame blackFrame3 = this.mEnteringBlackFrame;
        if (blackFrame3 != null) {
            if (!this.mMoreStartEnter && !this.mMoreFinishEnter && !this.mMoreRotateEnter) {
                blackFrame3.hide(t);
            } else {
                this.mEnteringBlackFrame.setMatrix(t, this.mEnterTransformation.getMatrix());
            }
        }
        t.setEarlyWakeup();
        setSnapshotTransform(t, this.mSnapshotFinalMatrix, this.mExitTransformation.getAlpha());
    }

    public boolean stepAnimationLocked(long now) {
        if (!hasAnimations()) {
            this.mFinishAnimReady = false;
            return false;
        }
        if (!this.mAnimRunning) {
            Animation animation = this.mRotateEnterAnimation;
            if (animation != null) {
                animation.setStartTime(now);
            }
            Animation animation2 = this.mRotateExitAnimation;
            if (animation2 != null) {
                animation2.setStartTime(now);
            }
            this.mAnimRunning = true;
            this.mHalfwayPoint = (this.mRotateEnterAnimation.getDuration() / 2) + now;
        }
        return stepAnimation(now);
    }

    public Transformation getEnterTransformation() {
        return this.mEnterTransformation;
    }
}
