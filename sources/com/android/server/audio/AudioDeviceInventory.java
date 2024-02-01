package com.android.server.audio;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioPort;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import com.android.server.audio.AudioDeviceInventory;
import com.android.server.audio.AudioEventLogger;
import com.android.server.audio.AudioServiceEvents;
import com.android.server.audio.BtHelper;
import com.android.server.pm.PackageManagerService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/* loaded from: classes.dex */
public class AudioDeviceInventory {
    private static final String CONNECT_INTENT_KEY_ADDRESS = "address";
    private static final String CONNECT_INTENT_KEY_DEVICE_CLASS = "class";
    private static final String CONNECT_INTENT_KEY_HAS_CAPTURE = "hasCapture";
    private static final String CONNECT_INTENT_KEY_HAS_MIDI = "hasMIDI";
    private static final String CONNECT_INTENT_KEY_HAS_PLAYBACK = "hasPlayback";
    private static final String CONNECT_INTENT_KEY_PORT_NAME = "portName";
    private static final String CONNECT_INTENT_KEY_STATE = "state";
    private static final int DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG = 67264524;
    private static final String TAG = "AS.AudioDeviceInventory";
    private int mBecomingNoisyIntentDevices;
    private final ArrayMap<String, DeviceInfo> mConnectedDevices;
    final AudioRoutesInfo mCurAudioRoutes;
    private AudioDeviceBroker mDeviceBroker;
    private String mDockAddress;
    final RemoteCallbackList<IAudioRoutesObserver> mRoutesObservers;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioDeviceInventory(AudioDeviceBroker broker) {
        this.mConnectedDevices = new ArrayMap<>();
        this.mCurAudioRoutes = new AudioRoutesInfo();
        this.mRoutesObservers = new RemoteCallbackList<>();
        this.mBecomingNoisyIntentDevices = 201490316;
        this.mDeviceBroker = broker;
    }

    AudioDeviceInventory() {
        this.mConnectedDevices = new ArrayMap<>();
        this.mCurAudioRoutes = new AudioRoutesInfo();
        this.mRoutesObservers = new RemoteCallbackList<>();
        this.mBecomingNoisyIntentDevices = 201490316;
        this.mDeviceBroker = null;
    }

