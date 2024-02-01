package com.android.server.wm;

import android.animation.AnimationHandler;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
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
import android.view.ISystemGestureExclusionListener;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AnimationThread;
import com.android.server.UiModeManagerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.android.server.wm.DisplayContent;
import com.android.server.wm.WindowContainer;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.utils.DisplayRotationUtil;
import com.android.server.wm.utils.RegionUtils;
import com.android.server.wm.utils.RotationCache;
import com.android.server.wm.utils.WmDisplayCutout;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class DisplayContent extends WindowContainer<DisplayChildWindowContainer> implements WindowManagerPolicy.DisplayContentInfo {
    static final int FORCE_SCALING_MODE_AUTO = 0;
    static final int FORCE_SCALING_MODE_DISABLED = 1;
    private static final String TAG = "WindowManager";
    @VisibleForTesting
    boolean isDefaultDisplay;
    private final AboveAppWindowContainers mAboveAppWindowsContainers;
    ActivityDisplay mAcitvityDisplay;
    final AppTransition mAppTransition;
    final AppTransitionController mAppTransitionController;
    private final Consumer<WindowState> mApplyPostLayoutPolicy;
    private final Consumer<WindowState> mApplySurfaceChangesTransaction;
    int mBaseDisplayDensity;
    int mBaseDisplayHeight;
    private Rect mBaseDisplayRect;
    int mBaseDisplayWidth;
    private final NonAppWindowContainers mBelowAppWindowsContainers;
    BoundsAnimationController mBoundsAnimationController;
    final ArraySet<AppWindowToken> mChangingApps;
    @VisibleForTesting
    final float mCloseToSquareMaxAspectRatio;
    final ArraySet<AppWindowToken> mClosingApps;
    private final DisplayMetrics mCompatDisplayMetrics;
    float mCompatibleScreenScale;
    private final Predicate<WindowState> mComputeImeTargetPredicate;
    WindowState mCurrentFocus;
    private int mDeferUpdateImeTargetCount;
    private boolean mDeferredRemoval;
    int mDeferredRotationPauseCount;
    private final Display mDisplay;
    private final RotationCache<DisplayCutout, WmDisplayCutout> mDisplayCutoutCache;
    DisplayFrames mDisplayFrames;
    private final int mDisplayId;
    private final DisplayInfo mDisplayInfo;
    private final DisplayMetrics mDisplayMetrics;
    private final DisplayPolicy mDisplayPolicy;
    private boolean mDisplayReady;
    private DisplayRotation mDisplayRotation;
    boolean mDisplayScalingDisabled;
    final DockedStackDividerController mDividerControllerLocked;
    final ArrayList<WindowToken> mExitingTokens;
    private final ToBooleanFunction<WindowState> mFindFocusedWindow;
    AppWindowToken mFocusedApp;
    private boolean mHaveApp;
    private boolean mHaveBootMsg;
    private boolean mHaveKeyguard;
    private boolean mHaveWallpaper;
    private final TaskStackContainers mHomeStackContainers;
    private boolean mIgnoreRotationForApps;
    private final NonAppWindowContainers mImeWindowsContainers;
    DisplayCutout mInitialDisplayCutout;
    int mInitialDisplayDensity;
    int mInitialDisplayHeight;
    int mInitialDisplayWidth;
    WindowState mInputMethodTarget;
    boolean mInputMethodTargetWaitingAnim;
    WindowState mInputMethodWindow;
    private InputMonitor mInputMonitor;
    private final InsetsStateController mInsetsStateController;
    private int mLastDispatchedSystemUiVisibility;
    WindowState mLastFocus;
    private boolean mLastHasContent;
    private int mLastKeyguardForcedOrientation;
    private int mLastOrientation;
    private int mLastStatusBarVisibility;
    private boolean mLastWallpaperVisible;
    private int mLastWindowForcedOrientation;
    private boolean mLayoutNeeded;
    int mLayoutSeq;
    private Point mLocationInParentWindow;
    ArrayList<WindowState> mLosingFocus;
    private MagnificationSpec mMagnificationSpec;
    private int mMaxUiWidth;
    private MetricsLogger mMetricsLogger;
    private final AboveAppWindowContainers mMiddleAppWindowsContainers;
    final List<IBinder> mNoAnimationNotifyOnTransitionFinished;
    final ArraySet<AppWindowToken> mOpeningApps;
    private SurfaceControl mOverlayLayer;
    private SurfaceControl mParentSurfaceControl;
    private WindowState mParentWindow;
    private final Consumer<WindowState> mPerformLayout;
    private final Consumer<WindowState> mPerformLayoutAttached;
    final PinnedStackController mPinnedStackControllerLocked;
    private final PointerEventDispatcher mPointerEventDispatcher;
    private InputWindowHandle mPortalWindowHandle;
    final DisplayMetrics mRealDisplayMetrics;
    private boolean mRemovingDisplay;
    private int mRotation;
    private DisplayRotationUtil mRotationUtil;
    private final Consumer<WindowState> mScheduleToastTimeout;
    private final SurfaceSession mSession;
    boolean mShouldOverrideDisplayConfiguration;
    boolean mSkipAppTransitionAnimation;
    private final Region mSystemGestureExclusion;
    private int mSystemGestureExclusionLimit;
    private final RemoteCallbackList<ISystemGestureExclusionListener> mSystemGestureExclusionListeners;
    private final Region mSystemGestureExclusionUnrestricted;
    private boolean mSystemGestureExclusionWasRestricted;
    @VisibleForTesting
    final TaskTapPointerEventListener mTapDetector;
    final ArraySet<WindowState> mTapExcludeProvidingWindows;
    final ArrayList<WindowState> mTapExcludedWindows;
    private final TaskStackContainers mTaskStackContainers;
    private final ApplySurfaceChangesTransactionState mTmpApplySurfaceChangesTransactionState;
    private final Rect mTmpBounds;
    private final Configuration mTmpConfiguration;
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
    private final HashMap<IBinder, WindowToken> mTokenMap;
    private Region mTouchExcludeRegion;
    final UnknownAppVisibilityController mUnknownAppVisibilityController;
    private boolean mUpdateImeTarget;
    private final Consumer<WindowState> mUpdateWallpaperForAnimator;
    private final Consumer<WindowState> mUpdateWindowsForAnimator;
    boolean mWaitingForConfig;
    WallpaperController mWallpaperController;
    boolean mWallpaperMayChange;
    final ArrayList<WindowState> mWinAddedSinceNullFocus;
    final ArrayList<WindowState> mWinRemovedSinceNullFocus;
    private final float mWindowCornerRadius;
    private SurfaceControl mWindowingLayer;
    int pendingLayoutChanges;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    @interface ForceScalingMode {
    }

    public /* synthetic */ void lambda$new$0$DisplayContent(WindowState w) {
        WindowStateAnimator winAnimator = w.mWinAnimator;
        AppWindowToken atoken = w.mAppToken;
        if (winAnimator.mDrawState == 3) {
            if ((atoken == null || atoken.canShowWindows()) && w.performShowLocked()) {
                this.pendingLayoutChanges |= 8;
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    this.mWmService.mWindowPlacerLocked.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5", this.pendingLayoutChanges);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$new$1(WindowState w) {
        AnimationAdapter anim;
        int color;
        TaskStack stack;
        WindowStateAnimator winAnimator = w.mWinAnimator;
        if (winAnimator.mSurfaceController == null || !winAnimator.hasSurface()) {
            return;
        }
        if (w.mAppToken != null) {
            anim = w.mAppToken.getAnimation();
        } else {
            anim = w.getAnimation();
        }
        if (anim != null && (color = anim.getBackgroundColor()) != 0 && (stack = w.getStack()) != null) {
            stack.setAnimationBackground(winAnimator, color);
        }
    }

    public /* synthetic */ void lambda$new$2$DisplayContent(WindowState w) {
        int lostFocusUid = this.mTmpWindow.mOwnerUid;
        Handler handler = this.mWmService.mH;
        if (w.mAttrs.type == 2005 && w.mOwnerUid == lostFocusUid && !handler.hasMessages(52, w)) {
            handler.sendMessageDelayed(handler.obtainMessage(52, w), w.mAttrs.hideTimeoutMilliseconds);
        }
    }

    public /* synthetic */ boolean lambda$new$3$DisplayContent(WindowState w) {
        AppWindowToken focusedApp = this.mFocusedApp;
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
                this.mTmpWindow = w;
                return true;
            } else if (!focusedApp.windowsAreFocusable()) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: focusedApp windows not focusable using new focus @ " + w);
                }
                this.mTmpWindow = w;
                return true;
            } else if (wtoken != null && w.mAttrs.type != 3 && focusedApp.compareTo((WindowContainer) wtoken) > 0) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: Reached focused app=" + focusedApp);
                }
                this.mTmpWindow = null;
                return true;
            } else {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.v(TAG, "findFocusedWindow: Found new focus @ " + w);
                }
                this.mTmpWindow = w;
                return true;
            }
        }
        return false;
    }

    public /* synthetic */ void lambda$new$4$DisplayContent(WindowState w) {
        boolean gone = (this.mTmpWindow != null && this.mWmService.mPolicy.canBeHiddenByKeyguardLw(w)) || w.isGoneForLayoutLw();
        if (WindowManagerDebugConfig.DEBUG_LAYOUT && !w.mLayoutAttached) {
            Slog.v(TAG, "1ST PASS " + w + ": gone=" + gone + " mHaveFrame=" + w.mHaveFrame + " mLayoutAttached=" + w.mLayoutAttached + " config reported=" + w.isLastConfigReportedToClient());
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
        if ((!gone || !w.mHaveFrame || w.mLayoutNeeded) && !w.mLayoutAttached) {
            if (this.mTmpInitial) {
                w.resetContentChanged();
            }
            if (w.mAttrs.type == 2023) {
                this.mTmpWindow = w;
            }
            w.mLayoutNeeded = false;
            w.prelayout();
            boolean firstLayout = !w.isLaidOut();
            getDisplayPolicy().layoutWindowLw(w, null, this.mDisplayFrames);
            w.mLayoutSeq = this.mLayoutSeq;
            if (firstLayout) {
                w.updateLastInsetValues();
            }
            if (w.mAppToken != null) {
                w.mAppToken.layoutLetterbox(w);
            }
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.v(TAG, "  LAYOUT: mFrame=" + w.getFrameLw() + " mContainingFrame=" + w.getContainingFrame() + " mDisplayFrame=" + w.getDisplayFrameLw());
            }
        }
    }

    public /* synthetic */ void lambda$new$5$DisplayContent(WindowState w) {
        if (w.mLayoutAttached) {
            if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                Slog.v(TAG, "2ND PASS " + w + " mHaveFrame=" + w.mHaveFrame + " mViewVisibility=" + w.mViewVisibility + " mRelayoutCalled=" + w.mRelayoutCalled);
            }
            if (this.mTmpWindow != null && this.mWmService.mPolicy.canBeHiddenByKeyguardLw(w)) {
                return;
            }
            if ((w.mViewVisibility != 8 && w.mRelayoutCalled) || !w.mHaveFrame || w.mLayoutNeeded) {
                if (this.mTmpInitial) {
                    w.resetContentChanged();
                }
                w.mLayoutNeeded = false;
                w.prelayout();
                getDisplayPolicy().layoutWindowLw(w, w.getParentWindow(), this.mDisplayFrames);
                w.mLayoutSeq = this.mLayoutSeq;
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, " LAYOUT: mFrame=" + w.getFrameLw() + " mContainingFrame=" + w.getContainingFrame() + " mDisplayFrame=" + w.getDisplayFrameLw());
                }
            }
        } else if (w.mAttrs.type == 2023) {
            this.mTmpWindow = this.mTmpWindow2;
        }
    }

    public /* synthetic */ boolean lambda$new$6$DisplayContent(WindowState w) {
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD && this.mUpdateImeTarget) {
            Slog.i(TAG, "Checking window @" + w + " fl=0x" + Integer.toHexString(w.mAttrs.flags));
        }
        return w.canBeImeTarget();
    }

    public /* synthetic */ void lambda$new$7$DisplayContent(WindowState w) {
        getDisplayPolicy().applyPostLayoutPolicyLw(w, w.mAttrs, w.getParentWindow(), this.mInputMethodTarget);
    }

    public /* synthetic */ void lambda$new$8$DisplayContent(WindowState w) {
        WindowSurfacePlacer surfacePlacer = this.mWmService.mWindowPlacerLocked;
        boolean obscuredChanged = w.mObscured != this.mTmpApplySurfaceChangesTransactionState.obscured;
        RootWindowContainer root = this.mWmService.mRoot;
        w.mObscured = this.mTmpApplySurfaceChangesTransactionState.obscured;
        if (!this.mTmpApplySurfaceChangesTransactionState.obscured) {
            boolean isDisplayed = w.isDisplayedLw();
            if (isDisplayed && w.isObscuringDisplay()) {
                root.mObscuringWindow = w;
                this.mTmpApplySurfaceChangesTransactionState.obscured = true;
            }
            this.mTmpApplySurfaceChangesTransactionState.displayHasContent |= root.handleNotObscuredLocked(w, this.mTmpApplySurfaceChangesTransactionState.obscured, this.mTmpApplySurfaceChangesTransactionState.syswin);
            if (w.mHasSurface && isDisplayed) {
                int type = w.mAttrs.type;
                if (type == 2008 || type == 2010 || (w.mAttrs.privateFlags & 1024) != 0) {
                    this.mTmpApplySurfaceChangesTransactionState.syswin = true;
                }
                if (this.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate == 0.0f && w.mAttrs.preferredRefreshRate != 0.0f) {
                    this.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate = w.mAttrs.preferredRefreshRate;
                }
                int preferredModeId = getDisplayPolicy().getRefreshRatePolicy().getPreferredModeId(w);
                if (this.mTmpApplySurfaceChangesTransactionState.preferredModeId == 0 && preferredModeId != 0) {
                    this.mTmpApplySurfaceChangesTransactionState.preferredModeId = preferredModeId;
                }
            }
        }
        if (obscuredChanged && w.isVisibleLw() && this.mWallpaperController.isWallpaperTarget(w)) {
            this.mWallpaperController.updateWallpaperVisibility();
        }
        w.handleWindowMovedIfNeeded();
        WindowStateAnimator winAnimator = w.mWinAnimator;
        w.resetContentChanged();
        if (w.mHasSurface) {
            boolean committed = winAnimator.commitFinishDrawingLocked();
            if (this.isDefaultDisplay && committed) {
                if (w.mAttrs.type == 2023) {
                    this.pendingLayoutChanges |= 1;
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats("dream and commitFinishDrawingLocked true", this.pendingLayoutChanges);
                    }
                }
                if ((w.mAttrs.flags & DumpState.DUMP_DEXOPT) != 0) {
                    if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                        Slog.v(TAG, "First draw done in potential wallpaper target " + w);
                    }
                    this.mWallpaperMayChange = true;
                    this.pendingLayoutChanges |= 4;
                    if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                        surfacePlacer.debugLayoutRepeats("wallpaper and commitFinishDrawingLocked true", this.pendingLayoutChanges);
                    }
                }
            }
        }
        AppWindowToken atoken = w.mAppToken;
        if (atoken != null) {
            atoken.updateLetterboxSurface(w);
            boolean updateAllDrawn = atoken.updateDrawnWindowStates(w);
            if (updateAllDrawn && !this.mTmpUpdateAllDrawn.contains(atoken)) {
                this.mTmpUpdateAllDrawn.add(atoken);
            }
        }
        if (!this.mLosingFocus.isEmpty() && w.isFocused() && w.isDisplayedLw()) {
            this.mWmService.mH.obtainMessage(3, this).sendToTarget();
        }
        w.updateResizingWindowIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent(Display display, WindowManagerService service, ActivityDisplay activityDisplay) {
        super(service);
        boolean z;
        this.mTaskStackContainers = new TaskStackContainers(this.mWmService);
        this.mHomeStackContainers = new TaskStackContainers(this.mWmService);
        this.mAboveAppWindowsContainers = new AboveAppWindowContainers("mAboveAppWindowsContainers", this.mWmService);
        this.mBelowAppWindowsContainers = new NonAppWindowContainers("mBelowAppWindowsContainers", this.mWmService);
        this.mImeWindowsContainers = new NonAppWindowContainers("mImeWindowsContainers", this.mWmService);
        this.mMiddleAppWindowsContainers = new AboveAppWindowContainers("mMiddleAppWindowsContainers", this.mWmService);
        this.mSkipAppTransitionAnimation = false;
        this.mOpeningApps = new ArraySet<>();
        this.mClosingApps = new ArraySet<>();
        this.mChangingApps = new ArraySet<>();
        this.mNoAnimationNotifyOnTransitionFinished = new ArrayList();
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
        this.mSystemGestureExclusionListeners = new RemoteCallbackList<>();
        this.mSystemGestureExclusion = new Region();
        this.mSystemGestureExclusionWasRestricted = false;
        this.mSystemGestureExclusionUnrestricted = new Region();
        this.mRealDisplayMetrics = new DisplayMetrics();
        this.mTmpDisplayMetrics = new DisplayMetrics();
        this.mCompatDisplayMetrics = new DisplayMetrics();
        this.mRotation = 0;
        this.mLastOrientation = -1;
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
        this.mTmpConfiguration = new Configuration();
        this.mTapExcludedWindows = new ArrayList<>();
        this.mTapExcludeProvidingWindows = new ArraySet<>();
        this.mHaveBootMsg = false;
        this.mHaveApp = false;
        this.mHaveWallpaper = false;
        this.mHaveKeyguard = true;
        this.mTmpUpdateAllDrawn = new LinkedList<>();
        this.mTmpTaskForResizePointSearchResult = new TaskForResizePointSearchResult();
        this.mTmpApplySurfaceChangesTransactionState = new ApplySurfaceChangesTransactionState();
        this.mRemovingDisplay = false;
        this.mDisplayReady = false;
        this.mWallpaperMayChange = false;
        this.mSession = new SurfaceSession();
        this.mCurrentFocus = null;
        this.mLastFocus = null;
        this.mLosingFocus = new ArrayList<>();
        this.mFocusedApp = null;
        this.mWinAddedSinceNullFocus = new ArrayList<>();
        this.mWinRemovedSinceNullFocus = new ArrayList<>();
        this.mLayoutSeq = 0;
        this.mTmpFloats = new float[9];
        this.mRotationUtil = new DisplayRotationUtil();
        this.mLocationInParentWindow = new Point();
        this.mLastStatusBarVisibility = 0;
        this.mLastDispatchedSystemUiVisibility = 0;
        this.mUpdateWindowsForAnimator = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$0yxrqH9eGY2qTjH1u_BvaVrXCSA
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$0$DisplayContent((WindowState) obj);
            }
        };
        this.mUpdateWallpaperForAnimator = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$GuCKVzKP141d6J0gfRAjKtuBJUU
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$new$1((WindowState) obj);
            }
        };
        this.mScheduleToastTimeout = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$hRKjZwmneu0T85LNNY6_Zcs4gKM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$2$DisplayContent((WindowState) obj);
            }
        };
        this.mFindFocusedWindow = new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$7uZtakUXzuXqF_Qht5Uq7LUvubI
            public final boolean apply(Object obj) {
                return DisplayContent.this.lambda$new$3$DisplayContent((WindowState) obj);
            }
        };
        this.mPerformLayout = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$qT01Aq6xt_ZOs86A1yDQe-qmPFQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$4$DisplayContent((WindowState) obj);
            }
        };
        this.mPerformLayoutAttached = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$7voe_dEKk2BYMriCvPuvaznb9WQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$5$DisplayContent((WindowState) obj);
            }
        };
        this.mComputeImeTargetPredicate = new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$TPj3OjTsuIg5GTLb5nMmFqIghA4
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.this.lambda$new$6$DisplayContent((WindowState) obj);
            }
        };
        this.mApplyPostLayoutPolicy = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$JibsaX4YnJd0ta_wiDDdSp-PjQk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$7$DisplayContent((WindowState) obj);
            }
        };
        this.mApplySurfaceChangesTransaction = new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$qxt4izS31fb0LF2uo_OF9DMa7gc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$new$8$DisplayContent((WindowState) obj);
            }
        };
        this.mAcitvityDisplay = activityDisplay;
        if (service.mRoot.getDisplayContent(display.getDisplayId()) != null) {
            throw new IllegalArgumentException("Display with ID=" + display.getDisplayId() + " already exists=" + service.mRoot.getDisplayContent(display.getDisplayId()) + " new=" + display);
        }
        this.mDisplay = display;
        this.mDisplayId = display.getDisplayId();
        this.mWallpaperController = new WallpaperController(this.mWmService, this);
        display.getDisplayInfo(this.mDisplayInfo);
        display.getMetrics(this.mDisplayMetrics);
        this.mSystemGestureExclusionLimit = (this.mWmService.mSystemGestureExclusionLimitDp * this.mDisplayMetrics.densityDpi) / 160;
        if (this.mDisplayId != 0) {
            z = false;
        } else {
            z = true;
        }
        this.isDefaultDisplay = z;
        int i = this.mDisplayId;
        DisplayInfo displayInfo = this.mDisplayInfo;
        this.mDisplayFrames = new DisplayFrames(i, displayInfo, calculateDisplayCutoutForRotation(displayInfo.rotation));
        initializeDisplayBaseInfo();
        this.mAppTransition = new AppTransition(service.mContext, service, this);
        this.mAppTransition.registerListenerLocked(service.mActivityManagerAppTransitionNotifier);
        this.mAppTransitionController = new AppTransitionController(service, this);
        this.mUnknownAppVisibilityController = new UnknownAppVisibilityController(service, this);
        AnimationHandler animationHandler = new AnimationHandler();
        this.mBoundsAnimationController = new BoundsAnimationController(service.mContext, this.mAppTransition, AnimationThread.getHandler(), animationHandler);
        InputChannel inputChannel = this.mWmService.mInputManager.monitorInput("PointerEventDispatcher" + this.mDisplayId, this.mDisplayId);
        this.mPointerEventDispatcher = new PointerEventDispatcher(inputChannel);
        this.mTapDetector = new TaskTapPointerEventListener(this.mWmService, this);
        registerPointerEventListener(this.mTapDetector);
        registerPointerEventListener(this.mWmService.mMousePositionTracker);
        if (this.mWmService.mAtmService.getRecentTasks() != null) {
            registerPointerEventListener(this.mWmService.mAtmService.getRecentTasks().getInputListener());
        }
        this.mDisplayPolicy = new DisplayPolicy(service, this);
        this.mDisplayRotation = new DisplayRotation(service, this);
        this.mCloseToSquareMaxAspectRatio = service.mContext.getResources().getFloat(17105054);
        if (this.isDefaultDisplay) {
            this.mWmService.mPolicy.setDefaultDisplay(this);
        }
        if (this.mWmService.mDisplayReady) {
            this.mDisplayPolicy.onConfigurationChanged();
        }
        if (this.mWmService.mSystemReady) {
            this.mDisplayPolicy.systemReady();
        }
        this.mWindowCornerRadius = this.mDisplayPolicy.getWindowCornerRadius();
        this.mDividerControllerLocked = new DockedStackDividerController(service, this);
        this.mPinnedStackControllerLocked = new PinnedStackController(service, this);
        SurfaceControl.Builder b = this.mWmService.makeSurfaceBuilder(this.mSession).setOpaque(true).setContainerLayer();
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
        this.mWmService.mRoot.addChild((RootWindowContainer) this, (Comparator<RootWindowContainer>) null);
        this.mDisplayReady = true;
        this.mWmService.mAnimator.addDisplayLocked(this.mDisplayId);
        this.mInputMonitor = new InputMonitor(service, this.mDisplayId);
        this.mInsetsStateController = new InsetsStateController(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isReady() {
        return this.mWmService.mDisplayReady && this.mDisplayReady;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDisplayId() {
        return this.mDisplayId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float getWindowCornerRadius() {
        return this.mWindowCornerRadius;
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
        DisplayContent dc = this.mWmService.mRoot.getWindowTokenDisplay(token);
        if (dc != null) {
            throw new IllegalArgumentException("Can't map token=" + token + " to display=" + getName() + " already mapped to display=" + dc + " tokens=" + dc.mTokenMap);
        } else if (binder == null) {
            throw new IllegalArgumentException("Can't map token=" + token + " to display=" + getName() + " binder is null");
        } else if (token == null) {
            throw new IllegalArgumentException("Can't map null token to display=" + getName() + " binder=" + binder);
        } else {
            this.mTokenMap.put(binder, token);
            if (token.asAppWindowToken() == null) {
                int layer = this.mWmService.getXuiLayer(token.windowType);
                if (token.windowType == 2050) {
                    layer = xpWindowManager.getOverrideWallpaperLayer(layer);
                }
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
        if (prevDc != null) {
            if (prevDc.mTokenMap.remove(token.token) != null && token.asAppWindowToken() == null) {
                token.getParent().removeChild(token);
            }
            if (token.hasChild(prevDc.mLastFocus)) {
                prevDc.mLastFocus = null;
            }
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

    @Override // com.android.server.policy.WindowManagerPolicy.DisplayContentInfo
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
    public DisplayPolicy getDisplayPolicy() {
        return this.mDisplayPolicy;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.DisplayContentInfo
    public DisplayRotation getDisplayRotation() {
        return this.mDisplayRotation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInsetProvider(int type, WindowState win, TriConsumer<DisplayFrames, WindowState, Rect> frameProvider) {
        this.mInsetsStateController.getSourceProvider(type).setWindow(win, frameProvider);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public InsetsStateController getInsetsStateController() {
        return this.mInsetsStateController;
    }

    @VisibleForTesting
    void setDisplayRotation(DisplayRotation displayRotation) {
        this.mDisplayRotation = displayRotation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRotation() {
        return this.mRotation;
    }

    @VisibleForTesting
    void setRotation(int newRotation) {
        this.mRotation = newRotation;
        this.mDisplayRotation.setRotation(newRotation);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastOrientation() {
        return this.mLastOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastWindowForcedOrientation() {
        return this.mLastWindowForcedOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        this.mAppTransitionController.registerRemoteAnimations(definition);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pauseRotationLocked() {
        this.mDeferredRotationPauseCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeRotationLocked() {
        int i = this.mDeferredRotationPauseCount;
        if (i <= 0) {
            return;
        }
        this.mDeferredRotationPauseCount = i - 1;
        if (this.mDeferredRotationPauseCount == 0) {
            updateRotationAndSendNewConfigIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean rotationNeedsUpdate() {
        int lastOrientation = getLastOrientation();
        int oldRotation = getRotation();
        int rotation = this.mDisplayRotation.rotationForOrientation(lastOrientation, oldRotation);
        return oldRotation != rotation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initializeDisplayOverrideConfiguration() {
        ActivityDisplay activityDisplay = this.mAcitvityDisplay;
        if (activityDisplay != null) {
            activityDisplay.onInitializeOverrideConfiguration(getRequestedOverrideConfiguration());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendNewConfiguration() {
        this.mWmService.mH.obtainMessage(18, this).sendToTarget();
    }

    @Override // com.android.server.wm.WindowContainer
    boolean onDescendantOrientationChanged(IBinder freezeDisplayToken, ConfigurationContainer requestingContainer) {
        Configuration config = updateOrientationFromAppTokens(getRequestedOverrideConfiguration(), freezeDisplayToken, false);
        boolean handled = getDisplayRotation().respectAppRequestedOrientation();
        if (config == null) {
            return handled;
        }
        if (handled && (requestingContainer instanceof ActivityRecord)) {
            ActivityRecord activityRecord = (ActivityRecord) requestingContainer;
            boolean kept = this.mWmService.mAtmService.updateDisplayOverrideConfigurationLocked(config, activityRecord, false, getDisplayId());
            activityRecord.frozenBeforeDestroy = true;
            if (!kept) {
                this.mWmService.mAtmService.mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
        } else {
            this.mWmService.mAtmService.updateDisplayOverrideConfigurationLocked(config, null, false, getDisplayId());
        }
        return handled;
    }

    @Override // com.android.server.wm.WindowContainer
    boolean handlesOrientationChangeFromDescendant() {
        return getDisplayRotation().respectAppRequestedOrientation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateOrientationFromAppTokens() {
        return updateOrientationFromAppTokens(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration updateOrientationFromAppTokens(Configuration currentConfig, IBinder freezeDisplayToken, boolean forceUpdate) {
        AppWindowToken atoken;
        if (!this.mDisplayReady) {
            return null;
        }
        if (updateOrientationFromAppTokens(forceUpdate)) {
            if (freezeDisplayToken != null && !this.mWmService.mRoot.mOrientationChangeComplete && (atoken = getAppWindowToken(freezeDisplayToken)) != null) {
                atoken.startFreezingScreen();
            }
            Configuration config = new Configuration();
            computeScreenConfiguration(config);
            return config;
        } else if (currentConfig == null) {
            return null;
        } else {
            this.mTmpConfiguration.unset();
            this.mTmpConfiguration.updateFrom(currentConfig);
            computeScreenConfiguration(this.mTmpConfiguration);
            if (currentConfig.diff(this.mTmpConfiguration) == 0) {
                return null;
            }
            this.mWaitingForConfig = true;
            setLayoutNeeded();
            int[] anim = new int[2];
            getDisplayPolicy().selectRotationAnimationLw(anim);
            this.mWmService.startFreezingDisplayLocked(anim[0], anim[1], this);
            return new Configuration(this.mTmpConfiguration);
        }
    }

    private boolean updateOrientationFromAppTokens(boolean forceUpdate) {
        int req = getOrientation();
        if (req != this.mLastOrientation || forceUpdate) {
            this.mLastOrientation = req;
            this.mDisplayRotation.setCurrentOrientation(req);
            return updateRotationUnchecked(forceUpdate);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateRotationAndSendNewConfigIfNeeded() {
        boolean changed = updateRotationUnchecked(false);
        if (changed) {
            sendNewConfiguration();
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateRotationUnchecked() {
        return updateRotationUnchecked(false);
    }

    boolean updateRotationUnchecked(boolean forceUpdate) {
        if (!forceUpdate) {
            if (this.mDeferredRotationPauseCount > 0) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, rotation is paused.");
                }
                return false;
            }
            ScreenRotationAnimation screenRotationAnimation = this.mWmService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
            if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, animation in progress.");
                }
                return false;
            } else if (this.mWmService.mDisplayFrozen) {
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Deferring rotation, still finishing previous rotation");
                }
                return false;
            }
        }
        if (!this.mWmService.mDisplayEnabled) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Deferring rotation, display is not enabled.");
            }
            return false;
        }
        int oldRotation = this.mRotation;
        int lastOrientation = this.mLastOrientation;
        int rotation = this.mDisplayRotation.rotationForOrientation(lastOrientation, oldRotation);
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "Computed rotation=" + rotation + " for display id=" + this.mDisplayId + " based on lastOrientation=" + lastOrientation + " and oldRotation=" + oldRotation);
        }
        boolean mayRotateSeamlessly = this.mDisplayPolicy.shouldRotateSeamlessly(this.mDisplayRotation, oldRotation, rotation);
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
                if (i >= this.mWmService.mSessions.size()) {
                    break;
                } else if (!this.mWmService.mSessions.valueAt(i).hasAlertWindowSurfaces()) {
                    i++;
                } else {
                    mayRotateSeamlessly = false;
                    break;
                }
            }
        }
        boolean rotateSeamlessly = mayRotateSeamlessly;
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "Display id=" + this.mDisplayId + " selected orientation " + lastOrientation + ", got rotation " + rotation);
        }
        if (oldRotation == rotation) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "Display id=" + this.mDisplayId + " rotation changed to " + rotation + " from " + oldRotation + ", lastOrientation=" + lastOrientation);
        }
        if (deltaRotation(rotation, oldRotation) != 2) {
            this.mWaitingForConfig = true;
        }
        this.mRotation = rotation;
        this.mWmService.mWindowsFreezingScreen = 1;
        this.mWmService.mH.sendNewMessageDelayed(11, this, 2000L);
        setLayoutNeeded();
        int[] anim = new int[2];
        this.mDisplayPolicy.selectRotationAnimationLw(anim);
        if (!rotateSeamlessly) {
            this.mWmService.startFreezingDisplayLocked(anim[0], anim[1], this);
        } else {
            this.mWmService.startSeamlessRotation();
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyRotationLocked(final int oldRotation, final int rotation) {
        this.mDisplayRotation.setRotation(rotation);
        final boolean rotateSeamlessly = this.mWmService.isRotatingSeamlessly();
        ScreenRotationAnimation screenRotationAnimation = rotateSeamlessly ? null : this.mWmService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
        updateDisplayAndOrientation(getConfiguration().uiMode, null);
        if (WindowManagerService.CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null && screenRotationAnimation.hasScreenshot()) {
            if (screenRotationAnimation.setRotation(getPendingTransaction(), rotation, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY, this.mWmService.getTransitionAnimationScaleLocked(), this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight)) {
                this.mWmService.scheduleAnimationLocked();
            }
        }
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$3g7y7M5XrDR3cz8tOp9f3pwWbyQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$applyRotationLocked$10$DisplayContent(oldRotation, rotation, rotateSeamlessly, (WindowState) obj);
            }
        }, true);
        this.mWmService.mDisplayManagerInternal.performTraversal(getPendingTransaction());
        scheduleAnimation();
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$-XeeexVnAosqA0zfHVCT_Txqwl8
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$applyRotationLocked$11$DisplayContent(rotateSeamlessly, (WindowState) obj);
            }
        }, true);
        if (rotateSeamlessly) {
            this.mWmService.mH.sendNewMessageDelayed(54, this, 2000L);
        }
        for (int i = this.mWmService.mRotationWatchers.size() - 1; i >= 0; i--) {
            WindowManagerService.RotationWatcher rotationWatcher = this.mWmService.mRotationWatchers.get(i);
            if (rotationWatcher.mDisplayId == this.mDisplayId) {
                try {
                    rotationWatcher.mWatcher.onRotationChanged(rotation);
                } catch (RemoteException e) {
                }
            }
        }
        if (screenRotationAnimation == null && this.mWmService.mAccessibilityController != null) {
            this.mWmService.mAccessibilityController.onRotationChangedLocked(this);
        }
    }

    public /* synthetic */ void lambda$applyRotationLocked$10$DisplayContent(int oldRotation, int rotation, boolean rotateSeamlessly, WindowState w) {
        w.seamlesslyRotateIfAllowed(getPendingTransaction(), oldRotation, rotation, rotateSeamlessly);
    }

    public /* synthetic */ void lambda$applyRotationLocked$11$DisplayContent(boolean rotateSeamlessly, WindowState w) {
        if (w.mHasSurface && !rotateSeamlessly) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Set mOrientationChanging of " + w);
            }
            w.setOrientationChanging(true);
            this.mWmService.mRoot.mOrientationChangeComplete = false;
            w.mLastFreezeDuration = 0;
        }
        w.mReportOrientationChanged = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void configureDisplayPolicy() {
        int shortSize;
        int longSize;
        int width = this.mBaseDisplayWidth;
        int height = this.mBaseDisplayHeight;
        if (width > height) {
            shortSize = height;
            longSize = width;
        } else {
            shortSize = width;
            longSize = height;
        }
        int i = this.mBaseDisplayDensity;
        int shortSizeDp = (shortSize * 160) / i;
        int longSizeDp = (longSize * 160) / i;
        this.mDisplayPolicy.updateConfigurationAndScreenSizeDependentBehaviors();
        this.mDisplayRotation.configure(width, height, shortSizeDp, longSizeDp);
        DisplayFrames displayFrames = this.mDisplayFrames;
        DisplayInfo displayInfo = this.mDisplayInfo;
        displayFrames.onDisplayInfoUpdated(displayInfo, calculateDisplayCutoutForRotation(displayInfo.rotation));
        this.mIgnoreRotationForApps = isNonDecorDisplayCloseToSquare(0, width, height);
    }

    private boolean isNonDecorDisplayCloseToSquare(int rotation, int width, int height) {
        DisplayCutout displayCutout = calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        int uiMode = this.mWmService.mPolicy.getUiMode();
        int w = this.mDisplayPolicy.getNonDecorDisplayWidth(width, height, rotation, uiMode, displayCutout);
        int h = this.mDisplayPolicy.getNonDecorDisplayHeight(width, height, rotation, uiMode, displayCutout);
        float aspectRatio = Math.max(w, h) / Math.min(w, h);
        return aspectRatio <= this.mCloseToSquareMaxAspectRatio;
    }

    private DisplayInfo updateDisplayAndOrientation(int uiMode, Configuration outConfig) {
        int i = this.mRotation;
        boolean z = true;
        if (i != 1 && i != 3) {
            z = false;
        }
        boolean rotated = z;
        int dw = rotated ? this.mBaseDisplayHeight : this.mBaseDisplayWidth;
        int dh = rotated ? this.mBaseDisplayWidth : this.mBaseDisplayHeight;
        WmDisplayCutout wmDisplayCutout = calculateDisplayCutoutForRotation(this.mRotation);
        DisplayCutout displayCutout = wmDisplayCutout.getDisplayCutout();
        int appWidth = this.mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, this.mRotation, uiMode, displayCutout);
        int appHeight = this.mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, this.mRotation, uiMode, displayCutout);
        DisplayInfo displayInfo = this.mDisplayInfo;
        displayInfo.rotation = this.mRotation;
        displayInfo.logicalWidth = dw;
        displayInfo.logicalHeight = dh;
        displayInfo.logicalDensityDpi = this.mBaseDisplayDensity;
        displayInfo.appWidth = appWidth;
        displayInfo.appHeight = appHeight;
        if (this.isDefaultDisplay) {
            displayInfo.getLogicalMetrics(this.mRealDisplayMetrics, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, (Configuration) null);
        }
        this.mDisplayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout;
        this.mDisplayInfo.getAppMetrics(this.mDisplayMetrics);
        if (this.mDisplayScalingDisabled) {
            this.mDisplayInfo.flags |= 1073741824;
        } else {
            this.mDisplayInfo.flags &= -1073741825;
        }
        DisplayInfo displayInfo2 = null;
        computeSizeRangesAndScreenLayout(this.mDisplayInfo, rotated, uiMode, dw, dh, this.mDisplayMetrics.density, outConfig);
        if (this.mShouldOverrideDisplayConfiguration) {
            displayInfo2 = this.mDisplayInfo;
        }
        DisplayInfo overrideDisplayInfo = displayInfo2;
        this.mWmService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(this.mDisplayId, overrideDisplayInfo);
        this.mBaseDisplayRect.set(0, 0, dw, dh);
        if (this.isDefaultDisplay) {
            this.mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(this.mDisplayMetrics, this.mCompatDisplayMetrics);
        }
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
        Rect[] newBounds = this.mRotationUtil.getRotatedBounds(WmDisplayCutout.computeSafeInsets(cutout, this.mInitialDisplayWidth, this.mInitialDisplayHeight).getDisplayCutout().getBoundingRectsAll(), rotation, this.mInitialDisplayWidth, this.mInitialDisplayHeight);
        return WmDisplayCutout.computeSafeInsets(DisplayCutout.fromBounds(newBounds), rotated ? this.mInitialDisplayHeight : this.mInitialDisplayWidth, rotated ? this.mInitialDisplayWidth : this.mInitialDisplayHeight);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void computeScreenConfiguration(Configuration config) {
        int i;
        int i2;
        int i3;
        boolean rotated;
        int i4;
        DisplayInfo displayInfo = updateDisplayAndOrientation(config.uiMode, config);
        calculateBounds(displayInfo, this.mTmpBounds);
        config.windowConfiguration.setBounds(this.mTmpBounds);
        int dw = displayInfo.logicalWidth;
        int dh = displayInfo.logicalHeight;
        config.orientation = dw <= dh ? 1 : 2;
        config.windowConfiguration.setWindowingMode(getWindowingMode());
        config.windowConfiguration.setDisplayWindowingMode(getWindowingMode());
        config.windowConfiguration.setRotation(displayInfo.rotation);
        float density = this.mDisplayMetrics.density;
        config.screenWidthDp = (int) (this.mDisplayPolicy.getConfigDisplayWidth(dw, dh, displayInfo.rotation, config.uiMode, displayInfo.displayCutout) / density);
        config.screenHeightDp = (int) (this.mDisplayPolicy.getConfigDisplayHeight(dw, dh, displayInfo.rotation, config.uiMode, displayInfo.displayCutout) / density);
        this.mDisplayPolicy.getNonDecorInsetsLw(displayInfo.rotation, dw, dh, displayInfo.displayCutout, this.mTmpRect);
        int leftInset = this.mTmpRect.left;
        int topInset = this.mTmpRect.top;
        config.windowConfiguration.setAppBounds(leftInset, topInset, displayInfo.appWidth + leftInset, displayInfo.appHeight + topInset);
        boolean rotated2 = displayInfo.rotation == 1 || displayInfo.rotation == 3;
        int i5 = config.screenLayout & (-769);
        if ((displayInfo.flags & 16) != 0) {
            i = 512;
        } else {
            i = 256;
        }
        config.screenLayout = i5 | i;
        config.compatScreenWidthDp = (int) (config.screenWidthDp / this.mCompatibleScreenScale);
        config.compatScreenHeightDp = (int) (config.screenHeightDp / this.mCompatibleScreenScale);
        config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated2, config.uiMode, dw, dh, displayInfo.displayCutout);
        config.densityDpi = displayInfo.logicalDensityDpi;
        if (displayInfo.isHdr() && this.mWmService.hasHdrSupport()) {
            i2 = 8;
        } else {
            i2 = 4;
        }
        if (displayInfo.isWideColorGamut() && this.mWmService.hasWideColorGamutSupport()) {
            i3 = 2;
        } else {
            i3 = 1;
        }
        config.colorMode = i2 | i3;
        config.touchscreen = 1;
        config.keyboard = 1;
        config.navigation = 1;
        int keyboardPresence = 0;
        int navigationPresence = 0;
        InputDevice[] devices = this.mWmService.mInputManager.getInputDevices();
        int len = devices != null ? devices.length : 0;
        int i6 = 0;
        while (i6 < len) {
            InputDevice device = devices[i6];
            if (device.isVirtual()) {
                rotated = rotated2;
            } else {
                rotated = rotated2;
                if (this.mWmService.mInputManager.canDispatchToDisplay(device.getId(), displayInfo.type == 5 ? 0 : this.mDisplayId)) {
                    int sources = device.getSources();
                    int presenceFlag = device.isExternal() ? 2 : 1;
                    if (this.mWmService.mIsTouchDevice) {
                        if ((sources & UsbACInterface.FORMAT_II_AC3) == 4098) {
                            config.touchscreen = 3;
                        }
                    } else {
                        config.touchscreen = 1;
                    }
                    if ((sources & 65540) == 65540) {
                        config.navigation = 3;
                        navigationPresence |= presenceFlag;
                        i4 = 2;
                    } else if ((sources & UsbTerminalTypes.TERMINAL_IN_MIC) != 513 || config.navigation != 1) {
                        i4 = 2;
                    } else {
                        i4 = 2;
                        config.navigation = 2;
                        navigationPresence |= presenceFlag;
                    }
                    if (device.getKeyboardType() == i4) {
                        config.keyboard = i4;
                        keyboardPresence |= presenceFlag;
                    }
                }
            }
            i6++;
            rotated2 = rotated;
        }
        if (config.navigation == 1 && this.mWmService.mHasPermanentDpad) {
            config.navigation = 2;
            navigationPresence |= 1;
        }
        boolean hardKeyboardAvailable = config.keyboard != 1;
        if (hardKeyboardAvailable != this.mWmService.mHardKeyboardAvailable) {
            this.mWmService.mHardKeyboardAvailable = hardKeyboardAvailable;
            this.mWmService.mH.removeMessages(22);
            this.mWmService.mH.sendEmptyMessage(22);
        }
        this.mDisplayPolicy.updateConfigurationAndScreenSizeDependentBehaviors();
        config.keyboardHidden = 1;
        config.hardKeyboardHidden = 1;
        config.navigationHidden = 1;
        this.mWmService.mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, int dw, int dh, DisplayCutout displayCutout) {
        int unrotDw;
        int unrotDh;
        this.mTmpDisplayMetrics.setTo(this.mDisplayMetrics);
        DisplayMetrics tmpDm = this.mTmpDisplayMetrics;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        int sw = reduceCompatConfigWidthSize(0, 0, uiMode, tmpDm, unrotDw, unrotDh, displayCutout);
        return reduceCompatConfigWidthSize(reduceCompatConfigWidthSize(reduceCompatConfigWidthSize(sw, 1, uiMode, tmpDm, unrotDh, unrotDw, displayCutout), 2, uiMode, tmpDm, unrotDw, unrotDh, displayCutout), 3, uiMode, tmpDm, unrotDh, unrotDw, displayCutout);
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode, DisplayMetrics dm, int dw, int dh, DisplayCutout displayCutout) {
        dm.noncompatWidthPixels = this.mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayCutout);
        dm.noncompatHeightPixels = this.mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayCutout);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, (DisplayMetrics) null);
        int size = (int) (((dm.noncompatWidthPixels / scale) / dm.density) + 0.5f);
        if (curSize == 0 || size < curSize) {
            return size;
        }
        return curSize;
    }

    private void computeSizeRangesAndScreenLayout(DisplayInfo displayInfo, boolean rotated, int uiMode, int dw, int dh, float density, Configuration outConfig) {
        int unrotDw;
        int unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        displayInfo.smallestNominalAppWidth = 1073741824;
        displayInfo.smallestNominalAppHeight = 1073741824;
        displayInfo.largestNominalAppWidth = 0;
        displayInfo.largestNominalAppHeight = 0;
        adjustDisplaySizeRanges(displayInfo, 0, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, 1, uiMode, unrotDh, unrotDw);
        adjustDisplaySizeRanges(displayInfo, 2, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, 3, uiMode, unrotDh, unrotDw);
        if (outConfig == null) {
            return;
        }
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        int i = unrotDh;
        int i2 = unrotDw;
        int i3 = unrotDw;
        int i4 = unrotDh;
        int i5 = unrotDh;
        int i6 = unrotDw;
        int sl2 = reduceConfigLayout(reduceConfigLayout(reduceConfigLayout(reduceConfigLayout(sl, 0, density, unrotDw, unrotDh, uiMode, displayInfo.displayCutout), 1, density, i, i2, uiMode, displayInfo.displayCutout), 2, density, i3, i4, uiMode, displayInfo.displayCutout), 3, density, i5, i6, uiMode, displayInfo.displayCutout);
        outConfig.smallestScreenWidthDp = (int) (displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl2;
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density, int dw, int dh, int uiMode, DisplayCutout displayCutout) {
        int w = this.mDisplayPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode, displayCutout);
        int h = this.mDisplayPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode, displayCutout);
        int longSize = w;
        int shortSize = h;
        if (longSize < shortSize) {
            longSize = shortSize;
            shortSize = longSize;
        }
        return Configuration.reduceScreenLayout(curLayout, (int) (longSize / density), (int) (shortSize / density));
    }

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int rotation, int uiMode, int dw, int dh) {
        DisplayCutout displayCutout = calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
        int width = this.mDisplayPolicy.getConfigDisplayWidth(dw, dh, rotation, uiMode, displayCutout);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        int height = this.mDisplayPolicy.getConfigDisplayHeight(dw, dh, rotation, uiMode, displayCutout);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getPreferredOptionsPanelGravity() {
        int rotation = getRotation();
        if (this.mInitialDisplayWidth < this.mInitialDisplayHeight) {
            if (rotation != 1) {
                return (rotation == 2 || rotation != 3) ? 81 : 8388691;
            }
            return 85;
        } else if (rotation != 1) {
            if (rotation != 2) {
                return rotation != 3 ? 85 : 81;
            }
            return 8388691;
        } else {
            return 81;
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
    WindowList<TaskStack> getStacks() {
        WindowList<TaskStack> stacks = new WindowList<>();
        stacks.addAll(this.mTaskStackContainers.mChildren);
        stacks.addAll(this.mHomeStackContainers.mChildren);
        return stacks;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public TaskStack getTopStack() {
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
        int lastOrientation = getConfiguration().orientation;
        super.onConfigurationChanged(newParentConfig);
        DisplayPolicy displayPolicy = this.mDisplayPolicy;
        if (displayPolicy != null) {
            displayPolicy.onConfigurationChanged();
        }
        if (lastOrientation != getConfiguration().orientation) {
            getMetricsLogger().write(new LogMaker(1659).setSubtype(getConfiguration().orientation).addTaggedData(1660, Integer.valueOf(getDisplayId())));
        }
        if (this.mPinnedStackControllerLocked != null && !hasPinnedStack()) {
            this.mPinnedStackControllerLocked.onDisplayInfoChanged(getDisplayInfo());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void preOnConfigurationChanged() {
        DockedStackDividerController dividerController = getDockedDividerController();
        if (dividerController != null) {
            getDockedDividerController().onConfigurationChanged();
        }
        PinnedStackController pinnedStackController = getPinnedStackController();
        if (pinnedStackController != null) {
            getPinnedStackController().onConfigurationChanged();
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

    @Override // com.android.server.wm.WindowContainer
    void onAppTransitionDone() {
        super.onAppTransitionDone();
        this.mWmService.mWindowsChanged = true;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void setWindowingMode(int windowingMode) {
        super.setWindowingMode(windowingMode);
        super.setDisplayWindowingMode(windowingMode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public void setDisplayWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode);
    }

    private boolean skipTraverseChild(WindowContainer child) {
        if (child == this.mImeWindowsContainers && this.mInputMethodTarget != null && !hasSplitScreenPrimaryStack()) {
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
            return false;
        }
        int count = this.mChildren.size();
        for (int i2 = 0; i2 < count; i2++) {
            DisplayChildWindowContainer child2 = (DisplayChildWindowContainer) this.mChildren.get(i2);
            if (!skipTraverseChild(child2) && child2.forAllWindows(callback, traverseTopToBottom)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean forAllImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return this.mImeWindowsContainers.forAllWindows(callback, traverseTopToBottom);
    }

    @Override // com.android.server.wm.WindowContainer
    int getOrientation() {
        WindowManagerPolicy policy = this.mWmService.mPolicy;
        if (this.mIgnoreRotationForApps) {
            return 2;
        }
        if (this.mWmService.mDisplayFrozen) {
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
        onDisplayChanged(this);
    }

    @Override // com.android.server.wm.WindowContainer
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        updateSystemGestureExclusionLimit();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateSystemGestureExclusionLimit() {
        this.mSystemGestureExclusionLimit = (this.mWmService.mSystemGestureExclusionLimitDp * this.mDisplayMetrics.densityDpi) / 160;
        updateSystemGestureExclusion();
    }

    void initializeDisplayBaseInfo() {
        DisplayInfo newDisplayInfo;
        DisplayManagerInternal displayManagerInternal = this.mWmService.mDisplayManagerInternal;
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
        this.mWmService.mDisplayManagerInternal.getNonOverrideDisplayInfo(this.mDisplayId, this.mDisplayInfo);
        int orientation = this.mDisplayInfo.rotation;
        boolean rotated = orientation == 1 || orientation == 3;
        DisplayInfo displayInfo = this.mDisplayInfo;
        int newWidth = rotated ? displayInfo.logicalHeight : displayInfo.logicalWidth;
        DisplayInfo displayInfo2 = this.mDisplayInfo;
        int newHeight = rotated ? displayInfo2.logicalWidth : displayInfo2.logicalHeight;
        int newDensity = this.mDisplayInfo.logicalDensityDpi;
        DisplayCutout newCutout = this.mDisplayInfo.displayCutout;
        boolean displayMetricsChanged = (this.mInitialDisplayWidth == newWidth && this.mInitialDisplayHeight == newHeight && this.mInitialDisplayDensity == this.mDisplayInfo.logicalDensityDpi && Objects.equals(this.mInitialDisplayCutout, newCutout)) ? false : true;
        if (displayMetricsChanged) {
            boolean isDisplaySizeForced = (this.mBaseDisplayWidth == this.mInitialDisplayWidth && this.mBaseDisplayHeight == this.mInitialDisplayHeight) ? false : true;
            boolean isDisplayDensityForced = this.mBaseDisplayDensity != this.mInitialDisplayDensity;
            updateBaseDisplayMetrics(isDisplaySizeForced ? this.mBaseDisplayWidth : newWidth, isDisplaySizeForced ? this.mBaseDisplayHeight : newHeight, isDisplayDensityForced ? this.mBaseDisplayDensity : newDensity);
            this.mInitialDisplayWidth = newWidth;
            this.mInitialDisplayHeight = newHeight;
            this.mInitialDisplayDensity = newDensity;
            this.mInitialDisplayCutout = newCutout;
            this.mWmService.reconfigureDisplayLocked(this);
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
        int i;
        this.mBaseDisplayWidth = baseWidth;
        this.mBaseDisplayHeight = baseHeight;
        this.mBaseDisplayDensity = baseDensity;
        int i2 = this.mMaxUiWidth;
        if (i2 > 0 && (i = this.mBaseDisplayWidth) > i2) {
            this.mBaseDisplayHeight = (this.mBaseDisplayHeight * i2) / i;
            this.mBaseDisplayDensity = (this.mBaseDisplayDensity * i2) / i;
            this.mBaseDisplayWidth = i2;
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.v(TAG, "Applying config restraints:" + this.mBaseDisplayWidth + "x" + this.mBaseDisplayHeight + " at density:" + this.mBaseDisplayDensity + " on display:" + getDisplayId());
            }
        }
        this.mBaseDisplayRect.set(0, 0, this.mBaseDisplayWidth, this.mBaseDisplayHeight);
        updateBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForcedDensity(int density, int userId) {
        if (density == this.mInitialDisplayDensity) {
        }
        boolean updateCurrent = userId == -2;
        if (this.mWmService.mCurrentUserId == userId || updateCurrent) {
            this.mBaseDisplayDensity = density;
            this.mWmService.reconfigureDisplayLocked(this);
        }
        if (updateCurrent) {
            return;
        }
        if (density == this.mInitialDisplayDensity) {
            density = 0;
        }
        this.mWmService.mDisplayWindowSettings.setForcedDensity(this, density, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForcedScalingMode(int mode) {
        if (mode != 1) {
            mode = 0;
        }
        this.mDisplayScalingDisabled = mode != 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Using display scaling mode: ");
        sb.append(this.mDisplayScalingDisabled ? "off" : UiModeManagerService.Shell.NIGHT_MODE_STR_AUTO);
        Slog.i(TAG, sb.toString());
        this.mWmService.reconfigureDisplayLocked(this);
        this.mWmService.mDisplayWindowSettings.setForcedScalingMode(this, mode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForcedSize(int width, int height) {
        boolean clear = this.mInitialDisplayWidth == width && this.mInitialDisplayHeight == height;
        if (!clear) {
            width = Math.min(Math.max(width, 200), this.mInitialDisplayWidth * 2);
            height = Math.min(Math.max(height, 200), this.mInitialDisplayHeight * 2);
        }
        Slog.i(TAG, "Using new display size: " + width + "x" + height);
        updateBaseDisplayMetrics(width, height, this.mBaseDisplayDensity);
        this.mWmService.reconfigureDisplayLocked(this);
        if (clear) {
            height = 0;
            width = 0;
        }
        this.mWmService.mDisplayWindowSettings.setForcedSize(this, width, height);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStableRect(Rect out) {
        out.set(this.mDisplayFrames.mStable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setStackOnDisplay(int stackId, boolean onTop, TaskStack stack) {
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.d(TAG, "Create new stackId=" + stackId + " on displayId=" + this.mDisplayId);
        }
        if (stack != null && stack.isActivityTypeHome()) {
            this.mHomeStackContainers.addStackToDisplay(stack, onTop);
        } else {
            this.mTaskStackContainers.addStackToDisplay(stack, onTop);
        }
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
    public void positionStackAt(int position, TaskStack child, boolean includingParents) {
        if (child != null && child.isActivityTypeHome()) {
            this.mHomeStackContainers.positionChildAt(position, child, includingParents);
        } else {
            this.mTaskStackContainers.positionChildAt(position, child, includingParents);
        }
        layoutAndAssignWindowLayersIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean pointWithinAppWindow(final int x, final int y) {
        final int[] targetWindowType = {-1};
        PooledConsumer obtainConsumer = PooledLambda.obtainConsumer(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$9GF6f8baPGZRvxJVeBknIuDUb_Y
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                DisplayContent.lambda$pointWithinAppWindow$12(targetWindowType, x, y, (WindowState) obj, (Rect) obj2);
            }
        }, PooledLambda.__(WindowState.class), this.mTmpRect);
        forAllWindows((Consumer<WindowState>) obtainConsumer, true);
        obtainConsumer.recycle();
        return 1 <= targetWindowType[0] && targetWindowType[0] <= 99;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$pointWithinAppWindow$12(int[] targetWindowType, int x, int y, WindowState w, Rect nonArg) {
        if (targetWindowType[0] == -1 && w.isOnScreen() && w.isVisibleLw() && w.getFrameLw().contains(x, y)) {
            targetWindowType[0] = w.mAttrs.type;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task findTaskForResizePoint(int x, int y) {
        int delta = WindowManagerService.dipToPixel(30, this.mDisplayMetrics);
        this.mTmpTaskForResizePointSearchResult.reset();
        int stackNdx = this.mTaskStackContainers.getChildCount();
        while (true) {
            stackNdx--;
            if (stackNdx >= 0) {
                TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
                if (!stack.getWindowConfiguration().canResizeTask()) {
                    return null;
                }
                stack.findTaskForResizePoint(x, y, delta, this.mTmpTaskForResizePointSearchResult);
                if (this.mTmpTaskForResizePointSearchResult.searchDone) {
                    return this.mTmpTaskForResizePointSearchResult.taskForResize;
                }
            } else {
                this.mTmpTaskForResizePointSearchResult.reset();
                for (int stackNdx2 = this.mHomeStackContainers.getChildCount() - 1; stackNdx2 >= 0; stackNdx2--) {
                    TaskStack stack2 = (TaskStack) this.mHomeStackContainers.getChildAt(stackNdx2);
                    if (!stack2.getWindowConfiguration().canResizeTask()) {
                        return null;
                    }
                    stack2.findTaskForResizePoint(x, y, delta, this.mTmpTaskForResizePointSearchResult);
                    if (this.mTmpTaskForResizePointSearchResult.searchDone) {
                        return this.mTmpTaskForResizePointSearchResult.taskForResize;
                    }
                }
                return null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTouchExcludeRegion() {
        AppWindowToken appWindowToken = this.mFocusedApp;
        Task focusedTask = appWindowToken != null ? appWindowToken.getTask() : null;
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
        WindowState windowState = this.mInputMethodWindow;
        if (windowState != null && windowState.isVisibleLw()) {
            this.mInputMethodWindow.getTouchableRegion(this.mTmpRegion);
            this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
        }
        for (int i = this.mTapExcludedWindows.size() - 1; i >= 0; i--) {
            WindowState win = this.mTapExcludedWindows.get(i);
            win.getTouchableRegion(this.mTmpRegion);
            this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
        }
        amendWindowTapExcludeRegion(this.mTouchExcludeRegion);
        if (this.mDisplayId == 0 && getSplitScreenPrimaryStack() != null) {
            this.mDividerControllerLocked.getTouchRegion(this.mTmpRect);
            this.mTmpRegion.set(this.mTmpRect);
            this.mTouchExcludeRegion.op(this.mTmpRegion, Region.Op.UNION);
        }
        this.mTapDetector.setTouchExcludeRegion(this.mTouchExcludeRegion);
    }

    void amendWindowTapExcludeRegion(Region inOutRegion) {
        for (int i = this.mTapExcludeProvidingWindows.size() - 1; i >= 0; i--) {
            WindowState win = this.mTapExcludeProvidingWindows.valueAt(i);
            win.amendTapExcludeRegion(inOutRegion);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void switchUser() {
        super.switchUser();
        this.mWmService.mWindowsChanged = true;
        this.mDisplayPolicy.switchUser();
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
            if (this.mParentWindow != null) {
                this.mParentWindow.removeEmbeddedDisplayContent(this);
            }
            this.mOpeningApps.clear();
            this.mClosingApps.clear();
            this.mChangingApps.clear();
            this.mUnknownAppVisibilityController.clear();
            this.mAppTransition.removeAppTransitionTimeoutCallbacks();
            handleAnimatingStoppedAndTransition();
            this.mWmService.stopFreezingDisplayLocked();
            super.removeImmediately();
            if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                Slog.v(TAG, "Removing display=" + this);
            }
            this.mPointerEventDispatcher.dispose();
            this.mWmService.mAnimator.removeDisplayLocked(this.mDisplayId);
            this.mWindowingLayer.release();
            this.mOverlayLayer.release();
            this.mInputMonitor.onDisplayRemoved();
            this.mDisplayReady = false;
            this.mRemovingDisplay = false;
            this.mWmService.mWindowPlacerLocked.requestTraversal();
        } catch (Throwable th) {
            this.mDisplayReady = false;
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

    boolean isRemovalDeferred() {
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
                    DockedStackDividerController dockedStackDividerController = this.mDividerControllerLocked;
                    dockedStackDividerController.mLastAnimationProgress = dockedStackDividerController.getInterpolatedAnimationValue(interpolatedValue);
                    DockedStackDividerController dockedStackDividerController2 = this.mDividerControllerLocked;
                    dockedStackDividerController2.mLastDividerProgress = dockedStackDividerController2.getInterpolatedDividerValue(interpolatedValue);
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
                    DockedStackDividerController dockedStackDividerController3 = this.mDividerControllerLocked;
                    dockedStackDividerController3.mLastAnimationProgress = dockedStackDividerController3.getInterpolatedAnimationValue(interpolatedValue);
                    DockedStackDividerController dockedStackDividerController4 = this.mDividerControllerLocked;
                    dockedStackDividerController4.mLastDividerProgress = dockedStackDividerController4.getInterpolatedDividerValue(interpolatedValue);
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
    /* JADX WARN: Removed duplicated region for block: B:100:0x0134  */
    /* JADX WARN: Removed duplicated region for block: B:107:0x0151  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void adjustForImeIfNeeded() {
        /*
            Method dump skipped, instructions count: 375
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayContent.adjustForImeIfNeeded():void");
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
        rotateBounds(this.mTmpRect, oldRotation, newRotation, bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void rotateBounds(Rect parentBounds, int oldRotation, int newRotation, Rect bounds) {
        int deltaRotation = deltaRotation(newRotation, oldRotation);
        createRotationMatrix(deltaRotation, parentBounds.width(), parentBounds.height(), this.mTmpMatrix);
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
        if (rotation == 0) {
            outMatrix.reset();
        } else if (rotation == 1) {
            outMatrix.setRotate(90.0f, 0.0f, 0.0f);
            outMatrix.postTranslate(displayWidth, 0.0f);
            outMatrix.postTranslate(-rectTop, rectLeft);
        } else if (rotation == 2) {
            outMatrix.reset();
        } else if (rotation == 3) {
            outMatrix.setRotate(270.0f, 0.0f, 0.0f);
            outMatrix.postTranslate(0.0f, displayHeight);
            outMatrix.postTranslate(rectTop, 0.0f);
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel == 2 && !isVisible()) {
            return;
        }
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        proto.write(1120986464258L, this.mDisplayId);
        for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            stack.writeToProto(proto, 2246267895811L, logLevel);
        }
        this.mDividerControllerLocked.writeToProto(proto, 1146756268036L);
        this.mPinnedStackControllerLocked.writeToProto(proto, 1146756268037L);
        for (int i = this.mAboveAppWindowsContainers.getChildCount() - 1; i >= 0; i--) {
            WindowToken windowToken = (WindowToken) this.mAboveAppWindowsContainers.getChildAt(i);
            windowToken.writeToProto(proto, 2246267895814L, logLevel);
        }
        for (int i2 = this.mBelowAppWindowsContainers.getChildCount() - 1; i2 >= 0; i2--) {
            WindowToken windowToken2 = (WindowToken) this.mBelowAppWindowsContainers.getChildAt(i2);
            windowToken2.writeToProto(proto, 2246267895815L, logLevel);
        }
        for (int i3 = this.mImeWindowsContainers.getChildCount() - 1; i3 >= 0; i3--) {
            WindowToken windowToken3 = (WindowToken) this.mImeWindowsContainers.getChildAt(i3);
            windowToken3.writeToProto(proto, 2246267895816L, logLevel);
        }
        proto.write(1120986464265L, this.mBaseDisplayDensity);
        this.mDisplayInfo.writeToProto(proto, 1146756268042L);
        proto.write(1120986464267L, this.mRotation);
        ScreenRotationAnimation screenRotationAnimation = this.mWmService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
        if (screenRotationAnimation != null) {
            screenRotationAnimation.writeToProto(proto, 1146756268044L);
        }
        this.mDisplayFrames.writeToProto(proto, 1146756268045L);
        this.mAppTransition.writeToProto(proto, 1146756268048L);
        AppWindowToken appWindowToken = this.mFocusedApp;
        if (appWindowToken != null) {
            appWindowToken.writeNameToProto(proto, 1138166333455L);
        }
        for (int i4 = this.mOpeningApps.size() - 1; i4 >= 0; i4--) {
            this.mOpeningApps.valueAt(i4).mActivityRecord.writeIdentifierToProto(proto, 2246267895825L);
        }
        for (int i5 = this.mClosingApps.size() - 1; i5 >= 0; i5--) {
            this.mClosingApps.valueAt(i5).mActivityRecord.writeIdentifierToProto(proto, 2246267895826L);
        }
        for (int i6 = this.mChangingApps.size() - 1; i6 >= 0; i6--) {
            this.mChangingApps.valueAt(i6).mActivityRecord.writeIdentifierToProto(proto, 2246267895827L);
        }
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
        pw.print(prefix);
        pw.print("mDeferredRotationPauseCount=");
        pw.println(this.mDeferredRotationPauseCount);
        pw.print("  mCurrentFocus=");
        pw.println(this.mCurrentFocus);
        if (this.mLastFocus != this.mCurrentFocus) {
            pw.print("  mLastFocus=");
            pw.println(this.mLastFocus);
        }
        if (this.mLosingFocus.size() > 0) {
            pw.println();
            pw.println("  Windows losing focus:");
            for (int i = this.mLosingFocus.size() - 1; i >= 0; i--) {
                WindowState w = this.mLosingFocus.get(i);
                pw.print("  Losing #");
                pw.print(i);
                pw.print(' ');
                pw.print(w);
                if (dumpAll) {
                    pw.println(":");
                    w.dump(pw, "    ", true);
                } else {
                    pw.println();
                }
            }
        }
        pw.print("  mFocusedApp=");
        pw.println(this.mFocusedApp);
        if (this.mLastStatusBarVisibility != 0) {
            pw.print("  mLastStatusBarVisibility=0x");
            pw.println(Integer.toHexString(this.mLastStatusBarVisibility));
        }
        pw.println();
        this.mWallpaperController.dump(pw, "  ");
        pw.println();
        pw.print("mSystemGestureExclusion=");
        if (this.mSystemGestureExclusionListeners.getRegisteredCallbackCount() > 0) {
            pw.println(this.mSystemGestureExclusion);
        } else {
            pw.println("<no lstnrs>");
        }
        pw.println();
        pw.println(prefix + "Application tokens in top down Z order:");
        for (int stackNdx = this.mTaskStackContainers.getChildCount() - 1; stackNdx >= 0; stackNdx += -1) {
            TaskStack stack = (TaskStack) this.mTaskStackContainers.getChildAt(stackNdx);
            stack.dump(pw, prefix + "  ", dumpAll);
        }
        pw.println();
        if (!this.mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i2 = this.mExitingTokens.size() - 1; i2 >= 0; i2--) {
                WindowToken token = this.mExitingTokens.get(i2);
                pw.print("  Exiting #");
                pw.print(i2);
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
        pw.println();
        this.mDisplayPolicy.dump(prefix, pw);
        pw.println();
        this.mDisplayRotation.dump(prefix, pw);
        pw.println();
        this.mInputMonitor.dump(pw, "  ");
        pw.println();
        this.mInsetsStateController.dump(prefix, pw);
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
                return DisplayContent.this.lambda$getTouchableWinAtPointLocked$13$DisplayContent(x, y, (WindowState) obj);
            }
        });
        return touchedWin;
    }

    public /* synthetic */ boolean lambda$getTouchableWinAtPointLocked$13$DisplayContent(int x, int y, WindowState w) {
        int flags = w.mAttrs.flags;
        if (w.isVisibleLw() && (flags & 16) == 0) {
            w.getVisibleBounds(this.mTmpRect);
            if (this.mTmpRect.contains(x, y)) {
                w.getTouchableRegion(this.mTmpRegion);
                int touchFlags = flags & 40;
                return this.mTmpRegion.contains(x, y) || touchFlags == 0;
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

    void scheduleToastWindowsTimeoutIfNeededLocked(WindowState oldFocus, WindowState newFocus) {
        if (oldFocus != null) {
            if (newFocus != null && newFocus.mOwnerUid == oldFocus.mOwnerUid) {
                return;
            }
            this.mTmpWindow = oldFocus;
            forAllWindows(this.mScheduleToastTimeout, false);
        }
    }

    WindowState findFocusedWindowIfNeeded(int topFocusedDisplayId) {
        if (this.mWmService.mPerDisplayFocusEnabled || topFocusedDisplayId == -1) {
            return findFocusedWindow();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState findFocusedWindow() {
        this.mTmpWindow = null;
        forAllWindows(this.mFindFocusedWindow, true);
        WindowState windowState = this.mTmpWindow;
        if (windowState == null) {
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v(TAG, "findFocusedWindow: No focusable windows.");
            }
            return null;
        }
        return windowState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x004a, code lost:
        if (com.android.server.wm.WindowManagerService.localLOGV != false) goto L50;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean updateFocusedWindowLocked(int r12, boolean r13, int r14) {
        /*
            Method dump skipped, instructions count: 236
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplayContent.updateFocusedWindowLocked(int, boolean, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setFocusedApp(AppWindowToken newFocus) {
        DisplayContent appDisplay;
        if (newFocus != null && (appDisplay = newFocus.getDisplayContent()) != this) {
            StringBuilder sb = new StringBuilder();
            sb.append(newFocus);
            sb.append(" is not on ");
            sb.append(getName());
            sb.append(" but ");
            sb.append(appDisplay != null ? appDisplay.getName() : "none");
            throw new IllegalStateException(sb.toString());
        } else if (this.mFocusedApp == newFocus) {
            return false;
        } else {
            this.mFocusedApp = newFocus;
            getInputMonitor().setFocusedAppLw(newFocus);
            updateTouchExcludeRegion();
            return true;
        }
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
        this.mWmService.mWindowsChanged = true;
        setLayoutNeeded();
        if (!this.mWmService.updateFocusedWindowLocked(3, false)) {
            assignWindowLayers(false);
        }
        this.mInputMonitor.setUpdateInputWindowsNeededLw();
        this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
        this.mInputMonitor.updateInputWindowsLw(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean destroyLeakedSurfaces() {
        this.mTmpWindow = null;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$rF1ZhFUTWyZqcBK8Oea3g5-uNlM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$destroyLeakedSurfaces$16$DisplayContent((WindowState) obj);
            }
        }, false);
        return this.mTmpWindow != null;
    }

    public /* synthetic */ void lambda$destroyLeakedSurfaces$16$DisplayContent(WindowState w) {
        WindowStateAnimator wsa = w.mWinAnimator;
        if (wsa.mSurfaceController == null) {
            return;
        }
        if (!this.mWmService.mSessions.contains(wsa.mSession)) {
            Slog.w(TAG, "LEAKED SURFACE (session doesn't exist): " + w + " surface=" + wsa.mSurfaceController + " token=" + w.mToken + " pid=" + w.mSession.mPid + " uid=" + w.mSession.mUid);
            wsa.destroySurface();
            this.mWmService.mForceRemoves.add(w);
            this.mTmpWindow = w;
        } else if (w.mAppToken != null && w.mAppToken.isClientHidden()) {
            Slog.w(TAG, "LEAKED SURFACE (app token hidden): " + w + " surface=" + wsa.mSurfaceController + " token=" + w.mAppToken);
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                WindowManagerService.logSurface(w, "LEAK DESTROY", false);
            }
            wsa.destroySurface();
            this.mTmpWindow = w;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInputMethodWindowLocked(WindowState win) {
        this.mInputMethodWindow = win;
        WindowState windowState = this.mInputMethodWindow;
        if (windowState != null) {
            int imePid = windowState.mSession.mPid;
            this.mWmService.mAtmInternal.onImeWindowSetOnDisplay(imePid, this.mInputMethodWindow.getDisplayId());
        }
        computeImeTarget(true);
        this.mInsetsStateController.getSourceProvider(10).setWindow(win, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState computeImeTarget(boolean updateImeTarget) {
        AppWindowToken token;
        WindowState betterTarget;
        if (this.mInputMethodWindow == null) {
            if (updateImeTarget) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    Slog.w(TAG, "Moving IM target from " + this.mInputMethodTarget + " to null since mInputMethodWindow is null");
                }
                setInputMethodTarget(null, this.mInputMethodTargetWaitingAnim);
            }
            return null;
        }
        WindowState curTarget = this.mInputMethodTarget;
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
            Slog.v(TAG, "Proposed new IME target: " + target + " for display: " + getDisplayId());
        }
        if (curTarget != null && !curTarget.mRemoved && curTarget.isDisplayedLw() && curTarget.isClosing()) {
            if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                Slog.v(TAG, "Not changing target till current window is closing and not removed");
            }
            return curTarget;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.v(TAG, "Desired input method target=" + target + " updateImeTarget=" + updateImeTarget);
        }
        String str = "";
        if (target == null) {
            if (updateImeTarget) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Moving IM target from ");
                    sb.append(curTarget);
                    sb.append(" to null.");
                    if (WindowManagerDebugConfig.SHOW_STACK_CRAWLS) {
                        str = " Callers=" + Debug.getCallers(4);
                    }
                    sb.append(str);
                    Slog.w(TAG, sb.toString());
                }
                setInputMethodTarget(null, this.mInputMethodTargetWaitingAnim);
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
                    if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                        Slog.v(TAG, this.mAppTransition + " " + highestTarget + " animating=" + highestTarget.isAnimating());
                    }
                    if (this.mAppTransition.isTransitionSet()) {
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
                }
                sb2.append(str);
                Slog.w(TAG, sb2.toString());
            }
            setInputMethodTarget(target, false);
        }
        return target;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void computeImeTargetIfNeeded(AppWindowToken candidate) {
        WindowState windowState = this.mInputMethodTarget;
        if (windowState != null && windowState.mAppToken == candidate) {
            computeImeTarget(true);
        }
    }

    private void setInputMethodTarget(WindowState target, boolean targetWaitingAnim) {
        if (target == this.mInputMethodTarget && this.mInputMethodTargetWaitingAnim == targetWaitingAnim) {
            return;
        }
        this.mInputMethodTarget = target;
        this.mInputMethodTargetWaitingAnim = targetWaitingAnim;
        assignWindowLayers(false);
        this.mInsetsStateController.onImeTargetChanged(target);
        updateImeParent();
    }

    private void updateImeParent() {
        boolean shouldAttachToDisplay = this.mMagnificationSpec != null;
        SurfaceControl newParent = shouldAttachToDisplay ? this.mWindowingLayer : computeImeParent();
        if (newParent != null) {
            getPendingTransaction().reparent(this.mImeWindowsContainers.mSurfaceControl, newParent);
            scheduleAnimation();
        }
    }

    @VisibleForTesting
    SurfaceControl computeImeParent() {
        WindowState windowState = this.mInputMethodTarget;
        if (windowState != null && windowState.mAppToken != null && this.mInputMethodTarget.getWindowingMode() == 1 && this.mInputMethodTarget.mAppToken.matchParentBounds()) {
            return this.mInputMethodTarget.mAppToken.getSurfaceControl();
        }
        return this.mWindowingLayer;
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
                return DisplayContent.this.lambda$getNeedsMenu$17$DisplayContent(top, bottom, (WindowState) obj);
            }
        });
        return candidate != null && candidate.mAttrs.needsMenuKey == 1;
    }

    public /* synthetic */ boolean lambda$getNeedsMenu$17$DisplayContent(WindowState top, WindowManagerPolicy.WindowState bottom, WindowState w) {
        if (w == top) {
            this.mTmpWindow = w;
        }
        if (this.mTmpWindow == null) {
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
        if (!this.mOpeningApps.isEmpty() || !this.mClosingApps.isEmpty() || !this.mChangingApps.isEmpty()) {
            pw.println();
            if (this.mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps=");
                pw.println(this.mOpeningApps);
            }
            if (this.mClosingApps.size() > 0) {
                pw.print("  mClosingApps=");
                pw.println(this.mClosingApps);
            }
            if (this.mChangingApps.size() > 0) {
                pw.print("  mChangingApps=");
                pw.println(this.mChangingApps);
            }
        }
        this.mUnknownAppVisibilityController.dump(pw, "  ");
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
    public void startKeyguardExitOnNonAppWindows(final boolean onWallpaper, final boolean goingToShade, final boolean subtle) {
        final WindowManagerPolicy policy = this.mWmService.mPolicy;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$6eK8yH4GLimaHyBccN7V6eRKIHQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$startKeyguardExitOnNonAppWindows$19(WindowManagerPolicy.this, onWallpaper, goingToShade, subtle, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startKeyguardExitOnNonAppWindows$19(WindowManagerPolicy policy, boolean onWallpaper, boolean goingToShade, boolean subtle, WindowState w) {
        if (w.mAppToken == null && policy.canBeHiddenByKeyguardLw(w) && w.wouldBeVisibleIfPolicyIgnored() && !w.isVisible()) {
            w.startAnimation(policy.createHiddenByKeyguardExit(onWallpaper, goingToShade, subtle));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkWaitingForWindows() {
        boolean wallpaperEnabled;
        this.mHaveBootMsg = false;
        this.mHaveApp = false;
        this.mHaveWallpaper = false;
        this.mHaveKeyguard = true;
        WindowState visibleWindow = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$BgTlvHbVclnASz-MrvERWxyMV-A
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return DisplayContent.this.lambda$checkWaitingForWindows$20$DisplayContent((WindowState) obj);
            }
        });
        if (visibleWindow != null) {
            return true;
        }
        if (!this.mWmService.mContext.getResources().getBoolean(17891453) || !this.mWmService.mContext.getResources().getBoolean(17891394) || this.mWmService.mOnlyCore) {
            wallpaperEnabled = false;
        } else {
            wallpaperEnabled = true;
        }
        if (WindowManagerDebugConfig.DEBUG_SCREEN_ON || WindowManagerDebugConfig.DEBUG_BOOT) {
            Slog.i(TAG, "******** booted=" + this.mWmService.mSystemBooted + " msg=" + this.mWmService.mShowingBootMessages + " haveBoot=" + this.mHaveBootMsg + " haveApp=" + this.mHaveApp + " haveWall=" + this.mHaveWallpaper + " wallEnabled=" + wallpaperEnabled + " haveKeyguard=" + this.mHaveKeyguard);
        }
        if (!this.mWmService.mSystemBooted && !this.mHaveBootMsg) {
            return true;
        }
        if (!this.mWmService.mSystemBooted || ((this.mHaveApp || this.mHaveKeyguard) && (!wallpaperEnabled || this.mHaveWallpaper))) {
            return false;
        }
        return true;
    }

    public /* synthetic */ boolean lambda$checkWaitingForWindows$20$DisplayContent(WindowState w) {
        if (!w.isVisibleLw() || w.mObscured || w.isDrawnLw()) {
            if (w.isDrawnLw()) {
                if (w.mAttrs.type == 2021) {
                    this.mHaveBootMsg = true;
                    return false;
                } else if (w.mAttrs.type == 2 || w.mAttrs.type == 6 || w.mAttrs.type == 12 || w.mAttrs.type == 10 || w.mAttrs.type == 5 || w.mAttrs.type == 4) {
                    this.mHaveApp = true;
                    return false;
                } else if (w.mAttrs.type == 2013) {
                    this.mHaveWallpaper = true;
                    return false;
                } else if (w.mAttrs.type == 2000) {
                    this.mHaveKeyguard = this.mWmService.mPolicy.isKeyguardDrawnLw();
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
    public void updateWindowsForAnimator() {
        forAllWindows(this.mUpdateWindowsForAnimator, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBackgroundForAnimator() {
        resetAnimationBackgroundAnimator();
        forAllWindows(this.mUpdateWallpaperForAnimator, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInputMethodClientFocus(int uid, int pid) {
        WindowState imFocus = computeImeTarget(false);
        if (imFocus == null) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.i(TAG, "Desired input method target: " + imFocus);
            Slog.i(TAG, "Current focus: " + this.mCurrentFocus + " displayId=" + this.mDisplayId);
            Slog.i(TAG, "Last focus: " + this.mLastFocus + " displayId=" + this.mDisplayId);
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
            Slog.i(TAG, "IM target uid/pid: " + imFocus.mSession.mUid + SliceClientPermissions.SliceAuthority.DELIMITER + imFocus.mSession.mPid);
            Slog.i(TAG, "Requesting client uid/pid: " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + pid);
        }
        return imFocus.mSession.mUid == uid && imFocus.mSession.mPid == pid;
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
    public void statusBarVisibilityChanged(int visibility) {
        this.mLastStatusBarVisibility = visibility;
        updateStatusBarVisibilityLocked(getDisplayPolicy().adjustSystemUiVisibilityLw(visibility));
    }

    private boolean updateStatusBarVisibilityLocked(int visibility) {
        int i = this.mLastDispatchedSystemUiVisibility;
        if (i == visibility) {
            return false;
        }
        int globalDiff = (i ^ visibility) & 7 & (~visibility);
        this.mLastDispatchedSystemUiVisibility = visibility;
        if (this.isDefaultDisplay) {
            this.mWmService.mInputManager.setSystemUiVisibility(visibility);
        }
        updateSystemUiVisibility(visibility, globalDiff);
        return true;
    }

    void updateSystemUiVisibility(final int visibility, final int globalDiff) {
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
    public void reevaluateStatusBarVisibility() {
        int visibility = getDisplayPolicy().adjustSystemUiVisibilityLw(this.mLastStatusBarVisibility);
        if (updateStatusBarVisibilityLocked(visibility)) {
            this.mWmService.mWindowPlacerLocked.requestTraversal();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onWindowFreezeTimeout() {
        Slog.w(TAG, "Window freeze timeout expired.");
        this.mWmService.mWindowsFreezingScreen = 2;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$2HHBX1R6lnY5GedkE9LUBwsCPoE
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$onWindowFreezeTimeout$23$DisplayContent((WindowState) obj);
            }
        }, true);
        this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    public /* synthetic */ void lambda$onWindowFreezeTimeout$23$DisplayContent(WindowState w) {
        if (!w.getOrientationChanging()) {
            return;
        }
        w.orientationChangeTimedOut();
        w.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - this.mWmService.mDisplayFreezeTime);
        Slog.w(TAG, "Force clearing orientation change: " + w);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void waitForAllWindowsDrawn() {
        final WindowManagerPolicy policy = this.mWmService.mPolicy;
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$oqhmXZMcpcvgI50swQTzosAcjac
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$waitForAllWindowsDrawn$24$DisplayContent(policy, (WindowState) obj);
            }
        }, true);
    }

    public /* synthetic */ void lambda$waitForAllWindowsDrawn$24$DisplayContent(WindowManagerPolicy policy, WindowState w) {
        boolean keyguard = policy.isKeyguardHostWindow(w.mAttrs);
        if (w.isVisibleLw()) {
            if (w.mAppToken != null || keyguard) {
                w.mWinAnimator.mDrawState = 1;
                w.resetLastContentInsets();
                this.mWmService.mWaitingForDrawn.add(w);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applySurfaceChangesTransaction(boolean recoveringMemory) {
        boolean z;
        WindowSurfacePlacer surfacePlacer = this.mWmService.mWindowPlacerLocked;
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
            if ((this.pendingLayoutChanges & 4) != 0) {
                this.mWallpaperController.adjustWallpaperWindows();
            }
            if ((this.pendingLayoutChanges & 2) != 0) {
                if (WindowManagerDebugConfig.DEBUG_LAYOUT) {
                    Slog.v(TAG, "Computing new config from layout");
                }
                if (updateOrientationFromAppTokens()) {
                    setLayoutNeeded();
                    sendNewConfiguration();
                }
            }
            if ((this.pendingLayoutChanges & 1) != 0) {
                setLayoutNeeded();
            }
            if (repeats < 4) {
                if (repeats == 1) {
                    z = true;
                } else {
                    z = false;
                }
                performLayout(z, false);
            } else {
                Slog.w(TAG, "Layout repeat skipped after too many iterations");
            }
            this.pendingLayoutChanges = 0;
            Trace.traceBegin(32L, "applyPostLayoutPolicy");
            try {
                this.mDisplayPolicy.beginPostLayoutPolicyLw();
                forAllWindows(this.mApplyPostLayoutPolicy, true);
                this.pendingLayoutChanges |= this.mDisplayPolicy.finishPostLayoutPolicyLw();
                Trace.traceEnd(32L);
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after finishPostLayoutPolicyLw", this.pendingLayoutChanges);
                }
                this.mInsetsStateController.onPostLayout();
                if (this.pendingLayoutChanges == 0) {
                    break;
                }
            } finally {
            }
        }
        this.mTmpApplySurfaceChangesTransactionState.reset();
        this.mTmpRecoveringMemory = recoveringMemory;
        Trace.traceBegin(32L, "applyWindowSurfaceChanges");
        try {
            forAllWindows(this.mApplySurfaceChangesTransaction, true);
            Trace.traceEnd(32L);
            prepareSurfaces();
            this.mLastHasContent = this.mTmpApplySurfaceChangesTransactionState.displayHasContent;
            this.mWmService.mDisplayManagerInternal.setDisplayProperties(this.mDisplayId, this.mLastHasContent, this.mTmpApplySurfaceChangesTransactionState.preferredRefreshRate, this.mTmpApplySurfaceChangesTransactionState.preferredModeId, true);
            boolean wallpaperVisible = this.mWallpaperController.isWallpaperVisible();
            if (wallpaperVisible != this.mLastWallpaperVisible) {
                this.mLastWallpaperVisible = wallpaperVisible;
                this.mWmService.mWallpaperVisibilityListeners.notifyWallpaperVisibilityChanged(this);
            }
            while (!this.mTmpUpdateAllDrawn.isEmpty()) {
                AppWindowToken atoken = this.mTmpUpdateAllDrawn.removeLast();
                atoken.updateAllDrawn();
            }
        } finally {
        }
    }

    private void updateBounds() {
        calculateBounds(this.mDisplayInfo, this.mTmpBounds);
        setBounds(this.mTmpBounds);
        InputWindowHandle inputWindowHandle = this.mPortalWindowHandle;
        if (inputWindowHandle != null && this.mParentSurfaceControl != null) {
            inputWindowHandle.touchableRegion.getBounds(this.mTmpRect);
            if (!this.mTmpBounds.equals(this.mTmpRect)) {
                this.mPortalWindowHandle.touchableRegion.set(this.mTmpBounds);
                getPendingTransaction().setInputWindowInfo(this.mParentSurfaceControl, this.mPortalWindowHandle);
            }
        }
    }

    private void calculateBounds(DisplayInfo displayInfo, Rect out) {
        int rotation = displayInfo.rotation;
        boolean rotated = true;
        if (rotation != 1 && rotation != 3) {
            rotated = false;
        }
        int physWidth = rotated ? this.mBaseDisplayHeight : this.mBaseDisplayWidth;
        int physHeight = rotated ? this.mBaseDisplayWidth : this.mBaseDisplayHeight;
        int width = displayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = displayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
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
    public int getNaturalOrientation() {
        return this.mBaseDisplayWidth < this.mBaseDisplayHeight ? 1 : 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performLayout(boolean initial, boolean updateInputWindows) {
        Trace.traceBegin(32L, "performLayout");
        try {
            performLayoutNoTrace(initial, updateInputWindows);
        } finally {
            Trace.traceEnd(32L);
        }
    }

    private void performLayoutNoTrace(boolean initial, boolean updateInputWindows) {
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
        DisplayFrames displayFrames = this.mDisplayFrames;
        DisplayInfo displayInfo = this.mDisplayInfo;
        displayFrames.onDisplayInfoUpdated(displayInfo, calculateDisplayCutoutForRotation(displayInfo.rotation));
        DisplayFrames displayFrames2 = this.mDisplayFrames;
        displayFrames2.mRotation = this.mRotation;
        this.mDisplayPolicy.beginLayoutLw(displayFrames2, getConfiguration().uiMode);
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
        this.mInputMonitor.layoutInputConsumers(dw, dh);
        this.mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            this.mInputMonitor.updateInputWindowsLw(false);
        }
        this.mWmService.mH.sendEmptyMessage(41);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bitmap screenshotDisplayLocked(Bitmap.Config config) {
        if (!this.mWmService.mPolicy.isScreenOn()) {
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
        boolean inRotation = false;
        Rect frame = new Rect(0, 0, dw, dh);
        int rot = this.mDisplay.getRotation();
        if (rot == 1 || rot == 3) {
            rot = rot != 1 ? 1 : 3;
        }
        convertCropForSurfaceFlinger(frame, rot, dw, dh);
        ScreenRotationAnimation screenRotationAnimation = this.mWmService.mAnimator.getScreenRotationAnimationLocked(0);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            inRotation = true;
        }
        if (WindowManagerDebugConfig.DEBUG_SCREENSHOT && inRotation) {
            Slog.v(TAG, "Taking screenshot while rotating");
        }
        Bitmap bitmap = SurfaceControl.screenshot(frame, dw, dh, inRotation, rot);
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
                DisplayContent.this.lambda$onSeamlessRotationTimeout$25$DisplayContent((WindowState) obj);
            }
        }, true);
        if (this.mTmpWindow != null) {
            this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public /* synthetic */ void lambda$onSeamlessRotationTimeout$25$DisplayContent(WindowState w) {
        if (!w.mSeamlesslyRotated) {
            return;
        }
        this.mTmpWindow = w;
        w.setDisplayLayoutNeeded();
        w.finishSeamlessRotation(true);
        this.mWmService.markForSeamlessRotation(w, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setExitingTokensHasVisible(boolean hasVisible) {
        for (int i = this.mExitingTokens.size() - 1; i >= 0; i--) {
            this.mExitingTokens.get(i).hasVisible = hasVisible;
        }
        this.mTaskStackContainers.setExitingTokensHasVisible(hasVisible);
        this.mHomeStackContainers.setExitingTokensHasVisible(hasVisible);
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
        this.mHomeStackContainers.removeExistingAppTokensIfPossible();
    }

    @Override // com.android.server.wm.WindowContainer
    void onDescendantOverrideConfigurationChanged() {
        setLayoutNeeded();
        this.mWmService.requestTraversal();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToDisplay() {
        return this.mDisplayId == 0 ? !this.mWmService.mDisplayFrozen && this.mWmService.mDisplayEnabled && this.mWmService.mPolicy.isScreenOn() : this.mDisplayInfo.state == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToAnimate() {
        return okToDisplay() && (this.mDisplayId != 0 || this.mWmService.mPolicy.okToAnimate());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
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

    /* loaded from: classes2.dex */
    private static final class ApplySurfaceChangesTransactionState {
        boolean displayHasContent;
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
            this.preferredRefreshRate = 0.0f;
            this.preferredModeId = 0;
        }
    }

    /* loaded from: classes2.dex */
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
    /* loaded from: classes2.dex */
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
    /* loaded from: classes2.dex */
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
                this.mWmService.setDockedStackCreateStateLocked(0, null);
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
            int topChildPosition;
            if (child.getWindowConfiguration().isAlwaysOnTop() && position != Integer.MAX_VALUE) {
                Slog.w(DisplayContent.TAG, "Ignoring move of always-on-top stack=" + this + " to bottom");
                int currentPosition = this.mChildren.indexOf(child);
                super.positionChildAt(currentPosition, (int) child, false);
                return;
            }
            int targetPosition = findPositionForStack(position, child, false);
            super.positionChildAt(targetPosition, (int) child, includingParents);
            if (includingParents && targetPosition < (topChildPosition = getChildCount() - 1) && position >= topChildPosition) {
                getParent().positionChildAt(Integer.MAX_VALUE, this, true);
            }
            DisplayContent.this.setLayoutNeeded();
        }

        private int findPositionForStack(int requestedPosition, TaskStack stack, boolean adding) {
            if (stack.inPinnedWindowingMode()) {
                return Integer.MAX_VALUE;
            }
            int topChildPosition = this.mChildren.size() - 1;
            int belowAlwaysOnTopPosition = Integer.MIN_VALUE;
            int i = topChildPosition;
            while (true) {
                if (i >= 0) {
                    if (DisplayContent.this.getStacks().get(i) == stack || DisplayContent.this.getStacks().get(i).isAlwaysOnTop()) {
                        i--;
                    } else {
                        belowAlwaysOnTopPosition = i;
                        break;
                    }
                } else {
                    break;
                }
            }
            int maxPosition = Integer.MAX_VALUE;
            int minPosition = Integer.MIN_VALUE;
            if (stack.isAlwaysOnTop()) {
                if (DisplayContent.this.hasPinnedStack()) {
                    maxPosition = DisplayContent.this.getStacks().indexOf(this.mPinnedStack) - 1;
                }
                minPosition = belowAlwaysOnTopPosition != Integer.MIN_VALUE ? belowAlwaysOnTopPosition : topChildPosition;
            } else {
                maxPosition = belowAlwaysOnTopPosition != Integer.MIN_VALUE ? belowAlwaysOnTopPosition : 0;
            }
            int targetPosition = Math.max(Math.min(requestedPosition, maxPosition), minPosition);
            int prevPosition = DisplayContent.this.getStacks().indexOf(stack);
            if (targetPosition != requestedPosition) {
                if (adding || targetPosition < prevPosition) {
                    return targetPosition + 1;
                }
                return targetPosition;
            }
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
                return false;
            }
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
                    if (!token.hasVisible && !DisplayContent.this.mClosingApps.contains(token) && (!token.mIsExiting || token.isEmpty())) {
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
                TaskStack taskStack = this.mHomeStack;
                if (taskStack == null || !taskStack.isVisible() || !DisplayContent.this.mDividerControllerLocked.isMinimizedDock() || ((DisplayContent.this.mDividerControllerLocked.isHomeStackResizable() && this.mHomeStack.matchParentBounds()) || (orientation = this.mHomeStack.getOrientation()) == -2)) {
                    return -1;
                }
                return orientation;
            }
            int orientation2 = super.getOrientation();
            boolean isCar = this.mWmService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
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
            SurfaceControl surfaceControl;
            int layer = 0;
            int layerForAnimationLayer = 0;
            int layerForBoostedAnimationLayer = 0;
            int layerForHomeAnimationLayer = 0;
            for (int state = 0; state <= 2; state++) {
                for (int i = 0; i < this.mChildren.size(); i++) {
                    TaskStack s = (TaskStack) this.mChildren.get(i);
                    if ((state != 0 || s.isActivityTypeHome()) && ((state != 1 || (!s.isActivityTypeHome() && !s.isAlwaysOnTop())) && (state != 2 || s.isAlwaysOnTop()))) {
                        int layer2 = layer + 1;
                        s.assignLayer(t, layer);
                        if (s.inSplitScreenWindowingMode() && (surfaceControl = this.mSplitScreenDividerAnchor) != null) {
                            t.setLayer(surfaceControl, layer2);
                            layer2++;
                        }
                        if ((s.isTaskAnimating() || s.isAppAnimating()) && state != 2) {
                            layer = layer2 + 1;
                            layerForAnimationLayer = layer2;
                        } else {
                            layer = layer2;
                        }
                        if (state != 2) {
                            layerForBoostedAnimationLayer = layer;
                            layer++;
                        }
                    }
                }
                if (state == 0) {
                    layerForHomeAnimationLayer = layer;
                    layer++;
                }
            }
            SurfaceControl surfaceControl2 = this.mAppAnimationLayer;
            if (surfaceControl2 != null) {
                t.setLayer(surfaceControl2, layerForAnimationLayer);
            }
            SurfaceControl surfaceControl3 = this.mBoostedAppAnimationLayer;
            if (surfaceControl3 != null) {
                t.setLayer(surfaceControl3, layerForBoostedAnimationLayer);
            }
            SurfaceControl surfaceControl4 = this.mHomeAppAnimationLayer;
            if (surfaceControl4 != null) {
                t.setLayer(surfaceControl4, layerForHomeAnimationLayer);
            }
        }

        @Override // com.android.server.wm.WindowContainer
        SurfaceControl getAppAnimationLayer(@WindowContainer.AnimationLayer int animationLayer) {
            if (animationLayer != 1) {
                if (animationLayer == 2) {
                    return this.mHomeAppAnimationLayer;
                }
                return this.mAppAnimationLayer;
            }
            return this.mBoostedAppAnimationLayer;
        }

        SurfaceControl getSplitScreenDividerAnchor() {
            return this.mSplitScreenDividerAnchor;
        }

        @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
        void onParentChanged() {
            super.onParentChanged();
            if (getParent() != null) {
                this.mAppAnimationLayer = makeChildSurface(null).setName("animationLayer").build();
                this.mBoostedAppAnimationLayer = makeChildSurface(null).setName("boostedAnimationLayer").build();
                this.mHomeAppAnimationLayer = makeChildSurface(null).setName("homeAnimationLayer").build();
                this.mSplitScreenDividerAnchor = makeChildSurface(null).setName("splitScreenDividerAnchor").build();
                getPendingTransaction().show(this.mAppAnimationLayer).show(this.mBoostedAppAnimationLayer).show(this.mHomeAppAnimationLayer).show(this.mSplitScreenDividerAnchor);
                scheduleAnimation();
                return;
            }
            this.mWmService.mTransactionFactory.make().remove(this.mAppAnimationLayer).remove(this.mBoostedAppAnimationLayer).remove(this.mHomeAppAnimationLayer).remove(this.mSplitScreenDividerAnchor).apply();
            this.mAppAnimationLayer = null;
            this.mBoostedAppAnimationLayer = null;
            this.mHomeAppAnimationLayer = null;
            this.mSplitScreenDividerAnchor = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
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
                    int layer = this.mWmService.mPolicy.getWindowLayerFromTypeLw(wt.windowType, wt.mOwnerCanManageAppTokens);
                    if (needAssignIme && layer >= this.mWmService.mPolicy.getWindowLayerFromTypeLw(2012, true)) {
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
    /* loaded from: classes2.dex */
    public class NonAppWindowContainers extends DisplayChildWindowContainer<WindowToken> {
        private final Dimmer mDimmer;
        private final Predicate<WindowState> mGetOrientingWindow;
        private final String mName;
        private final Dimmer mPrimaryDimmer;
        private final Dimmer mSecondaryDimmer;
        private final Rect mTmpDimBoundsRect;
        private final Comparator<WindowToken> mWindowComparator;

        public /* synthetic */ int lambda$new$0$DisplayContent$NonAppWindowContainers(WindowToken token1, WindowToken token2) {
            return this.mWmService.mPolicy.getWindowLayerFromTypeLw(token1.windowType, token1.mOwnerCanManageAppTokens) < this.mWmService.mPolicy.getWindowLayerFromTypeLw(token2.windowType, token2.mOwnerCanManageAppTokens) ? -1 : 1;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$new$1(WindowState w) {
            int req;
            return (!w.isVisibleLw() || !w.mLegacyPolicyVisibilityAfterAnim || (req = w.mAttrs.screenOrientation) == -1 || req == 3 || req == -2) ? false : true;
        }

        NonAppWindowContainers(String name, WindowManagerService service) {
            super(service);
            this.mWindowComparator = new Comparator() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$NonAppWindowContainers$nqCymC3xR9b3qaeohnnJJpSiajc
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return DisplayContent.NonAppWindowContainers.this.lambda$new$0$DisplayContent$NonAppWindowContainers((WindowToken) obj, (WindowToken) obj2);
                }
            };
            this.mGetOrientingWindow = new Predicate() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$NonAppWindowContainers$FI_O7m2qEDfIRZef3D32AxG-rcs
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return DisplayContent.NonAppWindowContainers.lambda$new$1((WindowState) obj);
                }
            };
            this.mDimmer = new Dimmer(this);
            this.mPrimaryDimmer = new Dimmer(this);
            this.mSecondaryDimmer = new Dimmer(this);
            this.mTmpDimBoundsRect = new Rect();
            this.mName = name;
        }

        void addChild(WindowToken token) {
            addChild((NonAppWindowContainers) token, (Comparator<NonAppWindowContainers>) this.mWindowComparator);
        }

        @Override // com.android.server.wm.WindowContainer
        int getOrientation() {
            WindowManagerPolicy policy = this.mWmService.mPolicy;
            WindowState win = getWindow(this.mGetOrientingWindow);
            if (win == null) {
                DisplayContent.this.mLastWindowForcedOrientation = -1;
                boolean isUnoccluding = DisplayContent.this.mAppTransition.getAppTransition() == 23 && DisplayContent.this.mUnknownAppVisibilityController.allResolved();
                if (policy.isKeyguardShowingAndNotOccluded() || isUnoccluding) {
                    return DisplayContent.this.mLastKeyguardForcedOrientation;
                }
                return -2;
            }
            int req = win.mAttrs.screenOrientation;
            if (policy.isKeyguardHostWindow(win.mAttrs)) {
                DisplayContent.this.mLastKeyguardForcedOrientation = req;
                if (this.mWmService.mKeyguardGoingAway) {
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
        Dimmer getDimmer(int sharedId) {
            if (sharedId != 0) {
                if (sharedId == 1) {
                    return this.mSecondaryDimmer;
                }
                return this.mDimmer;
            }
            return this.mPrimaryDimmer;
        }

        @Override // com.android.server.wm.WindowContainer
        void prepareSurfaces() {
            this.mDimmer.resetDimStates();
            this.mPrimaryDimmer.resetDimStates();
            this.mSecondaryDimmer.resetDimStates();
            super.prepareSurfaces();
            getBounds(this.mTmpDimBoundsRect);
            if (this.mDimmer.updateDims(getPendingTransaction(), this.mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
            getBounds(this.mTmpDimBoundsRect);
            if (this.mPrimaryDimmer.updateDims(getPendingTransaction(), this.mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
            getBounds(this.mTmpDimBoundsRect);
            if (this.mSecondaryDimmer.updateDims(getPendingTransaction(), this.mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
        }
    }

    SurfaceControl.Builder makeSurface(SurfaceSession s) {
        return this.mWmService.makeSurfaceBuilder(s).setParent(this.mWindowingLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public SurfaceSession getSession() {
        return this.mSession;
    }

    @Override // com.android.server.wm.WindowContainer
    SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        SurfaceSession s = child != null ? child.getSession() : getSession();
        SurfaceControl.Builder b = this.mWmService.makeSurfaceBuilder(s).setContainerLayer();
        if (child == null) {
            return b;
        }
        return b.setName(child.getName()).setParent(this.mWindowingLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeOverlay() {
        return this.mWmService.makeSurfaceBuilder(this.mSession).setParent(this.mOverlayLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparentToOverlay(SurfaceControl.Transaction transaction, SurfaceControl surface) {
        transaction.reparent(surface, this.mOverlayLayer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyMagnificationSpec(MagnificationSpec spec) {
        if (spec.scale != 1.0d) {
            this.mMagnificationSpec = spec;
        } else {
            this.mMagnificationSpec = null;
        }
        updateImeParent();
        applyMagnificationSpec(getPendingTransaction(), spec);
        getPendingTransaction().apply();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reapplyMagnificationSpec() {
        if (this.mMagnificationSpec != null) {
            applyMagnificationSpec(getPendingTransaction(), this.mMagnificationSpec);
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    void onParentChanged() {
    }

    @Override // com.android.server.wm.WindowContainer
    void assignChildLayers(SurfaceControl.Transaction t) {
        this.mBelowAppWindowsContainers.assignLayer(t, 0);
        this.mHomeStackContainers.assignLayer(t, 1);
        this.mMiddleAppWindowsContainers.assignLayer(t, 2);
        this.mTaskStackContainers.assignLayer(t, 3);
        this.mAboveAppWindowsContainers.assignLayer(t, 4);
        WindowState imeTarget = this.mInputMethodTarget;
        boolean needAssignIme = true;
        if (imeTarget != null && ((imeTarget.mAppToken == null || !imeTarget.mAppToken.hasStartingWindow()) && !imeTarget.inSplitScreenWindowingMode() && !imeTarget.mToken.isAppAnimating() && imeTarget.getSurfaceControl() != null)) {
            this.mImeWindowsContainers.assignRelativeLayer(t, imeTarget.getSurfaceControl(), 1);
            needAssignIme = false;
        }
        this.mBelowAppWindowsContainers.assignChildLayers(t);
        this.mMiddleAppWindowsContainers.assignChildLayers(t, null);
        this.mHomeStackContainers.assignChildLayers(t);
        this.mTaskStackContainers.assignChildLayers(t);
        this.mAboveAppWindowsContainers.assignChildLayers(t, needAssignIme ? this.mImeWindowsContainers : null);
        this.mImeWindowsContainers.assignChildLayers(t);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignRelativeLayerForImeTargetChild(SurfaceControl.Transaction t, WindowContainer child) {
        child.assignRelativeLayer(t, this.mImeWindowsContainers.getSurfaceControl(), 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void prepareSurfaces() {
        Trace.traceBegin(32L, "prepareSurfaces");
        try {
            ScreenRotationAnimation screenRotationAnimation = this.mWmService.mAnimator.getScreenRotationAnimationLocked(this.mDisplayId);
            SurfaceControl.Transaction transaction = getPendingTransaction();
            if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                screenRotationAnimation.getEnterTransformation().getMatrix().getValues(this.mTmpFloats);
                transaction.setMatrix(this.mWindowingLayer, this.mTmpFloats[0], this.mTmpFloats[3], this.mTmpFloats[1], this.mTmpFloats[4]);
                transaction.setPosition(this.mWindowingLayer, this.mTmpFloats[2], this.mTmpFloats[5]);
                transaction.setAlpha(this.mWindowingLayer, screenRotationAnimation.getEnterTransformation().getAlpha());
            }
            super.prepareSurfaces();
            SurfaceControl.mergeToGlobalTransaction(transaction);
        } finally {
            Trace.traceEnd(32L);
        }
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
        int i = this.mDeferUpdateImeTargetCount;
        if (i == 0) {
            return;
        }
        this.mDeferUpdateImeTargetCount = i - 1;
        if (this.mDeferUpdateImeTargetCount == 0) {
            computeImeTarget(true);
        }
    }

    private boolean canUpdateImeTarget() {
        return this.mDeferUpdateImeTargetCount == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public InputMonitor getInputMonitor() {
        return this.mInputMonitor;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getLastHasContent() {
        return this.mLastHasContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener) {
        this.mPointerEventDispatcher.registerInputEventListener(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterPointerEventListener(WindowManagerPolicyConstants.PointerEventListener listener) {
        this.mPointerEventDispatcher.unregisterInputEventListener(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        prepareAppTransition(transit, alwaysKeepCurrent, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent, int flags, boolean forceOverride) {
        boolean prepared = this.mAppTransition.prepareAppTransitionLocked(transit, alwaysKeepCurrent, flags, forceOverride);
        if (prepared && okToAnimate()) {
            this.mSkipAppTransitionAnimation = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void executeAppTransition() {
        if (this.mAppTransition.isTransitionSet()) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.w(TAG, "Execute app transition: " + this.mAppTransition + ", displayId: " + this.mDisplayId + " Callers=" + Debug.getCallers(5));
            }
            this.mAppTransition.setReady();
            this.mWmService.mWindowPlacerLocked.requestTraversal();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleAnimatingStoppedAndTransition() {
        this.mAppTransition.setIdle();
        for (int i = this.mNoAnimationNotifyOnTransitionFinished.size() - 1; i >= 0; i--) {
            IBinder token = this.mNoAnimationNotifyOnTransitionFinished.get(i);
            this.mAppTransition.notifyAppTransitionFinishedLocked(token);
        }
        this.mNoAnimationNotifyOnTransitionFinished.clear();
        this.mWallpaperController.hideDeferredWallpapersIfNeeded();
        onAppTransitionDone();
        int changes = 0 | 1;
        if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
            Slog.v(TAG, "Wallpaper layer changed: assigning layers + relayout");
        }
        computeImeTarget(true);
        this.mWallpaperMayChange = true;
        this.mWmService.mFocusMayChange = true;
        this.pendingLayoutChanges |= changes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNextTransitionForward() {
        int transit = this.mAppTransition.getAppTransition();
        return transit == 6 || transit == 8 || transit == 10;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean supportsSystemDecorations() {
        return (this.mWmService.mDisplayWindowSettings.shouldShowSystemDecorsLocked(this) || (this.mDisplay.getFlags() & 64) != 0 || (this.mWmService.mForceDesktopModeOnExternalDisplays && !isUntrustedVirtualDisplay())) && this.mDisplayId != this.mWmService.mVr2dDisplayId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUntrustedVirtualDisplay() {
        return this.mDisplay.getType() == 5 && this.mDisplay.getOwnerUid() != 1000;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparentDisplayContent(WindowState win, SurfaceControl sc) {
        this.mParentWindow = win;
        this.mParentWindow.addEmbeddedDisplayContent(this);
        this.mParentSurfaceControl = sc;
        if (this.mPortalWindowHandle == null) {
            this.mPortalWindowHandle = createPortalWindowHandle(sc.toString());
        }
        getPendingTransaction().setInputWindowInfo(sc, this.mPortalWindowHandle).reparent(this.mWindowingLayer, sc).reparent(this.mOverlayLayer, sc);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getParentWindow() {
        return this.mParentWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLocation(WindowState win, int x, int y) {
        if (this.mParentWindow != win) {
            throw new IllegalArgumentException("The given window is not the parent window of this display.");
        }
        if (!this.mLocationInParentWindow.equals(x, y)) {
            this.mLocationInParentWindow.set(x, y);
            if (this.mWmService.mAccessibilityController != null) {
                this.mWmService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
            }
            notifyLocationInParentDisplayChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Point getLocationInParentWindow() {
        return this.mLocationInParentWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Point getLocationInParentDisplay() {
        Point location = new Point();
        if (this.mParentWindow != null) {
            DisplayContent dc = this;
            do {
                WindowState displayParent = dc.getParentWindow();
                location.x = (int) (location.x + displayParent.getFrameLw().left + (dc.getLocationInParentWindow().x * displayParent.mGlobalScale) + 0.5f);
                location.y = (int) (location.y + displayParent.getFrameLw().top + (dc.getLocationInParentWindow().y * displayParent.mGlobalScale) + 0.5f);
                dc = displayParent.getDisplayContent();
                if (dc == null) {
                    break;
                }
            } while (dc.getParentWindow() != null);
        }
        return location;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyLocationInParentDisplayChanged() {
        forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$CnD6O2AhxKYYNnRm_LJG-t5IpnM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).updateLocationInParentDisplayIfNeeded();
            }
        }, false);
    }

    @VisibleForTesting
    SurfaceControl getWindowingLayer() {
        return this.mWindowingLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateSystemGestureExclusion() {
        if (this.mSystemGestureExclusionListeners.getRegisteredCallbackCount() == 0) {
            return false;
        }
        Region systemGestureExclusion = Region.obtain();
        this.mSystemGestureExclusionWasRestricted = calculateSystemGestureExclusion(systemGestureExclusion, this.mSystemGestureExclusionUnrestricted);
        try {
            if (this.mSystemGestureExclusion.equals(systemGestureExclusion)) {
                return false;
            }
            this.mSystemGestureExclusion.set(systemGestureExclusion);
            Region unrestrictedOrNull = this.mSystemGestureExclusionWasRestricted ? this.mSystemGestureExclusionUnrestricted : null;
            for (int i = this.mSystemGestureExclusionListeners.beginBroadcast() - 1; i >= 0; i--) {
                try {
                    this.mSystemGestureExclusionListeners.getBroadcastItem(i).onSystemGestureExclusionChanged(this.mDisplayId, systemGestureExclusion, unrestrictedOrNull);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify SystemGestureExclusionListener", e);
                }
            }
            this.mSystemGestureExclusionListeners.finishBroadcast();
            return true;
        } finally {
            systemGestureExclusion.recycle();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public boolean calculateSystemGestureExclusion(final Region outExclusion, final Region outExclusionUnrestricted) {
        outExclusion.setEmpty();
        if (outExclusionUnrestricted != null) {
            outExclusionUnrestricted.setEmpty();
        }
        final Region unhandled = Region.obtain();
        unhandled.set(0, 0, this.mDisplayFrames.mDisplayWidth, this.mDisplayFrames.mDisplayHeight);
        final Rect leftEdge = this.mInsetsStateController.getSourceProvider(6).getSource().getFrame();
        final Rect rightEdge = this.mInsetsStateController.getSourceProvider(7).getSource().getFrame();
        final Region touchableRegion = Region.obtain();
        final Region local = Region.obtain();
        int i = this.mSystemGestureExclusionLimit;
        final int[] remainingLeftRight = {i, i};
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$eztUNCUexr-AihKglJLac_ojTcg
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.this.lambda$calculateSystemGestureExclusion$27$DisplayContent(unhandled, touchableRegion, local, remainingLeftRight, outExclusion, leftEdge, rightEdge, outExclusionUnrestricted, (WindowState) obj);
            }
        }, true);
        local.recycle();
        touchableRegion.recycle();
        unhandled.recycle();
        int i2 = remainingLeftRight[0];
        int i3 = this.mSystemGestureExclusionLimit;
        return i2 < i3 || remainingLeftRight[1] < i3;
    }

    public /* synthetic */ void lambda$calculateSystemGestureExclusion$27$DisplayContent(Region unhandled, Region touchableRegion, Region local, int[] remainingLeftRight, Region outExclusion, Rect leftEdge, Rect rightEdge, Region outExclusionUnrestricted, WindowState w) {
        if (!w.cantReceiveTouchInput() && w.isVisible()) {
            if ((w.getAttrs().flags & 16) == 0 && !unhandled.isEmpty()) {
                w.getEffectiveTouchableRegion(touchableRegion);
                touchableRegion.op(unhandled, Region.Op.INTERSECT);
                if (w.isImplicitlyExcludingAllSystemGestures()) {
                    local.set(touchableRegion);
                } else {
                    RegionUtils.rectListToRegion(w.getSystemGestureExclusion(), local);
                    local.scale(w.mGlobalScale);
                    Rect frame = w.getWindowFrames().mFrame;
                    local.translate(frame.left, frame.top);
                    local.op(touchableRegion, Region.Op.INTERSECT);
                }
                if (needsGestureExclusionRestrictions(w, this.mLastDispatchedSystemUiVisibility)) {
                    remainingLeftRight[0] = addToGlobalAndConsumeLimit(local, outExclusion, leftEdge, remainingLeftRight[0], w, 0);
                    remainingLeftRight[1] = addToGlobalAndConsumeLimit(local, outExclusion, rightEdge, remainingLeftRight[1], w, 1);
                    Region middle = Region.obtain(local);
                    middle.op(leftEdge, Region.Op.DIFFERENCE);
                    middle.op(rightEdge, Region.Op.DIFFERENCE);
                    outExclusion.op(middle, Region.Op.UNION);
                    middle.recycle();
                } else {
                    boolean loggable = needsGestureExclusionRestrictions(w, 0);
                    if (loggable) {
                        addToGlobalAndConsumeLimit(local, outExclusion, leftEdge, Integer.MAX_VALUE, w, 0);
                        addToGlobalAndConsumeLimit(local, outExclusion, rightEdge, Integer.MAX_VALUE, w, 1);
                    }
                    outExclusion.op(local, Region.Op.UNION);
                }
                if (outExclusionUnrestricted != null) {
                    outExclusionUnrestricted.op(local, Region.Op.UNION);
                }
                unhandled.op(touchableRegion, Region.Op.DIFFERENCE);
            }
        }
    }

    private static boolean needsGestureExclusionRestrictions(WindowState win, int sysUiVisibility) {
        int type = win.mAttrs.type;
        boolean stickyHideNav = (sysUiVisibility & UsbACInterface.FORMAT_II_AC3) == 4098;
        return (stickyHideNav || type == 2011 || type == 2000 || win.getActivityType() == 2) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean logsGestureExclusionRestrictions(WindowState win) {
        WindowManager.LayoutParams attrs;
        int type;
        return win.mWmService.mSystemGestureExclusionLogDebounceTimeoutMillis > 0 && (type = (attrs = win.getAttrs()).type) != 2013 && type != 3 && type != 2019 && (attrs.flags & 16) == 0 && needsGestureExclusionRestrictions(win, 0) && win.getDisplayContent().mDisplayPolicy.hasSideGestures();
    }

    private static int addToGlobalAndConsumeLimit(Region local, final Region global, Rect edge, int limit, WindowState win, int side) {
        Region r = Region.obtain(local);
        r.op(edge, Region.Op.INTERSECT);
        final int[] remaining = {limit};
        final int[] requestedExclusion = {0};
        RegionUtils.forEachRectReverse(r, new Consumer() { // from class: com.android.server.wm.-$$Lambda$DisplayContent$8i4L6sPLH8SnoxAVdVHQUeZg5VM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DisplayContent.lambda$addToGlobalAndConsumeLimit$28(remaining, requestedExclusion, global, (Rect) obj);
            }
        });
        int grantedExclusion = limit - remaining[0];
        win.setLastExclusionHeights(side, requestedExclusion[0], grantedExclusion);
        r.recycle();
        return remaining[0];
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$addToGlobalAndConsumeLimit$28(int[] remaining, int[] requestedExclusion, Region global, Rect rect) {
        if (remaining[0] <= 0) {
            return;
        }
        int height = rect.height();
        requestedExclusion[0] = requestedExclusion[0] + height;
        if (height > remaining[0]) {
            rect.top = rect.bottom - remaining[0];
        }
        remaining[0] = remaining[0] - height;
        global.op(rect, Region.Op.UNION);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerSystemGestureExclusionListener(ISystemGestureExclusionListener listener) {
        boolean changed;
        this.mSystemGestureExclusionListeners.register(listener);
        if (this.mSystemGestureExclusionListeners.getRegisteredCallbackCount() == 1) {
            changed = updateSystemGestureExclusion();
        } else {
            changed = false;
        }
        if (!changed) {
            Region unrestrictedOrNull = this.mSystemGestureExclusionWasRestricted ? this.mSystemGestureExclusionUnrestricted : null;
            try {
                listener.onSystemGestureExclusionChanged(this.mDisplayId, this.mSystemGestureExclusion, unrestrictedOrNull);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify SystemGestureExclusionListener during register", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterSystemGestureExclusionListener(ISystemGestureExclusionListener listener) {
        this.mSystemGestureExclusionListeners.unregister(listener);
    }

    private InputWindowHandle createPortalWindowHandle(String name) {
        InputWindowHandle portalWindowHandle = new InputWindowHandle((InputApplicationHandle) null, (IWindow) null, -1);
        portalWindowHandle.name = name;
        portalWindowHandle.token = new Binder();
        portalWindowHandle.layoutParamsFlags = 8388648;
        getBounds(this.mTmpBounds);
        portalWindowHandle.touchableRegion.set(this.mTmpBounds);
        portalWindowHandle.scaleFactor = 1.0f;
        portalWindowHandle.ownerPid = Process.myPid();
        portalWindowHandle.ownerUid = Process.myUid();
        portalWindowHandle.portalToDisplayId = this.mDisplayId;
        return portalWindowHandle;
    }

    public void setForwardedInsets(Insets insets) {
        if (insets == null) {
            insets = Insets.NONE;
        }
        if (this.mDisplayPolicy.getForwardedInsets().equals(insets)) {
            return;
        }
        this.mDisplayPolicy.setForwardedInsets(insets);
        setLayoutNeeded();
        this.mWmService.mWindowPlacerLocked.requestTraversal();
    }

    protected MetricsLogger getMetricsLogger() {
        if (this.mMetricsLogger == null) {
            this.mMetricsLogger = new MetricsLogger();
        }
        return this.mMetricsLogger;
    }
}
