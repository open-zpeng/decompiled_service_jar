package com.android.server.wm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class EmulatorDisplayOverlay {
    private static final String TAG = "WindowManager";
    private boolean mDrawNeeded;
    private int mLastDH;
    private int mLastDW;
    private Drawable mOverlay;
    private int mRotation;
    private Point mScreenSize;
    private final Surface mSurface = new Surface();
    private final SurfaceControl mSurfaceControl;
    private boolean mVisible;

    public EmulatorDisplayOverlay(Context context, DisplayContent dc, int zOrder) {
        Display display = dc.getDisplay();
        this.mScreenSize = new Point();
        display.getSize(this.mScreenSize);
        SurfaceControl ctrl = null;
        try {
            ctrl = dc.makeOverlay().setName("EmulatorDisplayOverlay").setSize(this.mScreenSize.x, this.mScreenSize.y).setFormat(-3).build();
            ctrl.setLayer(zOrder);
            ctrl.setPosition(0.0f, 0.0f);
            ctrl.show();
            this.mSurface.copyFrom(ctrl);
        } catch (Surface.OutOfResourcesException e) {
        }
        this.mSurfaceControl = ctrl;
        this.mDrawNeeded = true;
        this.mOverlay = context.getDrawable(17302199);
    }

    private void drawIfNeeded() {
        if (!this.mDrawNeeded || !this.mVisible) {
            return;
        }
        this.mDrawNeeded = false;
        Rect dirty = new Rect(0, 0, this.mScreenSize.x, this.mScreenSize.y);
        Canvas c = null;
        try {
            c = this.mSurface.lockCanvas(dirty);
        } catch (Surface.OutOfResourcesException e) {
        } catch (IllegalArgumentException e2) {
        }
        if (c != null) {
            c.drawColor(0, PorterDuff.Mode.SRC);
            this.mSurfaceControl.setPosition(0.0f, 0.0f);
            int size = Math.max(this.mScreenSize.x, this.mScreenSize.y);
            this.mOverlay.setBounds(0, 0, size, size);
            this.mOverlay.draw(c);
            this.mSurface.unlockCanvasAndPost(c);
        }
    }

    public void setVisibility(boolean on) {
        if (this.mSurfaceControl == null) {
            return;
        }
        this.mVisible = on;
        drawIfNeeded();
        if (on) {
            this.mSurfaceControl.show();
        } else {
            this.mSurfaceControl.hide();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionSurface(int dw, int dh, int rotation) {
        if (this.mLastDW == dw && this.mLastDH == dh && this.mRotation == rotation) {
            return;
        }
        this.mLastDW = dw;
        this.mLastDH = dh;
        this.mDrawNeeded = true;
        this.mRotation = rotation;
        drawIfNeeded();
    }
}
