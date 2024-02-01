package com.android.server.wm;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.IApplicationToken;
import android.view.InputApplicationHandle;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.LocalServices;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.RemoteAnimationController;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowState;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class AppWindowToken extends WindowToken implements WindowManagerService.AppFreezeListener, ConfigurationContainerListener {
    private static final int STARTING_WINDOW_TYPE_NONE = 0;
    private static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    private static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;
    private static final String TAG = "WindowManager";
    @VisibleForTesting
    static final int Z_BOOST_BASE = 800570000;
    boolean allDrawn;
    final IApplicationToken appToken;
    boolean deferClearAllDrawn;
    boolean firstWindowDrawn;
    boolean hiddenRequested;
    boolean inPendingTransaction;
    final ComponentName mActivityComponent;
    ActivityRecord mActivityRecord;
    private final Runnable mAddStartingWindow;
    private boolean mAlwaysFocusable;
    private AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry;
    SurfaceControl mAnimationBoundsLayer;
    boolean mAppStopped;
    private boolean mCanTurnScreenOn;
    private boolean mClientHidden;
    private final ColorDisplayService.ColorTransformController mColorTransformController;
    boolean mDeferHidingClient;
    private boolean mDisablePreviewScreenshots;
    boolean mEnteringAnimation;
    private boolean mFillsParent;
    private boolean mFreezingScreen;
    ArrayDeque<Rect> mFrozenBounds;
    ArrayDeque<Configuration> mFrozenMergedConfig;
    private boolean mHiddenSetFromTransferredStartingWindow;
    final InputApplicationHandle mInputApplicationHandle;
    long mInputDispatchingTimeoutNanos;
    boolean mIsExiting;
    private boolean mLastAllDrawn;
    private AppSaturationInfo mLastAppSaturationInfo;
    private boolean mLastContainsDismissKeyguardWindow;
    private boolean mLastContainsShowWhenLockedWindow;
    private Task mLastParent;
    private boolean mLastSurfaceShowing;
    private long mLastTransactionSequence;
    boolean mLaunchTaskBehind;
    private Letterbox mLetterbox;
    boolean mNeedsAnimationBoundsLayer;
    @VisibleForTesting
    boolean mNeedsZBoost;
    private int mNumDrawnWindows;
    private int mNumInterestingWindows;
    private int mPendingRelaunchCount;
    private RemoteAnimationDefinition mRemoteAnimationDefinition;
    private boolean mRemovingFromDisplay;
    private boolean mReparenting;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults;
    int mRotationAnimationHint;
    boolean mShowForAllUsers;
    private Rect mSizeCompatBounds;
    private float mSizeCompatScale;
    StartingData mStartingData;
    int mTargetSdk;
    private AppWindowThumbnail mThumbnail;
    private final Point mTmpPoint;
    private final Rect mTmpPrevBounds;
    private final Rect mTmpRect;
    private int mTransit;
    private SurfaceControl mTransitChangeLeash;
    private int mTransitFlags;
    private final Rect mTransitStartRect;
    private boolean mUseTransferredAnimation;
    final boolean mVoiceInteraction;
    private boolean mWillCloseOrEnterPip;
    boolean removed;
    private boolean reportedDrawn;
    boolean reportedVisible;
    boolean startingDisplayed;
    boolean startingMoved;
    WindowManagerPolicy.StartingSurface startingSurface;
    WindowState startingWindow;

    public /* synthetic */ void lambda$new$1$AppWindowToken(final float[] matrix, final float[] translation) {
        this.mWmService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$-fbAn0RqOBB6FcyKBQMt-QpZ1Ec
            @Override // java.lang.Runnable
            public final void run() {
                AppWindowToken.this.lambda$new$0$AppWindowToken(matrix, translation);
            }
        });
    }

    public /* synthetic */ void lambda$new$0$AppWindowToken(float[] matrix, float[] translation) {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mLastAppSaturationInfo == null) {
                    this.mLastAppSaturationInfo = new AppSaturationInfo();
                }
                this.mLastAppSaturationInfo.setSaturation(matrix, translation);
                updateColorTransform();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken(WindowManagerService service, IApplicationToken token, ComponentName activityComponent, boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation, int rotationAnimationHint, boolean launchTaskBehind, boolean alwaysFocusable, ActivityRecord activityRecord) {
        this(service, token, activityComponent, voiceInteraction, dc, fullscreen);
        this.mActivityRecord = activityRecord;
        this.mActivityRecord.registerConfigurationChangeListener(this);
        this.mInputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
        this.mShowForAllUsers = showForAllUsers;
        this.mTargetSdk = targetSdk;
        this.mOrientation = orientation;
        this.mLaunchTaskBehind = launchTaskBehind;
        this.mAlwaysFocusable = alwaysFocusable;
        this.mRotationAnimationHint = rotationAnimationHint;
        setHidden(true);
        this.hiddenRequested = true;
        ColorDisplayService.ColorDisplayServiceInternal cds = (ColorDisplayService.ColorDisplayServiceInternal) LocalServices.getService(ColorDisplayService.ColorDisplayServiceInternal.class);
        cds.attachColorTransformController(activityRecord.packageName, activityRecord.mUserId, new WeakReference<>(this.mColorTransformController));
    }

    AppWindowToken(WindowManagerService service, IApplicationToken token, ComponentName activityComponent, boolean voiceInteraction, DisplayContent dc, boolean fillsParent) {
        super(service, token != null ? token.asBinder() : null, 2, true, dc, false);
        this.mRemovingFromDisplay = false;
        this.mLastTransactionSequence = Long.MIN_VALUE;
        this.mReportedVisibilityResults = new WindowState.UpdateReportedVisibilityResults();
        this.mFrozenBounds = new ArrayDeque<>();
        this.mFrozenMergedConfig = new ArrayDeque<>();
        this.mSizeCompatScale = 1.0f;
        this.mCanTurnScreenOn = true;
        this.mLastSurfaceShowing = true;
        this.mTransitStartRect = new Rect();
        this.mTransitChangeLeash = null;
        this.mTmpPoint = new Point();
        this.mTmpRect = new Rect();
        this.mTmpPrevBounds = new Rect();
        this.mColorTransformController = new ColorDisplayService.ColorTransformController() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$cwsF3cyeJjO4UiuaM07w8TBc698
            @Override // com.android.server.display.color.ColorDisplayService.ColorTransformController
            public final void applyAppSaturation(float[] fArr, float[] fArr2) {
                AppWindowToken.this.lambda$new$1$AppWindowToken(fArr, fArr2);
            }
        };
        this.mAddStartingWindow = new Runnable() { // from class: com.android.server.wm.AppWindowToken.1
            @Override // java.lang.Runnable
            public void run() {
                synchronized (AppWindowToken.this.mWmService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        AppWindowToken.this.mWmService.mAnimationHandler.removeCallbacks(this);
                        if (AppWindowToken.this.mStartingData == null) {
                            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                Slog.v(AppWindowToken.TAG, "startingData was nulled out before handling mAddStartingWindow: " + AppWindowToken.this);
                            }
                            return;
                        }
                        StartingData startingData = AppWindowToken.this.mStartingData;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                            Slog.v(AppWindowToken.TAG, "Add starting " + this + ": startingData=" + startingData);
                        }
                        WindowManagerPolicy.StartingSurface surface = null;
                        try {
                            surface = startingData.createStartingSurface(AppWindowToken.this);
                        } catch (Exception e) {
                            Slog.w(AppWindowToken.TAG, "Exception when adding starting window", e);
                        }
                        if (surface != null) {
                            boolean abort = false;
                            synchronized (AppWindowToken.this.mWmService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    if (!AppWindowToken.this.removed && AppWindowToken.this.mStartingData != null) {
                                        AppWindowToken.this.startingSurface = surface;
                                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW && !abort) {
                                            Slog.v(AppWindowToken.TAG, "Added starting " + AppWindowToken.this + ": startingWindow=" + AppWindowToken.this.startingWindow + " startingView=" + AppWindowToken.this.startingSurface);
                                        }
                                    }
                                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                        Slog.v(AppWindowToken.TAG, "Aborted starting " + AppWindowToken.this + ": removed=" + AppWindowToken.this.removed + " startingData=" + AppWindowToken.this.mStartingData);
                                    }
                                    AppWindowToken.this.startingWindow = null;
                                    AppWindowToken.this.mStartingData = null;
                                    abort = true;
                                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                        Slog.v(AppWindowToken.TAG, "Added starting " + AppWindowToken.this + ": startingWindow=" + AppWindowToken.this.startingWindow + " startingView=" + AppWindowToken.this.startingSurface);
                                    }
                                } finally {
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            if (abort) {
                                surface.remove();
                                return;
                            }
                            return;
                        }
                        boolean abort2 = WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
                        if (abort2) {
                            Slog.v(AppWindowToken.TAG, "Surface returned was null: " + AppWindowToken.this);
                        }
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
            }
        };
        this.appToken = token;
        this.mActivityComponent = activityComponent;
        this.mVoiceInteraction = voiceInteraction;
        this.mFillsParent = fillsParent;
        this.mInputApplicationHandle = new InputApplicationHandle(this.appToken.asBinder());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        this.firstWindowDrawn = true;
        removeDeadWindows();
        if (this.startingWindow != null) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW || WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "Finish starting " + win.mToken + ": first real window is shown, no animation");
            }
            win.cancelAnimation();
        }
        removeStartingWindow();
        updateReportedVisibilityLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateReportedVisibilityLocked() {
        if (this.appToken == null) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Update reported visibility: " + this);
        }
        int count = this.mChildren.size();
        this.mReportedVisibilityResults.reset();
        for (int i = 0; i < count; i++) {
            WindowState win = (WindowState) this.mChildren.get(i);
            win.updateReportedVisibility(this.mReportedVisibilityResults);
        }
        int numInteresting = this.mReportedVisibilityResults.numInteresting;
        int numVisible = this.mReportedVisibilityResults.numVisible;
        int numDrawn = this.mReportedVisibilityResults.numDrawn;
        boolean nowGone = this.mReportedVisibilityResults.nowGone;
        boolean nowVisible = false;
        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        if (numInteresting > 0 && numVisible >= numInteresting && !isHidden()) {
            nowVisible = true;
        }
        if (!nowGone) {
            if (!nowDrawn) {
                nowDrawn = this.reportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = this.reportedVisible;
            }
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "VIS " + this + ": interesting=" + numInteresting + " visible=" + numVisible);
        }
        if (nowDrawn != this.reportedDrawn) {
            ActivityRecord activityRecord = this.mActivityRecord;
            if (activityRecord != null) {
                activityRecord.onWindowsDrawn(nowDrawn, SystemClock.uptimeMillis());
            }
            this.reportedDrawn = nowDrawn;
        }
        if (nowVisible != this.reportedVisible) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Visibility changed in " + this + ": vis=" + nowVisible);
            }
            this.reportedVisible = nowVisible;
            if (this.mActivityRecord != null) {
                if (nowVisible) {
                    onWindowsVisible();
                } else {
                    onWindowsGone();
                }
            }
        }
    }

    private void onWindowsGone() {
        if (this.mActivityRecord == null) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Reporting gone in " + this.mActivityRecord.appToken);
        }
        this.mActivityRecord.onWindowsGone();
    }

    private void onWindowsVisible() {
        if (this.mActivityRecord == null) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Reporting visible in " + this.mActivityRecord.appToken);
        }
        this.mActivityRecord.onWindowsVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClientHidden() {
        return this.mClientHidden;
    }

    void setClientHidden(boolean hideClient) {
        if (this.mClientHidden != hideClient) {
            if (hideClient && this.mDeferHidingClient) {
                return;
            }
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "setClientHidden: " + this + " clientHidden=" + hideClient + " Callers=" + Debug.getCallers(5));
            }
            this.mClientHidden = hideClient;
            sendAppVisibilityToClients();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setVisibility(boolean visible, boolean deferHidingClient) {
        WindowState win;
        AppWindowToken focusedToken;
        AppTransition appTransition = getDisplayContent().mAppTransition;
        if (!visible && this.hiddenRequested) {
            if (!deferHidingClient && this.mDeferHidingClient) {
                this.mDeferHidingClient = deferHidingClient;
                setClientHidden(true);
                return;
            }
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "setAppVisibility(" + this.appToken + ", visible=" + visible + "): " + appTransition + " hidden=" + isHidden() + " hiddenRequested=" + this.hiddenRequested + " Callers=" + Debug.getCallers(6));
        }
        DisplayContent displayContent = getDisplayContent();
        displayContent.mOpeningApps.remove(this);
        displayContent.mClosingApps.remove(this);
        if (isInChangeTransition()) {
            clearChangeLeash(getPendingTransaction(), true);
        }
        displayContent.mChangingApps.remove(this);
        this.waitingToShow = false;
        this.hiddenRequested = !visible;
        this.mDeferHidingClient = deferHidingClient;
        if (!visible) {
            removeDeadWindows();
        } else {
            if (!appTransition.isTransitionSet() && appTransition.isReady()) {
                displayContent.mOpeningApps.add(this);
            }
            this.startingMoved = false;
            if (isHidden() || this.mAppStopped) {
                clearAllDrawn();
                if (isHidden()) {
                    this.waitingToShow = true;
                    forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$ia5zMjvf931ks869isVbSY4rjGU
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            AppWindowToken.lambda$setVisibility$2((WindowState) obj);
                        }
                    }, true);
                }
            }
            setClientHidden(false);
            requestUpdateWallpaperIfNeeded();
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                Slog.v(TAG, "No longer Stopped: " + this);
            }
            this.mAppStopped = false;
            transferStartingWindowFromHiddenAboveTokenIfNeeded();
        }
        if (okToAnimate() && appTransition.isTransitionSet()) {
            this.inPendingTransaction = true;
            if (visible) {
                displayContent.mOpeningApps.add(this);
                this.mEnteringAnimation = true;
            } else {
                displayContent.mClosingApps.add(this);
                this.mEnteringAnimation = false;
            }
            if (appTransition.getAppTransition() == 16 && (win = getDisplayContent().findFocusedWindow()) != null && (focusedToken = win.mAppToken) != null) {
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.d(TAG, "TRANSIT_TASK_OPEN_BEHIND,  adding " + focusedToken + " to mOpeningApps");
                }
                focusedToken.setHidden(true);
                displayContent.mOpeningApps.add(focusedToken);
            }
            reportDescendantOrientationChangeIfNeeded();
            return;
        }
        commitVisibility(null, visible, -1, true, this.mVoiceInteraction);
        updateReportedVisibilityLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setVisibility$2(WindowState w) {
        if (w.mWinAnimator.mDrawState == 4) {
            w.mWinAnimator.resetDrawState();
            w.resetLastContentInsets();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean commitVisibility(WindowManager.LayoutParams lp, boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {
        boolean z;
        boolean delayed = false;
        this.inPendingTransaction = false;
        this.mHiddenSetFromTransferredStartingWindow = false;
        boolean visibilityChanged = false;
        if (isHidden() != visible && ((!isHidden() || !this.mIsExiting) && (!visible || !waitingForReplacement()))) {
            z = false;
        } else {
            AccessibilityController accessibilityController = this.mWmService.mAccessibilityController;
            boolean changed = false;
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Changing app " + this + " hidden=" + isHidden() + " performLayout=" + performLayout);
            }
            boolean runningAppAnimation = false;
            if (transit != -1) {
                if (!this.mUseTransferredAnimation) {
                    if (applyAnimationLocked(lp, transit, visible, isVoiceInteraction)) {
                        runningAppAnimation = true;
                    }
                } else {
                    runningAppAnimation = isReallyAnimating();
                }
                delayed = runningAppAnimation;
                WindowState window = findMainWindow();
                if (window != null && accessibilityController != null) {
                    accessibilityController.onAppWindowTransitionLocked(window, transit);
                }
                changed = true;
            }
            int windowsCount = this.mChildren.size();
            for (int i = 0; i < windowsCount; i++) {
                WindowState win = (WindowState) this.mChildren.get(i);
                changed |= win.onAppVisibilityChanged(visible, runningAppAnimation);
            }
            setHidden(!visible);
            this.hiddenRequested = !visible;
            visibilityChanged = true;
            if (!visible) {
                stopFreezingScreen(true, true);
            } else {
                WindowState windowState = this.startingWindow;
                if (windowState != null && !windowState.isDrawnLw()) {
                    this.startingWindow.clearPolicyVisibilityFlag(1);
                    this.startingWindow.mLegacyPolicyVisibilityAfterAnim = false;
                }
                final WindowManagerService windowManagerService = this.mWmService;
                Objects.requireNonNull(windowManagerService);
                forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$2KrtdmjrY7Nagc4IRqzCk9gDuQU
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        WindowManagerService.this.makeWindowFreezingScreenIfNeededLocked((WindowState) obj);
                    }
                }, true);
            }
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "commitVisibility: " + this + ": hidden=" + isHidden() + " hiddenRequested=" + this.hiddenRequested);
            }
            if (!changed) {
                z = false;
            } else {
                getDisplayContent().getInputMonitor().setUpdateInputWindowsNeededLw();
                if (!performLayout) {
                    z = false;
                } else {
                    z = false;
                    this.mWmService.updateFocusedWindowLocked(3, false);
                    this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
                }
                getDisplayContent().getInputMonitor().updateInputWindowsLw(z);
            }
        }
        this.mUseTransferredAnimation = z;
        if (isReallyAnimating()) {
            delayed = true;
        } else {
            onAnimationFinished();
        }
        for (int i2 = this.mChildren.size() - 1; i2 >= 0 && !delayed; i2--) {
            if (((WindowState) this.mChildren.get(i2)).isSelfOrChildAnimating()) {
                delayed = true;
            }
        }
        if (visibilityChanged) {
            if (visible && !delayed) {
                this.mEnteringAnimation = true;
                this.mWmService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(this.token);
            }
            if (visible || !isReallyAnimating()) {
                setClientHidden(!visible);
            }
            if (!getDisplayContent().mClosingApps.contains(this) && !getDisplayContent().mOpeningApps.contains(this)) {
                getDisplayContent().getDockedDividerController().notifyAppVisibilityChanged();
                this.mWmService.mTaskSnapshotController.notifyAppVisibilityChanged(this, visible);
            }
            if (isHidden() && !delayed && !getDisplayContent().mAppTransition.isTransitionSet()) {
                SurfaceControl.openTransaction();
                for (int i3 = this.mChildren.size() - 1; i3 >= 0; i3--) {
                    ((WindowState) this.mChildren.get(i3)).mWinAnimator.hide("immediately hidden");
                }
                SurfaceControl.closeTransaction();
            }
            reportDescendantOrientationChangeIfNeeded();
        }
        return delayed;
    }

    private void reportDescendantOrientationChangeIfNeeded() {
        if (this.mActivityRecord.getRequestedConfigurationOrientation() == getConfiguration().orientation || getOrientationIgnoreVisibility() == -2) {
            return;
        }
        ActivityRecord activityRecord = this.mActivityRecord;
        onDescendantOrientationChanged(activityRecord.mayFreezeScreenLocked(activityRecord.app) ? this.mActivityRecord.appToken : null, this.mActivityRecord);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getTopFullscreenWindow() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            if (win != null && win.mAttrs.isFullscreen()) {
                return win;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState findMainWindow() {
        return findMainWindow(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState findMainWindow(boolean includeStartingApp) {
        WindowState candidate = null;
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowState win = (WindowState) this.mChildren.get(j);
            int type = win.mAttrs.type;
            if (type == 1 || type == 5 || type == 12 || type == 10 || type == 6 || (includeStartingApp && type == 3)) {
                if (win.mAnimatingExit) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean windowsAreFocusable() {
        if (this.mTargetSdk < 29) {
            ActivityRecord activityRecord = this.mActivityRecord;
            int pid = (activityRecord == null || activityRecord.app == null) ? 0 : this.mActivityRecord.app.getPid();
            AppWindowToken topFocusedAppOfMyProcess = this.mWmService.mRoot.mTopFocusedAppByProcess.get(Integer.valueOf(pid));
            if (topFocusedAppOfMyProcess != null && topFocusedAppOfMyProcess != this) {
                return false;
            }
        }
        return getWindowConfiguration().canReceiveKeys() || this.mAlwaysFocusable;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean isVisible() {
        return !isHidden();
    }

    @Override // com.android.server.wm.WindowToken, com.android.server.wm.WindowContainer
    void removeImmediately() {
        onRemovedFromDisplay();
        ActivityRecord activityRecord = this.mActivityRecord;
        if (activityRecord != null) {
            activityRecord.unregisterConfigurationChangeListener(this);
        }
        super.removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeIfPossible() {
        this.mIsExiting = false;
        removeAllWindowsIfPossible();
        removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean checkCompleteDeferredRemoval() {
        if (this.mIsExiting) {
            removeIfPossible();
        }
        return super.checkCompleteDeferredRemoval();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onRemovedFromDisplay() {
        if (this.mRemovingFromDisplay) {
            return;
        }
        this.mRemovingFromDisplay = true;
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "Removing app token: " + this);
        }
        boolean delayed = commitVisibility(null, false, -1, true, this.mVoiceInteraction);
        getDisplayContent().mOpeningApps.remove(this);
        getDisplayContent().mChangingApps.remove(this);
        getDisplayContent().mUnknownAppVisibilityController.appRemovedOrHidden(this);
        this.mWmService.mTaskSnapshotController.onAppRemoved(this);
        this.waitingToShow = false;
        if (getDisplayContent().mClosingApps.contains(this)) {
            delayed = true;
        } else if (getDisplayContent().mAppTransition.isTransitionSet()) {
            getDisplayContent().mClosingApps.add(this);
            delayed = true;
        }
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "Removing app " + this + " delayed=" + delayed + " animation=" + getAnimation() + " animating=" + isSelfAnimating());
        }
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE || WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT) {
            Slog.v(TAG, "removeAppToken: " + this + " delayed=" + delayed + " Callers=" + Debug.getCallers(4));
        }
        if (this.mStartingData != null) {
            removeStartingWindow();
        }
        if (isSelfAnimating()) {
            getDisplayContent().mNoAnimationNotifyOnTransitionFinished.add(this.token);
        }
        TaskStack stack = getStack();
        if (delayed && !isEmpty()) {
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE || WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT) {
                Slog.v(TAG, "removeAppToken make exiting: " + this);
            }
            if (stack != null) {
                stack.mExitingAppTokens.add(this);
            }
            this.mIsExiting = true;
        } else {
            cancelAnimation();
            if (stack != null) {
                stack.mExitingAppTokens.remove(this);
            }
            removeIfPossible();
        }
        this.removed = true;
        stopFreezingScreen(true, true);
        DisplayContent dc = getDisplayContent();
        if (dc.mFocusedApp == this) {
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v(TAG, "Removing focused app token:" + this + " displayId=" + dc.getDisplayId());
            }
            dc.setFocusedApp(null);
            this.mWmService.updateFocusedWindowLocked(0, true);
        }
        Letterbox letterbox = this.mLetterbox;
        if (letterbox != null) {
            letterbox.destroy();
            this.mLetterbox = null;
        }
        if (!delayed) {
            updateReportedVisibilityLocked();
        }
        this.mRemovingFromDisplay = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearAnimatingFlags() {
        boolean wallpaperMightChange = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            wallpaperMightChange |= win.clearAnimatingFlags();
        }
        if (wallpaperMightChange) {
            requestUpdateWallpaperIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySurfaces() {
        destroySurfaces(false);
    }

    private void destroySurfaces(boolean cleanupOnResume) {
        boolean destroyedSomething = false;
        ArrayList<WindowState> children = new ArrayList<>(this.mChildren);
        for (int i = children.size() - 1; i >= 0; i--) {
            WindowState win = children.get(i);
            destroyedSomething |= win.destroySurface(cleanupOnResume, this.mAppStopped);
        }
        if (destroyedSomething) {
            DisplayContent dc = getDisplayContent();
            dc.assignWindowLayers(true);
            updateLetterboxSurface(null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppResumed(boolean wasStopped) {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "notifyAppResumed: wasStopped=" + wasStopped + " " + this);
        }
        this.mAppStopped = false;
        setCanTurnScreenOn(true);
        if (!wasStopped) {
            destroySurfaces(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppStopped() {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "notifyAppStopped: " + this);
        }
        this.mAppStopped = true;
        destroySurfaces();
        removeStartingWindow();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearAllDrawn() {
        this.allDrawn = false;
        this.deferClearAllDrawn = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task getTask() {
        return (Task) getParent();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getStack() {
        Task task = getTask();
        if (task != null) {
            return task.mStack;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry;
        super.onParentChanged();
        Task task = getTask();
        if (!this.mReparenting) {
            if (task == null) {
                getDisplayContent().mClosingApps.remove(this);
            } else {
                Task task2 = this.mLastParent;
                if (task2 != null && task2.mStack != null) {
                    task.mStack.mExitingAppTokens.remove(this);
                }
            }
        }
        TaskStack stack = getStack();
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry2 = this.mAnimatingAppWindowTokenRegistry;
        if (animatingAppWindowTokenRegistry2 != null) {
            animatingAppWindowTokenRegistry2.notifyFinished(this);
        }
        if (stack != null) {
            animatingAppWindowTokenRegistry = stack.getAnimatingAppWindowTokenRegistry();
        } else {
            animatingAppWindowTokenRegistry = null;
        }
        this.mAnimatingAppWindowTokenRegistry = animatingAppWindowTokenRegistry;
        this.mLastParent = task;
        updateColorTransform();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postWindowRemoveStartingWindowCleanup(WindowState win) {
        if (this.startingWindow == win) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Notify removed startingWindow " + win);
            }
            removeStartingWindow();
        } else if (this.mChildren.size() == 0) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Nulling last startingData");
            }
            this.mStartingData = null;
            if (this.mHiddenSetFromTransferredStartingWindow) {
                setHidden(true);
            }
        } else if (this.mChildren.size() == 1 && this.startingSurface != null && !isRelaunching()) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Last window, removing starting window " + win);
            }
            removeStartingWindow();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeDeadWindows() {
        for (int winNdx = this.mChildren.size() - 1; winNdx >= 0; winNdx--) {
            WindowState win = (WindowState) this.mChildren.get(winNdx);
            if (win.mAppDied) {
                if (WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT || WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                    Slog.w(TAG, "removeDeadWindows: " + win);
                }
                win.mDestroying = true;
                win.removeIfPossible();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasWindowsAlive() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            if (!((WindowState) this.mChildren.get(i)).mAppDied) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillReplaceWindows(boolean animate) {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.d(TAG, "Marking app token " + this + " with replacing windows.");
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.setWillReplaceWindow(animate);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillReplaceChildWindows() {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.d(TAG, "Marking app token " + this + " with replacing child windows.");
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.setWillReplaceChildWindows();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearWillReplaceWindows() {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.d(TAG, "Resetting app token " + this + " of replacing window marks.");
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.clearWillReplaceWindow();
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.requestUpdateWallpaperIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRelaunching() {
        return this.mPendingRelaunchCount > 0;
    }

    boolean shouldFreezeBounds() {
        Task task = getTask();
        if (task == null || task.inFreeformWindowingMode()) {
            return false;
        }
        return getTask().isDragResizing();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startRelaunching() {
        if (shouldFreezeBounds()) {
            freezeBounds();
        }
        detachChildren();
        this.mPendingRelaunchCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void detachChildren() {
        SurfaceControl.openTransaction();
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.mWinAnimator.detachChildren();
        }
        SurfaceControl.closeTransaction();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishRelaunching() {
        unfreezeBounds();
        int i = this.mPendingRelaunchCount;
        if (i > 0) {
            this.mPendingRelaunchCount = i - 1;
        } else {
            checkKeyguardFlagsChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearRelaunching() {
        if (this.mPendingRelaunchCount == 0) {
            return;
        }
        unfreezeBounds();
        this.mPendingRelaunchCount = 0;
    }

    @Override // com.android.server.wm.WindowToken
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow, WindowState existingWindow) {
        int type1 = newWindow.mAttrs.type;
        int type2 = existingWindow.mAttrs.type;
        if ((type1 == 1 || type1 == 5 || type1 == 12 || type1 == 10 || type1 == 6) && type2 != 1 && type2 != 5 && type2 != 12 && type2 != 10 && type2 != 6) {
            return false;
        }
        if (type1 == 1 || type1 == 5 || type1 == 12 || type1 == 10 || type1 == 6 || !(type2 == 1 || type2 == 5 || type2 == 12 || type2 == 10 || type2 == 6)) {
            return (type1 == 3 && type2 != 3) || type1 == 3 || type2 != 3;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasStartingWindow() {
        if (this.startingDisplayed || this.mStartingData != null) {
            return true;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            if (((WindowState) getChildAt(i)).mAttrs.type == 3) {
                return true;
            }
        }
        return false;
    }

    @Override // com.android.server.wm.WindowToken
    void addWindow(WindowState w) {
        super.addWindow(w);
        boolean gotReplacementWindow = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState candidate = (WindowState) this.mChildren.get(i);
            gotReplacementWindow |= candidate.setReplacementWindowIfNeeded(w);
        }
        if (gotReplacementWindow) {
            this.mWmService.scheduleWindowReplacementTimeouts(this);
        }
        checkKeyguardFlagsChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(WindowState child) {
        if (!this.mChildren.contains(child)) {
            return;
        }
        super.removeChild((AppWindowToken) child);
        checkKeyguardFlagsChanged();
        updateLetterboxSurface(child);
    }

    private boolean waitingForReplacement() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState candidate = (WindowState) this.mChildren.get(i);
            if (candidate.waitingForReplacement()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onWindowReplacementTimeout() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            ((WindowState) this.mChildren.get(i)).onWindowReplacementTimeout();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(Task task, int position) {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i(TAG, "reparent: moving app token=" + this + " to task=" + task.mTaskId + " at " + position);
        }
        if (task == null) {
            throw new IllegalArgumentException("reparent: could not find task");
        }
        Task currentTask = getTask();
        if (task == currentTask) {
            throw new IllegalArgumentException("window token=" + this + " already child of task=" + currentTask);
        } else if (currentTask.mStack != task.mStack) {
            throw new IllegalArgumentException("window token=" + this + " current task=" + currentTask + " belongs to a different stack than " + task);
        } else {
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                Slog.i(TAG, "reParentWindowToken: removing window token=" + this + " from task=" + currentTask);
            }
            DisplayContent prevDisplayContent = getDisplayContent();
            this.mReparenting = true;
            getParent().removeChild(this);
            task.addChild(this, position);
            this.mReparenting = false;
            DisplayContent displayContent = task.getDisplayContent();
            displayContent.setLayoutNeeded();
            if (prevDisplayContent != displayContent) {
                onDisplayChanged(displayContent);
                prevDisplayContent.setLayoutNeeded();
            }
            getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override // com.android.server.wm.WindowToken, com.android.server.wm.WindowContainer
    void onDisplayChanged(DisplayContent dc) {
        Task task;
        DisplayContent prevDc = this.mDisplayContent;
        super.onDisplayChanged(dc);
        if (prevDc == null || prevDc == this.mDisplayContent) {
            return;
        }
        if (prevDc.mOpeningApps.remove(this)) {
            this.mDisplayContent.mOpeningApps.add(this);
            this.mDisplayContent.prepareAppTransition(prevDc.mAppTransition.getAppTransition(), true);
            this.mDisplayContent.executeAppTransition();
        }
        if (prevDc.mChangingApps.remove(this)) {
            clearChangeLeash(getPendingTransaction(), true);
        }
        prevDc.mClosingApps.remove(this);
        if (prevDc.mFocusedApp == this) {
            prevDc.setFocusedApp(null);
            TaskStack stack = dc.getTopStack();
            if (stack != null && (task = stack.getTopChild()) != null && task.getTopChild() == this) {
                dc.setFocusedApp(this);
            }
        }
        Letterbox letterbox = this.mLetterbox;
        if (letterbox != null) {
            letterbox.onMovedToDisplay(this.mDisplayContent.getDisplayId());
        }
    }

    private void freezeBounds() {
        Task task = getTask();
        this.mFrozenBounds.offer(new Rect(task.mPreparedFrozenBounds));
        if (task.mPreparedFrozenMergedConfig.equals(Configuration.EMPTY)) {
            this.mFrozenMergedConfig.offer(new Configuration(task.getConfiguration()));
        } else {
            this.mFrozenMergedConfig.offer(new Configuration(task.mPreparedFrozenMergedConfig));
        }
        task.mPreparedFrozenMergedConfig.unset();
    }

    private void unfreezeBounds() {
        if (this.mFrozenBounds.isEmpty()) {
            return;
        }
        this.mFrozenBounds.remove();
        if (!this.mFrozenMergedConfig.isEmpty()) {
            this.mFrozenMergedConfig.remove();
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            win.onUnfreezeBounds();
        }
        this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!this.mChildren.isEmpty()) {
            DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                this.mWmService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeReplacedWindowIfNeeded(WindowState replacement) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            if (win.removeReplacedWindowIfNeeded(replacement)) {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startFreezingScreen() {
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            WindowManagerService.logWithStack(TAG, "Set freezing of " + this.appToken + ": hidden=" + isHidden() + " freezing=" + this.mFreezingScreen + " hiddenRequested=" + this.hiddenRequested);
        }
        if (!this.hiddenRequested) {
            if (!this.mFreezingScreen) {
                this.mFreezingScreen = true;
                this.mWmService.registerAppFreezeListener(this);
                this.mWmService.mAppsFreezingScreen++;
                if (this.mWmService.mAppsFreezingScreen == 1) {
                    this.mWmService.startFreezingDisplayLocked(0, 0, getDisplayContent());
                    this.mWmService.mH.removeMessages(17);
                    this.mWmService.mH.sendEmptyMessageDelayed(17, 2000L);
                }
            }
            int count = this.mChildren.size();
            for (int i = 0; i < count; i++) {
                WindowState w = (WindowState) this.mChildren.get(i);
                w.onStartFreezingScreen();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopFreezingScreen(boolean unfreezeSurfaceNow, boolean force) {
        if (!this.mFreezingScreen) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
            Slog.v(TAG, "Clear freezing of " + this + " force=" + force);
        }
        int count = this.mChildren.size();
        boolean unfrozeWindows = false;
        for (int i = 0; i < count; i++) {
            WindowState w = (WindowState) this.mChildren.get(i);
            unfrozeWindows |= w.onStopFreezingScreen();
        }
        if (force || unfrozeWindows) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "No longer freezing: " + this);
            }
            this.mFreezingScreen = false;
            this.mWmService.unregisterAppFreezeListener(this);
            WindowManagerService windowManagerService = this.mWmService;
            windowManagerService.mAppsFreezingScreen--;
            this.mWmService.mLastFinishedFreezeSource = this;
        }
        if (unfreezeSurfaceNow) {
            if (unfrozeWindows) {
                this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
            }
            this.mWmService.stopFreezingDisplayLocked();
        }
    }

    @Override // com.android.server.wm.WindowManagerService.AppFreezeListener
    public void onAppFreezeTimeout() {
        Slog.w(TAG, "Force clearing freeze: " + this);
        stopFreezingScreen(true, true);
    }

    void transferStartingWindowFromHiddenAboveTokenIfNeeded() {
        Task task = getTask();
        for (int i = task.mChildren.size() - 1; i >= 0; i--) {
            AppWindowToken fromToken = (AppWindowToken) task.mChildren.get(i);
            if (fromToken == this) {
                return;
            }
            if (fromToken.hiddenRequested && transferStartingWindow(fromToken.token)) {
                return;
            }
        }
    }

    boolean transferStartingWindow(IBinder transferFrom) {
        AppWindowToken fromToken = getDisplayContent().getAppWindowToken(transferFrom);
        if (fromToken == null) {
            return false;
        }
        WindowState tStartingWindow = fromToken.startingWindow;
        if (tStartingWindow != null && fromToken.startingSurface != null) {
            getDisplayContent().mSkipAppTransitionAnimation = true;
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Moving existing starting " + tStartingWindow + " from " + fromToken + " to " + this);
            }
            long origId = Binder.clearCallingIdentity();
            try {
                this.mStartingData = fromToken.mStartingData;
                this.startingSurface = fromToken.startingSurface;
                this.startingDisplayed = fromToken.startingDisplayed;
                fromToken.startingDisplayed = false;
                this.startingWindow = tStartingWindow;
                this.reportedVisible = fromToken.reportedVisible;
                fromToken.mStartingData = null;
                fromToken.startingSurface = null;
                fromToken.startingWindow = null;
                fromToken.startingMoved = true;
                tStartingWindow.mToken = this;
                tStartingWindow.mAppToken = this;
                if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE || WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG, "Removing starting " + tStartingWindow + " from " + fromToken);
                }
                fromToken.removeChild(tStartingWindow);
                fromToken.postWindowRemoveStartingWindowCleanup(tStartingWindow);
                fromToken.mHiddenSetFromTransferredStartingWindow = false;
                addWindow(tStartingWindow);
                if (fromToken.allDrawn) {
                    this.allDrawn = true;
                    this.deferClearAllDrawn = fromToken.deferClearAllDrawn;
                }
                if (fromToken.firstWindowDrawn) {
                    this.firstWindowDrawn = true;
                }
                if (!fromToken.isHidden()) {
                    setHidden(false);
                    this.hiddenRequested = false;
                    this.mHiddenSetFromTransferredStartingWindow = true;
                }
                setClientHidden(fromToken.mClientHidden);
                transferAnimation(fromToken);
                this.mUseTransferredAnimation = true;
                this.mWmService.updateFocusedWindowLocked(3, true);
                getDisplayContent().setLayoutNeeded();
                this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
                return true;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        } else if (fromToken.mStartingData == null) {
            return false;
        } else {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Moving pending starting from " + fromToken + " to " + this);
            }
            this.mStartingData = fromToken.mStartingData;
            fromToken.mStartingData = null;
            fromToken.startingMoved = true;
            scheduleAddStartingWindow();
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLastWindow(WindowState win) {
        return this.mChildren.size() == 1 && this.mChildren.get(0) == win;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onAppTransitionDone() {
        this.sendingToBottom = false;
    }

    @Override // com.android.server.wm.WindowContainer
    int getOrientation(int candidate) {
        if (candidate == 3) {
            return this.mOrientation;
        }
        if (!this.sendingToBottom && !getDisplayContent().mClosingApps.contains(this)) {
            if (isVisible() || getDisplayContent().mOpeningApps.contains(this)) {
                return this.mOrientation;
            }
            return -2;
        }
        return -2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getOrientationIgnoreVisibility() {
        return this.mOrientation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean inSizeCompatMode() {
        return this.mSizeCompatBounds != null;
    }

    @Override // com.android.server.wm.WindowToken
    float getSizeCompatScale() {
        return inSizeCompatMode() ? this.mSizeCompatScale : super.getSizeCompatScale();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getResolvedOverrideBounds() {
        return getResolvedOverrideConfiguration().windowConfiguration.getBounds();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        Rect stackBounds;
        int prevWinMode = getWindowingMode();
        this.mTmpPrevBounds.set(getBounds());
        super.onConfigurationChanged(newParentConfig);
        Task task = getTask();
        Rect overrideBounds = getResolvedOverrideBounds();
        if (task != null && !overrideBounds.isEmpty() && (task.mTaskRecord == null || task.mTaskRecord.getConfiguration().orientation == newParentConfig.orientation)) {
            Rect taskBounds = task.getBounds();
            if (overrideBounds.width() != taskBounds.width() || overrideBounds.height() > taskBounds.height()) {
                calculateCompatBoundsTransformation(newParentConfig);
                updateSurfacePosition();
            } else if (this.mSizeCompatBounds != null) {
                this.mSizeCompatBounds = null;
                this.mSizeCompatScale = 1.0f;
                updateSurfacePosition();
            }
        }
        int winMode = getWindowingMode();
        if (prevWinMode == winMode) {
            return;
        }
        if (prevWinMode != 0 && winMode == 2) {
            this.mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(this);
        } else if (prevWinMode == 2 && winMode != 0 && !isHidden()) {
            TaskStack pinnedStack = this.mDisplayContent.getPinnedStack();
            if (pinnedStack != null) {
                if (pinnedStack.lastAnimatingBoundsWasToFullscreen()) {
                    stackBounds = pinnedStack.mPreAnimationBounds;
                } else {
                    stackBounds = this.mTmpRect;
                    pinnedStack.getBounds(stackBounds);
                }
                this.mDisplayContent.mPinnedStackControllerLocked.saveReentrySnapFraction(this, stackBounds);
            }
        } else if (shouldStartChangeTransition(prevWinMode, winMode)) {
            initializeChangeTransition(this.mTmpPrevBounds);
        }
    }

    private boolean shouldStartChangeTransition(int prevWinMode, int newWinMode) {
        if (this.mWmService.mDisableTransitionAnimation || !isVisible() || getDisplayContent().mAppTransition.isTransitionSet() || getSurfaceControl() == null) {
            return false;
        }
        return (prevWinMode == 5) != (newWinMode == 5);
    }

    private void initializeChangeTransition(Rect startBounds) {
        SurfaceControl.ScreenshotGraphicBuffer snapshot;
        this.mDisplayContent.prepareAppTransition(27, false, 0, false);
        this.mDisplayContent.mChangingApps.add(this);
        this.mTransitStartRect.set(startBounds);
        SurfaceControl.Builder parent = makeAnimationLeash().setParent(getAnimationLeashParent());
        SurfaceControl.Builder builder = parent.setName(getSurfaceControl() + " - interim-change-leash");
        this.mTransitChangeLeash = builder.build();
        SurfaceControl.Transaction t = getPendingTransaction();
        t.setWindowCrop(this.mTransitChangeLeash, startBounds.width(), startBounds.height());
        t.setPosition(this.mTransitChangeLeash, startBounds.left, startBounds.top);
        t.show(this.mTransitChangeLeash);
        t.reparent(getSurfaceControl(), this.mTransitChangeLeash);
        onAnimationLeashCreated(t, this.mTransitChangeLeash);
        ArraySet<Integer> activityTypes = new ArraySet<>();
        activityTypes.add(Integer.valueOf(getActivityType()));
        RemoteAnimationAdapter adapter = this.mDisplayContent.mAppTransitionController.getRemoteAnimationOverride(this, 27, activityTypes);
        if (adapter != null && !adapter.getChangeNeedsSnapshot()) {
            return;
        }
        Task task = getTask();
        if (this.mThumbnail == null && task != null && !hasCommittedReparentToAnimationLeash() && (snapshot = this.mWmService.mTaskSnapshotController.createTaskSnapshot(task, 1.0f)) != null) {
            this.mThumbnail = new AppWindowThumbnail(t, this, snapshot.getGraphicBuffer(), true);
        }
    }

    boolean isInChangeTransition() {
        return this.mTransitChangeLeash != null || AppTransition.isChangeTransit(this.mTransit);
    }

    @VisibleForTesting
    AppWindowThumbnail getThumbnail() {
        return this.mThumbnail;
    }

    private void calculateCompatBoundsTransformation(Configuration newParentConfig) {
        Rect parentAppBounds = newParentConfig.windowConfiguration.getAppBounds();
        Rect parentBounds = newParentConfig.windowConfiguration.getBounds();
        Rect viewportBounds = parentAppBounds != null ? parentAppBounds : parentBounds;
        Rect appBounds = getWindowConfiguration().getAppBounds();
        Rect contentBounds = appBounds != null ? appBounds : getResolvedOverrideBounds();
        float contentW = contentBounds.width();
        float contentH = contentBounds.height();
        float viewportW = viewportBounds.width();
        float viewportH = viewportBounds.height();
        this.mSizeCompatScale = (contentW > viewportW || contentH > viewportH) ? Math.min(viewportW / contentW, viewportH / contentH) : 1.0f;
        int offsetX = ((int) (((viewportW - (this.mSizeCompatScale * contentW)) + 1.0f) * 0.5f)) + viewportBounds.left;
        if (this.mSizeCompatBounds == null) {
            this.mSizeCompatBounds = new Rect();
        }
        this.mSizeCompatBounds.set(contentBounds);
        this.mSizeCompatBounds.offsetTo(0, 0);
        this.mSizeCompatBounds.scale(this.mSizeCompatScale);
        this.mSizeCompatBounds.top = parentBounds.top;
        this.mSizeCompatBounds.bottom += viewportBounds.top;
        this.mSizeCompatBounds.left += offsetX;
        this.mSizeCompatBounds.right += offsetX;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Rect getBounds() {
        Rect rect = this.mSizeCompatBounds;
        if (rect != null) {
            return rect;
        }
        return super.getBounds();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean matchParentBounds() {
        WindowContainer parent;
        return super.matchParentBounds() || (parent = getParent()) == null || parent.getBounds().equals(getResolvedOverrideBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void checkAppWindowsReadyToShow() {
        boolean z = this.allDrawn;
        if (z == this.mLastAllDrawn) {
            return;
        }
        this.mLastAllDrawn = z;
        if (!z) {
            return;
        }
        if (this.mFreezingScreen) {
            showAllWindowsLocked();
            stopFreezingScreen(false, true);
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.i(TAG, "Setting mOrientationChangeComplete=true because wtoken " + this + " numInteresting=" + this.mNumInterestingWindows + " numDrawn=" + this.mNumDrawnWindows);
            }
            setAppLayoutChanges(4, "checkAppWindowsReadyToShow: freezingScreen");
            return;
        }
        setAppLayoutChanges(8, "checkAppWindowsReadyToShow");
        if (!getDisplayContent().mOpeningApps.contains(this) && canShowWindows()) {
            showAllWindowsLocked();
        }
    }

    private boolean allDrawnStatesConsidered() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState child = (WindowState) this.mChildren.get(i);
            if (child.mightAffectAllDrawn() && !child.getDrawnStateEvaluated()) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateAllDrawn() {
        int numInteresting;
        if (!this.allDrawn && (numInteresting = this.mNumInterestingWindows) > 0 && allDrawnStatesConsidered() && this.mNumDrawnWindows >= numInteresting && !isRelaunching()) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "allDrawn: " + this + " interesting=" + numInteresting + " drawn=" + this.mNumDrawnWindows);
            }
            this.allDrawn = true;
            if (this.mDisplayContent != null) {
                this.mDisplayContent.setLayoutNeeded();
            }
            this.mWmService.mH.obtainMessage(32, this.token).sendToTarget();
            TaskStack pinnedStack = this.mDisplayContent.getPinnedStack();
            if (pinnedStack != null) {
                pinnedStack.onAllWindowsDrawn();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        ActivityRecord activityRecord = this.mActivityRecord;
        return activityRecord != null && activityRecord.keyDispatchingTimedOut(reason, windowPid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true);
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE && w == this.startingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen() + " allDrawn=" + this.allDrawn + " freezingScreen=" + this.mFreezingScreen);
        }
        if (!this.allDrawn || this.mFreezingScreen) {
            if (this.mLastTransactionSequence != this.mWmService.mTransactionSequence) {
                this.mLastTransactionSequence = this.mWmService.mTransactionSequence;
                this.mNumDrawnWindows = 0;
                this.startingDisplayed = false;
                this.mNumInterestingWindows = findMainWindow(false) != null ? 1 : 0;
            }
            WindowStateAnimator winAnimator = w.mWinAnimator;
            if (this.allDrawn || !w.mightAffectAllDrawn()) {
                return false;
            }
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawnLw() + ", isAnimationSet=" + isSelfAnimating());
                if (!w.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceController + " pv=" + w.isVisibleByPolicy() + " mDrawState=" + winAnimator.drawStateToString() + " ph=" + w.isParentWindowHidden() + " th=" + this.hiddenRequested + " a=" + isSelfAnimating());
                }
            }
            if (w != this.startingWindow) {
                if (!w.isInteresting()) {
                    return false;
                }
                if (findMainWindow(false) != w) {
                    this.mNumInterestingWindows++;
                }
                if (w.isDrawnLw()) {
                    this.mNumDrawnWindows++;
                    if (WindowManagerDebugConfig.DEBUG_VISIBILITY || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                        Slog.v(TAG, "tokenMayBeDrawn: " + this + " w=" + w + " numInteresting=" + this.mNumInterestingWindows + " freezingScreen=" + this.mFreezingScreen + " mAppFreezing=" + w.mAppFreezing);
                    }
                    return true;
                }
                return false;
            } else if (!w.isDrawnLw()) {
                return false;
            } else {
                ActivityRecord activityRecord = this.mActivityRecord;
                if (activityRecord != null) {
                    activityRecord.onStartingWindowDrawn(SystemClock.uptimeMillis());
                }
                this.startingDisplayed = true;
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void layoutLetterbox(WindowState winHint) {
        WindowState w = findMainWindow();
        if (w != null) {
            if (winHint != null && w != winHint) {
                return;
            }
            boolean needsLetterbox = false;
            boolean surfaceReady = w.isDrawnLw() || w.mWinAnimator.mSurfaceDestroyDeferred || w.isDragResizeChanged();
            if (surfaceReady && w.isLetterboxedAppWindow() && fillsParent()) {
                needsLetterbox = true;
            }
            if (needsLetterbox) {
                if (this.mLetterbox == null) {
                    this.mLetterbox = new Letterbox(new Supplier() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$kWpxOpxJiMwx92-ZTbqi9WL8d2s
                        @Override // java.util.function.Supplier
                        public final Object get() {
                            return AppWindowToken.this.lambda$layoutLetterbox$3$AppWindowToken();
                        }
                    });
                    this.mLetterbox.attachInput(w);
                }
                getPosition(this.mTmpPoint);
                Rect spaceToFill = (inMultiWindowMode() || getStack() == null) ? getTask().getDisplayedBounds() : getStack().getDisplayedBounds();
                this.mLetterbox.layout(spaceToFill, w.getFrameLw(), this.mTmpPoint);
                return;
            }
            Letterbox letterbox = this.mLetterbox;
            if (letterbox != null) {
                letterbox.hide();
            }
        }
    }

    public /* synthetic */ SurfaceControl.Builder lambda$layoutLetterbox$3$AppWindowToken() {
        return makeChildSurface(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLetterboxSurface(WindowState winHint) {
        WindowState w = findMainWindow();
        if (w != winHint && winHint != null && w != null) {
            return;
        }
        layoutLetterbox(winHint);
        Letterbox letterbox = this.mLetterbox;
        if (letterbox != null && letterbox.needsApplySurfaceChanges()) {
            this.mLetterbox.applySurfaceChanges(getPendingTransaction());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (this.mIsExiting && !waitingForReplacement()) {
            return false;
        }
        return forAllWindowsUnchecked(callback, traverseTopToBottom);
    }

    @Override // com.android.server.wm.WindowContainer
    void forAllAppWindows(Consumer<AppWindowToken> callback) {
        callback.accept(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean forAllWindowsUnchecked(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return super.forAllWindows(callback, traverseTopToBottom);
    }

    @Override // com.android.server.wm.WindowToken
    AppWindowToken asAppWindowToken() {
        return this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:45:0x00f9 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:46:0x00fa  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean addStartingWindow(java.lang.String r21, int r22, android.content.res.CompatibilityInfo r23, java.lang.CharSequence r24, int r25, int r26, int r27, int r28, android.os.IBinder r29, boolean r30, boolean r31, boolean r32, boolean r33, boolean r34, boolean r35) {
        /*
            Method dump skipped, instructions count: 299
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.AppWindowToken.addStartingWindow(java.lang.String, int, android.content.res.CompatibilityInfo, java.lang.CharSequence, int, int, int, int, android.os.IBinder, boolean, boolean, boolean, boolean, boolean, boolean):boolean");
    }

    private boolean createSnapshot(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Creating SnapshotStartingData");
        }
        this.mStartingData = new SnapshotStartingData(this.mWmService, snapshot);
        scheduleAddStartingWindow();
        return true;
    }

    void scheduleAddStartingWindow() {
        if (!this.mWmService.mAnimationHandler.hasCallbacks(this.mAddStartingWindow)) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Enqueueing ADD_STARTING");
            }
            this.mWmService.mAnimationHandler.postAtFrontOfQueue(this.mAddStartingWindow);
        }
    }

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning, boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents, ActivityManager.TaskSnapshot snapshot) {
        if (getDisplayContent().mAppTransition.getAppTransition() == 19) {
            return 0;
        }
        if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
            return 2;
        }
        if (taskSwitch && allowTaskSnapshot) {
            if (this.mWmService.mLowRamTaskSnapshotsAndRecents) {
                return 2;
            }
            if (snapshot == null) {
                return 0;
            }
            return (snapshotOrientationSameAsTask(snapshot) || fromRecents) ? 1 : 2;
        }
        return 0;
    }

    private boolean snapshotOrientationSameAsTask(ActivityManager.TaskSnapshot snapshot) {
        return snapshot != null && getTask().getConfiguration().orientation == snapshot.getOrientation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStartingWindow() {
        if (this.startingWindow == null) {
            if (this.mStartingData != null) {
                if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG, "Clearing startingData for token=" + this);
                }
                this.mStartingData = null;
            }
        } else if (this.mStartingData != null) {
            final WindowManagerPolicy.StartingSurface surface = this.startingSurface;
            this.mStartingData = null;
            this.startingSurface = null;
            this.startingWindow = null;
            this.startingDisplayed = false;
            if (surface == null) {
                if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG, "startingWindow was set but startingSurface==null, couldn't remove");
                    return;
                }
                return;
            }
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Schedule remove starting " + this + " startingWindow=" + this.startingWindow + " startingView=" + this.startingSurface + " Callers=" + Debug.getCallers(5));
            }
            this.mWmService.mAnimationHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$4wx593XO55AcDD3O9-1QAS0fIHY
                @Override // java.lang.Runnable
                public final void run() {
                    AppWindowToken.lambda$removeStartingWindow$4(WindowManagerPolicy.StartingSurface.this);
                }
            });
        } else if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Tried to remove starting window but startingWindow was null:" + this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$removeStartingWindow$4(WindowManagerPolicy.StartingSurface surface) {
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Removing startingView=" + surface);
        }
        try {
            surface.remove();
        } catch (Exception e) {
            Slog.w(TAG, "Exception when removing starting window", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean fillsParent() {
        return this.mFillsParent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFillsParent(boolean fillsParent) {
        this.mFillsParent = fillsParent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean containsDismissKeyguardWindow() {
        if (isRelaunching()) {
            return this.mLastContainsDismissKeyguardWindow;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            if ((((WindowState) this.mChildren.get(i)).mAttrs.flags & DumpState.DUMP_CHANGES) != 0) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean containsShowWhenLockedWindow() {
        if (isRelaunching()) {
            return this.mLastContainsShowWhenLockedWindow;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            if ((((WindowState) this.mChildren.get(i)).mAttrs.flags & DumpState.DUMP_FROZEN) != 0) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkKeyguardFlagsChanged() {
        boolean containsDismissKeyguard = containsDismissKeyguardWindow();
        boolean containsShowWhenLocked = containsShowWhenLockedWindow();
        if (containsDismissKeyguard != this.mLastContainsDismissKeyguardWindow || containsShowWhenLocked != this.mLastContainsShowWhenLockedWindow) {
            this.mWmService.notifyKeyguardFlagsChanged(null, getDisplayContent().getDisplayId());
        }
        this.mLastContainsDismissKeyguardWindow = containsDismissKeyguard;
        this.mLastContainsShowWhenLockedWindow = containsShowWhenLocked;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getImeTargetBelowWindow(WindowState w) {
        int index = this.mChildren.indexOf(w);
        if (index > 0) {
            WindowState target = (WindowState) this.mChildren.get(index - 1);
            if (target.canBeImeTarget()) {
                return target;
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getHighestAnimLayerWindow(WindowState currentTarget) {
        WindowState candidate = null;
        for (int i = this.mChildren.indexOf(currentTarget); i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            if (!w.mRemoved && candidate == null) {
                candidate = w;
            }
        }
        return candidate;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisablePreviewScreenshots(boolean disable) {
        this.mDisablePreviewScreenshots = disable;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCanTurnScreenOn(boolean canTurnScreenOn) {
        this.mCanTurnScreenOn = canTurnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canTurnScreenOn() {
        return this.mCanTurnScreenOn;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$shouldUseAppThemeSnapshot$5(WindowState w) {
        return (w.mAttrs.flags & 8192) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldUseAppThemeSnapshot() {
        return this.mDisablePreviewScreenshots || forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$Zf9XP8X2P-GWYnn5VrENXlB2pEI
            public final boolean apply(Object obj) {
                return AppWindowToken.lambda$shouldUseAppThemeSnapshot$5((WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl getAppAnimationLayer() {
        int i;
        if (isActivityTypeHome()) {
            i = 2;
        } else {
            i = needsZBoost() ? 1 : 0;
        }
        return getAppAnimationLayer(i);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl getAnimationLeashParent() {
        if (!inPinnedWindowingMode()) {
            return getAppAnimationLayer();
        }
        return getStack().getSurfaceControl();
    }

    @VisibleForTesting
    boolean shouldAnimate(int transit) {
        boolean isSplitScreenPrimary = getWindowingMode() == 3;
        boolean allowSplitScreenPrimaryAnimation = transit != 13;
        RecentsAnimationController controller = this.mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(getTask()) && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }
        return !isSplitScreenPrimary || allowSplitScreenPrimaryAnimation;
    }

    private SurfaceControl createAnimationBoundsLayer(SurfaceControl.Transaction t) {
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.i(TAG, "Creating animation bounds layer");
        }
        SurfaceControl.Builder parent = makeAnimationLeash().setParent(getAnimationLeashParent());
        SurfaceControl.Builder builder = parent.setName(getSurfaceControl() + " - animation-bounds");
        SurfaceControl boundsLayer = builder.build();
        t.show(boundsLayer);
        return boundsLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public Rect getDisplayedBounds() {
        Task task = getTask();
        if (task != null) {
            Rect overrideDisplayedBounds = task.getOverrideDisplayedBounds();
            if (!overrideDisplayedBounds.isEmpty()) {
                return overrideDisplayedBounds;
            }
        }
        return getBounds();
    }

    @VisibleForTesting
    Rect getAnimationBounds(int appStackClipMode) {
        if (appStackClipMode != 1 || getStack() == null) {
            return getTask() != null ? getTask().getBounds() : getBounds();
        }
        return getStack().getBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean applyAnimationLocked(WindowManager.LayoutParams lp, int transit, boolean enter, boolean isVoiceInteraction) {
        AnimationAdapter adapter;
        float windowCornerRadius;
        if (this.mWmService.mDisableTransitionAnimation || !shouldAnimate(transit)) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "applyAnimation: transition animation is disabled or skipped. atoken=" + this);
            }
            cancelAnimation();
            return false;
        }
        Trace.traceBegin(32L, "AWT#applyAnimationLocked");
        if (okToAnimate()) {
            AnimationAdapter thumbnailAdapter = null;
            int appStackClipMode = getDisplayContent().mAppTransition.getAppStackClipMode();
            this.mTmpRect.set(getAnimationBounds(appStackClipMode));
            this.mTmpPoint.set(this.mTmpRect.left, this.mTmpRect.top);
            this.mTmpRect.offsetTo(0, 0);
            boolean isChanging = AppTransition.isChangeTransit(transit) && enter && getDisplayContent().mChangingApps.contains(this);
            if (getDisplayContent().mAppTransition.getRemoteAnimationController() != null && !this.mSurfaceAnimator.isAnimationStartDelayed()) {
                RemoteAnimationController.RemoteAnimationRecord adapters = getDisplayContent().mAppTransition.getRemoteAnimationController().createRemoteAnimationRecord(this, this.mTmpPoint, this.mTmpRect, isChanging ? this.mTransitStartRect : null);
                adapter = adapters.mAdapter;
                thumbnailAdapter = adapters.mThumbnailAdapter;
            } else if (!isChanging) {
                this.mNeedsAnimationBoundsLayer = appStackClipMode == 0;
                Animation a = loadAnimation(lp, transit, enter, isVoiceInteraction);
                if (a != null) {
                    if (!inMultiWindowMode()) {
                        windowCornerRadius = getDisplayContent().getWindowCornerRadius();
                    } else {
                        windowCornerRadius = 0.0f;
                    }
                    adapter = new LocalAnimationAdapter(new WindowAnimationSpec(a, this.mTmpPoint, this.mTmpRect, getDisplayContent().mAppTransition.canSkipFirstFrame(), appStackClipMode, true, windowCornerRadius), this.mWmService.mSurfaceAnimationRunner);
                    if (a.getZAdjustment() == 1) {
                        this.mNeedsZBoost = true;
                    }
                    this.mTransit = transit;
                    this.mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
                } else {
                    adapter = null;
                }
            } else {
                float durationScale = this.mWmService.getTransitionAnimationScaleLocked();
                this.mTmpRect.offsetTo(this.mTmpPoint.x, this.mTmpPoint.y);
                AnimationAdapter adapter2 = new LocalAnimationAdapter(new WindowChangeAnimationSpec(this.mTransitStartRect, this.mTmpRect, getDisplayContent().getDisplayInfo(), durationScale, true, false), this.mWmService.mSurfaceAnimationRunner);
                if (this.mThumbnail != null) {
                    thumbnailAdapter = new LocalAnimationAdapter(new WindowChangeAnimationSpec(this.mTransitStartRect, this.mTmpRect, getDisplayContent().getDisplayInfo(), durationScale, true, true), this.mWmService.mSurfaceAnimationRunner);
                }
                this.mTransit = transit;
                this.mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
                adapter = adapter2;
            }
            if (adapter != null) {
                startAnimation(getPendingTransaction(), adapter, !isVisible());
                if (adapter.getShowWallpaper()) {
                    this.mDisplayContent.pendingLayoutChanges |= 4;
                }
                if (thumbnailAdapter != null) {
                    this.mThumbnail.startAnimation(getPendingTransaction(), thumbnailAdapter, !isVisible());
                }
            }
        } else {
            cancelAnimation();
        }
        Trace.traceEnd(32L);
        return isReallyAnimating();
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter, boolean isVoiceInteraction) {
        Rect surfaceInsets;
        boolean enter2;
        DisplayContent displayContent = getTask().getDisplayContent();
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        int width = displayInfo.appWidth;
        int height = displayInfo.appHeight;
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "applyAnimation: atoken=" + this);
        }
        WindowState win = findMainWindow();
        boolean freeform = false;
        Rect frame = new Rect(0, 0, width, height);
        Rect displayFrame = new Rect(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        Rect insets = new Rect();
        Rect stableInsets = new Rect();
        if (win != null && win.inFreeformWindowingMode()) {
            freeform = true;
        }
        if (win == null) {
            surfaceInsets = null;
        } else {
            if (freeform) {
                frame.set(win.getFrameLw());
            } else if (win.isLetterboxedAppWindow()) {
                frame.set(getTask().getBounds());
            } else if (win.isDockedResizing()) {
                frame.set(getTask().getParent().getBounds());
            } else {
                frame.set(win.getContainingFrame());
            }
            Rect surfaceInsets2 = win.getAttrs().surfaceInsets;
            win.getContentInsets(insets);
            win.getStableInsets(stableInsets);
            surfaceInsets = surfaceInsets2;
        }
        if (!this.mLaunchTaskBehind) {
            enter2 = enter;
        } else {
            enter2 = false;
        }
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.d(TAG, "Loading animation for app transition. transit=" + AppTransition.appTransitionToString(transit) + " enter=" + enter2 + " frame=" + frame + " insets=" + insets + " surfaceInsets=" + surfaceInsets);
        }
        Configuration displayConfig = displayContent.getConfiguration();
        Animation a = getDisplayContent().mAppTransition.loadAnimation(lp, transit, enter2, displayConfig.uiMode, displayConfig.orientation, frame, displayFrame, insets, surfaceInsets, stableInsets, isVoiceInteraction, freeform, getTask().mTaskId);
        if (a != null) {
            a.restrictDuration(BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                WindowManagerService.logWithStack(TAG, "Loaded animation " + a + " for " + this + ", duration: " + a.getDuration());
            }
            int containingWidth = frame.width();
            int containingHeight = frame.height();
            a.initialize(containingWidth, containingHeight, width, height);
            a.scaleCurrentDuration(this.mWmService.getTransitionAnimationScaleLocked());
        }
        return a;
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry = this.mAnimatingAppWindowTokenRegistry;
        return animatingAppWindowTokenRegistry != null && animatingAppWindowTokenRegistry.notifyAboutToFinish(this, endDeferFinishCallback);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        super.onAnimationLeashLost(t);
        SurfaceControl surfaceControl = this.mAnimationBoundsLayer;
        if (surfaceControl != null) {
            t.remove(surfaceControl);
            this.mAnimationBoundsLayer = null;
        }
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry = this.mAnimatingAppWindowTokenRegistry;
        if (animatingAppWindowTokenRegistry != null) {
            animatingAppWindowTokenRegistry.notifyFinished(this);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    protected void setLayer(SurfaceControl.Transaction t, int layer) {
        if (!this.mSurfaceAnimator.hasLeash()) {
            t.setLayer(this.mSurfaceControl, layer);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    protected void setRelativeLayer(SurfaceControl.Transaction t, SurfaceControl relativeTo, int layer) {
        if (!this.mSurfaceAnimator.hasLeash()) {
            t.setRelativeLayer(this.mSurfaceControl, relativeTo, layer);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    protected void reparentSurfaceControl(SurfaceControl.Transaction t, SurfaceControl newParent) {
        if (!this.mSurfaceAnimator.hasLeash()) {
            t.reparent(this.mSurfaceControl, newParent);
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        int layer;
        if (!inPinnedWindowingMode()) {
            layer = getPrefixOrderIndex();
        } else {
            layer = getParent().getPrefixOrderIndex();
        }
        if (this.mNeedsZBoost) {
            layer += Z_BOOST_BASE;
        }
        if (!this.mNeedsAnimationBoundsLayer) {
            leash.setLayer(layer);
        }
        DisplayContent dc = getDisplayContent();
        dc.assignStackOrdering();
        SurfaceControl surfaceControl = this.mTransitChangeLeash;
        if (leash == surfaceControl) {
            return;
        }
        if (surfaceControl != null) {
            clearChangeLeash(t, false);
        }
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry = this.mAnimatingAppWindowTokenRegistry;
        if (animatingAppWindowTokenRegistry != null) {
            animatingAppWindowTokenRegistry.notifyStarting(this);
        }
        if (this.mNeedsAnimationBoundsLayer) {
            this.mTmpRect.setEmpty();
            Task task = getTask();
            if (getDisplayContent().mAppTransitionController.isTransitWithinTask(getTransit(), task)) {
                task.getBounds(this.mTmpRect);
            } else {
                TaskStack stack = getStack();
                if (stack == null) {
                    return;
                }
                stack.getBounds(this.mTmpRect);
            }
            this.mAnimationBoundsLayer = createAnimationBoundsLayer(t);
            t.setWindowCrop(this.mAnimationBoundsLayer, this.mTmpRect);
            t.setLayer(this.mAnimationBoundsLayer, layer);
            t.reparent(leash, this.mAnimationBoundsLayer);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showAllWindowsLocked() {
        forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$GO_44j7HKFWrNpwWGQ4totlKXW8
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                AppWindowToken.lambda$showAllWindowsLocked$6((WindowState) obj);
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAllWindowsLocked$6(WindowState windowState) {
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "performing show on: " + windowState);
        }
        windowState.performShowLocked();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void onAnimationFinished() {
        super.onAnimationFinished();
        Trace.traceBegin(32L, "AWT#onAnimationFinished");
        this.mTransit = -1;
        boolean z = false;
        this.mTransitFlags = 0;
        this.mNeedsZBoost = false;
        this.mNeedsAnimationBoundsLayer = false;
        setAppLayoutChanges(12, "AppWindowToken");
        clearThumbnail();
        if (isHidden() && this.hiddenRequested) {
            z = true;
        }
        setClientHidden(z);
        getDisplayContent().computeImeTargetIfNeeded(this);
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Animation done in " + this + ": reportedVisible=" + this.reportedVisible + " okToDisplay=" + okToDisplay() + " okToAnimate=" + okToAnimate() + " startingDisplayed=" + this.startingDisplayed);
        }
        AppWindowThumbnail appWindowThumbnail = this.mThumbnail;
        if (appWindowThumbnail != null) {
            appWindowThumbnail.destroy();
            this.mThumbnail = null;
        }
        ArrayList<WindowState> children = new ArrayList<>(this.mChildren);
        children.forEach(new Consumer() { // from class: com.android.server.wm.-$$Lambda$01bPtngJg5AqEoOWfW3rWfV7MH4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).onExitAnimationDone();
            }
        });
        getDisplayContent().mAppTransition.notifyAppTransitionFinishedLocked(this.token);
        scheduleAnimation();
        this.mActivityRecord.onAnimationFinished();
        Trace.traceEnd(32L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean isAppAnimating() {
        return isSelfAnimating();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean isSelfAnimating() {
        return isWaitingForTransitionStart() || isReallyAnimating();
    }

    private boolean isReallyAnimating() {
        return super.isSelfAnimating();
    }

    private void clearChangeLeash(SurfaceControl.Transaction t, boolean cancel) {
        if (this.mTransitChangeLeash == null) {
            return;
        }
        if (cancel) {
            clearThumbnail();
            SurfaceControl sc = getSurfaceControl();
            SurfaceControl parentSc = getParentSurfaceControl();
            if (parentSc != null && sc != null) {
                t.reparent(sc, getParentSurfaceControl());
            }
        }
        t.hide(this.mTransitChangeLeash);
        t.remove(this.mTransitChangeLeash);
        this.mTransitChangeLeash = null;
        if (cancel) {
            onAnimationLeashLost(t);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void cancelAnimation() {
        cancelAnimationOnly();
        clearThumbnail();
        clearChangeLeash(getPendingTransaction(), true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimationOnly() {
        super.cancelAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWaitingForTransitionStart() {
        return getDisplayContent().mAppTransition.isTransitionSet() && (getDisplayContent().mOpeningApps.contains(this) || getDisplayContent().mClosingApps.contains(this) || getDisplayContent().mChangingApps.contains(this));
    }

    public int getTransit() {
        return this.mTransit;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getTransitFlags() {
        return this.mTransitFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void attachThumbnailAnimation() {
        if (!isReallyAnimating()) {
            return;
        }
        int taskId = getTask().mTaskId;
        GraphicBuffer thumbnailHeader = getDisplayContent().mAppTransition.getAppTransitionThumbnailHeader(taskId);
        if (thumbnailHeader == null) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.d(TAG, "No thumbnail header bitmap for: " + taskId);
                return;
            }
            return;
        }
        clearThumbnail();
        this.mThumbnail = new AppWindowThumbnail(getPendingTransaction(), this, thumbnailHeader);
        this.mThumbnail.startAnimation(getPendingTransaction(), loadThumbnailAnimation(thumbnailHeader));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void attachCrossProfileAppsThumbnailAnimation() {
        int thumbnailDrawableRes;
        if (!isReallyAnimating()) {
            return;
        }
        clearThumbnail();
        WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        Rect frame = win.getFrameLw();
        if (getTask().mUserId == this.mWmService.mCurrentUserId) {
            thumbnailDrawableRes = 17302285;
        } else {
            thumbnailDrawableRes = 17302367;
        }
        GraphicBuffer thumbnail = getDisplayContent().mAppTransition.createCrossProfileAppsThumbnail(thumbnailDrawableRes, frame);
        if (thumbnail == null) {
            return;
        }
        this.mThumbnail = new AppWindowThumbnail(getPendingTransaction(), this, thumbnail);
        Animation animation = getDisplayContent().mAppTransition.createCrossProfileAppsThumbnailAnimationLocked(win.getFrameLw());
        this.mThumbnail.startAnimation(getPendingTransaction(), animation, new Point(frame.left, frame.top));
    }

    private Animation loadThumbnailAnimation(GraphicBuffer thumbnailHeader) {
        DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
        WindowState win = findMainWindow();
        Rect appRect = win != null ? win.getContentFrameLw() : new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        Rect insets = win != null ? win.getContentInsets() : null;
        Configuration displayConfig = this.mDisplayContent.getConfiguration();
        return getDisplayContent().mAppTransition.createThumbnailAspectScaleAnimationLocked(appRect, insets, thumbnailHeader, getTask().mTaskId, displayConfig.uiMode, displayConfig.orientation);
    }

    private void clearThumbnail() {
        AppWindowThumbnail appWindowThumbnail = this.mThumbnail;
        if (appWindowThumbnail == null) {
            return;
        }
        appWindowThumbnail.destroy();
        this.mThumbnail = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        this.mRemoteAnimationDefinition = definition;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAnimationDefinition getRemoteAnimationDefinition() {
        return this.mRemoteAnimationDefinition;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowToken, com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        String str;
        super.dump(pw, prefix, dumpAll);
        if (this.appToken != null) {
            pw.println(prefix + "app=true mVoiceInteraction=" + this.mVoiceInteraction);
        }
        pw.println(prefix + "component=" + this.mActivityComponent.flattenToShortString());
        pw.print(prefix);
        pw.print("task=");
        pw.println(getTask());
        pw.print(prefix);
        pw.print(" mFillsParent=");
        pw.print(this.mFillsParent);
        pw.print(" mOrientation=");
        pw.println(this.mOrientation);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append("hiddenRequested=");
        sb.append(this.hiddenRequested);
        sb.append(" mClientHidden=");
        sb.append(this.mClientHidden);
        if (this.mDeferHidingClient) {
            str = " mDeferHidingClient=" + this.mDeferHidingClient;
        } else {
            str = "";
        }
        sb.append(str);
        sb.append(" reportedDrawn=");
        sb.append(this.reportedDrawn);
        sb.append(" reportedVisible=");
        sb.append(this.reportedVisible);
        pw.println(sb.toString());
        if (this.paused) {
            pw.print(prefix);
            pw.print("paused=");
            pw.println(this.paused);
        }
        if (this.mAppStopped) {
            pw.print(prefix);
            pw.print("mAppStopped=");
            pw.println(this.mAppStopped);
        }
        if (this.mNumInterestingWindows != 0 || this.mNumDrawnWindows != 0 || this.allDrawn || this.mLastAllDrawn) {
            pw.print(prefix);
            pw.print("mNumInterestingWindows=");
            pw.print(this.mNumInterestingWindows);
            pw.print(" mNumDrawnWindows=");
            pw.print(this.mNumDrawnWindows);
            pw.print(" inPendingTransaction=");
            pw.print(this.inPendingTransaction);
            pw.print(" allDrawn=");
            pw.print(this.allDrawn);
            pw.print(" lastAllDrawn=");
            pw.print(this.mLastAllDrawn);
            pw.println(")");
        }
        if (this.inPendingTransaction) {
            pw.print(prefix);
            pw.print("inPendingTransaction=");
            pw.println(this.inPendingTransaction);
        }
        if (this.mStartingData != null || this.removed || this.firstWindowDrawn || this.mIsExiting) {
            pw.print(prefix);
            pw.print("startingData=");
            pw.print(this.mStartingData);
            pw.print(" removed=");
            pw.print(this.removed);
            pw.print(" firstWindowDrawn=");
            pw.print(this.firstWindowDrawn);
            pw.print(" mIsExiting=");
            pw.println(this.mIsExiting);
        }
        if (this.startingWindow != null || this.startingSurface != null || this.startingDisplayed || this.startingMoved || this.mHiddenSetFromTransferredStartingWindow) {
            pw.print(prefix);
            pw.print("startingWindow=");
            pw.print(this.startingWindow);
            pw.print(" startingSurface=");
            pw.print(this.startingSurface);
            pw.print(" startingDisplayed=");
            pw.print(this.startingDisplayed);
            pw.print(" startingMoved=");
            pw.print(this.startingMoved);
            pw.println(" mHiddenSetFromTransferredStartingWindow=" + this.mHiddenSetFromTransferredStartingWindow);
        }
        if (!this.mFrozenBounds.isEmpty()) {
            pw.print(prefix);
            pw.print("mFrozenBounds=");
            pw.println(this.mFrozenBounds);
            pw.print(prefix);
            pw.print("mFrozenMergedConfig=");
            pw.println(this.mFrozenMergedConfig);
        }
        if (this.mPendingRelaunchCount != 0) {
            pw.print(prefix);
            pw.print("mPendingRelaunchCount=");
            pw.println(this.mPendingRelaunchCount);
        }
        if (this.mSizeCompatScale != 1.0f || this.mSizeCompatBounds != null) {
            pw.println(prefix + "mSizeCompatScale=" + this.mSizeCompatScale + " mSizeCompatBounds=" + this.mSizeCompatBounds);
        }
        if (this.mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + this.mRemovingFromDisplay);
        }
    }

    @Override // com.android.server.wm.WindowToken
    void setHidden(boolean hidden) {
        super.setHidden(hidden);
        if (hidden) {
            this.mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(this);
        }
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void prepareSurfaces() {
        boolean reallyAnimating = super.isSelfAnimating();
        boolean show = !isHidden() || reallyAnimating;
        if (this.mSurfaceControl != null) {
            if (show && !this.mLastSurfaceShowing) {
                getPendingTransaction().show(this.mSurfaceControl);
            } else if (!show && this.mLastSurfaceShowing) {
                getPendingTransaction().hide(this.mSurfaceControl);
            }
        }
        AppWindowThumbnail appWindowThumbnail = this.mThumbnail;
        if (appWindowThumbnail != null) {
            appWindowThumbnail.setShowing(getPendingTransaction(), show);
        }
        this.mLastSurfaceShowing = show;
        super.prepareSurfaces();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSurfaceShowing() {
        return this.mLastSurfaceShowing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFreezingScreen() {
        return this.mFreezingScreen;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean needsZBoost() {
        return this.mNeedsZBoost || super.needsZBoost();
    }

    @Override // com.android.server.wm.WindowToken, com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel == 2 && !isVisible()) {
            return;
        }
        long token = proto.start(fieldId);
        writeNameToProto(proto, 1138166333441L);
        super.writeToProto(proto, 1146756268034L, logLevel);
        proto.write(1133871366147L, this.mLastSurfaceShowing);
        proto.write(1133871366148L, isWaitingForTransitionStart());
        proto.write(1133871366149L, isReallyAnimating());
        AppWindowThumbnail appWindowThumbnail = this.mThumbnail;
        if (appWindowThumbnail != null) {
            appWindowThumbnail.writeToProto(proto, 1146756268038L);
        }
        proto.write(1133871366151L, this.mFillsParent);
        proto.write(1133871366152L, this.mAppStopped);
        proto.write(1133871366153L, this.hiddenRequested);
        proto.write(1133871366154L, this.mClientHidden);
        proto.write(1133871366155L, this.mDeferHidingClient);
        proto.write(1133871366156L, this.reportedDrawn);
        proto.write(1133871366157L, this.reportedVisible);
        proto.write(1120986464270L, this.mNumInterestingWindows);
        proto.write(1120986464271L, this.mNumDrawnWindows);
        proto.write(1133871366160L, this.allDrawn);
        proto.write(1133871366161L, this.mLastAllDrawn);
        proto.write(1133871366162L, this.removed);
        WindowState windowState = this.startingWindow;
        if (windowState != null) {
            windowState.writeIdentifierToProto(proto, 1146756268051L);
        }
        proto.write(1133871366164L, this.startingDisplayed);
        proto.write(1133871366165L, this.startingMoved);
        proto.write(1133871366166L, this.mHiddenSetFromTransferredStartingWindow);
        Iterator<Rect> it = this.mFrozenBounds.iterator();
        while (it.hasNext()) {
            Rect bounds = it.next();
            bounds.writeToProto(proto, 2246267895831L);
        }
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeNameToProto(ProtoOutputStream proto, long fieldId) {
        IApplicationToken iApplicationToken = this.appToken;
        if (iApplicationToken == null) {
            return;
        }
        try {
            proto.write(fieldId, iApplicationToken.getName());
        } catch (RemoteException e) {
            Slog.e(TAG, e.toString());
        }
    }

    @Override // com.android.server.wm.WindowToken
    public String toString() {
        if (this.stringName == null) {
            this.stringName = "AppWindowToken{" + Integer.toHexString(System.identityHashCode(this)) + " token=" + this.token + '}';
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.stringName);
        sb.append(this.mIsExiting ? " mIsExiting=" : "");
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getLetterboxInsets() {
        Letterbox letterbox = this.mLetterbox;
        if (letterbox != null) {
            return letterbox.getInsets();
        }
        return new Rect();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getLetterboxInnerBounds(Rect outBounds) {
        Letterbox letterbox = this.mLetterbox;
        if (letterbox != null) {
            outBounds.set(letterbox.getInnerFrame());
        } else {
            outBounds.setEmpty();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLetterboxOverlappingWith(Rect rect) {
        Letterbox letterbox = this.mLetterbox;
        return letterbox != null && letterbox.isOverlappingWith(rect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        this.mWillCloseOrEnterPip = willCloseOrEnterPip;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClosingOrEnteringPip() {
        return (isAnimating() && this.hiddenRequested) || this.mWillCloseOrEnterPip;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowWindows() {
        return this.allDrawn && !(isReallyAnimating() && hasNonDefaultColorWindow());
    }

    private boolean hasNonDefaultColorWindow() {
        return forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$fPUApbLk_vYcjY_mIHRDEOCqbZU
            public final boolean apply(Object obj) {
                return AppWindowToken.lambda$hasNonDefaultColorWindow$7((WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$hasNonDefaultColorWindow$7(WindowState ws) {
        return ws.mAttrs.getColorMode() != 0;
    }

    private void updateColorTransform() {
        if (this.mSurfaceControl != null && this.mLastAppSaturationInfo != null) {
            getPendingTransaction().setColorTransform(this.mSurfaceControl, this.mLastAppSaturationInfo.mMatrix, this.mLastAppSaturationInfo.mTranslation);
            this.mWmService.scheduleAnimationLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class AppSaturationInfo {
        float[] mMatrix;
        float[] mTranslation;

        private AppSaturationInfo() {
            this.mMatrix = new float[9];
            this.mTranslation = new float[3];
        }

        void setSaturation(float[] matrix, float[] translation) {
            float[] fArr = this.mMatrix;
            System.arraycopy(matrix, 0, fArr, 0, fArr.length);
            float[] fArr2 = this.mTranslation;
            System.arraycopy(translation, 0, fArr2, 0, fArr2.length);
        }
    }
}
