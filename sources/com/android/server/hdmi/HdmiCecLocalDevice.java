package com.android.server.hdmi;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.KeyEvent;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.Constants;
import com.android.server.hdmi.HdmiAnnotations;
import com.android.server.hdmi.HdmiControlService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public abstract class HdmiCecLocalDevice {
    private static final int DEVICE_CLEANUP_TIMEOUT = 5000;
    private static final int FOLLOWER_SAFETY_TIMEOUT = 550;
    private static final int MSG_DISABLE_DEVICE_TIMEOUT = 1;
    private static final int MSG_USER_CONTROL_RELEASE_TIMEOUT = 2;
    private static final String TAG = "HdmiCecLocalDevice";
    @GuardedBy({"mLock"})
    private int mActiveRoutingPath;
    @GuardedBy({"mLock"})
    protected HdmiDeviceInfo mDeviceInfo;
    protected final int mDeviceType;
    protected final Object mLock;
    protected PendingActionClearedCallback mPendingActionClearedCallback;
    protected int mPreferredAddress;
    protected final HdmiControlService mService;
    protected int mLastKeycode = -1;
    protected int mLastKeyRepeatCount = 0;
    protected final HdmiCecMessageCache mCecMessageCache = new HdmiCecMessageCache();
    private final ArrayList<HdmiCecFeatureAction> mActions = new ArrayList<>();
    private final Handler mHandler = new Handler() { // from class: com.android.server.hdmi.HdmiCecLocalDevice.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                HdmiCecLocalDevice.this.handleDisableDeviceTimeout();
            } else if (i == 2) {
                HdmiCecLocalDevice.this.handleUserControlReleased();
            }
        }
    };
    protected int mAddress = 15;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface PendingActionClearedCallback {
        void onCleared(HdmiCecLocalDevice hdmiCecLocalDevice);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public abstract int getPreferredAddress();

    protected abstract void onAddressAllocated(int i, int i2);

    protected abstract void setPreferredAddress(int i);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ActiveSource {
        int logicalAddress;
        int physicalAddress;

        public ActiveSource() {
            invalidate();
        }

        public ActiveSource(int logical, int physical) {
            this.logicalAddress = logical;
            this.physicalAddress = physical;
        }

        public static ActiveSource of(ActiveSource source) {
            return new ActiveSource(source.logicalAddress, source.physicalAddress);
        }

        public static ActiveSource of(int logical, int physical) {
            return new ActiveSource(logical, physical);
        }

        public boolean isValid() {
            return HdmiUtils.isValidAddress(this.logicalAddress);
        }

        public void invalidate() {
            this.logicalAddress = -1;
            this.physicalAddress = 65535;
        }

        public boolean equals(int logical, int physical) {
            return this.logicalAddress == logical && this.physicalAddress == physical;
        }

        public boolean equals(Object obj) {
            if (obj instanceof ActiveSource) {
                ActiveSource that = (ActiveSource) obj;
                return that.logicalAddress == this.logicalAddress && that.physicalAddress == this.physicalAddress;
            }
            return false;
        }

        public int hashCode() {
            return (this.logicalAddress * 29) + this.physicalAddress;
        }

        public String toString() {
            StringBuffer s = new StringBuffer();
            int i = this.logicalAddress;
            String logicalAddressString = i == -1 ? "invalid" : String.format("0x%02x", Integer.valueOf(i));
            s.append("(");
            s.append(logicalAddressString);
            int i2 = this.physicalAddress;
            String physicalAddressString = i2 != 65535 ? String.format("0x%04x", Integer.valueOf(i2)) : "invalid";
            s.append(", ");
            s.append(physicalAddressString);
            s.append(")");
            return s.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public HdmiCecLocalDevice(HdmiControlService service, int deviceType) {
        this.mService = service;
        this.mDeviceType = deviceType;
        this.mLock = service.getServiceLock();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static HdmiCecLocalDevice create(HdmiControlService service, int deviceType) {
        if (deviceType != 0) {
            if (deviceType != 4) {
                if (deviceType == 5) {
                    return new HdmiCecLocalDeviceAudioSystem(service);
                }
                return null;
            }
            return new HdmiCecLocalDevicePlayback(service);
        }
        return new HdmiCecLocalDeviceTv(service);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void init() {
        assertRunOnServiceThread();
        this.mPreferredAddress = getPreferredAddress();
        this.mPendingActionClearedCallback = null;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isInputReady(int deviceId) {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean canGoToStandby() {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public boolean dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int dest = message.getDestination();
        if (dest != this.mAddress && dest != 15) {
            return false;
        }
        this.mCecMessageCache.cacheMessage(message);
        return onMessage(message);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @HdmiAnnotations.ServiceThreadOnly
    public final boolean onMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (dispatchMessageToAction(message)) {
            return true;
        }
        int opcode = message.getOpcode();
        if (opcode != 4) {
            if (opcode != 10) {
                if (opcode != 13) {
                    if (opcode != 15) {
                        if (opcode != 50) {
                            if (opcode != 122) {
                                if (opcode != 137) {
                                    if (opcode != 157) {
                                        if (opcode != 53) {
                                            if (opcode != 54) {
                                                if (opcode != 125) {
                                                    if (opcode != 126) {
                                                        if (opcode != 159) {
                                                            if (opcode != 160) {
                                                                if (opcode != 163) {
                                                                    if (opcode != 164) {
                                                                        switch (opcode) {
                                                                            case HdmiCecKeycode.CEC_KEYCODE_MUTE /* 67 */:
                                                                                return handleTimerClearedStatus(message);
                                                                            case HdmiCecKeycode.CEC_KEYCODE_PLAY /* 68 */:
                                                                                return handleUserControlPressed(message);
                                                                            case HdmiCecKeycode.CEC_KEYCODE_STOP /* 69 */:
                                                                                return handleUserControlReleased();
                                                                            case HdmiCecKeycode.CEC_KEYCODE_PAUSE /* 70 */:
                                                                                return handleGiveOsdName(message);
                                                                            case HdmiCecKeycode.CEC_KEYCODE_RECORD /* 71 */:
                                                                                return handleSetOsdName(message);
                                                                            default:
                                                                                switch (opcode) {
                                                                                    case HdmiCecKeycode.UI_BROADCAST_DIGITAL_CABLE /* 112 */:
                                                                                        return handleSystemAudioModeRequest(message);
                                                                                    case HdmiCecKeycode.CEC_KEYCODE_F1_BLUE /* 113 */:
                                                                                        return handleGiveAudioStatus(message);
                                                                                    case HdmiCecKeycode.CEC_KEYCODE_F2_RED /* 114 */:
                                                                                        return handleSetSystemAudioMode(message);
                                                                                    default:
                                                                                        switch (opcode) {
                                                                                            case 128:
                                                                                                return handleRoutingChange(message);
                                                                                            case 129:
                                                                                                return handleRoutingInformation(message);
                                                                                            case 130:
                                                                                                return handleActiveSource(message);
                                                                                            case 131:
                                                                                                return handleGivePhysicalAddress(null);
                                                                                            case 132:
                                                                                                return handleReportPhysicalAddress(message);
                                                                                            case 133:
                                                                                                return handleRequestActiveSource(message);
                                                                                            case 134:
                                                                                                return handleSetStreamPath(message);
                                                                                            default:
                                                                                                switch (opcode) {
                                                                                                    case 140:
                                                                                                        return handleGiveDeviceVendorId(null);
                                                                                                    case 141:
                                                                                                        return handleMenuRequest(message);
                                                                                                    case 142:
                                                                                                        return handleMenuStatus(message);
                                                                                                    case 143:
                                                                                                        return handleGiveDevicePowerStatus(message);
                                                                                                    case 144:
                                                                                                        return handleReportPowerStatus(message);
                                                                                                    case HdmiCecKeycode.UI_BROADCAST_DIGITAL_COMMNICATIONS_SATELLITE_2 /* 145 */:
                                                                                                        return handleGetMenuLanguage(message);
                                                                                                    default:
                                                                                                        switch (opcode) {
                                                                                                            case 192:
                                                                                                                return handleInitiateArc(message);
                                                                                                            case HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_PLUS /* 193 */:
                                                                                                                return handleReportArcInitiate(message);
                                                                                                            case HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_NEUTRAL /* 194 */:
                                                                                                                return handleReportArcTermination(message);
                                                                                                            case HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_MINUS /* 195 */:
                                                                                                                return handleRequestArcInitiate(message);
                                                                                                            case 196:
                                                                                                                return handleRequestArcTermination(message);
                                                                                                            case 197:
                                                                                                                return handleTerminateArc(message);
                                                                                                            default:
                                                                                                                return false;
                                                                                                        }
                                                                                                }
                                                                                        }
                                                                                }
                                                                        }
                                                                    }
                                                                    return handleRequestShortAudioDescriptor(message);
                                                                }
                                                                return handleReportShortAudioDescriptor(message);
                                                            }
                                                            return handleVendorCommandWithId(message);
                                                        }
                                                        return handleGetCecVersion(message);
                                                    }
                                                    return handleSystemAudioModeStatus(message);
                                                }
                                                return handleGiveSystemAudioModeStatus(message);
                                            }
                                            return handleStandby(message);
                                        }
                                        return handleTimerStatus(message);
                                    }
                                    return handleInactiveSource(message);
                                }
                                return handleVendorCommand(message);
                            }
                            return handleReportAudioStatus(message);
                        }
                        return handleSetMenuLanguage(message);
                    }
                    return handleRecordTvScreen(message);
                }
                return handleTextViewOn(message);
            }
            return handleRecordStatus(message);
        }
        return handleImageViewOn(message);
    }

    @HdmiAnnotations.ServiceThreadOnly
    private boolean dispatchMessageToAction(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean processed = false;
        Iterator it = new ArrayList(this.mActions).iterator();
        while (it.hasNext()) {
            HdmiCecFeatureAction action = (HdmiCecFeatureAction) it.next();
            boolean result = action.processCommand(message);
            processed = processed || result;
        }
        return processed;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGivePhysicalAddress(HdmiControlService.SendMessageCallback callback) {
        assertRunOnServiceThread();
        int physicalAddress = this.mService.getPhysicalAddress();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(this.mAddress, physicalAddress, this.mDeviceType);
        this.mService.sendCecCommand(cecMessage, callback);
        return true;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGiveDeviceVendorId(HdmiControlService.SendMessageCallback callback) {
        assertRunOnServiceThread();
        int vendorId = this.mService.getVendorId();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildDeviceVendorIdCommand(this.mAddress, vendorId);
        this.mService.sendCecCommand(cecMessage, callback);
        return true;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGetCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int version = this.mService.getCecVersion();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildCecVersion(message.getDestination(), message.getSource(), version);
        this.mService.sendCecCommand(cecMessage);
        return true;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only Playback device can handle <Set Menu Language>:" + message.toString());
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGiveOsdName(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildSetOsdNameCommand(this.mAddress, message.getSource(), this.mDeviceInfo.getDisplayName());
        if (cecMessage != null) {
            this.mService.sendCecCommand(cecMessage);
            return true;
        }
        Slog.w(TAG, "Failed to build <Get Osd Name>:" + this.mDeviceInfo.getDisplayName());
        return true;
    }

    protected boolean handleRoutingChange(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRoutingInformation(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSystemAudioModeRequest(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleTerminateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleInitiateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportShortAudioDescriptor(HdmiCecMessage message) {
        return false;
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleStandby(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (this.mService.isControlEnabled() && !this.mService.isProhibitMode() && this.mService.isPowerOnOrTransient()) {
            this.mService.standby();
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @HdmiAnnotations.ServiceThreadOnly
    public boolean handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        this.mHandler.removeMessages(2);
        if (this.mService.isPowerOnOrTransient() && isPowerOffOrToggleCommand(message)) {
            this.mService.standby();
            return true;
        } else if (this.mService.isPowerStandbyOrTransient() && isPowerOnOrToggleCommand(message)) {
            this.mService.wakeUp();
            return true;
        } else {
            long downTime = SystemClock.uptimeMillis();
            byte[] params = message.getParams();
            int keycode = HdmiCecKeycode.cecKeycodeAndParamsToAndroidKey(params);
            int keyRepeatCount = 0;
            int i = this.mLastKeycode;
            if (i != -1) {
                if (keycode == i) {
                    keyRepeatCount = this.mLastKeyRepeatCount + 1;
                } else {
                    injectKeyEvent(downTime, 1, i, 0);
                }
            }
            this.mLastKeycode = keycode;
            this.mLastKeyRepeatCount = keyRepeatCount;
            if (keycode != -1) {
                injectKeyEvent(downTime, 0, keycode, keyRepeatCount);
                Handler handler = this.mHandler;
                handler.sendMessageDelayed(Message.obtain(handler, 2), 550L);
                return true;
            }
            return false;
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleUserControlReleased() {
        assertRunOnServiceThread();
        this.mHandler.removeMessages(2);
        this.mLastKeyRepeatCount = 0;
        if (this.mLastKeycode == -1) {
            return false;
        }
        long upTime = SystemClock.uptimeMillis();
        injectKeyEvent(upTime, 1, this.mLastKeycode, 0);
        this.mLastKeycode = -1;
        return true;
    }

    static void injectKeyEvent(long time, int action, int keycode, int repeat) {
        KeyEvent keyEvent = KeyEvent.obtain(time, time, action, keycode, repeat, 0, -1, 0, 8, 33554433, null);
        InputManager.getInstance().injectInputEvent(keyEvent, 0);
        keyEvent.recycle();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isPowerOnOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        if (message.getOpcode() == 68) {
            return params[0] == 64 || params[0] == 109 || params[0] == 107;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isPowerOffOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        if (message.getOpcode() == 68) {
            return params[0] == 108 || params[0] == 107;
        }
        return false;
    }

    protected boolean handleTextViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleImageViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveDevicePowerStatus(HdmiCecMessage message) {
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPowerStatus(this.mAddress, message.getSource(), this.mService.getPowerStatus()));
        return true;
    }

    protected boolean handleMenuRequest(HdmiCecMessage message) {
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportMenuStatus(this.mAddress, message.getSource(), 0));
        return true;
    }

    protected boolean handleMenuStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleVendorCommand(HdmiCecMessage message) {
        if (!this.mService.invokeVendorCommandListenersOnReceived(this.mDeviceType, message.getSource(), message.getDestination(), message.getParams(), false)) {
            this.mService.maySendFeatureAbortCommand(message, 1);
        }
        return true;
    }

    protected boolean handleVendorCommandWithId(HdmiCecMessage message) {
        byte[] params = message.getParams();
        int vendorId = HdmiUtils.threeBytesToInt(params);
        if (vendorId == this.mService.getVendorId()) {
            if (!this.mService.invokeVendorCommandListenersOnReceived(this.mDeviceType, message.getSource(), message.getDestination(), params, true)) {
                this.mService.maySendFeatureAbortCommand(message, 1);
            }
        } else if (message.getDestination() != 15 && message.getSource() != 15) {
            Slog.v(TAG, "Wrong direct vendor command. Replying with <Feature Abort>");
            this.mService.maySendFeatureAbortCommand(message, 0);
        } else {
            Slog.v(TAG, "Wrong broadcast vendor command. Ignoring");
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void sendStandby(int deviceId) {
    }

    protected boolean handleSetOsdName(HdmiCecMessage message) {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean handleRecordTvScreen(HdmiCecMessage message) {
        this.mService.maySendFeatureAbortCommand(message, 2);
        return true;
    }

    protected boolean handleTimerClearedStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPowerStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleTimerStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRecordStatus(HdmiCecMessage message) {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public final void handleAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        this.mPreferredAddress = logicalAddress;
        this.mAddress = logicalAddress;
        onAddressAllocated(logicalAddress, reason);
        setPreferredAddress(logicalAddress);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getType() {
        return this.mDeviceType;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public HdmiDeviceInfo getDeviceInfo() {
        HdmiDeviceInfo hdmiDeviceInfo;
        synchronized (this.mLock) {
            hdmiDeviceInfo = this.mDeviceInfo;
        }
        return hdmiDeviceInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void setDeviceInfo(HdmiDeviceInfo info) {
        synchronized (this.mLock) {
            this.mDeviceInfo = info;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public boolean isAddressOf(int addr) {
        assertRunOnServiceThread();
        return addr == this.mAddress;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void clearAddress() {
        assertRunOnServiceThread();
        this.mAddress = 15;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void addAndStartAction(HdmiCecFeatureAction action) {
        assertRunOnServiceThread();
        this.mActions.add(action);
        if (this.mService.isPowerStandby() || !this.mService.isAddressAllocated()) {
            Slog.i(TAG, "Not ready to start action. Queued for deferred start:" + action);
            return;
        }
        action.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void startQueuedActions() {
        assertRunOnServiceThread();
        Iterator it = new ArrayList(this.mActions).iterator();
        while (it.hasNext()) {
            HdmiCecFeatureAction action = (HdmiCecFeatureAction) it.next();
            if (!action.started()) {
                Slog.i(TAG, "Starting queued action:" + action);
                action.start();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public <T extends HdmiCecFeatureAction> boolean hasAction(Class<T> clazz) {
        assertRunOnServiceThread();
        Iterator<HdmiCecFeatureAction> it = this.mActions.iterator();
        while (it.hasNext()) {
            HdmiCecFeatureAction action = it.next();
            if (action.getClass().equals(clazz)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public <T extends HdmiCecFeatureAction> List<T> getActions(Class<T> clazz) {
        assertRunOnServiceThread();
        List<T> actions = Collections.emptyList();
        Iterator<HdmiCecFeatureAction> it = this.mActions.iterator();
        while (it.hasNext()) {
            HdmiCecFeatureAction action = it.next();
            if (action.getClass().equals(clazz)) {
                if (actions.isEmpty()) {
                    List<T> actions2 = new ArrayList<>();
                    actions = actions2;
                }
                actions.add(action);
            }
        }
        return actions;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void removeAction(HdmiCecFeatureAction action) {
        assertRunOnServiceThread();
        action.finish(false);
        this.mActions.remove(action);
        checkIfPendingActionsCleared();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public <T extends HdmiCecFeatureAction> void removeAction(Class<T> clazz) {
        assertRunOnServiceThread();
        removeActionExcept(clazz, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public <T extends HdmiCecFeatureAction> void removeActionExcept(Class<T> clazz, HdmiCecFeatureAction exception) {
        assertRunOnServiceThread();
        Iterator<HdmiCecFeatureAction> iter = this.mActions.iterator();
        while (iter.hasNext()) {
            HdmiCecFeatureAction action = iter.next();
            if (action != exception && action.getClass().equals(clazz)) {
                action.finish(false);
                iter.remove();
            }
        }
        checkIfPendingActionsCleared();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void checkIfPendingActionsCleared() {
        if (this.mActions.isEmpty() && this.mPendingActionClearedCallback != null) {
            PendingActionClearedCallback callback = this.mPendingActionClearedCallback;
            this.mPendingActionClearedCallback = null;
            callback.onCleared(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void assertRunOnServiceThread() {
        if (Looper.myLooper() != this.mService.getServiceLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAutoDeviceOff(boolean enabled) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onHotplug(int portId, boolean connected) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final HdmiControlService getService() {
        return this.mService;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public final boolean isConnectedToArcPort(int path) {
        assertRunOnServiceThread();
        return this.mService.isConnectedToArcPort(path);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActiveSource getActiveSource() {
        return this.mService.getActiveSource();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActiveSource(ActiveSource newActive) {
        setActiveSource(newActive.logicalAddress, newActive.physicalAddress);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActiveSource(HdmiDeviceInfo info) {
        setActiveSource(info.getLogicalAddress(), info.getPhysicalAddress());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActiveSource(int logicalAddress, int physicalAddress) {
        this.mService.setActiveSource(logicalAddress, physicalAddress);
        this.mService.setLastInputForMhl(-1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getActivePath() {
        int i;
        synchronized (this.mLock) {
            i = this.mActiveRoutingPath;
        }
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActivePath(int path) {
        synchronized (this.mLock) {
            this.mActiveRoutingPath = path;
        }
        this.mService.setActivePortId(pathToPortId(path));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getActivePortId() {
        int pathToPortId;
        synchronized (this.mLock) {
            pathToPortId = this.mService.pathToPortId(this.mActiveRoutingPath);
        }
        return pathToPortId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActivePortId(int portId) {
        setActivePath(this.mService.portIdToPath(portId));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getPortId(int physicalAddress) {
        return this.mService.pathToPortId(physicalAddress);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public HdmiCecMessageCache getCecMessageCache() {
        assertRunOnServiceThread();
        return this.mCecMessageCache;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public int pathToPortId(int newPath) {
        assertRunOnServiceThread();
        return this.mService.pathToPortId(newPath);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onStandby(boolean initiatedByCec, int standbyAction) {
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void disableDevice(boolean initiatedByCec, final PendingActionClearedCallback originalCallback) {
        this.mPendingActionClearedCallback = new PendingActionClearedCallback() { // from class: com.android.server.hdmi.HdmiCecLocalDevice.2
            @Override // com.android.server.hdmi.HdmiCecLocalDevice.PendingActionClearedCallback
            public void onCleared(HdmiCecLocalDevice device) {
                HdmiCecLocalDevice.this.mHandler.removeMessages(1);
                originalCallback.onCleared(device);
            }
        };
        Handler handler = this.mHandler;
        handler.sendMessageDelayed(Message.obtain(handler, 1), 5000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.ServiceThreadOnly
    public void handleDisableDeviceTimeout() {
        assertRunOnServiceThread();
        Iterator<HdmiCecFeatureAction> iter = this.mActions.iterator();
        while (iter.hasNext()) {
            HdmiCecFeatureAction action = iter.next();
            action.finish(false);
            iter.remove();
        }
        PendingActionClearedCallback pendingActionClearedCallback = this.mPendingActionClearedCallback;
        if (pendingActionClearedCallback != null) {
            pendingActionClearedCallback.onCleared(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @HdmiAnnotations.ServiceThreadOnly
    public void sendKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        if (!HdmiCecKeycode.isSupportedKeycode(keyCode)) {
            Slog.w(TAG, "Unsupported key: " + keyCode);
            return;
        }
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        int logicalAddress = findKeyReceiverAddress();
        if (logicalAddress == -1 || logicalAddress == this.mAddress) {
            Slog.w(TAG, "Discard key event: " + keyCode + ", pressed:" + isPressed + ", receiverAddr=" + logicalAddress);
        } else if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else if (isPressed) {
            addAndStartAction(new SendKeyAction(this, logicalAddress, keyCode));
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @HdmiAnnotations.ServiceThreadOnly
    public void sendVolumeKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        if (!HdmiCecKeycode.isVolumeKeycode(keyCode)) {
            Slog.w(TAG, "Not a volume key: " + keyCode);
            return;
        }
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        int logicalAddress = findAudioReceiverAddress();
        if (logicalAddress == -1 || logicalAddress == this.mAddress) {
            Slog.w(TAG, "Discard volume key event: " + keyCode + ", pressed:" + isPressed + ", receiverAddr=" + logicalAddress);
        } else if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else if (isPressed) {
            addAndStartAction(new SendKeyAction(this, logicalAddress, keyCode));
        }
    }

    protected int findKeyReceiverAddress() {
        Slog.w(TAG, "findKeyReceiverAddress is not implemented");
        return -1;
    }

    protected int findAudioReceiverAddress() {
        Slog.w(TAG, "findAudioReceiverAddress is not implemented");
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void invokeCallback(IHdmiControlCallback callback, int result) {
        assertRunOnServiceThread();
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendUserControlPressedAndReleased(int targetAddress, int cecKeycode) {
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildUserControlPressed(this.mAddress, targetAddress, cecKeycode));
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildUserControlReleased(this.mAddress, targetAddress));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void dump(IndentingPrintWriter pw) {
        pw.println("mDeviceType: " + this.mDeviceType);
        pw.println("mAddress: " + this.mAddress);
        pw.println("mPreferredAddress: " + this.mPreferredAddress);
        pw.println("mDeviceInfo: " + this.mDeviceInfo);
        pw.println("mActiveSource: " + getActiveSource());
        pw.println(String.format("mActiveRoutingPath: 0x%04x", Integer.valueOf(this.mActiveRoutingPath)));
    }

    protected int getActivePathOnSwitchFromActivePortId(@Constants.LocalActivePort int activePortId) {
        int myPhysicalAddress = this.mService.getPhysicalAddress();
        int finalMask = activePortId << 8;
        for (int mask = 3840; mask > 15 && (myPhysicalAddress & mask) != 0; mask >>= 4) {
            finalMask >>= 4;
        }
        return finalMask | myPhysicalAddress;
    }
}
