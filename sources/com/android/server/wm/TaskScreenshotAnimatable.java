package com.android.server.wm;

import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import com.android.server.wm.SurfaceAnimator;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class TaskScreenshotAnimatable implements SurfaceAnimator.Animatable {
    private static final String TAG = "TaskScreenshotAnim";
    private int mHeight;
    private SurfaceControl mSurfaceControl;
    private Task mTask;
    private int mWidth;

    public static TaskScreenshotAnimatable create(Task task) {
        return new TaskScreenshotAnimatable(task, getBufferFromTask(task));
    }

    private static SurfaceControl.ScreenshotGraphicBuffer getBufferFromTask(Task task) {
        if (task == null) {
            return null;
        }
        Rect tmpRect = task.getBounds();
        tmpRect.offset(0, 0);
        return SurfaceControl.captureLayers(task.getSurfaceControl().getHandle(), tmpRect, 1.0f);
    }

    private TaskScreenshotAnimatable(Task task, SurfaceControl.ScreenshotGraphicBuffer screenshotBuffer) {
        GraphicBuffer buffer = screenshotBuffer == null ? null : screenshotBuffer.getGraphicBuffer();
        this.mTask = task;
        this.mWidth = buffer != null ? buffer.getWidth() : 1;
        this.mHeight = buffer != null ? buffer.getHeight() : 1;
        if (WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS) {
            Slog.d(TAG, "Creating TaskScreenshotAnimatable: task: " + task + "width: " + this.mWidth + "height: " + this.mHeight);
        }
        this.mSurfaceControl = new SurfaceControl.Builder(new SurfaceSession()).setName("RecentTaskScreenshotSurface").setBufferSize(this.mWidth, this.mHeight).build();
        if (buffer != null) {
            Surface surface = new Surface();
            surface.copyFrom(this.mSurfaceControl);
            surface.attachAndQueueBufferWithColorSpace(buffer, screenshotBuffer.getColorSpace());
            surface.release();
        }
        getPendingTransaction().show(this.mSurfaceControl);
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl.Transaction getPendingTransaction() {
        return this.mTask.getPendingTransaction();
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public void commitPendingTransaction() {
        this.mTask.commitPendingTransaction();
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        t.setLayer(leash, 1);
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null) {
            t.remove(surfaceControl);
            this.mSurfaceControl = null;
        }
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl.Builder makeAnimationLeash() {
        return this.mTask.makeAnimationLeash();
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl getAnimationLeashParent() {
        return this.mTask.getAnimationLeashParent();
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl getSurfaceControl() {
        return this.mSurfaceControl;
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public SurfaceControl getParentSurfaceControl() {
        return this.mTask.mSurfaceControl;
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public int getSurfaceWidth() {
        return this.mWidth;
    }

    @Override // com.android.server.wm.SurfaceAnimator.Animatable
    public int getSurfaceHeight() {
        return this.mHeight;
    }
}
