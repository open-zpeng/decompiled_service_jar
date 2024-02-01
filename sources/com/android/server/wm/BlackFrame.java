package com.android.server.wm;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import java.io.PrintWriter;

/* loaded from: classes2.dex */
public class BlackFrame {
    final boolean mForceDefaultOrientation;
    final Rect mInnerRect;
    final Rect mOuterRect;
    private final TransactionFactory mTransactionFactory;
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];
    final BlackSurface[] mBlackSurfaces = new BlackSurface[4];

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public class BlackSurface {
        final int layer;
        final int left;
        final SurfaceControl surface;
        final int top;

        BlackSurface(SurfaceControl.Transaction transaction, int layer, int l, int t, int r, int b, DisplayContent dc) throws Surface.OutOfResourcesException {
            this.left = l;
            this.top = t;
            this.layer = layer;
            int w = r - l;
            int h = b - t;
            this.surface = dc.makeOverlay().setName("BlackSurface").setColorLayer().setParent(null).build();
            transaction.setWindowCrop(this.surface, w, h);
            transaction.setLayerStack(this.surface, dc.getDisplayId());
            transaction.setAlpha(this.surface, 1.0f);
            transaction.setLayer(this.surface, layer);
            transaction.setPosition(this.surface, this.left, this.top);
            transaction.show(this.surface);
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                Slog.i("WindowManager", "  BLACK " + this.surface + ": CREATE layer=" + layer);
            }
        }

        void setAlpha(SurfaceControl.Transaction t, float alpha) {
            t.setAlpha(this.surface, alpha);
        }

        void setMatrix(SurfaceControl.Transaction t, Matrix matrix) {
            BlackFrame.this.mTmpMatrix.setTranslate(this.left, this.top);
            BlackFrame.this.mTmpMatrix.postConcat(matrix);
            BlackFrame.this.mTmpMatrix.getValues(BlackFrame.this.mTmpFloats);
            t.setPosition(this.surface, BlackFrame.this.mTmpFloats[2], BlackFrame.this.mTmpFloats[5]);
            t.setMatrix(this.surface, BlackFrame.this.mTmpFloats[0], BlackFrame.this.mTmpFloats[3], BlackFrame.this.mTmpFloats[1], BlackFrame.this.mTmpFloats[4]);
        }

        void clearMatrix(SurfaceControl.Transaction t) {
            t.setMatrix(this.surface, 1.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("Outer: ");
        this.mOuterRect.printShortString(pw);
        pw.print(" / Inner: ");
        this.mInnerRect.printShortString(pw);
        pw.println();
        int i = 0;
        while (true) {
            BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
            if (i < blackSurfaceArr.length) {
                BlackSurface bs = blackSurfaceArr[i];
                pw.print(prefix);
                pw.print("#");
                pw.print(i);
                pw.print(": ");
                pw.print(bs.surface);
                pw.print(" left=");
                pw.print(bs.left);
                pw.print(" top=");
                pw.println(bs.top);
                i++;
            } else {
                return;
            }
        }
    }

    public BlackFrame(TransactionFactory factory, SurfaceControl.Transaction t, Rect outer, Rect inner, int layer, DisplayContent dc, boolean forceDefaultOrientation) throws Surface.OutOfResourcesException {
        boolean success = false;
        this.mTransactionFactory = factory;
        this.mForceDefaultOrientation = forceDefaultOrientation;
        this.mOuterRect = new Rect(outer);
        this.mInnerRect = new Rect(inner);
        try {
            if (outer.top < inner.top) {
                this.mBlackSurfaces[0] = new BlackSurface(t, layer, outer.left, outer.top, inner.right, inner.top, dc);
            }
            if (outer.left < inner.left) {
                this.mBlackSurfaces[1] = new BlackSurface(t, layer, outer.left, inner.top, inner.left, outer.bottom, dc);
            }
            if (outer.bottom > inner.bottom) {
                this.mBlackSurfaces[2] = new BlackSurface(t, layer, inner.left, inner.bottom, outer.right, outer.bottom, dc);
            }
            if (outer.right > inner.right) {
                this.mBlackSurfaces[3] = new BlackSurface(t, layer, inner.right, outer.top, outer.right, inner.bottom, dc);
            }
            success = true;
        } finally {
            if (!success) {
                kill();
            }
        }
    }

    public void kill() {
        if (this.mBlackSurfaces != null) {
            SurfaceControl.Transaction t = this.mTransactionFactory.make();
            int i = 0;
            while (true) {
                BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
                if (i < blackSurfaceArr.length) {
                    if (blackSurfaceArr[i] != null) {
                        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                            Slog.i("WindowManager", "  BLACK " + this.mBlackSurfaces[i].surface + ": DESTROY");
                        }
                        t.remove(this.mBlackSurfaces[i].surface);
                        this.mBlackSurfaces[i] = null;
                    }
                    i++;
                } else {
                    t.apply();
                    return;
                }
            }
        }
    }

    public void hide(SurfaceControl.Transaction t) {
        if (this.mBlackSurfaces != null) {
            int i = 0;
            while (true) {
                BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
                if (i < blackSurfaceArr.length) {
                    if (blackSurfaceArr[i] != null) {
                        t.hide(blackSurfaceArr[i].surface);
                    }
                    i++;
                } else {
                    return;
                }
            }
        }
    }

    public void setAlpha(SurfaceControl.Transaction t, float alpha) {
        int i = 0;
        while (true) {
            BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
            if (i < blackSurfaceArr.length) {
                if (blackSurfaceArr[i] != null) {
                    blackSurfaceArr[i].setAlpha(t, alpha);
                }
                i++;
            } else {
                return;
            }
        }
    }

    public void setMatrix(SurfaceControl.Transaction t, Matrix matrix) {
        int i = 0;
        while (true) {
            BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
            if (i < blackSurfaceArr.length) {
                if (blackSurfaceArr[i] != null) {
                    blackSurfaceArr[i].setMatrix(t, matrix);
                }
                i++;
            } else {
                return;
            }
        }
    }

    public void clearMatrix(SurfaceControl.Transaction t) {
        int i = 0;
        while (true) {
            BlackSurface[] blackSurfaceArr = this.mBlackSurfaces;
            if (i < blackSurfaceArr.length) {
                if (blackSurfaceArr[i] != null) {
                    blackSurfaceArr[i].clearMatrix(t);
                }
                i++;
            } else {
                return;
            }
        }
    }
}
