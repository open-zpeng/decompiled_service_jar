package com.android.server.wm;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.IDockedStackListener;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManagerInternal;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import java.io.PrintWriter;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class DockedStackDividerController {
    private static final float CLIP_REVEAL_MEET_EARLIEST = 0.6f;
    private static final float CLIP_REVEAL_MEET_FRACTION_MAX = 0.8f;
    private static final float CLIP_REVEAL_MEET_FRACTION_MIN = 0.4f;
    private static final float CLIP_REVEAL_MEET_LAST = 1.0f;
    private static final int DIVIDER_WIDTH_INACTIVE_DP = 4;
    private static final long IME_ADJUST_ANIM_DURATION = 280;
    private static final long IME_ADJUST_DRAWN_TIMEOUT = 200;
    private static final Interpolator IME_ADJUST_ENTRY_INTERPOLATOR = new PathInterpolator(0.2f, 0.0f, 0.1f, 1.0f);
    private static final String TAG = "WindowManager";
    private boolean mAdjustedForDivider;
    private boolean mAdjustedForIme;
    private boolean mAnimatingForIme;
    private boolean mAnimatingForMinimizedDockedStack;
    private long mAnimationDuration;
    private float mAnimationStart;
    private boolean mAnimationStartDelayed;
    private long mAnimationStartTime;
    private boolean mAnimationStarted;
    private float mAnimationTarget;
    private WindowState mDelayedImeWin;
    private TaskStack mDimmedStack;
    private final DisplayContent mDisplayContent;
    private float mDividerAnimationStart;
    private float mDividerAnimationTarget;
    private int mDividerInsets;
    private int mDividerWindowWidth;
    private int mDividerWindowWidthInactive;
    private int mImeHeight;
    private boolean mImeHideRequested;
    float mLastAnimationProgress;
    private float mLastDimLayerAlpha;
    float mLastDividerProgress;
    private float mMaximizeMeetFraction;
    private boolean mMinimizedDock;
    private final Interpolator mMinimizedDockInterpolator;
    private boolean mResizing;
    private final WindowManagerService mService;
    private int mTaskHeightInMinimizedMode;
    private WindowState mWindow;
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Rect mTmpRect3 = new Rect();
    private final Rect mLastRect = new Rect();
    private boolean mLastVisibility = false;
    private final RemoteCallbackList<IDockedStackListener> mDockedStackListeners = new RemoteCallbackList<>();
    private int mOriginalDockedSide = -1;
    private final Rect mTouchRegion = new Rect();
    private final DividerSnapAlgorithm[] mSnapAlgorithmForRotation = new DividerSnapAlgorithm[4];
    private final Rect mLastDimLayerRect = new Rect();

    /* JADX INFO: Access modifiers changed from: package-private */
    public DockedStackDividerController(WindowManagerService service, DisplayContent displayContent) {
        this.mService = service;
        this.mDisplayContent = displayContent;
        Context context = service.mContext;
        this.mMinimizedDockInterpolator = AnimationUtils.loadInterpolator(context, 17563661);
        loadDimens();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getSmallestWidthDpForBounds(Rect bounds) {
        boolean z;
        DisplayInfo di = this.mDisplayContent.getDisplayInfo();
        int baseDisplayWidth = this.mDisplayContent.mBaseDisplayWidth;
        int baseDisplayHeight = this.mDisplayContent.mBaseDisplayHeight;
        int minWidth = Integer.MAX_VALUE;
        for (int minWidth2 = 0; minWidth2 < 4; minWidth2++) {
            this.mTmpRect.set(bounds);
            this.mDisplayContent.rotateBounds(di.rotation, minWidth2, this.mTmpRect);
            int i = 1;
            if (minWidth2 != 1 && minWidth2 != 3) {
                z = false;
            } else {
                z = true;
            }
            boolean rotated = z;
            this.mTmpRect2.set(0, 0, rotated ? baseDisplayHeight : baseDisplayWidth, rotated ? baseDisplayWidth : baseDisplayHeight);
            if (this.mTmpRect2.width() > this.mTmpRect2.height()) {
                i = 2;
            }
            int orientation = i;
            int dockSide = getDockSide(this.mTmpRect, this.mTmpRect2, orientation);
            int position = DockedDividerUtils.calculatePositionForBounds(this.mTmpRect, dockSide, getContentWidth());
            DisplayCutout displayCutout = this.mDisplayContent.calculateDisplayCutoutForRotation(minWidth2).getDisplayCutout();
            int snappedPosition = this.mSnapAlgorithmForRotation[minWidth2].calculateNonDismissingSnapTarget(position).position;
            DockedDividerUtils.calculateBoundsForPosition(snappedPosition, dockSide, this.mTmpRect, this.mTmpRect2.width(), this.mTmpRect2.height(), getContentWidth());
            WindowManagerPolicy windowManagerPolicy = this.mService.mPolicy;
            int width = this.mTmpRect2.width();
            int position2 = this.mTmpRect2.height();
            windowManagerPolicy.getStableInsetsLw(minWidth2, width, position2, displayCutout, this.mTmpRect3);
            this.mService.intersectDisplayInsetBounds(this.mTmpRect2, this.mTmpRect3, this.mTmpRect);
            minWidth = Math.min(this.mTmpRect.width(), minWidth);
        }
        return (int) (minWidth / this.mDisplayContent.getDisplayMetrics().density);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDockSide(Rect bounds, Rect displayRect, int orientation) {
        if (orientation == 1) {
            int diff = (displayRect.bottom - bounds.bottom) - (bounds.top - displayRect.top);
            if (diff > 0) {
                return 2;
            }
            if (diff >= 0 && canPrimaryStackDockTo(2)) {
                return 2;
            }
            return 4;
        } else if (orientation == 2) {
            int diff2 = (displayRect.right - bounds.right) - (bounds.left - displayRect.left);
            if (diff2 > 0) {
                return 1;
            }
            return (diff2 >= 0 && canPrimaryStackDockTo(1)) ? 1 : 3;
        } else {
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getHomeStackBoundsInDockedMode(Rect outBounds) {
        DisplayInfo di = this.mDisplayContent.getDisplayInfo();
        this.mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, di.displayCutout, this.mTmpRect);
        int dividerSize = this.mDividerWindowWidth - (2 * this.mDividerInsets);
        Configuration configuration = this.mDisplayContent.getConfiguration();
        if (configuration.orientation == 1) {
            outBounds.set(0, this.mTaskHeightInMinimizedMode + dividerSize + this.mTmpRect.top, di.logicalWidth, di.logicalHeight);
            return;
        }
        TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        int primaryTaskWidth = this.mTaskHeightInMinimizedMode + dividerSize + this.mTmpRect.top;
        int left = this.mTmpRect.left;
        int right = di.logicalWidth - this.mTmpRect.right;
        if (stack != null) {
            if (stack.getDockSide() == 1) {
                left += primaryTaskWidth;
            } else if (stack.getDockSide() == 3) {
                right -= primaryTaskWidth;
            }
        }
        outBounds.set(left, 0, right, di.logicalHeight);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isHomeStackResizable() {
        Task homeTask;
        TaskStack homeStack = this.mDisplayContent.getHomeStack();
        return (homeStack == null || (homeTask = homeStack.findHomeTask()) == null || !homeTask.isResizeable()) ? false : true;
    }

    private void initSnapAlgorithmForRotations() {
        boolean z;
        int i;
        int i2;
        boolean z2;
        Configuration baseConfig = this.mDisplayContent.getConfiguration();
        Configuration config = new Configuration();
        for (int rotation = 0; rotation < 4; rotation++) {
            if (rotation != 1 && rotation != 3) {
                z = false;
            } else {
                z = true;
            }
            boolean rotated = z;
            if (rotated) {
                i = this.mDisplayContent.mBaseDisplayHeight;
            } else {
                i = this.mDisplayContent.mBaseDisplayWidth;
            }
            int dw = i;
            if (rotated) {
                i2 = this.mDisplayContent.mBaseDisplayWidth;
            } else {
                i2 = this.mDisplayContent.mBaseDisplayHeight;
            }
            int dh = i2;
            DisplayCutout displayCutout = this.mDisplayContent.calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
            this.mService.mPolicy.getStableInsetsLw(rotation, dw, dh, displayCutout, this.mTmpRect);
            config.unset();
            config.orientation = dw <= dh ? 1 : 2;
            int displayId = this.mDisplayContent.getDisplayId();
            int i3 = rotation;
            int appWidth = this.mService.mPolicy.getNonDecorDisplayWidth(dw, dh, i3, baseConfig.uiMode, displayId, displayCutout);
            int appHeight = this.mService.mPolicy.getNonDecorDisplayHeight(dw, dh, i3, baseConfig.uiMode, displayId, displayCutout);
            this.mService.mPolicy.getNonDecorInsetsLw(rotation, dw, dh, displayCutout, this.mTmpRect);
            int leftInset = this.mTmpRect.left;
            int topInset = this.mTmpRect.top;
            config.windowConfiguration.setAppBounds(leftInset, topInset, leftInset + appWidth, topInset + appHeight);
            float density = this.mDisplayContent.getDisplayMetrics().density;
            int i4 = rotation;
            config.screenWidthDp = (int) (this.mService.mPolicy.getConfigDisplayWidth(dw, dh, i4, baseConfig.uiMode, displayId, displayCutout) / density);
            config.screenHeightDp = (int) (this.mService.mPolicy.getConfigDisplayHeight(dw, dh, i4, baseConfig.uiMode, displayId, displayCutout) / density);
            Context rotationContext = this.mService.mContext.createConfigurationContext(config);
            DividerSnapAlgorithm[] dividerSnapAlgorithmArr = this.mSnapAlgorithmForRotation;
            Resources resources = rotationContext.getResources();
            int contentWidth = getContentWidth();
            if (config.orientation != 1) {
                z2 = false;
            } else {
                z2 = true;
            }
            dividerSnapAlgorithmArr[rotation] = new DividerSnapAlgorithm(resources, dw, dh, contentWidth, z2, this.mTmpRect);
        }
    }

    private void loadDimens() {
        Context context = this.mService.mContext;
        this.mDividerWindowWidth = context.getResources().getDimensionPixelSize(17105054);
        this.mDividerInsets = context.getResources().getDimensionPixelSize(17105053);
        this.mDividerWindowWidthInactive = WindowManagerService.dipToPixel(4, this.mDisplayContent.getDisplayMetrics());
        this.mTaskHeightInMinimizedMode = context.getResources().getDimensionPixelSize(17105343);
        initSnapAlgorithmForRotations();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onConfigurationChanged() {
        loadDimens();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isResizing() {
        return this.mResizing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getContentWidth() {
        return this.mDividerWindowWidth - (2 * this.mDividerInsets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getContentInsets() {
        return this.mDividerInsets;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getContentWidthInactive() {
        return this.mDividerWindowWidthInactive;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResizing(boolean resizing) {
        if (this.mResizing != resizing) {
            this.mResizing = resizing;
            resetDragResizingChangeReported();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTouchRegion(Rect touchRegion) {
        this.mTouchRegion.set(touchRegion);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTouchRegion(Rect outRegion) {
        outRegion.set(this.mTouchRegion);
        outRegion.offset(this.mWindow.getFrameLw().left, this.mWindow.getFrameLw().top);
    }

    private void resetDragResizingChangeReported() {
        this.mDisplayContent.forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$vhwCX-wzYksBgFM46tASKUCeQRc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).resetDragResizingChangeReported();
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindow(WindowState window) {
        this.mWindow = window;
        reevaluateVisibility(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reevaluateVisibility(boolean force) {
        if (this.mWindow == null) {
            return;
        }
        TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        boolean visible = stack != null;
        if (this.mLastVisibility == visible && !force) {
            return;
        }
        this.mLastVisibility = visible;
        notifyDockedDividerVisibilityChanged(visible);
        if (!visible) {
            setResizeDimLayer(false, 0, 0.0f);
        }
    }

    private boolean wasVisible() {
        return this.mLastVisibility;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAdjustedForIme(boolean adjustedForIme, boolean adjustedForDivider, boolean animate, WindowState imeWin, int imeHeight) {
        if (this.mAdjustedForIme != adjustedForIme || ((adjustedForIme && this.mImeHeight != imeHeight) || this.mAdjustedForDivider != adjustedForDivider)) {
            if (animate && !this.mAnimatingForMinimizedDockedStack) {
                startImeAdjustAnimation(adjustedForIme, adjustedForDivider, imeWin);
            } else {
                notifyAdjustedForImeChanged(adjustedForIme || adjustedForDivider, 0L);
            }
            this.mAdjustedForIme = adjustedForIme;
            this.mImeHeight = imeHeight;
            this.mAdjustedForDivider = adjustedForDivider;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getImeHeightAdjustedFor() {
        return this.mImeHeight;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionDockedStackedDivider(Rect frame) {
        TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (stack == null) {
            frame.set(this.mLastRect);
            return;
        }
        stack.getDimBounds(this.mTmpRect);
        int side = stack.getDockSide();
        switch (side) {
            case 1:
                frame.set(this.mTmpRect.right - this.mDividerInsets, frame.top, (this.mTmpRect.right + frame.width()) - this.mDividerInsets, frame.bottom);
                break;
            case 2:
                frame.set(frame.left, this.mTmpRect.bottom - this.mDividerInsets, this.mTmpRect.right, (this.mTmpRect.bottom + frame.height()) - this.mDividerInsets);
                break;
            case 3:
                frame.set((this.mTmpRect.left - frame.width()) + this.mDividerInsets, frame.top, this.mTmpRect.left + this.mDividerInsets, frame.bottom);
                break;
            case 4:
                frame.set(frame.left, (this.mTmpRect.top - frame.height()) + this.mDividerInsets, frame.right, this.mTmpRect.top + this.mDividerInsets);
                break;
        }
        this.mLastRect.set(frame);
    }

    private void notifyDockedDividerVisibilityChanged(boolean visible) {
        int size = this.mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; i++) {
            IDockedStackListener listener = this.mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDividerVisibilityChanged(visible);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering divider visibility changed event.", e);
            }
        }
        this.mDockedStackListeners.finishBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canPrimaryStackDockTo(int dockSide) {
        DisplayInfo di = this.mDisplayContent.getDisplayInfo();
        return this.mService.mPolicy.isDockSideAllowed(dockSide, this.mOriginalDockedSide, di.logicalWidth, di.logicalHeight, di.rotation);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyDockedStackExistsChanged(boolean exists) {
        int size = this.mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; i++) {
            IDockedStackListener listener = this.mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockedStackExistsChanged(exists);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering docked stack exists changed event.", e);
            }
        }
        this.mDockedStackListeners.finishBroadcast();
        if (exists) {
            InputMethodManagerInternal inputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
            if (inputMethodManagerInternal != null) {
                inputMethodManagerInternal.hideCurrentInputMethod();
                this.mImeHideRequested = true;
            }
            TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            this.mOriginalDockedSide = stack.getDockSideForDisplay(this.mDisplayContent);
            return;
        }
        this.mOriginalDockedSide = -1;
        setMinimizedDockedStack(false, false);
        if (this.mDimmedStack != null) {
            this.mDimmedStack.stopDimming();
            this.mDimmedStack = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetImeHideRequested() {
        this.mImeHideRequested = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isImeHideRequested() {
        return this.mImeHideRequested;
    }

    private void notifyDockedStackMinimizedChanged(boolean minimizedDock, boolean animate, boolean isHomeStackResizable) {
        long transitionDuration;
        long animDuration = 0;
        if (animate) {
            TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            if (isAnimationMaximizing()) {
                transitionDuration = this.mService.mAppTransition.getLastClipRevealTransitionDuration();
            } else {
                transitionDuration = 336;
            }
            this.mAnimationDuration = ((float) transitionDuration) * this.mService.getTransitionAnimationScaleLocked();
            this.mMaximizeMeetFraction = getClipRevealMeetFraction(stack);
            animDuration = ((float) this.mAnimationDuration) * this.mMaximizeMeetFraction;
        }
        this.mService.mH.removeMessages(53);
        int i = 0;
        this.mService.mH.obtainMessage(53, minimizedDock ? 1 : 0, 0).sendToTarget();
        int size = this.mDockedStackListeners.beginBroadcast();
        while (true) {
            int i2 = i;
            if (i2 < size) {
                IDockedStackListener listener = this.mDockedStackListeners.getBroadcastItem(i2);
                try {
                    listener.onDockedStackMinimizedChanged(minimizedDock, animDuration, isHomeStackResizable);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error delivering minimized dock changed event.", e);
                }
                i = i2 + 1;
            } else {
                this.mDockedStackListeners.finishBroadcast();
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyDockSideChanged(int newDockSide) {
        int size = this.mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; i++) {
            IDockedStackListener listener = this.mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockSideChanged(newDockSide);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering dock side changed event.", e);
            }
        }
        this.mDockedStackListeners.finishBroadcast();
    }

    private void notifyAdjustedForImeChanged(boolean adjustedForIme, long animDuration) {
        int size = this.mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; i++) {
            IDockedStackListener listener = this.mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onAdjustedForImeChanged(adjustedForIme, animDuration);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering adjusted for ime changed event.", e);
            }
        }
        this.mDockedStackListeners.finishBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerDockedStackListener(IDockedStackListener listener) {
        this.mDockedStackListeners.register(listener);
        notifyDockedDividerVisibilityChanged(wasVisible());
        notifyDockedStackExistsChanged(this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null);
        notifyDockedStackMinimizedChanged(this.mMinimizedDock, false, isHomeStackResizable());
        notifyAdjustedForImeChanged(this.mAdjustedForIme, 0L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        TaskStack stack;
        if (targetWindowingMode != 0) {
            stack = this.mDisplayContent.getTopStackInWindowingMode(targetWindowingMode);
        } else {
            stack = null;
        }
        TaskStack dockedStack = this.mDisplayContent.getSplitScreenPrimaryStack();
        boolean visibleAndValid = (!visible || stack == null || dockedStack == null) ? false : true;
        if (this.mDimmedStack != null && this.mDimmedStack != stack) {
            this.mDimmedStack.stopDimming();
            this.mDimmedStack = null;
        }
        if (visibleAndValid) {
            this.mDimmedStack = stack;
            stack.dim(alpha);
        }
        if (!visibleAndValid && stack != null) {
            this.mDimmedStack = null;
            stack.stopDimming();
        }
    }

    private int getResizeDimLayer() {
        if (this.mWindow != null) {
            return this.mWindow.mLayer - 1;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppVisibilityChanged() {
        checkMinimizeChanged(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppTransitionStarting(ArraySet<AppWindowToken> openingApps, int appTransition) {
        boolean wasMinimized = this.mMinimizedDock;
        checkMinimizeChanged(true);
        if (wasMinimized && this.mMinimizedDock && containsAppInDockedStack(openingApps) && appTransition != 0 && !AppTransition.isKeyguardGoingAwayTransit(appTransition) && !this.mService.mAmInternal.isRecentsComponentHomeActivity(this.mService.mCurrentUserId)) {
            this.mService.showRecentApps();
        }
    }

    private boolean containsAppInDockedStack(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            AppWindowToken token = apps.valueAt(i);
            if (token.getTask() != null && token.inSplitScreenPrimaryWindowingMode()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isMinimizedDock() {
        return this.mMinimizedDock;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkMinimizeChanged(boolean animate) {
        TaskStack homeStack;
        Task homeTask;
        if (this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() == null || (homeStack = this.mDisplayContent.getHomeStack()) == null || (homeTask = homeStack.findHomeTask()) == null || !isWithinDisplay(homeTask)) {
            return;
        }
        if (this.mMinimizedDock && this.mService.mKeyguardOrAodShowingOnDefaultDisplay) {
            return;
        }
        TaskStack topSecondaryStack = this.mDisplayContent.getTopStackInWindowingMode(4);
        RecentsAnimationController recentsAnim = this.mService.getRecentsAnimationController();
        boolean z = false;
        boolean minimizedForRecentsAnimation = recentsAnim != null && recentsAnim.isSplitScreenMinimized();
        boolean homeVisible = homeTask.getTopVisibleAppToken() != null;
        if (homeVisible && topSecondaryStack != null) {
            homeVisible = homeStack.compareTo((WindowContainer) topSecondaryStack) >= 0;
        }
        if (homeVisible || minimizedForRecentsAnimation) {
            z = true;
        }
        setMinimizedDockedStack(z, animate);
    }

    private boolean isWithinDisplay(Task task) {
        task.getBounds(this.mTmpRect);
        this.mDisplayContent.getBounds(this.mTmpRect2);
        return this.mTmpRect.intersect(this.mTmpRect2);
    }

    private void setMinimizedDockedStack(boolean minimizedDock, boolean animate) {
        boolean wasMinimized = this.mMinimizedDock;
        this.mMinimizedDock = minimizedDock;
        if (minimizedDock == wasMinimized) {
            return;
        }
        boolean imeChanged = clearImeAdjustAnimation();
        boolean minimizedChange = false;
        if (isHomeStackResizable()) {
            notifyDockedStackMinimizedChanged(minimizedDock, animate, true);
            minimizedChange = true;
        } else if (minimizedDock) {
            if (!animate) {
                minimizedChange = false | setMinimizedDockedStack(true);
            } else {
                startAdjustAnimation(0.0f, 1.0f);
            }
        } else if (!animate) {
            minimizedChange = false | setMinimizedDockedStack(false);
        } else {
            startAdjustAnimation(1.0f, 0.0f);
        }
        if (imeChanged || minimizedChange) {
            if (imeChanged && !minimizedChange) {
                Slog.d(TAG, "setMinimizedDockedStack: IME adjust changed due to minimizing, minimizedDock=" + minimizedDock + " minimizedChange=" + minimizedChange);
            }
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    private boolean clearImeAdjustAnimation() {
        boolean changed = this.mDisplayContent.clearImeAdjustAnimation();
        this.mAnimatingForIme = false;
        return changed;
    }

    private void startAdjustAnimation(float from, float to) {
        this.mAnimatingForMinimizedDockedStack = true;
        this.mAnimationStarted = false;
        this.mAnimationStart = from;
        this.mAnimationTarget = to;
    }

    private void startImeAdjustAnimation(final boolean adjustedForIme, final boolean adjustedForDivider, WindowState imeWin) {
        if (!this.mAnimatingForIme) {
            this.mAnimationStart = this.mAdjustedForIme ? 1.0f : 0.0f;
            this.mDividerAnimationStart = this.mAdjustedForDivider ? 1.0f : 0.0f;
            this.mLastAnimationProgress = this.mAnimationStart;
            this.mLastDividerProgress = this.mDividerAnimationStart;
        } else {
            this.mAnimationStart = this.mLastAnimationProgress;
            this.mDividerAnimationStart = this.mLastDividerProgress;
        }
        boolean z = true;
        this.mAnimatingForIme = true;
        this.mAnimationStarted = false;
        this.mAnimationTarget = adjustedForIme ? 1.0f : 0.0f;
        this.mDividerAnimationTarget = adjustedForDivider ? 1.0f : 0.0f;
        this.mDisplayContent.beginImeAdjustAnimation();
        if (!this.mService.mWaitingForDrawn.isEmpty()) {
            this.mService.mH.removeMessages(24);
            this.mService.mH.sendEmptyMessageDelayed(24, IME_ADJUST_DRAWN_TIMEOUT);
            this.mAnimationStartDelayed = true;
            if (imeWin != null) {
                if (this.mDelayedImeWin != null) {
                    this.mDelayedImeWin.endDelayingAnimationStart();
                }
                this.mDelayedImeWin = imeWin;
                imeWin.startDelayingAnimationStart();
            }
            if (this.mService.mWaitingForDrawnCallback != null) {
                this.mService.mWaitingForDrawnCallback.run();
            }
            this.mService.mWaitingForDrawnCallback = new Runnable() { // from class: com.android.server.wm.-$$Lambda$DockedStackDividerController$5bA1vUPZ2WAWRKwBSEsFIfWUu9o
                @Override // java.lang.Runnable
                public final void run() {
                    DockedStackDividerController.lambda$startImeAdjustAnimation$0(DockedStackDividerController.this, adjustedForIme, adjustedForDivider);
                }
            };
            return;
        }
        if (!adjustedForIme && !adjustedForDivider) {
            z = false;
        }
        notifyAdjustedForImeChanged(z, IME_ADJUST_ANIM_DURATION);
    }

    public static /* synthetic */ void lambda$startImeAdjustAnimation$0(DockedStackDividerController dockedStackDividerController, boolean adjustedForIme, boolean adjustedForDivider) {
        synchronized (dockedStackDividerController.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                boolean z = false;
                dockedStackDividerController.mAnimationStartDelayed = false;
                if (dockedStackDividerController.mDelayedImeWin != null) {
                    dockedStackDividerController.mDelayedImeWin.endDelayingAnimationStart();
                }
                long duration = 0;
                if (dockedStackDividerController.mAdjustedForIme == adjustedForIme && dockedStackDividerController.mAdjustedForDivider == adjustedForDivider) {
                    duration = IME_ADJUST_ANIM_DURATION;
                } else {
                    Slog.w(TAG, "IME adjust changed while waiting for drawn: adjustedForIme=" + adjustedForIme + " adjustedForDivider=" + adjustedForDivider + " mAdjustedForIme=" + dockedStackDividerController.mAdjustedForIme + " mAdjustedForDivider=" + dockedStackDividerController.mAdjustedForDivider);
                }
                if (!dockedStackDividerController.mAdjustedForIme && !dockedStackDividerController.mAdjustedForDivider) {
                    dockedStackDividerController.notifyAdjustedForImeChanged(z, duration);
                }
                z = true;
                dockedStackDividerController.notifyAdjustedForImeChanged(z, duration);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    private boolean setMinimizedDockedStack(boolean minimized) {
        TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        notifyDockedStackMinimizedChanged(minimized, false, isHomeStackResizable());
        if (stack != null) {
            return stack.setAdjustedForMinimizedDock(minimized ? 1.0f : 0.0f);
        }
        return false;
    }

    private boolean isAnimationMaximizing() {
        return this.mAnimationTarget == 0.0f;
    }

    public boolean animate(long now) {
        if (this.mWindow == null) {
            return false;
        }
        if (this.mAnimatingForMinimizedDockedStack) {
            return animateForMinimizedDockedStack(now);
        }
        if (this.mAnimatingForIme) {
            return animateForIme(now);
        }
        return false;
    }

    private boolean animateForIme(long now) {
        if (!this.mAnimationStarted || this.mAnimationStartDelayed) {
            this.mAnimationStarted = true;
            this.mAnimationStartTime = now;
            this.mAnimationDuration = 280.0f * this.mService.getWindowAnimationScaleLocked();
        }
        float t = (this.mAnimationTarget == 1.0f ? IME_ADJUST_ENTRY_INTERPOLATOR : AppTransition.TOUCH_RESPONSE_INTERPOLATOR).getInterpolation(Math.min(1.0f, ((float) (now - this.mAnimationStartTime)) / ((float) this.mAnimationDuration)));
        boolean updated = this.mDisplayContent.animateForIme(t, this.mAnimationTarget, this.mDividerAnimationTarget);
        if (updated) {
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
        }
        if (t >= 1.0f) {
            this.mLastAnimationProgress = this.mAnimationTarget;
            this.mLastDividerProgress = this.mDividerAnimationTarget;
            this.mAnimatingForIme = false;
            return false;
        }
        return true;
    }

    private boolean animateForMinimizedDockedStack(long now) {
        TaskStack stack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (!this.mAnimationStarted) {
            this.mAnimationStarted = true;
            this.mAnimationStartTime = now;
            notifyDockedStackMinimizedChanged(this.mMinimizedDock, true, isHomeStackResizable());
        }
        float t = (isAnimationMaximizing() ? AppTransition.TOUCH_RESPONSE_INTERPOLATOR : this.mMinimizedDockInterpolator).getInterpolation(Math.min(1.0f, ((float) (now - this.mAnimationStartTime)) / ((float) this.mAnimationDuration)));
        if (stack != null && stack.setAdjustedForMinimizedDock(getMinimizeAmount(stack, t))) {
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
        }
        if (t >= 1.0f) {
            this.mAnimatingForMinimizedDockedStack = false;
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float getInterpolatedAnimationValue(float t) {
        return (this.mAnimationTarget * t) + ((1.0f - t) * this.mAnimationStart);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float getInterpolatedDividerValue(float t) {
        return (this.mDividerAnimationTarget * t) + ((1.0f - t) * this.mDividerAnimationStart);
    }

    private float getMinimizeAmount(TaskStack stack, float t) {
        float naturalAmount = getInterpolatedAnimationValue(t);
        if (isAnimationMaximizing()) {
            return adjustMaximizeAmount(stack, t, naturalAmount);
        }
        return naturalAmount;
    }

    private float adjustMaximizeAmount(TaskStack stack, float t, float naturalAmount) {
        if (this.mMaximizeMeetFraction == 1.0f) {
            return naturalAmount;
        }
        int minimizeDistance = stack.getMinimizeDistance();
        float startPrime = this.mService.mAppTransition.getLastClipRevealMaxTranslation() / minimizeDistance;
        float amountPrime = (this.mAnimationTarget * t) + ((1.0f - t) * startPrime);
        float t2 = Math.min(t / this.mMaximizeMeetFraction, 1.0f);
        return (amountPrime * t2) + ((1.0f - t2) * naturalAmount);
    }

    private float getClipRevealMeetFraction(TaskStack stack) {
        if (isAnimationMaximizing() && stack != null && this.mService.mAppTransition.hadClipRevealAnimation()) {
            int minimizeDistance = stack.getMinimizeDistance();
            float fraction = Math.abs(this.mService.mAppTransition.getLastClipRevealMaxTranslation()) / minimizeDistance;
            float t = Math.max(0.0f, Math.min(1.0f, (fraction - CLIP_REVEAL_MEET_FRACTION_MIN) / CLIP_REVEAL_MEET_FRACTION_MIN));
            return CLIP_REVEAL_MEET_EARLIEST + ((1.0f - t) * 0.39999998f);
        }
        return 1.0f;
    }

    public String toShortString() {
        return TAG;
    }

    WindowState getWindow() {
        return this.mWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DockedStackDividerController");
        pw.println(prefix + "  mLastVisibility=" + this.mLastVisibility);
        pw.println(prefix + "  mMinimizedDock=" + this.mMinimizedDock);
        pw.println(prefix + "  mAdjustedForIme=" + this.mAdjustedForIme);
        pw.println(prefix + "  mAdjustedForDivider=" + this.mAdjustedForDivider);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1133871366145L, this.mMinimizedDock);
        proto.end(token);
    }
}
