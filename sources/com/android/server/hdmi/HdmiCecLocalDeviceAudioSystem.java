package com.android.server.hdmi;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.DeviceDiscoveryAction;
import com.android.server.hdmi.HdmiAnnotations;
import com.android.server.hdmi.HdmiCecLocalDevice;
import com.android.server.hdmi.HdmiUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public class HdmiCecLocalDeviceAudioSystem extends HdmiCecLocalDeviceSource {
    private static final String SHORT_AUDIO_DESCRIPTOR_CONFIG_PATH = "/vendor/etc/sadConfig.xml";
    private static final String TAG = "HdmiCecLocalDeviceAudioSystem";
    @HdmiAnnotations.ServiceThreadOnly
    private boolean mArcEstablished;
    private boolean mArcIntentUsed;
    private final SparseArray<HdmiDeviceInfo> mDeviceInfos;
    @GuardedBy({"mLock"})
    private final HashMap<Integer, String> mPortIdToTvInputs;
    @GuardedBy({"mLock"})
    private List<HdmiDeviceInfo> mSafeAllDeviceInfos;
    @GuardedBy({"mLock"})
    private boolean mSystemAudioControlFeatureEnabled;
    private final TvInputManager.TvInputCallback mTvInputCallback;
    @GuardedBy({"mLock"})
    private final HashMap<String, HdmiDeviceInfo> mTvInputsToDeviceInfo;
    private Boolean mTvSystemAudioModeSupport;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface TvSystemAudioModeSupportedCallback {
        void onResult(boolean z);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public HdmiCecLocalDeviceAudioSystem(HdmiControlService service) {
        super(service, 5);
        this.mTvSystemAudioModeSupport = null;
        this.mArcEstablished = false;
        this.mArcIntentUsed = SystemProperties.get("ro.hdmi.property_sytem_audio_device_arc_port", "0").contains("tvinput");
        this.mPortIdToTvInputs = new HashMap<>();
        this.mTvInputsToDeviceInfo = new HashMap<>();
        this.mSafeAllDeviceInfos = Collections.emptyList();
        this.mDeviceInfos = new SparseArray<>();
        this.mTvInputCallback = new TvInputManager.TvInputCallback() { // from class: com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.1
            @Override // android.media.tv.TvInputManager.TvInputCallback
            public void onInputAdded(String inputId) {
                HdmiCecLocalDeviceAudioSystem.this.addOrUpdateTvInput(inputId);
            }

            @Override // android.media.tv.TvInputManager.TvInputCallback
            public void onInputRemoved(String inputId) {
                HdmiCecLocalDeviceAudioSystem.this.removeTvInput(inputId);
            }

            @Override // android.media.tv.TvInputManager.TvInputCallback
            public void onInputUpdated(String inputId) {
                HdmiCecLocalDeviceAudioSystem.this.addOrUpdateTvInput(inputId);
            }
        };
        this.mRoutingControlFeatureEnabled = this.mService.readBooleanSetting("hdmi_cec_switch_enabled", false);
        this.mSystemAudioControlFeatureEnabled = this.mService.readBooleanSetting("hdmi_system_audio_control_enabled", true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.ServiceThreadOnly
    public void addOrUpdateTvInput(String inputId) {
        assertRunOnServiceThread();
        synchronized (this.mLock) {
            TvInputInfo tvInfo = this.mService.getTvInputManager().getTvInputInfo(inputId);
            if (tvInfo == null) {
                return;
            }
            HdmiDeviceInfo info = tvInfo.getHdmiDeviceInfo();
            if (info == null) {
                return;
            }
            this.mPortIdToTvInputs.put(Integer.valueOf(info.getPortId()), inputId);
            this.mTvInputsToDeviceInfo.put(inputId, info);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.ServiceThreadOnly
    public void removeTvInput(String inputId) {
        assertRunOnServiceThread();
        synchronized (this.mLock) {
            if (this.mTvInputsToDeviceInfo.get(inputId) == null) {
                return;
            }
            int portId = this.mTvInputsToDeviceInfo.get(inputId).getPortId();
            this.mPortIdToTvInputs.remove(Integer.valueOf(portId));
            this.mTvInputsToDeviceInfo.remove(inputId);
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    final void addCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);
        if (info.getPhysicalAddress() == this.mService.getPhysicalAddress()) {
            return;
        }
        if (old == null) {
            invokeDeviceEventListener(info, 1);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(old, 2);
            invokeDeviceEventListener(info, 1);
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        HdmiDeviceInfo info = removeDeviceInfo(HdmiDeviceInfo.idForCecDevice(address));
        this.mCecMessageCache.flushMessagesFrom(address);
        invokeDeviceEventListener(info, 2);
    }

    @HdmiAnnotations.ServiceThreadOnly
    final void updateCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);
        if (old == null) {
            invokeDeviceEventListener(info, 1);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(info, 3);
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    @VisibleForTesting
    protected HdmiDeviceInfo addDeviceInfo(HdmiDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiDeviceInfo oldDeviceInfo = getCecDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getId());
        }
        this.mDeviceInfos.append(deviceInfo.getId(), deviceInfo);
        updateSafeDeviceInfoList();
        return oldDeviceInfo;
    }

    @HdmiAnnotations.ServiceThreadOnly
    private HdmiDeviceInfo removeDeviceInfo(int id) {
        assertRunOnServiceThread();
        HdmiDeviceInfo deviceInfo = this.mDeviceInfos.get(id);
        if (deviceInfo != null) {
            this.mDeviceInfos.remove(id);
        }
        updateSafeDeviceInfoList();
        return deviceInfo;
    }

    @HdmiAnnotations.ServiceThreadOnly
    HdmiDeviceInfo getCecDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return this.mDeviceInfos.get(HdmiDeviceInfo.idForCecDevice(logicalAddress));
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(this.mDeviceInfos);
        synchronized (this.mLock) {
            this.mSafeAllDeviceInfos = copiedDevices;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public List<HdmiDeviceInfo> getSafeCecDevicesLocked() {
        ArrayList<HdmiDeviceInfo> infoList = new ArrayList<>();
        for (HdmiDeviceInfo info : this.mSafeAllDeviceInfos) {
            infoList.add(info);
        }
        return infoList;
    }

    private void invokeDeviceEventListener(HdmiDeviceInfo info, int status) {
        this.mService.invokeDeviceEventListeners(info, status);
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDeviceSource, com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        if (connected) {
            this.mService.wakeUp();
        }
        if (this.mService.getPortInfo(portId).getType() == 1) {
            this.mCecMessageCache.flushAll();
        } else if (!connected && this.mPortIdToTvInputs.get(Integer.valueOf(portId)) != null) {
            String tvInputId = this.mPortIdToTvInputs.get(Integer.valueOf(portId));
            HdmiDeviceInfo info = this.mTvInputsToDeviceInfo.get(tvInputId);
            if (info == null) {
                return;
            }
            removeCecDevice(info.getLogicalAddress());
        }
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, HdmiCecLocalDevice.PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);
        assertRunOnServiceThread();
        this.mService.unregisterTvInputCallback(this.mTvInputCallback);
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        this.mTvSystemAudioModeSupport = null;
        synchronized (this.mLock) {
            this.mService.writeStringSystemProperty("persist.sys.hdmi.last_system_audio_control", isSystemAudioActivated() ? "true" : "false");
        }
        terminateSystemAudioMode();
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        HdmiControlService hdmiControlService = this.mService;
        if (reason == 0) {
            this.mService.setAndBroadcastActiveSource(this.mService.getPhysicalAddress(), getDeviceInfo().getDeviceType(), 15);
        }
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(this.mAddress, this.mService.getPhysicalAddress(), this.mDeviceType));
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(this.mAddress, this.mService.getVendorId()));
        this.mService.registerTvInputCallback(this.mTvInputCallback);
        int systemAudioControlOnPowerOnProp = SystemProperties.getInt("persist.sys.hdmi.system_audio_control_on_power_on", 0);
        boolean lastSystemAudioControlStatus = SystemProperties.getBoolean("persist.sys.hdmi.last_system_audio_control", true);
        systemAudioControlOnPowerOn(systemAudioControlOnPowerOnProp, lastSystemAudioControlStatus);
        clearDeviceInfoList();
        launchDeviceDiscovery();
        startQueuedActions();
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    protected int findKeyReceiverAddress() {
        if (getActiveSource().isValid()) {
            return getActiveSource().logicalAddress;
        }
        return -1;
    }

    @VisibleForTesting
    protected void systemAudioControlOnPowerOn(int systemAudioOnPowerOnProp, boolean lastSystemAudioControlStatus) {
        if (systemAudioOnPowerOnProp == 0 || (systemAudioOnPowerOnProp == 1 && lastSystemAudioControlStatus && isSystemAudioControlFeatureEnabled())) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt("persist.sys.hdmi.addr.audiosystem", 15);
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        this.mService.writeStringSystemProperty("persist.sys.hdmi.addr.audiosystem", String.valueOf(addr));
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        int type = message.getParams()[2];
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignored while Device Discovery Action is in progress: " + message);
            return true;
        }
        HdmiDeviceInfo oldDevice = getCecDeviceInfo(address);
        if (oldDevice == null || oldDevice.getPhysicalAddress() != path) {
            addCecDevice(new HdmiDeviceInfo(address, path, this.mService.pathToPortId(path), type, 16777215, HdmiUtils.getDefaultDeviceName(address)));
            this.mService.sendCecCommand(HdmiCecMessageBuilder.buildGiveOsdNameCommand(this.mAddress, address));
            return true;
        }
        Slog.w(TAG, "Device info exists. Not updating on Physical Address.");
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    protected boolean handleReportPowerStatus(HdmiCecMessage command) {
        int newStatus = command.getParams()[0] & 255;
        updateDevicePowerStatus(command.getSource(), newStatus);
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleSetOsdName(HdmiCecMessage message) {
        int source = message.getSource();
        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(source);
        if (deviceInfo == null) {
            Slog.i(TAG, "No source device info for <Set Osd Name>." + message);
            return true;
        }
        try {
            String osdName = new String(message.getParams(), "US-ASCII");
            if (deviceInfo.getDisplayName().equals(osdName)) {
                Slog.d(TAG, "Ignore incoming <Set Osd Name> having same osd name:" + message);
                return true;
            }
            Slog.d(TAG, "Updating device OSD name from " + deviceInfo.getDisplayName() + " to " + osdName);
            updateCecDevice(new HdmiDeviceInfo(deviceInfo.getLogicalAddress(), deviceInfo.getPhysicalAddress(), deviceInfo.getPortId(), deviceInfo.getDeviceType(), deviceInfo.getVendorId(), osdName));
            return true;
        } catch (UnsupportedEncodingException e) {
            Slog.e(TAG, "Invalid <Set Osd Name> request:" + message, e);
            return true;
        }
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug("HdmiCecLocalDeviceAudioSystemStub handleInitiateArc", new Object[0]);
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug("HdmiCecLocalDeviceAudioSystemStub handleReportArcInitiate", new Object[0]);
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug("HdmiCecLocalDeviceAudioSystemStub handleReportArcTermination", new Object[0]);
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isSystemAudioControlFeatureEnabled()) {
            reportAudioStatus(message.getSource());
            return true;
        }
        this.mService.maySendFeatureAbortCommand(message, 4);
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean isSystemAudioModeOnOrTurningOn = isSystemAudioActivated();
        if (!isSystemAudioModeOnOrTurningOn && message.getSource() == 0 && hasAction(SystemAudioInitiationActionFromAvr.class)) {
            isSystemAudioModeOnOrTurningOn = true;
        }
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportSystemAudioMode(this.mAddress, message.getSource(), isSystemAudioModeOnOrTurningOn));
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        removeAction(ArcInitiationActionFromAvr.class);
        if (!this.mService.readBooleanSystemProperty("persist.sys.hdmi.property_arc_support", true)) {
            this.mService.maySendFeatureAbortCommand(message, 0);
        } else if (!isDirectConnectToTv()) {
            HdmiLogger.debug("AVR device is not directly connected with TV", new Object[0]);
            this.mService.maySendFeatureAbortCommand(message, 1);
        } else {
            addAndStartAction(new ArcInitiationActionFromAvr(this));
        }
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SystemProperties.getBoolean("persist.sys.hdmi.property_arc_support", true)) {
            this.mService.maySendFeatureAbortCommand(message, 0);
        } else if (!isArcEnabled()) {
            HdmiLogger.debug("ARC is not established between TV and AVR device", new Object[0]);
            this.mService.maySendFeatureAbortCommand(message, 1);
        } else {
            removeAction(ArcTerminationActionFromAvr.class);
            addAndStartAction(new ArcTerminationActionFromAvr(this));
        }
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        byte[] sadBytes;
        assertRunOnServiceThread();
        HdmiLogger.debug("HdmiCecLocalDeviceAudioSystemStub handleRequestShortAudioDescriptor", new Object[0]);
        if (!isSystemAudioControlFeatureEnabled()) {
            this.mService.maySendFeatureAbortCommand(message, 4);
            return true;
        } else if (!isSystemAudioActivated()) {
            this.mService.maySendFeatureAbortCommand(message, 1);
            return true;
        } else {
            List<HdmiUtils.DeviceConfig> config = null;
            File file = new File(SHORT_AUDIO_DESCRIPTOR_CONFIG_PATH);
            if (file.exists()) {
                try {
                    InputStream in = new FileInputStream(file);
                    config = HdmiUtils.ShortAudioDescriptorXmlParser.parse(in);
                    in.close();
                } catch (IOException e) {
                    Slog.e(TAG, "Error reading file: " + file, e);
                } catch (XmlPullParserException e2) {
                    Slog.e(TAG, "Unable to parse file: " + file, e2);
                }
            }
            int[] audioFormatCodes = parseAudioFormatCodes(message.getParams());
            if (config != null && config.size() > 0) {
                sadBytes = getSupportedShortAudioDescriptorsFromConfig(config, audioFormatCodes);
            } else {
                AudioDeviceInfo deviceInfo = getSystemAudioDeviceInfo();
                if (deviceInfo == null) {
                    this.mService.maySendFeatureAbortCommand(message, 5);
                    return true;
                }
                sadBytes = getSupportedShortAudioDescriptors(deviceInfo, audioFormatCodes);
            }
            if (sadBytes.length == 0) {
                this.mService.maySendFeatureAbortCommand(message, 3);
            } else {
                this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportShortAudioDescriptor(this.mAddress, message.getSource(), sadBytes));
            }
            return true;
        }
    }

    private byte[] getSupportedShortAudioDescriptors(AudioDeviceInfo deviceInfo, int[] audioFormatCodes) {
        ArrayList<byte[]> sads = new ArrayList<>(audioFormatCodes.length);
        for (int audioFormatCode : audioFormatCodes) {
            byte[] sad = getSupportedShortAudioDescriptor(deviceInfo, audioFormatCode);
            if (sad != null) {
                if (sad.length == 3) {
                    sads.add(sad);
                } else {
                    HdmiLogger.warning("Dropping Short Audio Descriptor with length %d for requested codec %x", Integer.valueOf(sad.length), Integer.valueOf(audioFormatCode));
                }
            }
        }
        return getShortAudioDescriptorBytes(sads);
    }

    private byte[] getSupportedShortAudioDescriptorsFromConfig(List<HdmiUtils.DeviceConfig> deviceConfig, int[] audioFormatCodes) {
        byte[] sad;
        HdmiUtils.DeviceConfig deviceConfigToUse = null;
        Iterator<HdmiUtils.DeviceConfig> it = deviceConfig.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            HdmiUtils.DeviceConfig device = it.next();
            if (device.name.equals("VX_AUDIO_DEVICE_IN_HDMI_ARC")) {
                deviceConfigToUse = device;
                break;
            }
        }
        if (deviceConfigToUse == null) {
            Slog.w(TAG, "sadConfig.xml does not have required device info for VX_AUDIO_DEVICE_IN_HDMI_ARC");
            return new byte[0];
        }
        HashMap<Integer, byte[]> map = new HashMap<>();
        ArrayList<byte[]> sads = new ArrayList<>(audioFormatCodes.length);
        for (HdmiUtils.CodecSad codecSad : deviceConfigToUse.supportedCodecs) {
            map.put(Integer.valueOf(codecSad.audioCodec), codecSad.sad);
        }
        for (int i = 0; i < audioFormatCodes.length; i++) {
            if (map.containsKey(Integer.valueOf(audioFormatCodes[i])) && (sad = map.get(Integer.valueOf(audioFormatCodes[i]))) != null && sad.length == 3) {
                sads.add(sad);
            }
        }
        return getShortAudioDescriptorBytes(sads);
    }

    private byte[] getShortAudioDescriptorBytes(ArrayList<byte[]> sads) {
        byte[] bytes = new byte[sads.size() * 3];
        int index = 0;
        Iterator<byte[]> it = sads.iterator();
        while (it.hasNext()) {
            byte[] sad = it.next();
            System.arraycopy(sad, 0, bytes, index, 3);
            index += 3;
        }
        return bytes;
    }

    private byte[] getSupportedShortAudioDescriptor(AudioDeviceInfo deviceInfo, int audioFormatCode) {
        if (audioFormatCode == 0 || audioFormatCode != 1) {
            return null;
        }
        return getLpcmShortAudioDescriptor(deviceInfo);
    }

    private byte[] getLpcmShortAudioDescriptor(AudioDeviceInfo deviceInfo) {
        return null;
    }

    private AudioDeviceInfo getSystemAudioDeviceInfo() {
        AudioManager audioManager = (AudioManager) this.mService.getContext().getSystemService(AudioManager.class);
        if (audioManager == null) {
            HdmiLogger.error("Error getting system audio device because AudioManager not available.", new Object[0]);
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(1);
        HdmiLogger.debug("Found %d audio input devices", Integer.valueOf(devices.length));
        for (AudioDeviceInfo device : devices) {
            HdmiLogger.debug("%s at port %s", device.getProductName(), device.getPort());
            HdmiLogger.debug("Supported encodings are %s", Arrays.stream(device.getEncodings()).mapToObj(new IntFunction() { // from class: com.android.server.hdmi.-$$Lambda$Q3K33XXIADNcvSncyQ2-wHWHi4c
                @Override // java.util.function.IntFunction
                public final Object apply(int i) {
                    return AudioFormat.toLogFriendlyEncoding(i);
                }
            }).collect(Collectors.joining(", ")));
            if (device.getType() == 10) {
                return device;
            }
        }
        return null;
    }

    private int[] parseAudioFormatCodes(byte[] params) {
        int[] audioFormatCodes = new int[params.length];
        for (int i = 0; i < params.length; i++) {
            byte val = params[i];
            audioFormatCodes[i] = (val < 1 || val > 15) ? (byte) 0 : val;
        }
        return audioFormatCodes;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleSystemAudioModeRequest(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean systemAudioStatusOn = message.getParams().length != 0;
        if (message.getSource() != 0) {
            if (systemAudioStatusOn) {
                handleSystemAudioModeOnFromNonTvDevice(message);
                return true;
            }
        } else {
            setTvSystemAudioModeSupport(true);
        }
        if (!checkSupportAndSetSystemAudioMode(systemAudioStatusOn)) {
            this.mService.maySendFeatureAbortCommand(message, 4);
            return true;
        }
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(this.mAddress, 15, systemAudioStatusOn));
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            this.mService.maySendFeatureAbortCommand(message, 4);
            return true;
        }
        return true;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    @HdmiAnnotations.ServiceThreadOnly
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            this.mService.maySendFeatureAbortCommand(message, 4);
            return true;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void setArcStatus(boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("Set Arc Status[old:%b new:%b]", Boolean.valueOf(this.mArcEstablished), Boolean.valueOf(enabled));
        enableAudioReturnChannel(enabled);
        notifyArcStatusToAudioService(enabled);
        this.mArcEstablished = enabled;
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        this.mService.enableAudioReturnChannel(SystemProperties.getInt("ro.hdmi.property_sytem_audio_device_arc_port", 0), enabled);
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        this.mService.getAudioManager().setWiredDeviceConnectionState(-2147483616, enabled ? 1 : 0, "", "");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportAudioStatus(int source) {
        assertRunOnServiceThread();
        int volume = this.mService.getAudioManager().getStreamVolume(3);
        boolean mute = this.mService.getAudioManager().isStreamMute(3);
        int maxVolume = this.mService.getAudioManager().getStreamMaxVolume(3);
        int minVolume = this.mService.getAudioManager().getStreamMinVolume(3);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiLogger.debug("Reporting volume %d (%d-%d) as CEC volume %d", Integer.valueOf(volume), Integer.valueOf(minVolume), Integer.valueOf(maxVolume), Integer.valueOf(scaledVolume));
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildReportAudioStatus(this.mAddress, source, scaledVolume, mute));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean checkSupportAndSetSystemAudioMode(boolean newSystemAudioMode) {
        if (!isSystemAudioControlFeatureEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Cannot turn ");
            sb.append(newSystemAudioMode ? "on" : "off");
            sb.append("system audio mode because the System Audio Control feature is disabled.");
            HdmiLogger.debug(sb.toString(), new Object[0]);
            return false;
        }
        HdmiLogger.debug("System Audio Mode change[old:%b new:%b]", Boolean.valueOf(isSystemAudioActivated()), Boolean.valueOf(newSystemAudioMode));
        if (newSystemAudioMode) {
            this.mService.wakeUp();
        }
        setSystemAudioMode(newSystemAudioMode);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setSystemAudioMode(boolean newSystemAudioMode) {
        int i;
        int targetPhysicalAddress = getActiveSource().physicalAddress;
        int port = this.mService.pathToPortId(targetPhysicalAddress);
        if (newSystemAudioMode && port >= 0) {
            switchToAudioInput();
        }
        boolean currentMuteStatus = this.mService.getAudioManager().isStreamMute(3);
        if (currentMuteStatus == newSystemAudioMode && (this.mService.readBooleanSystemProperty("ro.hdmi.property_system_audio_mode_muting_enable", true) || newSystemAudioMode)) {
            AudioManager audioManager = this.mService.getAudioManager();
            if (newSystemAudioMode) {
                i = 100;
            } else {
                i = -100;
            }
            audioManager.adjustStreamVolume(3, i, 0);
        }
        updateAudioManagerForSystemAudio(newSystemAudioMode);
        synchronized (this.mLock) {
            if (isSystemAudioActivated() != newSystemAudioMode) {
                this.mService.setSystemAudioActivated(newSystemAudioMode);
                this.mService.announceSystemAudioModeChange(newSystemAudioMode);
            }
        }
        if (SystemProperties.getBoolean("persist.sys.hdmi.property_arc_support", true) && isDirectConnectToTv()) {
            if (newSystemAudioMode && !isArcEnabled()) {
                removeAction(ArcInitiationActionFromAvr.class);
                addAndStartAction(new ArcInitiationActionFromAvr(this));
            } else if (!newSystemAudioMode && isArcEnabled()) {
                removeAction(ArcTerminationActionFromAvr.class);
                addAndStartAction(new ArcTerminationActionFromAvr(this));
            }
        }
    }

    protected void switchToAudioInput() {
    }

    protected boolean isDirectConnectToTv() {
        int myPhysicalAddress = this.mService.getPhysicalAddress();
        return (61440 & myPhysicalAddress) == myPhysicalAddress;
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = this.mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", Boolean.valueOf(on), Integer.valueOf(device));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemAduioControlFeatureSupportChanged(boolean enabled) {
        setSystemAudioControlFeatureEnabled(enabled);
        if (enabled) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    void setSystemAudioControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (this.mLock) {
            this.mSystemAudioControlFeatureEnabled = enabled;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void setRoutingControlFeatureEnables(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (this.mLock) {
            this.mRoutingControlFeatureEnabled = enabled;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        int oldPath;
        assertRunOnServiceThread();
        if (!this.mService.isValidPortId(portId)) {
            invokeCallback(callback, 3);
        } else if (portId == getLocalActivePort()) {
            invokeCallback(callback, 0);
        } else if (!this.mService.isControlEnabled()) {
            setRoutingPort(portId);
            setLocalActivePort(portId);
            invokeCallback(callback, 6);
        } else {
            if (getRoutingPort() != 0) {
                oldPath = this.mService.portIdToPath(getRoutingPort());
            } else {
                oldPath = getDeviceInfo().getPhysicalAddress();
            }
            int newPath = this.mService.portIdToPath(portId);
            if (oldPath == newPath) {
                return;
            }
            setRoutingPort(portId);
            setLocalActivePort(portId);
            HdmiCecMessage routingChange = HdmiCecMessageBuilder.buildRoutingChange(this.mAddress, oldPath, newPath);
            this.mService.sendCecCommand(routingChange);
        }
    }

    boolean isSystemAudioControlFeatureEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mSystemAudioControlFeatureEnabled;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isSystemAudioActivated() {
        return this.mService.isSystemAudioActivated();
    }

    protected void terminateSystemAudioMode() {
        removeAction(SystemAudioInitiationActionFromAvr.class);
        if (isSystemAudioActivated() && checkSupportAndSetSystemAudioMode(false)) {
            this.mService.sendCecCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(this.mAddress, 15, false));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void queryTvSystemAudioModeSupport(TvSystemAudioModeSupportedCallback callback) {
        Boolean bool = this.mTvSystemAudioModeSupport;
        if (bool == null) {
            addAndStartAction(new DetectTvSystemAudioModeSupportAction(this, callback));
        } else {
            callback.onResult(bool.booleanValue());
        }
    }

    void handleSystemAudioModeOnFromNonTvDevice(final HdmiCecMessage message) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug("Cannot turn onsystem audio mode because the System Audio Control feature is disabled.", new Object[0]);
            this.mService.maySendFeatureAbortCommand(message, 4);
            return;
        }
        this.mService.wakeUp();
        if (this.mService.pathToPortId(getActiveSource().physicalAddress) != -1) {
            setSystemAudioMode(true);
            this.mService.sendCecCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(this.mAddress, 15, true));
            return;
        }
        queryTvSystemAudioModeSupport(new TvSystemAudioModeSupportedCallback() { // from class: com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.2
            @Override // com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.TvSystemAudioModeSupportedCallback
            public void onResult(boolean supported) {
                if (supported) {
                    HdmiCecLocalDeviceAudioSystem.this.setSystemAudioMode(true);
                    HdmiCecLocalDeviceAudioSystem.this.mService.sendCecCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(HdmiCecLocalDeviceAudioSystem.this.mAddress, 15, true));
                    return;
                }
                HdmiCecLocalDeviceAudioSystem.this.mService.maySendFeatureAbortCommand(message, 4);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTvSystemAudioModeSupport(boolean supported) {
        this.mTvSystemAudioModeSupport = Boolean.valueOf(supported);
    }

    @VisibleForTesting
    protected boolean isArcEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mArcEstablished;
        }
        return z;
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDeviceSource
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        int port = this.mService.pathToPortId(physicalAddress);
        if (isSystemAudioActivated() && port < 0) {
            routeToInputFromPortId(17);
        } else if (this.mIsSwitchDevice && port >= 0) {
            routeToInputFromPortId(port);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void routeToInputFromPortId(int portId) {
        if (!isRoutingControlFeatureEnabled()) {
            HdmiLogger.debug("Routing Control Feature is not enabled.", new Object[0]);
        } else if (this.mArcIntentUsed) {
            routeToTvInputFromPortId(portId);
        }
    }

    protected void routeToTvInputFromPortId(int portId) {
        if (portId < 0 || portId >= 21) {
            HdmiLogger.debug("Invalid port number for Tv Input switching.", new Object[0]);
            return;
        }
        this.mService.wakeUp();
        if (portId == 0 && this.mService.isPlaybackDevice()) {
            switchToHomeTvInput();
        } else if (portId == 17) {
            switchToTvInput(SystemProperties.get("ro.hdmi.property_sytem_audio_device_arc_port"));
            setLocalActivePort(portId);
            return;
        } else {
            String uri = this.mPortIdToTvInputs.get(Integer.valueOf(portId));
            if (uri != null) {
                switchToTvInput(uri);
            } else {
                HdmiLogger.debug("Port number does not match any Tv Input.", new Object[0]);
                return;
            }
        }
        setLocalActivePort(portId);
        setRoutingPort(portId);
    }

    private void switchToTvInput(String uri) {
        try {
            this.mService.getContext().startActivity(new Intent("android.intent.action.VIEW", TvContract.buildChannelUriForPassthroughInput(uri)).addFlags(268435456));
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Can't find activity to switch to " + uri, e);
        }
    }

    private void switchToHomeTvInput() {
        try {
            Intent activityIntent = new Intent("android.intent.action.MAIN").addCategory("android.intent.category.HOME").setFlags(872480768);
            this.mService.getContext().startActivity(activityIntent);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Can't find activity to switch to HOME", e);
        }
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDeviceSource
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        int port = this.mService.pathToPortId(physicalAddress);
        if (port > 0) {
            return;
        }
        if (port < 0 && isSystemAudioActivated()) {
            handleRoutingChangeAndInformationForSystemAudio();
        } else if (port == 0) {
            handleRoutingChangeAndInformationForSwitch(message);
        }
    }

    private void handleRoutingChangeAndInformationForSystemAudio() {
        routeToInputFromPortId(17);
    }

    private void handleRoutingChangeAndInformationForSwitch(HdmiCecMessage message) {
        if (getRoutingPort() == 0 && this.mService.isPlaybackDevice()) {
            routeToInputFromPortId(0);
            this.mService.setAndBroadcastActiveSourceFromOneDeviceType(message.getSource(), this.mService.getPhysicalAddress());
            return;
        }
        int routingInformationPath = this.mService.portIdToPath(getRoutingPort());
        if (routingInformationPath == this.mService.getPhysicalAddress()) {
            HdmiLogger.debug("Current device can't assign valid physical addressto devices under it any more. It's physical address is " + routingInformationPath, new Object[0]);
            return;
        }
        this.mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingInformation(this.mAddress, routingInformationPath));
        routeToInputFromPortId(getRoutingPort());
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDeviceSource
    protected void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        HdmiDeviceInfo info = getCecDeviceInfo(logicalAddress);
        if (info == null) {
            Slog.w(TAG, "Can not update power status of non-existing device:" + logicalAddress);
        } else if (info.getDevicePowerStatus() == newPowerStatus) {
        } else {
            HdmiDeviceInfo newInfo = HdmiUtils.cloneHdmiDeviceInfo(info, newPowerStatus);
            addDeviceInfo(newInfo);
            invokeDeviceEventListener(newInfo, 3);
        }
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Device Discovery Action is in progress. Restarting.");
            removeAction(DeviceDiscoveryAction.class);
        }
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this, new DeviceDiscoveryAction.DeviceDiscoveryCallback() { // from class: com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.3
            @Override // com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback
            public void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos) {
                for (HdmiDeviceInfo info : deviceInfos) {
                    HdmiCecLocalDeviceAudioSystem.this.addCecDevice(info);
                }
            }
        });
        addAndStartAction(action);
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        for (HdmiDeviceInfo info : HdmiUtils.sparseArrayToList(this.mDeviceInfos)) {
            if (info.getPhysicalAddress() != this.mService.getPhysicalAddress()) {
                invokeDeviceEventListener(info, 2);
            }
        }
        this.mDeviceInfos.clear();
        updateSafeDeviceInfoList();
    }

    @Override // com.android.server.hdmi.HdmiCecLocalDevice
    protected void dump(IndentingPrintWriter pw) {
        pw.println("HdmiCecLocalDeviceAudioSystem:");
        pw.increaseIndent();
        pw.println("isRoutingFeatureEnabled " + isRoutingControlFeatureEnabled());
        pw.println("mSystemAudioControlFeatureEnabled: " + this.mSystemAudioControlFeatureEnabled);
        pw.println("mTvSystemAudioModeSupport: " + this.mTvSystemAudioModeSupport);
        pw.println("mArcEstablished: " + this.mArcEstablished);
        pw.println("mArcIntentUsed: " + this.mArcIntentUsed);
        pw.println("mRoutingPort: " + getRoutingPort());
        pw.println("mLocalActivePort: " + getLocalActivePort());
        HdmiUtils.dumpMap(pw, "mPortIdToTvInputs:", this.mPortIdToTvInputs);
        HdmiUtils.dumpMap(pw, "mTvInputsToDeviceInfo:", this.mTvInputsToDeviceInfo);
        HdmiUtils.dumpSparseArray(pw, "mDeviceInfos:", this.mDeviceInfos);
        pw.decreaseIndent();
        super.dump(pw);
    }
}
