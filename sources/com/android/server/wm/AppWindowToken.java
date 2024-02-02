package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.input.InputApplicationHandle;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowState;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AppWindowToken extends WindowToken implements WindowManagerService.AppFreezeListener {
    private static final String TAG = "WindowManager";
    private static final int Z_BOOST_BASE = 800570000;
    boolean allDrawn;
    final IApplicationToken appToken;
    boolean deferClearAllDrawn;
    boolean firstWindowDrawn;
    boolean hiddenRequested;
    boolean inPendingTransaction;
    boolean layoutConfigChanges;
    private boolean mAlwaysFocusable;
    private AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry;
    boolean mAppStopped;
    private boolean mCanTurnScreenOn;
    private boolean mClientHidden;
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
    private boolean mLastContainsDismissKeyguardWindow;
    private boolean mLastContainsShowWhenLockedWindow;
    private Task mLastParent;
    private boolean mLastSurfaceShowing;
    private long mLastTransactionSequence;
    boolean mLaunchTaskBehind;
    private Letterbox mLetterbox;
    private boolean mNeedsZBoost;
    private int mNumDrawnWindows;
    private int mNumInterestingWindows;
    private int mPendingRelaunchCount;
    private RemoteAnimationDefinition mRemoteAnimationDefinition;
    private boolean mRemovingFromDisplay;
    private boolean mReparenting;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults;
    int mRotationAnimationHint;
    boolean mShowForAllUsers;
    int mTargetSdk;
    private AppWindowThumbnail mThumbnail;
    private final Point mTmpPoint;
    private final Rect mTmpRect;
    private int mTransit;
    private int mTransitFlags;
    final boolean mVoiceInteraction;
    private boolean mWillCloseOrEnterPip;
    boolean removed;
    private boolean reportedDrawn;
    boolean reportedVisible;
    StartingData startingData;
    boolean startingDisplayed;
    boolean startingMoved;
    WindowManagerPolicy.StartingSurface startingSurface;
    WindowState startingWindow;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken(WindowManagerService service, IApplicationToken token, boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation, int rotationAnimationHint, int configChanges, boolean launchTaskBehind, boolean alwaysFocusable, AppWindowContainerController controller) {
        this(service, token, voiceInteraction, dc, fullscreen);
        setController(controller);
        this.mInputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
        this.mShowForAllUsers = showForAllUsers;
        this.mTargetSdk = targetSdk;
        this.mOrientation = orientation;
        this.layoutConfigChanges = (configChanges & 1152) != 0;
        this.mLaunchTaskBehind = launchTaskBehind;
        this.mAlwaysFocusable = alwaysFocusable;
        this.mRotationAnimationHint = rotationAnimationHint;
        setHidden(true);
        this.hiddenRequested = true;
    }

    AppWindowToken(WindowManagerService service, IApplicationToken token, boolean voiceInteraction, DisplayContent dc, boolean fillsParent) {
        super(service, token != null ? token.asBinder() : null, 2, true, dc, false);
        this.mRemovingFromDisplay = false;
        this.mLastTransactionSequence = Long.MIN_VALUE;
        this.mReportedVisibilityResults = new WindowState.UpdateReportedVisibilityResults();
        this.mFrozenBounds = new ArrayDeque<>();
        this.mFrozenMergedConfig = new ArrayDeque<>();
        this.mCanTurnScreenOn = true;
        this.mLastSurfaceShowing = true;
        this.mTmpPoint = new Point();
        this.mTmpRect = new Rect();
        this.appToken = token;
        this.mVoiceInteraction = voiceInteraction;
        this.mFillsParent = fillsParent;
        this.mInputApplicationHandle = new InputApplicationHandle(this);
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
            if (getController() != null) {
                getController().removeStartingWindow();
            }
        }
        updateReportedVisibilityLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateReportedVisibilityLocked() {
        boolean nowDrawn;
        if (this.appToken == null) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Update reported visibility: " + this);
        }
        int count = this.mChildren.size();
        this.mReportedVisibilityResults.reset();
        boolean nowVisible = false;
        for (int i = 0; i < count; i++) {
            WindowState win = (WindowState) this.mChildren.get(i);
            win.updateReportedVisibility(this.mReportedVisibilityResults);
        }
        int numInteresting = this.mReportedVisibilityResults.numInteresting;
        int numVisible = this.mReportedVisibilityResults.numVisible;
        int numDrawn = this.mReportedVisibilityResults.numDrawn;
        boolean nowGone = this.mReportedVisibilityResults.nowGone;
        if (numInteresting <= 0 || numDrawn < numInteresting) {
            nowDrawn = false;
        } else {
            nowDrawn = true;
        }
        if (numInteresting > 0 && numVisible >= numInteresting && !isHidden()) {
            nowVisible = true;
        }
        if (!nowGone) {
            if (!nowDrawn) {
                boolean nowDrawn2 = this.reportedDrawn;
                nowDrawn = nowDrawn2;
            }
            if (!nowVisible) {
                nowVisible = this.reportedVisible;
            }
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "VIS " + this + ": interesting=" + numInteresting + " visible=" + numVisible);
        }
        AppWindowContainerController controller = getController();
        if (nowDrawn != this.reportedDrawn) {
            if (nowDrawn) {
                if (controller != null) {
                    controller.reportWindowsDrawn();
                }
            } else if (controller != null) {
                controller.reportWindowsNotDrawn();
            }
            this.reportedDrawn = nowDrawn;
        }
        if (nowVisible != this.reportedVisible) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Visibility changed in " + this + ": vis=" + nowVisible);
            }
            this.reportedVisible = nowVisible;
            if (controller != null) {
                if (nowVisible) {
                    controller.reportWindowsVisible();
                } else {
                    controller.reportWindowsGone();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClientHidden() {
        return this.mClientHidden;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setClientHidden(boolean hideClient) {
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
    public boolean setVisibility(WindowManager.LayoutParams lp, boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {
        boolean delayed = false;
        this.inPendingTransaction = false;
        this.mHiddenSetFromTransferredStartingWindow = false;
        boolean visibilityChanged = false;
        if (isHidden() == visible || ((isHidden() && this.mIsExiting) || (visible && waitingForReplacement()))) {
            AccessibilityController accessibilityController = this.mService.mAccessibilityController;
            boolean changed = false;
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Changing app " + this + " hidden=" + isHidden() + " performLayout=" + performLayout);
            }
            boolean runningAppAnimation = false;
            if (transit != -1) {
                if (applyAnimationLocked(lp, transit, visible, isVoiceInteraction)) {
                    runningAppAnimation = true;
                    delayed = true;
                }
                WindowState window = findMainWindow();
                if (window != null && accessibilityController != null && getDisplayContent().getDisplayId() == 0) {
                    accessibilityController.onAppWindowTransitionLocked(window, transit);
                }
                changed = true;
            }
            int windowsCount = this.mChildren.size();
            boolean changed2 = changed;
            for (int i = 0; i < windowsCount; i++) {
                WindowState win = (WindowState) this.mChildren.get(i);
                changed2 |= win.onAppVisibilityChanged(visible, runningAppAnimation);
            }
            setHidden(!visible);
            this.hiddenRequested = !visible;
            visibilityChanged = true;
            if (!visible) {
                stopFreezingScreen(true, true);
            } else {
                if (this.startingWindow != null && !this.startingWindow.isDrawnLw()) {
                    this.startingWindow.mPolicyVisibility = false;
                    this.startingWindow.mPolicyVisibilityAfterAnim = false;
                }
                final WindowManagerService windowManagerService = this.mService;
                Objects.requireNonNull(windowManagerService);
                forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$2KrtdmjrY7Nagc4IRqzCk9gDuQU
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        WindowManagerService.this.makeWindowFreezingScreenIfNeededLocked((WindowState) obj);
                    }
                }, true);
            }
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "setVisibility: " + this + ": hidden=" + isHidden() + " hiddenRequested=" + this.hiddenRequested);
            }
            if (changed2) {
                this.mService.mInputMonitor.setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    this.mService.updateFocusedWindowLocked(3, false);
                    this.mService.mWindowPlacerLocked.performSurfacePlacement();
                }
                this.mService.mInputMonitor.updateInputWindowsLw(false);
            }
        }
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
                this.mService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(this.token);
            }
            if (visible || !isReallyAnimating()) {
                setClientHidden(!visible);
            }
            if (!this.mService.mClosingApps.contains(this) && !this.mService.mOpeningApps.contains(this)) {
                this.mService.getDefaultDisplayContentLocked().getDockedDividerController().notifyAppVisibilityChanged();
                this.mService.mTaskSnapshotController.notifyAppVisibilityChanged(this, visible);
            }
            if (isHidden() && !delayed && !this.mService.mAppTransition.isTransitionSet()) {
                SurfaceControl.openTransaction();
                for (int i3 = this.mChildren.size() - 1; i3 >= 0; i3--) {
                    ((WindowState) this.mChildren.get(i3)).mWinAnimator.hide("immediately hidden");
                }
                SurfaceControl.closeTransaction();
            }
        }
        return delayed;
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
            if (type == 1 || type == 5 || type == 12 || type == 6 || (includeStartingApp && type == 3)) {
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
        return getWindowConfiguration().canReceiveKeys() || this.mAlwaysFocusable;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public AppWindowContainerController getController() {
        WindowContainerController controller = super.getController();
        if (controller != null) {
            return (AppWindowContainerController) controller;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean isVisible() {
        return !isHidden();
    }

    @Override // com.android.server.wm.WindowToken, com.android.server.wm.WindowContainer
    void removeImmediately() {
        onRemovedFromDisplay();
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
        boolean delayed = setVisibility(null, false, -1, true, this.mVoiceInteraction);
        this.mService.mOpeningApps.remove(this);
        this.mService.mUnknownAppVisibilityController.appRemovedOrHidden(this);
        this.mService.mTaskSnapshotController.onAppRemoved(this);
        this.waitingToShow = false;
        if (this.mService.mClosingApps.contains(this)) {
            delayed = true;
        } else if (this.mService.mAppTransition.isTransitionSet()) {
            this.mService.mClosingApps.add(this);
            delayed = true;
        }
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "Removing app " + this + " delayed=" + delayed + " animation=" + getAnimation() + " animating=" + isSelfAnimating());
        }
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE || WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT) {
            Slog.v(TAG, "removeAppToken: " + this + " delayed=" + delayed + " Callers=" + Debug.getCallers(4));
        }
        if (this.startingData != null && getController() != null) {
            getController().removeStartingWindow();
        }
        if (isSelfAnimating()) {
            this.mService.mNoAnimationNotifyOnTransitionFinished.add(this.token);
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
        if (this.mService.mFocusedApp == this) {
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v(TAG, "Removing focused app token:" + this);
            }
            this.mService.mFocusedApp = null;
            this.mService.updateFocusedWindowLocked(0, true);
            this.mService.mInputMonitor.setFocusedAppLw(null);
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
        if (getController() != null) {
            getController().removeStartingWindow();
        }
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
    @Override // com.android.server.wm.WindowContainer
    public void onParentSet() {
        AnimatingAppWindowTokenRegistry animatingAppWindowTokenRegistry;
        super.onParentSet();
        Task task = getTask();
        if (!this.mReparenting) {
            if (task == null) {
                this.mService.mClosingApps.remove(this);
            } else if (this.mLastParent != null && this.mLastParent.mStack != null) {
                task.mStack.mExitingAppTokens.remove(this);
            }
        }
        TaskStack stack = getStack();
        if (this.mAnimatingAppWindowTokenRegistry != null) {
            this.mAnimatingAppWindowTokenRegistry.notifyFinished(this);
        }
        if (stack != null) {
            animatingAppWindowTokenRegistry = stack.getAnimatingAppWindowTokenRegistry();
        } else {
            animatingAppWindowTokenRegistry = null;
        }
        this.mAnimatingAppWindowTokenRegistry = animatingAppWindowTokenRegistry;
        this.mLastParent = task;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postWindowRemoveStartingWindowCleanup(WindowState win) {
        if (this.startingWindow == win) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Notify removed startingWindow " + win);
            }
            if (getController() != null) {
                getController().removeStartingWindow();
            }
        } else if (this.mChildren.size() == 0) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Nulling last startingData");
            }
            this.startingData = null;
            if (this.mHiddenSetFromTransferredStartingWindow) {
                setHidden(true);
            }
        } else if (this.mChildren.size() == 1 && this.startingSurface != null && !isRelaunching()) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Last window, removing starting window " + win);
            }
            if (getController() != null) {
                getController().removeStartingWindow();
            }
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestUpdateWallpaperIfNeeded() {
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
        if (this.mPendingRelaunchCount > 0) {
            this.mPendingRelaunchCount--;
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
        if ((type1 == 1 || type1 == 5 || type1 == 12 || type1 == 6) && type2 != 1 && type2 != 5 && type2 != 12 && type2 != 6) {
            return false;
        }
        if (type1 == 1 || type1 == 5 || type1 == 12 || type1 == 6 || !(type2 == 1 || type2 == 5 || type2 == 12 || type2 == 6)) {
            return (type1 == 3 && type2 != 3) || type1 == 3 || type2 != 3;
        }
        return true;
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
            this.mService.scheduleWindowReplacementTimeouts(this);
        }
        checkKeyguardFlagsChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(WindowState child) {
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
        this.mService.mWindowPlacerLocked.performSurfacePlacement();
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!this.mChildren.isEmpty()) {
            DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                this.mService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
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
                this.mService.registerAppFreezeListener(this);
                this.mService.mAppsFreezingScreen++;
                if (this.mService.mAppsFreezingScreen == 1) {
                    this.mService.startFreezingDisplayLocked(0, 0, getDisplayContent());
                    this.mService.mH.removeMessages(17);
                    this.mService.mH.sendEmptyMessageDelayed(17, 2000L);
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
            this.mService.unregisterAppFreezeListener(this);
            WindowManagerService windowManagerService = this.mService;
            windowManagerService.mAppsFreezingScreen--;
            this.mService.mLastFinishedFreezeSource = this;
        }
        if (unfreezeSurfaceNow) {
            if (unfrozeWindows) {
                this.mService.mWindowPlacerLocked.performSurfacePlacement();
            }
            this.mService.stopFreezingDisplayLocked();
        }
    }

    @Override // com.android.server.wm.WindowManagerService.AppFreezeListener
    public void onAppFreezeTimeout() {
        Slog.w(TAG, "Force clearing freeze: " + this);
        stopFreezingScreen(true, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void transferStartingWindowFromHiddenAboveTokenIfNeeded() {
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean transferStartingWindow(IBinder transferFrom) {
        AppWindowToken fromToken = getDisplayContent().getAppWindowToken(transferFrom);
        if (fromToken == null) {
            return false;
        }
        WindowState tStartingWindow = fromToken.startingWindow;
        if (tStartingWindow != null && fromToken.startingSurface != null) {
            this.mService.mSkipAppTransitionAnimation = true;
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Moving existing starting " + tStartingWindow + " from " + fromToken + " to " + this);
            }
            long origId = Binder.clearCallingIdentity();
            try {
                this.startingData = fromToken.startingData;
                this.startingSurface = fromToken.startingSurface;
                this.startingDisplayed = fromToken.startingDisplayed;
                fromToken.startingDisplayed = false;
                this.startingWindow = tStartingWindow;
                this.reportedVisible = fromToken.reportedVisible;
                fromToken.startingData = null;
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
                this.mService.mOpeningApps.remove(this);
                this.mService.updateFocusedWindowLocked(3, true);
                getDisplayContent().setLayoutNeeded();
                this.mService.mWindowPlacerLocked.performSurfacePlacement();
                return true;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        } else if (fromToken.startingData == null) {
            return false;
        } else {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v(TAG, "Moving pending starting from " + fromToken + " to " + this);
            }
            this.startingData = fromToken.startingData;
            fromToken.startingData = null;
            fromToken.startingMoved = true;
            if (getController() != null) {
                getController().scheduleAddStartingWindow();
            }
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
        if (!this.sendingToBottom && !this.mService.mClosingApps.contains(this)) {
            if (isVisible() || this.mService.mOpeningApps.contains(this)) {
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

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        TaskStack pinnedStack;
        Rect stackBounds;
        int prevWinMode = getWindowingMode();
        super.onConfigurationChanged(newParentConfig);
        int winMode = getWindowingMode();
        if (prevWinMode == winMode) {
            return;
        }
        if (prevWinMode != 0 && winMode == 2) {
            this.mDisplayContent.mPinnedStackControllerLocked.resetReentrySnapFraction(this);
        } else if (prevWinMode == 2 && winMode != 0 && !isHidden() && (pinnedStack = this.mDisplayContent.getPinnedStack()) != null) {
            if (pinnedStack.lastAnimatingBoundsWasToFullscreen()) {
                stackBounds = pinnedStack.mPreAnimationBounds;
            } else {
                stackBounds = this.mTmpRect;
                pinnedStack.getBounds(stackBounds);
            }
            this.mDisplayContent.mPinnedStackControllerLocked.saveReentrySnapFraction(this, stackBounds);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void checkAppWindowsReadyToShow() {
        if (this.allDrawn == this.mLastAllDrawn) {
            return;
        }
        this.mLastAllDrawn = this.allDrawn;
        if (!this.allDrawn) {
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
        if (!this.mService.mOpeningApps.contains(this) && canShowWindows()) {
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
            this.mService.mH.obtainMessage(32, this.token).sendToTarget();
            TaskStack pinnedStack = this.mDisplayContent.getPinnedStack();
            if (pinnedStack != null) {
                pinnedStack.onAllWindowsDrawn();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true);
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE && w == this.startingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen() + " allDrawn=" + this.allDrawn + " freezingScreen=" + this.mFreezingScreen);
        }
        if (!this.allDrawn || this.mFreezingScreen) {
            if (this.mLastTransactionSequence != this.mService.mTransactionSequence) {
                this.mLastTransactionSequence = this.mService.mTransactionSequence;
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
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceController + " pv=" + w.mPolicyVisibility + " mDrawState=" + winAnimator.drawStateToString() + " ph=" + w.isParentWindowHidden() + " th=" + this.hiddenRequested + " a=" + isSelfAnimating());
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
                if (getController() != null) {
                    getController().reportStartingWindowDrawn();
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
            boolean needsLetterbox = true;
            boolean surfaceReady = w.isDrawnLw() || w.mWinAnimator.mSurfaceDestroyDeferred || w.isDragResizeChanged();
            if (!w.isLetterboxedAppWindow() || !fillsParent() || !surfaceReady) {
                needsLetterbox = false;
            }
            if (needsLetterbox) {
                if (this.mLetterbox == null) {
                    this.mLetterbox = new Letterbox(new Supplier() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$clD7LvtE6cPZl3BRlaGuoR17rP4
                        @Override // java.util.function.Supplier
                        public final Object get() {
                            SurfaceControl.Builder makeChildSurface;
                            makeChildSurface = AppWindowToken.this.makeChildSurface(null);
                            return makeChildSurface;
                        }
                    });
                }
                this.mLetterbox.layout(getParent().getBounds(), w.mFrame);
            } else if (this.mLetterbox != null) {
                this.mLetterbox.hide();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLetterboxSurface(WindowState winHint) {
        WindowState w = findMainWindow();
        if (w != winHint && winHint != null && w != null) {
            return;
        }
        layoutLetterbox(winHint);
        if (this.mLetterbox != null && this.mLetterbox.needsApplySurfaceChanges()) {
            this.mLetterbox.applySurfaceChanges(this.mPendingTransaction);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean forAllWindowsUnchecked(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        return super.forAllWindows(callback, traverseTopToBottom);
    }

    @Override // com.android.server.wm.WindowToken
    AppWindowToken asAppWindowToken() {
        return this;
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
            this.mService.notifyKeyguardFlagsChanged(null);
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

    int getLowestAnimLayer() {
        for (int i = 0; i < this.mChildren.size(); i++) {
            WindowState w = (WindowState) this.mChildren.get(i);
            if (!w.mRemoved) {
                return w.mWinAnimator.mAnimLayer;
            }
        }
        return Integer.MAX_VALUE;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getHighestAnimLayerWindow(WindowState currentTarget) {
        WindowState candidate = null;
        for (int i = this.mChildren.indexOf(currentTarget); i >= 0; i--) {
            WindowState w = (WindowState) this.mChildren.get(i);
            if (!w.mRemoved && (candidate == null || w.mWinAnimator.mAnimLayer > candidate.mWinAnimator.mAnimLayer)) {
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
    public static /* synthetic */ boolean lambda$shouldUseAppThemeSnapshot$1(WindowState w) {
        return (w.mAttrs.flags & 8192) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldUseAppThemeSnapshot() {
        return this.mDisablePreviewScreenshots || forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$ErIvy8Kb9OulX2W0_mr0NNBS-KE
            public final boolean apply(Object obj) {
                return AppWindowToken.lambda$shouldUseAppThemeSnapshot$1((WindowState) obj);
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

    private boolean shouldAnimate(int transit) {
        boolean isSplitScreenPrimary = getWindowingMode() == 3;
        boolean allowSplitScreenPrimaryAnimation = transit != 13;
        return !isSplitScreenPrimary || allowSplitScreenPrimaryAnimation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean applyAnimationLocked(WindowManager.LayoutParams lp, int transit, boolean enter, boolean isVoiceInteraction) {
        AnimationAdapter adapter;
        if (this.mService.mDisableTransitionAnimation || !shouldAnimate(transit)) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "applyAnimation: transition animation is disabled or skipped. atoken=" + this);
            }
            cancelAnimation();
            return false;
        }
        Trace.traceBegin(32L, "AWT#applyAnimationLocked");
        if (okToAnimate()) {
            TaskStack stack = getStack();
            this.mTmpPoint.set(0, 0);
            this.mTmpRect.setEmpty();
            if (stack != null) {
                stack.getRelativePosition(this.mTmpPoint);
                stack.getBounds(this.mTmpRect);
                this.mTmpRect.offsetTo(0, 0);
            }
            if (this.mService.mAppTransition.getRemoteAnimationController() != null && !this.mSurfaceAnimator.isAnimationStartDelayed()) {
                adapter = this.mService.mAppTransition.getRemoteAnimationController().createAnimationAdapter(this, this.mTmpPoint, this.mTmpRect);
            } else {
                Animation a = loadAnimation(lp, transit, enter, isVoiceInteraction);
                if (a != null) {
                    AnimationAdapter adapter2 = new LocalAnimationAdapter(new WindowAnimationSpec(a, this.mTmpPoint, this.mTmpRect, this.mService.mAppTransition.canSkipFirstFrame(), this.mService.mAppTransition.getAppStackClipMode(), true), this.mService.mSurfaceAnimationRunner);
                    if (a.getZAdjustment() == 1) {
                        this.mNeedsZBoost = true;
                    }
                    this.mTransit = transit;
                    this.mTransitFlags = this.mService.mAppTransition.getTransitFlags();
                    adapter = adapter2;
                } else {
                    adapter = null;
                }
            }
            if (adapter != null) {
                startAnimation(getPendingTransaction(), adapter, true ^ isVisible());
                if (adapter.getShowWallpaper()) {
                    this.mDisplayContent.pendingLayoutChanges |= 4;
                }
            }
        } else {
            cancelAnimation();
        }
        Trace.traceEnd(32L);
        return isReallyAnimating();
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter, boolean isVoiceInteraction) {
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
        Rect surfaceInsets = null;
        if (win != null && win.inFreeformWindowingMode()) {
            freeform = true;
        }
        if (win != null) {
            if (freeform) {
                frame.set(win.mFrame);
            } else if (win.isLetterboxedAppWindow()) {
                frame.set(getTask().getBounds());
            } else if (win.isDockedResizing()) {
                frame.set(getTask().getParent().getBounds());
            } else {
                frame.set(win.mContainingFrame);
            }
            surfaceInsets = win.getAttrs().surfaceInsets;
            insets.set(win.mContentInsets);
            stableInsets.set(win.mStableInsets);
        }
        Rect surfaceInsets2 = surfaceInsets;
        boolean enter2 = this.mLaunchTaskBehind ? false : enter;
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.d(TAG, "Loading animation for app transition. transit=" + AppTransition.appTransitionToString(transit) + " enter=" + enter2 + " frame=" + frame + " insets=" + insets + " surfaceInsets=" + surfaceInsets2);
        }
        Configuration displayConfig = displayContent.getConfiguration();
        Animation a = this.mService.mAppTransition.loadAnimation(lp, transit, enter2, displayConfig.uiMode, displayConfig.orientation, frame, displayFrame, insets, surfaceInsets2, stableInsets, isVoiceInteraction, freeform, getTask().mTaskId);
        if (a != null) {
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                WindowManagerService.logWithStack(TAG, "Loaded animation " + a + " for " + this);
            }
            int containingWidth = frame.width();
            int containingHeight = frame.height();
            a.initialize(containingWidth, containingHeight, width, height);
            a.scaleCurrentDuration(this.mService.getTransitionAnimationScaleLocked());
        }
        return a;
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        return this.mAnimatingAppWindowTokenRegistry != null && this.mAnimatingAppWindowTokenRegistry.notifyAboutToFinish(this, endDeferFinishCallback);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashDestroyed(SurfaceControl.Transaction t) {
        super.onAnimationLeashDestroyed(t);
        if (this.mAnimatingAppWindowTokenRegistry != null) {
            this.mAnimatingAppWindowTokenRegistry.notifyFinished(this);
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
            t.reparent(this.mSurfaceControl, newParent.getHandle());
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
        leash.setLayer(layer);
        DisplayContent dc = getDisplayContent();
        dc.assignStackOrdering();
        if (this.mAnimatingAppWindowTokenRegistry != null) {
            this.mAnimatingAppWindowTokenRegistry.notifyStarting(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showAllWindowsLocked() {
        forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$jSO6pNpAHzC89v5XTI_Oj39kDGg
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                AppWindowToken.lambda$showAllWindowsLocked$2((WindowState) obj);
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAllWindowsLocked$2(WindowState windowState) {
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "performing show on: " + windowState);
        }
        windowState.performShowLocked();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void onAnimationFinished() {
        super.onAnimationFinished();
        this.mTransit = -1;
        boolean z = false;
        this.mTransitFlags = 0;
        this.mNeedsZBoost = false;
        setAppLayoutChanges(12, "AppWindowToken");
        clearThumbnail();
        if (isHidden() && this.hiddenRequested) {
            z = true;
        }
        setClientHidden(z);
        if (this.mService.mInputMethodTarget != null && this.mService.mInputMethodTarget.mAppToken == this) {
            getDisplayContent().computeImeTarget(true);
        }
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Animation done in " + this + ": reportedVisible=" + this.reportedVisible + " okToDisplay=" + okToDisplay() + " okToAnimate=" + okToAnimate() + " startingDisplayed=" + this.startingDisplayed);
        }
        ArrayList<WindowState> children = new ArrayList<>(this.mChildren);
        children.forEach(new Consumer() { // from class: com.android.server.wm.-$$Lambda$01bPtngJg5AqEoOWfW3rWfV7MH4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).onExitAnimationDone();
            }
        });
        this.mService.mAppTransition.notifyAppTransitionFinishedLocked(this.token);
        scheduleAnimation();
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void cancelAnimation() {
        super.cancelAnimation();
        clearThumbnail();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWaitingForTransitionStart() {
        return this.mService.mAppTransition.isTransitionSet() && (this.mService.mOpeningApps.contains(this) || this.mService.mClosingApps.contains(this));
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
        GraphicBuffer thumbnailHeader = this.mService.mAppTransition.getAppTransitionThumbnailHeader(taskId);
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
        Rect frame = win.mFrame;
        if (getTask().mUserId == this.mService.mCurrentUserId) {
            thumbnailDrawableRes = 17302262;
        } else {
            thumbnailDrawableRes = 17302331;
        }
        GraphicBuffer thumbnail = this.mService.mAppTransition.createCrossProfileAppsThumbnail(thumbnailDrawableRes, frame);
        if (thumbnail == null) {
            return;
        }
        this.mThumbnail = new AppWindowThumbnail(getPendingTransaction(), this, thumbnail);
        Animation animation = this.mService.mAppTransition.createCrossProfileAppsThumbnailAnimationLocked(win.mFrame);
        this.mThumbnail.startAnimation(getPendingTransaction(), animation, new Point(frame.left, frame.top));
    }

    private Animation loadThumbnailAnimation(GraphicBuffer thumbnailHeader) {
        DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
        WindowState win = findMainWindow();
        Rect appRect = win != null ? win.getContentFrameLw() : new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        Rect insets = win != null ? win.mContentInsets : null;
        Configuration displayConfig = this.mDisplayContent.getConfiguration();
        return this.mService.mAppTransition.createThumbnailAspectScaleAnimationLocked(appRect, insets, thumbnailHeader, getTask().mTaskId, displayConfig.uiMode, displayConfig.orientation);
    }

    private void clearThumbnail() {
        if (this.mThumbnail == null) {
            return;
        }
        this.mThumbnail.destroy();
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
            str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
        if (this.startingData != null || this.removed || this.firstWindowDrawn || this.mIsExiting) {
            pw.print(prefix);
            pw.print("startingData=");
            pw.print(this.startingData);
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
        if (getController() != null) {
            pw.print(prefix);
            pw.print("controller=");
            pw.println(getController());
        }
        if (this.mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + this.mRemovingFromDisplay);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowToken
    public void setHidden(boolean hidden) {
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
        if (show && !this.mLastSurfaceShowing) {
            this.mPendingTransaction.show(this.mSurfaceControl);
        } else if (!show && this.mLastSurfaceShowing) {
            this.mPendingTransaction.hide(this.mSurfaceControl);
        }
        if (this.mThumbnail != null) {
            this.mThumbnail.setShowing(this.mPendingTransaction, show);
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
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        writeNameToProto(proto, 1138166333441L);
        super.writeToProto(proto, 1146756268034L, trim);
        proto.write(1133871366147L, this.mLastSurfaceShowing);
        proto.write(1133871366148L, isWaitingForTransitionStart());
        proto.write(1133871366149L, isReallyAnimating());
        if (this.mThumbnail != null) {
            this.mThumbnail.writeToProto(proto, 1146756268038L);
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
        if (this.startingWindow != null) {
            this.startingWindow.writeIdentifierToProto(proto, 1146756268051L);
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
        if (this.appToken == null) {
            return;
        }
        try {
            proto.write(fieldId, this.appToken.getName());
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
        sb.append(this.mIsExiting ? " mIsExiting=" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getLetterboxInsets() {
        if (this.mLetterbox != null) {
            return this.mLetterbox.getInsets();
        }
        return new Rect();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLetterboxOverlappingWith(Rect rect) {
        return this.mLetterbox != null && this.mLetterbox.isOverlappingWith(rect);
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
        return forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$AppWindowToken$cDjsro5csMVDwRu9thAnDZqIICs
            public final boolean apply(Object obj) {
                return AppWindowToken.lambda$hasNonDefaultColorWindow$3((WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$hasNonDefaultColorWindow$3(WindowState ws) {
        return ws.mAttrs.getColorMode() != 0;
    }
}
