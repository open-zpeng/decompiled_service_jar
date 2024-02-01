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
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.inputmethod.InputMethodManagerInternal;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.utils.InsetUtils;
import com.google.android.collect.Sets;
import java.io.PrintWriter;
import java.util.ArrayList;
/* loaded from: classes.dex */
public class RecentsAnimationController implements IBinder.DeathRecipient {
    private static final long FAILSAFE_DELAY = 1000;
    public static final int REORDER_KEEP_IN_PLACE = 0;
    public static final int REORDER_MOVE_TO_ORIGINAL_POSITION = 2;
    public static final int REORDER_MOVE_TO_TOP = 1;
    private static final String TAG = RecentsAnimationController.class.getSimpleName();
    private final RecentsAnimationCallbacks mCallbacks;
    private boolean mCanceled;
    private final int mDisplayId;
    private boolean mInputConsumerEnabled;
    private boolean mLinkedToDeathOfRunner;
    private IRecentsAnimationRunner mRunner;
    private final WindowManagerService mService;
    private boolean mSplitScreenMinimized;
    private AppWindowToken mTargetAppToken;
    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();
    private final Runnable mFailsafeRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$RecentsAnimationController$4jQqaDgSmtGCjbUJiVoDh_jr9rY
        @Override // java.lang.Runnable
        public final void run() {
            RecentsAnimationController.this.cancelAnimation(2, "failSafeRunnable");
        }
    };
    private Rect mMinimizedHomeBounds = new Rect();
    private boolean mPendingStart = true;
    private final Rect mTmpRect = new Rect();
    private final IRecentsAnimationController mController = new IRecentsAnimationController.Stub() { // from class: com.android.server.wm.RecentsAnimationController.1
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

        public void finish(boolean moveHomeToTop) {
            if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                String str = RecentsAnimationController.TAG;
                Slog.d(str, "finish(" + moveHomeToTop + "): mCanceled=" + RecentsAnimationController.this.mCanceled);
            }
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (RecentsAnimationController.this.mService.getWindowManagerLock()) {
                    if (RecentsAnimationController.this.mCanceled) {
                        return;
                    }
                    RecentsAnimationController.this.mCallbacks.onAnimationFinished(moveHomeToTop ? 1 : 2, true);
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
                        ((TaskAnimationAdapter) RecentsAnimationController.this.mPendingAnimations.get(i)).mTask.setCanAffectSystemUiFlags(behindSystemBars);
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
                        RecentsAnimationController.this.mService.mInputMonitor.updateInputWindowsLw(true);
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
    };

    /* loaded from: classes.dex */
    public interface RecentsAnimationCallbacks {
        void onAnimationFinished(@ReorderMode int i, boolean z);
    }

    /* loaded from: classes.dex */
    public @interface ReorderMode {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentsAnimationController(WindowManagerService service, IRecentsAnimationRunner remoteAnimationRunner, RecentsAnimationCallbacks callbacks, int displayId) {
        this.mService = service;
        this.mRunner = remoteAnimationRunner;
        this.mCallbacks = callbacks;
        this.mDisplayId = displayId;
    }

    public void initialize(int targetActivityType, SparseBooleanArray recentTaskIds) {
        DisplayContent dc = this.mService.mRoot.getDisplayContent(this.mDisplayId);
        ArrayList<Task> visibleTasks = dc.getVisibleTasks();
        int taskCount = visibleTasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = visibleTasks.get(i);
            WindowConfiguration config = task.getWindowConfiguration();
            if (!config.tasksAreFloating() && config.getWindowingMode() != 3 && config.getActivityType() != targetActivityType) {
                addAnimation(task, !recentTaskIds.get(task.mTaskId));
            }
        }
        if (this.mPendingAnimations.isEmpty()) {
            cancelAnimation(2, "initialize-noVisibleTasks");
            return;
        }
        try {
            linkToDeathOfRunner();
            AppWindowToken recentsComponentAppToken = dc.getStack(0, targetActivityType).getTopChild().getTopFullscreenAppToken();
            if (recentsComponentAppToken != null) {
                if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
                    Slog.d(TAG, "setHomeApp(" + recentsComponentAppToken.getName() + ")");
                }
                this.mTargetAppToken = recentsComponentAppToken;
                if (recentsComponentAppToken.windowsCanBeWallpaperTarget()) {
                    dc.pendingLayoutChanges |= 4;
                    dc.setLayoutNeeded();
                }
            }
            dc.getDockedDividerController().getHomeStackBoundsInDockedMode(this.mMinimizedHomeBounds);
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
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
        SurfaceAnimator anim = new SurfaceAnimator(task, null, this.mService);
        TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task, isRecentTaskInvisible);
        anim.startAnimation(task.getPendingTransaction(), taskAdapter, false);
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

    void startAnimation() {
        ArrayList<RemoteAnimationTarget> appAnimations;
        Rect minimizedHomeBounds;
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
        Rect contentInsets = null;
        if (this.mTargetAppToken != null && this.mTargetAppToken.inSplitScreenSecondaryWindowingMode()) {
            minimizedHomeBounds = this.mMinimizedHomeBounds;
        } else {
            minimizedHomeBounds = null;
        }
        if (this.mTargetAppToken != null && this.mTargetAppToken.findMainWindow() != null) {
            contentInsets = this.mTargetAppToken.findMainWindow().mContentInsets;
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
        this.mService.mH.obtainMessage(47, reasons).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimation(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, false, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimationSynchronously(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, true, reason);
    }

    private void cancelAnimation(@ReorderMode int reorderMode, boolean runSynchronously, String reason) {
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
            try {
                this.mRunner.onAnimationCanceled();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to cancel recents animation", e);
            }
            this.mCallbacks.onAnimationFinished(reorderMode, runSynchronously);
        }
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
        unlinkToDeathOfRunner();
        this.mRunner = null;
        this.mCanceled = true;
        this.mService.mInputMonitor.updateInputWindowsLw(true);
        this.mService.destroyInputConsumer("recents_animation_input_consumer");
        if (this.mTargetAppToken != null) {
            if (reorderMode == 1 || reorderMode == 0) {
                this.mService.mAppTransition.notifyAppTransitionFinishedLocked(this.mTargetAppToken.token);
            }
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
    public boolean hasInputConsumerForApp(AppWindowToken appToken) {
        return this.mInputConsumerEnabled && isAnimatingApp(appToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateInputConsumerForApp(InputConsumerImpl recentsAnimationInputConsumer, boolean hasFocus) {
        WindowState targetAppMainWindow;
        if (this.mTargetAppToken != null) {
            targetAppMainWindow = this.mTargetAppToken.findMainWindow();
        } else {
            targetAppMainWindow = null;
        }
        if (targetAppMainWindow != null) {
            targetAppMainWindow.getBounds(this.mTmpRect);
            recentsAnimationInputConsumer.mWindowHandle.hasFocus = hasFocus;
            recentsAnimationInputConsumer.mWindowHandle.touchableRegion.set(this.mTmpRect);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTargetApp(AppWindowToken token) {
        return this.mTargetAppToken != null && token == this.mTargetAppToken;
    }

    private boolean isTargetOverWallpaper() {
        if (this.mTargetAppToken == null) {
            return false;
        }
        return this.mTargetAppToken.windowsCanBeWallpaperTarget();
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
    /* loaded from: classes.dex */
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
            container.getRelativePosition(this.mPosition);
            container.getBounds(this.mBounds);
        }

        RemoteAnimationTarget createRemoteAnimationApp() {
            WindowState mainWindow;
            AppWindowToken topApp = this.mTask.getTopVisibleAppToken();
            if (topApp != null) {
                mainWindow = topApp.findMainWindow();
            } else {
                mainWindow = null;
            }
            if (mainWindow == null) {
                return null;
            }
            Rect insets = new Rect(mainWindow.mContentInsets);
            InsetUtils.addInsets(insets, mainWindow.mAppToken.getLetterboxInsets());
            this.mTarget = new RemoteAnimationTarget(this.mTask.mTaskId, 1, this.mCapturedLeash, !topApp.fillsParent(), mainWindow.mWinAnimator.mLastClipRect, insets, this.mTask.getPrefixOrderIndex(), this.mPosition, this.mBounds, this.mTask.getWindowConfiguration(), this.mIsRecentTaskInvisible);
            return this.mTarget;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public boolean getDetachWallpaper() {
            return false;
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
            if (this.mTarget != null) {
                this.mTarget.writeToProto(proto, 1146756268033L);
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
        pw.println("mCanceled=" + this.mCanceled);
        pw.print(innerPrefix);
        pw.println("mInputConsumerEnabled=" + this.mInputConsumerEnabled);
        pw.print(innerPrefix);
        pw.println("mSplitScreenMinimized=" + this.mSplitScreenMinimized);
        pw.print(innerPrefix);
        pw.println("mTargetAppToken=" + this.mTargetAppToken);
        pw.print(innerPrefix);
        pw.println("isTargetOverWallpaper=" + isTargetOverWallpaper());
    }
}
