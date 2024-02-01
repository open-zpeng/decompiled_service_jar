package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.os.IBinder;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowContentFrameStats;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class WindowSurfaceController {
    static final String TAG = "WindowManager";
    final WindowStateAnimator mAnimator;
    private final WindowManagerService mService;
    SurfaceControl mSurfaceControl;
    private int mSurfaceH;
    private int mSurfaceW;
    private final Session mWindowSession;
    private final int mWindowType;
    private final String title;
    private boolean mSurfaceShown = false;
    private float mSurfaceX = 0.0f;
    private float mSurfaceY = 0.0f;
    private Rect mSurfaceCrop = new Rect(0, 0, -1, -1);
    private float mLastDsdx = 1.0f;
    private float mLastDtdx = 0.0f;
    private float mLastDsdy = 0.0f;
    private float mLastDtdy = 1.0f;
    private float mSurfaceAlpha = 0.0f;
    private int mSurfaceLayer = 0;
    private boolean mHiddenForCrop = false;
    private boolean mHiddenForOtherReasons = true;
    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();

    public WindowSurfaceController(SurfaceSession s, String name, int w, int h, int format, int flags, WindowStateAnimator animator, int windowType, int ownerUid, int pid) {
        this.mSurfaceW = 0;
        this.mSurfaceH = 0;
        this.mAnimator = animator;
        this.mSurfaceW = w;
        this.mSurfaceH = h;
        this.title = name;
        this.mService = animator.mService;
        WindowState win = animator.mWin;
        this.mWindowType = windowType;
        this.mWindowSession = win.mSession;
        Trace.traceBegin(32L, "new SurfaceControl");
        SurfaceControl.Builder b = win.makeSurface().setParent(win.getSurfaceControl()).setName(name).setBufferSize(w, h).setFormat(format).setFlags(flags).setMetadata(2, windowType).setMetadata(1, ownerUid).setMetadata(4, pid);
        this.mSurfaceControl = b.build();
        if (xpWindowManager.shouldCropRoundCorner(win.mAttrs)) {
            xpWindowManager.setRoundCorner(this.mSurfaceControl);
        }
        Trace.traceEnd(32L);
    }

    private void logSurface(String msg, RuntimeException where) {
        String str = "  SURFACE " + msg + ": " + this.title;
        if (where != null) {
            Slog.i(TAG, str, where);
        } else {
            Slog.i(TAG, str);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparentChildrenInTransaction(WindowSurfaceController other) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            Slog.i(TAG, "REPARENT from: " + this + " to: " + other);
        }
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null && other.mSurfaceControl != null) {
            surfaceControl.reparentChildren(other.getHandle());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void detachChildren() {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            Slog.i(TAG, "SEVER CHILDREN");
        }
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null) {
            surfaceControl.detachChildren();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hide(SurfaceControl.Transaction transaction, String reason) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("HIDE ( " + reason + " )", null);
        }
        this.mHiddenForOtherReasons = true;
        this.mAnimator.destroyPreservedSurfaceLocked();
        if (this.mSurfaceShown) {
            hideSurface(transaction);
        }
    }

    private void hideSurface(SurfaceControl.Transaction transaction) {
        if (this.mSurfaceControl == null) {
            return;
        }
        setShown(false);
        try {
            transaction.hide(this.mSurfaceControl);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception hiding surface in " + this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroyNotInTransaction() {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
            Slog.i(TAG, "Destroying surface " + this + " called by " + Debug.getCallers(8));
        }
        try {
            try {
                if (this.mSurfaceControl != null) {
                    this.mTmpTransaction.remove(this.mSurfaceControl).apply();
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error destroying surface in: " + this, e);
            }
        } finally {
            setShown(false);
            this.mSurfaceControl = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCropInTransaction(Rect clipRect, boolean recoveringMemory) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("CROP " + clipRect.toShortString(), null);
        }
        try {
            if (clipRect.width() > 0 && clipRect.height() > 0) {
                if (!clipRect.equals(this.mSurfaceCrop)) {
                    this.mSurfaceControl.setWindowCrop(clipRect);
                    this.mSurfaceCrop.set(clipRect);
                }
                this.mHiddenForCrop = false;
                updateVisibility();
                return;
            }
            this.mHiddenForCrop = true;
            this.mAnimator.destroyPreservedSurfaceLocked();
            updateVisibility();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error setting crop surface of " + this + " crop=" + clipRect.toShortString(), e);
            if (!recoveringMemory) {
                this.mAnimator.reclaimSomeSurfaceMemory("crop", true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearCropInTransaction(boolean recoveringMemory) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("CLEAR CROP", null);
        }
        try {
            Rect clipRect = new Rect(0, 0, -1, -1);
            if (this.mSurfaceCrop.equals(clipRect)) {
                return;
            }
            this.mSurfaceControl.setWindowCrop(clipRect);
            this.mSurfaceCrop.set(clipRect);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error setting clearing crop of " + this, e);
            if (!recoveringMemory) {
                this.mAnimator.reclaimSomeSurfaceMemory("crop", true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPositionInTransaction(float left, float top, boolean recoveringMemory) {
        setPosition(null, left, top, recoveringMemory);
    }

    void setPosition(SurfaceControl.Transaction t, float left, float top, boolean recoveringMemory) {
        boolean surfaceMoved = (this.mSurfaceX == left && this.mSurfaceY == top) ? false : true;
        if (surfaceMoved) {
            this.mSurfaceX = left;
            this.mSurfaceY = top;
            try {
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                    logSurface("POS (setPositionInTransaction) @ (" + left + "," + top + ")", null);
                }
                if (t == null) {
                    this.mSurfaceControl.setPosition(left, top);
                } else {
                    t.setPosition(this.mSurfaceControl, left, top);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + this + " pos=(" + left + "," + top + ")", e);
                if (!recoveringMemory) {
                    this.mAnimator.reclaimSomeSurfaceMemory("position", true);
                }
            }
        }
    }

    void setGeometryAppliesWithResizeInTransaction(boolean recoveringMemory) {
        this.mSurfaceControl.setGeometryAppliesWithResize();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setMatrixInTransaction(float dsdx, float dtdx, float dtdy, float dsdy, boolean recoveringMemory) {
        setMatrix(null, dsdx, dtdx, dtdy, dsdy, false);
    }

    void setMatrix(SurfaceControl.Transaction t, float dsdx, float dtdx, float dtdy, float dsdy, boolean recoveringMemory) {
        Throwable th;
        boolean matrixChanged = (this.mLastDsdx == dsdx && this.mLastDtdx == dtdx && this.mLastDtdy == dtdy && this.mLastDsdy == dsdy) ? false : true;
        if (!matrixChanged) {
            return;
        }
        this.mLastDsdx = dsdx;
        this.mLastDtdx = dtdx;
        this.mLastDtdy = dtdy;
        this.mLastDsdy = dsdy;
        try {
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                logSurface("MATRIX [" + dsdx + "," + dtdx + "," + dtdy + "," + dsdy + "]", null);
            }
            if (t != null) {
                th = null;
                try {
                    t.setMatrix(this.mSurfaceControl, dsdx, dtdx, dtdy, dsdy);
                    return;
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Error setting matrix on surface surface" + this.title + " MATRIX [" + dsdx + "," + dtdx + "," + dtdy + "," + dsdy + "]", th);
                    if (!recoveringMemory) {
                        this.mAnimator.reclaimSomeSurfaceMemory("matrix", true);
                        return;
                    }
                    return;
                }
            }
            this.mSurfaceControl.setMatrix(dsdx, dtdx, dtdy, dsdy);
        } catch (RuntimeException e2) {
            th = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setBufferSizeInTransaction(int width, int height, boolean recoveringMemory) {
        boolean surfaceResized = (this.mSurfaceW == width && this.mSurfaceH == height) ? false : true;
        if (surfaceResized) {
            this.mSurfaceW = width;
            this.mSurfaceH = height;
            try {
                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                    logSurface("SIZE " + width + "x" + height, null);
                }
                this.mSurfaceControl.setBufferSize(width, height);
                return true;
            } catch (RuntimeException e) {
                Slog.e(TAG, "Error resizing surface of " + this.title + " size=(" + width + "x" + height + ")", e);
                if (!recoveringMemory) {
                    this.mAnimator.reclaimSomeSurfaceMemory("size", true);
                }
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean prepareToShowInTransaction(float alpha, float dsdx, float dtdx, float dsdy, float dtdy, boolean recoveringMemory) {
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null) {
            try {
                this.mSurfaceAlpha = alpha;
                surfaceControl.setAlpha(alpha);
                this.mLastDsdx = dsdx;
                this.mLastDtdx = dtdx;
                this.mLastDsdy = dsdy;
                this.mLastDtdy = dtdy;
                this.mSurfaceControl.setMatrix(dsdx, dtdx, dsdy, dtdy);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error updating surface in " + this.title, e);
                if (!recoveringMemory) {
                    this.mAnimator.reclaimSomeSurfaceMemory("update", true);
                    return false;
                }
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTransparentRegionHint(Region region) {
        if (this.mSurfaceControl == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
            return;
        }
        if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION setTransparentRegion");
        }
        this.mService.openSurfaceTransaction();
        try {
            this.mSurfaceControl.setTransparentRegionHint(region);
        } finally {
            this.mService.closeSurfaceTransaction("setTransparentRegion");
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setTransparentRegion");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOpaque(boolean isOpaque) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("isOpaque=" + isOpaque, null);
        }
        if (this.mSurfaceControl == null) {
            return;
        }
        if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION setOpaqueLocked");
        }
        this.mService.openSurfaceTransaction();
        try {
            this.mSurfaceControl.setOpaque(isOpaque);
        } finally {
            this.mService.closeSurfaceTransaction("setOpaqueLocked");
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setOpaqueLocked");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSecure(boolean isSecure) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("isSecure=" + isSecure, null);
        }
        if (this.mSurfaceControl == null) {
            return;
        }
        if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION setSecureLocked");
        }
        this.mService.openSurfaceTransaction();
        try {
            this.mSurfaceControl.setSecure(isSecure);
        } finally {
            this.mService.closeSurfaceTransaction("setSecure");
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setSecureLocked");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setColorSpaceAgnostic(boolean agnostic) {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("isColorSpaceAgnostic=" + agnostic, null);
        }
        if (this.mSurfaceControl == null) {
            return;
        }
        if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION setColorSpaceAgnosticLocked");
        }
        this.mService.openSurfaceTransaction();
        try {
            this.mSurfaceControl.setColorSpaceAgnostic(agnostic);
        } finally {
            this.mService.closeSurfaceTransaction("setColorSpaceAgnostic");
            if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setColorSpaceAgnosticLocked");
            }
        }
    }

    void getContainerRect(Rect rect) {
        this.mAnimator.getContainerRect(rect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean showRobustlyInTransaction() {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            logSurface("SHOW (performLayout)", null);
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Showing " + this + " during relayout");
        }
        this.mHiddenForOtherReasons = false;
        return updateVisibility();
    }

    private boolean updateVisibility() {
        if (this.mHiddenForCrop || this.mHiddenForOtherReasons) {
            if (this.mSurfaceShown) {
                hideSurface(this.mTmpTransaction);
                SurfaceControl.mergeToGlobalTransaction(this.mTmpTransaction);
                return false;
            }
            return false;
        } else if (!this.mSurfaceShown) {
            return showSurface();
        } else {
            return true;
        }
    }

    private boolean showSurface() {
        try {
            setShown(true);
            this.mSurfaceControl.show();
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure showing surface " + this.mSurfaceControl + " in " + this, e);
            this.mAnimator.reclaimSomeSurfaceMemory("show", true);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferTransactionUntil(IBinder handle, long frame) {
        this.mSurfaceControl.deferTransactionUntil(handle, frame);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forceScaleableInTransaction(boolean force) {
        int scalingMode = force ? 1 : -1;
        this.mSurfaceControl.setOverrideScalingMode(scalingMode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearWindowContentFrameStats() {
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl == null) {
            return false;
        }
        return surfaceControl.clearContentFrameStats();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getWindowContentFrameStats(WindowContentFrameStats outStats) {
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl == null) {
            return false;
        }
        return surfaceControl.getContentFrameStats(outStats);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSurface() {
        return this.mSurfaceControl != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IBinder getHandle() {
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl == null) {
            return null;
        }
        return surfaceControl.getHandle();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getSurfaceControl(SurfaceControl outSurfaceControl) {
        outSurfaceControl.copyFrom(this.mSurfaceControl);
    }

    int getLayer() {
        return this.mSurfaceLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getShown() {
        return this.mSurfaceShown;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setShown(boolean surfaceShown) {
        this.mSurfaceShown = surfaceShown;
        this.mService.updateNonSystemOverlayWindowsVisibilityIfNeeded(this.mAnimator.mWin, surfaceShown);
        this.mAnimator.mWin.onSurfaceShownChanged(surfaceShown);
        Session session = this.mWindowSession;
        if (session != null) {
            session.onWindowSurfaceVisibilityChanged(this, this.mSurfaceShown, this.mWindowType);
        }
    }

    float getX() {
        return this.mSurfaceX;
    }

    float getY() {
        return this.mSurfaceY;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getWidth() {
        return this.mSurfaceW;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getHeight() {
        return this.mSurfaceH;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1133871366145L, this.mSurfaceShown);
        proto.write(1120986464258L, this.mSurfaceLayer);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mSurface=");
            pw.println(this.mSurfaceControl);
        }
        pw.print(prefix);
        pw.print("Surface: shown=");
        pw.print(this.mSurfaceShown);
        pw.print(" layer=");
        pw.print(this.mSurfaceLayer);
        pw.print(" alpha=");
        pw.print(this.mSurfaceAlpha);
        pw.print(" rect=(");
        pw.print(this.mSurfaceX);
        pw.print(",");
        pw.print(this.mSurfaceY);
        pw.print(") ");
        pw.print(this.mSurfaceW);
        pw.print(" x ");
        pw.print(this.mSurfaceH);
        pw.print(" transform=(");
        pw.print(this.mLastDsdx);
        pw.print(", ");
        pw.print(this.mLastDtdx);
        pw.print(", ");
        pw.print(this.mLastDsdy);
        pw.print(", ");
        pw.print(this.mLastDtdy);
        pw.println(")");
    }

    public String toString() {
        return this.mSurfaceControl.toString();
    }
}
