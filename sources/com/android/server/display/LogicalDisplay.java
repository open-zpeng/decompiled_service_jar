package com.android.server.display;

import android.graphics.Rect;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import com.android.server.wm.utils.InsetUtils;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
/* loaded from: classes.dex */
final class LogicalDisplay {
    private static final int BLANK_LAYER_STACK = -1;
    private static final String PROP_MASKING_INSET_TOP = "persist.sys.displayinset.top";
    private final int mDisplayId;
    private int mDisplayOffsetX;
    private int mDisplayOffsetY;
    private boolean mHasContent;
    private DisplayInfo mInfo;
    private final int mLayerStack;
    private DisplayInfo mOverrideDisplayInfo;
    private DisplayDevice mPrimaryDisplayDevice;
    private DisplayDeviceInfo mPrimaryDisplayDeviceInfo;
    private int mRequestedColorMode;
    private int mRequestedModeId;
    private final DisplayInfo mBaseDisplayInfo = new DisplayInfo();
    private final Rect mTempLayerStackRect = new Rect();
    private final Rect mTempDisplayRect = new Rect();

    public LogicalDisplay(int displayId, int layerStack, DisplayDevice primaryDisplayDevice) {
        this.mDisplayId = displayId;
        this.mLayerStack = layerStack;
        this.mPrimaryDisplayDevice = primaryDisplayDevice;
    }

    public int getDisplayIdLocked() {
        return this.mDisplayId;
    }

    public DisplayDevice getPrimaryDisplayDeviceLocked() {
        return this.mPrimaryDisplayDevice;
    }

