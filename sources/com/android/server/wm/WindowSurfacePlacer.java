package com.android.server.wm;

import android.os.Debug;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.animation.Animation;
import com.android.internal.annotations.VisibleForTesting;
import java.io.PrintWriter;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowSurfacePlacer {
    static final int SET_FORCE_HIDING_CHANGED = 4;
    static final int SET_ORIENTATION_CHANGE_COMPLETE = 8;
    static final int SET_UPDATE_ROTATION = 1;
    static final int SET_WALLPAPER_ACTION_PENDING = 16;
    static final int SET_WALLPAPER_MAY_CHANGE = 2;
    private static final String TAG = "WindowManager";
    private int mLayoutRepeatCount;
    private final WindowManagerService mService;
    private boolean mTraversalScheduled;
    private final WallpaperController mWallpaperControllerLocked;
    private boolean mInLayout = false;
    private int mDeferDepth = 0;
    private final LayerAndToken mTmpLayerAndToken = new LayerAndToken();
    private final SparseIntArray mTempTransitionReasons = new SparseIntArray();
    private final Runnable mPerformSurfacePlacement = new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowSurfacePlacer$4Hbamt-LFcbu8AoZBoOZN_LveKQ
        @Override // java.lang.Runnable
        public final void run() {
            WindowSurfacePlacer.lambda$new$0(WindowSurfacePlacer.this);
        }
    };

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class LayerAndToken {
        public int layer;
        public AppWindowToken token;

        private LayerAndToken() {
        }
    }

    public WindowSurfacePlacer(WindowManagerService service) {
        this.mService = service;
        this.mWallpaperControllerLocked = this.mService.mRoot.mWallpaperController;
    }

    public static /* synthetic */ void lambda$new$0(WindowSurfacePlacer windowSurfacePlacer) {
        synchronized (windowSurfacePlacer.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                windowSurfacePlacer.performSurfacePlacement();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferLayout() {
        this.mDeferDepth++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueLayout() {
        this.mDeferDepth--;
        if (this.mDeferDepth <= 0) {
            performSurfacePlacement();
        }
    }

    boolean isLayoutDeferred() {
        return this.mDeferDepth > 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void performSurfacePlacement() {
        performSurfacePlacement(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void performSurfacePlacement(boolean force) {
        if (this.mDeferDepth > 0 && !force) {
            return;
        }
        int loopCount = 6;
        do {
            this.mTraversalScheduled = false;
            performSurfacePlacementLoop();
            this.mService.mAnimationHandler.removeCallbacks(this.mPerformSurfacePlacement);
            loopCount--;
            if (!this.mTraversalScheduled) {
                break;
            }
        } while (loopCount > 0);
        this.mService.mRoot.mWallpaperActionPending = false;
    }

    private void performSurfacePlacementLoop() {
        if (this.mInLayout) {
            if (WindowManagerDebugConfig.DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers=" + Debug.getCallers(3));
        } else if (this.mService.mWaitingForConfig || !this.mService.mDisplayReady) {
        } else {
            Trace.traceBegin(32L, "wmLayout");
            this.mInLayout = true;
            boolean recoveringMemory = false;
            if (!this.mService.mForceRemoves.isEmpty()) {
                while (!this.mService.mForceRemoves.isEmpty()) {
                    WindowState ws = this.mService.mForceRemoves.remove(0);
                    Slog.i(TAG, "Force removing: " + ws);
                    ws.removeImmediately();
                }
                Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
                Object tmp = new Object();
                synchronized (tmp) {
                    try {
                        tmp.wait(250L);
                    } catch (InterruptedException e) {
                    }
                }
                recoveringMemory = true;
            }
            try {
                this.mService.mRoot.performSurfacePlacement(recoveringMemory);
                this.mInLayout = false;
                if (this.mService.mRoot.isLayoutNeeded()) {
                    int i = this.mLayoutRepeatCount + 1;
                    this.mLayoutRepeatCount = i;
                    if (i < 6) {
                        requestTraversal();
                    } else {
                        Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                        this.mLayoutRepeatCount = 0;
                    }
                } else {
                    this.mLayoutRepeatCount = 0;
                }
                if (this.mService.mWindowsChanged && !this.mService.mWindowChangeListeners.isEmpty()) {
                    this.mService.mH.removeMessages(19);
                    this.mService.mH.sendEmptyMessage(19);
                }
            } catch (RuntimeException e2) {
                this.mInLayout = false;
                Slog.wtf(TAG, "Unhandled exception while laying out windows", e2);
            }
            Trace.traceEnd(32L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void debugLayoutRepeats(String msg, int pendingLayoutChanges) {
        if (this.mLayoutRepeatCount >= 4) {
            Slog.v(TAG, "Layouts looping: " + msg + ", mPendingLayoutChanges = 0x" + Integer.toHexString(pendingLayoutChanges));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInLayout() {
        return this.mInLayout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int handleAppTransitionReadyLocked() {
        AppWindowToken appWindowToken;
        AppWindowToken topClosingApp;
        AppWindowToken topOpeningApp;
        int flags;
        int appsCount = this.mService.mOpeningApps.size();
        if (transitionGoodToGo(appsCount, this.mTempTransitionReasons)) {
            Trace.traceBegin(32L, "AppTransitionReady");
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "**** GOOD TO GO");
            }
            int transit = this.mService.mAppTransition.getAppTransition();
            if (this.mService.mSkipAppTransitionAnimation && !AppTransition.isKeyguardGoingAwayTransit(transit)) {
                transit = -1;
            }
            this.mService.mSkipAppTransitionAnimation = false;
            this.mService.mNoAnimationNotifyOnTransitionFinished.clear();
            this.mService.mH.removeMessages(13);
            DisplayContent displayContent = this.mService.getDefaultDisplayContentLocked();
            this.mService.mRoot.mWallpaperMayChange = false;
            for (int i = 0; i < appsCount; i++) {
                AppWindowToken wtoken = this.mService.mOpeningApps.valueAt(i);
                wtoken.clearAnimatingFlags();
            }
            this.mWallpaperControllerLocked.adjustWallpaperWindowsForAppTransitionIfNeeded(displayContent, this.mService.mOpeningApps);
            boolean hasWallpaperTarget = this.mWallpaperControllerLocked.getWallpaperTarget() != null;
            boolean openingAppHasWallpaper = canBeWallpaperTarget(this.mService.mOpeningApps) && hasWallpaperTarget;
            boolean closingAppHasWallpaper = canBeWallpaperTarget(this.mService.mClosingApps) && hasWallpaperTarget;
            int transit2 = maybeUpdateTransitToWallpaper(maybeUpdateTransitToTranslucentAnim(transit), openingAppHasWallpaper, closingAppHasWallpaper);
            ArraySet<Integer> activityTypes = collectActivityTypes(this.mService.mOpeningApps, this.mService.mClosingApps);
            if (this.mService.mPolicy.allowAppAnimationsLw()) {
                appWindowToken = findAnimLayoutParamsToken(transit2, activityTypes);
            } else {
                appWindowToken = null;
            }
            AppWindowToken animLpToken = appWindowToken;
            WindowManager.LayoutParams animLp = getAnimLp(animLpToken);
            overrideWithRemoteAnimationIfSet(animLpToken, transit2, activityTypes);
            boolean voiceInteraction = containsVoiceInteraction(this.mService.mOpeningApps) || containsVoiceInteraction(this.mService.mOpeningApps);
            this.mService.mSurfaceAnimationRunner.deferStartingAnimations();
            try {
                processApplicationsAnimatingInPlace(transit2);
                this.mTmpLayerAndToken.token = null;
                handleClosingApps(transit2, animLp, voiceInteraction, this.mTmpLayerAndToken);
                topClosingApp = this.mTmpLayerAndToken.token;
                topOpeningApp = handleOpeningApps(transit2, animLp, voiceInteraction);
                this.mService.mAppTransition.setLastAppTransition(transit2, topOpeningApp, topClosingApp);
                flags = this.mService.mAppTransition.getTransitFlags();
                try {
                    try {
                    } catch (Throwable th) {
                        th = th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
            try {
                int layoutRedo = this.mService.mAppTransition.goodToGo(transit2, topOpeningApp, topClosingApp, this.mService.mOpeningApps, this.mService.mClosingApps);
                handleNonAppWindowsInTransition(transit2, flags);
                this.mService.mAppTransition.postAnimationCallback();
                this.mService.mAppTransition.clear();
                this.mService.mSurfaceAnimationRunner.continueStartingAnimations();
                this.mService.mTaskSnapshotController.onTransitionStarting();
                this.mService.mOpeningApps.clear();
                this.mService.mClosingApps.clear();
                this.mService.mUnknownAppVisibilityController.clear();
                displayContent.setLayoutNeeded();
                DisplayContent dc = this.mService.getDefaultDisplayContentLocked();
                dc.computeImeTarget(true);
                this.mService.updateFocusedWindowLocked(2, true);
                this.mService.mFocusMayChange = false;
                this.mService.mH.obtainMessage(47, this.mTempTransitionReasons.clone()).sendToTarget();
                Trace.traceEnd(32L);
                return layoutRedo | 1 | 2;
            } catch (Throwable th4) {
                th = th4;
                this.mService.mSurfaceAnimationRunner.continueStartingAnimations();
                throw th;
            }
        }
        return 0;
    }

    private static WindowManager.LayoutParams getAnimLp(AppWindowToken wtoken) {
        WindowState mainWindow = wtoken != null ? wtoken.findMainWindow() : null;
        if (mainWindow != null) {
            return mainWindow.mAttrs;
        }
        return null;
    }

    private void overrideWithRemoteAnimationIfSet(AppWindowToken animLpToken, int transit, ArraySet<Integer> activityTypes) {
        RemoteAnimationDefinition definition;
        RemoteAnimationAdapter adapter;
        if (transit != 26 && animLpToken != null && (definition = animLpToken.getRemoteAnimationDefinition()) != null && (adapter = definition.getAdapter(transit, activityTypes)) != null) {
            this.mService.mAppTransition.overridePendingAppTransitionRemote(adapter);
        }
    }

    private AppWindowToken findAnimLayoutParamsToken(final int transit, final ArraySet<Integer> activityTypes) {
        AppWindowToken result = lookForHighestTokenWithFilter(this.mService.mClosingApps, this.mService.mOpeningApps, new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowSurfacePlacer$AnzDJL6vBWwhbuz7sYsAfUAzZko
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return WindowSurfacePlacer.lambda$findAnimLayoutParamsToken$1(transit, activityTypes, (AppWindowToken) obj);
            }
        });
        if (result != null) {
            return result;
        }
        AppWindowToken result2 = lookForHighestTokenWithFilter(this.mService.mClosingApps, this.mService.mOpeningApps, new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowSurfacePlacer$wCevQN6hMxiB97Eay8ibpi2Xaxo
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return WindowSurfacePlacer.lambda$findAnimLayoutParamsToken$2((AppWindowToken) obj);
            }
        });
        if (result2 != null) {
            return result2;
        }
        return lookForHighestTokenWithFilter(this.mService.mClosingApps, this.mService.mOpeningApps, new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowSurfacePlacer$tJcqA51ohv9DQjcvHOarwInr01s
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return WindowSurfacePlacer.lambda$findAnimLayoutParamsToken$3((AppWindowToken) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$findAnimLayoutParamsToken$1(int transit, ArraySet activityTypes, AppWindowToken w) {
        return w.getRemoteAnimationDefinition() != null && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$findAnimLayoutParamsToken$2(AppWindowToken w) {
        return w.fillsParent() && w.findMainWindow() != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$findAnimLayoutParamsToken$3(AppWindowToken w) {
        return w.findMainWindow() != null;
    }

    private ArraySet<Integer> collectActivityTypes(ArraySet<AppWindowToken> array1, ArraySet<AppWindowToken> array2) {
        ArraySet<Integer> result = new ArraySet<>();
        for (int i = array1.size() - 1; i >= 0; i--) {
            result.add(Integer.valueOf(array1.valueAt(i).getActivityType()));
        }
        int i2 = array2.size();
        for (int i3 = i2 - 1; i3 >= 0; i3--) {
            result.add(Integer.valueOf(array2.valueAt(i3).getActivityType()));
        }
        return result;
    }

    private AppWindowToken lookForHighestTokenWithFilter(ArraySet<AppWindowToken> array1, ArraySet<AppWindowToken> array2, Predicate<AppWindowToken> filter) {
        AppWindowToken wtoken;
        int array1count = array1.size();
        int count = array2.size() + array1count;
        int bestPrefixOrderIndex = Integer.MIN_VALUE;
        AppWindowToken bestToken = null;
        for (int i = 0; i < count; i++) {
            if (i < array1count) {
                wtoken = array1.valueAt(i);
            } else {
                wtoken = array2.valueAt(i - array1count);
            }
            int prefixOrderIndex = wtoken.getPrefixOrderIndex();
            if (filter.test(wtoken) && prefixOrderIndex > bestPrefixOrderIndex) {
                bestPrefixOrderIndex = prefixOrderIndex;
                bestToken = wtoken;
            }
        }
        return bestToken;
    }

    private boolean containsVoiceInteraction(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).mVoiceInteraction) {
                return true;
            }
        }
        return false;
    }

    private AppWindowToken handleOpeningApps(int transit, WindowManager.LayoutParams animLp, boolean voiceInteraction) {
        int appsCount = this.mService.mOpeningApps.size();
        int topOpeningLayer = Integer.MIN_VALUE;
        AppWindowToken topOpeningApp = null;
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = this.mService.mOpeningApps.valueAt(i);
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Now opening app" + wtoken);
            }
            if (!wtoken.setVisibility(animLp, true, transit, false, voiceInteraction)) {
                this.mService.mNoAnimationNotifyOnTransitionFinished.add(wtoken.token);
            }
            wtoken.updateReportedVisibilityLocked();
            wtoken.waitingToShow = false;
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, ">>> OPEN TRANSACTION handleAppTransitionReadyLocked()");
            }
            this.mService.openSurfaceTransaction();
            try {
                wtoken.showAllWindowsLocked();
                if (animLp != null) {
                    int layer = wtoken.getHighestAnimLayer();
                    if (topOpeningApp == null || layer > topOpeningLayer) {
                        topOpeningApp = wtoken;
                        topOpeningLayer = layer;
                    }
                }
                if (this.mService.mAppTransition.isNextAppTransitionThumbnailUp()) {
                    wtoken.attachThumbnailAnimation();
                } else if (this.mService.mAppTransition.isNextAppTransitionOpenCrossProfileApps()) {
                    wtoken.attachCrossProfileAppsThumbnailAnimation();
                }
            } finally {
                this.mService.closeSurfaceTransaction("handleAppTransitionReadyLocked");
                if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION handleAppTransitionReadyLocked()");
                }
            }
        }
        return topOpeningApp;
    }

    private void handleClosingApps(int transit, WindowManager.LayoutParams animLp, boolean voiceInteraction, LayerAndToken layerAndToken) {
        int appsCount = this.mService.mClosingApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = this.mService.mClosingApps.valueAt(i);
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Now closing app " + wtoken);
            }
            wtoken.setVisibility(animLp, false, transit, false, voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            wtoken.allDrawn = true;
            wtoken.deferClearAllDrawn = false;
            if (wtoken.startingWindow != null && !wtoken.startingWindow.mAnimatingExit && wtoken.getController() != null) {
                wtoken.getController().removeStartingWindow();
            }
            if (animLp != null) {
                int layer = wtoken.getHighestAnimLayer();
                if (layerAndToken.token == null || layer > layerAndToken.layer) {
                    layerAndToken.token = wtoken;
                    layerAndToken.layer = layer;
                }
            }
            if (this.mService.mAppTransition.isNextAppTransitionThumbnailDown()) {
                wtoken.attachThumbnailAnimation();
            }
        }
    }

    private void handleNonAppWindowsInTransition(int transit, int flags) {
        if (transit == 20 && (flags & 4) != 0 && (flags & 2) == 0) {
            Animation anim = this.mService.mPolicy.createKeyguardWallpaperExit((flags & 1) != 0);
            if (anim != null) {
                this.mService.getDefaultDisplayContentLocked().mWallpaperController.startWallpaperAnimation(anim);
            }
        }
        if (transit == 20 || transit == 21) {
            this.mService.getDefaultDisplayContentLocked().startKeyguardExitOnNonAppWindows(transit == 21, (flags & 1) != 0);
        }
    }

    private boolean transitionGoodToGo(int appsCount, SparseIntArray outReasons) {
        int i;
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "Checking " + appsCount + " opening apps (frozen=" + this.mService.mDisplayFrozen + " timeout=" + this.mService.mAppTransition.isTimeout() + ")...");
        }
        ScreenRotationAnimation screenRotationAnimation = this.mService.mAnimator.getScreenRotationAnimationLocked(0);
        outReasons.clear();
        if (this.mService.mAppTransition.isTimeout()) {
            return true;
        }
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating() && this.mService.rotationNeedsUpdateLocked()) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Delaying app transition for screen rotation animation to finish");
            }
            return false;
        }
        for (int i2 = 0; i2 < appsCount; i2++) {
            AppWindowToken wtoken = this.mService.mOpeningApps.valueAt(i2);
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Check opening app=" + wtoken + ": allDrawn=" + wtoken.allDrawn + " startingDisplayed=" + wtoken.startingDisplayed + " startingMoved=" + wtoken.startingMoved + " isRelaunching()=" + wtoken.isRelaunching() + " startingWindow=" + wtoken.startingWindow);
            }
            boolean allDrawn = wtoken.allDrawn && !wtoken.isRelaunching();
            if (!allDrawn && !wtoken.startingDisplayed && !wtoken.startingMoved) {
                return false;
            }
            int windowingMode = wtoken.getWindowingMode();
            if (allDrawn) {
                outReasons.put(windowingMode, 2);
            } else {
                if (wtoken.startingData instanceof SplashScreenStartingData) {
                    i = 1;
                } else {
                    i = 4;
                }
                outReasons.put(windowingMode, i);
            }
        }
        if (this.mService.mAppTransition.isFetchingAppTransitionsSpecs()) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "isFetchingAppTransitionSpecs=true");
            }
            return false;
        } else if (!this.mService.mUnknownAppVisibilityController.allResolved()) {
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "unknownApps is not empty: " + this.mService.mUnknownAppVisibilityController.getDebugMessage());
            }
            return false;
        } else {
            boolean wallpaperReady = !this.mWallpaperControllerLocked.isWallpaperVisible() || this.mWallpaperControllerLocked.wallpaperTransitionReady();
            return wallpaperReady;
        }
    }

    private int maybeUpdateTransitToWallpaper(int transit, boolean openingAppHasWallpaper, boolean closingAppHasWallpaper) {
        if (transit == 0 || transit == 26 || transit == 19) {
            return transit;
        }
        WindowState wallpaperTarget = this.mWallpaperControllerLocked.getWallpaperTarget();
        WindowState oldWallpaper = this.mWallpaperControllerLocked.isWallpaperTargetAnimating() ? null : wallpaperTarget;
        ArraySet<AppWindowToken> openingApps = this.mService.mOpeningApps;
        ArraySet<AppWindowToken> closingApps = this.mService.mClosingApps;
        AppWindowToken topOpeningApp = getTopApp(this.mService.mOpeningApps, false);
        AppWindowToken topClosingApp = getTopApp(this.mService.mClosingApps, true);
        boolean openingCanBeWallpaperTarget = canBeWallpaperTarget(openingApps);
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "New wallpaper target=" + wallpaperTarget + ", oldWallpaper=" + oldWallpaper + ", openingApps=" + openingApps + ", closingApps=" + closingApps);
        }
        if (openingCanBeWallpaperTarget && transit == 20) {
            transit = 21;
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "New transit: " + AppTransition.appTransitionToString(21));
            }
        } else if (!AppTransition.isKeyguardGoingAwayTransit(transit)) {
            if (closingAppHasWallpaper && openingAppHasWallpaper) {
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "Wallpaper animation!");
                }
                switch (transit) {
                    case 6:
                    case 8:
                    case 10:
                        transit = 14;
                        break;
                    case 7:
                    case 9:
                    case 11:
                        transit = 15;
                        break;
                }
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "New transit: " + AppTransition.appTransitionToString(transit));
                }
            } else if (oldWallpaper != null && !this.mService.mOpeningApps.isEmpty() && !openingApps.contains(oldWallpaper.mAppToken) && closingApps.contains(oldWallpaper.mAppToken) && topClosingApp == oldWallpaper.mAppToken) {
                transit = 12;
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "New transit away from wallpaper: " + AppTransition.appTransitionToString(12));
                }
            } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw() && openingApps.contains(wallpaperTarget.mAppToken) && topOpeningApp == wallpaperTarget.mAppToken && transit != 25) {
                transit = 13;
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                    Slog.v(TAG, "New transit into wallpaper: " + AppTransition.appTransitionToString(13));
                }
            }
        }
        return transit;
    }

    @VisibleForTesting
    int maybeUpdateTransitToTranslucentAnim(int transit) {
        boolean taskOrActivity = AppTransition.isTaskTransit(transit) || AppTransition.isActivityTransit(transit);
        boolean allOpeningVisible = true;
        boolean allTranslucentOpeningApps = !this.mService.mOpeningApps.isEmpty();
        for (int i = this.mService.mOpeningApps.size() - 1; i >= 0; i--) {
            AppWindowToken token = this.mService.mOpeningApps.valueAt(i);
            if (!token.isVisible()) {
                allOpeningVisible = false;
                if (token.fillsParent()) {
                    allTranslucentOpeningApps = false;
                }
            }
        }
        boolean allTranslucentClosingApps = !this.mService.mClosingApps.isEmpty();
        int i2 = this.mService.mClosingApps.size() - 1;
        while (true) {
            int i3 = i2;
            if (i3 >= 0) {
                if (this.mService.mClosingApps.valueAt(i3).fillsParent()) {
                    allTranslucentClosingApps = false;
                    break;
                } else {
                    i2 = i3 - 1;
                }
            } else {
                break;
            }
        }
        if (taskOrActivity && allTranslucentClosingApps && allOpeningVisible) {
            return 25;
        }
        if (taskOrActivity && allTranslucentOpeningApps && this.mService.mClosingApps.isEmpty()) {
            return 24;
        }
        return transit;
    }

    private boolean canBeWallpaperTarget(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).windowsCanBeWallpaperTarget()) {
                return true;
            }
        }
        return false;
    }

    private AppWindowToken getTopApp(ArraySet<AppWindowToken> apps, boolean ignoreHidden) {
        int prefixOrderIndex;
        int topPrefixOrderIndex = Integer.MIN_VALUE;
        AppWindowToken topApp = null;
        for (int i = apps.size() - 1; i >= 0; i--) {
            AppWindowToken app = apps.valueAt(i);
            if ((!ignoreHidden || !app.isHidden()) && (prefixOrderIndex = app.getPrefixOrderIndex()) > topPrefixOrderIndex) {
                topPrefixOrderIndex = prefixOrderIndex;
                topApp = app;
            }
        }
        return topApp;
    }

    private void processApplicationsAnimatingInPlace(int transit) {
        WindowState win;
        if (transit == 17 && (win = this.mService.getDefaultDisplayContentLocked().findFocusedWindow()) != null) {
            AppWindowToken wtoken = win.mAppToken;
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                Slog.v(TAG, "Now animating app in place " + wtoken);
            }
            wtoken.cancelAnimation();
            wtoken.applyAnimationLocked(null, transit, false, false);
            wtoken.updateReportedVisibilityLocked();
            wtoken.showAllWindowsLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestTraversal() {
        if (!this.mTraversalScheduled) {
            this.mTraversalScheduled = true;
            this.mService.mAnimationHandler.post(this.mPerformSurfacePlacement);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mTraversalScheduled=" + this.mTraversalScheduled);
        pw.println(prefix + "mHoldScreenWindow=" + this.mService.mRoot.mHoldScreenWindow);
        pw.println(prefix + "mObscuringWindow=" + this.mService.mRoot.mObscuringWindow);
    }
}
