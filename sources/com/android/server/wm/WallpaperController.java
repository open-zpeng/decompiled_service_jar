package com.android.server.wm;

import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.pm.DumpState;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WallpaperController {
    private static final String TAG = "WindowManager";
    private static final int WALLPAPER_DRAW_NORMAL = 0;
    private static final int WALLPAPER_DRAW_PENDING = 1;
    private static final long WALLPAPER_DRAW_PENDING_TIMEOUT_DURATION = 500;
    private static final int WALLPAPER_DRAW_TIMEOUT = 2;
    private static final long WALLPAPER_TIMEOUT = 150;
    private static final long WALLPAPER_TIMEOUT_RECOVERY = 10000;
    private long mLastWallpaperTimeoutTime;
    private WindowManagerService mService;
    private WindowState mTmpTopWallpaper;
    private WindowState mWaitingOnWallpaper;
    private final ArrayList<WallpaperWindowToken> mWallpaperTokens = new ArrayList<>();
    private WindowState mWallpaperTarget = null;
    private WindowState mPrevWallpaperTarget = null;
    private float mLastWallpaperX = -1.0f;
    private float mLastWallpaperY = -1.0f;
    private float mLastWallpaperXStep = -1.0f;
    private float mLastWallpaperYStep = -1.0f;
    private int mLastWallpaperDisplayOffsetX = Integer.MIN_VALUE;
    private int mLastWallpaperDisplayOffsetY = Integer.MIN_VALUE;
    WindowState mDeferredHideWallpaper = null;
    private int mWallpaperDrawState = 0;
    private final FindWallpaperTargetResult mFindResults = new FindWallpaperTargetResult();
    private final ToBooleanFunction<WindowState> mFindWallpaperTargetFunction = new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$WallpaperController$6pruPGLeSJAwNl9vGfC87eso21w
        public final boolean apply(Object obj) {
            return WallpaperController.lambda$new$0(WallpaperController.this, (WindowState) obj);
        }
    };

    public static /* synthetic */ boolean lambda$new$0(WallpaperController wallpaperController, WindowState w) {
        WindowAnimator winAnimator = wallpaperController.mService.mAnimator;
        if (w.mAttrs.type == 2013) {
            if (wallpaperController.mFindResults.topWallpaper == null || wallpaperController.mFindResults.resetTopWallpaper) {
                wallpaperController.mFindResults.setTopWallpaper(w);
                wallpaperController.mFindResults.resetTopWallpaper = false;
            }
            return false;
        }
        wallpaperController.mFindResults.resetTopWallpaper = true;
        if (w != winAnimator.mWindowDetachedWallpaper && w.mAppToken != null && w.mAppToken.isHidden() && !w.mAppToken.isSelfAnimating()) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "Skipping hidden and not animating token: " + w);
            }
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
            Slog.v(TAG, "Win " + w + ": isOnScreen=" + w.isOnScreen() + " mDrawState=" + w.mWinAnimator.mDrawState);
        }
        if (w.mWillReplaceWindow && wallpaperController.mWallpaperTarget == null && !wallpaperController.mFindResults.useTopWallpaperAsTarget) {
            wallpaperController.mFindResults.setUseTopWallpaperAsTarget(true);
        }
        boolean keyguardGoingAwayWithWallpaper = w.mAppToken != null && w.mAppToken.isSelfAnimating() && AppTransition.isKeyguardGoingAwayTransit(w.mAppToken.getTransit()) && (w.mAppToken.getTransitFlags() & 4) != 0;
        boolean needsShowWhenLockedWallpaper = false;
        if ((w.mAttrs.flags & DumpState.DUMP_FROZEN) != 0 && wallpaperController.mService.mPolicy.isKeyguardLocked() && wallpaperController.mService.mPolicy.isKeyguardOccluded()) {
            needsShowWhenLockedWallpaper = (wallpaperController.isFullscreen(w.mAttrs) && (w.mAppToken == null || w.mAppToken.fillsParent())) ? false : true;
        }
        if (keyguardGoingAwayWithWallpaper || needsShowWhenLockedWallpaper) {
            wallpaperController.mFindResults.setUseTopWallpaperAsTarget(true);
        }
        RecentsAnimationController recentsAnimationController = wallpaperController.mService.getRecentsAnimationController();
        boolean animationWallpaper = (w.mAppToken == null || w.mAppToken.getAnimation() == null || !w.mAppToken.getAnimation().getShowWallpaper()) ? false : true;
        boolean hasWallpaper = (w.mAttrs.flags & 1048576) != 0 || animationWallpaper;
        boolean isRecentsTransitionTarget = recentsAnimationController != null && recentsAnimationController.isWallpaperVisible(w);
        if (isRecentsTransitionTarget) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "Found recents animation wallpaper target: " + w);
            }
            wallpaperController.mFindResults.setWallpaperTarget(w);
            return true;
        } else if (hasWallpaper && w.isOnScreen() && (wallpaperController.mWallpaperTarget == w || w.isDrawFinishedLw())) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "Found wallpaper target: " + w);
            }
            wallpaperController.mFindResults.setWallpaperTarget(w);
            if (w == wallpaperController.mWallpaperTarget && w.mWinAnimator.isAnimationSet() && WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "Win " + w + ": token animating, looking behind.");
            }
            return true;
        } else {
            if (w == winAnimator.mWindowDetachedWallpaper) {
                if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                    Slog.v(TAG, "Found animating detached wallpaper target win: " + w);
                }
                wallpaperController.mFindResults.setUseTopWallpaperAsTarget(true);
            }
            return false;
        }
    }

    public WallpaperController(WindowManagerService service) {
        this.mService = service;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getWallpaperTarget() {
        return this.mWallpaperTarget;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWallpaperTarget(WindowState win) {
        return win == this.mWallpaperTarget;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBelowWallpaperTarget(WindowState win) {
        return this.mWallpaperTarget != null && this.mWallpaperTarget.mLayer >= win.mBaseLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWallpaperVisible() {
        return isWallpaperVisible(this.mWallpaperTarget);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startWallpaperAnimation(Animation a) {
        for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenNdx);
            token.startAnimation(a);
        }
    }

    private final boolean isWallpaperVisible(WindowState wallpaperTarget) {
        RecentsAnimationController recentsAnimationController = this.mService.getRecentsAnimationController();
        boolean isAnimatingWithRecentsComponent = recentsAnimationController != null && recentsAnimationController.isWallpaperVisible(wallpaperTarget);
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
            StringBuilder sb = new StringBuilder();
            sb.append("Wallpaper vis: target ");
            sb.append(wallpaperTarget);
            sb.append(", obscured=");
            sb.append(wallpaperTarget != null ? Boolean.toString(wallpaperTarget.mObscured) : "??");
            sb.append(" animating=");
            sb.append((wallpaperTarget == null || wallpaperTarget.mAppToken == null) ? null : Boolean.valueOf(wallpaperTarget.mAppToken.isSelfAnimating()));
            sb.append(" prev=");
            sb.append(this.mPrevWallpaperTarget);
            sb.append(" recentsAnimationWallpaperVisible=");
            sb.append(isAnimatingWithRecentsComponent);
            Slog.v(TAG, sb.toString());
        }
        return (wallpaperTarget != null && (!wallpaperTarget.mObscured || isAnimatingWithRecentsComponent || (wallpaperTarget.mAppToken != null && wallpaperTarget.mAppToken.isSelfAnimating()))) || this.mPrevWallpaperTarget != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWallpaperTargetAnimating() {
        return this.mWallpaperTarget != null && this.mWallpaperTarget.mWinAnimator.isAnimationSet() && (this.mWallpaperTarget.mAppToken == null || !this.mWallpaperTarget.mAppToken.isWaitingForTransitionStart());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWallpaperVisibility() {
        boolean visible = isWallpaperVisible(this.mWallpaperTarget);
        for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenNdx);
            token.updateWallpaperVisibility(visible);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hideDeferredWallpapersIfNeeded() {
        if (this.mDeferredHideWallpaper != null) {
            hideWallpapers(this.mDeferredHideWallpaper);
            this.mDeferredHideWallpaper = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hideWallpapers(WindowState winGoingAway) {
        if (this.mWallpaperTarget != null && (this.mWallpaperTarget != winGoingAway || this.mPrevWallpaperTarget != null)) {
            return;
        }
        if (this.mService.mAppTransition.isRunning()) {
            this.mDeferredHideWallpaper = winGoingAway;
            return;
        }
        boolean wasDeferred = this.mDeferredHideWallpaper == winGoingAway;
        int i = this.mWallpaperTokens.size() - 1;
        while (true) {
            int i2 = i;
            if (i2 >= 0) {
                WallpaperWindowToken token = this.mWallpaperTokens.get(i2);
                token.hideWallpaperToken(wasDeferred, "hideWallpapers");
                if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT && !token.isHidden()) {
                    Slog.d(TAG, "Hiding wallpaper " + token + " from " + winGoingAway + " target=" + this.mWallpaperTarget + " prev=" + this.mPrevWallpaperTarget + "\n" + Debug.getCallers(5, "  "));
                }
                i = i2 - 1;
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateWallpaperOffset(WindowState wallpaperWin, int dw, int dh, boolean sync) {
        float defaultWallpaperX;
        int offset;
        boolean rawChanged = false;
        float defaultWallpaperX2 = wallpaperWin.isRtl() ? 1.0f : 0.0f;
        float wpx = this.mLastWallpaperX >= 0.0f ? this.mLastWallpaperX : defaultWallpaperX2;
        float wpxs = this.mLastWallpaperXStep >= 0.0f ? this.mLastWallpaperXStep : -1.0f;
        int availw = (wallpaperWin.mFrame.right - wallpaperWin.mFrame.left) - dw;
        int offset2 = availw > 0 ? -((int) ((availw * wpx) + 0.5f)) : 0;
        if (this.mLastWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            offset2 += this.mLastWallpaperDisplayOffsetX;
        }
        int xOffset = offset2;
        if (wallpaperWin.mWallpaperX != wpx || wallpaperWin.mWallpaperXStep != wpxs) {
            wallpaperWin.mWallpaperX = wpx;
            wallpaperWin.mWallpaperXStep = wpxs;
            rawChanged = true;
        }
        float wpy = this.mLastWallpaperY >= 0.0f ? this.mLastWallpaperY : 0.5f;
        float wpys = this.mLastWallpaperYStep >= 0.0f ? this.mLastWallpaperYStep : -1.0f;
        int availh = (wallpaperWin.mFrame.bottom - wallpaperWin.mFrame.top) - dh;
        if (availh > 0) {
            defaultWallpaperX = wpy;
            offset = -((int) ((availh * defaultWallpaperX) + 0.5f));
        } else {
            defaultWallpaperX = wpy;
            offset = 0;
        }
        if (this.mLastWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            offset += this.mLastWallpaperDisplayOffsetY;
        }
        int offset3 = offset;
        if (wallpaperWin.mWallpaperY != defaultWallpaperX || wallpaperWin.mWallpaperYStep != wpys) {
            wallpaperWin.mWallpaperY = defaultWallpaperX;
            wallpaperWin.mWallpaperYStep = wpys;
            rawChanged = true;
        }
        boolean rawChanged2 = rawChanged;
        boolean changed = wallpaperWin.mWinAnimator.setWallpaperOffset(xOffset, offset3);
        if (rawChanged2 && (wallpaperWin.mAttrs.privateFlags & 4) != 0) {
            try {
                if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                    try {
                        Slog.v(TAG, "Report new wp offset " + wallpaperWin + " x=" + wallpaperWin.mWallpaperX + " y=" + wallpaperWin.mWallpaperY);
                    } catch (RemoteException e) {
                    }
                }
                if (sync) {
                    this.mWaitingOnWallpaper = wallpaperWin;
                }
                try {
                    try {
                        try {
                            wallpaperWin.mClient.dispatchWallpaperOffsets(wallpaperWin.mWallpaperX, wallpaperWin.mWallpaperY, wallpaperWin.mWallpaperXStep, wallpaperWin.mWallpaperYStep, sync);
                            if (sync && this.mWaitingOnWallpaper != null) {
                                long start = SystemClock.uptimeMillis();
                                if (this.mLastWallpaperTimeoutTime + 10000 < start) {
                                    try {
                                        if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                                            Slog.v(TAG, "Waiting for offset complete...");
                                        }
                                        this.mService.mWindowMap.wait(WALLPAPER_TIMEOUT);
                                    } catch (InterruptedException e2) {
                                    }
                                    if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                                        Slog.v(TAG, "Offset complete!");
                                    }
                                    if (WALLPAPER_TIMEOUT + start < SystemClock.uptimeMillis()) {
                                        Slog.i(TAG, "Timeout waiting for wallpaper to offset: " + wallpaperWin);
                                        this.mLastWallpaperTimeoutTime = start;
                                    }
                                }
                                this.mWaitingOnWallpaper = null;
                            }
                        } catch (RemoteException e3) {
                        }
                    } catch (RemoteException e4) {
                    }
                } catch (RemoteException e5) {
                }
            } catch (RemoteException e6) {
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowWallpaperPosition(WindowState window, float x, float y, float xStep, float yStep) {
        if (window.mWallpaperX != x || window.mWallpaperY != y) {
            window.mWallpaperX = x;
            window.mWallpaperY = y;
            window.mWallpaperXStep = xStep;
            window.mWallpaperYStep = yStep;
            updateWallpaperOffsetLocked(window, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowWallpaperDisplayOffset(WindowState window, int x, int y) {
        if (window.mWallpaperDisplayOffsetX != x || window.mWallpaperDisplayOffsetY != y) {
            window.mWallpaperDisplayOffsetX = x;
            window.mWallpaperDisplayOffsetY = y;
            updateWallpaperOffsetLocked(window, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bundle sendWindowWallpaperCommand(WindowState window, String action, int x, int y, int z, Bundle extras, boolean sync) {
        if (window == this.mWallpaperTarget || window == this.mPrevWallpaperTarget) {
            for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
                WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenNdx);
                token.sendWindowWallpaperCommand(action, x, y, z, extras, sync);
            }
            return null;
        }
        return null;
    }

    private void updateWallpaperOffsetLocked(WindowState changingTarget, boolean sync) {
        DisplayContent displayContent = changingTarget.getDisplayContent();
        if (displayContent == null) {
            return;
        }
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        int dw = displayInfo.logicalWidth;
        int dh = displayInfo.logicalHeight;
        WindowState target = this.mWallpaperTarget;
        if (target != null) {
            if (target.mWallpaperX >= 0.0f) {
                this.mLastWallpaperX = target.mWallpaperX;
            } else if (changingTarget.mWallpaperX >= 0.0f) {
                this.mLastWallpaperX = changingTarget.mWallpaperX;
            }
            if (target.mWallpaperY >= 0.0f) {
                this.mLastWallpaperY = target.mWallpaperY;
            } else if (changingTarget.mWallpaperY >= 0.0f) {
                this.mLastWallpaperY = changingTarget.mWallpaperY;
            }
            if (target.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetX = target.mWallpaperDisplayOffsetX;
            } else if (changingTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetX = changingTarget.mWallpaperDisplayOffsetX;
            }
            if (target.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetY = target.mWallpaperDisplayOffsetY;
            } else if (changingTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetY = changingTarget.mWallpaperDisplayOffsetY;
            }
            if (target.mWallpaperXStep >= 0.0f) {
                this.mLastWallpaperXStep = target.mWallpaperXStep;
            } else if (changingTarget.mWallpaperXStep >= 0.0f) {
                this.mLastWallpaperXStep = changingTarget.mWallpaperXStep;
            }
            if (target.mWallpaperYStep >= 0.0f) {
                this.mLastWallpaperYStep = target.mWallpaperYStep;
            } else if (changingTarget.mWallpaperYStep >= 0.0f) {
                this.mLastWallpaperYStep = changingTarget.mWallpaperYStep;
            }
        }
        for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            this.mWallpaperTokens.get(curTokenNdx).updateWallpaperOffset(dw, dh, sync);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearLastWallpaperTimeoutTime() {
        this.mLastWallpaperTimeoutTime = 0L;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void wallpaperCommandComplete(IBinder window) {
        if (this.mWaitingOnWallpaper != null && this.mWaitingOnWallpaper.mClient.asBinder() == window) {
            this.mWaitingOnWallpaper = null;
            this.mService.mWindowMap.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void wallpaperOffsetsComplete(IBinder window) {
        if (this.mWaitingOnWallpaper != null && this.mWaitingOnWallpaper.mClient.asBinder() == window) {
            this.mWaitingOnWallpaper = null;
            this.mService.mWindowMap.notifyAll();
        }
    }

    private void findWallpaperTarget(DisplayContent dc) {
        this.mFindResults.reset();
        if (dc.isStackVisible(5)) {
            this.mFindResults.setUseTopWallpaperAsTarget(true);
        }
        dc.forAllWindows(this.mFindWallpaperTargetFunction, true);
        if (this.mFindResults.wallpaperTarget == null && this.mFindResults.useTopWallpaperAsTarget) {
            this.mFindResults.setWallpaperTarget(this.mFindResults.topWallpaper);
        }
    }

    private boolean isFullscreen(WindowManager.LayoutParams attrs) {
        return attrs.x == 0 && attrs.y == 0 && attrs.width == -1 && attrs.height == -1;
    }

    private void updateWallpaperWindowsTarget(DisplayContent dc, FindWallpaperTargetResult result) {
        WindowState wallpaperTarget = result.wallpaperTarget;
        if (this.mWallpaperTarget == wallpaperTarget || (this.mPrevWallpaperTarget != null && this.mPrevWallpaperTarget == wallpaperTarget)) {
            if (this.mPrevWallpaperTarget != null && !this.mPrevWallpaperTarget.isAnimatingLw()) {
                if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                    Slog.v(TAG, "No longer animating wallpaper targets!");
                }
                this.mPrevWallpaperTarget = null;
                this.mWallpaperTarget = wallpaperTarget;
                return;
            }
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG, "New wallpaper target: " + wallpaperTarget + " prevTarget: " + this.mWallpaperTarget);
        }
        this.mPrevWallpaperTarget = null;
        final WindowState prevWallpaperTarget = this.mWallpaperTarget;
        this.mWallpaperTarget = wallpaperTarget;
        if (wallpaperTarget == null || prevWallpaperTarget == null) {
            return;
        }
        boolean oldAnim = prevWallpaperTarget.isAnimatingLw();
        boolean foundAnim = wallpaperTarget.isAnimatingLw();
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG, "New animation: " + foundAnim + " old animation: " + oldAnim);
        }
        if (!foundAnim || !oldAnim || dc.getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$WallpaperController$Gy7houdzET4VmpY0QJ2v-NX1b7k
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return WallpaperController.lambda$updateWallpaperWindowsTarget$1(WindowState.this, (WindowState) obj);
            }
        }) == null) {
            return;
        }
        boolean oldTargetHidden = false;
        boolean newTargetHidden = wallpaperTarget.mAppToken != null && wallpaperTarget.mAppToken.hiddenRequested;
        if (prevWallpaperTarget.mAppToken != null && prevWallpaperTarget.mAppToken.hiddenRequested) {
            oldTargetHidden = true;
        }
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG, "Animating wallpapers: old: " + prevWallpaperTarget + " hidden=" + oldTargetHidden + " new: " + wallpaperTarget + " hidden=" + newTargetHidden);
        }
        this.mPrevWallpaperTarget = prevWallpaperTarget;
        if (newTargetHidden && !oldTargetHidden) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                Slog.v(TAG, "Old wallpaper still the target.");
            }
            this.mWallpaperTarget = prevWallpaperTarget;
        } else if (newTargetHidden == oldTargetHidden && !this.mService.mOpeningApps.contains(wallpaperTarget.mAppToken) && (this.mService.mOpeningApps.contains(prevWallpaperTarget.mAppToken) || this.mService.mClosingApps.contains(prevWallpaperTarget.mAppToken))) {
            this.mWallpaperTarget = prevWallpaperTarget;
        }
        result.setWallpaperTarget(wallpaperTarget);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$updateWallpaperWindowsTarget$1(WindowState prevWallpaperTarget, WindowState w) {
        return w == prevWallpaperTarget;
    }

    private void updateWallpaperTokens(boolean visible) {
        for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenNdx);
            token.updateWallpaperWindows(visible);
            token.getDisplayContent().assignWindowLayers(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void adjustWallpaperWindows(DisplayContent dc) {
        boolean z = false;
        this.mService.mRoot.mWallpaperMayChange = false;
        findWallpaperTarget(dc);
        updateWallpaperWindowsTarget(dc, this.mFindResults);
        if (this.mWallpaperTarget != null && isWallpaperVisible(this.mWallpaperTarget)) {
            z = true;
        }
        boolean visible = z;
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
            Slog.v(TAG, "Wallpaper visibility: " + visible);
        }
        if (visible) {
            if (this.mWallpaperTarget.mWallpaperX >= 0.0f) {
                this.mLastWallpaperX = this.mWallpaperTarget.mWallpaperX;
                this.mLastWallpaperXStep = this.mWallpaperTarget.mWallpaperXStep;
            }
            if (this.mWallpaperTarget.mWallpaperY >= 0.0f) {
                this.mLastWallpaperY = this.mWallpaperTarget.mWallpaperY;
                this.mLastWallpaperYStep = this.mWallpaperTarget.mWallpaperYStep;
            }
            if (this.mWallpaperTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetX = this.mWallpaperTarget.mWallpaperDisplayOffsetX;
            }
            if (this.mWallpaperTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                this.mLastWallpaperDisplayOffsetY = this.mWallpaperTarget.mWallpaperDisplayOffsetY;
            }
        }
        updateWallpaperTokens(visible);
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.d(TAG, "New wallpaper: target=" + this.mWallpaperTarget + " prev=" + this.mPrevWallpaperTarget);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean processWallpaperDrawPendingTimeout() {
        if (this.mWallpaperDrawState == 1) {
            this.mWallpaperDrawState = 2;
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "*** WALLPAPER DRAW TIMEOUT");
            }
            if (this.mService.getRecentsAnimationController() != null) {
                this.mService.getRecentsAnimationController().cancelAnimation(2, "wallpaperDrawPendingTimeout");
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean wallpaperTransitionReady() {
        boolean transitionReady = true;
        boolean wallpaperReady = true;
        int curTokenIndex = this.mWallpaperTokens.size() - 1;
        while (true) {
            if (curTokenIndex < 0 || 1 == 0) {
                break;
            }
            WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenIndex);
            if (!token.hasVisibleNotDrawnWallpaper()) {
                curTokenIndex--;
            } else {
                wallpaperReady = false;
                if (this.mWallpaperDrawState != 2) {
                    transitionReady = false;
                }
                if (this.mWallpaperDrawState == 0) {
                    this.mWallpaperDrawState = 1;
                    this.mService.mH.removeMessages(39);
                    this.mService.mH.sendEmptyMessageDelayed(39, 500L);
                }
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                    Slog.v(TAG, "Wallpaper should be visible but has not been drawn yet. mWallpaperDrawState=" + this.mWallpaperDrawState);
                }
            }
        }
        if (wallpaperReady) {
            this.mWallpaperDrawState = 0;
            this.mService.mH.removeMessages(39);
        }
        return transitionReady;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void adjustWallpaperWindowsForAppTransitionIfNeeded(DisplayContent dc, ArraySet<AppWindowToken> openingApps) {
        boolean adjust = false;
        if ((dc.pendingLayoutChanges & 4) != 0) {
            adjust = true;
        } else {
            int i = openingApps.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                }
                AppWindowToken token = openingApps.valueAt(i);
                if (!token.windowsCanBeWallpaperTarget()) {
                    i--;
                } else {
                    adjust = true;
                    break;
                }
            }
        }
        if (adjust) {
            adjustWallpaperWindows(dc);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addWallpaperToken(WallpaperWindowToken token) {
        this.mWallpaperTokens.add(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeWallpaperToken(WallpaperWindowToken token) {
        this.mWallpaperTokens.remove(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bitmap screenshotWallpaperLocked() {
        if (!this.mService.mPolicy.isScreenOn()) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.i(TAG, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        WindowState wallpaperWindowState = getTopVisibleWallpaper();
        if (wallpaperWindowState == null) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.i(TAG, "No visible wallpaper to screenshot");
            }
            return null;
        }
        Rect bounds = wallpaperWindowState.getBounds();
        bounds.offsetTo(0, 0);
        GraphicBuffer wallpaperBuffer = SurfaceControl.captureLayers(wallpaperWindowState.getSurfaceControl().getHandle(), bounds, 1.0f);
        if (wallpaperBuffer == null) {
            Slog.w(TAG, "Failed to screenshot wallpaper");
            return null;
        }
        return Bitmap.createHardwareBitmap(wallpaperBuffer);
    }

    private WindowState getTopVisibleWallpaper() {
        this.mTmpTopWallpaper = null;
        for (int curTokenNdx = this.mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            WallpaperWindowToken token = this.mWallpaperTokens.get(curTokenNdx);
            token.forAllWindows(new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$WallpaperController$3kGUJhX6nW41Z26JaiCQelxXZr8
                public final boolean apply(Object obj) {
                    return WallpaperController.lambda$getTopVisibleWallpaper$2(WallpaperController.this, (WindowState) obj);
                }
            }, true);
        }
        return this.mTmpTopWallpaper;
    }

    public static /* synthetic */ boolean lambda$getTopVisibleWallpaper$2(WallpaperController wallpaperController, WindowState w) {
        WindowStateAnimator winAnim = w.mWinAnimator;
        if (winAnim != null && winAnim.getShown() && winAnim.mLastAlpha > 0.0f) {
            wallpaperController.mTmpTopWallpaper = w;
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mWallpaperTarget=");
        pw.println(this.mWallpaperTarget);
        if (this.mPrevWallpaperTarget != null) {
            pw.print(prefix);
            pw.print("mPrevWallpaperTarget=");
            pw.println(this.mPrevWallpaperTarget);
        }
        pw.print(prefix);
        pw.print("mLastWallpaperX=");
        pw.print(this.mLastWallpaperX);
        pw.print(" mLastWallpaperY=");
        pw.println(this.mLastWallpaperY);
        if (this.mLastWallpaperDisplayOffsetX != Integer.MIN_VALUE || this.mLastWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.print(prefix);
            pw.print("mLastWallpaperDisplayOffsetX=");
            pw.print(this.mLastWallpaperDisplayOffsetX);
            pw.print(" mLastWallpaperDisplayOffsetY=");
            pw.println(this.mLastWallpaperDisplayOffsetY);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class FindWallpaperTargetResult {
        boolean resetTopWallpaper;
        WindowState topWallpaper;
        boolean useTopWallpaperAsTarget;
        WindowState wallpaperTarget;

        private FindWallpaperTargetResult() {
            this.topWallpaper = null;
            this.useTopWallpaperAsTarget = false;
            this.wallpaperTarget = null;
            this.resetTopWallpaper = false;
        }

        void setTopWallpaper(WindowState win) {
            this.topWallpaper = win;
        }

        void setWallpaperTarget(WindowState win) {
            this.wallpaperTarget = win;
        }

        void setUseTopWallpaperAsTarget(boolean topWallpaperAsTarget) {
            this.useTopWallpaperAsTarget = topWallpaperAsTarget;
        }

        void reset() {
            this.topWallpaper = null;
            this.wallpaperTarget = null;
            this.useTopWallpaperAsTarget = false;
            this.resetTopWallpaper = false;
        }
    }
}