    public DisplayInfo getDisplayInfoLocked() {
        if (this.mInfo == null) {
            this.mInfo = new DisplayInfo();
            this.mInfo.copyFrom(this.mBaseDisplayInfo);
            if (this.mOverrideDisplayInfo != null) {
                this.mInfo.appWidth = this.mOverrideDisplayInfo.appWidth;
                this.mInfo.appHeight = this.mOverrideDisplayInfo.appHeight;
                this.mInfo.smallestNominalAppWidth = this.mOverrideDisplayInfo.smallestNominalAppWidth;
                this.mInfo.smallestNominalAppHeight = this.mOverrideDisplayInfo.smallestNominalAppHeight;
                this.mInfo.largestNominalAppWidth = this.mOverrideDisplayInfo.largestNominalAppWidth;
                this.mInfo.largestNominalAppHeight = this.mOverrideDisplayInfo.largestNominalAppHeight;
                this.mInfo.logicalWidth = this.mOverrideDisplayInfo.logicalWidth;
                this.mInfo.logicalHeight = this.mOverrideDisplayInfo.logicalHeight;
                this.mInfo.overscanLeft = this.mOverrideDisplayInfo.overscanLeft;
                this.mInfo.overscanTop = this.mOverrideDisplayInfo.overscanTop;
                this.mInfo.overscanRight = this.mOverrideDisplayInfo.overscanRight;
                this.mInfo.overscanBottom = this.mOverrideDisplayInfo.overscanBottom;
                this.mInfo.rotation = this.mOverrideDisplayInfo.rotation;
                this.mInfo.displayCutout = this.mOverrideDisplayInfo.displayCutout;
                this.mInfo.logicalDensityDpi = this.mOverrideDisplayInfo.logicalDensityDpi;
                this.mInfo.physicalXDpi = this.mOverrideDisplayInfo.physicalXDpi;
                this.mInfo.physicalYDpi = this.mOverrideDisplayInfo.physicalYDpi;
            }
        }
        return this.mInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getNonOverrideDisplayInfoLocked(DisplayInfo outInfo) {
        outInfo.copyFrom(this.mBaseDisplayInfo);
    }

    public boolean setDisplayInfoOverrideFromWindowManagerLocked(DisplayInfo info) {
        if (info != null) {
            if (this.mOverrideDisplayInfo == null) {
                this.mOverrideDisplayInfo = new DisplayInfo(info);
                this.mInfo = null;
                return true;
            } else if (!this.mOverrideDisplayInfo.equals(info)) {
                this.mOverrideDisplayInfo.copyFrom(info);
                this.mInfo = null;
                return true;
            } else {
                return false;
            }
        } else if (this.mOverrideDisplayInfo != null) {
            this.mOverrideDisplayInfo = null;
            this.mInfo = null;
            return true;
        } else {
            return false;
        }
    }

    public boolean isValidLocked() {
        return this.mPrimaryDisplayDevice != null;
    }

    public void updateLocked(List<DisplayDevice> devices) {
        if (this.mPrimaryDisplayDevice == null) {
            return;
        }
        if (!devices.contains(this.mPrimaryDisplayDevice)) {
            this.mPrimaryDisplayDevice = null;
            return;
        }
        DisplayDeviceInfo deviceInfo = this.mPrimaryDisplayDevice.getDisplayDeviceInfoLocked();
        if (!Objects.equals(this.mPrimaryDisplayDeviceInfo, deviceInfo)) {
            this.mBaseDisplayInfo.layerStack = this.mLayerStack;
            this.mBaseDisplayInfo.flags = 0;
            if ((deviceInfo.flags & 8) != 0) {
                this.mBaseDisplayInfo.flags |= 1;
            }
            if ((deviceInfo.flags & 4) != 0) {
                this.mBaseDisplayInfo.flags |= 2;
            }
            if ((deviceInfo.flags & 16) != 0) {
                this.mBaseDisplayInfo.flags |= 4;
                this.mBaseDisplayInfo.removeMode = 1;
            }
            if ((deviceInfo.flags & 1024) != 0) {
                this.mBaseDisplayInfo.removeMode = 1;
            }
            if ((deviceInfo.flags & 64) != 0) {
                this.mBaseDisplayInfo.flags |= 8;
            }
            if ((deviceInfo.flags & 256) != 0) {
                this.mBaseDisplayInfo.flags |= 16;
            }
            if ((deviceInfo.flags & 512) != 0) {
                this.mBaseDisplayInfo.flags |= 32;
            }
            Rect maskingInsets = getMaskingInsets(deviceInfo);
            int maskedWidth = (deviceInfo.width - maskingInsets.left) - maskingInsets.right;
            int maskedHeight = (deviceInfo.height - maskingInsets.top) - maskingInsets.bottom;
            this.mBaseDisplayInfo.type = deviceInfo.type;
            this.mBaseDisplayInfo.address = deviceInfo.address;
            this.mBaseDisplayInfo.name = deviceInfo.name;
            this.mBaseDisplayInfo.uniqueId = deviceInfo.uniqueId;
            this.mBaseDisplayInfo.appWidth = maskedWidth;
            this.mBaseDisplayInfo.appHeight = maskedHeight;
            this.mBaseDisplayInfo.logicalWidth = maskedWidth;
            this.mBaseDisplayInfo.logicalHeight = maskedHeight;
            this.mBaseDisplayInfo.rotation = 0;
            this.mBaseDisplayInfo.modeId = deviceInfo.modeId;
            this.mBaseDisplayInfo.defaultModeId = deviceInfo.defaultModeId;
            this.mBaseDisplayInfo.supportedModes = (Display.Mode[]) Arrays.copyOf(deviceInfo.supportedModes, deviceInfo.supportedModes.length);
            this.mBaseDisplayInfo.colorMode = deviceInfo.colorMode;
            this.mBaseDisplayInfo.supportedColorModes = Arrays.copyOf(deviceInfo.supportedColorModes, deviceInfo.supportedColorModes.length);
            this.mBaseDisplayInfo.hdrCapabilities = deviceInfo.hdrCapabilities;
            this.mBaseDisplayInfo.logicalDensityDpi = deviceInfo.densityDpi;
            this.mBaseDisplayInfo.physicalXDpi = deviceInfo.xDpi;
            this.mBaseDisplayInfo.physicalYDpi = deviceInfo.yDpi;
            this.mBaseDisplayInfo.appVsyncOffsetNanos = deviceInfo.appVsyncOffsetNanos;
            this.mBaseDisplayInfo.presentationDeadlineNanos = deviceInfo.presentationDeadlineNanos;
            Slog.i("LogicalDisplay", "deviceInfo.state: " + deviceInfo.state);
            this.mBaseDisplayInfo.state = deviceInfo.state;
            this.mBaseDisplayInfo.smallestNominalAppWidth = maskedWidth;
            this.mBaseDisplayInfo.smallestNominalAppHeight = maskedHeight;
            this.mBaseDisplayInfo.largestNominalAppWidth = maskedWidth;
            this.mBaseDisplayInfo.largestNominalAppHeight = maskedHeight;
            this.mBaseDisplayInfo.ownerUid = deviceInfo.ownerUid;
            this.mBaseDisplayInfo.ownerPackageName = deviceInfo.ownerPackageName;
            boolean maskCutout = (deviceInfo.flags & 2048) != 0;
            this.mBaseDisplayInfo.displayCutout = maskCutout ? null : deviceInfo.displayCutout;
            this.mPrimaryDisplayDeviceInfo = deviceInfo;
            this.mInfo = null;
        }
    }

    public Rect getInsets() {
        return getMaskingInsets(this.mPrimaryDisplayDeviceInfo);
    }

    private static Rect getMaskingInsets(DisplayDeviceInfo deviceInfo) {
        boolean maskCutout = (deviceInfo.flags & 2048) != 0;
        if (maskCutout && deviceInfo.displayCutout != null) {
            return deviceInfo.displayCutout.getSafeInsets();
        }
        return new Rect();
    }

    public void configureDisplayLocked(SurfaceControl.Transaction t, DisplayDevice device, boolean isBlanked) {
        int displayRectWidth;
        int displayRectHeight;
        device.setLayerStackLocked(t, isBlanked ? -1 : this.mLayerStack);
        boolean rotated = false;
        if (device == this.mPrimaryDisplayDevice) {
            device.requestDisplayModesLocked(this.mRequestedColorMode, this.mRequestedModeId);
        } else {
            device.requestDisplayModesLocked(0, 0);
        }
        DisplayInfo displayInfo = getDisplayInfoLocked();
        DisplayDeviceInfo displayDeviceInfo = device.getDisplayDeviceInfoLocked();
        this.mTempLayerStackRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        int orientation = 0;
        if ((displayDeviceInfo.flags & 2) != 0) {
            orientation = displayInfo.rotation;
        }
        int orientation2 = (displayDeviceInfo.rotation + orientation) % 4;
        if (orientation2 == 1 || orientation2 == 3) {
            rotated = true;
        }
        int physWidth = rotated ? displayDeviceInfo.height : displayDeviceInfo.width;
        int physHeight = rotated ? displayDeviceInfo.width : displayDeviceInfo.height;
        Rect maskingInsets = getMaskingInsets(displayDeviceInfo);
        InsetUtils.rotateInsets(maskingInsets, orientation2);
        int physWidth2 = physWidth - (maskingInsets.left + maskingInsets.right);
        int physHeight2 = physHeight - (maskingInsets.top + maskingInsets.bottom);
        if ((displayInfo.flags & 1073741824) != 0) {
            displayRectWidth = displayInfo.logicalWidth;
            displayRectHeight = displayInfo.logicalHeight;
        } else {
            int displayRectWidth2 = displayInfo.logicalHeight;
            if (displayRectWidth2 * physWidth2 < displayInfo.logicalWidth * physHeight2) {
                displayRectWidth = physWidth2;
                displayRectHeight = (displayInfo.logicalHeight * physWidth2) / displayInfo.logicalWidth;
            } else {
                int displayRectWidth3 = displayInfo.logicalWidth;
                displayRectWidth = (displayRectWidth3 * physHeight2) / displayInfo.logicalHeight;
                displayRectHeight = physHeight2;
            }
        }
        int displayRectTop = (physHeight2 - displayRectHeight) / 2;
        int displayRectLeft = (physWidth2 - displayRectWidth) / 2;
        this.mTempDisplayRect.set(displayRectLeft, displayRectTop, displayRectLeft + displayRectWidth, displayRectTop + displayRectHeight);
        this.mTempDisplayRect.offset(maskingInsets.left, maskingInsets.top);
        this.mTempDisplayRect.left += this.mDisplayOffsetX;
        this.mTempDisplayRect.right += this.mDisplayOffsetX;
        this.mTempDisplayRect.top += this.mDisplayOffsetY;
        this.mTempDisplayRect.bottom += this.mDisplayOffsetY;
        device.setProjectionLocked(t, orientation2, this.mTempLayerStackRect, this.mTempDisplayRect);
    }

    public boolean hasContentLocked() {
        return this.mHasContent;
    }

    public void setHasContentLocked(boolean hasContent) {
        this.mHasContent = hasContent;
    }

    public void setRequestedModeIdLocked(int modeId) {
        this.mRequestedModeId = modeId;
    }

    public int getRequestedModeIdLocked() {
        return this.mRequestedModeId;
    }

    public void setRequestedColorModeLocked(int colorMode) {
        this.mRequestedColorMode = colorMode;
    }

    public int getRequestedColorModeLocked() {
        return this.mRequestedColorMode;
    }

    public int getDisplayOffsetXLocked() {
        return this.mDisplayOffsetX;
    }

    public int getDisplayOffsetYLocked() {
        return this.mDisplayOffsetY;
    }

    public void setDisplayOffsetsLocked(int x, int y) {
        this.mDisplayOffsetX = x;
        this.mDisplayOffsetY = y;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("mDisplayId=" + this.mDisplayId);
        pw.println("mLayerStack=" + this.mLayerStack);
        pw.println("mHasContent=" + this.mHasContent);
        pw.println("mRequestedMode=" + this.mRequestedModeId);
        pw.println("mRequestedColorMode=" + this.mRequestedColorMode);
        pw.println("mDisplayOffset=(" + this.mDisplayOffsetX + ", " + this.mDisplayOffsetY + ")");
        StringBuilder sb = new StringBuilder();
        sb.append("mPrimaryDisplayDevice=");
        sb.append(this.mPrimaryDisplayDevice != null ? this.mPrimaryDisplayDevice.getNameLocked() : "null");
        pw.println(sb.toString());
        pw.println("mBaseDisplayInfo=" + this.mBaseDisplayInfo);
        pw.println("mOverrideDisplayInfo=" + this.mOverrideDisplayInfo);
    }
}
