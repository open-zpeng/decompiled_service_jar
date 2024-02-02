package com.android.server.display;

import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;
import android.view.Choreographer;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import com.android.server.LocalServices;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class XpColorFade {
    private static final int COLOR_FADE_LAYER = 1073741825;
    private static final boolean DEBUG = false;
    public static final int MODE_FADE = 2;
    private static final String TAG = "XpColorFade";
    private boolean mColorFadeDrawPending;
    private int mDisplayHeight;
    private final int mDisplayId;
    private int mDisplayLayerStack;
    private int mDisplayWidth;
    private int mMode;
    private boolean mPrepared;
    private Surface mSurface;
    private float mSurfaceAlpha;
    private SurfaceControl mSurfaceControl;
    private NaturalSurfaceLayout mSurfaceLayout;
    private SurfaceSession mSurfaceSession;
    private boolean mSurfaceVisible;
    private final Runnable mColorFadeDrawRunnable = new Runnable() { // from class: com.android.server.display.XpColorFade.1
        @Override // java.lang.Runnable
        public void run() {
            XpColorFade.this.mColorFadeDrawPending = false;
            if (XpColorFade.this.mColorFadePrepared) {
                XpColorFade.this.draw(XpColorFade.this.mColorFadeLevel);
            }
        }
    };
    private final Choreographer mChoreographer = Choreographer.getInstance();
    private float mColorFadeLevel = 1.0f;
    private boolean mColorFadePrepared = false;
    private final DisplayManagerInternal mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);

    public XpColorFade(int displayId) {
        this.mDisplayId = displayId;
    }

    public boolean prepare(int mode) {
        this.mMode = mode;
        DisplayInfo displayInfo = this.mDisplayManagerInternal.getDisplayInfo(this.mDisplayId);
        this.mDisplayLayerStack = displayInfo.layerStack;
        this.mDisplayWidth = displayInfo.getNaturalWidth();
        this.mDisplayHeight = displayInfo.getNaturalHeight();
        if (!createSurface()) {
            dismiss();
            return false;
        }
        this.mPrepared = true;
        this.mColorFadePrepared = true;
        scheduleColorFadeDraw();
        return true;
    }

    public void setColorFadeLevel(float level) {
        if (this.mColorFadeLevel != level) {
            this.mColorFadeLevel = level;
            if (this.mColorFadePrepared) {
                scheduleColorFadeDraw();
            }
        }
    }

    public float getColorFadeLevel() {
        return this.mColorFadeLevel;
    }

    public void dismiss() {
        if (this.mPrepared) {
            destroySurface();
            this.mPrepared = false;
        }
        this.mColorFadePrepared = false;
    }

    private void scheduleColorFadeDraw() {
        if (!this.mColorFadeDrawPending) {
            this.mColorFadeDrawPending = true;
            this.mChoreographer.postCallback(2, this.mColorFadeDrawRunnable, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean draw(float level) {
        if (!this.mPrepared) {
            return false;
        }
        return showSurface(1.0f - level);
    }

    private boolean createSurface() {
        int flags;
        if (this.mSurfaceSession == null) {
            this.mSurfaceSession = new SurfaceSession();
        }
        SurfaceControl.openTransaction();
        try {
            if (this.mSurfaceControl == null) {
                try {
                    if (this.mMode == 2) {
                        flags = 131076;
                    } else {
                        flags = UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE_SUPRESS;
                    }
                    this.mSurfaceControl = new SurfaceControl.Builder(this.mSurfaceSession).setName("ColorFade").setSize(this.mDisplayWidth, this.mDisplayHeight).setFlags(flags).build();
                    this.mSurfaceControl.setLayerStack(this.mDisplayLayerStack);
                    this.mSurfaceControl.setSize(this.mDisplayWidth, this.mDisplayHeight);
                    this.mSurface = new Surface();
                    this.mSurface.copyFrom(this.mSurfaceControl);
                    this.mSurfaceLayout = new NaturalSurfaceLayout(this.mDisplayManagerInternal, this.mDisplayId, this.mSurfaceControl);
                    this.mSurfaceLayout.onDisplayTransaction();
                } catch (Surface.OutOfResourcesException ex) {
                    Slog.e(TAG, "Unable to create surface.", ex);
                    SurfaceControl.closeTransaction();
                    return false;
                }
            }
            SurfaceControl.closeTransaction();
            return true;
        } catch (Throwable th) {
            SurfaceControl.closeTransaction();
            throw th;
        }
    }

    private void destroySurface() {
        if (this.mSurfaceControl != null) {
            this.mSurfaceLayout.dispose();
            this.mSurfaceLayout = null;
            SurfaceControl.openTransaction();
            try {
                this.mSurfaceControl.destroy();
                this.mSurface.release();
                SurfaceControl.closeTransaction();
                this.mSurfaceControl = null;
                this.mSurfaceVisible = false;
                this.mSurfaceAlpha = 0.0f;
            } catch (Throwable th) {
                SurfaceControl.closeTransaction();
                throw th;
            }
        }
    }

    private boolean showSurface(float alpha) {
        if (!this.mSurfaceVisible || this.mSurfaceAlpha != alpha) {
            SurfaceControl.openTransaction();
            try {
                this.mSurfaceControl.setLayer(1073741825);
                this.mSurfaceControl.setAlpha(alpha);
                this.mSurfaceControl.show();
                SurfaceControl.closeTransaction();
                this.mSurfaceVisible = true;
                this.mSurfaceAlpha = alpha;
            } catch (Throwable th) {
                SurfaceControl.closeTransaction();
                throw th;
            }
        }
        return true;
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Xp Color Fade State:");
        pw.println("  mPrepared=" + this.mPrepared);
        pw.println("  mMode=" + this.mMode);
        pw.println("  mDisplayLayerStack=" + this.mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + this.mDisplayWidth);
        pw.println("  mDisplayHeight=" + this.mDisplayHeight);
        pw.println("  mSurfaceVisible=" + this.mSurfaceVisible);
        pw.println("  mSurfaceAlpha=" + this.mSurfaceAlpha);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class NaturalSurfaceLayout implements DisplayManagerInternal.DisplayTransactionListener {
        private final int mDisplayId;
        private final DisplayManagerInternal mDisplayManagerInternal;
        private SurfaceControl mSurfaceControl;

        public NaturalSurfaceLayout(DisplayManagerInternal displayManagerInternal, int displayId, SurfaceControl surfaceControl) {
            this.mDisplayManagerInternal = displayManagerInternal;
            this.mDisplayId = displayId;
            this.mSurfaceControl = surfaceControl;
            this.mDisplayManagerInternal.registerDisplayTransactionListener(this);
        }

        public void dispose() {
            synchronized (this) {
                this.mSurfaceControl = null;
            }
            this.mDisplayManagerInternal.unregisterDisplayTransactionListener(this);
        }

        public void onDisplayTransaction() {
            synchronized (this) {
                if (this.mSurfaceControl == null) {
                    return;
                }
                DisplayInfo displayInfo = this.mDisplayManagerInternal.getDisplayInfo(this.mDisplayId);
                switch (displayInfo.rotation) {
                    case 0:
                        this.mSurfaceControl.setPosition(0.0f, 0.0f);
                        this.mSurfaceControl.setMatrix(1.0f, 0.0f, 0.0f, 1.0f);
                        break;
                    case 1:
                        this.mSurfaceControl.setPosition(0.0f, displayInfo.logicalHeight);
                        this.mSurfaceControl.setMatrix(0.0f, -1.0f, 1.0f, 0.0f);
                        break;
                    case 2:
                        this.mSurfaceControl.setPosition(displayInfo.logicalWidth, displayInfo.logicalHeight);
                        this.mSurfaceControl.setMatrix(-1.0f, 0.0f, 0.0f, -1.0f);
                        break;
                    case 3:
                        this.mSurfaceControl.setPosition(displayInfo.logicalWidth, 0.0f);
                        this.mSurfaceControl.setMatrix(0.0f, 1.0f, -1.0f, 0.0f);
                        break;
                }
            }
        }
    }
}
