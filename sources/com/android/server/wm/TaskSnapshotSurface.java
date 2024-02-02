package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityThread;
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
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManagerGlobal;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;
import com.android.internal.view.BaseIWindow;
import com.android.server.policy.WindowManagerPolicy;
import java.util.Objects;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
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
                synchronized (surface.mService.mWindowMap) {
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
    private final Surface mSurface;
    @VisibleForTesting
    final SystemBarBackgroundPainter mSystemBarBackgroundPainter;
    private final Rect mTaskBounds;
    private final CharSequence mTitle;
    private final Window mWindow;
    private final Rect mStableInsets = new Rect();
    private final Rect mContentInsets = new Rect();
    private final Rect mFrame = new Rect();
    private final Paint mBackgroundPaint = new Paint();
    private final IWindowSession mSession = WindowManagerGlobal.getWindowSession();

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:22:0x0099
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    static com.android.server.wm.TaskSnapshotSurface create(com.android.server.wm.WindowManagerService r61, com.android.server.wm.AppWindowToken r62, android.app.ActivityManager.TaskSnapshot r63) {
        /*
            Method dump skipped, instructions count: 672
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.TaskSnapshotSurface.create(com.android.server.wm.WindowManagerService, com.android.server.wm.AppWindowToken, android.app.ActivityManager$TaskSnapshot):com.android.server.wm.TaskSnapshotSurface");
    }

    @VisibleForTesting
    TaskSnapshotSurface(WindowManagerService service, Window window, Surface surface, ActivityManager.TaskSnapshot snapshot, CharSequence title, int backgroundColor, int statusBarColor, int navigationBarColor, int sysUiVis, int windowFlags, int windowPrivateFlags, Rect taskBounds, int currentOrientation) {
        this.mService = service;
        this.mHandler = new Handler(this.mService.mH.getLooper());
        this.mWindow = window;
        this.mSurface = surface;
        this.mSnapshot = snapshot;
        this.mTitle = title;
        this.mBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : -1);
        this.mTaskBounds = taskBounds;
        this.mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags, windowPrivateFlags, sysUiVis, statusBarColor, navigationBarColor);
        this.mStatusBarColor = statusBarColor;
        this.mOrientationOnCreation = currentOrientation;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.StartingSurface
    public void remove() {
        synchronized (this.mService.mWindowMap) {
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
        GraphicBuffer buffer = this.mSnapshot.getSnapshot();
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "Drawing snapshot surface sizeMismatch=" + this.mSizeMismatch);
        }
        if (this.mSizeMismatch) {
            drawSizeMismatchSnapshot(buffer);
        } else {
            drawSizeMatchSnapshot(buffer);
        }
        synchronized (this.mService.mWindowMap) {
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

    private void drawSizeMatchSnapshot(GraphicBuffer buffer) {
        this.mSurface.attachAndQueueBuffer(buffer);
        this.mSurface.release();
    }

    private void drawSizeMismatchSnapshot(GraphicBuffer buffer) {
        SurfaceSession session = new SurfaceSession(this.mSurface);
        SurfaceControl.Builder builder = new SurfaceControl.Builder(session);
        this.mChildSurfaceControl = builder.setName(((Object) this.mTitle) + " - task-snapshot-surface").setSize(buffer.getWidth(), buffer.getHeight()).setFormat(buffer.getFormat()).build();
        Surface surface = new Surface();
        surface.copyFrom(this.mChildSurfaceControl);
        Rect crop = calculateSnapshotCrop();
        Rect frame = calculateSnapshotFrame(crop);
        SurfaceControl.openTransaction();
        try {
            this.mChildSurfaceControl.show();
            this.mChildSurfaceControl.setWindowCrop(crop);
            this.mChildSurfaceControl.setPosition(frame.left, frame.top);
            float scale = 1.0f / this.mSnapshot.getScale();
            this.mChildSurfaceControl.setMatrix(scale, 0.0f, 0.0f, scale);
            SurfaceControl.closeTransaction();
            this.mChildSurfaceControl.releaseCloseGuard();
            surface.attachAndQueueBuffer(buffer);
            surface.release();
            Canvas c = this.mSurface.lockCanvas(null);
            drawBackgroundAndBars(c, frame);
            this.mSurface.unlockCanvasAndPost(c);
            this.mSurface.release();
        } catch (Throwable th) {
            SurfaceControl.closeTransaction();
            this.mChildSurfaceControl.releaseCloseGuard();
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
        int statusBarHeight = this.mSystemBarBackgroundPainter.getStatusBarColorViewHeight();
        boolean fillHorizontally = c.getWidth() > frame.right;
        boolean fillVertically = c.getHeight() > frame.bottom;
        if (fillHorizontally) {
            c.drawRect(frame.right, Color.alpha(this.mStatusBarColor) == 255 ? statusBarHeight : 0.0f, c.getWidth(), fillVertically ? frame.bottom : c.getHeight(), this.mBackgroundPaint);
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
    /* loaded from: classes.dex */
    public static class Window extends BaseIWindow {
        private TaskSnapshotSurface mOuter;

        Window() {
        }

        public void setOuter(TaskSnapshotSurface outer) {
            this.mOuter = outer;
        }

        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw, MergedConfiguration mergedConfiguration, Rect backDropFrame, boolean forceLayout, boolean alwaysConsumeNavBar, int displayId, DisplayCutout.ParcelableWrapper displayCutout) {
            if (mergedConfiguration != null && this.mOuter != null && this.mOuter.mOrientationOnCreation != mergedConfiguration.getMergedConfiguration().orientation) {
                Handler handler = TaskSnapshotSurface.sHandler;
                TaskSnapshotSurface taskSnapshotSurface = this.mOuter;
                Objects.requireNonNull(taskSnapshotSurface);
                handler.post(new $$Lambda$OevXHSXgaSE351ZqRnMoA024MM(taskSnapshotSurface));
            }
            if (reportDraw) {
                TaskSnapshotSurface.sHandler.obtainMessage(0, this.mOuter).sendToTarget();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class SystemBarBackgroundPainter {
        private final int mNavigationBarColor;
        private final int mStatusBarColor;
        private final int mSysUiVis;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final Rect mContentInsets = new Rect();
        private final Rect mStableInsets = new Rect();
        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();

        /* JADX INFO: Access modifiers changed from: package-private */
        public SystemBarBackgroundPainter(int windowFlags, int windowPrivateFlags, int sysUiVis, int statusBarColor, int navigationBarColor) {
            this.mWindowFlags = windowFlags;
            this.mWindowPrivateFlags = windowPrivateFlags;
            this.mSysUiVis = sysUiVis;
            this.mStatusBarColor = DecorView.calculateStatusBarColor(windowFlags, ActivityThread.currentActivityThread().getSystemUiContext().getColor(17170860), statusBarColor);
            this.mNavigationBarColor = navigationBarColor;
            this.mStatusBarPaint.setColor(this.mStatusBarColor);
            this.mNavigationBarPaint.setColor(navigationBarColor);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void setInsets(Rect contentInsets, Rect stableInsets) {
            this.mContentInsets.set(contentInsets);
            this.mStableInsets.set(stableInsets);
        }

        int getStatusBarColorViewHeight() {
            boolean forceStatusBarBackground = (this.mWindowPrivateFlags & 131072) != 0;
            if (DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(this.mSysUiVis, this.mStatusBarColor, this.mWindowFlags, forceStatusBarBackground)) {
                return DecorView.getColorViewTopInset(this.mStableInsets.top, this.mContentInsets.top);
            }
            return 0;
        }

        private boolean isNavigationBarColorViewVisible() {
            return DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(this.mSysUiVis, this.mNavigationBarColor, this.mWindowFlags, false);
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
                    int rightInset = DecorView.getColorViewRightInset(this.mStableInsets.right, this.mContentInsets.right);
                    int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                    c.drawRect(left, 0.0f, c.getWidth() - rightInset, statusBarHeight, this.mStatusBarPaint);
                }
            }
        }

        @VisibleForTesting
        void drawNavigationBarBackground(Canvas c) {
            Rect navigationBarRect = new Rect();
            DecorView.getNavigationBarRect(c.getWidth(), c.getHeight(), this.mStableInsets, this.mContentInsets, navigationBarRect);
            boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(this.mNavigationBarColor) != 0 && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, this.mNavigationBarPaint);
            }
        }
    }
}