    void setDeviceBroker(AudioDeviceBroker broker) {
        this.mDeviceBroker = broker;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class DeviceInfo {
        final String mDeviceAddress;
        int mDeviceCodecFormat;
        final String mDeviceName;
        final int mDeviceType;

        DeviceInfo(int deviceType, String deviceName, String deviceAddress, int deviceCodecFormat) {
            this.mDeviceType = deviceType;
            this.mDeviceName = deviceName;
            this.mDeviceAddress = deviceAddress;
            this.mDeviceCodecFormat = deviceCodecFormat;
        }

        public String toString() {
            return "[DeviceInfo: type:0x" + Integer.toHexString(this.mDeviceType) + " name:" + this.mDeviceName + " addr:" + this.mDeviceAddress + " codec: " + Integer.toHexString(this.mDeviceCodecFormat) + "]";
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static String makeDeviceListKey(int device, String deviceAddress) {
            return "0x" + Integer.toHexString(device) + ":" + deviceAddress;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class WiredDeviceConnectionState {
        public final String mAddress;
        public final String mCaller;
        public final String mName;
        public final int mState;
        public final int mType;

        WiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
            this.mType = type;
            this.mState = state;
            this.mAddress = address;
            this.mName = name;
            this.mCaller = caller;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onRestoreDevices() {
        synchronized (this.mConnectedDevices) {
            for (int i = 0; i < this.mConnectedDevices.size(); i++) {
                DeviceInfo di = this.mConnectedDevices.valueAt(i);
                AudioSystem.setDeviceConnectionState(di.mDeviceType, 1, di.mDeviceAddress, di.mDeviceName, di.mDeviceCodecFormat);
            }
        }
    }

    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    @VisibleForTesting
    public void onSetA2dpSinkConnectionState(BtHelper.BluetoothA2dpDeviceInfo btInfo, int state) {
        BluetoothDevice btDevice = btInfo.getBtDevice();
        int a2dpVolume = btInfo.getVolume();
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        int a2dpCodec = btInfo.getCodec();
        AudioEventLogger audioEventLogger = AudioService.sDeviceLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("A2DP sink connected: device addr=" + address + " state=" + state + " codec=" + a2dpCodec + " vol=" + a2dpVolume));
        synchronized (this.mConnectedDevices) {
            String key = DeviceInfo.makeDeviceListKey(128, btDevice.getAddress());
            DeviceInfo di = this.mConnectedDevices.get(key);
            boolean isConnected = di != null;
            if (isConnected) {
                if (state == 2) {
                    if (a2dpCodec != di.mDeviceCodecFormat) {
                        this.mDeviceBroker.postBluetoothA2dpDeviceConfigChange(btDevice);
                    }
                } else if (btDevice.isBluetoothDock()) {
                    if (state == 0) {
                        lambda$disconnectA2dp$1$AudioDeviceInventory(address, EventLogTags.JOB_DEFERRED_EXECUTION);
                    }
                } else {
                    makeA2dpDeviceUnavailableNow(address, di.mDeviceCodecFormat);
                }
            } else if (!isConnected && state == 2) {
                if (btDevice.isBluetoothDock()) {
                    this.mDeviceBroker.cancelA2dpDockTimeout();
                    this.mDockAddress = address;
                } else if (this.mDeviceBroker.hasScheduledA2dpDockTimeout() && this.mDockAddress != null) {
                    this.mDeviceBroker.cancelA2dpDockTimeout();
                    makeA2dpDeviceUnavailableNow(this.mDockAddress, 0);
                }
                if (a2dpVolume != -1) {
                    this.mDeviceBroker.postSetVolumeIndexOnDevice(3, a2dpVolume * 10, 128, "onSetA2dpSinkConnectionState");
                }
                makeA2dpDeviceAvailable(address, BtHelper.getName(btDevice), "onSetA2dpSinkConnectionState", a2dpCodec);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSetA2dpSourceConnectionState(BtHelper.BluetoothA2dpDeviceInfo btInfo, int state) {
        BluetoothDevice btDevice = btInfo.getBtDevice();
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        synchronized (this.mConnectedDevices) {
            String key = DeviceInfo.makeDeviceListKey(-2147352576, address);
            DeviceInfo di = this.mConnectedDevices.get(key);
            boolean isConnected = di != null;
            if (isConnected && state != 2) {
                lambda$disconnectA2dpSink$3$AudioDeviceInventory(address);
            } else if (!isConnected && state == 2) {
                makeA2dpSrcAvailable(address);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSetHearingAidConnectionState(BluetoothDevice btDevice, int state, int streamType) {
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        AudioEventLogger audioEventLogger = AudioService.sDeviceLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("onSetHearingAidConnectionState addr=" + address));
        synchronized (this.mConnectedDevices) {
            String key = DeviceInfo.makeDeviceListKey(134217728, btDevice.getAddress());
            DeviceInfo di = this.mConnectedDevices.get(key);
            boolean isConnected = di != null;
            if (isConnected && state != 2) {
                lambda$disconnectHearingAid$5$AudioDeviceInventory(address);
            } else if (!isConnected && state == 2) {
                makeHearingAidDeviceAvailable(address, BtHelper.getName(btDevice), streamType, "onSetHearingAidConnectionState");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public void onBluetoothA2dpActiveDeviceChange(BtHelper.BluetoothA2dpDeviceInfo btInfo, int event) {
        String address;
        BluetoothDevice btDevice = btInfo.getBtDevice();
        if (btDevice == null) {
            return;
        }
        int a2dpVolume = btInfo.getVolume();
        int a2dpCodec = btInfo.getCodec();
        String address2 = btDevice.getAddress();
        if (BluetoothAdapter.checkBluetoothAddress(address2)) {
            address = address2;
        } else {
            address = "";
        }
        AudioEventLogger audioEventLogger = AudioService.sDeviceLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("onBluetoothA2dpActiveDeviceChange addr=" + address + " event=" + BtHelper.a2dpDeviceEventToString(event)));
        synchronized (this.mConnectedDevices) {
            if (!this.mDeviceBroker.hasScheduledA2dpSinkConnectionState(btDevice)) {
                String key = DeviceInfo.makeDeviceListKey(128, address);
                DeviceInfo di = this.mConnectedDevices.get(key);
                if (di == null) {
                    Log.e(TAG, "invalid null DeviceInfo in onBluetoothA2dpActiveDeviceChange");
                    return;
                }
                if (event == 1) {
                    if (a2dpVolume != -1) {
                        this.mDeviceBroker.postSetVolumeIndexOnDevice(3, a2dpVolume * 10, 128, "onBluetoothA2dpActiveDeviceChange");
                    }
                } else if (event == 0 && di.mDeviceCodecFormat != a2dpCodec) {
                    di.mDeviceCodecFormat = a2dpCodec;
                    this.mConnectedDevices.replace(key, di);
                }
                if (AudioSystem.handleDeviceConfigChange(128, address, BtHelper.getName(btDevice), a2dpCodec) != 0) {
                    int musicDevice = this.mDeviceBroker.getDeviceForStream(3);
                    setBluetoothA2dpDeviceConnectionState(btDevice, 0, 2, false, musicDevice, -1);
                }
                return;
            }
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("A2dp config change ignored (scheduled connection change)"));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMakeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        synchronized (this.mConnectedDevices) {
            makeA2dpDeviceUnavailableNow(address, a2dpCodec);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onReportNewRoutes() {
        AudioRoutesInfo routes;
        int n = this.mRoutesObservers.beginBroadcast();
        if (n > 0) {
            synchronized (this.mCurAudioRoutes) {
                routes = new AudioRoutesInfo(this.mCurAudioRoutes);
            }
            while (n > 0) {
                n--;
                IAudioRoutesObserver obs = this.mRoutesObservers.getBroadcastItem(n);
                try {
                    obs.dispatchAudioRoutesChanged(routes);
                } catch (RemoteException e) {
                }
            }
        }
        this.mRoutesObservers.finishBroadcast();
        this.mDeviceBroker.postObserveDevicesForAllStreams();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSetWiredDeviceConnectionState(WiredDeviceConnectionState wdcs) {
        AudioService.sDeviceLogger.log(new AudioServiceEvents.WiredDevConnectEvent(wdcs));
        synchronized (this.mConnectedDevices) {
            boolean z = true;
            if (wdcs.mState == 0 && (wdcs.mType & DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG) != 0) {
                this.mDeviceBroker.setBluetoothA2dpOnInt(true, "onSetWiredDeviceConnectionState state DISCONNECTED");
            }
            if (wdcs.mState != 1) {
                z = false;
            }
            if (handleDeviceConnection(z, wdcs.mType, wdcs.mAddress, wdcs.mName)) {
                if (wdcs.mState != 0) {
                    if ((wdcs.mType & DEVICE_OVERRIDE_A2DP_ROUTE_ON_PLUG) != 0) {
                        this.mDeviceBroker.setBluetoothA2dpOnInt(false, "onSetWiredDeviceConnectionState state not DISCONNECTED");
                    }
                    this.mDeviceBroker.checkMusicActive(wdcs.mType, wdcs.mCaller);
                }
                if (wdcs.mType == 1024) {
                    this.mDeviceBroker.checkVolumeCecOnHdmiConnection(wdcs.mState, wdcs.mCaller);
                }
                sendDeviceConnectionIntent(wdcs.mType, wdcs.mState, wdcs.mAddress, wdcs.mName);
                updateAudioRoutes(wdcs.mType, wdcs.mState);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onToggleHdmi() {
        synchronized (this.mConnectedDevices) {
            String key = DeviceInfo.makeDeviceListKey(1024, "");
            DeviceInfo di = this.mConnectedDevices.get(key);
            if (di == null) {
                Log.e(TAG, "invalid null DeviceInfo in onToggleHdmi");
                return;
            }
            setWiredDeviceConnectionState(1024, 0, "", "", PackageManagerService.PLATFORM_PACKAGE_NAME);
            setWiredDeviceConnectionState(1024, 1, "", "", PackageManagerService.PLATFORM_PACKAGE_NAME);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleDeviceConnection(boolean connect, int device, String address, String deviceName) {
        synchronized (this.mConnectedDevices) {
            String deviceKey = DeviceInfo.makeDeviceListKey(device, address);
            DeviceInfo di = this.mConnectedDevices.get(deviceKey);
            boolean isConnected = di != null;
            if (connect && !isConnected) {
                int res = AudioSystem.setDeviceConnectionState(device, 1, address, deviceName, 0);
                if (res != 0) {
                    Slog.e(TAG, "not connecting device 0x" + Integer.toHexString(device) + " due to command error " + res);
                    return false;
                }
                this.mConnectedDevices.put(deviceKey, new DeviceInfo(device, deviceName, address, 0));
                this.mDeviceBroker.postAccessoryPlugMediaUnmute(device);
                return true;
            } else if (!connect && isConnected) {
                AudioSystem.setDeviceConnectionState(device, 0, address, deviceName, 0);
                this.mConnectedDevices.remove(deviceKey);
                return true;
            } else {
                Log.w(TAG, "handleDeviceConnection() failed, deviceKey=" + deviceKey + ", deviceSpec=" + di + ", connect=" + connect);
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disconnectA2dp() {
        synchronized (this.mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            this.mConnectedDevices.values().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$y5XThSW6MLia8Z0qpQToEJpUJk0
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AudioDeviceInventory.lambda$disconnectA2dp$0(toRemove, (AudioDeviceInventory.DeviceInfo) obj);
                }
            });
            if (toRemove.size() > 0) {
                final int delay = checkSendBecomingNoisyIntentInt(128, 0, 0);
                toRemove.stream().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$qhWWEgLKYMNfyt5ffemHAtlRkpw
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        AudioDeviceInventory.this.lambda$disconnectA2dp$1$AudioDeviceInventory(delay, (String) obj);
                    }
                });
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$disconnectA2dp$0(ArraySet toRemove, DeviceInfo deviceInfo) {
        if (deviceInfo.mDeviceType == 128) {
            toRemove.add(deviceInfo.mDeviceAddress);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disconnectA2dpSink() {
        synchronized (this.mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            this.mConnectedDevices.values().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$0fz2EAO4sH309I_0WQlshYm7ShE
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AudioDeviceInventory.lambda$disconnectA2dpSink$2(toRemove, (AudioDeviceInventory.DeviceInfo) obj);
                }
            });
            toRemove.stream().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$xRa5RyFQAe2dGU7YDh18NalwMMg
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AudioDeviceInventory.this.lambda$disconnectA2dpSink$3$AudioDeviceInventory((String) obj);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$disconnectA2dpSink$2(ArraySet toRemove, DeviceInfo deviceInfo) {
        if (deviceInfo.mDeviceType == -2147352576) {
            toRemove.add(deviceInfo.mDeviceAddress);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disconnectHearingAid() {
        synchronized (this.mConnectedDevices) {
            final ArraySet<String> toRemove = new ArraySet<>();
            this.mConnectedDevices.values().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$nQz4ldQjburNlVucAV7ieYoic28
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AudioDeviceInventory.lambda$disconnectHearingAid$4(toRemove, (AudioDeviceInventory.DeviceInfo) obj);
                }
            });
            if (toRemove.size() > 0) {
                checkSendBecomingNoisyIntentInt(134217728, 0, 0);
                toRemove.stream().forEach(new Consumer() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$9yZUrl4jHdQ7A-5G79yQVDbYVSI
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        AudioDeviceInventory.this.lambda$disconnectHearingAid$5$AudioDeviceInventory((String) obj);
                    }
                });
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$disconnectHearingAid$4(ArraySet toRemove, DeviceInfo deviceInfo) {
        if (deviceInfo.mDeviceType == 134217728) {
            toRemove.add(deviceInfo.mDeviceAddress);
        }
    }

    int checkSendBecomingNoisyIntent(int device, int state, int musicDevice) {
        int checkSendBecomingNoisyIntentInt;
        synchronized (this.mConnectedDevices) {
            checkSendBecomingNoisyIntentInt = checkSendBecomingNoisyIntentInt(device, state, musicDevice);
        }
        return checkSendBecomingNoisyIntentInt;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        AudioRoutesInfo routes;
        synchronized (this.mCurAudioRoutes) {
            routes = new AudioRoutesInfo(this.mCurAudioRoutes);
            this.mRoutesObservers.register(observer);
        }
        return routes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioRoutesInfo getCurAudioRoutes() {
        return this.mCurAudioRoutes;
    }

    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    @VisibleForTesting
    public void setBluetoothA2dpDeviceConnectionState(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int musicDevice, int a2dpVolume) {
        if (profile != 2 && profile != 11) {
            throw new IllegalArgumentException("invalid profile " + profile);
        }
        synchronized (this.mConnectedDevices) {
            int asState = 0;
            if (profile == 2 && !suppressNoisyIntent) {
                if (state == 2) {
                    asState = 1;
                }
                asState = checkSendBecomingNoisyIntentInt(128, asState, musicDevice);
            }
            int a2dpCodec = this.mDeviceBroker.getA2dpCodec(device);
            BtHelper.BluetoothA2dpDeviceInfo a2dpDeviceInfo = new BtHelper.BluetoothA2dpDeviceInfo(device, a2dpVolume, a2dpCodec);
            if (profile == 2) {
                if (asState == 0) {
                    onSetA2dpSinkConnectionState(a2dpDeviceInfo, state);
                } else {
                    this.mDeviceBroker.postA2dpSinkConnection(state, a2dpDeviceInfo, asState);
                }
            } else {
                this.mDeviceBroker.postA2dpSourceConnection(state, a2dpDeviceInfo, asState);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int setWiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
        int delay;
        synchronized (this.mConnectedDevices) {
            delay = checkSendBecomingNoisyIntentInt(type, state, 0);
            this.mDeviceBroker.postSetWiredDeviceConnectionState(new WiredDeviceConnectionState(type, state, address, name, caller), delay);
        }
        return delay;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int setBluetoothHearingAidDeviceConnectionState(BluetoothDevice device, int state, boolean suppressNoisyIntent, int musicDevice) {
        int intState;
        synchronized (this.mConnectedDevices) {
            if (!suppressNoisyIntent) {
                intState = state == 2 ? 1 : 0;
                intState = checkSendBecomingNoisyIntentInt(134217728, intState, musicDevice);
            }
            this.mDeviceBroker.postSetHearingAidConnectionState(state, device, intState);
        }
        return intState;
    }

    @GuardedBy({"mConnectedDevices"})
    private void makeA2dpDeviceAvailable(String address, String name, String eventSource, int a2dpCodec) {
        this.mDeviceBroker.setBluetoothA2dpOnInt(true, eventSource);
        AudioSystem.setDeviceConnectionState(128, 1, address, name, a2dpCodec);
        AudioSystem.setParameters("A2dpSuspended=false");
        this.mConnectedDevices.put(DeviceInfo.makeDeviceListKey(128, address), new DeviceInfo(128, name, address, a2dpCodec));
        this.mDeviceBroker.postAccessoryPlugMediaUnmute(128);
        setCurrentAudioRouteNameIfPossible(name);
    }

    @GuardedBy({"mConnectedDevices"})
    private void makeA2dpDeviceUnavailableNow(String address, int a2dpCodec) {
        if (address == null) {
            return;
        }
        this.mDeviceBroker.setAvrcpAbsoluteVolumeSupported(false);
        AudioSystem.setDeviceConnectionState(128, 0, address, "", a2dpCodec);
        this.mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(128, address));
        setCurrentAudioRouteNameIfPossible(null);
        if (this.mDockAddress == address) {
            this.mDockAddress = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mConnectedDevices"})
    /* renamed from: makeA2dpDeviceUnavailableLater */
    public void lambda$disconnectA2dp$1$AudioDeviceInventory(String address, int delayMs) {
        AudioSystem.setParameters("A2dpSuspended=true");
        String deviceKey = DeviceInfo.makeDeviceListKey(128, address);
        DeviceInfo deviceInfo = this.mConnectedDevices.get(deviceKey);
        int a2dpCodec = deviceInfo != null ? deviceInfo.mDeviceCodecFormat : 0;
        this.mConnectedDevices.remove(deviceKey);
        this.mDeviceBroker.setA2dpDockTimeout(address, a2dpCodec, delayMs);
    }

    @GuardedBy({"mConnectedDevices"})
    private void makeA2dpSrcAvailable(String address) {
        AudioSystem.setDeviceConnectionState(-2147352576, 1, address, "", 0);
        this.mConnectedDevices.put(DeviceInfo.makeDeviceListKey(-2147352576, address), new DeviceInfo(-2147352576, "", address, 0));
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mConnectedDevices"})
    /* renamed from: makeA2dpSrcUnavailable */
    public void lambda$disconnectA2dpSink$3$AudioDeviceInventory(String address) {
        AudioSystem.setDeviceConnectionState(-2147352576, 0, address, "", 0);
        this.mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(-2147352576, address));
    }

    @GuardedBy({"mConnectedDevices"})
    private void makeHearingAidDeviceAvailable(String address, String name, int streamType, String eventSource) {
        int hearingAidVolIndex = this.mDeviceBroker.getVssVolumeForDevice(streamType, 134217728);
        this.mDeviceBroker.postSetHearingAidVolumeIndex(hearingAidVolIndex, streamType);
        AudioSystem.setDeviceConnectionState(134217728, 1, address, name, 0);
        this.mConnectedDevices.put(DeviceInfo.makeDeviceListKey(134217728, address), new DeviceInfo(134217728, name, address, 0));
        this.mDeviceBroker.postAccessoryPlugMediaUnmute(134217728);
        this.mDeviceBroker.postApplyVolumeOnDevice(streamType, 134217728, "makeHearingAidDeviceAvailable");
        setCurrentAudioRouteNameIfPossible(name);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mConnectedDevices"})
    /* renamed from: makeHearingAidDeviceUnavailable */
    public void lambda$disconnectHearingAid$5$AudioDeviceInventory(String address) {
        AudioSystem.setDeviceConnectionState(134217728, 0, address, "", 0);
        this.mConnectedDevices.remove(DeviceInfo.makeDeviceListKey(134217728, address));
        setCurrentAudioRouteNameIfPossible(null);
    }

    @GuardedBy({"mConnectedDevices"})
    private void setCurrentAudioRouteNameIfPossible(String name) {
        synchronized (this.mCurAudioRoutes) {
            if (TextUtils.equals(this.mCurAudioRoutes.bluetoothName, name)) {
                return;
            }
            if (name != null || !isCurrentDeviceConnected()) {
                this.mCurAudioRoutes.bluetoothName = name;
                this.mDeviceBroker.postReportNewRoutes();
            }
        }
    }

    @GuardedBy({"mConnectedDevices"})
    private boolean isCurrentDeviceConnected() {
        return this.mConnectedDevices.values().stream().anyMatch(new Predicate() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$MfLl81BWvF9OIWh52LJfesOjVdw
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return AudioDeviceInventory.this.lambda$isCurrentDeviceConnected$6$AudioDeviceInventory((AudioDeviceInventory.DeviceInfo) obj);
            }
        });
    }

    public /* synthetic */ boolean lambda$isCurrentDeviceConnected$6$AudioDeviceInventory(DeviceInfo deviceInfo) {
        return TextUtils.equals(deviceInfo.mDeviceName, this.mCurAudioRoutes.bluetoothName);
    }

    @GuardedBy({"mConnectedDevices"})
    private int checkSendBecomingNoisyIntentInt(int device, int state, int musicDevice) {
        if (state != 0 || (this.mBecomingNoisyIntentDevices & device) == 0) {
            return 0;
        }
        int devices = 0;
        for (int i = 0; i < this.mConnectedDevices.size(); i++) {
            int dev = this.mConnectedDevices.valueAt(i).mDeviceType;
            if ((Integer.MIN_VALUE & dev) == 0 && (this.mBecomingNoisyIntentDevices & dev) != 0) {
                devices |= dev;
            }
        }
        if (musicDevice == 0) {
            musicDevice = this.mDeviceBroker.getDeviceForStream(3);
        }
        if ((device != musicDevice && !this.mDeviceBroker.isInCommunication()) || device != devices || this.mDeviceBroker.hasMediaDynamicPolicy() || (32768 & musicDevice) != 0) {
            return 0;
        }
        if (!AudioSystem.isStreamActive(3, 0) && !this.mDeviceBroker.hasAudioFocusUsers()) {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("dropping ACTION_AUDIO_BECOMING_NOISY").printLog(TAG));
            return 0;
        }
        this.mDeviceBroker.postBroadcastBecomingNoisy();
        return 1000;
    }

    private void sendDeviceConnectionIntent(int device, int state, String address, String deviceName) {
        Intent intent = new Intent();
        if (device != -2113929216) {
            if (device == 4) {
                intent.setAction("android.intent.action.HEADSET_PLUG");
                intent.putExtra("microphone", 1);
            } else {
                if (device != 8) {
                    if (device != 1024) {
                        if (device != 131072) {
                            if (device != 262144) {
                                if (device == 67108864) {
                                    intent.setAction("android.intent.action.HEADSET_PLUG");
                                    intent.putExtra("microphone", AudioSystem.getDeviceConnectionState(-2113929216, "") != 1 ? 0 : 1);
                                }
                            }
                        }
                    }
                    configureHdmiPlugIntent(intent, state);
                }
                intent.setAction("android.intent.action.HEADSET_PLUG");
                intent.putExtra("microphone", 0);
            }
        } else if (AudioSystem.getDeviceConnectionState(67108864, "") == 1) {
            intent.setAction("android.intent.action.HEADSET_PLUG");
            intent.putExtra("microphone", 1);
        } else {
            return;
        }
        if (intent.getAction() == null) {
            return;
        }
        intent.putExtra(CONNECT_INTENT_KEY_STATE, state);
        intent.putExtra(CONNECT_INTENT_KEY_ADDRESS, address);
        intent.putExtra(CONNECT_INTENT_KEY_PORT_NAME, deviceName);
        intent.addFlags(1073741824);
        long ident = Binder.clearCallingIdentity();
        try {
            ActivityManager.broadcastStickyIntent(intent, -2);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x001a, code lost:
        if (r5 != 67108864) goto L15;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void updateAudioRoutes(int r5, int r6) {
        /*
            r4 = this;
            r0 = 0
            r1 = 4
            if (r5 == r1) goto L25
            r1 = 8
            if (r5 == r1) goto L23
            r1 = 1024(0x400, float:1.435E-42)
            if (r5 == r1) goto L20
            r1 = 16384(0x4000, float:2.2959E-41)
            if (r5 == r1) goto L1d
            r1 = 131072(0x20000, float:1.83671E-40)
            if (r5 == r1) goto L23
            r1 = 262144(0x40000, float:3.67342E-40)
            if (r5 == r1) goto L20
            r1 = 67108864(0x4000000, float:1.5046328E-36)
            if (r5 == r1) goto L1d
            goto L27
        L1d:
            r0 = 16
            goto L27
        L20:
            r0 = 8
            goto L27
        L23:
            r0 = 2
            goto L27
        L25:
            r0 = 1
        L27:
            android.media.AudioRoutesInfo r1 = r4.mCurAudioRoutes
            monitor-enter(r1)
            if (r0 != 0) goto L2e
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L49
            return
        L2e:
            android.media.AudioRoutesInfo r2 = r4.mCurAudioRoutes     // Catch: java.lang.Throwable -> L49
            int r2 = r2.mainType     // Catch: java.lang.Throwable -> L49
            if (r6 == 0) goto L36
            r2 = r2 | r0
            goto L38
        L36:
            int r3 = ~r0     // Catch: java.lang.Throwable -> L49
            r2 = r2 & r3
        L38:
            android.media.AudioRoutesInfo r3 = r4.mCurAudioRoutes     // Catch: java.lang.Throwable -> L49
            int r3 = r3.mainType     // Catch: java.lang.Throwable -> L49
            if (r2 == r3) goto L47
            android.media.AudioRoutesInfo r3 = r4.mCurAudioRoutes     // Catch: java.lang.Throwable -> L49
            r3.mainType = r2     // Catch: java.lang.Throwable -> L49
            com.android.server.audio.AudioDeviceBroker r3 = r4.mDeviceBroker     // Catch: java.lang.Throwable -> L49
            r3.postReportNewRoutes()     // Catch: java.lang.Throwable -> L49
        L47:
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L49
            return
        L49:
            r2 = move-exception
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L49
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.AudioDeviceInventory.updateAudioRoutes(int, int):void");
    }

    private void configureHdmiPlugIntent(Intent intent, int state) {
        int[] channelMasks;
        intent.setAction("android.media.action.HDMI_AUDIO_PLUG");
        intent.putExtra("android.media.extra.AUDIO_PLUG_STATE", state);
        if (state != 1) {
            return;
        }
        ArrayList<AudioPort> ports = new ArrayList<>();
        int[] portGeneration = new int[1];
        int status = AudioSystem.listAudioPorts(ports, portGeneration);
        if (status != 0) {
            Log.e(TAG, "listAudioPorts error " + status + " in configureHdmiPlugIntent");
            return;
        }
        Iterator<AudioPort> it = ports.iterator();
        while (it.hasNext()) {
            AudioPort next = it.next();
            if (next instanceof AudioDevicePort) {
                AudioDevicePort devicePort = (AudioDevicePort) next;
                if (devicePort.type() == 1024 || devicePort.type() == 262144) {
                    int[] formats = AudioFormat.filterPublicFormats(devicePort.formats());
                    if (formats.length > 0) {
                        ArrayList<Integer> encodingList = new ArrayList<>(1);
                        for (int format : formats) {
                            if (format != 0) {
                                encodingList.add(Integer.valueOf(format));
                            }
                        }
                        int[] encodingArray = encodingList.stream().mapToInt(new ToIntFunction() { // from class: com.android.server.audio.-$$Lambda$AudioDeviceInventory$u_r8SlQF9hKqpPB7hUtp-bqyzdc
                            @Override // java.util.function.ToIntFunction
                            public final int applyAsInt(Object obj) {
                                int intValue;
                                intValue = ((Integer) obj).intValue();
                                return intValue;
                            }
                        }).toArray();
                        intent.putExtra("android.media.extra.ENCODINGS", encodingArray);
                    }
                    int maxChannels = 0;
                    for (int mask : devicePort.channelMasks()) {
                        int channelCount = AudioFormat.channelCountFromOutChannelMask(mask);
                        if (channelCount > maxChannels) {
                            maxChannels = channelCount;
                        }
                    }
                    intent.putExtra("android.media.extra.MAX_CHANNEL_COUNT", maxChannels);
                }
            }
        }
    }

    @VisibleForTesting
    public boolean isA2dpDeviceConnected(BluetoothDevice device) {
        boolean z;
        String key = DeviceInfo.makeDeviceListKey(128, device.getAddress());
        synchronized (this.mConnectedDevices) {
            z = this.mConnectedDevices.get(key) != null;
        }
        return z;
    }
}
