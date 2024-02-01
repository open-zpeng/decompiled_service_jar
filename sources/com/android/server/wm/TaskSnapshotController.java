package com.android.server.wm;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Environment;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
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
/* loaded from: classes2.dex */
public class TaskSnapshotController {
    @VisibleForTesting
    static final int SNAPSHOT_MODE_APP_THEME = 1;
    @VisibleForTesting
    static final int SNAPSHOT_MODE_NONE = 2;
    @VisibleForTesting
    static final int SNAPSHOT_MODE_REAL = 0;
    private static final String TAG = "WindowManager";
    private final TaskSnapshotCache mCache;
    private final float mFullSnapshotScale;
    private final boolean mIsRunningOnIoT;
    private final boolean mIsRunningOnTv;
    private final boolean mIsRunningOnWear;
    private final TaskSnapshotLoader mLoader;
    private final TaskSnapshotPersister mPersister;
    private final WindowManagerService mService;
    private final ArraySet<Task> mSkipClosingAppSnapshotTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();
    private final Rect mTmpRect = new Rect();

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskSnapshotController(WindowManagerService service) {
        this.mService = service;
        this.mPersister = new TaskSnapshotPersister(this.mService, new TaskSnapshotPersister.DirectoryResolver() { // from class: com.android.server.wm.-$$Lambda$OPdXuZQLetMnocdH6XV32JbNQ3I
            @Override // com.android.server.wm.TaskSnapshotPersister.DirectoryResolver
            public final File getSystemDirectoryForUser(int i) {
                return Environment.getDataSystemCeDirectory(i);
            }
        });
        this.mLoader = new TaskSnapshotLoader(this.mPersister);
        this.mCache = new TaskSnapshotCache(this.mService, this.mLoader);
        this.mIsRunningOnTv = this.mService.mContext.getPackageManager().hasSystemFeature("android.software.leanback");
        this.mIsRunningOnIoT = this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.embedded");
        this.mIsRunningOnWear = this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch");
        this.mFullSnapshotScale = this.mService.mContext.getResources().getFloat(17105060);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mPersister.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTransitionStarting(DisplayContent displayContent) {
        handleClosingApps(displayContent.mClosingApps);
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
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.valueAt(i);
            int mode = getSnapshotMode(task);
            if (mode != 2) {
                ActivityManager.TaskSnapshot snapshot = null;
                if (0 != 0) {
                    GraphicBuffer buffer = snapshot.getSnapshot();
                    if (buffer.getWidth() != 0 && buffer.getHeight() != 0) {
                        this.mCache.putSnapshot(task, null);
                        this.mPersister.persistSnapshot(task.mTaskId, task.mUserId, null);
                        task.onSnapshotChanged(null);
                    } else {
                        buffer.destroy();
                        Slog.e(TAG, "Invalid task snapshot dimensions " + buffer.getWidth() + "x" + buffer.getHeight());
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
        return null;
    }

    private AppWindowToken findAppTokenForSnapshot(Task task) {
        for (int i = task.getChildCount() - 1; i >= 0; i--) {
            AppWindowToken appWindowToken = (AppWindowToken) task.getChildAt(i);
            if (appWindowToken != null && appWindowToken.isSurfaceShowing() && appWindowToken.findMainWindow() != null) {
                boolean hasVisibleChild = appWindowToken.forAllWindows((ToBooleanFunction<WindowState>) new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$b7mc92hqzbRpmpc99dYS4wKuL6Y
                    public final boolean apply(Object obj) {
                        return TaskSnapshotController.lambda$findAppTokenForSnapshot$0((WindowState) obj);
                    }
                }, true);
                if (hasVisibleChild) {
                    return appWindowToken;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$findAppTokenForSnapshot$0(WindowState ws) {
        return ws.mWinAnimator != null && ws.mWinAnimator.getShown() && ws.mWinAnimator.mLastAlpha > 0.0f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.ScreenshotGraphicBuffer createTaskSnapshot(Task task, float scaleFraction) {
        if (task.getSurfaceControl() == null) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.w(TAG, "Failed to take screenshot. No surface control for " + task);
            }
            return null;
        }
        task.getBounds(this.mTmpRect);
        this.mTmpRect.offsetTo(0, 0);
        SurfaceControl.ScreenshotGraphicBuffer screenshotBuffer = SurfaceControl.captureLayers(task.getSurfaceControl().getHandle(), this.mTmpRect, scaleFraction);
        GraphicBuffer buffer = screenshotBuffer != null ? screenshotBuffer.getGraphicBuffer() : null;
        if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
            return null;
        }
        return screenshotBuffer;
    }

    private ActivityManager.TaskSnapshot snapshotTask(Task task) {
        float f;
        if (!this.mService.mPolicy.isScreenOn()) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.i(TAG, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        AppWindowToken appWindowToken = findAppTokenForSnapshot(task);
        if (appWindowToken == null) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.w(TAG, "Failed to take screenshot. No visible windows for " + task);
            }
            return null;
        } else if (appWindowToken.hasCommittedReparentToAnimationLeash()) {
            if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                Slog.w(TAG, "Failed to take screenshot. App is animating " + appWindowToken);
            }
            return null;
        } else {
            boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
            if (isLowRamDevice) {
                f = this.mPersister.getReducedScale();
            } else {
                f = this.mFullSnapshotScale;
            }
            float scaleFraction = f;
            WindowState mainWindow = appWindowToken.findMainWindow();
            if (mainWindow == null) {
                Slog.w(TAG, "Failed to take screenshot. No main window for " + task);
                return null;
            }
            SurfaceControl.ScreenshotGraphicBuffer screenshotBuffer = createTaskSnapshot(task, scaleFraction);
            if (screenshotBuffer == null) {
                if (WindowManagerDebugConfig.DEBUG_SCREENSHOT) {
                    Slog.w(TAG, "Failed to take screenshot for " + task);
                }
                return null;
            }
            boolean isWindowTranslucent = mainWindow.getAttrs().format != -1;
            return new ActivityManager.TaskSnapshot(appWindowToken.mActivityComponent, screenshotBuffer.getGraphicBuffer(), screenshotBuffer.getColorSpace(), appWindowToken.getTask().getConfiguration().orientation, getInsets(mainWindow), isLowRamDevice, scaleFraction, true, task.getWindowingMode(), getSystemUiVisibility(task), !appWindowToken.fillsParent() || isWindowTranslucent);
        }
    }

    private boolean shouldDisableSnapshots() {
        return this.mIsRunningOnWear || this.mIsRunningOnTv || this.mIsRunningOnIoT;
    }

    private Rect getInsets(WindowState state) {
        Rect insets = minRect(state.getContentInsets(), state.getStableInsets());
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
        WindowManager.LayoutParams attrs = mainWindow.getAttrs();
        TaskSnapshotSurface.SystemBarBackgroundPainter decorPainter = new TaskSnapshotSurface.SystemBarBackgroundPainter(attrs.flags, attrs.privateFlags, attrs.systemUiVisibility, task.getTaskDescription(), this.mFullSnapshotScale);
        int width = (int) (task.getBounds().width() * this.mFullSnapshotScale);
        int height = (int) (task.getBounds().height() * this.mFullSnapshotScale);
        RenderNode node = RenderNode.create("TaskSnapshotController", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        RecordingCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(mainWindow.getContentInsets(), mainWindow.getStableInsets());
        decorPainter.drawDecors(c, null);
        node.end(c);
        Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }
        return new ActivityManager.TaskSnapshot(topChild.mActivityComponent, hwBitmap.createGraphicBufferHandle(), hwBitmap.getColorSpace(), topChild.getTask().getConfiguration().orientation, getInsets(mainWindow), ActivityManager.isLowRamDeviceStatic(), this.mFullSnapshotScale, false, task.getWindowingMode(), getSystemUiVisibility(task), false);
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
                    TaskSnapshotController.this.lambda$screenTurningOff$2$TaskSnapshotController(listener);
                }
            });
        }
    }

    public /* synthetic */ void lambda$screenTurningOff$2$TaskSnapshotController(WindowManagerPolicy.ScreenOffListener listener) {
        try {
            synchronized (this.mService.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mTmpTasks.clear();
                this.mService.mRoot.forAllTasks(new Consumer() { // from class: com.android.server.wm.-$$Lambda$TaskSnapshotController$ewi-Dm2ws6pdTXd1elso7FtoLKw
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        TaskSnapshotController.this.lambda$screenTurningOff$1$TaskSnapshotController((Task) obj);
                    }
                });
                snapshotTasks(this.mTmpTasks);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            listener.onScreenOff();
        }
    }

    public /* synthetic */ void lambda$screenTurningOff$1$TaskSnapshotController(Task task) {
        if (task.isVisible()) {
            this.mTmpTasks.add(task);
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
        pw.println(prefix + "mFullSnapshotScale=" + this.mFullSnapshotScale);
        this.mCache.dump(pw, prefix);
    }
}
