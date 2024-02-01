package com.android.server.wm;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import android.view.DisplayListCanvas;
import android.view.RenderNode;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.WindowManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.TaskSnapshotPersister;
import com.android.server.wm.TaskSnapshotSurface;
import com.android.server.wm.utils.InsetUtils;
import com.google.android.collect.Sets;
import java.io.File;
import java.io.PrintWriter;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskSnapshotController {
    @VisibleForTesting
    static final int SNAPSHOT_MODE_APP_THEME = 1;
    @VisibleForTesting
    static final int SNAPSHOT_MODE_NONE = 2;
    @VisibleForTesting
    static final int SNAPSHOT_MODE_REAL = 0;
    private static final String TAG = "WindowManager";
    private final TaskSnapshotCache mCache;
    private final boolean mIsRunningOnIoT;
    private final boolean mIsRunningOnTv;
    private final boolean mIsRunningOnWear;
    private final WindowManagerService mService;
    private final TaskSnapshotPersister mPersister = new TaskSnapshotPersister(new TaskSnapshotPersister.DirectoryResolver() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$OPdXuZQLetMnocdH6XV32JbNQ3I
        @Override // com.android.server.wm.TaskSnapshotPersister.DirectoryResolver
        public final File getSystemDirectoryForUser(int i) {
            File dataSystemCeDirectory;
            dataSystemCeDirectory = Environment.getDataSystemCeDirectory(i);
            return dataSystemCeDirectory;
        }
    });
    private final TaskSnapshotLoader mLoader = new TaskSnapshotLoader(this.mPersister);
    private final ArraySet<Task> mSkipClosingAppSnapshotTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();
    private final Rect mTmpRect = new Rect();

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskSnapshotController(WindowManagerService service) {
        this.mService = service;
        this.mCache = new TaskSnapshotCache(this.mService, this.mLoader);
        this.mIsRunningOnTv = this.mService.mContext.getPackageManager().hasSystemFeature("android.software.leanback");
        this.mIsRunningOnIoT = this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.embedded");
        this.mIsRunningOnWear = this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mPersister.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTransitionStarting() {
        handleClosingApps(this.mService.mClosingApps);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppVisibilityChanged(AppWindowToken appWindowToken, boolean visible) {
        if (!visible) {
            handleClosingApps(Sets.newArraySet(new AppWindowToken[]{appWindowToken}));
        }
    }

    private void handleClosingApps(ArraySet<AppWindowToken> closingApps) {
        if (shouldDisableSnapshots()) {
            return;
        }
        getClosingTasks(closingApps, this.mTmpTasks);
        snapshotTasks(this.mTmpTasks);
        this.mSkipClosingAppSnapshotTasks.clear();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void addSkipClosingAppSnapshotTasks(ArraySet<Task> tasks) {
        this.mSkipClosingAppSnapshotTasks.addAll((ArraySet<? extends Task>) tasks);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void snapshotTasks(ArraySet<Task> tasks) {
        ActivityManager.TaskSnapshot snapshot;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.valueAt(i);
            int mode = getSnapshotMode(task);
            switch (mode) {
                case 0:
                    snapshot = snapshotTask(task);
                    break;
                case 1:
                    snapshot = drawAppThemeSnapshot(task);
                    break;
                case 2:
                default:
                    snapshot = null;
                    break;
            }
            if (snapshot != null) {
                GraphicBuffer buffer = snapshot.getSnapshot();
                if (buffer.getWidth() == 0 || buffer.getHeight() == 0) {
                    buffer.destroy();
                    Slog.e(TAG, "Invalid task snapshot dimensions " + buffer.getWidth() + "x" + buffer.getHeight());
                } else {
                    this.mCache.putSnapshot(task, snapshot);
                    this.mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
                    if (task.getController() != null) {
                        task.getController().reportSnapshotChanged(snapshot);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk, boolean reducedResolution) {
        return this.mCache.getSnapshot(taskId, userId, restoreFromDisk, reducedResolution || TaskSnapshotPersister.DISABLE_FULL_SIZED_BITMAPS);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowManagerPolicy.StartingSurface createStartingSurface(AppWindowToken token, ActivityManager.TaskSnapshot snapshot) {
        return TaskSnapshotSurface.create(this.mService, token, snapshot);
    }

    private ActivityManager.TaskSnapshot snapshotTask(Task task) {
        WindowState mainWindow;
        AppWindowToken top = task.getTopChild();
        if (top == null || (mainWindow = top.findMainWindow()) == null) {
            return null;
        }
        if (!this.mService.mPolicy.isScreenOn()) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.i(TAG, "Attempted to take screenshot while display was off.");
            }
            return null;
        } else if (task.getSurfaceControl() == null) {
            return null;
        } else {
            if (top.hasCommittedReparentToAnimationLeash()) {
                if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                    Slog.w(TAG, "Failed to take screenshot. App is animating " + top);
                }
                return null;
            }
            boolean z = true;
            boolean hasVisibleChild = top.forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$1IXTXVXjIGs9ncGKW_v40ivZeoI
                public final boolean apply(Object obj) {
                    return TaskSnapshotController.lambda$snapshotTask$0((WindowState) obj);
                }
            }, true);
            if (!hasVisibleChild) {
                if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                    Slog.w(TAG, "Failed to take screenshot. No visible windows for " + task);
                }
                return null;
            }
            boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
            float scaleFraction = isLowRamDevice ? TaskSnapshotPersister.REDUCED_SCALE : 1.0f;
            task.getBounds(this.mTmpRect);
            this.mTmpRect.offsetTo(0, 0);
            GraphicBuffer buffer = SurfaceControl.captureLayers(task.getSurfaceControl().getHandle(), this.mTmpRect, scaleFraction);
            boolean isWindowTranslucent = mainWindow.getAttrs().format != -1;
            if (buffer != null && buffer.getWidth() > 1) {
                if (buffer.getHeight() > 1) {
                    int i = top.getConfiguration().orientation;
                    Rect insets = getInsets(mainWindow);
                    int windowingMode = task.getWindowingMode();
                    int systemUiVisibility = getSystemUiVisibility(task);
                    if (top.fillsParent() && !isWindowTranslucent) {
                        z = false;
                    }
                    return new ActivityManager.TaskSnapshot(buffer, i, insets, isLowRamDevice, scaleFraction, true, windowingMode, systemUiVisibility, z);
                }
            }
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.w(TAG, "Failed to take screenshot for " + task);
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$snapshotTask$0(WindowState ws) {
        return (ws.mAppToken == null || ws.mAppToken.isSurfaceShowing()) && ws.mWinAnimator != null && ws.mWinAnimator.getShown() && ws.mWinAnimator.mLastAlpha > 0.0f;
    }

    private boolean shouldDisableSnapshots() {
        return this.mIsRunningOnWear || this.mIsRunningOnTv || this.mIsRunningOnIoT;
    }

    private Rect getInsets(WindowState state) {
        Rect insets = minRect(state.mContentInsets, state.mStableInsets);
        InsetUtils.addInsets(insets, state.mAppToken.getLetterboxInsets());
        return insets;
    }

    private Rect minRect(Rect rect1, Rect rect2) {
        return new Rect(Math.min(rect1.left, rect2.left), Math.min(rect1.top, rect2.top), Math.min(rect1.right, rect2.right), Math.min(rect1.bottom, rect2.bottom));
    }

    @VisibleForTesting
    void getClosingTasks(ArraySet<AppWindowToken> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            AppWindowToken atoken = closingApps.valueAt(i);
            Task task = atoken.getTask();
            if (task != null && !task.isVisible() && !this.mSkipClosingAppSnapshotTasks.contains(task)) {
                outClosingTasks.add(task);
            }
        }
    }

    @VisibleForTesting
    int getSnapshotMode(Task task) {
        AppWindowToken topChild = task.getTopChild();
        if (!task.isActivityTypeStandardOrUndefined() && !task.isActivityTypeAssistant()) {
            return 2;
        }
        if (topChild != null && topChild.shouldUseAppThemeSnapshot()) {
            return 1;
        }
        return 0;
    }

    private ActivityManager.TaskSnapshot drawAppThemeSnapshot(Task task) {
        WindowState mainWindow;
        AppWindowToken topChild = task.getTopChild();
        if (topChild == null || (mainWindow = topChild.findMainWindow()) == null) {
            return null;
        }
        int color = ColorUtils.setAlphaComponent(task.getTaskDescription().getBackgroundColor(), 255);
        int statusBarColor = task.getTaskDescription().getStatusBarColor();
        int navigationBarColor = task.getTaskDescription().getNavigationBarColor();
        WindowManager.LayoutParams attrs = mainWindow.getAttrs();
        TaskSnapshotSurface.SystemBarBackgroundPainter decorPainter = new TaskSnapshotSurface.SystemBarBackgroundPainter(attrs.flags, attrs.privateFlags, attrs.systemUiVisibility, statusBarColor, navigationBarColor);
        int width = mainWindow.getFrameLw().width();
        int height = mainWindow.getFrameLw().height();
        RenderNode node = RenderNode.create("TaskSnapshotController", (View) null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        DisplayListCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(mainWindow.mContentInsets, mainWindow.mStableInsets);
        decorPainter.drawDecors(c, null);
        node.end(c);
        Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }
        return new ActivityManager.TaskSnapshot(hwBitmap.createGraphicBufferHandle(), topChild.getConfiguration().orientation, mainWindow.mStableInsets, ActivityManager.isLowRamDeviceStatic(), 1.0f, false, task.getWindowingMode(), getSystemUiVisibility(task), false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAppRemoved(AppWindowToken wtoken) {
        this.mCache.onAppRemoved(wtoken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAppDied(AppWindowToken wtoken) {
        this.mCache.onAppDied(wtoken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskRemovedFromRecents(int taskId, int userId) {
        this.mCache.onTaskRemoved(taskId);
        this.mPersister.onTaskRemovedFromRecents(taskId, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        this.mPersister.removeObsoleteFiles(persistentTaskIds, runningUserIds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPersisterPaused(boolean paused) {
        this.mPersister.setPaused(paused);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void screenTurningOff(final WindowManagerPolicy.ScreenOffListener listener) {
        if (shouldDisableSnapshots()) {
            listener.onScreenOff();
        } else {
            this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$q-BG2kMqHK9gvuY43J0TfS4aSVU
                @Override // java.lang.Runnable
                public final void run() {
                    TaskSnapshotController.lambda$screenTurningOff$2(TaskSnapshotController.this, listener);
                }
            });
        }
    }

    public static /* synthetic */ void lambda$screenTurningOff$2(final TaskSnapshotController taskSnapshotController, WindowManagerPolicy.ScreenOffListener listener) {
        try {
            synchronized (taskSnapshotController.mService.mWindowMap) {
                WindowManagerService.boostPriorityForLockedSection();
                taskSnapshotController.mTmpTasks.clear();
                taskSnapshotController.mService.mRoot.forAllTasks(new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$ewi-Dm2ws6pdTXd1elso7FtoLKw
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        TaskSnapshotController.lambda$screenTurningOff$1(TaskSnapshotController.this, (Task) obj);
                    }
                });
                taskSnapshotController.snapshotTasks(taskSnapshotController.mTmpTasks);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            listener.onScreenOff();
        }
    }

    public static /* synthetic */ void lambda$screenTurningOff$1(TaskSnapshotController taskSnapshotController, Task task) {
        if (task.isVisible()) {
            taskSnapshotController.mTmpTasks.add(task);
        }
    }

    private int getSystemUiVisibility(Task task) {
        WindowState topFullscreenWindow;
        AppWindowToken topFullscreenToken = task.getTopFullscreenAppToken();
        if (topFullscreenToken != null) {
            topFullscreenWindow = topFullscreenToken.getTopFullscreenWindow();
        } else {
            topFullscreenWindow = null;
        }
        if (topFullscreenWindow != null) {
            return topFullscreenWindow.getSystemUiVisibility();
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        this.mCache.dump(pw, prefix);
    }
}
