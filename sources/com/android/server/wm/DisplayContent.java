package com.android.server.wm;

import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.view.IInputMethodClient;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.DisplayContent;
import com.android.server.wm.WindowContainer;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.utils.CoordinateTransforms;
import com.android.server.wm.utils.RotationCache;
import com.android.server.wm.utils.WmDisplayCutout;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class DisplayContent extends WindowContainer<DisplayChildWindowContainer> {
    private static final String TAG = "WindowManager";
    boolean isDefaultDisplay;
    private final AboveAppWindowContainers mAboveAppWindowsContainers;
    private boolean mAltOrientation;
    private final Consumer<WindowState> mApplyPostLayoutPolicy;
    private final Consumer<WindowState> mApplySurfaceChangesTransaction;
    int mBaseDisplayDensity;
    int mBaseDisplayHeight;
    private Rect mBaseDisplayRect;
    int mBaseDisplayWidth;
    private final NonAppWindowContainers mBelowAppWindowsContainers;
    private final DisplayMetrics mCompatDisplayMetrics;
    float mCompatibleScreenScale;
    private final Predicate<WindowState> mComputeImeTargetPredicate;
    private int mDeferUpdateImeTargetCount;
    private boolean mDeferredRemoval;
    private final Display mDisplay;
    private final RotationCache<DisplayCutout, WmDisplayCutout> mDisplayCutoutCache;
    DisplayFrames mDisplayFrames;
    private final int mDisplayId;
    private final DisplayInfo mDisplayInfo;
    private final DisplayMetrics mDisplayMetrics;
    private boolean mDisplayReady;
    boolean mDisplayScalingDisabled;
    final DockedStackDividerController mDividerControllerLocked;
    final ArrayList<WindowToken> mExitingTokens;
    private final ToBooleanFunction<WindowState> mFindFocusedWindow;
    private boolean mHaveApp;
    private boolean mHaveBootMsg;
    private boolean mHaveKeyguard;
    private boolean mHaveVendorWallpaper;
    private boolean mHaveWallpaper;
    private final TaskStackContainers mHomeStackContainers;
    private final NonMagnifiableWindowContainers mImeWindowsContainers;
    DisplayCutout mInitialDisplayCutout;
    int mInitialDisplayDensity;
    int mInitialDisplayHeight;
    int mInitialDisplayWidth;
    private boolean mLastHasContent;
    private int mLastKeyguardForcedOrientation;
    private int mLastOrientation;
    private boolean mLastWallpaperVisible;
    private int mLastWindowForcedOrientation;
    private boolean mLayoutNeeded;
    int mLayoutSeq;
    private MagnificationSpec mMagnificationSpec;
    private int mMaxUiWidth;
    private final AboveAppWindowContainers mMiddleAppWindowsContainers;
    private SurfaceControl mOverlayLayer;
    private final Consumer<WindowState> mPerformLayout;
    private final Consumer<WindowState> mPerformLayoutAttached;
    final PinnedStackController mPinnedStackControllerLocked;
    final DisplayMetrics mRealDisplayMetrics;
    private boolean mRemovingDisplay;
    private int mRotation;
    private final Consumer<WindowState> mScheduleToastTimeout;
    private final SurfaceSession mSession;
    boolean mShouldOverrideDisplayConfiguration;
    private int mSurfaceSize;
    TaskTapPointerEventListener mTapDetector;
    final ArraySet<WindowState> mTapExcludeProvidingWindows;
    final ArrayList<WindowState> mTapExcludedWindows;
    private final TaskStackContainers mTaskStackContainers;
    private final ApplySurfaceChangesTransactionState mTmpApplySurfaceChangesTransactionState;
    private final Rect mTmpBounds;
    private final DisplayMetrics mTmpDisplayMetrics;
    private final float[] mTmpFloats;
    private boolean mTmpInitial;
    private final Matrix mTmpMatrix;
    private boolean mTmpRecoveringMemory;
    private final Rect mTmpRect;
    private final Rect mTmpRect2;
    private final RectF mTmpRectF;
    private final Region mTmpRegion;
    private final TaskForResizePointSearchResult mTmpTaskForResizePointSearchResult;
    private final LinkedList<AppWindowToken> mTmpUpdateAllDrawn;
    private WindowState mTmpWindow;
    private WindowState mTmpWindow2;
    private WindowAnimator mTmpWindowAnimator;
    private final HashMap<IBinder, WindowToken> mTokenMap;
    private Region mTouchExcludeRegion;
    private boolean mUpdateImeTarget;
    private final Consumer<WindowState> mUpdateWallpaperForAnimator;
    private final Consumer<WindowState> mUpdateWindowsForAnimator;
    WallpaperController mWallpaperController;
    private SurfaceControl mWindowingLayer;
    int pendingLayoutChanges;

    public static /* synthetic */ void lambda$new$0(DisplayContent displayContent, WindowState w) {
        WindowStateAnimator winAnimator = w.mWinAnimator;
        AppWindowToken atoken = w.mAppToken;
        if (winAnimator.mDrawState == 3) {
            if ((atoken == null || atoken.canShowWindows()) && w.performShowLocked()) {
                displayContent.pendingLayoutChanges |= 8;
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    displayContent.mService.mWindowPlacerLocked.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5", displayContent.pendingLayoutChanges);
                }
            }
        }
    }

    public static /* synthetic */ void lambda$new$1(DisplayContent displayContent, WindowState w) {
        TaskStack stack;
        AnimationAdapter anim;
        TaskStack stack2;
        WindowStateAnimator winAnimator = w.mWinAnimator;
        if (winAnimator.mSurfaceController == null || !winAnimator.hasSurface()) {
            return;
        }
        int flags = w.mAttrs.flags;
        if (winAnimator.isAnimationSet() && (anim = w.getAnimation()) != null) {
            if ((flags & 1048576) != 0 && anim.getDetachWallpaper()) {
                displayContent.mTmpWindow = w;
            }
            int color = anim.getBackgroundColor();
            if (color != 0 && (stack2 = w.getStack()) != null) {
                stack2.setAnimationBackground(winAnimator, color);
            }
        }
        AppWindowToken atoken = winAnimator.mWin.mAppToken;
        AnimationAdapter animation = atoken != null ? atoken.getAnimation() : null;
        if (animation != null) {
            if ((1048576 & flags) != 0 && animation.getDetachWallpaper()) {
                displayContent.mTmpWindow = w;
            }
            int color2 = animation.getBackgroundColor();
            if (color2 != 0 && (stack = w.getStack()) != null) {
                stack.setAnimationBackground(winAnimator, color2);
            }
        }
    }

    public static /* synthetic */ void lambda$new$2(DisplayContent displayContent, WindowState w) {
        int lostFocusUid = displayContent.mTmpWindow.mOwnerUid;
        Handler handler = displayContent.mService.mH;
        if (w.mAttrs.type == 2005 && w.mOwnerUid == lostFocusUid && !handler.hasMessages(52, w)) {
            handler.sendMessageDelayed(handler.obtainMessage(52, w), w.mAttrs.hideTimeoutMilliseconds);
        }
    }

    public static /* synthetic */ boolean lambda$new$3(DisplayContent displayContent, WindowState w) {
        AppWindowToken focusedApp = displayContent.mService.mFocusedApp;
        if (WindowManagerDebugConfig.DEBUG_FOCUS) {
            Slog.v(TAG, "Looking for focus: " + w + ", flags=" + w.mAttrs.flags + ", canReceive=" + w.canReceiveKeys());
        }
        if (w.canReceiveKeys()) {
            AppWindowToken wtoken = w.mAppToken;
            if (wtoken != null && (wtoken.removed || wtoken.sendingToBottom)) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Skipping ");
                    sb.append(wtoken);
                    sb.append(" because ");
                    sb.append(wtoken.removed ? "removed" : "sendingToBottom");
                    Slog.v(TAG, sb.toString());
                }
                return false;
            } else if (focusedApp == null) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: focusedApp=null using new focus @ " + w);
                }
                displayContent.mTmpWindow = w;
                return true;
            } else if (!focusedApp.windowsAreFocusable()) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: focusedApp windows not focusable using new focus @ " + w);
                }
                displayContent.mTmpWindow = w;
                return true;
            } else if (wtoken != null && w.mAttrs.type != 3 && focusedApp.compareTo((WindowContainer) wtoken) > 0) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: Reached focused app=" + focusedApp);
                }
                displayContent.mTmpWindow = null;
                return true;
            } else {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: Found new focus @ " + w);
                }
                displayContent.mTmpWindow = w;
                return true;
            }
        }
        return false;
    }

    public static /* synthetic */ void lambda$new$4(DisplayContent displayContent, WindowState w) {
        boolean gone = (displayContent.mTmpWindow != null && displayContent.mService.mPolicy.canBeHiddenByKeyguardLw(w)) || w.isGoneForLayoutLw();
        if (WindowManagerDebugConfig.DEBUG_LAYOUT && !w.mLayoutAttached) {
            Slog.v(TAG, "1ST PASS " + w + ": gone=" + gone + " mHaveFrame=" + w.mHaveFrame + " mLayoutAttached=" + w.mLayoutAttached + " screen changed=" + w.isConfigChanged());
            AppWindowToken atoken = w.mAppToken;
            if (gone) {
                StringBuilder sb = new StringBuilder();
                sb.append("  GONE: mViewVisibility=");
                sb.append(w.mViewVisibility);
                sb.append(" mRelayoutCalled=");
                sb.append(w.mRelayoutCalled);
                sb.append(" hidden=");
                sb.append(w.mToken.isHidden());
                sb.append(" hiddenRequested=");
                sb.append(atoken != null && atoken.hiddenRequested);
                sb.append(" parentHidden=");
                sb.append(w.isParentWindowHidden());
                Slog.v(TAG, sb.toString());
            } else {
                StringBuilder sb2 = new StringBuilder();
                sb2.append("  VIS: mViewVisibility=");
                sb2.append(w.mViewVisibility);
                sb2.append(" mRelayoutCalled=");
                sb2.append(w.mRelayoutCalled);
                sb2.append(" hidden=");
                sb2.append(w.mToken.isHidden());
                sb2.append(" hiddenRequested=");
                sb2.append(atoken != null && atoken.hiddenRequested);
                sb2.append(" parentHidden=");
                sb2.append(w.isParentWindowHidden());
                Slog.v(TAG, sb2.toString());
            }
        }
        if (gone && w.mHaveFrame && !w.mLayoutNeeded) {
            if ((!w.isConfigChanged() && !w.setReportResizeHints()) || w.isGoneForLayoutLw()) {
                return;
            }
            if ((w.mAttrs.privateFlags & 1024) == 0 && (!w.mHasSurface || w.mAppToken == null || !w.mAppToken.layoutConfigChanges)) {
                return;
            }
        }
        if (!w.mLayoutAttached) {
            if (displayContent.mTmpInitial) {
                w.mContentChanged = false;
            }
            if (w.mAttrs.type == 2023) {
                displayContent.mTmpWindow = w;
            }
            w.mLayoutNeeded = false;
            w.prelayout();
            boolean firstLayout = true ^ w.isLaidOut();
            displayContent.mService.mPolicy.layoutWindowLw(w, null, displayContent.mDisplayFrames);
            w.mLayoutSeq = displayContent.mLayoutSeq;
            if (firstLayout) {
                w.updateLastInsetValues();
            }
            if (w.mAppToken != null) {
                w.mAppToken.layoutLetterbox(w);
            }
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.v(TAG, "  LAYOUT: mFrame=" + w.mFrame + " mContainingFrame=" + w.mContainingFrame + " mDisplayFrame=" + w.mDisplayFrame);
            }
        }
    }

    public static /* synthetic */ void lambda$new$5(DisplayContent displayContent, WindowState w) {
        if (w.mLayoutAttached) {
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.v(TAG, "2ND PASS " + w + " mHaveFrame=" + w.mHaveFrame + " mViewVisibility=" + w.mViewVisibility + " mRelayoutCalled=" + w.mRelayoutCalled);
            }
            if (displayContent.mTmpWindow != null && displayContent.mService.mPolicy.canBeHiddenByKeyguardLw(w)) {
                return;
            }
            if ((w.mViewVisibility != 8 && w.mRelayoutCalled) || !w.mHaveFrame || w.mLayoutNeeded) {
                if (displayContent.mTmpInitial) {
                    w.mContentChanged = false;
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                displayContent.mService.mPolicy.layoutWindowLw(w, w.getParentWindow(), displayContent.mDisplayFrames);
                w.mLayoutSeq = displayContent.mLayoutSeq;
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, " LAYOUT: mFrame=" + w.mFrame + " mContainingFrame=" + w.mContainingFrame + " mDisplayFrame=" + w.mDisplayFrame);
                }
            }
        } else if (w.mAttrs.type == 2023) {
            displayContent.mTmpWindow = displayContent.mTmpWindow2;
        }
    }

    public static /* synthetic */ boolean lambda$new$6(DisplayContent displayContent, WindowState w) {
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD && displayContent.mUpdateImeTarget) {
            Slog.i(TAG, "Checking window @" + w + " fl=0x" + Integer.toHexString(w.mAttrs.flags));
        }
        return w.canBeImeTarget();
    }

    public static /* synthetic */ void lambda$new$8(DisplayContent displayContent, WindowState w) {
        WindowSurfacePlacer surfacePlacer = displayContent.mService.mWindowPlacerLocked;
        boolean obscuredChanged = w.mObscured != displayContent.mTmpApplySurfaceChangesTransactionState.obscured;
        RootWindowContainer root = displayContent.mService.mRoot;
        boolean someoneLosingFocus = !displayContent.mService.mLosingFocus.isEmpty();
        w.mObscured = displayContent.mTmpApplySurfaceChangesTransactionState.obscured;
        if (!displayContent.mTmpApplySurfaceChangesTransactionState.obscured) {
            boolean isDisplayed = w.isDisplayedLw();
            if (isDisplayed && w.isObscuringDisplay()) {
                root.mObscuringWindow = w;
                displayContent.mTmpApplySurfaceChangesTransactionState.obscured = true;
            }
            displayContent.mTmpApplySurfaceChangesTransactionState.displayHasContent |= root.handleNotObscuredLocked(w, displayContent.mTmpApplySurfaceChangesTransactionState.obscured, displayContent.mTmpApplySurfaceChangesTransactionState.syswin);
            if (w.mHasSurface && isDisplayed) {
                int type = w.mAttrs.type;
                if (type == 2008 || type == 2010 || (w.mAttrs.privateFlags & 1024) != 0) {
                    displayContent.mTmpApplySurfaceChangesTransactionState.syswin = true;
                }
                if (displayContent.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate == 0.0f && w.mAttrs.preferredRefreshRate != 0.0f) {
                    displayContent.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate = w.mAttrs.preferredRefreshRate;
                }
                if (displayContent.mTmpApplySurfaceChangesTransactionState.preferredModeId == 0 && w.mAttrs.preferredDisplayModeId != 0) {
                    displayContent.mTmpApplySurfaceChangesTransactionState.preferredModeId = w.mAttrs.preferredDisplayModeId;
                }
            }
        }
        if (displayContent.isDefaultDisplay && obscuredChanged && w.isVisibleLw() && displayContent.mWallpaperController.isWallpaperTarget(w)) {
            displayContent.mWallpaperController.updateWallpaperVisibility();
        }
        w.handleWindowMovedIfNeeded();
        WindowStateAnimator winAnimator = w.mWinAnimator;
        w.mContentChanged = false;
        if (w.mHasSurface) {
            boolean committed = winAnimator.commitFinishDrawingLocked();
            if (displayContent.isDefaultDisplay && committed) {
                if (w.mAttrs.type == 2023) {
                    displayContent.pendingLayoutChanges |= 1;
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats("dream and commitFinishDrawingLocked true", displayContent.pendingLayoutChanges);
                    }
                }
                if ((w.mAttrs.flags & 1048576) != 0) {
                    if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                        Slog.v(TAG, "First draw done in potential wallpaper target " + w);
                    }
                    root.mWallpaperMayChange = true;
                    displayContent.pendingLayoutChanges |= 4;
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats("wallpaper and commitFinishDrawingLocked true", displayContent.pendingLayoutChanges);
                    }
                }
            }
        }
        AppWindowToken atoken = w.mAppToken;
        if (atoken != null) {
            atoken.updateLetterboxSurface(w);
            boolean updateAllDrawn = atoken.updateDrawnWindowStates(w);
            if (updateAllDrawn && !displayContent.mTmpUpdateAllDrawn.contains(atoken)) {
                displayContent.mTmpUpdateAllDrawn.add(atoken);
            }
        }
        boolean updateAllDrawn2 = displayContent.isDefaultDisplay;
        if (updateAllDrawn2 && someoneLosingFocus && w == displayContent.mService.mCurrentFocus && w.isDisplayedLw()) {
            displayContent.mTmpApplySurfaceChangesTransactionState.focusDisplayed = true;
        }
        w.updateResizingWindowIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent(Display display, WindowManagerService service, WallpaperController wallpaperController, DisplayWindowController controller) {
        super(service);
        boolean z;
        this.mTaskStackContainers = new TaskStackContainers(this.mService);
        this.mHomeStackContainers = new TaskStackContainers(this.mService);
        this.mAboveAppWindowsContainers = new AboveAppWindowContainers("mAboveAppWindowsContainers", this.mService);
        this.mBelowAppWindowsContainers = new NonAppWindowContainers("mBelowAppWindowsContainers", this.mService);
        this.mImeWindowsContainers = new NonMagnifiableWindowContainers("mImeWindowsContainers", this.mService);
        this.mMiddleAppWindowsContainers = new AboveAppWindowContainers("mMiddleAppWindowsContainers", this.mService);
        this.mTokenMap = new HashMap<>();
        this.mInitialDisplayWidth = 0;
        this.mInitialDisplayHeight = 0;
        this.mInitialDisplayDensity = 0;
        this.mDisplayCutoutCache = new RotationCache<>(new RotationCache.RotationDependentComputation() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$fiC19lMy-d_-rvza7hhOSw6bOM8
            @Override // com.android.server.wm.utils.RotationCache.RotationDependentComputation
            public final Object compute(Object obj, int i) {
                WmDisplayCutout calculateDisplayCutoutForRotationUncached;
                calculateDisplayCutoutForRotationUncached = DisplayContent.this.calculateDisplayCutoutForRotationUncached((DisplayCutout) obj, i);
                return calculateDisplayCutoutForRotationUncached;
            }
        });
        this.mBaseDisplayWidth = 0;
        this.mBaseDisplayHeight = 0;
        this.mBaseDisplayDensity = 0;
        this.mDisplayInfo = new DisplayInfo();
        this.mDisplayMetrics = new DisplayMetrics();
        this.mRealDisplayMetrics = new DisplayMetrics();
        this.mTmpDisplayMetrics = new DisplayMetrics();
        this.mCompatDisplayMetrics = new DisplayMetrics();
        this.mRotation = 0;
        this.mLastOrientation = -1;
        this.mAltOrientation = false;
        this.mLastWindowForcedOrientation = -1;
        this.mLastKeyguardForcedOrientation = -1;
        this.mLastWallpaperVisible = false;
        this.mBaseDisplayRect = new Rect();
        this.mShouldOverrideDisplayConfiguration = true;
        this.mExitingTokens = new ArrayList<>();
        this.mTouchExcludeRegion = new Region();
        this.mTmpRect = new Rect();
        this.mTmpRect2 = new Rect();
        this.mTmpRectF = new RectF();
        this.mTmpMatrix = new Matrix();
        this.mTmpRegion = new Region();
        this.mTmpBounds = new Rect();
        this.mTapExcludedWindows = new ArrayList<>();
        this.mTapExcludeProvidingWindows = new ArraySet<>();
        this.mHaveBootMsg = false;
        this.mHaveApp = false;
        this.mHaveWallpaper = false;
        this.mHaveKeyguard = true;
        this.mHaveVendorWallpaper = false;
        this.mTmpUpdateAllDrawn = new LinkedList<>();
        this.mTmpTaskForResizePointSearchResult = new TaskForResizePointSearchResult();
        this.mTmpApplySurfaceChangesTransactionState = new ApplySurfaceChangesTransactionState();
        this.mRemovingDisplay = false;
        this.mDisplayReady = false;
        this.mSession = new SurfaceSession();
        this.mLayoutSeq = 0;
        this.mTmpFloats = new float[9];
        this.mUpdateWindowsForAnimator = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$0yxrqH9eGY2qTjH1u_BvaVrXCSA
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$0(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mUpdateWallpaperForAnimator = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$D0QJUvhaQkGgoMtOmjw5foY9F8M
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$1(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mScheduleToastTimeout = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$hRKjZwmneu0T85LNNY6_Zcs4gKM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$2(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mFindFocusedWindow = new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$7uZtakUXzuXqF_Qht5Uq7LUvubI
            public final boolean apply(Object obj) {
                return DisplayContent.lambda$new$3(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mPerformLayout = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$qT01Aq6xt_ZOs86A1yDQe-qmPFQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$4(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mPerformLayoutAttached = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$7voe_dEKk2BYMriCvPuvaznb9WQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$5(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mComputeImeTargetPredicate = new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$TPj3OjTsuIg5GTLb5nMmFqIghA4
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$new$6(DisplayContent.this, (WindowState) obj);
            }
        };
        this.mApplyPostLayoutPolicy = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$JibsaX4YnJd0ta_wiDDdSp-PjQk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                r0.mService.mPolicy.applyPostLayoutPolicyLw(r2, r2.mAttrs, ((WindowState) obj).getParentWindow(), DisplayContent.this.mService.mInputMethodTarget);
            }
        };
        this.mApplySurfaceChangesTransaction = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$qxt4izS31fb0LF2uo_OF9DMa7gc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$8(DisplayContent.this, (WindowState) obj);
            }
        };
        setController(controller);
        if (service.mRoot.getDisplayContent(display.getDisplayId()) != null) {
            throw new IllegalArgumentException("Display with ID=" + display.getDisplayId() + " already exists=" + service.mRoot.getDisplayContent(display.getDisplayId()) + " new=" + display);
        }
        this.mDisplay = display;
        this.mDisplayId = display.getDisplayId();
        this.mWallpaperController = wallpaperController;
        display.getDisplayInfo(this.mDisplayInfo);
        display.getMetrics(this.mDisplayMetrics);
        if (this.mDisplayId != 0) {
            z = false;
        } else {
            z = true;
        }
        this.isDefaultDisplay = z;
        this.mDisplayFrames = new DisplayFrames(this.mDisplayId, this.mDisplayInfo, calculateDisplayCutoutForRotation(this.mDisplayInfo.rotation));
        initializeDisplayBaseInfo();
        this.mDividerControllerLocked = new DockedStackDividerController(service, this);
        this.mPinnedStackControllerLocked = new PinnedStackController(service, this);
        this.mSurfaceSize = Math.max(this.mBaseDisplayHeight, this.mBaseDisplayWidth) * 2;
        SurfaceControl.Builder b = this.mService.makeSurfaceBuilder(this.mSession).setSize(this.mSurfaceSize, this.mSurfaceSize).setOpaque(true);
        this.mWindowingLayer = b.setName("Display Root").build();
        this.mOverlayLayer = b.setName("Display Overlays").build();
        getPendingTransaction().setLayer(this.mWindowingLayer, 0).setLayerStack(this.mWindowingLayer, this.mDisplayId).show(this.mWindowingLayer).setLayer(this.mOverlayLayer, 1).setLayerStack(this.mOverlayLayer, this.mDisplayId).show(this.mOverlayLayer);
        getPendingTransaction().apply();
        super.addChild((DisplayContent) this.mBelowAppWindowsContainers, (Comparator<DisplayContent>) null);
        super.addChild((DisplayContent) this.mHomeStackContainers, (Comparator<DisplayContent>) null);
        super.addChild((DisplayContent) this.mMiddleAppWindowsContainers, (Comparator<DisplayContent>) null);
        super.addChild((DisplayContent) this.mTaskStackContainers, (Comparator<DisplayContent>) null);
        super.addChild((DisplayContent) this.mAboveAppWindowsContainers, (Comparator<DisplayContent>) null);
        super.addChild((DisplayContent) this.mImeWindowsContainers, (Comparator<DisplayContent>) null);
        this.mService.mRoot.addChild((RootWindowContainer) this, (Comparator<RootWindowContainer>) null);
        this.mDisplayReady = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isReady() {
        return this.mService.mDisplayReady && this.mDisplayReady;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDisplayId() {
        return this.mDisplayId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowToken getWindowToken(IBinder binder) {
        return this.mTokenMap.get(binder);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken getAppWindowToken(IBinder binder) {
        WindowToken token = getWindowToken(binder);
        if (token == null) {
            return null;
        }
        return token.asAppWindowToken();
    }

    private void addWindowToken(IBinder binder, WindowToken token) {
        DisplayContent dc = this.mService.mRoot.getWindowTokenDisplay(token);
        if (dc != null) {
            throw new IllegalArgumentException("Can't map token=" + token + " to display=" + getName() + " already mapped to display=" + dc + " tokens=" + dc.mTokenMap);
        } else if (binder == null) {
            throw new IllegalArgumentException("Can't map token=" + token + " to display=" + getName() + " binder is null");
        } else if (token == null) {
            throw new IllegalArgumentException("Can't map null token to display=" + getName() + " binder=" + binder);
        } else {
            this.mTokenMap.put(binder, token);
            if (token.asAppWindowToken() == null) {
                int layer = this.mService.getXuiLayer(token.windowType);
                if (layer != 65535) {
                    if (layer == 0) {
                        this.mBelowAppWindowsContainers.addChild(token);
                        return;
                    } else if (layer == 2) {
                        this.mMiddleAppWindowsContainers.addChild(token);
                        return;
                    } else if (layer == 4) {
                        this.mAboveAppWindowsContainers.addChild(token);
                        return;
                    }
                }
                int i = token.windowType;
                if (i != 2050) {
                    switch (i) {
                        case 2011:
                        case 2012:
                            this.mImeWindowsContainers.addChild(token);
                            return;
                        case 2013:
                            this.mBelowAppWindowsContainers.addChild(token);
                            return;
                        default:
                            switch (i) {
                                case 2039:
                                case 2040:
                                case 2041:
                                    break;
                                default:
                                    this.mAboveAppWindowsContainers.addChild(token);
                                    return;
                            }
                    }
                }
                this.mMiddleAppWindowsContainers.addChild(token);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowToken removeWindowToken(IBinder binder) {
        WindowToken token = this.mTokenMap.remove(binder);
        if (token != null && token.asAppWindowToken() == null) {
            token.setExiting();
        }
        return token;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reParentWindowToken(WindowToken token) {
        DisplayContent prevDc = token.getDisplayContent();
        if (prevDc == this) {
            return;
        }
        if (prevDc != null && prevDc.mTokenMap.remove(token.token) != null && token.asAppWindowToken() == null) {
            token.getParent().removeChild(token);
        }
        addWindowToken(token.token, token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAppToken(IBinder binder) {
        WindowToken token = removeWindowToken(binder);
        if (token == null) {
            Slog.w(TAG, "removeAppToken: Attempted to remove non-existing token: " + binder);
            return;
        }
        AppWindowToken appToken = token.asAppWindowToken();
        if (appToken == null) {
            Slog.w(TAG, "Attempted to remove non-App token: " + binder + " token=" + token);
            return;
        }
        appToken.onRemovedFromDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Display getDisplay() {
        return this.mDisplay;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayInfo getDisplayInfo() {
        return this.mDisplayInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayMetrics getDisplayMetrics() {
        return this.mDisplayMetrics;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRotation() {
        return this.mRotation;
    }

    @VisibleForTesting
    void setRotation(int newRotation) {
        this.mRotation = newRotation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastOrientation() {
        return this.mLastOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastOrientation(int orientation) {
        this.mLastOrientation = orientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getAltOrientation() {
        return this.mAltOrientation;
    }

    void setAltOrientation(boolean altOrientation) {
        this.mAltOrientation = altOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastWindowForcedOrientation() {
        return this.mLastWindowForcedOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateRotationUnchecked() {
        return updateRotationUnchecked(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r3v1 */
    /* JADX WARN: Type inference failed for: r3v13 */
    /* JADX WARN: Type inference failed for: r3v14 */
    /* JADX WARN: Type inference failed for: r3v2, types: [int, boolean] */
    public boolean updateRotationUnchecked(boolean forceUpdate) {
        ScreenRotationAnimation screenRotationAnimation;
        ScreenRotationAnimation screenRotationAnimation2;
        ?? r3;
        final boolean oldAltOrientation;
        if (!forceUpdate) {
            if (this.mService.mDeferredRotationPauseCount > 0) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, rotation is paused.");
                }
                return false;
            }
            ScreenRotationAnimation screenRotationAnimation3 = this.mService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
            if (screenRotationAnimation3 != null && screenRotationAnimation3.isAnimating()) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, animation in progress.");
                }
                return false;
            } else if (this.mService.mDisplayFrozen) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, still finishing previous rotation");
                }
                return false;
            }
        }
        if (!this.mService.mDisplayEnabled) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Deferring rotation, display is not enabled.");
            }
            return false;
        }
        final int oldRotation = this.mRotation;
        int lastOrientation = this.mLastOrientation;
        boolean oldAltOrientation2 = this.mAltOrientation;
        final int rotation = this.mService.mPolicy.rotationForOrientationLw(lastOrientation, oldRotation, this.isDefaultDisplay);
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "Computed rotation=" + rotation + " for display id=" + this.mDisplayId + " based on lastOrientation=" + lastOrientation + " and oldRotation=" + oldRotation);
        }
        boolean mayRotateSeamlessly = this.mService.mPolicy.shouldRotateSeamlessly(oldRotation, rotation);
        if (mayRotateSeamlessly) {
            WindowState seamlessRotated = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$05CtqlkxQvjLanO8D5BmaCdILKQ
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean z;
                    z = ((WindowState) obj).mSeamlesslyRotated;
                    return z;
                }
            });
            if (seamlessRotated != null && !forceUpdate) {
                return false;
            }
            if (hasPinnedStack()) {
                mayRotateSeamlessly = false;
            }
            int i = 0;
            while (true) {
                if (i >= this.mService.mSessions.size()) {
                    break;
                } else if (!this.mService.mSessions.valueAt(i).hasAlertWindowSurfaces()) {
                    i++;
                } else {
                    mayRotateSeamlessly = false;
                    break;
                }
            }
        }
        boolean rotateSeamlessly = mayRotateSeamlessly;
        boolean altOrientation = !this.mService.mPolicy.rotationHasCompatibleMetricsLw(lastOrientation, rotation);
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            StringBuilder sb = new StringBuilder();
            sb.append("Display id=");
            sb.append(this.mDisplayId);
            sb.append(" selected orientation ");
            sb.append(lastOrientation);
            sb.append(", got rotation ");
            sb.append(rotation);
            sb.append(" which has ");
            sb.append(altOrientation ? "incompatible" : "compatible");
            sb.append(" metrics");
            Slog.v(TAG, sb.toString());
        }
        if (oldRotation == rotation && oldAltOrientation2 == altOrientation) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            StringBuilder sb2 = new StringBuilder();
            sb2.append("Display id=");
            sb2.append(this.mDisplayId);
            sb2.append(" rotation changed to ");
            sb2.append(rotation);
            sb2.append(altOrientation ? " (alt)" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb2.append(" from ");
            sb2.append(oldRotation);
            sb2.append(oldAltOrientation2 ? " (alt)" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb2.append(", lastOrientation=");
            sb2.append(lastOrientation);
            Slog.v(TAG, sb2.toString());
        }
        if (deltaRotation(rotation, oldRotation) != 2) {
            this.mService.mWaitingForConfig = true;
        }
        this.mRotation = rotation;
        this.mAltOrientation = altOrientation;
        if (this.isDefaultDisplay) {
            this.mService.mPolicy.setRotationLw(rotation);
        }
        this.mService.mWindowsFreezingScreen = 1;
        this.mService.mH.removeMessages(11);
        this.mService.mH.sendEmptyMessageDelayed(11, 2000L);
        setLayoutNeeded();
        int[] anim = new int[2];
        this.mService.mPolicy.selectRotationAnimationLw(anim);
        if (!rotateSeamlessly) {
            this.mService.startFreezingDisplayLocked(anim[0], anim[1], this);
            screenRotationAnimation = this.mService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
        } else {
            screenRotationAnimation = null;
            this.mService.startSeamlessRotation();
        }
        ScreenRotationAnimation screenRotationAnimation4 = screenRotationAnimation;
        updateDisplayAndOrientation(getConfiguration().uiMode);
        if (screenRotationAnimation4 == null || !screenRotationAnimation4.hasScreenshot()) {
            screenRotationAnimation2 = screenRotationAnimation4;
            r3 = 1;
            oldAltOrientation = rotateSeamlessly;
        } else {
            screenRotationAnimation2 = screenRotationAnimation4;
            boolean z = true;
            oldAltOrientation = rotateSeamlessly;
            r3 = z;
            if (screenRotationAnimation4.setRotation(getPendingTransaction(), rotation, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, this.mService.getTransitionAnimationScaleLocked(), this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight)) {
                this.mService.scheduleAnimationLocked();
                r3 = z;
            }
        }
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$YooAGhJSBcftNk21oP6nOxalKHI
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).forceSeamlesslyRotateIfAllowed(oldRotation, rotation);
            }
        }, (boolean) r3);
        if (oldAltOrientation) {
            seamlesslyRotate(getPendingTransaction(), oldRotation, rotation);
        }
        this.mService.mDisplayManagerInternal.performTraversal(getPendingTransaction());
        scheduleAnimation();
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$mKe0fxS63Jo2y7lFQaTOMepRJDc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$updateRotationUnchecked$11(DisplayContent.this, oldAltOrientation, (WindowState) obj);
            }
        }, (boolean) r3);
        if (oldAltOrientation) {
            this.mService.mH.removeMessages(54);
            this.mService.mH.sendEmptyMessageDelayed(54, 2000L);
        }
        int i2 = this.mService.mRotationWatchers.size() - r3;
        while (true) {
            int i3 = i2;
            if (i3 < 0) {
                break;
            }
            WindowManagerService.RotationWatcher rotationWatcher = this.mService.mRotationWatchers.get(i3);
            if (rotationWatcher.mDisplayId == this.mDisplayId) {
                try {
                    rotationWatcher.mWatcher.onRotationChanged(rotation);
                } catch (RemoteException e) {
                }
            }
            i2 = i3 - 1;
        }
        if (screenRotationAnimation2 == null && this.mService.mAccessibilityController != null && this.isDefaultDisplay) {
            this.mService.mAccessibilityController.onRotationChangedLocked(this);
        }
        return r3;
    }

    public static /* synthetic */ void lambda$updateRotationUnchecked$11(DisplayContent displayContent, boolean rotateSeamlessly, WindowState w) {
        if (w.mHasSurface && !rotateSeamlessly) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Set mOrientationChanging of " + w);
            }
            w.setOrientationChanging(true);
            displayContent.mService.mRoot.mOrientationChangeComplete = false;
            w.mLastFreezeDuration = 0;
        }
        w.mReportOrientationChanged = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void configureDisplayPolicy() {
        this.mService.mPolicy.setInitialDisplaySize(getDisplay(), this.mBaseDisplayWidth, this.mBaseDisplayHeight, this.mBaseDisplayDensity);
        this.mDisplayFrames.onDisplayInfoUpdated(this.mDisplayInfo, calculateDisplayCutoutForRotation(this.mDisplayInfo.rotation));
    }

    private DisplayInfo updateDisplayAndOrientation(int uiMode) {
        boolean z = true;
        if (this.mRotation != 1 && this.mRotation != 3) {
            z = false;
        }
        boolean rotated = z;
        int realdw = rotated ? this.mBaseDisplayHeight : this.mBaseDisplayWidth;
        int realdh = rotated ? this.mBaseDisplayWidth : this.mBaseDisplayHeight;
        int dw = realdw;
        int dh = realdh;
        if (this.mAltOrientation) {
            if (realdw > realdh) {
                int maxw = (int) (realdh / 1.3f);
                if (maxw < realdw) {
                    dw = maxw;
                }
            } else {
                int maxh = (int) (realdw / 1.3f);
                if (maxh < realdh) {
                    dh = maxh;
                }
            }
        }
        WmDisplayCutout wmDisplayCutout = calculateDisplayCutoutForRotation(this.mRotation);
        DisplayCutout displayCutout = wmDisplayCutout.getDisplayCutout();
        int i = dw;
        int i2 = dh;
        int appWidth = this.mService.mPolicy.getNonDecorDisplayWidth(i, i2, this.mRotation, uiMode, this.mDisplayId, displayCutout);
        int appHeight = this.mService.mPolicy.getNonDecorDisplayHeight(i, i2, this.mRotation, uiMode, this.mDisplayId, displayCutout);
        this.mDisplayInfo.rotation = this.mRotation;
        this.mDisplayInfo.logicalWidth = dw;
        this.mDisplayInfo.logicalHeight = dh;
        this.mDisplayInfo.logicalDensityDpi = this.mBaseDisplayDensity;
        this.mDisplayInfo.appWidth = appWidth;
        this.mDisplayInfo.appHeight = appHeight;
        if (this.isDefaultDisplay) {
            this.mDisplayInfo.getLogicalMetrics(this.mRealDisplayMetrics, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, (Configuration) null);
        }
        this.mDisplayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout;
        this.mDisplayInfo.getAppMetrics(this.mDisplayMetrics);
        if (this.mDisplayScalingDisabled) {
            this.mDisplayInfo.flags |= 1073741824;
        } else {
            this.mDisplayInfo.flags &= -1073741825;
        }
        DisplayInfo overrideDisplayInfo = this.mShouldOverrideDisplayConfiguration ? this.mDisplayInfo : null;
        this.mService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(this.mDisplayId, overrideDisplayInfo);
        this.mBaseDisplayRect.set(0, 0, dw, dh);
        if (this.isDefaultDisplay) {
            this.mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(this.mDisplayMetrics, this.mCompatDisplayMetrics);
        }
        updateBounds();
        return this.mDisplayInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WmDisplayCutout calculateDisplayCutoutForRotation(int rotation) {
        return this.mDisplayCutoutCache.getOrCompute(this.mInitialDisplayCutout, rotation);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WmDisplayCutout calculateDisplayCutoutForRotationUncached(DisplayCutout cutout, int rotation) {
        if (cutout == null || cutout == DisplayCutout.NO_CUTOUT) {
            return WmDisplayCutout.NO_CUTOUT;
        }
        if (rotation == 0) {
            return WmDisplayCutout.computeSafeInsets(cutout, this.mInitialDisplayWidth, this.mInitialDisplayHeight);
        }
        boolean rotated = true;
        if (rotation != 1 && rotation != 3) {
            rotated = false;
        }
        List<Rect> bounds = WmDisplayCutout.computeSafeInsets(cutout, this.mInitialDisplayWidth, this.mInitialDisplayHeight).getDisplayCutout().getBoundingRects();
        CoordinateTransforms.transformPhysicalToLogicalCoordinates(rotation, this.mInitialDisplayWidth, this.mInitialDisplayHeight, this.mTmpMatrix);
        Region region = Region.obtain();
        for (int i = 0; i < bounds.size(); i++) {
            Rect rect = bounds.get(i);
            RectF rectF = new RectF(bounds.get(i));
            this.mTmpMatrix.mapRect(rectF);
            rectF.round(rect);
            region.op(rect, Region.Op.UNION);
        }
        return WmDisplayCutout.computeSafeInsets(DisplayCutout.fromBounds(region), rotated ? this.mInitialDisplayHeight : this.mInitialDisplayWidth, rotated ? this.mInitialDisplayWidth : this.mInitialDisplayHeight);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:57:0x0153  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0158 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void computeScreenConfiguration(android.content.res.Configuration r22) {
        /*
            Method dump skipped, instructions count: 416
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayContent.computeScreenConfiguration(android.content.res.Configuration):void");
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, int dw, int dh, int displayId) {
        int unrotDh;
        int unrotDw;
        this.mTmpDisplayMetrics.setTo(this.mDisplayMetrics);
        DisplayMetrics tmpDm = this.mTmpDisplayMetrics;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDh = dh;
            unrotDw = dw;
        }
        int sw = reduceCompatConfigWidthSize(0, 0, uiMode, tmpDm, unrotDw, unrotDh, displayId);
        return reduceCompatConfigWidthSize(reduceCompatConfigWidthSize(reduceCompatConfigWidthSize(sw, 1, uiMode, tmpDm, unrotDh, unrotDw, displayId), 2, uiMode, tmpDm, unrotDw, unrotDh, displayId), 3, uiMode, tmpDm, unrotDh, unrotDw, displayId);
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode, DisplayMetrics dm, int dw, int dh, int displayId) {
        dm.noncompatWidthPixels = this.mService.mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayId, this.mDisplayInfo.displayCutout);
        dm.noncompatHeightPixels = this.mService.mPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayId, this.mDisplayInfo.displayCutout);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, (DisplayMetrics) null);
        int size = (int) (((dm.noncompatWidthPixels / scale) / dm.density) + 0.5f);
        if (curSize == 0 || size < curSize) {
            return size;
        }
        return curSize;
    }

    private void computeSizeRangesAndScreenLayout(DisplayInfo displayInfo, int displayId, boolean rotated, int uiMode, int dw, int dh, float density, Configuration outConfig) {
        int unrotDh;
        int unrotDw;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDh = dh;
            unrotDw = dw;
        }
        displayInfo.smallestNominalAppWidth = 1073741824;
        displayInfo.smallestNominalAppHeight = 1073741824;
        displayInfo.largestNominalAppWidth = 0;
        displayInfo.largestNominalAppHeight = 0;
        adjustDisplaySizeRanges(displayInfo, displayId, 0, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, displayId, 1, uiMode, unrotDh, unrotDw);
        adjustDisplaySizeRanges(displayInfo, displayId, 2, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, displayId, 3, uiMode, unrotDh, unrotDw);
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        int sl2 = reduceConfigLayout(reduceConfigLayout(reduceConfigLayout(reduceConfigLayout(sl, 0, density, unrotDw, unrotDh, uiMode, displayId), 1, density, unrotDh, unrotDw, uiMode, displayId), 2, density, unrotDw, unrotDh, uiMode, displayId), 3, density, unrotDh, unrotDw, uiMode, displayId);
        outConfig.smallestScreenWidthDp = (int) (displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl2;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh, int uiMode, int displayId) {
        int w = this.mService.mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayId, this.mDisplayInfo.displayCutout);
        int h = this.mService.mPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayId, this.mDisplayInfo.displayCutout);
        int longSize = w;
        int shortSize = h;
        if (longSize < shortSize) {
            longSize = shortSize;
            shortSize = longSize;
        }
        return Configuration.reduceScreenLayout(curLayout, (int) (longSize / density), (int) (shortSize / density));
    }

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int displayId, int rotation, int uiMode, int dw, int dh) {
        DisplayCutout displayCutout = calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        int width = this.mService.mPolicy.getConfigDisplayWidth(dw, dh, rotation, uiMode, displayId, displayCutout);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        int height = this.mService.mPolicy.getConfigDisplayHeight(dw, dh, rotation, uiMode, displayId, displayCutout);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DockedStackDividerController getDockedDividerController() {
        return this.mDividerControllerLocked;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PinnedStackController getPinnedStackController() {
        return this.mPinnedStackControllerLocked;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasAccess(int uid) {
        return this.mDisplay.hasAccess(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPrivate() {
        return (this.mDisplay.getFlags() & 4) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getHomeStack() {
        return this.mHomeStackContainers.getHomeStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getSplitScreenPrimaryStack() {
        TaskStack stack = this.mTaskStackContainers.getSplitScreenPrimaryStack();
        if (stack == null || !stack.isVisible()) {
            return null;
        }
        return stack;
    }

    boolean hasSplitScreenPrimaryStack() {
        return getSplitScreenPrimaryStack() != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getSplitScreenPrimaryStackIgnoringVisibility() {
        return this.mTaskStackContainers.getSplitScreenPrimaryStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getPinnedStack() {
        return this.mTaskStackContainers.getPinnedStack();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean hasPinnedStack() {
        return this.mTaskStackContainers.getPinnedStack() != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getTopStackInWindowingMode(int windowingMode) {
        return getStack(windowingMode, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getStack(int windowingMode, int activityType) {
        if (activityType == 2) {
            return this.mHomeStackContainers.getStack(windowingMode, activityType);
        }
        return this.mTaskStackContainers.getStack(windowingMode, activityType);
    }

    @VisibleForTesting
    TaskStack getTopStack() {
        ArrayList<Task> list = this.mTaskStackContainers.getVisibleTasks();
        if (list != null && list.size() > 0) {
            return this.mTaskStackContainers.getTopStack();
        }
        return this.mHomeStackContainers.getTopStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<Task> getVisibleTasks() {
        ArrayList<Task> list = new ArrayList<>();
        list.addAll(this.mTaskStackContainers.getVisibleTasks());
        list.addAll(this.mHomeStackContainers.getVisibleTasks());
        return list;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStackWindowingModeChanged(TaskStack stack) {
        if (stack.isActivityTypeHome()) {
            this.mHomeStackContainers.onStackWindowingModeChanged(stack);
        } else {
            this.mTaskStackContainers.onStackWindowingModeChanged(stack);
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        this.mService.reconfigureDisplayLocked(this);
        DockedStackDividerController dividerController = getDockedDividerController();
        if (dividerController != null) {
            getDockedDividerController().onConfigurationChanged();
        }
        PinnedStackController pinnedStackController = getPinnedStackController();
        if (pinnedStackController != null) {
            getPinnedStackController().onConfigurationChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateStackBoundsAfterConfigChange(List<TaskStack> changedStackList) {
        for (int i = this.mTaskStackContainers.getChildCount() - 1; i >= 0; i--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(i);
            if (stack.updateBoundsAfterConfigChange()) {
                changedStackList.add(stack);
            }
        }
        for (int i2 = this.mHomeStackContainers.getChildCount() - 1; i2 >= 0; i2--) {
            TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(i2);
            if (stack2.updateBoundsAfterConfigChange()) {
                changedStackList.add(stack2);
            }
        }
        if (!hasPinnedStack()) {
            this.mPinnedStackControllerLocked.onDisplayInfoChanged();
        }
    }

    @Override // com.android.server.wm.WindowContainer
    boolean fillsParent() {
        return true;
    }

    @Override // com.android.server.wm.WindowContainer
    boolean isVisible() {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onAppTransitionDone() {
        super.onAppTransitionDone();
        this.mService.mWindowsChanged = true;
    }

    private boolean skipTraverseChild(WindowContainer child) {
        if (child == this.mImeWindowsContainers && this.mService.mInputMethodTarget != null && !hasSplitScreenPrimaryStack()) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                DisplayChildWindowContainer child = (DisplayChildWindowContainer) this.mChildren.get(i);
                if (!skipTraverseChild(child) && child.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        } else {
            int count = this.mChildren.size();
            for (int i2 = 0; i2 < count; i2++) {
                DisplayChildWindowContainer child2 = (DisplayChildWindowContainer) this.mChildren.get(i2);
                if (!skipTraverseChild(child2) && child2.forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean forAllImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return this.mImeWindowsContainers.forAllWindows(callback, traverseTopToBottom);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public int getOrientation() {
        WindowManagerPolicy policy = this.mService.mPolicy;
        if (this.mService.mDisplayFrozen) {
            if (this.mLastWindowForcedOrientation != -1) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Display id=" + this.mDisplayId + " is frozen, return " + this.mLastWindowForcedOrientation);
                }
                return this.mLastWindowForcedOrientation;
            } else if (policy.isKeyguardLocked()) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Display id=" + this.mDisplayId + " is frozen while keyguard locked, return " + this.mLastOrientation);
                }
                return this.mLastOrientation;
            }
        } else {
            int orientation = this.mAboveAppWindowsContainers.getOrientation();
            if (orientation != -2) {
                return orientation;
            }
        }
        return this.mTaskStackContainers.getOrientation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateDisplayInfo() {
        updateBaseDisplayMetricsIfNeeded();
        this.mDisplay.getDisplayInfo(this.mDisplayInfo);
        this.mDisplay.getMetrics(this.mDisplayMetrics);
        int i = this.mTaskStackContainers.getChildCount();
        while (true) {
            i--;
            if (i < 0) {
                break;
            }
            ((TaskStack) this.mTaskStackContainers.getChildAt(i)).updateDisplayInfo(null);
        }
        for (int i2 = this.mHomeStackContainers.getChildCount() - 1; i2 >= 0; i2--) {
            ((TaskStack) this.mHomeStackContainers.getChildAt(i2)).updateDisplayInfo(null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initializeDisplayBaseInfo() {
        DisplayInfo newDisplayInfo;
        DisplayManagerInternal displayManagerInternal = this.mService.mDisplayManagerInternal;
        if (displayManagerInternal != null && (newDisplayInfo = displayManagerInternal.getDisplayInfo(this.mDisplayId)) != null) {
            this.mDisplayInfo.copyFrom(newDisplayInfo);
        }
        updateBaseDisplayMetrics(this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight, this.mDisplayInfo.logicalDensityDpi);
        this.mInitialDisplayWidth = this.mDisplayInfo.logicalWidth;
        this.mInitialDisplayHeight = this.mDisplayInfo.logicalHeight;
        this.mInitialDisplayDensity = this.mDisplayInfo.logicalDensityDpi;
        this.mInitialDisplayCutout = this.mDisplayInfo.displayCutout;
    }

    private void updateBaseDisplayMetricsIfNeeded() {
        this.mService.mDisplayManagerInternal.getNonOverrideDisplayInfo(this.mDisplayId, this.mDisplayInfo);
        int orientation = this.mDisplayInfo.rotation;
        boolean rotated = orientation == 1 || orientation == 3;
        int newWidth = rotated ? this.mDisplayInfo.logicalHeight : this.mDisplayInfo.logicalWidth;
        int newHeight = rotated ? this.mDisplayInfo.logicalWidth : this.mDisplayInfo.logicalHeight;
        int newDensity = this.mDisplayInfo.logicalDensityDpi;
        DisplayCutout newCutout = this.mDisplayInfo.displayCutout;
        boolean sizeChanged = (this.mInitialDisplayWidth == newWidth && this.mInitialDisplayHeight == newHeight) ? false : true;
        boolean displayMetricsChanged = (!sizeChanged && this.mInitialDisplayDensity == this.mDisplayInfo.logicalDensityDpi && Objects.equals(this.mInitialDisplayCutout, newCutout)) ? false : true;
        if (displayMetricsChanged) {
            boolean isDisplaySizeForced = (this.mBaseDisplayWidth == this.mInitialDisplayWidth && this.mBaseDisplayHeight == this.mInitialDisplayHeight) ? false : true;
            boolean isDisplayDensityForced = this.mBaseDisplayDensity != this.mInitialDisplayDensity;
            updateBaseDisplayMetrics(isDisplaySizeForced ? this.mBaseDisplayWidth : newWidth, isDisplaySizeForced ? this.mBaseDisplayHeight : newHeight, isDisplayDensityForced ? this.mBaseDisplayDensity : newDensity);
            this.mInitialDisplayWidth = newWidth;
            this.mInitialDisplayHeight = newHeight;
            this.mInitialDisplayDensity = newDensity;
            this.mInitialDisplayCutout = newCutout;
            this.mService.reconfigureDisplayLocked(this);
        }
        boolean isDisplayDensityForced2 = this.isDefaultDisplay;
        if (isDisplayDensityForced2 && sizeChanged) {
            WindowManagerService.H h = this.mService.mH;
            final ActivityManagerInternal activityManagerInternal = this.mService.mAmInternal;
            Objects.requireNonNull(activityManagerInternal);
            h.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$gXpm9iON84WxoeOerSeNctnL3M0
                @Override // java.lang.Runnable
                public final void run() {
                    activityManagerInternal.notifyDefaultDisplaySizeChanged();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setMaxUiWidth(int width) {
        if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
            Slog.v(TAG, "Setting max ui width:" + width + " on display:" + getDisplayId());
        }
        this.mMaxUiWidth = width;
        updateBaseDisplayMetrics(this.mBaseDisplayWidth, this.mBaseDisplayHeight, this.mBaseDisplayDensity);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBaseDisplayMetrics(int baseWidth, int baseHeight, int baseDensity) {
        this.mBaseDisplayWidth = baseWidth;
        this.mBaseDisplayHeight = baseHeight;
        this.mBaseDisplayDensity = baseDensity;
        if (this.mMaxUiWidth > 0 && this.mBaseDisplayWidth > this.mMaxUiWidth) {
            this.mBaseDisplayHeight = (this.mMaxUiWidth * this.mBaseDisplayHeight) / this.mBaseDisplayWidth;
            this.mBaseDisplayDensity = (this.mMaxUiWidth * this.mBaseDisplayDensity) / this.mBaseDisplayWidth;
            this.mBaseDisplayWidth = this.mMaxUiWidth;
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.v(TAG, "Applying config restraints:" + this.mBaseDisplayWidth + "x" + this.mBaseDisplayHeight + " at density:" + this.mBaseDisplayDensity + " on display:" + getDisplayId());
            }
        }
        this.mBaseDisplayRect.set(0, 0, this.mBaseDisplayWidth, this.mBaseDisplayHeight);
        updateBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStableRect(Rect out) {
        out.set(this.mDisplayFrames.mStable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack createStack(int stackId, boolean onTop, StackWindowController controller) {
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.d(TAG, "Create new stackId=" + stackId + " on displayId=" + this.mDisplayId);
        }
        TaskStack stack = new TaskStack(this.mService, stackId, controller);
        if (stack.isActivityTypeHome()) {
            this.mHomeStackContainers.addStackToDisplay(stack, onTop);
        } else {
            this.mTaskStackContainers.addStackToDisplay(stack, onTop);
        }
        return stack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveStackToDisplay(TaskStack stack, boolean onTop) {
        DisplayContent prevDc = stack.getDisplayContent();
        if (prevDc == null) {
            throw new IllegalStateException("Trying to move stackId=" + stack.mStackId + " which is not currently attached to any display");
        } else if (prevDc.getDisplayId() == this.mDisplayId) {
            throw new IllegalArgumentException("Trying to move stackId=" + stack.mStackId + " to its current displayId=" + this.mDisplayId);
        } else if (stack.isActivityTypeHome()) {
            prevDc.mHomeStackContainers.removeChild(stack);
            this.mHomeStackContainers.addStackToDisplay(stack, onTop);
        } else {
            prevDc.mTaskStackContainers.removeChild(stack);
            this.mTaskStackContainers.addStackToDisplay(stack, onTop);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void addChild(DisplayChildWindowContainer child, Comparator<DisplayChildWindowContainer> comparator) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void addChild(DisplayChildWindowContainer child, int index) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(DisplayChildWindowContainer child) {
        if (this.mRemovingDisplay) {
            super.removeChild((DisplayContent) child);
            return;
        }
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void positionChildAt(int position, DisplayChildWindowContainer child, boolean includingParents) {
        getParent().positionChildAt(position, this, includingParents);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionStackAt(int position, TaskStack child) {
        if (child == null || !child.isActivityTypeHome()) {
            this.mTaskStackContainers.positionChildAt(position, child, false);
        } else {
            this.mHomeStackContainers.positionChildAt(position, child, false);
        }
        layoutAndAssignWindowLayersIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int taskIdFromPoint(int x, int y) {
        int stackNdx = this.mTaskStackContainers.getChildCount();
        while (true) {
            stackNdx--;
            if (stackNdx < 0) {
                for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
                    TaskStack stack = (TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2);
                    int taskId = stack.taskIdFromPoint(x, y);
                    if (taskId != -1) {
                        return taskId;
                    }
                }
                return -1;
            }
            TaskStack stack2 = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            int taskId2 = stack2.taskIdFromPoint(x, y);
            if (taskId2 != -1) {
                return taskId2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task findTaskForResizePoint(int x, int y) {
        int delta = WindowManagerService.dipToPixel(30, this.mDisplayMetrics);
        this.mTmpTaskForResizePointSearchResult.reset();
        int stackNdx = this.mTaskStackContainers.getChildCount();
        while (true) {
            stackNdx--;
            if (stackNdx < 0) {
                for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
                    TaskStack stack = (TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2);
                    if (!stack.getWindowConfiguration().canResizeTask()) {
                        return null;
                    }
                    stack.findTaskForResizePoint(x, y, delta, this.mTmpTaskForResizePointSearchResult);
                    if (this.mTmpTaskForResizePointSearchResult.searchDone) {
                        return this.mTmpTaskForResizePointSearchResult.taskForResize;
                    }
                }
                return null;
            }
            TaskStack stack2 = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            if (!stack2.getWindowConfiguration().canResizeTask()) {
                return null;
            }
            stack2.findTaskForResizePoint(x, y, delta, this.mTmpTaskForResizePointSearchResult);
            if (this.mTmpTaskForResizePointSearchResult.searchDone) {
                return this.mTmpTaskForResizePointSearchResult.taskForResize;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTouchExcludeRegion(Task focusedTask) {
        if (focusedTask == null) {
            this.mTouchExcludeRegion.setEmpty();
        } else {
            this.mTouchExcludeRegion.set(this.mBaseDisplayRect);
            int delta = WindowManagerService.dipToPixel(30, this.mDisplayMetrics);
            this.mTmpRect2.setEmpty();
            for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
                stack.setTouchExcludeRegion(focusedTask, delta, this.mTouchExcludeRegion, this.mDisplayFrames.mContent, this.mTmpRect2);
            }
            for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
                TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2);
                stack2.setTouchExcludeRegion(focusedTask, delta, this.mTouchExcludeRegion, this.mDisplayFrames.mContent, this.mTmpRect2);
            }
            if (!this.mTmpRect2.isEmpty()) {
                this.mTouchExcludeRegion.op(this.mTmpRect2, Region.Op.UNION);
            }
        }
        WindowState inputMethod = this.mService.mInputMethodWindow;
        if (inputMethod != null && inputMethod.isVisibleLw()) {
            inputMethod.getTouchableRegion(this.mTmpRegion);
            if (inputMethod.getDisplayId() == this.mDisplayId) {
                this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
            } else {
                inputMethod.getDisplayContent().setTouchExcludeRegion(null);
            }
        }
        for (int i = this.mTapExcludedWindows.size() - 1; i >= 0; i--) {
            WindowState win = this.mTapExcludedWindows.get(i);
            win.getTouchableRegion(this.mTmpRegion);
            this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
        }
        for (int i2 = this.mTapExcludeProvidingWindows.size() - 1; i2 >= 0; i2--) {
            WindowState win2 = this.mTapExcludeProvidingWindows.valueAt(i2);
            win2.amendTapExcludeRegion(this.mTouchExcludeRegion);
        }
        int i3 = this.mDisplayId;
        if (i3 == 0 && getSplitScreenPrimaryStack() != null) {
            this.mDividerControllerLocked.getTouchRegion(this.mTmpRect);
            this.mTmpRegion.set(this.mTmpRect);
            this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
        }
        if (this.mTapDetector != null) {
            this.mTapDetector.setTouchExcludeRegion(this.mTouchExcludeRegion);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void switchUser() {
        super.switchUser();
        this.mService.mWindowsChanged = true;
    }

    private void resetAnimationBackgroundAnimator() {
        for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            ((TaskStack) this.mTaskStackContainers.getChildAt(stackNdx)).resetAnimationBackgroundAnimator();
        }
        for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
            ((TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2)).resetAnimationBackgroundAnimator();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeIfPossible() {
        if (isAnimating()) {
            this.mDeferredRemoval = true;
        } else {
            removeImmediately();
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void removeImmediately() {
        this.mRemovingDisplay = true;
        try {
            super.removeImmediately();
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.v(TAG, "Removing display=" + this);
            }
            if (this.mService.canDispatchPointerEvents()) {
                if (this.mTapDetector != null) {
                    this.mService.unregisterPointerEventListener(this.mTapDetector);
                }
                if (this.mDisplayId == 0 && this.mService.mMousePositionTracker != null) {
                    this.mService.unregisterPointerEventListener(this.mService.mMousePositionTracker);
                }
            }
            this.mService.mAnimator.removeDisplayLocked(this.mDisplayId);
            this.mRemovingDisplay = false;
            this.mService.onDisplayRemoved(this.mDisplayId);
        } catch (Throwable th) {
            this.mRemovingDisplay = false;
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean checkCompleteDeferredRemoval() {
        boolean stillDeferringRemoval = super.checkCompleteDeferredRemoval();
        if (!stillDeferringRemoval && this.mDeferredRemoval) {
            removeImmediately();
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRemovalDeferred() {
        return this.mDeferredRemoval;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean animateForIme(float interpolatedValue, float animationTarget, float dividerAnimationTarget) {
        boolean updated = false;
        for (int i = this.mTaskStackContainers.getChildCount() - 1; i >= 0; i--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(i);
            if (stack != null && stack.isAdjustedForIme()) {
                if (interpolatedValue >= 1.0f && animationTarget == 0.0f && dividerAnimationTarget == 0.0f) {
                    stack.resetAdjustedForIme(true);
                    updated = true;
                } else {
                    this.mDividerControllerLocked.mLastAnimationProgress = this.mDividerControllerLocked.getInterpolatedAnimationValue(interpolatedValue);
                    this.mDividerControllerLocked.mLastDividerProgress = this.mDividerControllerLocked.getInterpolatedDividerValue(interpolatedValue);
                    updated |= stack.updateAdjustForIme(this.mDividerControllerLocked.mLastAnimationProgress, this.mDividerControllerLocked.mLastDividerProgress, false);
                }
                if (interpolatedValue >= 1.0f) {
                    stack.endImeAdjustAnimation();
                }
            }
        }
        for (int i2 = this.mHomeStackContainers.getChildCount() - 1; i2 >= 0; i2--) {
            TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(i2);
            if (stack2 != null && stack2.isAdjustedForIme()) {
                if (interpolatedValue >= 1.0f && animationTarget == 0.0f && dividerAnimationTarget == 0.0f) {
                    stack2.resetAdjustedForIme(true);
                    updated = true;
                } else {
                    this.mDividerControllerLocked.mLastAnimationProgress = this.mDividerControllerLocked.getInterpolatedAnimationValue(interpolatedValue);
                    this.mDividerControllerLocked.mLastDividerProgress = this.mDividerControllerLocked.getInterpolatedDividerValue(interpolatedValue);
                    updated |= stack2.updateAdjustForIme(this.mDividerControllerLocked.mLastAnimationProgress, this.mDividerControllerLocked.mLastDividerProgress, false);
                }
                if (interpolatedValue >= 1.0f) {
                    stack2.endImeAdjustAnimation();
                }
            }
        }
        return updated;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearImeAdjustAnimation() {
        boolean changed = false;
        for (int i = this.mTaskStackContainers.getChildCount() - 1; i >= 0; i--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(i);
            if (stack != null && stack.isAdjustedForIme()) {
                stack.resetAdjustedForIme(true);
                changed = true;
            }
        }
        for (int i2 = this.mHomeStackContainers.getChildCount() - 1; i2 >= 0; i2--) {
            TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(i2);
            if (stack2 != null && stack2.isAdjustedForIme()) {
                stack2.resetAdjustedForIme(true);
                changed = true;
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void beginImeAdjustAnimation() {
        for (int i = this.mTaskStackContainers.getChildCount() - 1; i >= 0; i--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(i);
            if (stack.isVisible() && stack.isAdjustedForIme()) {
                stack.beginImeAdjustAnimation();
            }
        }
        for (int i2 = this.mHomeStackContainers.getChildCount() - 1; i2 >= 0; i2--) {
            TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(i2);
            if (stack2.isVisible() && stack2.isAdjustedForIme()) {
                stack2.beginImeAdjustAnimation();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void adjustForImeIfNeeded() {
        WindowState imeWin = this.mService.mInputMethodWindow;
        boolean imeVisible = imeWin != null && imeWin.isVisibleLw() && imeWin.isDisplayedLw() && !this.mDividerControllerLocked.isImeHideRequested();
        boolean dockVisible = isStackVisible(3);
        TaskStack imeTargetStack = this.mService.getImeFocusStackLocked();
        int imeDockSide = (!dockVisible || imeTargetStack == null) ? -1 : imeTargetStack.getDockSide();
        boolean imeOnTop = imeDockSide == 2;
        int i = 4;
        boolean imeOnBottom = imeDockSide == 4;
        boolean dockMinimized = this.mDividerControllerLocked.isMinimizedDock();
        int imeHeight = this.mDisplayFrames.getInputMethodWindowVisibleHeight();
        boolean imeHeightChanged = imeVisible && imeHeight != this.mDividerControllerLocked.getImeHeightAdjustedFor();
        if (imeVisible && dockVisible && ((imeOnTop || imeOnBottom) && !dockMinimized)) {
            int i2 = this.mTaskStackContainers.getChildCount() - 1;
            while (i2 >= 0) {
                TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(i2);
                boolean isDockedOnBottom = stack.getDockSide() == i;
                if (stack.isVisible() && ((imeOnBottom || isDockedOnBottom) && stack.inSplitScreenWindowingMode())) {
                    stack.setAdjustedForIme(imeWin, imeOnBottom && imeHeightChanged);
                } else {
                    stack.resetAdjustedForIme(false);
                }
                i2--;
                i = 4;
            }
            for (int i3 = this.mHomeStackContainers.getChildCount() - 1; i3 >= 0; i3--) {
                TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(i3);
                boolean isDockedOnBottom2 = stack2.getDockSide() == 4;
                if (stack2.isVisible() && ((imeOnBottom || isDockedOnBottom2) && stack2.inSplitScreenWindowingMode())) {
                    stack2.setAdjustedForIme(imeWin, imeOnBottom && imeHeightChanged);
                } else {
                    stack2.resetAdjustedForIme(false);
                }
            }
            this.mDividerControllerLocked.setAdjustedForIme(imeOnBottom, true, true, imeWin, imeHeight);
        } else {
            for (int i4 = this.mTaskStackContainers.getChildCount() - 1; i4 >= 0; i4--) {
                TaskStack stack3 = (TaskStack) this.mTaskStackContainers.getChildAt(i4);
                stack3.resetAdjustedForIme(!dockVisible);
            }
            for (int i5 = this.mHomeStackContainers.getChildCount() - 1; i5 >= 0; i5--) {
                TaskStack stack4 = (TaskStack) this.mHomeStackContainers.getChildAt(i5);
                stack4.resetAdjustedForIme(!dockVisible);
            }
            this.mDividerControllerLocked.setAdjustedForIme(false, false, dockVisible, imeWin, imeHeight);
        }
        this.mPinnedStackControllerLocked.setAdjustedForIme(imeVisible, imeHeight);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLayerForAnimationBackground(WindowStateAnimator winAnimator) {
        WindowState visibleWallpaper = this.mBelowAppWindowsContainers.getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$Po0ivnfO2TfRfOth5ZIOFcmugs4
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$getLayerForAnimationBackground$12((WindowState) obj);
            }
        });
        if (visibleWallpaper != null) {
            return visibleWallpaper.mWinAnimator.mAnimLayer;
        }
        return winAnimator.mAnimLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getLayerForAnimationBackground$12(WindowState w) {
        return w.mIsWallpaper && w.isVisibleNow();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareFreezingTaskBounds() {
        for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            stack.prepareFreezingTaskBounds();
        }
        for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
            TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2);
            stack2.prepareFreezingTaskBounds();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        getBounds(this.mTmpRect, newRotation);
        int deltaRotation = deltaRotation(newRotation, oldRotation);
        createRotationMatrix(deltaRotation, this.mTmpRect.width(), this.mTmpRect.height(), this.mTmpMatrix);
        this.mTmpRectF.set(bounds);
        this.mTmpMatrix.mapRect(this.mTmpRectF);
        this.mTmpRectF.round(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        return delta < 0 ? delta + 4 : delta;
    }

    private static void createRotationMatrix(int rotation, float displayWidth, float displayHeight, Matrix outMatrix) {
        createRotationMatrix(rotation, 0.0f, 0.0f, displayWidth, displayHeight, outMatrix);
    }

    static void createRotationMatrix(int rotation, float rectLeft, float rectTop, float displayWidth, float displayHeight, Matrix outMatrix) {
        switch (rotation) {
            case 0:
                outMatrix.reset();
                return;
            case 1:
                outMatrix.setRotate(90.0f, 0.0f, 0.0f);
                outMatrix.postTranslate(displayWidth, 0.0f);
                outMatrix.postTranslate(-rectTop, rectLeft);
                return;
            case 2:
                outMatrix.reset();
                return;
            case 3:
                outMatrix.setRotate(270.0f, 0.0f, 0.0f);
                outMatrix.postTranslate(0.0f, displayHeight);
                outMatrix.postTranslate(rectTop, 0.0f);
                return;
            default:
                return;
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        proto.write(1120986464258L, this.mDisplayId);
        for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            stack.writeToProto(proto, 2246267895811L, trim);
        }
        this.mDividerControllerLocked.writeToProto(proto, 1146756268036L);
        this.mPinnedStackControllerLocked.writeToProto(proto, 1146756268037L);
        for (int i = this.mAboveAppWindowsContainers.getChildCount() - 1; i >= 0; i--) {
            WindowToken windowToken = (WindowToken) this.mAboveAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, 2246267895814L, trim);
        }
        for (int i2 = this.mBelowAppWindowsContainers.getChildCount() - 1; i2 >= 0; i2--) {
            WindowToken windowToken2 = (WindowToken) this.mBelowAppWindowsContainers.getChildAt(i2);
            windowToken2.writeToProto(proto, 2246267895815L, trim);
        }
        for (int i3 = this.mImeWindowsContainers.getChildCount() - 1; i3 >= 0; i3--) {
            WindowToken windowToken3 = (WindowToken) this.mImeWindowsContainers.getChildAt(i3);
            windowToken3.writeToProto(proto, 2246267895816L, trim);
        }
        proto.write(1120986464265L, this.mBaseDisplayDensity);
        this.mDisplayInfo.writeToProto(proto, 1146756268042L);
        proto.write(1120986464267L, this.mRotation);
        ScreenRotationAnimation screenRotationAnimation = this.mService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
        if (screenRotationAnimation != null) {
            screenRotationAnimation.writeToProto(proto, 1146756268044L);
        }
        this.mDisplayFrames.writeToProto(proto, 1146756268045L);
        proto.end(token);
    }

    @Override // com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix);
        pw.print("Display: mDisplayId=");
        pw.println(this.mDisplayId);
        String subPrefix = "  " + prefix;
        pw.print(subPrefix);
        pw.print("init=");
        pw.print(this.mInitialDisplayWidth);
        pw.print("x");
        pw.print(this.mInitialDisplayHeight);
        pw.print(" ");
        pw.print(this.mInitialDisplayDensity);
        pw.print("dpi");
        if (this.mInitialDisplayWidth != this.mBaseDisplayWidth || this.mInitialDisplayHeight != this.mBaseDisplayHeight || this.mInitialDisplayDensity != this.mBaseDisplayDensity) {
            pw.print(" base=");
            pw.print(this.mBaseDisplayWidth);
            pw.print("x");
            pw.print(this.mBaseDisplayHeight);
            pw.print(" ");
            pw.print(this.mBaseDisplayDensity);
            pw.print("dpi");
        }
        if (this.mDisplayScalingDisabled) {
            pw.println(" noscale");
        }
        pw.print(" cur=");
        pw.print(this.mDisplayInfo.logicalWidth);
        pw.print("x");
        pw.print(this.mDisplayInfo.logicalHeight);
        pw.print(" app=");
        pw.print(this.mDisplayInfo.appWidth);
        pw.print("x");
        pw.print(this.mDisplayInfo.appHeight);
        pw.print(" rng=");
        pw.print(this.mDisplayInfo.smallestNominalAppWidth);
        pw.print("x");
        pw.print(this.mDisplayInfo.smallestNominalAppHeight);
        pw.print("-");
        pw.print(this.mDisplayInfo.largestNominalAppWidth);
        pw.print("x");
        pw.println(this.mDisplayInfo.largestNominalAppHeight);
        pw.print(subPrefix + "deferred=" + this.mDeferredRemoval + " mLayoutNeeded=" + this.mLayoutNeeded);
        StringBuilder sb = new StringBuilder();
        sb.append(" mTouchExcludeRegion=");
        sb.append(this.mTouchExcludeRegion);
        pw.println(sb.toString());
        pw.println();
        pw.print(prefix);
        pw.print("mLayoutSeq=");
        pw.println(this.mLayoutSeq);
        pw.println();
        pw.println(prefix + "Application tokens in top down Z order:");
        for (int stackNdx = this.mTaskStackContainers.getChildCount() + (-1); stackNdx >= 0; stackNdx += -1) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            stack.dump(pw, prefix + "  ", dumpAll);
        }
        pw.println();
        if (!this.mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i = this.mExitingTokens.size() - 1; i >= 0; i--) {
                WindowToken token = this.mExitingTokens.get(i);
                pw.print("  Exiting #");
                pw.print(i);
                pw.print(' ');
                pw.print(token);
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            }
        }
        pw.println();
        TaskStack homeStack = getHomeStack();
        if (homeStack != null) {
            pw.println(prefix + "homeStack=" + homeStack.getName());
        }
        TaskStack pinnedStack = getPinnedStack();
        if (pinnedStack != null) {
            pw.println(prefix + "pinnedStack=" + pinnedStack.getName());
        }
        TaskStack splitScreenPrimaryStack = getSplitScreenPrimaryStack();
        if (splitScreenPrimaryStack != null) {
            pw.println(prefix + "splitScreenPrimaryStack=" + splitScreenPrimaryStack.getName());
        }
        pw.println();
        this.mDividerControllerLocked.dump(prefix, pw);
        pw.println();
        this.mPinnedStackControllerLocked.dump(prefix, pw);
        pw.println();
        this.mDisplayFrames.dump(prefix, pw);
    }

    public String toString() {
        return "Display " + this.mDisplayId + " info=" + this.mDisplayInfo + " stacks=" + this.mChildren;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return "Display " + this.mDisplayId + " name=\"" + this.mDisplayInfo.name + "\"";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isStackVisible(int windowingMode) {
        TaskStack stack = getTopStackInWindowingMode(windowingMode);
        return stack != null && stack.isVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getTouchableWinAtPointLocked(float xf, float yf) {
        final int x = (int) xf;
        final int y = (int) yf;
        WindowState touchedWin = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$_XfE1uZ9VUv6i0SxWUvqu69FNb4
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$getTouchableWinAtPointLocked$13(DisplayContent.this, x, y, (WindowState) obj);
            }
        });
        return touchedWin;
    }

    public static /* synthetic */ boolean lambda$getTouchableWinAtPointLocked$13(DisplayContent displayContent, int x, int y, WindowState w) {
        int flags = w.mAttrs.flags;
        if (w.isVisibleLw() && (flags & 16) == 0) {
            w.getVisibleBounds(displayContent.mTmpRect);
            if (displayContent.mTmpRect.contains(x, y)) {
                w.getTouchableRegion(displayContent.mTmpRegion);
                int touchFlags = flags & 40;
                return displayContent.mTmpRegion.contains(x, y) || touchFlags == 0;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canAddToastWindowForUid(final int uid) {
        WindowState focusedWindowForUid = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$2VlyMN8z2sOPqE9-yf-z3-peRMI
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$canAddToastWindowForUid$14(uid, (WindowState) obj);
            }
        });
        if (focusedWindowForUid != null) {
            return true;
        }
        WindowState win = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$JYsrGdifTPH6ASJDC3B9YWMD2pw
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$canAddToastWindowForUid$15(uid, (WindowState) obj);
            }
        });
        return win == null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$canAddToastWindowForUid$14(int uid, WindowState w) {
        return w.mOwnerUid == uid && w.isFocused();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$canAddToastWindowForUid$15(int uid, WindowState w) {
        return w.mAttrs.type == 2005 && w.mOwnerUid == uid && !w.mPermanentlyHidden && !w.mWindowRemovalAllowed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleToastWindowsTimeoutIfNeededLocked(WindowState oldFocus, WindowState newFocus) {
        if (oldFocus != null) {
            if (newFocus != null && newFocus.mOwnerUid == oldFocus.mOwnerUid) {
                return;
            }
            this.mTmpWindow = oldFocus;
            forAllWindows(this.mScheduleToastTimeout, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState findFocusedWindow() {
        this.mTmpWindow = null;
        forAllWindows(this.mFindFocusedWindow, true);
        if (this.mTmpWindow == null) {
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v(TAG, "findFocusedWindow: No focusable windows.");
            }
            return null;
        }
        return this.mTmpWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignWindowLayers(boolean setLayoutNeeded) {
        Trace.traceBegin(32L, "assignWindowLayers");
        assignChildLayers(getPendingTransaction());
        if (setLayoutNeeded) {
            setLayoutNeeded();
        }
        scheduleAnimation();
        Trace.traceEnd(32L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void layoutAndAssignWindowLayersIfNeeded() {
        this.mService.mWindowsChanged = true;
        setLayoutNeeded();
        if (!this.mService.updateFocusedWindowLocked(3, false)) {
            assignWindowLayers(false);
        }
        this.mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        this.mService.mWindowPlacerLocked.performSurfacePlacement();
        this.mService.mInputMonitor.updateInputWindowsLw(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean destroyLeakedSurfaces() {
        this.mTmpWindow = null;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$rF1ZhFUTWyZqcBK8Oea3g5-uNlM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$destroyLeakedSurfaces$16(DisplayContent.this, (WindowState) obj);
            }
        }, false);
        return this.mTmpWindow != null;
    }

    public static /* synthetic */ void lambda$destroyLeakedSurfaces$16(DisplayContent displayContent, WindowState w) {
        WindowStateAnimator wsa = w.mWinAnimator;
        if (wsa.mSurfaceController == null) {
            return;
        }
        if (!displayContent.mService.mSessions.contains(wsa.mSession)) {
            Slog.w(TAG, "LEAKED SURFACE (session doesn't exist): " + w + " surface=" + wsa.mSurfaceController + " token=" + w.mToken + " pid=" + w.mSession.mPid + " uid=" + w.mSession.mUid);
            wsa.destroySurface();
            displayContent.mService.mForceRemoves.add(w);
            displayContent.mTmpWindow = w;
        } else if (w.mAppToken != null && w.mAppToken.isClientHidden()) {
            Slog.w(TAG, "LEAKED SURFACE (app token hidden): " + w + " surface=" + wsa.mSurfaceController + " token=" + w.mAppToken);
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                WindowManagerService.logSurface(w, "LEAK DESTROY", false);
            }
            wsa.destroySurface();
            displayContent.mTmpWindow = w;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState computeImeTarget(boolean updateImeTarget) {
        String str;
        String str2;
        AppWindowToken token;
        WindowState betterTarget;
        if (this.mService.mInputMethodWindow == null) {
            if (updateImeTarget) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    Slog.w(TAG, "Moving IM target from " + this.mService.mInputMethodTarget + " to null since mInputMethodWindow is null");
                }
                setInputMethodTarget(null, this.mService.mInputMethodTargetWaitingAnim);
            }
            return null;
        }
        WindowState curTarget = this.mService.mInputMethodTarget;
        if (!canUpdateImeTarget()) {
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                Slog.w(TAG, "Defer updating IME target");
            }
            return curTarget;
        }
        this.mUpdateImeTarget = updateImeTarget;
        WindowState target = getWindow(this.mComputeImeTargetPredicate);
        if (target != null && target.mAttrs.type == 3 && (token = target.mAppToken) != null && (betterTarget = token.getImeTargetBelowWindow(target)) != null) {
            target = betterTarget;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD && updateImeTarget) {
            Slog.v(TAG, "Proposed new IME target: " + target);
        }
        if (curTarget != null && curTarget.isDisplayedLw() && curTarget.isClosing() && (target == null || target.isActivityTypeHome())) {
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                Slog.v(TAG, "New target is home while current target is closing, not changing");
            }
            return curTarget;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.v(TAG, "Desired input method target=" + target + " updateImeTarget=" + updateImeTarget);
        }
        if (target == null) {
            if (updateImeTarget) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Moving IM target from ");
                    sb.append(curTarget);
                    sb.append(" to null.");
                    if (WindowManagerDebugConfig.SHOW_STACK_CRAWLS) {
                        str2 = " Callers=" + Debug.getCallers(4);
                    } else {
                        str2 = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    }
                    sb.append(str2);
                    Slog.w(TAG, sb.toString());
                }
                setInputMethodTarget(null, this.mService.mInputMethodTargetWaitingAnim);
            }
            return null;
        }
        if (updateImeTarget) {
            AppWindowToken token2 = curTarget != null ? curTarget.mAppToken : null;
            if (token2 != null) {
                WindowState highestTarget = null;
                if (token2.isSelfAnimating()) {
                    highestTarget = token2.getHighestAnimLayerWindow(curTarget);
                }
                if (highestTarget != null) {
                    AppTransition appTransition = this.mService.mAppTransition;
                    if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                        Slog.v(TAG, appTransition + " " + highestTarget + " animating=" + highestTarget.mWinAnimator.isAnimationSet() + " layer=" + highestTarget.mWinAnimator.mAnimLayer + " new layer=" + target.mWinAnimator.mAnimLayer);
                    }
                    if (appTransition.isTransitionSet()) {
                        setInputMethodTarget(highestTarget, true);
                        return highestTarget;
                    } else if (highestTarget.mWinAnimator.isAnimationSet() && highestTarget.mWinAnimator.mAnimLayer > target.mWinAnimator.mAnimLayer) {
                        setInputMethodTarget(highestTarget, true);
                        return highestTarget;
                    }
                }
            }
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                StringBuilder sb2 = new StringBuilder();
                sb2.append("Moving IM target from ");
                sb2.append(curTarget);
                sb2.append(" to ");
                sb2.append(target);
                if (WindowManagerDebugConfig.SHOW_STACK_CRAWLS) {
                    str = " Callers=" + Debug.getCallers(4);
                } else {
                    str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                }
                sb2.append(str);
                Slog.w(TAG, sb2.toString());
            }
            setInputMethodTarget(target, false);
        }
        return target;
    }

    private void setInputMethodTarget(WindowState target, boolean targetWaitingAnim) {
        if (target == this.mService.mInputMethodTarget && this.mService.mInputMethodTargetWaitingAnim == targetWaitingAnim) {
            return;
        }
        this.mService.mInputMethodTarget = target;
        this.mService.mInputMethodTargetWaitingAnim = targetWaitingAnim;
        assignWindowLayers(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getNeedsMenu(final WindowState top, final WindowManagerPolicy.WindowState bottom) {
        if (top.mAttrs.needsMenuKey != 0) {
            return top.mAttrs.needsMenuKey == 1;
        }
        this.mTmpWindow = null;
        WindowState candidate = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$jJlRHCiYzTPceX3tUkQ_1wUz71E
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$getNeedsMenu$17(DisplayContent.this, top, bottom, (WindowState) obj);
            }
        });
        return candidate != null && candidate.mAttrs.needsMenuKey == 1;
    }

    public static /* synthetic */ boolean lambda$getNeedsMenu$17(DisplayContent displayContent, WindowState top, WindowManagerPolicy.WindowState bottom, WindowState w) {
        if (w == top) {
            displayContent.mTmpWindow = w;
        }
        if (displayContent.mTmpWindow == null) {
            return false;
        }
        return w.mAttrs.needsMenuKey != 0 || w == bottom;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLayoutNeeded() {
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.w(TAG, "setLayoutNeeded: callers=" + Debug.getCallers(3));
        }
        this.mLayoutNeeded = true;
    }

    private void clearLayoutNeeded() {
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.w(TAG, "clearLayoutNeeded: callers=" + Debug.getCallers(3));
        }
        this.mLayoutNeeded = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLayoutNeeded() {
        return this.mLayoutNeeded;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpTokens(PrintWriter pw, boolean dumpAll) {
        if (this.mTokenMap.isEmpty()) {
            return;
        }
        pw.println("  Display #" + this.mDisplayId);
        for (WindowToken token : this.mTokenMap.values()) {
            pw.print("  ");
            pw.print(token);
            if (dumpAll) {
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            } else {
                pw.println();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpWindowAnimators(final PrintWriter pw, final String subPrefix) {
        final int[] index = new int[1];
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$iSsga4uJnJzBuUddn6uWEUo6xO8
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$dumpWindowAnimators$18(pw, subPrefix, index, (WindowState) obj);
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dumpWindowAnimators$18(PrintWriter pw, String subPrefix, int[] index, WindowState w) {
        WindowStateAnimator wAnim = w.mWinAnimator;
        pw.println(subPrefix + "Window #" + index[0] + ": " + wAnim);
        index[0] = index[0] + 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startKeyguardExitOnNonAppWindows(final boolean onWallpaper, final boolean goingToShade) {
        final WindowManagerPolicy policy = this.mService.mPolicy;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$68_t-1mHyvN9aDP5Tt_BKUPoYT8
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$startKeyguardExitOnNonAppWindows$19(WindowManagerPolicy.this, onWallpaper, goingToShade, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startKeyguardExitOnNonAppWindows$19(WindowManagerPolicy policy, boolean onWallpaper, boolean goingToShade, WindowState w) {
        if (w.mAppToken == null && policy.canBeHiddenByKeyguardLw(w) && w.wouldBeVisibleIfPolicyIgnored() && !w.isVisible()) {
            w.startAnimation(policy.createHiddenByKeyguardExit(onWallpaper, goingToShade));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkWaitingForWindows() {
        boolean wallpaperEnabled;
        this.mHaveBootMsg = false;
        this.mHaveApp = false;
        this.mHaveWallpaper = false;
        this.mHaveKeyguard = true;
        this.mHaveVendorWallpaper = false;
        WindowState visibleWindow = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$BgTlvHbVclnASz-MrvERWxyMV-A
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$checkWaitingForWindows$20(DisplayContent.this, (WindowState) obj);
            }
        });
        if (visibleWindow != null) {
            return true;
        }
        if (!this.mService.mContext.getResources().getBoolean(17956971) || !this.mService.mContext.getResources().getBoolean(17956917) || this.mService.mOnlyCore) {
            wallpaperEnabled = false;
        } else {
            wallpaperEnabled = true;
        }
        if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || WindowManagerDebugConfig.DEBUG_BOOT) {
            Slog.i(TAG, "******** booted=" + this.mService.mSystemBooted + " msg=" + this.mService.mShowingBootMessages + " haveBoot=" + this.mHaveBootMsg + " haveApp=" + this.mHaveApp + " haveWall=" + this.mHaveWallpaper + " wallEnabled=" + wallpaperEnabled + " haveKeyguard=" + this.mHaveKeyguard);
        }
        if (!this.mService.mSystemBooted && !this.mHaveBootMsg) {
            return true;
        }
        if (!this.mService.mSystemBooted || ((this.mHaveApp || this.mHaveKeyguard) && ((!wallpaperEnabled || this.mHaveWallpaper) && (wallpaperEnabled || this.mHaveVendorWallpaper)))) {
            return false;
        }
        return true;
    }

    public static /* synthetic */ boolean lambda$checkWaitingForWindows$20(DisplayContent displayContent, WindowState w) {
        if (!w.isVisibleLw() || w.mObscured || w.isDrawnLw()) {
            if (w.isDrawnLw()) {
                if (w.mAttrs.type == 2021) {
                    displayContent.mHaveBootMsg = true;
                    return false;
                } else if (w.mAttrs.type == 2 || w.mAttrs.type == 6 || w.mAttrs.type == 12 || w.mAttrs.type == 5 || w.mAttrs.type == 4) {
                    displayContent.mHaveApp = true;
                    return false;
                } else if (w.mAttrs.type == 2013) {
                    displayContent.mHaveWallpaper = true;
                    return false;
                } else if (w.mAttrs.type == 2000) {
                    displayContent.mHaveKeyguard = displayContent.mService.mPolicy.isKeyguardDrawnLw();
                    return false;
                } else if (w.mAttrs.type == 2050) {
                    displayContent.mHaveVendorWallpaper = true;
                    return false;
                } else {
                    return false;
                }
            }
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWindowsForAnimator(WindowAnimator animator) {
        this.mTmpWindowAnimator = animator;
        forAllWindows(this.mUpdateWindowsForAnimator, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWallpaperForAnimator(WindowAnimator animator) {
        resetAnimationBackgroundAnimator();
        this.mTmpWindow = null;
        this.mTmpWindowAnimator = animator;
        forAllWindows(this.mUpdateWallpaperForAnimator, true);
        if (animator.mWindowDetachedWallpaper != this.mTmpWindow) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER) {
                Slog.v(TAG, "Detached wallpaper changed from " + animator.mWindowDetachedWallpaper + " to " + this.mTmpWindow);
            }
            animator.mWindowDetachedWallpaper = this.mTmpWindow;
            animator.mBulkUpdateParams |= 2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean inputMethodClientHasFocus(IInputMethodClient client) {
        WindowState imFocus = computeImeTarget(false);
        if (imFocus == null) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.i(TAG, "Desired input method target: " + imFocus);
            Slog.i(TAG, "Current focus: " + this.mService.mCurrentFocus);
            Slog.i(TAG, "Last focus: " + this.mService.mLastFocus);
        }
        IInputMethodClient imeClient = imFocus.mSession.mClient;
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.i(TAG, "IM target client: " + imeClient);
            if (imeClient != null) {
                Slog.i(TAG, "IM target client binder: " + imeClient.asBinder());
                Slog.i(TAG, "Requesting client binder: " + client.asBinder());
            }
        }
        return imeClient != null && imeClient.asBinder() == client.asBinder();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSecureWindowOnScreen() {
        WindowState win = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$5D_ifLpk7QwG-e9ZLZynNnDca9g
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.lambda$hasSecureWindowOnScreen$21((WindowState) obj);
            }
        });
        return win != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$hasSecureWindowOnScreen$21(WindowState w) {
        return w.isOnScreen() && (w.mAttrs.flags & 8192) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateSystemUiVisibility(final int visibility, final int globalDiff) {
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$1C_-u_mpQFfKL_O8K1VFzBgPg50
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$updateSystemUiVisibility$22(visibility, globalDiff, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$updateSystemUiVisibility$22(int visibility, int globalDiff, WindowState w) {
        try {
            int curValue = w.mSystemUiVisibility;
            int diff = (curValue ^ visibility) & globalDiff;
            int newValue = ((~diff) & curValue) | (visibility & diff);
            if (newValue != curValue) {
                w.mSeq++;
                w.mSystemUiVisibility = newValue;
            }
            if (newValue != curValue || w.mAttrs.hasSystemUiListeners) {
                w.mClient.dispatchSystemUiVisibilityChanged(w.mSeq, visibility, newValue, diff);
            }
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onWindowFreezeTimeout() {
        Slog.w(TAG, "Window freeze timeout expired.");
        this.mService.mWindowsFreezingScreen = 2;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$2HHBX1R6lnY5GedkE9LUBwsCPoE
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$onWindowFreezeTimeout$23(DisplayContent.this, (WindowState) obj);
            }
        }, true);
        this.mService.mWindowPlacerLocked.performSurfacePlacement();
    }

    public static /* synthetic */ void lambda$onWindowFreezeTimeout$23(DisplayContent displayContent, WindowState w) {
        if (!w.getOrientationChanging()) {
            return;
        }
        w.orientationChangeTimedOut();
        w.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - displayContent.mService.mDisplayFreezeTime);
        Slog.w(TAG, "Force clearing orientation change: " + w);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void waitForAllWindowsDrawn() {
        final WindowManagerPolicy policy = this.mService.mPolicy;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$oqhmXZMcpcvgI50swQTzosAcjac
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$waitForAllWindowsDrawn$24(DisplayContent.this, policy, (WindowState) obj);
            }
        }, true);
    }

    public static /* synthetic */ void lambda$waitForAllWindowsDrawn$24(DisplayContent displayContent, WindowManagerPolicy policy, WindowState w) {
        boolean keyguard = policy.isKeyguardHostWindow(w.mAttrs);
        if (w.isVisibleLw()) {
            if (w.mAppToken != null || keyguard) {
                w.mWinAnimator.mDrawState = 1;
                w.mLastContentInsets.set(-1, -1, -1, -1);
                displayContent.mService.mWaitingForDrawn.add(w);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean applySurfaceChangesTransaction(boolean recoveringMemory) {
        boolean z;
        int dw = this.mDisplayInfo.logicalWidth;
        int dh = this.mDisplayInfo.logicalHeight;
        WindowSurfacePlacer surfacePlacer = this.mService.mWindowPlacerLocked;
        this.mTmpUpdateAllDrawn.clear();
        int repeats = 0;
        while (true) {
            repeats++;
            if (repeats > 6) {
                Slog.w(TAG, "Animation repeat aborted after too many iterations");
                clearLayoutNeeded();
                break;
            }
            if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                surfacePlacer.debugLayoutRepeats("On entry to LockedInner", this.pendingLayoutChanges);
            }
            if (this.isDefaultDisplay && (this.pendingLayoutChanges & 4) != 0) {
                this.mWallpaperController.adjustWallpaperWindows(this);
            }
            if (this.isDefaultDisplay && (this.pendingLayoutChanges & 2) != 0) {
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, "Computing new config from layout");
                }
                if (this.mService.updateOrientationFromAppTokensLocked(this.mDisplayId)) {
                    setLayoutNeeded();
                    this.mService.mH.obtainMessage(18, Integer.valueOf(this.mDisplayId)).sendToTarget();
                }
            }
            if ((this.pendingLayoutChanges & 1) != 0) {
                setLayoutNeeded();
            }
            if (repeats < 4) {
                if (repeats != 1) {
                    z = false;
                } else {
                    z = true;
                }
                performLayout(z, false);
            } else {
                Slog.w(TAG, "Layout repeat skipped after too many iterations");
            }
            this.pendingLayoutChanges = 0;
            if (this.isDefaultDisplay) {
                this.mService.mPolicy.beginPostLayoutPolicyLw(dw, dh);
                forAllWindows(this.mApplyPostLayoutPolicy, true);
                this.pendingLayoutChanges |= this.mService.mPolicy.finishPostLayoutPolicyLw();
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after finishPostLayoutPolicyLw", this.pendingLayoutChanges);
                }
            }
            if (this.pendingLayoutChanges == 0) {
                break;
            }
        }
        this.mTmpApplySurfaceChangesTransactionState.reset();
        this.mTmpRecoveringMemory = recoveringMemory;
        forAllWindows(this.mApplySurfaceChangesTransaction, true);
        prepareSurfaces();
        this.mLastHasContent = this.mTmpApplySurfaceChangesTransactionState.displayHasContent;
        this.mService.mDisplayManagerInternal.setDisplayProperties(this.mDisplayId, this.mLastHasContent, this.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate, this.mTmpApplySurfaceChangesTransactionState.preferredModeId, true);
        boolean wallpaperVisible = this.mWallpaperController.isWallpaperVisible();
        if (wallpaperVisible != this.mLastWallpaperVisible) {
            this.mLastWallpaperVisible = wallpaperVisible;
            this.mService.mWallpaperVisibilityListeners.notifyWallpaperVisibilityChanged(this);
        }
        while (!this.mTmpUpdateAllDrawn.isEmpty()) {
            AppWindowToken atoken = this.mTmpUpdateAllDrawn.removeLast();
            atoken.updateAllDrawn();
        }
        return this.mTmpApplySurfaceChangesTransactionState.focusDisplayed;
    }

    private void updateBounds() {
        calculateBounds(this.mTmpBounds);
        setBounds(this.mTmpBounds);
    }

    private void calculateBounds(Rect out) {
        int orientation = this.mDisplayInfo.rotation;
        boolean rotated = true;
        if (orientation != 1 && orientation != 3) {
            rotated = false;
        }
        int physWidth = rotated ? this.mBaseDisplayHeight : this.mBaseDisplayWidth;
        int physHeight = rotated ? this.mBaseDisplayWidth : this.mBaseDisplayHeight;
        int width = this.mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = this.mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void getBounds(Rect out) {
        calculateBounds(out);
    }

    private void getBounds(Rect out, int orientation) {
        getBounds(out);
        int currentRotation = this.mDisplayInfo.rotation;
        int rotationDelta = deltaRotation(currentRotation, orientation);
        if (rotationDelta == 1 || rotationDelta == 3) {
            createRotationMatrix(rotationDelta, this.mBaseDisplayWidth, this.mBaseDisplayHeight, this.mTmpMatrix);
            this.mTmpRectF.set(out);
            this.mTmpMatrix.mapRect(this.mTmpRectF);
            this.mTmpRectF.round(out);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performLayout(boolean initial, boolean updateInputWindows) {
        if (!isLayoutNeeded()) {
            return;
        }
        clearLayoutNeeded();
        int dw = this.mDisplayInfo.logicalWidth;
        int dh = this.mDisplayInfo.logicalHeight;
        if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed=" + isLayoutNeeded() + " dw=" + dw + " dh=" + dh);
        }
        this.mDisplayFrames.onDisplayInfoUpdated(this.mDisplayInfo, calculateDisplayCutoutForRotation(this.mDisplayInfo.rotation));
        this.mDisplayFrames.mRotation = this.mRotation;
        this.mService.mPolicy.beginLayoutLw(this.mDisplayFrames, getConfiguration().uiMode);
        if (this.isDefaultDisplay) {
            this.mService.mSystemDecorLayer = this.mService.mPolicy.getSystemDecorLayerLw();
            this.mService.mScreenRect.set(0, 0, dw, dh);
        }
        int seq = this.mLayoutSeq + 1;
        if (seq < 0) {
            seq = 0;
        }
        this.mLayoutSeq = seq;
        this.mTmpWindow = null;
        this.mTmpInitial = initial;
        forAllWindows(this.mPerformLayout, true);
        this.mTmpWindow2 = this.mTmpWindow;
        this.mTmpWindow = null;
        forAllWindows(this.mPerformLayoutAttached, true);
        this.mService.mInputMonitor.layoutInputConsumers(dw, dh);
        this.mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            this.mService.mInputMonitor.updateInputWindowsLw(false);
        }
        this.mService.mH.sendEmptyMessage(41);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bitmap screenshotDisplayLocked(Bitmap.Config config) {
        if (!this.mService.mPolicy.isScreenOn()) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.i(TAG, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        int dw = this.mDisplayInfo.logicalWidth;
        int dh = this.mDisplayInfo.logicalHeight;
        if (dw <= 0 || dh <= 0) {
            return null;
        }
        boolean z = false;
        Rect frame = new Rect(0, 0, dw, dh);
        int rot = this.mDisplay.getRotation();
        if (rot == 1 || rot == 3) {
            rot = rot != 1 ? 1 : 3;
        }
        int rot2 = rot;
        convertCropForSurfaceFlinger(frame, rot2, dw, dh);
        ScreenRotationAnimation screenRotationAnimation = this.mService.mAnimator.getScreenRotationAnimationLocked(0);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            z = true;
        }
        boolean inRotation = z;
        if (WindowManagerDebugConfig.DEBUG_SCREENSHOT && inRotation) {
            Slog.v(TAG, "Taking screenshot while rotating");
        }
        Bitmap bitmap = SurfaceControl.screenshot(frame, dw, dh, 0, 1, inRotation, rot2);
        if (bitmap == null) {
            Slog.w(TAG, "Failed to take screenshot");
            return null;
        }
        Bitmap ret = bitmap.createAshmemBitmap(config);
        bitmap.recycle();
        return ret;
    }

    private static void convertCropForSurfaceFlinger(Rect crop, int rot, int dw, int dh) {
        if (rot == 1) {
            int tmp = crop.top;
            crop.top = dw - crop.right;
            crop.right = crop.bottom;
            crop.bottom = dw - crop.left;
            crop.left = tmp;
        } else if (rot == 2) {
            int tmp2 = crop.top;
            crop.top = dh - crop.bottom;
            crop.bottom = dh - tmp2;
            int tmp3 = crop.right;
            crop.right = dw - crop.left;
            crop.left = dw - tmp3;
        } else if (rot == 3) {
            int tmp4 = crop.top;
            crop.top = crop.left;
            crop.left = dh - crop.bottom;
            crop.bottom = crop.right;
            crop.right = dh - tmp4;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSeamlessRotationTimeout() {
        this.mTmpWindow = null;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$vn2WRFHoZv7DB3bbwsmraKDpl0I
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$onSeamlessRotationTimeout$25(DisplayContent.this, (WindowState) obj);
            }
        }, true);
        if (this.mTmpWindow != null) {
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public static /* synthetic */ void lambda$onSeamlessRotationTimeout$25(DisplayContent displayContent, WindowState w) {
        if (!w.mSeamlesslyRotated) {
            return;
        }
        displayContent.mTmpWindow = w;
        w.setDisplayLayoutNeeded();
        displayContent.mService.markForSeamlessRotation(w, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setExitingTokensHasVisible(boolean hasVisible) {
        for (int i = this.mExitingTokens.size() - 1; i >= 0; i--) {
            this.mExitingTokens.get(i).hasVisible = hasVisible;
        }
        this.mTaskStackContainers.setExitingTokensHasVisible(hasVisible);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeExistingTokensIfPossible() {
        for (int i = this.mExitingTokens.size() - 1; i >= 0; i--) {
            WindowToken token = this.mExitingTokens.get(i);
            if (!token.hasVisible) {
                this.mExitingTokens.remove(i);
            }
        }
        this.mTaskStackContainers.removeExistingAppTokensIfPossible();
    }

    @Override // com.android.server.wm.WindowContainer
    void onDescendantOverrideConfigurationChanged() {
        setLayoutNeeded();
        this.mService.requestTraversal();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToDisplay() {
        return this.mDisplayId == 0 ? !this.mService.mDisplayFrozen && this.mService.mDisplayEnabled && this.mService.mPolicy.isScreenOn() : this.mDisplayInfo.state == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToAnimate() {
        return okToDisplay() && (this.mDisplayId != 0 || this.mService.mPolicy.okToAnimate());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class TaskForResizePointSearchResult {
        boolean searchDone;
        Task taskForResize;

        TaskForResizePointSearchResult() {
        }

        void reset() {
            this.searchDone = false;
            this.taskForResize = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ApplySurfaceChangesTransactionState {
        boolean displayHasContent;
        boolean focusDisplayed;
        boolean obscured;
        int preferredModeId;
        float preferredRefreshRate;
        boolean syswin;

        private ApplySurfaceChangesTransactionState() {
        }

        void reset() {
            this.displayHasContent = false;
            this.obscured = false;
            this.syswin = false;
            this.focusDisplayed = false;
            this.preferredRefreshRate = 0.0f;
            this.preferredModeId = 0;
        }
    }

    /* loaded from: classes.dex */
    private static final class ScreenshotApplicationState {
        WindowState appWin;
        int maxLayer;
        int minLayer;
        boolean screenshotReady;

        private ScreenshotApplicationState() {
        }

        void reset(boolean screenshotReady) {
            this.appWin = null;
            this.maxLayer = 0;
            this.minLayer = 0;
            this.screenshotReady = screenshotReady;
            this.minLayer = screenshotReady ? 0 : Integer.MAX_VALUE;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class DisplayChildWindowContainer<E extends WindowContainer> extends WindowContainer<E> {
        DisplayChildWindowContainer(WindowManagerService service) {
            super(service);
        }

        @Override // com.android.server.wm.WindowContainer
        boolean fillsParent() {
            return true;
        }

        @Override // com.android.server.wm.WindowContainer
        boolean isVisible() {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class TaskStackContainers extends DisplayChildWindowContainer<TaskStack> {
        SurfaceControl mAppAnimationLayer;
        SurfaceControl mBoostedAppAnimationLayer;
        SurfaceControl mHomeAppAnimationLayer;
        private TaskStack mHomeStack;
        private TaskStack mPinnedStack;
        SurfaceControl mSplitScreenDividerAnchor;
        private TaskStack mSplitScreenPrimaryStack;

        TaskStackContainers(WindowManagerService service) {
            super(service);
            this.mAppAnimationLayer = null;
            this.mBoostedAppAnimationLayer = null;
            this.mHomeAppAnimationLayer = null;
            this.mSplitScreenDividerAnchor = null;
            this.mHomeStack = null;
            this.mPinnedStack = null;
            this.mSplitScreenPrimaryStack = null;
        }

        TaskStack getStack(int windowingMode, int activityType) {
            if (activityType == 2) {
                return this.mHomeStack;
            }
            if (windowingMode == 2) {
                return this.mPinnedStack;
            }
            if (windowingMode != 3) {
                for (int i = DisplayContent.this.mTaskStackContainers.getChildCount() - 1; i >= 0; i--) {
                    TaskStack stack = (TaskStack) DisplayContent.this.mTaskStackContainers.getChildAt(i);
                    if (activityType == 0 && windowingMode == stack.getWindowingMode()) {
                        return stack;
                    }
                    if (stack.isCompatible(windowingMode, activityType)) {
                        return stack;
                    }
                }
                return null;
            }
            return this.mSplitScreenPrimaryStack;
        }

        @VisibleForTesting
        TaskStack getTopStack() {
            List<Task> list = DisplayContent.this.mTaskStackContainers.getVisibleTasks();
            if (list == null || list.size() <= 0) {
                if (DisplayContent.this.mHomeStackContainers.getChildCount() > 0) {
                    return (TaskStack) DisplayContent.this.mHomeStackContainers.getChildAt(DisplayContent.this.mHomeStackContainers.getChildCount() - 1);
                }
                return null;
            } else if (DisplayContent.this.mTaskStackContainers.getChildCount() > 0) {
                return (TaskStack) DisplayContent.this.mTaskStackContainers.getChildAt(DisplayContent.this.mTaskStackContainers.getChildCount() - 1);
            } else {
                return null;
            }
        }

        TaskStack getHomeStack() {
            if (this.mHomeStack == null && DisplayContent.this.mDisplayId == 0) {
                Slog.e(DisplayContent.TAG, "getHomeStack: Returning null from this=" + this);
            }
            return this.mHomeStack;
        }

        TaskStack getPinnedStack() {
            return this.mPinnedStack;
        }

        TaskStack getSplitScreenPrimaryStack() {
            return this.mSplitScreenPrimaryStack;
        }

        ArrayList<Task> getVisibleTasks() {
            final ArrayList<Task> visibleTasks = new ArrayList<>();
            forAllTasks(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$TaskStackContainers$rQnI0Y8R9ptQ09cGHwbCHDiG2FY
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    DisplayContent.TaskStackContainers.lambda$getVisibleTasks$0(visibleTasks, (Task) obj);
                }
            });
            return visibleTasks;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getVisibleTasks$0(ArrayList visibleTasks, Task task) {
            if (task.isVisible()) {
                visibleTasks.add(task);
            }
        }

        void addStackToDisplay(TaskStack stack, boolean onTop) {
            addStackReferenceIfNeeded(stack);
            addChild(stack, onTop);
            stack.onDisplayChanged(DisplayContent.this);
        }

        void onStackWindowingModeChanged(TaskStack stack) {
            removeStackReferenceIfNeeded(stack);
            addStackReferenceIfNeeded(stack);
            if (stack == this.mPinnedStack && getTopStack() != stack) {
                positionChildAt(Integer.MAX_VALUE, stack, false);
            }
        }

        private void addStackReferenceIfNeeded(TaskStack stack) {
            if (stack.isActivityTypeHome()) {
                if (this.mHomeStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack=" + this.mHomeStack + " already exist on display=" + this + " stack=" + stack);
                }
                this.mHomeStack = stack;
            }
            int windowingMode = stack.getWindowingMode();
            if (windowingMode == 2) {
                if (this.mPinnedStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack=" + this.mPinnedStack + " already exist on display=" + this + " stack=" + stack);
                }
                this.mPinnedStack = stack;
            } else if (windowingMode == 3) {
                if (this.mSplitScreenPrimaryStack != null) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: split-screen-primary stack=" + this.mSplitScreenPrimaryStack + " already exist on display=" + this + " stack=" + stack);
                }
                this.mSplitScreenPrimaryStack = stack;
                DisplayContent.this.mDividerControllerLocked.notifyDockedStackExistsChanged(true);
            }
        }

        private void removeStackReferenceIfNeeded(TaskStack stack) {
            if (stack == this.mHomeStack) {
                this.mHomeStack = null;
            } else if (stack == this.mPinnedStack) {
                this.mPinnedStack = null;
            } else if (stack == this.mSplitScreenPrimaryStack) {
                this.mSplitScreenPrimaryStack = null;
                this.mService.setDockedStackCreateStateLocked(0, null);
                DisplayContent.this.mDividerControllerLocked.notifyDockedStackExistsChanged(false);
            }
        }

        private void addChild(TaskStack stack, boolean toTop) {
            int addIndex = findPositionForStack(toTop ? this.mChildren.size() : 0, stack, true);
            addChild((TaskStackContainers) stack, addIndex);
            DisplayContent.this.setLayoutNeeded();
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.wm.WindowContainer
        public void removeChild(TaskStack stack) {
            super.removeChild((TaskStackContainers) stack);
            removeStackReferenceIfNeeded(stack);
        }

        @Override // com.android.server.wm.WindowContainer
        boolean isOnTop() {
            return true;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        @Override // com.android.server.wm.WindowContainer
        public void positionChildAt(int position, TaskStack child, boolean includingParents) {
            if (child.getWindowConfiguration().isAlwaysOnTop() && position != Integer.MAX_VALUE) {
                Slog.w(DisplayContent.TAG, "Ignoring move of always-on-top stack=" + this + " to bottom");
                int currentPosition = this.mChildren.indexOf(child);
                super.positionChildAt(currentPosition, (int) child, false);
                return;
            }
            int targetPosition = findPositionForStack(position, child, false);
            super.positionChildAt(targetPosition, (int) child, includingParents);
            DisplayContent.this.setLayoutNeeded();
        }

        private int findPositionForStack(int requestedPosition, TaskStack stack, boolean adding) {
            boolean z = true;
            int topChildPosition = this.mChildren.size() - 1;
            boolean toTop = requestedPosition == Integer.MAX_VALUE;
            if (!adding ? requestedPosition < topChildPosition : requestedPosition < topChildPosition + 1) {
                z = false;
            }
            if (!(z | toTop) || stack.getWindowingMode() == 2 || !DisplayContent.this.hasPinnedStack()) {
                return requestedPosition;
            }
            TaskStack topStack = (TaskStack) this.mChildren.get(topChildPosition);
            if (topStack.getWindowingMode() != 2) {
                throw new IllegalStateException("Pinned stack isn't top stack??? " + this.mChildren);
            }
            int targetPosition = adding ? topChildPosition : topChildPosition - 1;
            return targetPosition;
        }

        @Override // com.android.server.wm.WindowContainer
        boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
            if (traverseTopToBottom) {
                if (super.forAllWindows(callback, traverseTopToBottom) || forAllExitingAppTokenWindows(callback, traverseTopToBottom)) {
                    return true;
                }
                return false;
            } else if (forAllExitingAppTokenWindows(callback, traverseTopToBottom) || super.forAllWindows(callback, traverseTopToBottom)) {
                return true;
            } else {
                return false;
            }
        }

        private boolean forAllExitingAppTokenWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
            if (traverseTopToBottom) {
                for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                    AppTokenList appTokens = ((TaskStack) this.mChildren.get(i)).mExitingAppTokens;
                    for (int j = appTokens.size() - 1; j >= 0; j--) {
                        if (appTokens.get(j).forAllWindowsUnchecked(callback, traverseTopToBottom)) {
                            return true;
                        }
                    }
                }
            } else {
                int count = this.mChildren.size();
                for (int i2 = 0; i2 < count; i2++) {
                    AppTokenList appTokens2 = ((TaskStack) this.mChildren.get(i2)).mExitingAppTokens;
                    int appTokensCount = appTokens2.size();
                    for (int j2 = 0; j2 < appTokensCount; j2++) {
                        if (appTokens2.get(j2).forAllWindowsUnchecked(callback, traverseTopToBottom)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        void setExitingTokensHasVisible(boolean hasVisible) {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                AppTokenList appTokens = ((TaskStack) this.mChildren.get(i)).mExitingAppTokens;
                for (int j = appTokens.size() - 1; j >= 0; j--) {
                    appTokens.get(j).hasVisible = hasVisible;
                }
            }
        }

        void removeExistingAppTokensIfPossible() {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                AppTokenList appTokens = ((TaskStack) this.mChildren.get(i)).mExitingAppTokens;
                for (int j = appTokens.size() - 1; j >= 0; j--) {
                    AppWindowToken token = appTokens.get(j);
                    if (!token.hasVisible && !this.mService.mClosingApps.contains(token) && (!token.mIsExiting || token.isEmpty())) {
                        cancelAnimation();
                        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE || WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT) {
                            Slog.v(DisplayContent.TAG, "performLayout: App token exiting now removed" + token);
                        }
                        token.removeIfPossible();
                    }
                }
            }
        }

        @Override // com.android.server.wm.WindowContainer
        int getOrientation() {
            int orientation;
            if (DisplayContent.this.isStackVisible(3) || DisplayContent.this.isStackVisible(5)) {
                if (this.mHomeStack == null || !this.mHomeStack.isVisible() || !DisplayContent.this.mDividerControllerLocked.isMinimizedDock() || ((DisplayContent.this.mDividerControllerLocked.isHomeStackResizable() && this.mHomeStack.matchParentBounds()) || (orientation = this.mHomeStack.getOrientation()) == -2)) {
                    return -1;
                }
                return orientation;
            }
            int orientation2 = super.getOrientation();
            boolean isCar = this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
            if (isCar) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(DisplayContent.TAG, "Forcing UNSPECIFIED orientation in car for display id=" + DisplayContent.this.mDisplayId + ". Ignoring " + orientation2);
                }
                return -1;
            } else if (orientation2 != -2 && orientation2 != 3) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(DisplayContent.TAG, "App is requesting an orientation, return " + orientation2 + " for display id=" + DisplayContent.this.mDisplayId);
                }
                return orientation2;
            } else {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(DisplayContent.TAG, "No app is requesting an orientation, return " + DisplayContent.this.mLastOrientation + " for display id=" + DisplayContent.this.mDisplayId);
                }
                return DisplayContent.this.mLastOrientation;
            }
        }

        @Override // com.android.server.wm.WindowContainer
        void assignChildLayers(SurfaceControl.Transaction t) {
            assignStackOrdering(t);
            for (int i = 0; i < this.mChildren.size(); i++) {
                TaskStack s = (TaskStack) this.mChildren.get(i);
                s.assignChildLayers(t);
            }
        }

        void assignStackOrdering(SurfaceControl.Transaction t) {
            int layerForAnimationLayer = 0;
            int layerForAnimationLayer2 = 0;
            int layerForHomeAnimationLayer = 0;
            int layer = 0;
            int layer2 = 0;
            while (layer2 <= 2) {
                int layerForBoostedAnimationLayer = layerForAnimationLayer2;
                int layerForBoostedAnimationLayer2 = layerForAnimationLayer;
                for (int layerForAnimationLayer3 = 0; layerForAnimationLayer3 < this.mChildren.size(); layerForAnimationLayer3++) {
                    TaskStack s = (TaskStack) this.mChildren.get(layerForAnimationLayer3);
                    if ((layer2 != 0 || s.isActivityTypeHome()) && ((layer2 != 1 || (!s.isActivityTypeHome() && !s.isAlwaysOnTop())) && (layer2 != 2 || s.isAlwaysOnTop()))) {
                        int layer3 = layer + 1;
                        s.assignLayer(t, layer);
                        if (s.inSplitScreenWindowingMode() && this.mSplitScreenDividerAnchor != null) {
                            t.setLayer(this.mSplitScreenDividerAnchor, layer3);
                            layer3++;
                        }
                        if ((s.isTaskAnimating() || s.isAppAnimating()) && layer2 != 2) {
                            layerForBoostedAnimationLayer2 = layer3;
                            layer3++;
                        }
                        if (layer2 == 2) {
                            layer = layer3;
                        } else {
                            layer = layer3 + 1;
                            layerForBoostedAnimationLayer = layer3;
                        }
                    }
                }
                if (layer2 == 0) {
                    layerForHomeAnimationLayer = layer;
                    layer++;
                }
                layer2++;
                layerForAnimationLayer = layerForBoostedAnimationLayer2;
                layerForAnimationLayer2 = layerForBoostedAnimationLayer;
            }
            if (this.mAppAnimationLayer != null) {
                t.setLayer(this.mAppAnimationLayer, layerForAnimationLayer);
            }
            if (this.mBoostedAppAnimationLayer != null) {
                t.setLayer(this.mBoostedAppAnimationLayer, layerForAnimationLayer2);
            }
            if (this.mHomeAppAnimationLayer != null) {
                t.setLayer(this.mHomeAppAnimationLayer, layerForHomeAnimationLayer);
            }
        }

        @Override // com.android.server.wm.WindowContainer
        SurfaceControl getAppAnimationLayer(@WindowContainer.AnimationLayer int animationLayer) {
            switch (animationLayer) {
                case 1:
                    return this.mBoostedAppAnimationLayer;
                case 2:
                    return this.mHomeAppAnimationLayer;
                default:
                    return this.mAppAnimationLayer;
            }
        }

        SurfaceControl getSplitScreenDividerAnchor() {
            return this.mSplitScreenDividerAnchor;
        }

        @Override // com.android.server.wm.WindowContainer
        void onParentSet() {
            super.onParentSet();
            if (getParent() != null) {
                this.mAppAnimationLayer = makeChildSurface(null).setName("animationLayer").build();
                this.mBoostedAppAnimationLayer = makeChildSurface(null).setName("boostedAnimationLayer").build();
                this.mHomeAppAnimationLayer = makeChildSurface(null).setName("homeAnimationLayer").build();
                this.mSplitScreenDividerAnchor = makeChildSurface(null).setName("splitScreenDividerAnchor").build();
                getPendingTransaction().show(this.mAppAnimationLayer).show(this.mBoostedAppAnimationLayer).show(this.mHomeAppAnimationLayer).show(this.mSplitScreenDividerAnchor);
                scheduleAnimation();
                return;
            }
            this.mAppAnimationLayer.destroy();
            this.mAppAnimationLayer = null;
            this.mBoostedAppAnimationLayer.destroy();
            this.mBoostedAppAnimationLayer = null;
            this.mHomeAppAnimationLayer.destroy();
            this.mHomeAppAnimationLayer = null;
            this.mSplitScreenDividerAnchor.destroy();
            this.mSplitScreenDividerAnchor = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AboveAppWindowContainers extends NonAppWindowContainers {
        AboveAppWindowContainers(String name, WindowManagerService service) {
            super(name, service);
        }

        @Override // com.android.server.wm.WindowContainer
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            SurfaceControl.Builder builder = super.makeChildSurface(child);
            if ((child instanceof WindowToken) && ((WindowToken) child).mRoundedCornerOverlay) {
                builder.setParent(null);
            }
            return builder;
        }

        @Override // com.android.server.wm.WindowContainer
        void assignChildLayers(SurfaceControl.Transaction t) {
            assignChildLayers(t, null);
        }

        void assignChildLayers(SurfaceControl.Transaction t, WindowContainer imeContainer) {
            boolean needAssignIme = (imeContainer == null || imeContainer.getSurfaceControl() == null) ? false : true;
            for (int j = 0; j < this.mChildren.size(); j++) {
                WindowToken wt = (WindowToken) this.mChildren.get(j);
                if (wt.windowType == 2034) {
                    wt.assignRelativeLayer(t, DisplayContent.this.mTaskStackContainers.getSplitScreenDividerAnchor(), 1);
                } else if (wt.mRoundedCornerOverlay) {
                    wt.assignLayer(t, 1073741826);
                } else {
                    wt.assignLayer(t, j);
                    wt.assignChildLayers(t);
                    int layer = this.mService.mPolicy.getWindowLayerFromTypeLw(wt.windowType, wt.mOwnerCanManageAppTokens);
                    if (needAssignIme && layer >= this.mService.mPolicy.getWindowLayerFromTypeLw(2012, true)) {
                        imeContainer.assignRelativeLayer(t, wt.getSurfaceControl(), -1);
                        needAssignIme = false;
                    }
                }
            }
            if (needAssignIme) {
                imeContainer.assignRelativeLayer(t, getSurfaceControl(), Integer.MAX_VALUE);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NonAppWindowContainers extends DisplayChildWindowContainer<WindowToken> {
        private final Dimmer mDimmer;
        private final Predicate<WindowState> mGetOrientingWindow;
        private final String mName;
        private final Rect mTmpDimBoundsRect;
        private final Comparator<WindowToken> mWindowComparator;

        public static /* synthetic */ int lambda$new$0(NonAppWindowContainers nonAppWindowContainers, WindowToken token1, WindowToken token2) {
            return nonAppWindowContainers.mService.mPolicy.getWindowLayerFromTypeLw(token1.windowType, token1.mOwnerCanManageAppTokens) < nonAppWindowContainers.mService.mPolicy.getWindowLayerFromTypeLw(token2.windowType, token2.mOwnerCanManageAppTokens) ? -1 : 1;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$new$1(WindowState w) {
            int req;
            return (!w.isVisibleLw() || !w.mPolicyVisibilityAfterAnim || (req = w.mAttrs.screenOrientation) == -1 || req == 3 || req == -2) ? false : true;
        }

        NonAppWindowContainers(String name, WindowManagerService service) {
            super(service);
            this.mWindowComparator = new Comparator() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$NonAppWindowContainers$nqCymC3xR9b3qaeohnnJJpSiajc
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return DisplayContent.NonAppWindowContainers.lambda$new$0(DisplayContent.NonAppWindowContainers.this, (WindowToken) obj, (WindowToken) obj2);
                }
            };
            this.mGetOrientingWindow = new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$NonAppWindowContainers$FI_O7m2qEDfIRZef3D32AxG-rcs
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return DisplayContent.NonAppWindowContainers.lambda$new$1((WindowState) obj);
                }
            };
            this.mDimmer = new Dimmer(this);
            this.mTmpDimBoundsRect = new Rect();
            this.mName = name;
        }

        void addChild(WindowToken token) {
            addChild((NonAppWindowContainers) token, (Comparator<NonAppWindowContainers>) this.mWindowComparator);
        }

        @Override // com.android.server.wm.WindowContainer
        int getOrientation() {
            WindowManagerPolicy policy = this.mService.mPolicy;
            WindowState win = getWindow(this.mGetOrientingWindow);
            if (win == null) {
                DisplayContent.this.mLastWindowForcedOrientation = -1;
                boolean isUnoccluding = this.mService.mAppTransition.getAppTransition() == 23 && this.mService.mUnknownAppVisibilityController.allResolved();
                if (policy.isKeyguardShowingAndNotOccluded() || isUnoccluding) {
                    return DisplayContent.this.mLastKeyguardForcedOrientation;
                }
                return -2;
            }
            int req = win.mAttrs.screenOrientation;
            if (policy.isKeyguardHostWindow(win.mAttrs)) {
                DisplayContent.this.mLastKeyguardForcedOrientation = req;
                if (this.mService.mKeyguardGoingAway) {
                    DisplayContent.this.mLastWindowForcedOrientation = -1;
                    return -2;
                }
            }
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(DisplayContent.TAG, win + " forcing orientation to " + req + " for display id=" + DisplayContent.this.mDisplayId);
            }
            return DisplayContent.this.mLastWindowForcedOrientation = req;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        @Override // com.android.server.wm.ConfigurationContainer
        public String getName() {
            return this.mName;
        }

        @Override // com.android.server.wm.WindowContainer
        Dimmer getDimmer() {
            return this.mDimmer;
        }

        @Override // com.android.server.wm.WindowContainer
        void prepareSurfaces() {
            this.mDimmer.resetDimStates();
            super.prepareSurfaces();
            getBounds(this.mTmpDimBoundsRect);
            if (this.mDimmer.updateDims(getPendingTransaction(), this.mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NonMagnifiableWindowContainers extends NonAppWindowContainers {
        NonMagnifiableWindowContainers(String name, WindowManagerService service) {
            super(name, service);
        }

        @Override // com.android.server.wm.WindowContainer
        void applyMagnificationSpec(SurfaceControl.Transaction t, MagnificationSpec spec) {
        }
    }

    SurfaceControl.Builder makeSurface(SurfaceSession s) {
        return this.mService.makeSurfaceBuilder(s).setParent(this.mWindowingLayer);
    }

    @Override // com.android.server.wm.WindowContainer
    SurfaceSession getSession() {
        return this.mSession;
    }

    @Override // com.android.server.wm.WindowContainer
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        SurfaceSession s = child != null ? child.getSession() : getSession();
        SurfaceControl.Builder b = this.mService.makeSurfaceBuilder(s);
        b.setSize(this.mSurfaceSize, this.mSurfaceSize);
        if (child == null) {
            return b;
        }
        return b.setName(child.getName()).setParent(this.mWindowingLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeOverlay() {
        return this.mService.makeSurfaceBuilder(this.mSession).setParent(this.mOverlayLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparentToOverlay(SurfaceControl.Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, this.mOverlayLayer.getHandle());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyMagnificationSpec(MagnificationSpec spec) {
        if (spec.scale != 1.0d) {
            this.mMagnificationSpec = spec;
        } else {
            this.mMagnificationSpec = null;
        }
        applyMagnificationSpec(getPendingTransaction(), spec);
        getPendingTransaction().apply();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reapplyMagnificationSpec() {
        if (this.mMagnificationSpec != null) {
            applyMagnificationSpec(getPendingTransaction(), this.mMagnificationSpec);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void onParentSet() {
    }

    @Override // com.android.server.wm.WindowContainer
    void assignChildLayers(SurfaceControl.Transaction t) {
        this.mBelowAppWindowsContainers.assignLayer(t, 0);
        this.mHomeStackContainers.assignLayer(t, 1);
        this.mMiddleAppWindowsContainers.assignLayer(t, 2);
        this.mTaskStackContainers.assignLayer(t, 3);
        this.mAboveAppWindowsContainers.assignLayer(t, 4);
        WindowState imeTarget = this.mService.mInputMethodTarget;
        boolean needAssignIme = true;
        if (!this.mService.isImeLayerExist() && imeTarget != null && !imeTarget.inSplitScreenWindowingMode() && !imeTarget.mToken.isAppAnimating() && imeTarget.getSurfaceControl() != null) {
            this.mImeWindowsContainers.assignRelativeLayer(t, imeTarget.getSurfaceControl(), 1);
            needAssignIme = false;
        }
        this.mBelowAppWindowsContainers.assignChildLayers(t);
        this.mMiddleAppWindowsContainers.assignChildLayers(t, null);
        this.mHomeStackContainers.assignChildLayers(t);
        this.mTaskStackContainers.assignChildLayers(t);
        if (!this.mService.isImeLayerExist()) {
            this.mAboveAppWindowsContainers.assignChildLayers(t, needAssignIme ? this.mImeWindowsContainers : null);
        } else {
            this.mAboveAppWindowsContainers.assignChildLayers(t, null);
        }
        this.mImeWindowsContainers.assignChildLayers(t);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignRelativeLayerForImeTargetChild(SurfaceControl.Transaction t, WindowContainer child) {
        child.assignRelativeLayer(t, this.mImeWindowsContainers.getSurfaceControl(), 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void prepareSurfaces() {
        ScreenRotationAnimation screenRotationAnimation = this.mService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            screenRotationAnimation.getEnterTransformation().getMatrix().getValues(this.mTmpFloats);
            this.mPendingTransaction.setMatrix(this.mWindowingLayer, this.mTmpFloats[0], this.mTmpFloats[3], this.mTmpFloats[1], this.mTmpFloats[4]);
            this.mPendingTransaction.setPosition(this.mWindowingLayer, this.mTmpFloats[2], this.mTmpFloats[5]);
            this.mPendingTransaction.setAlpha(this.mWindowingLayer, screenRotationAnimation.getEnterTransformation().getAlpha());
        }
        super.prepareSurfaces();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignStackOrdering() {
        this.mTaskStackContainers.assignStackOrdering(getPendingTransaction());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferUpdateImeTarget() {
        this.mDeferUpdateImeTargetCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueUpdateImeTarget() {
        if (this.mDeferUpdateImeTargetCount == 0) {
            return;
        }
        this.mDeferUpdateImeTargetCount--;
        if (this.mDeferUpdateImeTargetCount == 0) {
            computeImeTarget(true);
        }
    }

    private boolean canUpdateImeTarget() {
        return this.mDeferUpdateImeTargetCount == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getLastHasContent() {
        return this.mLastHasContent;
    }
}
