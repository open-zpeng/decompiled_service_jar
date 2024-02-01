package com.android.server.display;

import android.graphics.Rect;
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
    private final int mDisplayId;
    private int mDisplayOffsetX;
    private int mDisplayOffsetY;
    private boolean mDisplayScalingDisabled;
    private boolean mHasContent;
    private DisplayInfo mInfo;
    private final int mLayerStack;
    private DisplayInfo mOverrideDisplayInfo;
    private DisplayDevice mPrimaryDisplayDevice;
    private DisplayDeviceInfo mPrimaryDisplayDeviceInfo;
    private int mRequestedColorMode;
    private final DisplayInfo mBaseDisplayInfo = new DisplayInfo();
    private int[] mAllowedDisplayModes = new int[0];
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
            DisplayInfo displayInfo = this.mOverrideDisplayInfo;
            if (displayInfo != null) {
                this.mInfo.appWidth = displayInfo.appWidth;
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
            DisplayInfo displayInfo = this.mOverrideDisplayInfo;
            if (displayInfo == null) {
                this.mOverrideDisplayInfo = new DisplayInfo(info);
                this.mInfo = null;
                return true;
            } else if (!displayInfo.equals(info)) {
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
        DisplayDevice displayDevice = this.mPrimaryDisplayDevice;
        if (displayDevice == null) {
            return;
        }
        if (!devices.contains(displayDevice)) {
            this.mPrimaryDisplayDevice = null;
            return;
        }
        DisplayDeviceInfo deviceInfo = this.mPrimaryDisplayDevice.getDisplayDeviceInfoLocked();
        if (!Objects.equals(this.mPrimaryDisplayDeviceInfo, deviceInfo)) {
            DisplayInfo displayInfo = this.mBaseDisplayInfo;
            displayInfo.layerStack = this.mLayerStack;
            displayInfo.flags = 0;
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
            if ((deviceInfo.flags & 4096) != 0) {
                this.mBaseDisplayInfo.flags |= 64;
            }
            Rect maskingInsets = getMaskingInsets(deviceInfo);
            int maskedWidth = (deviceInfo.width - maskingInsets.left) - maskingInsets.right;
            int maskedHeight = (deviceInfo.height - maskingInsets.top) - maskingInsets.bottom;
            this.mBaseDisplayInfo.type = deviceInfo.type;
            this.mBaseDisplayInfo.address = deviceInfo.address;
            this.mBaseDisplayInfo.name = deviceInfo.name;
            this.mBaseDisplayInfo.uniqueId = deviceInfo.uniqueId;
            DisplayInfo displayInfo2 = this.mBaseDisplayInfo;
            displayInfo2.appWidth = maskedWidth;
            displayInfo2.appHeight = maskedHeight;
            displayInfo2.logicalWidth = maskedWidth;
            displayInfo2.logicalHeight = maskedHeight;
            displayInfo2.rotation = 0;
            displayInfo2.modeId = deviceInfo.modeId;
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
            this.mBaseDisplayInfo.state = deviceInfo.state;
            DisplayInfo displayInfo3 = this.mBaseDisplayInfo;
            displayInfo3.smallestNominalAppWidth = maskedWidth;
            displayInfo3.smallestNominalAppHeight = maskedHeight;
            displayInfo3.largestNominalAppWidth = maskedWidth;
            displayInfo3.largestNominalAppHeight = maskedHeight;
            displayInfo3.ownerUid = deviceInfo.ownerUid;
            this.mBaseDisplayInfo.ownerPackageName = deviceInfo.ownerPackageName;
            boolean maskCutout = (deviceInfo.flags & 2048) != 0;
            this.mBaseDisplayInfo.displayCutout = maskCutout ? null : deviceInfo.displayCutout;
            this.mBaseDisplayInfo.displayId = this.mDisplayId;
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
            device.setAllowedDisplayModesLocked(this.mAllowedDisplayModes);
            device.setRequestedColorModeLocked(this.mRequestedColorMode);
        } else {
            device.setAllowedDisplayModesLocked(new int[]{0});
            device.setRequestedColorModeLocked(0);
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
        if ((displayInfo.flags & 1073741824) != 0 || this.mDisplayScalingDisabled) {
            displayRectWidth = displayInfo.logicalWidth;
            displayRectHeight = displayInfo.logicalHeight;
        } else if (displayInfo.logicalHeight * physWidth2 < displayInfo.logicalWidth * physHeight2) {
            displayRectWidth = physWidth2;
            displayRectHeight = (displayInfo.logicalHeight * physWidth2) / displayInfo.logicalWidth;
        } else {
            int displayRectWidth2 = displayInfo.logicalWidth;
            displayRectWidth = (displayRectWidth2 * physHeight2) / displayInfo.logicalHeight;
            displayRectHeight = physHeight2;
        }
        int displayRectTop = (physHeight2 - displayRectHeight) / 2;
        int displayRectLeft = (physWidth2 - displayRectWidth) / 2;
        this.mTempDisplayRect.set(displayRectLeft, displayRectTop, displayRectLeft + displayRectWidth, displayRectTop + displayRectHeight);
        this.mTempDisplayRect.offset(maskingInsets.left, maskingInsets.top);
        if (orientation2 == 0) {
            this.mTempDisplayRect.offset(this.mDisplayOffsetX, this.mDisplayOffsetY);
        } else if (orientation2 == 1) {
            this.mTempDisplayRect.offset(this.mDisplayOffsetY, -this.mDisplayOffsetX);
        } else if (orientation2 == 2) {
            this.mTempDisplayRect.offset(-this.mDisplayOffsetX, -this.mDisplayOffsetY);
        } else {
            this.mTempDisplayRect.offset(-this.mDisplayOffsetY, this.mDisplayOffsetX);
        }
        device.setProjectionLocked(t, orientation2, this.mTempLayerStackRect, this.mTempDisplayRect);
    }

    public boolean hasContentLocked() {
        return this.mHasContent;
    }

    public void setHasContentLocked(boolean hasContent) {
        this.mHasContent = hasContent;
    }

    public void setAllowedDisplayModesLocked(int[] modes) {
        this.mAllowedDisplayModes = modes;
    }

    public int[] getAllowedDisplayModesLocked() {
        return this.mAllowedDisplayModes;
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

    public boolean isDisplayScalingDisabled() {
        return this.mDisplayScalingDisabled;
    }

    public void setDisplayScalingDisabledLocked(boolean disableScaling) {
        this.mDisplayScalingDisabled = disableScaling;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("mDisplayId=" + this.mDisplayId);
        pw.println("mLayerStack=" + this.mLayerStack);
        pw.println("mHasContent=" + this.mHasContent);
        pw.println("mAllowedDisplayModes=" + Arrays.toString(this.mAllowedDisplayModes));
        pw.println("mRequestedColorMode=" + this.mRequestedColorMode);
        pw.println("mDisplayOffset=(" + this.mDisplayOffsetX + ", " + this.mDisplayOffsetY + ")");
        StringBuilder sb = new StringBuilder();
        sb.append("mDisplayScalingDisabled=");
        sb.append(this.mDisplayScalingDisabled);
        pw.println(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        sb2.append("mPrimaryDisplayDevice=");
        DisplayDevice displayDevice = this.mPrimaryDisplayDevice;
        sb2.append(displayDevice != null ? displayDevice.getNameLocked() : "null");
        pw.println(sb2.toString());
        pw.println("mBaseDisplayInfo=" + this.mBaseDisplayInfo);
        pw.println("mOverrideDisplayInfo=" + this.mOverrideDisplayInfo);
    }
}
