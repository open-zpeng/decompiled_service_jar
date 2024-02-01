package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ContextImpl;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;
import com.android.internal.view.BaseIWindow;
import com.android.server.policy.WindowManagerPolicy;
import java.util.Objects;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class TaskSnapshotSurface implements WindowManagerPolicy.StartingSurface {
    private static final int FLAG_INHERIT_EXCLUDES = 830922808;
    private static final int MSG_REPORT_DRAW = 0;
    private static final int PRIVATE_FLAG_INHERITS = 131072;
    private static final long SIZE_MISMATCH_MINIMUM_TIME_MS = 450;
    private static final String TAG = "WindowManager";
    private static final String TITLE_FORMAT = "SnapshotStartingWindow for taskId=%s";
    private static Handler sHandler = new Handler(Looper.getMainLooper()) { // from class: com.android.server.wm.TaskSnapshotSurface.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            boolean hasDrawn;
            if (msg.what == 0) {
                TaskSnapshotSurface surface = (TaskSnapshotSurface) msg.obj;
                synchronized (surface.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        hasDrawn = surface.mHasDrawn;
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                if (hasDrawn) {
                    surface.reportDrawn();
                }
            }
        }
    };
    private SurfaceControl mChildSurfaceControl;
    private final Handler mHandler;
    private boolean mHasDrawn;
    private final int mOrientationOnCreation;
    private final WindowManagerService mService;
    private long mShownTime;
    private boolean mSizeMismatch;
    private ActivityManager.TaskSnapshot mSnapshot;
    private final int mStatusBarColor;
    private SurfaceControl mSurfaceControl;
    @VisibleForTesting
    final SystemBarBackgroundPainter mSystemBarBackgroundPainter;
    private final Rect mTaskBounds;
    private final CharSequence mTitle;
    private final Window mWindow;
    private final Rect mStableInsets = new Rect();
    private final Rect mContentInsets = new Rect();
    private final Rect mFrame = new Rect();
    private final Paint mBackgroundPaint = new Paint();
    private final Surface mSurface = new Surface();
    private final IWindowSession mSession = WindowManagerGlobal.getWindowSession();

    /* JADX WARN: Not initialized variable reg: 18, insn: 0x00c5: MOVE  (r8 I:??[OBJECT, ARRAY]) = (r18 I:??[OBJECT, ARRAY] A[D('tmpContentInsets' android.graphics.Rect)]), block:B:27:0x00bc */
    static TaskSnapshotSurface create(WindowManagerService service, AppWindowToken token, ActivityManager.TaskSnapshot snapshot) {
        Rect taskBounds;
        ActivityManager.TaskDescription taskDescription;
        int sysUiVis;
        Rect tmpFrame;
        IWindowSession session;
        Window window;
        Rect tmpStableInsets;
        Rect tmpContentInsets;
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        Window window2 = new Window();
        IWindowSession session2 = WindowManagerGlobal.getWindowSession();
        window2.setSession(session2);
        SurfaceControl surfaceControl = new SurfaceControl();
        Rect tmpRect = new Rect();
        DisplayCutout.ParcelableWrapper tmpCutout = new DisplayCutout.ParcelableWrapper();
        Rect tmpFrame2 = new Rect();
        Rect tmpContentInsets2 = new Rect();
        Rect tmpStableInsets2 = new Rect();
        InsetsState mTmpInsetsState = new InsetsState();
        MergedConfiguration tmpMergedConfiguration = new MergedConfiguration();
        ActivityManager.TaskDescription taskDescription2 = new ActivityManager.TaskDescription();
        taskDescription2.setBackgroundColor(-1);
        synchronized (service.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState mainWindow = token.findMainWindow();
                    Task task = token.getTask();
                    if (task == null) {
                        try {
                            StringBuilder sb = new StringBuilder();
                            try {
                                sb.append("TaskSnapshotSurface.create: Failed to find task for token=");
                                sb.append(token);
                                Slog.w(TAG, sb.toString());
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return null;
                            } catch (Throwable th) {
                                th = th;
                            }
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    } else {
                        try {
                            AppWindowToken topFullscreenToken = token.getTask().getTopFullscreenAppToken();
                            try {
                                if (topFullscreenToken == null) {
                                    try {
                                        StringBuilder sb2 = new StringBuilder();
                                        sb2.append("TaskSnapshotSurface.create: Failed to find top fullscreen for task=");
                                        sb2.append(task);
                                        Slog.w(TAG, sb2.toString());
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        return null;
                                    } catch (Throwable th3) {
                                        th = th3;
                                    }
                                } else {
                                    try {
                                        WindowState topFullscreenWindow = topFullscreenToken.getTopFullscreenWindow();
                                        if (mainWindow != null && topFullscreenWindow != null) {
                                            int sysUiVis2 = topFullscreenWindow.getSystemUiVisibility();
                                            int windowFlags = topFullscreenWindow.getAttrs().flags;
                                            int windowPrivateFlags = topFullscreenWindow.getAttrs().privateFlags;
                                            layoutParams.packageName = mainWindow.getAttrs().packageName;
                                            layoutParams.windowAnimations = mainWindow.getAttrs().windowAnimations;
                                            layoutParams.dimAmount = mainWindow.getAttrs().dimAmount;
                                            layoutParams.type = 3;
                                            layoutParams.format = snapshot.getSnapshot().getFormat();
                                            layoutParams.flags = (windowFlags & (-830922809)) | 8 | 16;
                                            layoutParams.privateFlags = windowPrivateFlags & 131072;
                                            layoutParams.token = token.token;
                                            layoutParams.width = -1;
                                            layoutParams.height = -1;
                                            layoutParams.systemUiVisibility = sysUiVis2;
                                            layoutParams.setTitle(String.format(TITLE_FORMAT, Integer.valueOf(task.mTaskId)));
                                            ActivityManager.TaskDescription td = task.getTaskDescription();
                                            if (td != null) {
                                                taskDescription2.copyFrom(td);
                                            }
                                            Rect taskBounds2 = new Rect();
                                            task.getBounds(taskBounds2);
                                            int currentOrientation = topFullscreenWindow.getConfiguration().orientation;
                                            WindowManagerService.resetPriorityAfterLockedSection();
                                            try {
                                                taskBounds = taskBounds2;
                                                taskDescription = taskDescription2;
                                                tmpStableInsets = tmpStableInsets2;
                                                sysUiVis = sysUiVis2;
                                                tmpContentInsets = tmpContentInsets2;
                                                tmpFrame = tmpFrame2;
                                                session = session2;
                                                window = window2;
                                                try {
                                                    int res = session2.addToDisplay(window2, window2.mSeq, layoutParams, 8, token.getDisplayContent().getDisplayId(), tmpFrame2, tmpRect, tmpRect, tmpRect, tmpCutout, (InputChannel) null, mTmpInsetsState);
                                                    if (res < 0) {
                                                        Slog.w(TAG, "Failed to add snapshot starting window res=" + res);
                                                        return null;
                                                    }
                                                } catch (RemoteException e) {
                                                }
                                            } catch (RemoteException e2) {
                                                taskBounds = taskBounds2;
                                                taskDescription = taskDescription2;
                                                sysUiVis = sysUiVis2;
                                                tmpFrame = tmpFrame2;
                                                session = session2;
                                                window = window2;
                                                tmpStableInsets = tmpStableInsets2;
                                                tmpContentInsets = tmpContentInsets2;
                                            }
                                            TaskSnapshotSurface snapshotSurface = new TaskSnapshotSurface(service, window, surfaceControl, snapshot, layoutParams.getTitle(), taskDescription, sysUiVis, windowFlags, windowPrivateFlags, taskBounds, currentOrientation);
                                            Window window3 = window;
                                            window3.setOuter(snapshotSurface);
                                            try {
                                                try {
                                                    session.relayout(window3, window3.mSeq, layoutParams, -1, -1, 0, 0, -1L, tmpFrame, tmpRect, tmpContentInsets, tmpRect, tmpStableInsets, tmpRect, tmpRect, tmpCutout, tmpMergedConfiguration, surfaceControl, mTmpInsetsState);
                                                } catch (RemoteException e3) {
                                                }
                                            } catch (RemoteException e4) {
                                            }
                                            snapshotSurface.setFrames(tmpFrame, tmpContentInsets, tmpStableInsets);
                                            snapshotSurface.drawSnapshot();
                                            return snapshotSurface;
                                        }
                                        Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find main window for token=" + token);
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        return null;
                                    } catch (Throwable th4) {
                                        th = th4;
                                    }
                                }
                            } catch (Throwable th5) {
                                th = th5;
                            }
                        } catch (Throwable th6) {
                            th = th6;
                        }
                    }
                } catch (Throwable th7) {
                    th = th7;
                }
            } catch (Throwable th8) {
                th = th8;
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            throw th;
        }
    }

    @VisibleForTesting
    TaskSnapshotSurface(WindowManagerService service, Window window, SurfaceControl surfaceControl, ActivityManager.TaskSnapshot snapshot, CharSequence title, ActivityManager.TaskDescription taskDescription, int sysUiVis, int windowFlags, int windowPrivateFlags, Rect taskBounds, int currentOrientation) {
        this.mService = service;
        this.mHandler = new Handler(this.mService.mH.getLooper());
        this.mWindow = window;
        this.mSurfaceControl = surfaceControl;
        this.mSnapshot = snapshot;
        this.mTitle = title;
        int backgroundColor = taskDescription.getBackgroundColor();
        this.mBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : -1);
        this.mTaskBounds = taskBounds;
        this.mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags, windowPrivateFlags, sysUiVis, taskDescription, 1.0f);
        this.mStatusBarColor = taskDescription.getStatusBarColor();
        this.mOrientationOnCreation = currentOrientation;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.StartingSurface
    public void remove() {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long now = SystemClock.uptimeMillis();
                if (this.mSizeMismatch && now - this.mShownTime < SIZE_MISMATCH_MINIMUM_TIME_MS) {
                    this.mHandler.postAtTime(new $$Lambda$OevXHSXgaSE351ZqRnMoA024MM(this), this.mShownTime + SIZE_MISMATCH_MINIMUM_TIME_MS);
                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                        Slog.v(TAG, "Defer removing snapshot surface in " + (now - this.mShownTime) + "ms");
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                try {
                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                        Slog.v(TAG, "Removing snapshot surface");
                    }
                    this.mSession.remove(this.mWindow);
                } catch (RemoteException e) {
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @VisibleForTesting
    void setFrames(Rect frame, Rect contentInsets, Rect stableInsets) {
        this.mFrame.set(frame);
        this.mContentInsets.set(contentInsets);
        this.mStableInsets.set(stableInsets);
        this.mSizeMismatch = (this.mFrame.width() == this.mSnapshot.getSnapshot().getWidth() && this.mFrame.height() == this.mSnapshot.getSnapshot().getHeight()) ? false : true;
        this.mSystemBarBackgroundPainter.setInsets(contentInsets, stableInsets);
    }

    private void drawSnapshot() {
        this.mSurface.copyFrom(this.mSurfaceControl);
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Drawing snapshot surface sizeMismatch=" + this.mSizeMismatch);
        }
        if (this.mSizeMismatch) {
            drawSizeMismatchSnapshot();
        } else {
            drawSizeMatchSnapshot();
        }
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mShownTime = SystemClock.uptimeMillis();
                this.mHasDrawn = true;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        reportDrawn();
        this.mSnapshot = null;
    }

    private void drawSizeMatchSnapshot() {
        this.mSurface.attachAndQueueBufferWithColorSpace(this.mSnapshot.getSnapshot(), this.mSnapshot.getColorSpace());
        this.mSurface.release();
    }

    private void drawSizeMismatchSnapshot() {
        Rect frame;
        if (!this.mSurface.isValid()) {
            throw new IllegalStateException("mSurface does not hold a valid surface.");
        }
        GraphicBuffer buffer = this.mSnapshot.getSnapshot();
        SurfaceSession session = new SurfaceSession();
        boolean aspectRatioMismatch = Math.abs((((float) buffer.getWidth()) / ((float) buffer.getHeight())) - (((float) this.mFrame.width()) / ((float) this.mFrame.height()))) > 0.01f;
        this.mChildSurfaceControl = new SurfaceControl.Builder(session).setName(((Object) this.mTitle) + " - task-snapshot-surface").setBufferSize(buffer.getWidth(), buffer.getHeight()).setFormat(buffer.getFormat()).setParent(this.mSurfaceControl).build();
        Surface surface = new Surface();
        surface.copyFrom(this.mChildSurfaceControl);
        SurfaceControl.openTransaction();
        try {
            this.mChildSurfaceControl.show();
            if (aspectRatioMismatch) {
                Rect crop = calculateSnapshotCrop();
                frame = calculateSnapshotFrame(crop);
                this.mChildSurfaceControl.setWindowCrop(crop);
                this.mChildSurfaceControl.setPosition(frame.left, frame.top);
            } else {
                frame = null;
            }
            float scale = 1.0f / this.mSnapshot.getScale();
            this.mChildSurfaceControl.setMatrix(scale, 0.0f, 0.0f, scale);
            SurfaceControl.closeTransaction();
            surface.attachAndQueueBufferWithColorSpace(buffer, this.mSnapshot.getColorSpace());
            surface.release();
            if (aspectRatioMismatch) {
                Canvas c = this.mSurface.lockCanvas(null);
                drawBackgroundAndBars(c, frame);
                this.mSurface.unlockCanvasAndPost(c);
                this.mSurface.release();
            }
        } catch (Throwable th) {
            SurfaceControl.closeTransaction();
            throw th;
        }
    }

    @VisibleForTesting
    Rect calculateSnapshotCrop() {
        Rect rect = new Rect();
        rect.set(0, 0, this.mSnapshot.getSnapshot().getWidth(), this.mSnapshot.getSnapshot().getHeight());
        Rect insets = this.mSnapshot.getContentInsets();
        boolean isTop = this.mTaskBounds.top == 0 && this.mFrame.top == 0;
        rect.inset((int) (insets.left * this.mSnapshot.getScale()), isTop ? 0 : (int) (insets.top * this.mSnapshot.getScale()), (int) (insets.right * this.mSnapshot.getScale()), (int) (insets.bottom * this.mSnapshot.getScale()));
        return rect;
    }

    @VisibleForTesting
    Rect calculateSnapshotFrame(Rect crop) {
        Rect frame = new Rect(crop);
        float scale = this.mSnapshot.getScale();
        frame.scale(1.0f / scale);
        frame.offsetTo((int) ((-crop.left) / scale), (int) ((-crop.top) / scale));
        int colorViewLeftInset = DecorView.getColorViewLeftInset(this.mStableInsets.left, this.mContentInsets.left);
        frame.offset(colorViewLeftInset, 0);
        return frame;
    }

    @VisibleForTesting
    void drawBackgroundAndBars(Canvas c, Rect frame) {
        float height;
        int statusBarHeight = this.mSystemBarBackgroundPainter.getStatusBarColorViewHeight();
        boolean fillHorizontally = c.getWidth() > frame.right;
        boolean fillVertically = c.getHeight() > frame.bottom;
        if (fillHorizontally) {
            float f = frame.right;
            float f2 = Color.alpha(this.mStatusBarColor) == 255 ? statusBarHeight : 0.0f;
            float width = c.getWidth();
            if (fillVertically) {
                height = frame.bottom;
            } else {
                height = c.getHeight();
            }
            c.drawRect(f, f2, width, height, this.mBackgroundPaint);
        }
        if (fillVertically) {
            c.drawRect(0.0f, frame.bottom, c.getWidth(), c.getHeight(), this.mBackgroundPaint);
        }
        this.mSystemBarBackgroundPainter.drawDecors(c, frame);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reportDrawn() {
        try {
            this.mSession.finishDrawing(this.mWindow);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes2.dex */
    public static class Window extends BaseIWindow {
        private TaskSnapshotSurface mOuter;

        Window() {
        }

        public void setOuter(TaskSnapshotSurface outer) {
            this.mOuter = outer;
        }

        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw, MergedConfiguration mergedConfiguration, Rect backDropFrame, boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId, DisplayCutout.ParcelableWrapper displayCutout) {
            TaskSnapshotSurface taskSnapshotSurface;
            if (mergedConfiguration != null && (taskSnapshotSurface = this.mOuter) != null && taskSnapshotSurface.mOrientationOnCreation != mergedConfiguration.getMergedConfiguration().orientation) {
                Handler handler = TaskSnapshotSurface.sHandler;
                TaskSnapshotSurface taskSnapshotSurface2 = this.mOuter;
                Objects.requireNonNull(taskSnapshotSurface2);
                handler.post(new $$Lambda$OevXHSXgaSE351ZqRnMoA024MM(taskSnapshotSurface2));
            }
            if (reportDraw) {
                TaskSnapshotSurface.sHandler.obtainMessage(0, this.mOuter).sendToTarget();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class SystemBarBackgroundPainter {
        private final int mNavigationBarColor;
        private final float mScale;
        private final int mStatusBarColor;
        private final int mSysUiVis;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final Rect mContentInsets = new Rect();
        private final Rect mStableInsets = new Rect();
        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();

        /* JADX INFO: Access modifiers changed from: package-private */
        public SystemBarBackgroundPainter(int windowFlags, int windowPrivateFlags, int sysUiVis, ActivityManager.TaskDescription taskDescription, float scale) {
            this.mWindowFlags = windowFlags;
            this.mWindowPrivateFlags = windowPrivateFlags;
            this.mSysUiVis = sysUiVis;
            this.mScale = scale;
            ContextImpl systemUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
            int semiTransparent = systemUiContext.getColor(17170985);
            this.mStatusBarColor = DecorView.calculateBarColor(windowFlags, 67108864, semiTransparent, taskDescription.getStatusBarColor(), sysUiVis, 8192, taskDescription.getEnsureStatusBarContrastWhenTransparent());
            this.mNavigationBarColor = DecorView.calculateBarColor(windowFlags, 134217728, semiTransparent, taskDescription.getNavigationBarColor(), sysUiVis, 16, taskDescription.getEnsureNavigationBarContrastWhenTransparent() && systemUiContext.getResources().getBoolean(17891487));
            this.mStatusBarPaint.setColor(this.mStatusBarColor);
            this.mNavigationBarPaint.setColor(this.mNavigationBarColor);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void setInsets(Rect contentInsets, Rect stableInsets) {
            this.mContentInsets.set(contentInsets);
            this.mStableInsets.set(stableInsets);
        }

        int getStatusBarColorViewHeight() {
            boolean forceBarBackground = (this.mWindowPrivateFlags & 131072) != 0;
            if (DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(this.mSysUiVis, this.mStatusBarColor, this.mWindowFlags, forceBarBackground)) {
                return (int) (DecorView.getColorViewTopInset(this.mStableInsets.top, this.mContentInsets.top) * this.mScale);
            }
            return 0;
        }

        private boolean isNavigationBarColorViewVisible() {
            boolean forceBarBackground = (this.mWindowPrivateFlags & 131072) != 0;
            return DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(this.mSysUiVis, this.mNavigationBarColor, this.mWindowFlags, forceBarBackground);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void drawDecors(Canvas c, Rect alreadyDrawnFrame) {
            drawStatusBarBackground(c, alreadyDrawnFrame, getStatusBarColorViewHeight());
            drawNavigationBarBackground(c);
        }

        @VisibleForTesting
        void drawStatusBarBackground(Canvas c, Rect alreadyDrawnFrame, int statusBarHeight) {
            if (statusBarHeight > 0 && Color.alpha(this.mStatusBarColor) != 0) {
                if (alreadyDrawnFrame == null || c.getWidth() > alreadyDrawnFrame.right) {
                    int rightInset = (int) (DecorView.getColorViewRightInset(this.mStableInsets.right, this.mContentInsets.right) * this.mScale);
                    int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                    c.drawRect(left, 0.0f, c.getWidth() - rightInset, statusBarHeight, this.mStatusBarPaint);
                }
            }
        }

        @VisibleForTesting
        void drawNavigationBarBackground(Canvas c) {
            Rect navigationBarRect = new Rect();
            DecorView.getNavigationBarRect(c.getWidth(), c.getHeight(), this.mStableInsets, this.mContentInsets, navigationBarRect, this.mScale);
            boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(this.mNavigationBarColor) != 0 && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, this.mNavigationBarPaint);
            }
        }
    }
}
