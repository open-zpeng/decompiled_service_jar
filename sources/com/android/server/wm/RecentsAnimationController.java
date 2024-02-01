package com.android.server.wm;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.InputWindowHandle;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.utils.InsetUtils;
import com.google.android.collect.Sets;
import java.io.PrintWriter;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public class RecentsAnimationController implements IBinder.DeathRecipient {
    private static final long FAILSAFE_DELAY = 1000;
    public static final int REORDER_KEEP_IN_PLACE = 0;
    public static final int REORDER_MOVE_TO_ORIGINAL_POSITION = 2;
    public static final int REORDER_MOVE_TO_TOP = 1;
    private static final String TAG = RecentsAnimationController.class.getSimpleName();
    private final RecentsAnimationCallbacks mCallbacks;
    private boolean mCancelDeferredWithScreenshot;
    private boolean mCancelOnNextTransitionStart;
    private boolean mCanceled;
    private DisplayContent mDisplayContent;
    private final int mDisplayId;
    private boolean mInputConsumerEnabled;
    private boolean mLinkedToDeathOfRunner;
    SurfaceAnimator mRecentScreenshotAnimator;
    private boolean mRequestDeferCancelUntilNextTransition;
    private IRecentsAnimationRunner mRunner;
    private final WindowManagerService mService;
    private boolean mSplitScreenMinimized;
    private int mTargetActivityType;
    private AppWindowToken mTargetAppToken;
    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();
    private final Runnable mFailsafeRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$RecentsAnimationController$4jQqaDgSmtGCjbUJiVoDh_jr9rY
        @Override // java.lang.Runnable
        public final void run() {
            RecentsAnimationController.this.lambda$new$0$RecentsAnimationController();
        }
    };
    private Rect mMinimizedHomeBounds = new Rect();
    private boolean mPendingStart = true;
    private final Rect mTmpRect = new Rect();
    final WindowManagerInternal.AppTransitionListener mAppTransitionListener = new WindowManagerInternal.AppTransitionListener() { // from class: com.android.server.wm.RecentsAnimationController.1
        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public int onAppTransitionStartingLocked(int transit, long duration, long statusBarAnimationStartTime, long statusBarAnimationDuration) {
            continueDeferredCancel();
            return 0;
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionCancelledLocked(int transit) {
            continueDeferredCancel();
        }

        private void continueDeferredCancel() {
            RecentsAnimationController.this.mDisplayContent.mAppTransition.unregisterListener(this);
            if (!RecentsAnimationController.this.mCanceled && RecentsAnimationController.this.mCancelOnNextTransitionStart) {
                RecentsAnimationController.this.mCancelOnNextTransitionStart = false;
                RecentsAnimationController recentsAnimationController = RecentsAnimationController.this;
                recentsAnimationController.cancelAnimationWithScreenshot(recentsAnimationController.mCancelDeferredWithScreenshot);
            }
        }
    };
    private final IRecentsAnimationController mController = new IRecentsAnimationController.Stub() { // from class: com.android.server.wm.RecentsAnimationController.2
        public ActivityManager.TaskSnapshot screenshotTask(int taskId) {
            if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                Slog.d(RecentsAnimationController.TAG, "screenshotTask(" + taskId + "): mCanceled=" + RecentsAnimationController.this.mCanceled);
            }
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    if (RecentsAnimationController.this.mCanceled) {
                        return null;
                    }
                    for (int i = RecentsAnimationController.this.mPendingAnimations.size() - 1; i >= 0; i--) {
                        TaskAnimationAdapter adapter = (TaskAnimationAdapter) RecentsAnimationController.this.mPendingAnimations.get(i);
                        Task task = adapter.mTask;
                        if (task.mTaskId == taskId) {
                            TaskSnapshotController snapshotController = RecentsAnimationController.this.mService.mTaskSnapshotController;
                            ArraySet<Task> tasks = Sets.newArraySet(new Task[]{task});
                            snapshotController.snapshotTasks(tasks);
                            snapshotController.addSkipClosingAppSnapshotTasks(tasks);
                            return snapshotController.getSnapshot(taskId, 0, false, false);
                        }
                    }
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void finish(boolean moveHomeToTop, boolean sendUserLeaveHint) {
            int i;
            if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                Slog.d(RecentsAnimationController.TAG, "finish(" + moveHomeToTop + "): mCanceled=" + RecentsAnimationController.this.mCanceled);
            }
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    if (!RecentsAnimationController.this.mCanceled) {
                        RecentsAnimationCallbacks recentsAnimationCallbacks = RecentsAnimationController.this.mCallbacks;
                        if (moveHomeToTop) {
                            i = 1;
                        } else {
                            i = 2;
                        }
                        recentsAnimationCallbacks.onAnimationFinished(i, true, sendUserLeaveHint);
                        RecentsAnimationController.this.mDisplayContent.mBoundsAnimationController.setAnimationType(1);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars) throws RemoteException {
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    for (int i = RecentsAnimationController.this.mPendingAnimations.size() - 1; i >= 0; i--) {
                        Task task = ((TaskAnimationAdapter) RecentsAnimationController.this.mPendingAnimations.get(i)).mTask;
                        if (task.getActivityType() != RecentsAnimationController.this.mTargetActivityType) {
                            task.setCanAffectSystemUiFlags(behindSystemBars);
                        }
                    }
                    RecentsAnimationController.this.mService.mWindowPlacerLocked.requestTraversal();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setInputConsumerEnabled(boolean enabled) {
            if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                String str = RecentsAnimationController.TAG;
                Slog.d(str, "setInputConsumerEnabled(" + enabled + "): mCanceled=" + RecentsAnimationController.this.mCanceled);
            }
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    if (!RecentsAnimationController.this.mCanceled) {
                        RecentsAnimationController.this.mInputConsumerEnabled = enabled;
                        InputMonitor inputMonitor = RecentsAnimationController.this.mDisplayContent.getInputMonitor();
                        inputMonitor.updateInputWindowsLw(true);
                        RecentsAnimationController.this.mService.scheduleAnimationLocked();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setSplitScreenMinimized(boolean minimized) {
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    if (!RecentsAnimationController.this.mCanceled) {
                        RecentsAnimationController.this.mSplitScreenMinimized = minimized;
                        RecentsAnimationController.this.mService.checkSplitScreenMinimizedChanged(true);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void hideCurrentInputMethod() {
            long token = Binder.clearCallingIdentity();
            try {
                InputMethodManagerInternal inputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
                if (inputMethodManagerInternal != null) {
                    inputMethodManagerInternal.hideCurrentInputMethod();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Deprecated
        public void setCancelWithDeferredScreenshot(boolean screenshot) {
            synchronized (RecentsAnimationController.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    RecentsAnimationController.this.setDeferredCancel(true, screenshot);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
            synchronized (RecentsAnimationController.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    RecentsAnimationController.this.setDeferredCancel(defer, screenshot);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        public void cleanupScreenshot() {
            synchronized (RecentsAnimationController.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (RecentsAnimationController.this.mRecentScreenshotAnimator != null) {
                        RecentsAnimationController.this.mRecentScreenshotAnimator.cancelAnimation();
                        RecentsAnimationController.this.mRecentScreenshotAnimator = null;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }
    };
    private final StatusBarManagerInternal mStatusBar = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);

    /* loaded from: classes2.dex */
    public interface RecentsAnimationCallbacks {
        void onAnimationFinished(@ReorderMode int i, boolean z, boolean z2);
    }

    /* loaded from: classes2.dex */
    public @interface ReorderMode {
    }

    public /* synthetic */ void lambda$new$0$RecentsAnimationController() {
        cancelAnimation(2, "failSafeRunnable");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentsAnimationController(WindowManagerService service, IRecentsAnimationRunner remoteAnimationRunner, RecentsAnimationCallbacks callbacks, int displayId) {
        this.mService = service;
        this.mRunner = remoteAnimationRunner;
        this.mCallbacks = callbacks;
        this.mDisplayId = displayId;
        this.mDisplayContent = service.mRoot.getDisplayContent(displayId);
    }

    public void initialize(int targetActivityType, SparseBooleanArray recentTaskIds) {
        this.mTargetActivityType = targetActivityType;
        this.mDisplayContent.mAppTransition.registerListenerLocked(this.mAppTransitionListener);
        ArrayList<Task> visibleTasks = this.mDisplayContent.getVisibleTasks();
        TaskStack targetStack = this.mDisplayContent.getStack(0, targetActivityType);
        if (targetStack != null) {
            for (int i = targetStack.getChildCount() - 1; i >= 0; i--) {
                Task t = (Task) targetStack.getChildAt(i);
                if (!visibleTasks.contains(t)) {
                    visibleTasks.add(t);
                }
            }
        }
        int taskCount = visibleTasks.size();
        for (int i2 = 0; i2 < taskCount; i2++) {
            Task task = visibleTasks.get(i2);
            WindowConfiguration config = task.getWindowConfiguration();
            if (!config.tasksAreFloating() && config.getWindowingMode() != 3) {
                addAnimation(task, !recentTaskIds.get(task.mTaskId));
            }
        }
        if (this.mPendingAnimations.isEmpty()) {
            cancelAnimation(2, "initialize-noVisibleTasks");
            return;
        }
        try {
            linkToDeathOfRunner();
            AppWindowToken recentsComponentAppToken = targetStack.getTopChild().getTopFullscreenAppToken();
            if (recentsComponentAppToken != null) {
                if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                    Slog.d(TAG, "setHomeApp(" + recentsComponentAppToken.getName() + ")");
                }
                this.mTargetAppToken = recentsComponentAppToken;
                if (recentsComponentAppToken.windowsCanBeWallpaperTarget()) {
                    this.mDisplayContent.pendingLayoutChanges |= 4;
                    this.mDisplayContent.setLayoutNeeded();
                }
            }
            TaskStack dockedStack = this.mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            this.mDisplayContent.getDockedDividerController().getHomeStackBoundsInDockedMode(this.mDisplayContent.getConfiguration(), dockedStack == null ? -1 : dockedStack.getDockSide(), this.mMinimizedHomeBounds);
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
            StatusBarManagerInternal statusBarManagerInternal = this.mStatusBar;
            if (statusBarManagerInternal != null) {
                statusBarManagerInternal.onRecentsAnimationStateChanged(true);
            }
        } catch (RemoteException e) {
            cancelAnimation(2, "initialize-failedToLinkToDeath");
        }
    }

    @VisibleForTesting
    AnimationAdapter addAnimation(Task task, boolean isRecentTaskInvisible) {
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "addAnimation(" + task.getName() + ")");
        }
        TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task, isRecentTaskInvisible);
        task.startAnimation(task.getPendingTransaction(), taskAdapter, false);
        task.commitPendingTransaction();
        this.mPendingAnimations.add(taskAdapter);
        return taskAdapter;
    }

    @VisibleForTesting
    void removeAnimation(TaskAnimationAdapter taskAdapter) {
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "removeAnimation(" + taskAdapter.mTask.mTaskId + ")");
        }
        taskAdapter.mTask.setCanAffectSystemUiFlags(true);
        taskAdapter.mCapturedFinishCallback.onAnimationFinished(taskAdapter);
        this.mPendingAnimations.remove(taskAdapter);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation() {
        ArrayList<RemoteAnimationTarget> appAnimations;
        Rect minimizedHomeBounds;
        Rect contentInsets;
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "startAnimation(): mPendingStart=" + this.mPendingStart + " mCanceled=" + this.mCanceled);
        }
        if (!this.mPendingStart || this.mCanceled) {
            return;
        }
        try {
            appAnimations = new ArrayList<>();
            for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
                TaskAnimationAdapter taskAdapter = this.mPendingAnimations.get(i);
                RemoteAnimationTarget target = taskAdapter.createRemoteAnimationApp();
                if (target != null) {
                    appAnimations.add(target);
                } else {
                    removeAnimation(taskAdapter);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start recents animation", e);
        }
        if (appAnimations.isEmpty()) {
            cancelAnimation(2, "startAnimation-noAppWindows");
            return;
        }
        RemoteAnimationTarget[] appTargets = (RemoteAnimationTarget[]) appAnimations.toArray(new RemoteAnimationTarget[appAnimations.size()]);
        this.mPendingStart = false;
        this.mDisplayContent.performLayout(false, false);
        if (this.mTargetAppToken != null && this.mTargetAppToken.inSplitScreenSecondaryWindowingMode()) {
            minimizedHomeBounds = this.mMinimizedHomeBounds;
        } else {
            minimizedHomeBounds = null;
        }
        if (this.mTargetAppToken != null && this.mTargetAppToken.findMainWindow() != null) {
            contentInsets = this.mTargetAppToken.findMainWindow().getContentInsets();
        } else {
            this.mService.getStableInsets(this.mDisplayId, this.mTmpRect);
            contentInsets = this.mTmpRect;
        }
        this.mRunner.onAnimationStart(this.mController, appTargets, contentInsets, minimizedHomeBounds);
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "startAnimation(): Notify animation start:");
            for (int i2 = 0; i2 < this.mPendingAnimations.size(); i2++) {
                Task task = this.mPendingAnimations.get(i2).mTask;
                Slog.d(TAG, "\t" + task.mTaskId);
            }
        }
        SparseIntArray reasons = new SparseIntArray();
        reasons.put(1, 5);
        this.mService.mAtmInternal.notifyAppTransitionStarting(reasons, SystemClock.uptimeMillis());
    }

    void cancelAnimation(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, false, false, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimationSynchronously(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, true, false, reason);
    }

    void cancelAnimationWithScreenshot(boolean screenshot) {
        cancelAnimation(0, true, screenshot, "stackOrderChanged");
    }

    private void cancelAnimation(@ReorderMode int reorderMode, boolean runSynchronously, boolean screenshot, String reason) {
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "cancelAnimation(): reason=" + reason + " runSynchronously=" + runSynchronously);
        }
        synchronized (this.mService.getWindowManagerLock()) {
            if (this.mCanceled) {
                return;
            }
            this.mService.mH.removeCallbacks(this.mFailsafeRunnable);
            this.mCanceled = true;
            if (!screenshot) {
                try {
                    this.mRunner.onAnimationCanceled(false);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to cancel recents animation", e);
                }
                this.mCallbacks.onAnimationFinished(reorderMode, runSynchronously, false);
                return;
            }
            Task task = this.mPendingAnimations.get(0).mTask;
            screenshotRecentTask(task, reorderMode, runSynchronously);
            try {
                this.mRunner.onAnimationCanceled(true);
            } catch (RemoteException e2) {
                Slog.e(TAG, "Failed to cancel recents animation", e2);
            }
            return;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCancelOnNextTransitionStart() {
        this.mCancelOnNextTransitionStart = true;
    }

    void setDeferredCancel(boolean defer, boolean screenshot) {
        this.mRequestDeferCancelUntilNextTransition = defer;
        this.mCancelDeferredWithScreenshot = screenshot;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldDeferCancelUntilNextTransition() {
        return this.mRequestDeferCancelUntilNextTransition;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldDeferCancelWithScreenshot() {
        return this.mRequestDeferCancelUntilNextTransition && this.mCancelDeferredWithScreenshot;
    }

    void screenshotRecentTask(Task task, @ReorderMode final int reorderMode, final boolean runSynchronously) {
        TaskScreenshotAnimatable animatable = TaskScreenshotAnimatable.create(task);
        if (animatable != null) {
            this.mRecentScreenshotAnimator = new SurfaceAnimator(animatable, new Runnable() { // from class: com.android.server.wm.-$$Lambda$RecentsAnimationController$UtmXbQuPny5O24HGUrj6wbS-P2A
                @Override // java.lang.Runnable
                public final void run() {
                    RecentsAnimationController.this.lambda$screenshotRecentTask$1$RecentsAnimationController(reorderMode, runSynchronously);
                }
            }, this.mService);
            this.mRecentScreenshotAnimator.transferAnimation(task.mSurfaceAnimator);
        }
    }

    public /* synthetic */ void lambda$screenshotRecentTask$1$RecentsAnimationController(int reorderMode, boolean runSynchronously) {
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "mRecentScreenshotAnimator finish");
        }
        this.mCallbacks.onAnimationFinished(reorderMode, runSynchronously, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanupAnimation(@ReorderMode int reorderMode) {
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "cleanupAnimation(): Notify animation finished mPendingAnimations=" + this.mPendingAnimations.size() + " reorderMode=" + reorderMode);
        }
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            TaskAnimationAdapter taskAdapter = this.mPendingAnimations.get(i);
            if (reorderMode == 1 || reorderMode == 0) {
                taskAdapter.mTask.dontAnimateDimExit();
            }
            removeAnimation(taskAdapter);
        }
        this.mService.mH.removeCallbacks(this.mFailsafeRunnable);
        this.mDisplayContent.mAppTransition.unregisterListener(this.mAppTransitionListener);
        unlinkToDeathOfRunner();
        this.mRunner = null;
        this.mCanceled = true;
        SurfaceAnimator surfaceAnimator = this.mRecentScreenshotAnimator;
        if (surfaceAnimator != null) {
            surfaceAnimator.cancelAnimation();
            this.mRecentScreenshotAnimator = null;
        }
        InputMonitor inputMonitor = this.mDisplayContent.getInputMonitor();
        inputMonitor.updateInputWindowsLw(true);
        if (this.mTargetAppToken != null && (reorderMode == 1 || reorderMode == 0)) {
            this.mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(this.mTargetAppToken.token);
        }
        StatusBarManagerInternal statusBarManagerInternal = this.mStatusBar;
        if (statusBarManagerInternal != null) {
            statusBarManagerInternal.onRecentsAnimationStateChanged(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleFailsafe() {
        this.mService.mH.postDelayed(this.mFailsafeRunnable, 1000L);
    }

    private void linkToDeathOfRunner() throws RemoteException {
        if (!this.mLinkedToDeathOfRunner) {
            this.mRunner.asBinder().linkToDeath(this, 0);
            this.mLinkedToDeathOfRunner = true;
        }
    }

    private void unlinkToDeathOfRunner() {
        if (this.mLinkedToDeathOfRunner) {
            this.mRunner.asBinder().unlinkToDeath(this, 0);
            this.mLinkedToDeathOfRunner = false;
        }
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        cancelAnimation(2, "binderDied");
        synchronized (this.mService.getWindowManagerLock()) {
            InputMonitor inputMonitor = this.mDisplayContent.getInputMonitor();
            inputMonitor.destroyInputConsumer("recents_animation_input_consumer");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkAnimationReady(WallpaperController wallpaperController) {
        if (this.mPendingStart) {
            boolean wallpaperReady = !isTargetOverWallpaper() || (wallpaperController.getWallpaperTarget() != null && wallpaperController.wallpaperTransitionReady());
            if (wallpaperReady) {
                this.mService.getRecentsAnimationController().startAnimation();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSplitScreenMinimized() {
        return this.mSplitScreenMinimized;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWallpaperVisible(WindowState w) {
        return w != null && w.mAppToken != null && this.mTargetAppToken == w.mAppToken && isTargetOverWallpaper();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldApplyInputConsumer(AppWindowToken appToken) {
        return this.mInputConsumerEnabled && this.mTargetAppToken != appToken && isAnimatingApp(appToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateInputConsumerForApp(InputWindowHandle inputWindowHandle, boolean hasFocus) {
        WindowState targetAppMainWindow;
        AppWindowToken appWindowToken = this.mTargetAppToken;
        if (appWindowToken != null) {
            targetAppMainWindow = appWindowToken.findMainWindow();
        } else {
            targetAppMainWindow = null;
        }
        if (targetAppMainWindow != null) {
            targetAppMainWindow.getBounds(this.mTmpRect);
            inputWindowHandle.hasFocus = hasFocus;
            inputWindowHandle.touchableRegion.set(this.mTmpRect);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTargetApp(AppWindowToken token) {
        AppWindowToken appWindowToken = this.mTargetAppToken;
        return appWindowToken != null && token == appWindowToken;
    }

    private boolean isTargetOverWallpaper() {
        AppWindowToken appWindowToken = this.mTargetAppToken;
        if (appWindowToken == null) {
            return false;
        }
        return appWindowToken.windowsCanBeWallpaperTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimatingTask(Task task) {
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            if (task == this.mPendingAnimations.get(i).mTask) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnimatingApp(AppWindowToken appToken) {
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            Task task = this.mPendingAnimations.get(i).mTask;
            for (int j = task.getChildCount() - 1; j >= 0; j--) {
                AppWindowToken app = (AppWindowToken) task.getChildAt(j);
                if (app == appToken) {
                    return true;
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes2.dex */
    public class TaskAnimationAdapter implements AnimationAdapter {
        private SurfaceAnimator.OnAnimationFinishedCallback mCapturedFinishCallback;
        private SurfaceControl mCapturedLeash;
        private final boolean mIsRecentTaskInvisible;
        private RemoteAnimationTarget mTarget;
        private final Task mTask;
        private final Point mPosition = new Point();
        private final Rect mBounds = new Rect();

        TaskAnimationAdapter(Task task, boolean isRecentTaskInvisible) {
            this.mTask = task;
            this.mIsRecentTaskInvisible = isRecentTaskInvisible;
            WindowContainer container = this.mTask.getParent();
            container.getRelativeDisplayedPosition(this.mPosition);
            this.mBounds.set(container.getDisplayedBounds());
        }

        RemoteAnimationTarget createRemoteAnimationApp() {
            WindowState mainWindow;
            int mode;
            AppWindowToken topApp = this.mTask.getTopVisibleAppToken();
            if (topApp != null) {
                mainWindow = topApp.findMainWindow();
            } else {
                mainWindow = null;
            }
            if (mainWindow == null) {
                return null;
            }
            Rect insets = new Rect();
            mainWindow.getContentInsets(insets);
            InsetUtils.addInsets(insets, mainWindow.mAppToken.getLetterboxInsets());
            if (topApp.getActivityType() == RecentsAnimationController.this.mTargetActivityType) {
                mode = 0;
            } else {
                mode = 1;
            }
            this.mTarget = new RemoteAnimationTarget(this.mTask.mTaskId, mode, this.mCapturedLeash, !topApp.fillsParent(), mainWindow.mWinAnimator.mLastClipRect, insets, this.mTask.getPrefixOrderIndex(), this.mPosition, this.mBounds, this.mTask.getWindowConfiguration(), this.mIsRecentTaskInvisible, (SurfaceControl) null, (Rect) null);
            return this.mTarget;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public boolean getShowWallpaper() {
            return false;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public int getBackgroundColor() {
            return 0;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
            t.setLayer(animationLeash, this.mTask.getPrefixOrderIndex());
            t.setPosition(animationLeash, this.mPosition.x, this.mPosition.y);
            RecentsAnimationController.this.mTmpRect.set(this.mBounds);
            RecentsAnimationController.this.mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, RecentsAnimationController.this.mTmpRect);
            this.mCapturedLeash = animationLeash;
            this.mCapturedFinishCallback = finishCallback;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            RecentsAnimationController.this.cancelAnimation(2, "taskAnimationAdapterCanceled");
        }

        @Override // com.android.server.wm.AnimationAdapter
        public long getDurationHint() {
            return 0L;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis();
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("task=" + this.mTask);
            if (this.mTarget != null) {
                pw.print(prefix);
                pw.println("Target:");
                RemoteAnimationTarget remoteAnimationTarget = this.mTarget;
                remoteAnimationTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix);
                pw.println("Target: null");
            }
            pw.println("mIsRecentTaskInvisible=" + this.mIsRecentTaskInvisible);
            pw.println("mPosition=" + this.mPosition);
            pw.println("mBounds=" + this.mBounds);
            pw.println("mIsRecentTaskInvisible=" + this.mIsRecentTaskInvisible);
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void writeToProto(ProtoOutputStream proto) {
            long token = proto.start(1146756268034L);
            RemoteAnimationTarget remoteAnimationTarget = this.mTarget;
            if (remoteAnimationTarget != null) {
                remoteAnimationTarget.writeToProto(proto, 1146756268033L);
            }
            proto.end(token);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        String innerPrefix = prefix + "  ";
        pw.print(prefix);
        pw.println(RecentsAnimationController.class.getSimpleName() + ":");
        pw.print(innerPrefix);
        pw.println("mPendingStart=" + this.mPendingStart);
        pw.print(innerPrefix);
        pw.println("mPendingAnimations=" + this.mPendingAnimations.size());
        pw.print(innerPrefix);
        pw.println("mCanceled=" + this.mCanceled);
        pw.print(innerPrefix);
        pw.println("mInputConsumerEnabled=" + this.mInputConsumerEnabled);
        pw.print(innerPrefix);
        pw.println("mSplitScreenMinimized=" + this.mSplitScreenMinimized);
        pw.print(innerPrefix);
        pw.println("mTargetAppToken=" + this.mTargetAppToken);
        pw.print(innerPrefix);
        pw.println("isTargetOverWallpaper=" + isTargetOverWallpaper());
        pw.print(innerPrefix);
        pw.println("mRequestDeferCancelUntilNextTransition=" + this.mRequestDeferCancelUntilNextTransition);
        pw.print(innerPrefix);
        pw.println("mCancelOnNextTransitionStart=" + this.mCancelOnNextTransitionStart);
        pw.print(innerPrefix);
        pw.println("mCancelDeferredWithScreenshot=" + this.mCancelDeferredWithScreenshot);
    }
}
