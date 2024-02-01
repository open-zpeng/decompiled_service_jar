package com.android.server.audio;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Intent;
import android.media.AudioSystem;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.AudioEventLogger;
import com.android.server.audio.AudioServiceEvents;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/* loaded from: classes.dex */
public class BtHelper {
    private static final int BT_HEARING_AID_GAIN_MIN = -128;
    static final int EVENT_ACTIVE_DEVICE_CHANGE = 1;
    static final int EVENT_DEVICE_CONFIG_CHANGE = 0;
    private static final int SCO_MODE_MAX = 2;
    private static final int SCO_MODE_RAW = 1;
    static final int SCO_MODE_UNDEFINED = -1;
    static final int SCO_MODE_VIRTUAL_CALL = 0;
    private static final int SCO_MODE_VR = 2;
    private static final int SCO_STATE_ACTIVATE_REQ = 1;
    private static final int SCO_STATE_ACTIVE_EXTERNAL = 2;
    private static final int SCO_STATE_ACTIVE_INTERNAL = 3;
    private static final int SCO_STATE_DEACTIVATE_REQ = 4;
    private static final int SCO_STATE_DEACTIVATING = 5;
    private static final int SCO_STATE_INACTIVE = 0;
    private static final String TAG = "AS.BtHelper";
    private BluetoothA2dp mA2dp;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothDevice mBluetoothHeadsetDevice;
    private final AudioDeviceBroker mDeviceBroker;
    private BluetoothHearingAid mHearingAid;
    private int mScoAudioMode;
    private int mScoAudioState;
    private int mScoConnectionState;
    private final ArrayList<ScoClient> mScoClients = new ArrayList<>();
    private boolean mAvrcpAbsVolSupported = false;
    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener = new BluetoothProfile.ServiceListener() { // from class: com.android.server.audio.BtHelper.1
        @Override // android.bluetooth.BluetoothProfile.ServiceListener
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == 1) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("BT profile service: connecting HEADSET profile"));
                BtHelper.this.mDeviceBroker.postBtHeasetProfileConnected((BluetoothHeadset) proxy);
            } else if (profile == 2) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("BT profile service: connecting A2DP profile"));
                BtHelper.this.mDeviceBroker.postBtA2dpProfileConnected((BluetoothA2dp) proxy);
            } else if (profile == 11) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("BT profile service: connecting A2DP_SINK profile"));
                BtHelper.this.mDeviceBroker.postBtA2dpSinkProfileConnected(proxy);
            } else if (profile == 21) {
                AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("BT profile service: connecting HEARING_AID profile"));
                BtHelper.this.mDeviceBroker.postBtHearingAidProfileConnected((BluetoothHearingAid) proxy);
            }
        }

        @Override // android.bluetooth.BluetoothProfile.ServiceListener
        public void onServiceDisconnected(int profile) {
            if (profile == 1) {
                BtHelper.this.mDeviceBroker.postDisconnectHeadset();
            } else if (profile == 2) {
                BtHelper.this.mDeviceBroker.postDisconnectA2dp();
            } else if (profile == 11) {
                BtHelper.this.mDeviceBroker.postDisconnectA2dpSink();
            } else if (profile == 21) {
                BtHelper.this.mDeviceBroker.postDisconnectHearingAid();
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    public BtHelper(AudioDeviceBroker broker) {
        this.mDeviceBroker = broker;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class BluetoothA2dpDeviceInfo {
        private final BluetoothDevice mBtDevice;
        private final int mCodec;
        private final int mVolume;

        /* JADX INFO: Access modifiers changed from: package-private */
        public BluetoothA2dpDeviceInfo(BluetoothDevice btDevice) {
            this(btDevice, -1, 0);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public BluetoothA2dpDeviceInfo(BluetoothDevice btDevice, int volume, int codec) {
            this.mBtDevice = btDevice;
            this.mVolume = volume;
            this.mCodec = codec;
        }

        public BluetoothDevice getBtDevice() {
            return this.mBtDevice;
        }

        public int getVolume() {
            return this.mVolume;
        }

        public int getCodec() {
            return this.mCodec;
        }

        public boolean equals(Object o) {
            return this.mBtDevice.equals(o);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String a2dpDeviceEventToString(int event) {
        if (event != 0) {
            if (event == 1) {
                return "ACTIVE_DEVICE_CHANGE";
            }
            return new String("invalid event:" + event);
        }
        return "DEVICE_CONFIG_CHANGE";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String getName(BluetoothDevice device) {
        String deviceName = device.getName();
        if (deviceName == null) {
            return "";
        }
        return deviceName;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void onSystemReady() {
        this.mScoConnectionState = -1;
        resetBluetoothSco();
        getBluetoothHeadset();
        Intent newIntent = new Intent("android.media.SCO_AUDIO_STATE_CHANGED");
        newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", 0);
        sendStickyBroadcastToAll(newIntent);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(this.mDeviceBroker.getContext(), this.mBluetoothProfileServiceListener, 2);
            adapter.getProfileProxy(this.mDeviceBroker.getContext(), this.mBluetoothProfileServiceListener, 21);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onAudioServerDiedRestoreA2dp() {
        int forMed = this.mDeviceBroker.getBluetoothA2dpEnabled() ? 0 : 10;
        this.mDeviceBroker.setForceUse_Async(1, forMed, "onAudioServerDied()");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized boolean isAvrcpAbsoluteVolumeSupported() {
        boolean z;
        if (this.mA2dp != null) {
            z = this.mAvrcpAbsVolSupported;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        this.mAvrcpAbsVolSupported = supported;
        Log.i(TAG, "setAvrcpAbsoluteVolumeSupported supported=" + supported);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void setAvrcpAbsoluteVolumeIndex(int index) {
        if (this.mA2dp == null) {
            AudioService.sVolumeLogger.log(new AudioEventLogger.StringEvent("setAvrcpAbsoluteVolumeIndex: bailing due to null mA2dp").printLog(TAG));
        } else if (!this.mAvrcpAbsVolSupported) {
            AudioService.sVolumeLogger.log(new AudioEventLogger.StringEvent("setAvrcpAbsoluteVolumeIndex: abs vol not supported ").printLog(TAG));
        } else {
            Log.i(TAG, "setAvrcpAbsoluteVolumeIndex index=" + index);
            AudioService.sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(4, index));
            this.mA2dp.setAvrcpAbsoluteVolume(index);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized int getA2dpCodec(BluetoothDevice device) {
        if (this.mA2dp == null) {
            return 0;
        }
        BluetoothCodecStatus btCodecStatus = this.mA2dp.getCodecStatus(device);
        if (btCodecStatus == null) {
            return 0;
        }
        BluetoothCodecConfig btCodecConfig = btCodecStatus.getCodecConfig();
        if (btCodecConfig == null) {
            return 0;
        }
        return mapBluetoothCodecToAudioFormat(btCodecConfig.getCodecType());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void receiveBtEvent(Intent intent) {
        String action = intent.getAction();
        if (action.equals("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED")) {
            BluetoothDevice btDevice = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
            setBtScoActiveDevice(btDevice);
        } else if (action.equals("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED")) {
            boolean broadcast = false;
            int scoAudioState = -1;
            int btState = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
            boolean z = true;
            if (!this.mScoClients.isEmpty() && (this.mScoAudioState == 3 || this.mScoAudioState == 1 || this.mScoAudioState == 4 || this.mScoAudioState == 5)) {
                broadcast = true;
            }
            switch (btState) {
                case 10:
                    this.mDeviceBroker.setBluetoothScoOn(false, "BtHelper.receiveBtEvent");
                    scoAudioState = 0;
                    if (this.mScoAudioState == 1 && this.mBluetoothHeadset != null && this.mBluetoothHeadsetDevice != null && connectBluetoothScoAudioHelper(this.mBluetoothHeadset, this.mBluetoothHeadsetDevice, this.mScoAudioMode)) {
                        this.mScoAudioState = 3;
                        broadcast = false;
                        break;
                    } else {
                        if (this.mScoAudioState != 3) {
                            z = false;
                        }
                        clearAllScoClients(0, z);
                        this.mScoAudioState = 0;
                        break;
                    }
                    break;
                case 11:
                    if (this.mScoAudioState != 3 && this.mScoAudioState != 4) {
                        this.mScoAudioState = 2;
                        break;
                    }
                    break;
                case 12:
                    scoAudioState = 1;
                    if (this.mScoAudioState != 3 && this.mScoAudioState != 4) {
                        this.mScoAudioState = 2;
                    }
                    this.mDeviceBroker.setBluetoothScoOn(true, "BtHelper.receiveBtEvent");
                    break;
                default:
                    broadcast = false;
                    break;
            }
            if (broadcast) {
                broadcastScoConnectionState(scoAudioState);
                Intent newIntent = new Intent("android.media.SCO_AUDIO_STATE_CHANGED");
                newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", scoAudioState);
                sendStickyBroadcastToAll(newIntent);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized boolean isBluetoothScoOn() {
        if (this.mBluetoothHeadset != null && this.mBluetoothHeadset.getAudioState(this.mBluetoothHeadsetDevice) != 12) {
            Log.w(TAG, "isBluetoothScoOn(true) returning false because " + this.mBluetoothHeadsetDevice + " is not in audio connected mode");
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void disconnectBluetoothSco(int exceptPid) {
        checkScoAudioState();
        if (this.mScoAudioState == 2) {
            return;
        }
        clearAllScoClients(exceptPid, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void startBluetoothScoForClient(IBinder cb, int scoAudioMode, String eventSource) {
        ScoClient client = getScoClient(cb, true);
        long ident = Binder.clearCallingIdentity();
        try {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource + " client count before=" + client.getCount()));
            client.incCount(scoAudioMode);
        } catch (NullPointerException e) {
            Log.e(TAG, "Null ScoClient", e);
        }
        Binder.restoreCallingIdentity(ident);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void stopBluetoothScoForClient(IBinder cb, String eventSource) {
        ScoClient client = getScoClient(cb, false);
        long ident = Binder.clearCallingIdentity();
        if (client != null) {
            AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent(eventSource + " client count before=" + client.getCount()));
            client.decCount();
        }
        Binder.restoreCallingIdentity(ident);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void setHearingAidVolume(int index, int streamType) {
        if (this.mHearingAid == null) {
            Log.i(TAG, "setHearingAidVolume: null mHearingAid");
            return;
        }
        int gainDB = (int) AudioSystem.getStreamVolumeDB(streamType, index / 10, 134217728);
        if (gainDB < -128) {
            gainDB = -128;
        }
        Log.i(TAG, "setHearingAidVolume: calling mHearingAid.setVolume idx=" + index + " gain=" + gainDB);
        AudioService.sVolumeLogger.log(new AudioServiceEvents.VolumeEvent(3, index, gainDB));
        this.mHearingAid.setVolume(gainDB);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onBroadcastScoConnectionState(int state) {
        if (state == this.mScoConnectionState) {
            return;
        }
        Intent newIntent = new Intent("android.media.ACTION_SCO_AUDIO_STATE_UPDATED");
        newIntent.putExtra("android.media.extra.SCO_AUDIO_STATE", state);
        newIntent.putExtra("android.media.extra.SCO_AUDIO_PREVIOUS_STATE", this.mScoConnectionState);
        sendStickyBroadcastToAll(newIntent);
        this.mScoConnectionState = state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void disconnectAllBluetoothProfiles() {
        this.mDeviceBroker.postDisconnectA2dp();
        this.mDeviceBroker.postDisconnectA2dpSink();
        this.mDeviceBroker.postDisconnectHeadset();
        this.mDeviceBroker.postDisconnectHearingAid();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void resetBluetoothSco() {
        clearAllScoClients(0, false);
        this.mScoAudioState = 0;
        broadcastScoConnectionState(0);
        AudioSystem.setParameters("A2dpSuspended=false");
        this.mDeviceBroker.setBluetoothScoOn(false, "resetBluetoothSco");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void disconnectHeadset() {
        setBtScoActiveDevice(null);
        this.mBluetoothHeadset = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onA2dpProfileConnected(BluetoothA2dp a2dp) {
        this.mA2dp = a2dp;
        List<BluetoothDevice> deviceList = this.mA2dp.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        BluetoothDevice btDevice = deviceList.get(0);
        this.mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(btDevice, 2, 11, true, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onA2dpSinkProfileConnected(BluetoothProfile profile) {
        List<BluetoothDevice> deviceList = profile.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        BluetoothDevice btDevice = deviceList.get(0);
        int state = profile.getConnectionState(btDevice);
        this.mDeviceBroker.postSetA2dpSourceConnectionState(state, new BluetoothA2dpDeviceInfo(btDevice));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onHearingAidProfileConnected(BluetoothHearingAid hearingAid) {
        this.mHearingAid = hearingAid;
        List<BluetoothDevice> deviceList = this.mHearingAid.getConnectedDevices();
        if (deviceList.isEmpty()) {
            return;
        }
        BluetoothDevice btDevice = deviceList.get(0);
        int state = this.mHearingAid.getConnectionState(btDevice);
        this.mDeviceBroker.postBluetoothHearingAidDeviceConnectionState(btDevice, state, false, 0, "mBluetoothProfileServiceListener");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x004f, code lost:
        r4.mScoAudioState = 0;
        broadcastScoConnectionState(0);
     */
    @com.android.internal.annotations.GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized void onHeadsetProfileConnected(android.bluetooth.BluetoothHeadset r5) {
        /*
            r4 = this;
            monitor-enter(r4)
            com.android.server.audio.AudioDeviceBroker r0 = r4.mDeviceBroker     // Catch: java.lang.Throwable -> L57
            r0.handleCancelFailureToConnectToBtHeadsetService()     // Catch: java.lang.Throwable -> L57
            r4.mBluetoothHeadset = r5     // Catch: java.lang.Throwable -> L57
            android.bluetooth.BluetoothHeadset r0 = r4.mBluetoothHeadset     // Catch: java.lang.Throwable -> L57
            android.bluetooth.BluetoothDevice r0 = r0.getActiveDevice()     // Catch: java.lang.Throwable -> L57
            r4.setBtScoActiveDevice(r0)     // Catch: java.lang.Throwable -> L57
            r4.checkScoAudioState()     // Catch: java.lang.Throwable -> L57
            int r0 = r4.mScoAudioState     // Catch: java.lang.Throwable -> L57
            r1 = 4
            r2 = 1
            if (r0 == r2) goto L20
            int r0 = r4.mScoAudioState     // Catch: java.lang.Throwable -> L57
            if (r0 == r1) goto L20
            monitor-exit(r4)
            return
        L20:
            r0 = 0
            android.bluetooth.BluetoothDevice r3 = r4.mBluetoothHeadsetDevice     // Catch: java.lang.Throwable -> L57
            if (r3 == 0) goto L4d
            int r3 = r4.mScoAudioState     // Catch: java.lang.Throwable -> L57
            if (r3 == r2) goto L3d
            if (r3 == r1) goto L2c
            goto L4d
        L2c:
            android.bluetooth.BluetoothHeadset r1 = r4.mBluetoothHeadset     // Catch: java.lang.Throwable -> L57
            android.bluetooth.BluetoothDevice r2 = r4.mBluetoothHeadsetDevice     // Catch: java.lang.Throwable -> L57
            int r3 = r4.mScoAudioMode     // Catch: java.lang.Throwable -> L57
            boolean r1 = disconnectBluetoothScoAudioHelper(r1, r2, r3)     // Catch: java.lang.Throwable -> L57
            r0 = r1
            if (r0 == 0) goto L4d
            r1 = 5
            r4.mScoAudioState = r1     // Catch: java.lang.Throwable -> L57
            goto L4d
        L3d:
            android.bluetooth.BluetoothHeadset r1 = r4.mBluetoothHeadset     // Catch: java.lang.Throwable -> L57
            android.bluetooth.BluetoothDevice r2 = r4.mBluetoothHeadsetDevice     // Catch: java.lang.Throwable -> L57
            int r3 = r4.mScoAudioMode     // Catch: java.lang.Throwable -> L57
            boolean r1 = connectBluetoothScoAudioHelper(r1, r2, r3)     // Catch: java.lang.Throwable -> L57
            r0 = r1
            if (r0 == 0) goto L4d
            r1 = 3
            r4.mScoAudioState = r1     // Catch: java.lang.Throwable -> L57
        L4d:
            if (r0 != 0) goto L55
            r1 = 0
            r4.mScoAudioState = r1     // Catch: java.lang.Throwable -> L57
            r4.broadcastScoConnectionState(r1)     // Catch: java.lang.Throwable -> L57
        L55:
            monitor-exit(r4)
            return
        L57:
            r5 = move-exception
            monitor-exit(r4)
            throw r5
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.BtHelper.onHeadsetProfileConnected(android.bluetooth.BluetoothHeadset):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void broadcastScoConnectionState(int state) {
        this.mDeviceBroker.postBroadcastScoConnectionState(state);
    }

    private boolean handleBtScoActiveDeviceChange(BluetoothDevice btDevice, boolean isActive) {
        boolean result;
        if (btDevice == null) {
            return true;
        }
        String address = btDevice.getAddress();
        BluetoothClass btClass = btDevice.getBluetoothClass();
        int[] outDeviceTypes = {16, 32, 64};
        if (btClass != null) {
            int deviceClass = btClass.getDeviceClass();
            if (deviceClass == 1028 || deviceClass == 1032) {
                outDeviceTypes = new int[]{32};
            } else if (deviceClass == 1056) {
                outDeviceTypes = new int[]{64};
            }
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        String btDeviceName = getName(btDevice);
        if (isActive) {
            result = false | this.mDeviceBroker.handleDeviceConnection(isActive, outDeviceTypes[0], address, btDeviceName);
        } else {
            boolean result2 = false;
            for (int outDeviceType : outDeviceTypes) {
                result2 |= this.mDeviceBroker.handleDeviceConnection(isActive, outDeviceType, address, btDeviceName);
            }
            result = result2;
        }
        if (this.mDeviceBroker.handleDeviceConnection(isActive, -2147483640, address, btDeviceName) && result) {
            return true;
        }
        return false;
    }

    @GuardedBy({"BtHelper.this"})
    private void setBtScoActiveDevice(BluetoothDevice btDevice) {
        Log.i(TAG, "setBtScoActiveDevice: " + this.mBluetoothHeadsetDevice + " -> " + btDevice);
        BluetoothDevice previousActiveDevice = this.mBluetoothHeadsetDevice;
        if (Objects.equals(btDevice, previousActiveDevice)) {
            return;
        }
        if (!handleBtScoActiveDeviceChange(previousActiveDevice, false)) {
            Log.w(TAG, "setBtScoActiveDevice() failed to remove previous device " + previousActiveDevice);
        }
        if (!handleBtScoActiveDeviceChange(btDevice, true)) {
            Log.e(TAG, "setBtScoActiveDevice() failed to add new device " + btDevice);
            btDevice = null;
        }
        this.mBluetoothHeadsetDevice = btDevice;
        if (this.mBluetoothHeadsetDevice == null) {
            resetBluetoothSco();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"AudioDeviceBroker.mDeviceStateLock"})
    public synchronized void scoClientDied(Object obj) {
        ScoClient client = (ScoClient) obj;
        Log.w(TAG, "SCO client died");
        int index = this.mScoClients.indexOf(client);
        if (index < 0) {
            Log.w(TAG, "unregistered SCO client died");
        } else {
            client.clearCount(true);
            this.mScoClients.remove(client);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ScoClient implements IBinder.DeathRecipient {
        private IBinder mCb;
        private int mCreatorPid = Binder.getCallingPid();
        private int mStartcount = 0;

        ScoClient(IBinder cb) {
            this.mCb = cb;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            BtHelper.this.mDeviceBroker.postScoClientDied(this);
        }

        @GuardedBy({"BtHelper.this"})
        void incCount(int scoAudioMode) {
            if (!requestScoState(12, scoAudioMode)) {
                Log.e(BtHelper.TAG, "Request sco connected with scoAudioMode(" + scoAudioMode + ") failed");
                return;
            }
            if (this.mStartcount == 0) {
                try {
                    this.mCb.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Log.w(BtHelper.TAG, "ScoClient  incCount() could not link to " + this.mCb + " binder death");
                }
            }
            this.mStartcount++;
        }

        @GuardedBy({"BtHelper.this"})
        void decCount() {
            int i = this.mStartcount;
            if (i == 0) {
                Log.w(BtHelper.TAG, "ScoClient.decCount() already 0");
                return;
            }
            this.mStartcount = i - 1;
            if (this.mStartcount == 0) {
                try {
                    this.mCb.unlinkToDeath(this, 0);
                } catch (NoSuchElementException e) {
                    Log.w(BtHelper.TAG, "decCount() going to 0 but not registered to binder");
                }
            }
            if (!requestScoState(10, 0)) {
                Log.w(BtHelper.TAG, "Request sco disconnected with scoAudioMode(0) failed");
            }
        }

        @GuardedBy({"BtHelper.this"})
        void clearCount(boolean stopSco) {
            if (this.mStartcount != 0) {
                try {
                    this.mCb.unlinkToDeath(this, 0);
                } catch (NoSuchElementException e) {
                    Log.w(BtHelper.TAG, "clearCount() mStartcount: " + this.mStartcount + " != 0 but not registered to binder");
                }
            }
            this.mStartcount = 0;
            if (stopSco) {
                requestScoState(10, 0);
            }
        }

        int getCount() {
            return this.mStartcount;
        }

        IBinder getBinder() {
            return this.mCb;
        }

        int getPid() {
            return this.mCreatorPid;
        }

        private int totalCount() {
            int count = 0;
            Iterator it = BtHelper.this.mScoClients.iterator();
            while (it.hasNext()) {
                ScoClient mScoClient = (ScoClient) it.next();
                count += mScoClient.getCount();
            }
            return count;
        }

        @GuardedBy({"BtHelper.this"})
        private boolean requestScoState(int state, int scoAudioMode) {
            BtHelper.this.checkScoAudioState();
            int clientCount = totalCount();
            if (clientCount != 0) {
                Log.i(BtHelper.TAG, "requestScoState: state=" + state + ", scoAudioMode=" + scoAudioMode + ", clientCount=" + clientCount);
                return true;
            }
            if (state == 12) {
                BtHelper.this.broadcastScoConnectionState(2);
                int modeOwnerPid = BtHelper.this.mDeviceBroker.getModeOwnerPid();
                if (modeOwnerPid == 0 || modeOwnerPid == this.mCreatorPid) {
                    int i = BtHelper.this.mScoAudioState;
                    if (i == 0) {
                        BtHelper.this.mScoAudioMode = scoAudioMode;
                        if (scoAudioMode == -1) {
                            BtHelper.this.mScoAudioMode = 0;
                            if (BtHelper.this.mBluetoothHeadsetDevice != null) {
                                BtHelper btHelper = BtHelper.this;
                                ContentResolver contentResolver = btHelper.mDeviceBroker.getContentResolver();
                                btHelper.mScoAudioMode = Settings.Global.getInt(contentResolver, "bluetooth_sco_channel_" + BtHelper.this.mBluetoothHeadsetDevice.getAddress(), 0);
                                if (BtHelper.this.mScoAudioMode > 2 || BtHelper.this.mScoAudioMode < 0) {
                                    BtHelper.this.mScoAudioMode = 0;
                                }
                            }
                        }
                        if (BtHelper.this.mBluetoothHeadset == null) {
                            if (BtHelper.this.getBluetoothHeadset()) {
                                BtHelper.this.mScoAudioState = 1;
                            } else {
                                Log.w(BtHelper.TAG, "requestScoState: getBluetoothHeadset failed during connection, mScoAudioMode=" + BtHelper.this.mScoAudioMode);
                                BtHelper.this.broadcastScoConnectionState(0);
                                return false;
                            }
                        } else if (BtHelper.this.mBluetoothHeadsetDevice != null) {
                            if (BtHelper.connectBluetoothScoAudioHelper(BtHelper.this.mBluetoothHeadset, BtHelper.this.mBluetoothHeadsetDevice, BtHelper.this.mScoAudioMode)) {
                                BtHelper.this.mScoAudioState = 3;
                            } else {
                                Log.w(BtHelper.TAG, "requestScoState: connect to " + BtHelper.this.mBluetoothHeadsetDevice + " failed, mScoAudioMode=" + BtHelper.this.mScoAudioMode);
                                BtHelper.this.broadcastScoConnectionState(0);
                                return false;
                            }
                        } else {
                            Log.w(BtHelper.TAG, "requestScoState: no active device while connecting, mScoAudioMode=" + BtHelper.this.mScoAudioMode);
                            BtHelper.this.broadcastScoConnectionState(0);
                            return false;
                        }
                    } else if (i == 4) {
                        BtHelper.this.mScoAudioState = 3;
                        BtHelper.this.broadcastScoConnectionState(1);
                    } else if (i == 5) {
                        BtHelper.this.mScoAudioState = 1;
                    } else {
                        Log.w(BtHelper.TAG, "requestScoState: failed to connect in state " + BtHelper.this.mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                        BtHelper.this.broadcastScoConnectionState(0);
                        return false;
                    }
                } else {
                    Log.w(BtHelper.TAG, "requestScoState: audio mode is not NORMAL and modeOwnerPid " + modeOwnerPid + " != creatorPid " + this.mCreatorPid);
                    BtHelper.this.broadcastScoConnectionState(0);
                    return false;
                }
            } else if (state == 10) {
                int i2 = BtHelper.this.mScoAudioState;
                if (i2 == 1) {
                    BtHelper.this.mScoAudioState = 0;
                    BtHelper.this.broadcastScoConnectionState(0);
                } else if (i2 == 3) {
                    if (BtHelper.this.mBluetoothHeadset == null) {
                        if (BtHelper.this.getBluetoothHeadset()) {
                            BtHelper.this.mScoAudioState = 4;
                        } else {
                            Log.w(BtHelper.TAG, "requestScoState: getBluetoothHeadset failed during disconnection, mScoAudioMode=" + BtHelper.this.mScoAudioMode);
                            BtHelper.this.mScoAudioState = 0;
                            BtHelper.this.broadcastScoConnectionState(0);
                            return false;
                        }
                    } else if (BtHelper.this.mBluetoothHeadsetDevice == null) {
                        BtHelper.this.mScoAudioState = 0;
                        BtHelper.this.broadcastScoConnectionState(0);
                    } else if (BtHelper.disconnectBluetoothScoAudioHelper(BtHelper.this.mBluetoothHeadset, BtHelper.this.mBluetoothHeadsetDevice, BtHelper.this.mScoAudioMode)) {
                        BtHelper.this.mScoAudioState = 5;
                    } else {
                        BtHelper.this.mScoAudioState = 0;
                        BtHelper.this.broadcastScoConnectionState(0);
                    }
                } else {
                    Log.w(BtHelper.TAG, "requestScoState: failed to disconnect in state " + BtHelper.this.mScoAudioState + ", scoAudioMode=" + scoAudioMode);
                    BtHelper.this.broadcastScoConnectionState(0);
                    return false;
                }
            }
            return true;
        }
    }

    private void sendStickyBroadcastToAll(Intent intent) {
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mDeviceBroker.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean disconnectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset, BluetoothDevice device, int scoAudioMode) {
        if (scoAudioMode != 0) {
            if (scoAudioMode != 1) {
                if (scoAudioMode == 2) {
                    return bluetoothHeadset.stopVoiceRecognition(device);
                }
                return false;
            }
            return bluetoothHeadset.disconnectAudio();
        }
        return bluetoothHeadset.stopScoUsingVirtualVoiceCall();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean connectBluetoothScoAudioHelper(BluetoothHeadset bluetoothHeadset, BluetoothDevice device, int scoAudioMode) {
        if (scoAudioMode != 0) {
            if (scoAudioMode != 1) {
                if (scoAudioMode == 2) {
                    return bluetoothHeadset.startVoiceRecognition(device);
                }
                return false;
            }
            return bluetoothHeadset.connectAudio();
        }
        return bluetoothHeadset.startScoUsingVirtualVoiceCall();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkScoAudioState() {
        BluetoothDevice bluetoothDevice;
        BluetoothHeadset bluetoothHeadset = this.mBluetoothHeadset;
        if (bluetoothHeadset != null && (bluetoothDevice = this.mBluetoothHeadsetDevice) != null && this.mScoAudioState == 0 && bluetoothHeadset.getAudioState(bluetoothDevice) != 10) {
            this.mScoAudioState = 2;
        }
    }

    private ScoClient getScoClient(IBinder cb, boolean create) {
        Iterator<ScoClient> it = this.mScoClients.iterator();
        while (it.hasNext()) {
            ScoClient existingClient = it.next();
            if (existingClient.getBinder() == cb) {
                return existingClient;
            }
        }
        if (create) {
            ScoClient newClient = new ScoClient(cb);
            this.mScoClients.add(newClient);
            return newClient;
        }
        return null;
    }

    @GuardedBy({"BtHelper.this"})
    private void clearAllScoClients(int exceptPid, boolean stopSco) {
        ScoClient savedClient = null;
        Iterator<ScoClient> it = this.mScoClients.iterator();
        while (it.hasNext()) {
            ScoClient cl = it.next();
            if (cl.getPid() != exceptPid) {
                cl.clearCount(stopSco);
            } else {
                savedClient = cl;
            }
        }
        this.mScoClients.clear();
        if (savedClient != null) {
            this.mScoClients.add(savedClient);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getBluetoothHeadset() {
        boolean result = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            result = adapter.getProfileProxy(this.mDeviceBroker.getContext(), this.mBluetoothProfileServiceListener, 1);
        }
        this.mDeviceBroker.handleFailureToConnectToBtHeadsetService(result ? 3000 : 0);
        return result;
    }

    private int mapBluetoothCodecToAudioFormat(int btCodecType) {
        if (btCodecType != 0) {
            if (btCodecType != 1) {
                if (btCodecType != 2) {
                    if (btCodecType != 3) {
                        if (btCodecType == 4) {
                            return 587202560;
                        }
                        return 0;
                    }
                    return 553648128;
                }
                return 536870912;
            }
            return 67108864;
        }
        return 520093696;
    }
}
