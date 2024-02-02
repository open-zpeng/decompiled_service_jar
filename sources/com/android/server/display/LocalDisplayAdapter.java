package com.android.server.display;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.sidekick.SidekickInternal;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.SurfaceControl;
import com.android.server.LocalServices;
import com.android.server.display.DisplayAdapter;
import com.android.server.display.DisplayManagerService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.xpeng.server.display.ResumeProfEvent;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class LocalDisplayAdapter extends DisplayAdapter {
    private static final int[] BUILT_IN_DISPLAY_IDS_TO_SCAN = {0, 1};
    private static final boolean DEBUG = false;
    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";
    private static final String TAG = "LocalDisplayAdapter";
    private static final String UNIQUE_ID_PREFIX = "local:";
    private final SparseArray<LocalDisplayDevice> mDevices;
    private HotplugDisplayEventReceiver mHotplugReceiver;

    public LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot, Context context, Handler handler, DisplayAdapter.Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
        this.mDevices = new SparseArray<>();
    }

    @Override // com.android.server.display.DisplayAdapter
    public void registerLocked() {
        int[] iArr;
        super.registerLocked();
        this.mHotplugReceiver = new HotplugDisplayEventReceiver(getHandler().getLooper());
        for (int builtInDisplayId : BUILT_IN_DISPLAY_IDS_TO_SCAN) {
            tryConnectDisplayLocked(builtInDisplayId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryConnectDisplayLocked(int builtInDisplayId) {
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(builtInDisplayId);
        if (displayToken != null) {
            SurfaceControl.PhysicalDisplayInfo[] configs = SurfaceControl.getDisplayConfigs(displayToken);
            if (configs == null) {
                Slog.w(TAG, "No valid configs found for display device " + builtInDisplayId);
                return;
            }
            int activeConfig = SurfaceControl.getActiveConfig(displayToken);
            if (activeConfig < 0) {
                Slog.w(TAG, "No active config found for display device " + builtInDisplayId);
                return;
            }
            int activeColorMode = SurfaceControl.getActiveColorMode(displayToken);
            if (activeColorMode < 0) {
                Slog.w(TAG, "Unable to get active color mode for display device " + builtInDisplayId);
                activeColorMode = -1;
            }
            int activeColorMode2 = activeColorMode;
            int[] colorModes = SurfaceControl.getDisplayColorModes(displayToken);
            LocalDisplayDevice device = this.mDevices.get(builtInDisplayId);
            if (device == null) {
                LocalDisplayDevice device2 = new LocalDisplayDevice(displayToken, builtInDisplayId, configs, activeConfig, colorModes, activeColorMode2);
                this.mDevices.put(builtInDisplayId, device2);
                sendDisplayDeviceEventLocked(device2, 1);
            } else if (device.updatePhysicalDisplayInfoLocked(configs, activeConfig, colorModes, activeColorMode2)) {
                sendDisplayDeviceEventLocked(device, 2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryDisconnectDisplayLocked(int builtInDisplayId) {
        LocalDisplayDevice device = this.mDevices.get(builtInDisplayId);
        if (device != null) {
            this.mDevices.remove(builtInDisplayId);
            sendDisplayDeviceEventLocked(device, 3);
        }
    }

    static int getPowerModeForState(int state) {
        if (state != 1) {
            if (state != 6) {
                switch (state) {
                    case 3:
                        return 1;
                    case 4:
                        return 3;
                    default:
                        return 2;
                }
            }
            return 4;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class LocalDisplayDevice extends DisplayDevice {
        static final /* synthetic */ boolean $assertionsDisabled = false;
        private int mActiveColorMode;
        private boolean mActiveColorModeInvalid;
        private int mActiveModeId;
        private boolean mActiveModeInvalid;
        private int mActivePhysIndex;
        private final Light mBacklight;
        private int mBrightness;
        private final int mBuiltInDisplayId;
        private int mDefaultModeId;
        private SurfaceControl.PhysicalDisplayInfo[] mDisplayInfos;
        private boolean mHavePendingChanges;
        private Display.HdrCapabilities mHdrCapabilities;
        private DisplayDeviceInfo mInfo;
        private boolean mSidekickActive;
        private SidekickInternal mSidekickInternal;
        private int mState;
        private final ArrayList<Integer> mSupportedColorModes;
        private final SparseArray<DisplayModeRecord> mSupportedModes;

        public LocalDisplayDevice(IBinder displayToken, int builtInDisplayId, SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo, int[] colorModes, int activeColorMode) {
            super(LocalDisplayAdapter.this, displayToken, LocalDisplayAdapter.UNIQUE_ID_PREFIX + builtInDisplayId, builtInDisplayId);
            this.mSupportedModes = new SparseArray<>();
            this.mSupportedColorModes = new ArrayList<>();
            this.mState = 0;
            this.mBrightness = -1;
            this.mBuiltInDisplayId = builtInDisplayId;
            updatePhysicalDisplayInfoLocked(physicalDisplayInfos, activeDisplayInfo, colorModes, activeColorMode);
            updateColorModesLocked(colorModes, activeColorMode);
            this.mSidekickInternal = (SidekickInternal) LocalServices.getService(SidekickInternal.class);
            if (this.mBuiltInDisplayId == 0) {
                LightsManager lights = (LightsManager) LocalServices.getService(LightsManager.class);
                this.mBacklight = lights.getLight(0);
            } else {
                this.mBacklight = null;
            }
            this.mHdrCapabilities = SurfaceControl.getHdrCapabilities(displayToken);
        }

        @Override // com.android.server.display.DisplayDevice
        public boolean hasStableUniqueId() {
            return true;
        }

        public boolean updatePhysicalDisplayInfoLocked(SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo, int[] colorModes, int activeColorMode) {
            this.mDisplayInfos = (SurfaceControl.PhysicalDisplayInfo[]) Arrays.copyOf(physicalDisplayInfos, physicalDisplayInfos.length);
            this.mActivePhysIndex = activeDisplayInfo;
            ArrayList<DisplayModeRecord> records = new ArrayList<>();
            boolean modesAdded = false;
            for (SurfaceControl.PhysicalDisplayInfo info : physicalDisplayInfos) {
                boolean existingMode = false;
                int j = 0;
                while (true) {
                    if (j < records.size()) {
                        if (!records.get(j).hasMatchingMode(info)) {
                            j++;
                        } else {
                            existingMode = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
                if (!existingMode) {
                    DisplayModeRecord record = findDisplayModeRecord(info);
                    if (record == null) {
                        record = new DisplayModeRecord(info);
                        modesAdded = true;
                    }
                    records.add(record);
                }
            }
            DisplayModeRecord activeRecord = null;
            int i = 0;
            while (true) {
                if (i >= records.size()) {
                    break;
                }
                DisplayModeRecord record2 = records.get(i);
                if (!record2.hasMatchingMode(physicalDisplayInfos[activeDisplayInfo])) {
                    i++;
                } else {
                    activeRecord = record2;
                    break;
                }
            }
            int i2 = this.mActiveModeId;
            if (i2 != 0 && this.mActiveModeId != activeRecord.mMode.getModeId()) {
                this.mActiveModeInvalid = true;
                LocalDisplayAdapter.this.sendTraversalRequestLocked();
            }
            boolean recordsChanged = records.size() != this.mSupportedModes.size() || modesAdded;
            if (!recordsChanged) {
                return false;
            }
            this.mHavePendingChanges = true;
            this.mSupportedModes.clear();
            Iterator<DisplayModeRecord> it = records.iterator();
            while (it.hasNext()) {
                DisplayModeRecord record3 = it.next();
                this.mSupportedModes.put(record3.mMode.getModeId(), record3);
            }
            if (findDisplayInfoIndexLocked(this.mDefaultModeId) < 0) {
                if (this.mDefaultModeId != 0) {
                    Slog.w(LocalDisplayAdapter.TAG, "Default display mode no longer available, using currently active mode as default.");
                }
                this.mDefaultModeId = activeRecord.mMode.getModeId();
            }
            if (this.mSupportedModes.indexOfKey(this.mActiveModeId) < 0) {
                if (this.mActiveModeId != 0) {
                    Slog.w(LocalDisplayAdapter.TAG, "Active display mode no longer available, reverting to default mode.");
                }
                this.mActiveModeId = this.mDefaultModeId;
                this.mActiveModeInvalid = true;
            }
            LocalDisplayAdapter.this.sendTraversalRequestLocked();
            return true;
        }

        private boolean updateColorModesLocked(int[] colorModes, int activeColorMode) {
            List<Integer> pendingColorModes = new ArrayList<>();
            if (colorModes == null) {
                return false;
            }
            boolean colorModesAdded = false;
            for (int colorMode : colorModes) {
                if (!this.mSupportedColorModes.contains(Integer.valueOf(colorMode))) {
                    colorModesAdded = true;
                }
                pendingColorModes.add(Integer.valueOf(colorMode));
            }
            boolean colorModesChanged = pendingColorModes.size() != this.mSupportedColorModes.size() || colorModesAdded;
            if (!colorModesChanged) {
                return false;
            }
            this.mHavePendingChanges = true;
            this.mSupportedColorModes.clear();
            this.mSupportedColorModes.addAll(pendingColorModes);
            Collections.sort(this.mSupportedColorModes);
            if (!this.mSupportedColorModes.contains(Integer.valueOf(this.mActiveColorMode))) {
                if (this.mActiveColorMode != 0) {
                    Slog.w(LocalDisplayAdapter.TAG, "Active color mode no longer available, reverting to default mode.");
                    this.mActiveColorMode = 0;
                    this.mActiveColorModeInvalid = true;
                } else if (!this.mSupportedColorModes.isEmpty()) {
                    Slog.e(LocalDisplayAdapter.TAG, "Default and active color mode is no longer available! Reverting to first available mode.");
                    this.mActiveColorMode = this.mSupportedColorModes.get(0).intValue();
                    this.mActiveColorModeInvalid = true;
                } else {
                    Slog.e(LocalDisplayAdapter.TAG, "No color modes available!");
                }
            }
            return true;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.PhysicalDisplayInfo info) {
            for (int i = 0; i < this.mSupportedModes.size(); i++) {
                DisplayModeRecord record = this.mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(info)) {
                    return record;
                }
            }
            return null;
        }

        @Override // com.android.server.display.DisplayDevice
        public void applyPendingDisplayDeviceInfoChangesLocked() {
            if (this.mHavePendingChanges) {
                this.mInfo = null;
                this.mHavePendingChanges = false;
            }
        }

        @Override // com.android.server.display.DisplayDevice
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (this.mInfo == null) {
                SurfaceControl.PhysicalDisplayInfo phys = this.mDisplayInfos[this.mActivePhysIndex];
                this.mInfo = new DisplayDeviceInfo();
                this.mInfo.width = phys.width;
                this.mInfo.height = phys.height;
                this.mInfo.modeId = this.mActiveModeId;
                this.mInfo.defaultModeId = this.mDefaultModeId;
                this.mInfo.supportedModes = new Display.Mode[this.mSupportedModes.size()];
                for (int i = 0; i < this.mSupportedModes.size(); i++) {
                    DisplayModeRecord record = this.mSupportedModes.valueAt(i);
                    this.mInfo.supportedModes[i] = record.mMode;
                }
                this.mInfo.colorMode = this.mActiveColorMode;
                this.mInfo.supportedColorModes = new int[this.mSupportedColorModes.size()];
                for (int i2 = 0; i2 < this.mSupportedColorModes.size(); i2++) {
                    this.mInfo.supportedColorModes[i2] = this.mSupportedColorModes.get(i2).intValue();
                }
                this.mInfo.hdrCapabilities = this.mHdrCapabilities;
                this.mInfo.appVsyncOffsetNanos = phys.appVsyncOffsetNanos;
                this.mInfo.presentationDeadlineNanos = phys.presentationDeadlineNanos;
                this.mInfo.state = this.mState;
                this.mInfo.uniqueId = getUniqueId();
                if (phys.secure) {
                    this.mInfo.flags = 12;
                }
                Resources res = LocalDisplayAdapter.this.getOverlayContext().getResources();
                if (this.mBuiltInDisplayId == 0) {
                    this.mInfo.name = res.getString(17039815);
                    DisplayDeviceInfo displayDeviceInfo = this.mInfo;
                    displayDeviceInfo.flags = 3 | displayDeviceInfo.flags;
                    if (res.getBoolean(17956996) || (Build.IS_EMULATOR && SystemProperties.getBoolean(LocalDisplayAdapter.PROPERTY_EMULATOR_CIRCULAR, false))) {
                        this.mInfo.flags |= 256;
                    }
                    if (res.getBoolean(17956997)) {
                        this.mInfo.flags |= 2048;
                    }
                    this.mInfo.displayCutout = DisplayCutout.fromResourcesRectApproximation(res, this.mInfo.width, this.mInfo.height);
                    this.mInfo.type = 1;
                    this.mInfo.densityDpi = (int) ((phys.density * 160.0f) + 0.5f);
                    this.mInfo.xDpi = phys.xDpi;
                    this.mInfo.yDpi = phys.yDpi;
                    this.mInfo.touch = 1;
                } else if (this.mBuiltInDisplayId >= 1 && this.mBuiltInDisplayId < 5) {
                    this.mInfo.displayCutout = null;
                    this.mInfo.type = 2;
                    this.mInfo.flags |= 64;
                    this.mInfo.name = LocalDisplayAdapter.this.getContext().getResources().getString(17039816);
                    this.mInfo.touch = 2;
                    this.mInfo.setAssumedDensityForExternalDisplay(phys.width, phys.height);
                    if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
                        this.mInfo.rotation = 3;
                    }
                    if (SystemProperties.getBoolean("persist.demo.hdmirotates", false)) {
                        this.mInfo.flags |= 2;
                    }
                    if (!res.getBoolean(17956992)) {
                        this.mInfo.flags |= 128;
                    }
                    if (res.getBoolean(17956993)) {
                        this.mInfo.flags |= 16;
                    }
                } else {
                    this.mInfo.type = 1;
                    this.mInfo.touch = 1;
                    this.mInfo.name = LocalDisplayAdapter.this.getContext().getResources().getString(17039815);
                    this.mInfo.flags |= 2;
                    if (SystemProperties.getBoolean("vendor.display.builtin_presentation", false)) {
                        this.mInfo.flags |= 64;
                    } else {
                        this.mInfo.flags |= 16;
                    }
                    if (!SystemProperties.getBoolean("vendor.display.builtin_mirroring", false)) {
                        this.mInfo.flags |= 128;
                    }
                    this.mInfo.setAssumedDensityForExternalDisplay(phys.width, phys.height);
                }
            }
            return this.mInfo;
        }

        @Override // com.android.server.display.DisplayDevice
        public Runnable requestDisplayStateLocked(final int state, final int brightness) {
            boolean stateChanged = this.mState != state;
            final boolean brightnessChanged = (this.mBrightness == brightness || this.mBacklight == null) ? false : true;
            if (stateChanged || brightnessChanged) {
                final int displayId = this.mBuiltInDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = this.mState;
                if (stateChanged) {
                    if (LocalDisplayAdapter.this.isStateHandle()) {
                        try {
                            Slog.i(LocalDisplayAdapter.TAG, "sync root wait");
                            LocalDisplayAdapter.this.setSyncWait(true);
                            LocalDisplayAdapter.this.getSyncRoot().wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        LocalDisplayAdapter.this.setSyncWait(false);
                    }
                    LocalDisplayAdapter.this.setStateHandle(true);
                    Slog.i(LocalDisplayAdapter.TAG, "mState: " + this.mState + ", state: " + state);
                    this.mState = state;
                    updateDeviceInfoLocked();
                    SystemProperties.set("sys.xiaopeng.blackScreen_mode", "0");
                }
                if (brightnessChanged) {
                    this.mBrightness = brightness;
                }
                return new Runnable() { // from class: com.android.server.display.LocalDisplayAdapter.LocalDisplayDevice.1
                    @Override // java.lang.Runnable
                    public void run() {
                        int currentState = oldState;
                        if (Display.isSuspendedState(oldState) || oldState == 0) {
                            if (!Display.isSuspendedState(state)) {
                                if (displayId == 0 && !Build.IS_USER) {
                                    ResumeProfEvent.addResumeProfEvent("LocalDisplayAdapter: setDisplayState=ON");
                                }
                                setDisplayState(state);
                                currentState = state;
                            } else if (state == 4 || oldState == 4) {
                                setDisplayState(3);
                                currentState = 3;
                            } else if (state == 6 || oldState == 6) {
                                setDisplayState(2);
                                currentState = 2;
                            } else {
                                return;
                            }
                        }
                        boolean vrModeChange = false;
                        if ((state == 5 || currentState == 5) && currentState != state) {
                            setVrMode(state == 5);
                            vrModeChange = true;
                        }
                        if (brightnessChanged || vrModeChange) {
                            setDisplayBrightness(brightness);
                        }
                        if (state != currentState) {
                            setDisplayState(state);
                        }
                    }

                    private void setVrMode(boolean isVrEnabled) {
                        LocalDisplayDevice.this.mBacklight.setVrMode(isVrEnabled);
                    }

                    private void setDisplayState(int state2) {
                        Slog.i(LocalDisplayAdapter.TAG, "setDisplayState(id=" + displayId + ", state=" + Display.stateToString(state2) + ")");
                        if (LocalDisplayDevice.this.mSidekickActive) {
                            Trace.traceBegin(131072L, "SidekickInternal#endDisplayControl");
                            try {
                                LocalDisplayDevice.this.mSidekickInternal.endDisplayControl();
                                Trace.traceEnd(131072L);
                                LocalDisplayDevice.this.mSidekickActive = false;
                            } finally {
                            }
                        }
                        int mode = LocalDisplayAdapter.getPowerModeForState(state2);
                        Trace.traceBegin(131072L, "setDisplayState(id=" + displayId + ", state=" + Display.stateToString(state2) + ")");
                        try {
                            SurfaceControl.setDisplayPowerMode(token, mode);
                            if (displayId == 0 && !Build.IS_USER) {
                                ResumeProfEvent.addResumeProfEvent("LocalDisplayAdapter: setDisplayPowerMode DONE");
                            }
                            Trace.traceCounter(131072L, "DisplayPowerMode", mode);
                            Trace.traceEnd(131072L);
                            if (Display.isSuspendedState(state2) && state2 != 1 && LocalDisplayDevice.this.mSidekickInternal != null && !LocalDisplayDevice.this.mSidekickActive) {
                                Trace.traceBegin(131072L, "SidekickInternal#startDisplayControl");
                                try {
                                    LocalDisplayDevice.this.mSidekickActive = LocalDisplayDevice.this.mSidekickInternal.startDisplayControl(state2);
                                } finally {
                                }
                            }
                        } finally {
                        }
                    }

                    private void setDisplayBrightness(int brightness2) {
                        Trace.traceBegin(131072L, "setDisplayBrightness(id=" + displayId + ", brightness=" + brightness2 + ")");
                        try {
                            int brightness3 = LocalDisplayAdapter.getOverrideBrightness(brightness2);
                            if (!"1".equals(SystemProperties.get("sys.xiaopeng.blackScreen_mode", "0"))) {
                                LocalDisplayDevice.this.mBacklight.setBrightness(brightness3);
                                Trace.traceCounter(131072L, "ScreenBrightness", brightness3);
                            }
                        } finally {
                            Trace.traceEnd(131072L);
                        }
                    }
                };
            }
            return null;
        }

        @Override // com.android.server.display.DisplayDevice
        public void requestDisplayModesLocked(int colorMode, int modeId) {
            if (requestModeLocked(modeId) || requestColorModeLocked(colorMode)) {
                updateDeviceInfoLocked();
            }
        }

        @Override // com.android.server.display.DisplayDevice
        public void onOverlayChangedLocked() {
            updateDeviceInfoLocked();
        }

        public boolean requestModeLocked(int modeId) {
            if (modeId == 0) {
                modeId = this.mDefaultModeId;
            } else if (this.mSupportedModes.indexOfKey(modeId) < 0) {
                Slog.w(LocalDisplayAdapter.TAG, "Requested mode " + modeId + " is not supported by this display, reverting to default display mode.");
                modeId = this.mDefaultModeId;
            }
            int physIndex = findDisplayInfoIndexLocked(modeId);
            if (physIndex < 0) {
                Slog.w(LocalDisplayAdapter.TAG, "Requested mode ID " + modeId + " not available, trying with default mode ID");
                modeId = this.mDefaultModeId;
                physIndex = findDisplayInfoIndexLocked(modeId);
            }
            if (this.mActivePhysIndex == physIndex) {
                return false;
            }
            SurfaceControl.setActiveConfig(getDisplayTokenLocked(), physIndex);
            this.mActivePhysIndex = physIndex;
            this.mActiveModeId = modeId;
            this.mActiveModeInvalid = false;
            return true;
        }

        public boolean requestColorModeLocked(int colorMode) {
            if (this.mActiveColorMode == colorMode) {
                return false;
            }
            if (!this.mSupportedColorModes.contains(Integer.valueOf(colorMode))) {
                Slog.w(LocalDisplayAdapter.TAG, "Unable to find color mode " + colorMode + ", ignoring request.");
                return false;
            }
            SurfaceControl.setActiveColorMode(getDisplayTokenLocked(), colorMode);
            this.mActiveColorMode = colorMode;
            this.mActiveColorModeInvalid = false;
            return true;
        }

        @Override // com.android.server.display.DisplayDevice
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mBuiltInDisplayId=" + this.mBuiltInDisplayId);
            pw.println("mActivePhysIndex=" + this.mActivePhysIndex);
            pw.println("mActiveModeId=" + this.mActiveModeId);
            pw.println("mActiveColorMode=" + this.mActiveColorMode);
            pw.println("mState=" + Display.stateToString(this.mState));
            pw.println("mBrightness=" + this.mBrightness);
            pw.println("mBacklight=" + this.mBacklight);
            pw.println("mDisplayInfos=");
            for (int i = 0; i < this.mDisplayInfos.length; i++) {
                pw.println("  " + this.mDisplayInfos[i]);
            }
            pw.println("mSupportedModes=");
            for (int i2 = 0; i2 < this.mSupportedModes.size(); i2++) {
                pw.println("  " + this.mSupportedModes.valueAt(i2));
            }
            pw.print("mSupportedColorModes=[");
            for (int i3 = 0; i3 < this.mSupportedColorModes.size(); i3++) {
                if (i3 != 0) {
                    pw.print(", ");
                }
                pw.print(this.mSupportedColorModes.get(i3));
            }
            pw.println("]");
        }

        private int findDisplayInfoIndexLocked(int modeId) {
            DisplayModeRecord record = this.mSupportedModes.get(modeId);
            if (record != null) {
                for (int i = 0; i < this.mDisplayInfos.length; i++) {
                    SurfaceControl.PhysicalDisplayInfo info = this.mDisplayInfos[i];
                    if (record.hasMatchingMode(info)) {
                        return i;
                    }
                }
                return -1;
            }
            return -1;
        }

        private void updateDeviceInfoLocked() {
            this.mInfo = null;
            LocalDisplayAdapter.this.sendDisplayDeviceEventLocked(this, 2);
        }
    }

    Context getOverlayContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DisplayModeRecord {
        public final Display.Mode mMode;

        public DisplayModeRecord(SurfaceControl.PhysicalDisplayInfo phys) {
            this.mMode = DisplayAdapter.createMode(phys.width, phys.height, phys.refreshRate);
        }

        public boolean hasMatchingMode(SurfaceControl.PhysicalDisplayInfo info) {
            int modeRefreshRate = Float.floatToIntBits(this.mMode.getRefreshRate());
            int displayInfoRefreshRate = Float.floatToIntBits(info.refreshRate);
            return this.mMode.getPhysicalWidth() == info.width && this.mMode.getPhysicalHeight() == info.height && modeRefreshRate == displayInfoRefreshRate;
        }

        public String toString() {
            return "DisplayModeRecord{mMode=" + this.mMode + "}";
        }
    }

    /* loaded from: classes.dex */
    private final class HotplugDisplayEventReceiver extends DisplayEventReceiver {
        public HotplugDisplayEventReceiver(Looper looper) {
            super(looper, 0);
        }

        public void onHotplug(long timestampNanos, int builtInDisplayId, boolean connected) {
            synchronized (LocalDisplayAdapter.this.getSyncRoot()) {
                try {
                    if (connected) {
                        LocalDisplayAdapter.this.tryConnectDisplayLocked(builtInDisplayId);
                    } else {
                        LocalDisplayAdapter.this.tryDisconnectDisplayLocked(builtInDisplayId);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int getOverrideBrightness(int brightness) {
        return DisplayManager.getOverrideBrightness(brightness);
    }
}
