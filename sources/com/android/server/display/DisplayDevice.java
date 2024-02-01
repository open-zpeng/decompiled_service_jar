package com.android.server.display;

import android.graphics.Rect;
import android.hardware.display.DisplayViewport;
import android.os.IBinder;
import android.util.Slog;
import android.view.DisplayAddress;
import android.view.Surface;
import android.view.SurfaceControl;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public abstract class DisplayDevice {
    private Rect mCurrentDisplayRect;
    private Rect mCurrentLayerStackRect;
    private Surface mCurrentSurface;
    DisplayDeviceInfo mDebugLastLoggedDeviceInfo;
    private final DisplayAdapter mDisplayAdapter;
    private final IBinder mDisplayToken;
    private final String mUniqueId;
    private int mCurrentLayerStack = -1;
    private int mCurrentOrientation = -1;

    public abstract DisplayDeviceInfo getDisplayDeviceInfoLocked();

    public abstract boolean hasStableUniqueId();

    public DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken, String uniqueId) {
        this.mDisplayAdapter = displayAdapter;
        this.mDisplayToken = displayToken;
        this.mUniqueId = uniqueId;
    }

    public final DisplayAdapter getAdapterLocked() {
        return this.mDisplayAdapter;
    }

    public final IBinder getDisplayTokenLocked() {
        return this.mDisplayToken;
    }

    public final String getNameLocked() {
        return getDisplayDeviceInfoLocked().name;
    }

    public final String getUniqueId() {
        return this.mUniqueId;
    }

    public void applyPendingDisplayDeviceInfoChangesLocked() {
    }

    public void performTraversalLocked(SurfaceControl.Transaction t) {
    }

    public Runnable requestDisplayStateLocked(int state, int brightness) {
        return null;
    }

    public void setAllowedDisplayModesLocked(int[] modes) {
    }

    public void setRequestedColorModeLocked(int colorMode) {
    }

    public void onOverlayChangedLocked() {
    }

    public final void setLayerStackLocked(SurfaceControl.Transaction t, int layerStack) {
        if (this.mCurrentLayerStack != layerStack) {
            Slog.i("DisplayDevice", "mCurrentLayerStack-->" + this.mCurrentLayerStack + ", layerStack-->" + layerStack + ", deviceType-->" + getDisplayDeviceInfoLocked().type);
            this.mCurrentLayerStack = layerStack;
            t.setDisplayLayerStack(this.mDisplayToken, layerStack);
        }
    }

    public final void setProjectionLocked(SurfaceControl.Transaction t, int orientation, Rect layerStackRect, Rect displayRect) {
        Rect rect;
        Rect rect2;
        if (this.mCurrentOrientation != orientation || (rect = this.mCurrentLayerStackRect) == null || !rect.equals(layerStackRect) || (rect2 = this.mCurrentDisplayRect) == null || !rect2.equals(displayRect)) {
            this.mCurrentOrientation = orientation;
            if (this.mCurrentLayerStackRect == null) {
                this.mCurrentLayerStackRect = new Rect();
            }
            this.mCurrentLayerStackRect.set(layerStackRect);
            if (this.mCurrentDisplayRect == null) {
                this.mCurrentDisplayRect = new Rect();
            }
            this.mCurrentDisplayRect.set(displayRect);
            t.setDisplayProjection(this.mDisplayToken, orientation, layerStackRect, displayRect);
        }
    }

    public final void setSurfaceLocked(SurfaceControl.Transaction t, Surface surface) {
        if (this.mCurrentSurface != surface) {
            this.mCurrentSurface = surface;
            t.setDisplaySurface(this.mDisplayToken, surface);
        }
    }

    public final void populateViewportLocked(DisplayViewport viewport) {
        viewport.orientation = this.mCurrentOrientation;
        if (this.mCurrentLayerStackRect != null) {
            viewport.logicalFrame.set(this.mCurrentLayerStackRect);
        } else {
            viewport.logicalFrame.setEmpty();
        }
        if (this.mCurrentDisplayRect != null) {
            viewport.physicalFrame.set(this.mCurrentDisplayRect);
        } else {
            viewport.physicalFrame.setEmpty();
        }
        int i = this.mCurrentOrientation;
        boolean z = true;
        if (i != 1 && i != 3) {
            z = false;
        }
        boolean isRotated = z;
        DisplayDeviceInfo info = getDisplayDeviceInfoLocked();
        viewport.deviceWidth = isRotated ? info.height : info.width;
        viewport.deviceHeight = isRotated ? info.width : info.height;
        viewport.uniqueId = info.uniqueId;
        if (info.address instanceof DisplayAddress.Physical) {
            viewport.physicalPort = Byte.valueOf(info.address.getPort());
        } else {
            viewport.physicalPort = null;
        }
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("mAdapter=" + this.mDisplayAdapter.getName());
        pw.println("mUniqueId=" + this.mUniqueId);
        pw.println("mDisplayToken=" + this.mDisplayToken);
        pw.println("mCurrentLayerStack=" + this.mCurrentLayerStack);
        pw.println("mCurrentOrientation=" + this.mCurrentOrientation);
        pw.println("mCurrentLayerStackRect=" + this.mCurrentLayerStackRect);
        pw.println("mCurrentDisplayRect=" + this.mCurrentDisplayRect);
        pw.println("mCurrentSurface=" + this.mCurrentSurface);
    }
}
