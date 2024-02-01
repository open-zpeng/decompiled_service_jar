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
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.SurfaceControl;
import com.android.server.LocalServices;
import com.android.server.display.DisplayAdapter;
import com.android.server.display.DisplayManagerService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class LocalDisplayAdapter extends DisplayAdapter {
    private static final boolean DEBUG = false;
    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";
    private static final String TAG = "LocalDisplayAdapter";
    private static final String UNIQUE_ID_PREFIX = "local:";
    private final LongSparseArray<LocalDisplayDevice> mDevices;
    private PhysicalDisplayEventReceiver mPhysicalDisplayEventReceiver;
    private final SparseLongArray mTypePhysicIds;
    private final XpDisplayIntercept mXpDisplayIntercept;

    public LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot, Context context, Handler handler, DisplayAdapter.Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
        this.mDevices = new LongSparseArray<>();
        this.mTypePhysicIds = new SparseLongArray();
        if (PowerManagerInternal.IS_E38) {
            this.mXpDisplayIntercept = new XpDisplayIntercept();
        } else {
            this.mXpDisplayIntercept = null;
        }
    }

    @Override // com.android.server.display.DisplayAdapter
    public void registerLocked() {
        long[] physicalDisplayIds;
        super.registerLocked();
        this.mPhysicalDisplayEventReceiver = new PhysicalDisplayEventReceiver(getHandler().getLooper());
        for (long physicalDisplayId : SurfaceControl.getPhysicalDisplayIds()) {
            tryConnectDisplayLocked(physicalDisplayId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryConnectDisplayLocked(long physicalDisplayId) {
        int activeColorMode;
        IBinder displayToken = SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
        if (displayToken != null) {
            SurfaceControl.PhysicalDisplayInfo[] configs = SurfaceControl.getDisplayConfigs(displayToken);
            if (configs == null) {
                Slog.w(TAG, "No valid configs found for display device " + physicalDisplayId);
                return;
            }
            int activeConfig = SurfaceControl.getActiveConfig(displayToken);
            if (activeConfig < 0) {
                Slog.w(TAG, "No active config found for display device " + physicalDisplayId);
                return;
            }
            int activeColorMode2 = SurfaceControl.getActiveColorMode(displayToken);
            if (activeColorMode2 >= 0) {
                activeColorMode = activeColorMode2;
            } else {
                Slog.w(TAG, "Unable to get active color mode for display device " + physicalDisplayId);
                activeColorMode = -1;
            }
            int[] colorModes = SurfaceControl.getDisplayColorModes(displayToken);
            int[] allowedConfigs = SurfaceControl.getAllowedDisplayConfigs(displayToken);
            if (allowedConfigs == null) {
                Slog.w(TAG, "No allowed configs found for display device " + physicalDisplayId);
                return;
            }
            LocalDisplayDevice device = this.mDevices.get(physicalDisplayId);
            int i = 2;
            if (device == null) {
                boolean isInternal = this.mDevices.size() == 0;
                if (isInternal) {
                    i = 1;
                } else if (this.mDevices.size() == 1) {
                    i = 6;
                }
                int type = i;
                LocalDisplayDevice device2 = new LocalDisplayDevice(displayToken, physicalDisplayId, configs, activeConfig, allowedConfigs, colorModes, activeColorMode, isInternal, type);
                this.mDevices.put(physicalDisplayId, device2);
                this.mTypePhysicIds.put(type, physicalDisplayId);
                sendDisplayDeviceEventLocked(device2, 1);
            } else if (device.updatePhysicalDisplayInfoLocked(configs, activeConfig, allowedConfigs, colorModes, activeColorMode)) {
                sendDisplayDeviceEventLocked(device, 2);
            }
        }
    }

    public DisplayDevice getDisplayDevice(int typeId) {
        return this.mDevices.get(this.mTypePhysicIds.get(typeId));
    }

    public List<Long> getPhysicDisplayIds() {
        List<Long> physicIds = new ArrayList<>();
        synchronized (getSyncRoot()) {
            int size = this.mDevices.size();
            for (int i = 0; i < size; i++) {
                physicIds.add(Long.valueOf(this.mDevices.keyAt(i)));
            }
        }
        return physicIds;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryDisconnectDisplayLocked(long physicalDisplayId) {
        LocalDisplayDevice device = this.mDevices.get(physicalDisplayId);
        if (device != null) {
            this.mDevices.remove(physicalDisplayId);
            sendDisplayDeviceEventLocked(device, 3);
        }
    }

    static int getPowerModeForState(int state) {
        if (state != 1) {
            if (state != 6) {
                if (state != 3) {
                    return state != 4 ? 2 : 3;
                }
                return 1;
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
        private int[] mAllowedModeIds;
        private boolean mAllowedModeIdsInvalid;
        private int[] mAllowedPhysIndexes;
        private final Light mBacklight;
        private int mBrightness;
        private int mDefaultModeId;
        private SurfaceControl.PhysicalDisplayInfo[] mDisplayInfos;
        private final int mDisplayType;
        private boolean mHavePendingChanges;
        private Display.HdrCapabilities mHdrCapabilities;
        private DisplayDeviceInfo mInfo;
        private final boolean mIsInternal;
        private final long mPhysicalDisplayId;
        private final PowerManagerInternal mPowerInternal;
        private boolean mSidekickActive;
        private SidekickInternal mSidekickInternal;
        private int mState;
        private final ArrayList<Integer> mSupportedColorModes;
        private final SparseArray<DisplayModeRecord> mSupportedModes;

        LocalDisplayDevice(IBinder displayToken, long physicalDisplayId, SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo, int[] allowedDisplayInfos, int[] colorModes, int activeColorMode, boolean isInternal, int displayType) {
            super(LocalDisplayAdapter.this, displayToken, LocalDisplayAdapter.UNIQUE_ID_PREFIX + physicalDisplayId);
            this.mSupportedModes = new SparseArray<>();
            this.mSupportedColorModes = new ArrayList<>();
            this.mState = 0;
            this.mBrightness = -1;
            if (PowerManagerInternal.IS_E38) {
                LocalDisplayAdapter.this.mXpDisplayIntercept.addDisplayToken(displayType, displayToken);
            }
            this.mPhysicalDisplayId = physicalDisplayId;
            this.mIsInternal = isInternal;
            this.mDisplayType = displayType;
            updatePhysicalDisplayInfoLocked(physicalDisplayInfos, activeDisplayInfo, allowedDisplayInfos, colorModes, activeColorMode);
            updateColorModesLocked(colorModes, activeColorMode);
            this.mSidekickInternal = (SidekickInternal) LocalServices.getService(SidekickInternal.class);
            if (!this.mIsInternal) {
                this.mBacklight = null;
            } else {
                LightsManager lights = (LightsManager) LocalServices.getService(LightsManager.class);
                this.mBacklight = lights.getLight(0);
            }
            this.mPowerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
            this.mHdrCapabilities = SurfaceControl.getHdrCapabilities(displayToken);
        }

        @Override // com.android.server.display.DisplayDevice
        public boolean hasStableUniqueId() {
            return true;
        }

        public boolean updatePhysicalDisplayInfoLocked(SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo, int[] allowedDisplayInfos, int[] colorModes, int activeColorMode) {
            this.mDisplayInfos = (SurfaceControl.PhysicalDisplayInfo[]) Arrays.copyOf(physicalDisplayInfos, physicalDisplayInfos.length);
            this.mActivePhysIndex = activeDisplayInfo;
            this.mAllowedPhysIndexes = Arrays.copyOf(allowedDisplayInfos, allowedDisplayInfos.length);
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
            if (i2 != 0 && i2 != activeRecord.mMode.getModeId()) {
                this.mActiveModeInvalid = true;
                LocalDisplayAdapter.this.sendTraversalRequestLocked();
            }
            boolean recordsChanged = records.size() != this.mSupportedModes.size() || modesAdded;
            if (recordsChanged) {
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
                this.mAllowedModeIds = new int[]{this.mActiveModeId};
                int[] iArr = this.mAllowedPhysIndexes;
                int[] allowedModeIds = new int[iArr.length];
                int size = 0;
                for (int physIndex : iArr) {
                    int modeId = findMatchingModeIdLocked(physIndex);
                    if (modeId > 0) {
                        allowedModeIds[size] = modeId;
                        size++;
                    }
                }
                this.mAllowedModeIdsInvalid = !Arrays.equals(allowedModeIds, this.mAllowedModeIds);
                LocalDisplayAdapter.this.sendTraversalRequestLocked();
                return true;
            }
            return false;
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
                DisplayDeviceInfo displayDeviceInfo = this.mInfo;
                displayDeviceInfo.modeId = this.mActiveModeId;
                displayDeviceInfo.defaultModeId = this.mDefaultModeId;
                displayDeviceInfo.supportedModes = getDisplayModes(this.mSupportedModes);
                DisplayDeviceInfo displayDeviceInfo2 = this.mInfo;
                displayDeviceInfo2.colorMode = this.mActiveColorMode;
                displayDeviceInfo2.supportedColorModes = new int[this.mSupportedColorModes.size()];
                for (int i = 0; i < this.mSupportedColorModes.size(); i++) {
                    this.mInfo.supportedColorModes[i] = this.mSupportedColorModes.get(i).intValue();
                }
                DisplayDeviceInfo displayDeviceInfo3 = this.mInfo;
                displayDeviceInfo3.hdrCapabilities = this.mHdrCapabilities;
                displayDeviceInfo3.appVsyncOffsetNanos = phys.appVsyncOffsetNanos;
                this.mInfo.presentationDeadlineNanos = phys.presentationDeadlineNanos;
                DisplayDeviceInfo displayDeviceInfo4 = this.mInfo;
                displayDeviceInfo4.state = this.mState;
                displayDeviceInfo4.uniqueId = getUniqueId();
                DisplayAddress.Physical physicalAddress = DisplayAddress.fromPhysicalDisplayId(this.mPhysicalDisplayId);
                this.mInfo.address = physicalAddress;
                if (phys.secure) {
                    this.mInfo.flags = 12;
                }
                Resources res = LocalDisplayAdapter.this.getOverlayContext().getResources();
                if (this.mIsInternal) {
                    this.mInfo.name = res.getString(17039878);
                    DisplayDeviceInfo displayDeviceInfo5 = this.mInfo;
                    displayDeviceInfo5.flags = 3 | displayDeviceInfo5.flags;
                    if (res.getBoolean(17891481) || (Build.IS_EMULATOR && SystemProperties.getBoolean(LocalDisplayAdapter.PROPERTY_EMULATOR_CIRCULAR, false))) {
                        this.mInfo.flags |= 256;
                    }
                    if (res.getBoolean(17891482)) {
                        this.mInfo.flags |= 2048;
                    }
                    DisplayDeviceInfo displayDeviceInfo6 = this.mInfo;
                    displayDeviceInfo6.displayCutout = DisplayCutout.fromResourcesRectApproximation(res, displayDeviceInfo6.width, this.mInfo.height);
                    DisplayDeviceInfo displayDeviceInfo7 = this.mInfo;
                    displayDeviceInfo7.type = 1;
                    displayDeviceInfo7.densityDpi = (int) ((phys.density * 160.0f) + 0.5f);
                    this.mInfo.xDpi = phys.xDpi;
                    this.mInfo.yDpi = phys.yDpi;
                    this.mInfo.touch = 1;
                } else {
                    DisplayDeviceInfo displayDeviceInfo8 = this.mInfo;
                    displayDeviceInfo8.displayCutout = null;
                    displayDeviceInfo8.type = this.mDisplayType;
                    displayDeviceInfo8.flags |= 64;
                    this.mInfo.name = LocalDisplayAdapter.this.getContext().getResources().getString(17039879);
                    DisplayDeviceInfo displayDeviceInfo9 = this.mInfo;
                    displayDeviceInfo9.touch = 2;
                    displayDeviceInfo9.setAssumedDensityForExternalDisplay(phys.width, phys.height);
                    if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
                        this.mInfo.rotation = 3;
                    }
                    if (SystemProperties.getBoolean("persist.demo.hdmirotates", false)) {
                        this.mInfo.flags |= 2;
                    }
                    if (!res.getBoolean(17891477)) {
                        Slog.i(LocalDisplayAdapter.TAG, "set own content only");
                        this.mInfo.flags |= 128;
                    } else {
                        Slog.i(LocalDisplayAdapter.TAG, "set mirror content");
                    }
                    if (isDisplayPrivate(physicalAddress)) {
                        this.mInfo.flags |= 16;
                    }
                }
            }
            return this.mInfo;
        }

        @Override // com.android.server.display.DisplayDevice
        public Runnable requestDisplayStateLocked(final int state, final int brightness) {
            boolean stateChanged = this.mState != state;
            final boolean brightnessChanged = (this.mBrightness == brightness || this.mBacklight == null) ? false : true;
            if (stateChanged || brightnessChanged) {
                final long physicalDisplayId = this.mPhysicalDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = this.mState;
                if (stateChanged) {
                    if (this.mState != 0) {
                        if (LocalDisplayAdapter.this.isStateHandle()) {
                            try {
                                Slog.i(LocalDisplayAdapter.TAG, "sync root wait displayId: " + physicalDisplayId);
                                LocalDisplayAdapter.this.setSyncWait(true);
                                LocalDisplayAdapter.this.getSyncRoot().wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            LocalDisplayAdapter.this.setSyncWait(false);
                        }
                        LocalDisplayAdapter.this.setStateHandle(true);
                    }
                    Slog.i(LocalDisplayAdapter.TAG, "physicalDisplayId=" + physicalDisplayId + ", mState=" + Display.stateToString(this.mState) + ", state=" + Display.stateToString(state));
                    this.mState = state;
                    updateDeviceInfoLocked();
                }
                if (brightnessChanged) {
                    this.mBrightness = brightness;
                }
                return new Runnable() { // from class: com.android.server.display.LocalDisplayAdapter.LocalDisplayDevice.1
                    @Override // java.lang.Runnable
                    public void run() {
                        int i;
                        int i2;
                        int currentState = oldState;
                        if (Display.isSuspendedState(oldState) || oldState == 0) {
                            if (!Display.isSuspendedState(state)) {
                                setDisplayState(state);
                                currentState = state;
                            } else {
                                int i3 = state;
                                if (i3 == 4 || (i = oldState) == 4) {
                                    setDisplayState(3);
                                    currentState = 3;
                                } else if (i3 == 6 || i == 6) {
                                    setDisplayState(2);
                                    currentState = 2;
                                } else {
                                    return;
                                }
                            }
                        }
                        boolean vrModeChange = false;
                        if ((state == 5 || currentState == 5) && currentState != (i2 = state)) {
                            setVrMode(i2 == 5);
                            vrModeChange = true;
                        }
                        if (brightnessChanged || vrModeChange) {
                            setDisplayBrightness(brightness);
                        }
                        int i4 = state;
                        if (i4 != currentState) {
                            setDisplayState(i4);
                        }
                    }

                    private void setVrMode(boolean isVrEnabled) {
                        LocalDisplayDevice.this.mBacklight.setVrMode(isVrEnabled);
                    }

                    private void setDisplayState(int state2) {
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
                        Trace.traceBegin(131072L, "setDisplayState(id=" + physicalDisplayId + ", state=" + Display.stateToString(state2) + ")");
                        try {
                            SurfaceControl.setDisplayPowerMode(token, mode);
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
                        Trace.traceBegin(131072L, "setDisplayBrightness(id=" + physicalDisplayId + ", brightness=" + brightness2 + ")");
                        try {
                            int brightness3 = LocalDisplayAdapter.getOverrideBrightness(brightness2);
                            LocalDisplayDevice.this.mBacklight.setBrightness(brightness3);
                            Trace.traceCounter(131072L, "ScreenBrightness", brightness3);
                        } finally {
                            Trace.traceEnd(131072L);
                        }
                    }
                };
            }
            return null;
        }

        @Override // com.android.server.display.DisplayDevice
        public void setRequestedColorModeLocked(int colorMode) {
            if (requestColorModeLocked(colorMode)) {
                updateDeviceInfoLocked();
            }
        }

        @Override // com.android.server.display.DisplayDevice
        public void setAllowedDisplayModesLocked(int[] modes) {
            updateAllowedModesLocked(modes);
        }

        @Override // com.android.server.display.DisplayDevice
        public void onOverlayChangedLocked() {
            updateDeviceInfoLocked();
        }

        public void onActivePhysicalDisplayModeChangedLocked(int physIndex) {
            if (updateActiveModeLocked(physIndex)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateActiveModeLocked(int activePhysIndex) {
            if (this.mActivePhysIndex == activePhysIndex || activePhysIndex < 0) {
                if (activePhysIndex < 0) {
                    Slog.w(LocalDisplayAdapter.TAG, "No activePhysIndex found for display device: " + activePhysIndex);
                }
                return false;
            }
            this.mActivePhysIndex = activePhysIndex;
            this.mActiveModeId = findMatchingModeIdLocked(activePhysIndex);
            this.mActiveModeInvalid = this.mActiveModeId == 0;
            if (this.mActiveModeInvalid) {
                Slog.w(LocalDisplayAdapter.TAG, "In unknown mode after setting allowed configs: allowedPhysIndexes=" + this.mAllowedPhysIndexes + ", activePhysIndex=" + this.mActivePhysIndex);
            }
            return true;
        }

        public void updateAllowedModesLocked(int[] allowedModes) {
            if ((!Arrays.equals(allowedModes, this.mAllowedModeIds) || this.mAllowedModeIdsInvalid) && updateAllowedModesInternalLocked(allowedModes)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateAllowedModesInternalLocked(int[] allowedModes) {
            int[] allowedPhysIndexes = new int[allowedModes.length];
            int size = 0;
            for (int modeId : allowedModes) {
                int physIndex = findDisplayInfoIndexLocked(modeId);
                if (physIndex < 0) {
                    Slog.w(LocalDisplayAdapter.TAG, "Requested mode ID " + modeId + " not available, dropping from allowed set.");
                } else {
                    allowedPhysIndexes[size] = physIndex;
                    size++;
                }
            }
            if (size != allowedModes.length) {
                allowedPhysIndexes = Arrays.copyOf(allowedPhysIndexes, size);
            }
            if (size == 0) {
                int i = this.mDefaultModeId;
                allowedModes = new int[]{i};
                allowedPhysIndexes = new int[]{findDisplayInfoIndexLocked(i)};
            }
            this.mAllowedModeIds = allowedModes;
            this.mAllowedModeIdsInvalid = false;
            if (Arrays.equals(this.mAllowedPhysIndexes, allowedPhysIndexes)) {
                return false;
            }
            this.mAllowedPhysIndexes = allowedPhysIndexes;
            SurfaceControl.setAllowedDisplayConfigs(getDisplayTokenLocked(), allowedPhysIndexes);
            int activePhysIndex = SurfaceControl.getActiveConfig(getDisplayTokenLocked());
            return updateActiveModeLocked(activePhysIndex);
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
            pw.println("mPhysicalDisplayId=" + this.mPhysicalDisplayId);
            pw.println("mAllowedPhysIndexes=" + Arrays.toString(this.mAllowedPhysIndexes));
            pw.println("mAllowedModeIds=" + Arrays.toString(this.mAllowedModeIds));
            pw.println("mAllowedModeIdsInvalid=" + this.mAllowedModeIdsInvalid);
            pw.println("mActivePhysIndex=" + this.mActivePhysIndex);
            pw.println("mActiveModeId=" + this.mActiveModeId);
            pw.println("mActiveColorMode=" + this.mActiveColorMode);
            pw.println("mDefaultModeId=" + this.mDefaultModeId);
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
                int i = 0;
                while (true) {
                    SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfoArr = this.mDisplayInfos;
                    if (i < physicalDisplayInfoArr.length) {
                        SurfaceControl.PhysicalDisplayInfo info = physicalDisplayInfoArr[i];
                        if (!record.hasMatchingMode(info)) {
                            i++;
                        } else {
                            return i;
                        }
                    } else {
                        return -1;
                    }
                }
            } else {
                return -1;
            }
        }

        private int findMatchingModeIdLocked(int physIndex) {
            SurfaceControl.PhysicalDisplayInfo info = this.mDisplayInfos[physIndex];
            for (int i = 0; i < this.mSupportedModes.size(); i++) {
                DisplayModeRecord record = this.mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(info)) {
                    return record.mMode.getModeId();
                }
            }
            return 0;
        }

        private void updateDeviceInfoLocked() {
            this.mInfo = null;
            LocalDisplayAdapter.this.sendDisplayDeviceEventLocked(this, 2);
        }

        private Display.Mode[] getDisplayModes(SparseArray<DisplayModeRecord> records) {
            int size = records.size();
            Display.Mode[] modes = new Display.Mode[size];
            for (int i = 0; i < size; i++) {
                DisplayModeRecord record = records.valueAt(i);
                modes[i] = record.mMode;
            }
            return modes;
        }

        private boolean isDisplayPrivate(DisplayAddress.Physical physicalAddress) {
            if (physicalAddress == null) {
                return false;
            }
            Resources res = LocalDisplayAdapter.this.getOverlayContext().getResources();
            int[] ports = res.getIntArray(17236041);
            if (ports != null) {
                int port = physicalAddress.getPort();
                for (int p : ports) {
                    if (p == port) {
                        return true;
                    }
                }
            }
            return false;
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
    private final class PhysicalDisplayEventReceiver extends DisplayEventReceiver {
        PhysicalDisplayEventReceiver(Looper looper) {
            super(looper, 0, 1);
        }

        public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
            synchronized (LocalDisplayAdapter.this.getSyncRoot()) {
                if (connected) {
                    LocalDisplayAdapter.this.tryConnectDisplayLocked(physicalDisplayId);
                } else {
                    LocalDisplayAdapter.this.tryDisconnectDisplayLocked(physicalDisplayId);
                }
            }
        }

        public void onConfigChanged(long timestampNanos, long physicalDisplayId, int physIndex) {
            synchronized (LocalDisplayAdapter.this.getSyncRoot()) {
                LocalDisplayDevice device = (LocalDisplayDevice) LocalDisplayAdapter.this.mDevices.get(physicalDisplayId);
                if (device == null) {
                    return;
                }
                device.onActivePhysicalDisplayModeChangedLocked(physIndex);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int getOverrideBrightness(int brightness) {
        return DisplayManager.getOverrideBrightness(brightness);
    }
}
