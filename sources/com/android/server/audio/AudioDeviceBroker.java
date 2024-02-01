package com.android.server.audio;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.AudioDeviceInventory;
import com.android.server.audio.AudioEventLogger;
import com.android.server.audio.AudioServiceEvents;
import com.android.server.audio.BtHelper;
import com.android.server.slice.SliceClientPermissions;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class AudioDeviceBroker {
    private static final long BROKER_WAKELOCK_TIMEOUT_MS = 5000;
    static final int BTA2DP_DOCK_TIMEOUT_MS = 8000;
    static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;
    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 12;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_DISCONNECT_A2DP = 19;
    private static final int MSG_DISCONNECT_A2DP_SINK = 20;
    private static final int MSG_DISCONNECT_BT_HEADSET = 22;
    private static final int MSG_DISCONNECT_BT_HEARING_AID = 21;
    private static final int MSG_IIL_SET_FORCE_BT_A2DP_USE = 5;
    private static final int MSG_IIL_SET_FORCE_USE = 4;
    private static final int MSG_II_SET_HEARING_AID_VOLUME = 14;
    private static final int MSG_IL_BTA2DP_DOCK_TIMEOUT = 10;
    private static final int MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED = 27;
    private static final int MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED = 28;
    private static final int MSG_IL_SET_A2DP_SOURCE_CONNECTION_STATE = 7;
    private static final int MSG_IL_SET_HEARING_AID_CONNECTION_STATE = 8;
    private static final int MSG_I_BROADCAST_BT_CONNECTION_STATE = 3;
    private static final int MSG_I_DISCONNECT_BT_SCO = 16;
    private static final int MSG_I_SET_AVRCP_ABSOLUTE_VOLUME = 15;
    private static final int MSG_L_A2DP_ACTIVE_DEVICE_CHANGE = 18;
    private static final int MSG_L_A2DP_DEVICE_CONFIG_CHANGE = 11;
    private static final int MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_CONNECTION = 29;
    private static final int MSG_L_A2DP_DEVICE_CONNECTION_CHANGE_EXT_DISCONNECTION = 30;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP = 23;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_A2DP_SINK = 24;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET = 26;
    private static final int MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEARING_AID = 25;
    private static final int MSG_L_HEARING_AID_DEVICE_CONNECTION_CHANGE_EXT = 31;
    private static final int MSG_L_SCOCLIENT_DIED = 32;
    private static final int MSG_L_SET_WIRED_DEVICE_CONNECTION_STATE = 2;
    private static final int MSG_REPORT_NEW_ROUTES = 13;
    private static final int MSG_RESTORE_DEVICES = 1;
    private static final int MSG_TOGGLE_HDMI = 6;
    private static final int SENDMSG_NOOP = 1;
    private static final int SENDMSG_QUEUE = 2;
    private static final int SENDMSG_REPLACE = 0;
    private static final String TAG = "AS.AudioDeviceBroker";
    private final AudioService mAudioService;
    @GuardedBy({"mDeviceStateLock"})
    private boolean mBluetoothA2dpEnabled;
    private PowerManager.WakeLock mBrokerEventWakeLock;
    private BrokerHandler mBrokerHandler;
    private BrokerThread mBrokerThread;
    private final BtHelper mBtHelper;
    private final Context mContext;
    private final AudioDeviceInventory mDeviceInventory;
    private final Object mDeviceStateLock;
    private int mForcedUseForComm;
    private int mForcedUseForCommExt;
    final Object mSetModeLock;
    private static final Object sLastDeviceConnectionMsgTimeLock = new Object();
    @GuardedBy({"sLastDeviceConnectionMsgTimeLock"})
    private static long sLastDeviceConnectMsgTime = 0;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioDeviceBroker(Context context, AudioService service) {
        this.mDeviceStateLock = new Object();
        this.mSetModeLock = new Object();
        this.mContext = context;
        this.mAudioService = service;
        this.mBtHelper = new BtHelper(this);
        this.mDeviceInventory = new AudioDeviceInventory(this);
        init();
    }

    AudioDeviceBroker(Context context, AudioService service, AudioDeviceInventory mockDeviceInventory) {
        this.mDeviceStateLock = new Object();
        this.mSetModeLock = new Object();
        this.mContext = context;
        this.mAudioService = service;
        this.mBtHelper = new BtHelper(this);
        this.mDeviceInventory = mockDeviceInventory;
        init();
    }

    private void init() {
        setupMessaging(this.mContext);
        this.mForcedUseForComm = 0;
        this.mForcedUseForCommExt = this.mForcedUseForComm;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Context getContext() {
        return this.mContext;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemReady() {
        synchronized (this.mSetModeLock) {
            synchronized (this.mDeviceStateLock) {
                this.mBtHelper.onSystemReady();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAudioServerDied() {
        synchronized (this.mDeviceStateLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("BT_SCO=");
            sb.append(this.mForcedUseForComm == 3 ? "on" : "off");
            AudioSystem.setParameters(sb.toString());
            onSetForceUse(0, this.mForcedUseForComm, "onAudioServerDied");
            onSetForceUse(2, this.mForcedUseForComm, "onAudioServerDied");
        }
        sendMsgNoDelay(1, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForceUse_Async(int useCase, int config, String eventSource) {
        sendIILMsgNoDelay(4, 2, useCase, config, eventSource);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void toggleHdmiIfConnected_Async() {
        sendMsgNoDelay(6, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disconnectAllBluetoothProfiles() {
        synchronized (this.mDeviceStateLock) {
            this.mBtHelper.disconnectAllBluetoothProfiles();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void receiveBtEvent(Intent intent) {
        synchronized (this.mSetModeLock) {
            synchronized (this.mDeviceStateLock) {
                this.mBtHelper.receiveBtEvent(intent);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBluetoothA2dpOn_Async(boolean on, String source) {
        synchronized (this.mDeviceStateLock) {
            if (this.mBluetoothA2dpEnabled == on) {
                return;
            }
            this.mBluetoothA2dpEnabled = on;
            this.mBrokerHandler.removeMessages(5);
            sendIILMsgNoDelay(5, 2, 1, this.mBluetoothA2dpEnabled ? 0 : 10, source);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setSpeakerphoneOn(boolean on, String eventSource) {
        boolean z;
        synchronized (this.mDeviceStateLock) {
            boolean wasOn = isSpeakerphoneOn();
            z = true;
            if (!on) {
                if (this.mForcedUseForComm == 1) {
                    this.mForcedUseForComm = 0;
                }
            } else {
                if (this.mForcedUseForComm == 3) {
                    setForceUse_Async(2, 0, eventSource);
                }
                this.mForcedUseForComm = 1;
            }
            this.mForcedUseForCommExt = this.mForcedUseForComm;
            setForceUse_Async(0, this.mForcedUseForComm, eventSource);
            if (wasOn == isSpeakerphoneOn()) {
                z = false;
            }
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSpeakerphoneOn() {
        boolean z;
        synchronized (this.mDeviceStateLock) {
            z = true;
            if (this.mForcedUseForCommExt != 1) {
                z = false;
            }
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWiredDeviceConnectionState(int type, int state, String address, String name, String caller) {
        synchronized (this.mDeviceStateLock) {
            this.mDeviceInventory.setWiredDeviceConnectionState(type, state, address, name, caller);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class BtDeviceConnectionInfo {
        final BluetoothDevice mDevice;
        final int mProfile;
        final int mState;
        final boolean mSupprNoisy;
        final int mVolume;

        BtDeviceConnectionInfo(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int vol) {
            this.mDevice = device;
            this.mState = state;
            this.mProfile = profile;
            this.mSupprNoisy = suppressNoisyIntent;
            this.mVolume = vol;
        }

        public boolean equals(Object o) {
            return this.mDevice.equals(o);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(BluetoothDevice device, int state, int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        int i;
        BtDeviceConnectionInfo info = new BtDeviceConnectionInfo(device, state, profile, suppressNoisyIntent, a2dpVolume);
        removeAllA2dpConnectionEvents(device);
        if (state == 2) {
            i = 29;
        } else {
            i = 30;
        }
        sendLMsgNoDelay(i, 2, info);
    }

    private void removeAllA2dpConnectionEvents(BluetoothDevice device) {
        this.mBrokerHandler.removeMessages(30, device);
        this.mBrokerHandler.removeMessages(29, device);
        this.mBrokerHandler.removeMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED, device);
        this.mBrokerHandler.removeMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED, device);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class HearingAidDeviceConnectionInfo {
        final BluetoothDevice mDevice;
        final String mEventSource;
        final int mMusicDevice;
        final int mState;
        final boolean mSupprNoisy;

        HearingAidDeviceConnectionInfo(BluetoothDevice device, int state, boolean suppressNoisyIntent, int musicDevice, String eventSource) {
            this.mDevice = device;
            this.mState = state;
            this.mSupprNoisy = suppressNoisyIntent;
            this.mMusicDevice = musicDevice;
            this.mEventSource = eventSource;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBluetoothHearingAidDeviceConnectionState(BluetoothDevice device, int state, boolean suppressNoisyIntent, int musicDevice, String eventSource) {
        HearingAidDeviceConnectionInfo info = new HearingAidDeviceConnectionInfo(device, state, suppressNoisyIntent, musicDevice, eventSource);
        sendLMsgNoDelay(31, 2, info);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBluetoothScoOnByApp(boolean on) {
        synchronized (this.mDeviceStateLock) {
            this.mForcedUseForCommExt = on ? 3 : 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBluetoothScoOnForApp() {
        boolean z;
        synchronized (this.mDeviceStateLock) {
            z = this.mForcedUseForCommExt == 3;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBluetoothScoOn(boolean on, String eventSource) {
        synchronized (this.mDeviceStateLock) {
            if (!on) {
                if (this.mForcedUseForComm == 3) {
                    this.mForcedUseForComm = 0;
                }
            } else if (!this.mBtHelper.isBluetoothScoOn()) {
                this.mForcedUseForCommExt = 3;
                return;
            } else {
                this.mForcedUseForComm = 3;
            }
            this.mForcedUseForCommExt = this.mForcedUseForComm;
            StringBuilder sb = new StringBuilder();
            sb.append("BT_SCO=");
            sb.append(on ? "on" : "off");
            AudioSystem.setParameters(sb.toString());
            sendIILMsgNoDelay(4, 2, 0, this.mForcedUseForComm, eventSource);
            sendIILMsgNoDelay(4, 2, 2, this.mForcedUseForComm, eventSource);
            this.mAudioService.postUpdateRingerModeServiceInt();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        AudioRoutesInfo startWatchingRoutes;
        synchronized (this.mDeviceStateLock) {
            startWatchingRoutes = this.mDeviceInventory.startWatchingRoutes(observer);
        }
        return startWatchingRoutes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioRoutesInfo getCurAudioRoutes() {
        AudioRoutesInfo curAudioRoutes;
        synchronized (this.mDeviceStateLock) {
            curAudioRoutes = this.mDeviceInventory.getCurAudioRoutes();
        }
        return curAudioRoutes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAvrcpAbsoluteVolumeSupported() {
        boolean isAvrcpAbsoluteVolumeSupported;
        synchronized (this.mDeviceStateLock) {
            isAvrcpAbsoluteVolumeSupported = this.mBtHelper.isAvrcpAbsoluteVolumeSupported();
        }
        return isAvrcpAbsoluteVolumeSupported;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBluetoothA2dpOn() {
        boolean z;
        synchronized (this.mDeviceStateLock) {
            z = this.mBluetoothA2dpEnabled;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetAvrcpAbsoluteVolumeIndex(int index) {
        sendIMsgNoDelay(15, 0, index);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetHearingAidVolumeIndex(int index, int streamType) {
        sendIIMsgNoDelay(14, 0, index, streamType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postDisconnectBluetoothSco(int exceptPid) {
        sendIMsgNoDelay(16, 0, exceptPid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBluetoothA2dpDeviceConfigChange(BluetoothDevice device) {
        sendLMsgNoDelay(11, 2, device);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mSetModeLock"})
    public void startBluetoothScoForClient_Sync(IBinder cb, int scoAudioMode, String eventSource) {
        synchronized (this.mDeviceStateLock) {
            this.mBtHelper.startBluetoothScoForClient(cb, scoAudioMode, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mSetModeLock"})
    public void stopBluetoothScoForClient_Sync(IBinder cb, String eventSource) {
        synchronized (this.mDeviceStateLock) {
            this.mBtHelper.stopBluetoothScoForClient(cb, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postAccessoryPlugMediaUnmute(int device) {
        this.mAudioService.postAccessoryPlugMediaUnmute(device);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getVssVolumeForDevice(int streamType, int device) {
        return this.mAudioService.getVssVolumeForDevice(streamType, device);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getModeOwnerPid() {
        return this.mAudioService.getModeOwnerPid();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDeviceForStream(int streamType) {
        return this.mAudioService.getDeviceForStream(streamType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postApplyVolumeOnDevice(int streamType, int device, String caller) {
        this.mAudioService.postApplyVolumeOnDevice(streamType, device, caller);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetVolumeIndexOnDevice(int streamType, int vssVolIndex, int device, String caller) {
        this.mAudioService.postSetVolumeIndexOnDevice(streamType, vssVolIndex, device, caller);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postObserveDevicesForAllStreams() {
        this.mAudioService.postObserveDevicesForAllStreams();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInCommunication() {
        return this.mAudioService.isInCommunication();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasMediaDynamicPolicy() {
        return this.mAudioService.hasMediaDynamicPolicy();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentResolver getContentResolver() {
        return this.mAudioService.getContentResolver();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkMusicActive(int deviceType, String caller) {
        this.mAudioService.checkMusicActive(deviceType, caller);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkVolumeCecOnHdmiConnection(int state, String caller) {
        this.mAudioService.postCheckVolumeCecOnHdmiConnection(state, caller);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasAudioFocusUsers() {
        return this.mAudioService.hasAudioFocusUsers();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBroadcastScoConnectionState(int state) {
        sendIMsgNoDelay(3, 2, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBroadcastBecomingNoisy() {
        sendMsgNoDelay(12, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postA2dpSinkConnection(int state, BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo, int delay) {
        int i;
        if (state == 2) {
            i = MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED;
        } else {
            i = MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED;
        }
        sendILMsg(i, 2, state, btDeviceInfo, delay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postA2dpSourceConnection(int state, BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo, int delay) {
        sendILMsg(7, 2, state, btDeviceInfo, delay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetWiredDeviceConnectionState(AudioDeviceInventory.WiredDeviceConnectionState connectionState, int delay) {
        sendLMsg(2, 2, connectionState, delay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetHearingAidConnectionState(int state, BluetoothDevice device, int delay) {
        sendILMsg(8, 2, state, device, delay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postDisconnectA2dp() {
        sendMsgNoDelay(19, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postDisconnectA2dpSink() {
        sendMsgNoDelay(20, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postDisconnectHearingAid() {
        sendMsgNoDelay(21, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postDisconnectHeadset() {
        sendMsgNoDelay(22, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBtA2dpProfileConnected(BluetoothA2dp a2dpProfile) {
        sendLMsgNoDelay(23, 2, a2dpProfile);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBtA2dpSinkProfileConnected(BluetoothProfile profile) {
        sendLMsgNoDelay(24, 2, profile);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBtHeasetProfileConnected(BluetoothHeadset headsetProfile) {
        sendLMsgNoDelay(MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET, 2, headsetProfile);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postBtHearingAidProfileConnected(BluetoothHearingAid hearingAidProfile) {
        sendLMsgNoDelay(25, 2, hearingAidProfile);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postScoClientDied(Object obj) {
        sendLMsgNoDelay(32, 2, obj);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBluetoothA2dpOnInt(boolean on, String source) {
        String eventSource = "setBluetoothA2dpOn(" + on + ") from u/pid:" + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid() + " src:" + source;
        synchronized (this.mDeviceStateLock) {
            this.mBluetoothA2dpEnabled = on;
            this.mBrokerHandler.removeMessages(5);
            onSetForceUse(1, this.mBluetoothA2dpEnabled ? 0 : 10, eventSource);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleDeviceConnection(boolean connect, int device, String address, String deviceName) {
        boolean handleDeviceConnection;
        synchronized (this.mDeviceStateLock) {
            handleDeviceConnection = this.mDeviceInventory.handleDeviceConnection(connect, device, address, deviceName);
        }
        return handleDeviceConnection;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postSetA2dpSourceConnectionState(int state, BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        if (state == 2) {
        }
        sendILMsgNoDelay(7, 2, state, btDeviceInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleFailureToConnectToBtHeadsetService(int delay) {
        sendMsg(9, 0, delay);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleCancelFailureToConnectToBtHeadsetService() {
        this.mBrokerHandler.removeMessages(9);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postReportNewRoutes() {
        sendMsgNoDelay(13, 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelA2dpDockTimeout() {
        this.mBrokerHandler.removeMessages(10);
    }

    void postA2dpActiveDeviceChange(BtHelper.BluetoothA2dpDeviceInfo btDeviceInfo) {
        sendLMsgNoDelay(18, 2, btDeviceInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasScheduledA2dpDockTimeout() {
        return this.mBrokerHandler.hasMessages(10);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasScheduledA2dpSinkConnectionState(BluetoothDevice btDevice) {
        return this.mBrokerHandler.hasMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED, new BtHelper.BluetoothA2dpDeviceInfo(btDevice)) || this.mBrokerHandler.hasMessages(MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED, new BtHelper.BluetoothA2dpDeviceInfo(btDevice));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setA2dpDockTimeout(String address, int a2dpCodec, int delayMs) {
        sendILMsg(10, 2, a2dpCodec, address, delayMs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAvrcpAbsoluteVolumeSupported(boolean supported) {
        synchronized (this.mDeviceStateLock) {
            this.mBtHelper.setAvrcpAbsoluteVolumeSupported(supported);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getBluetoothA2dpEnabled() {
        boolean z;
        synchronized (this.mDeviceStateLock) {
            z = this.mBluetoothA2dpEnabled;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getA2dpCodec(BluetoothDevice device) {
        int a2dpCodec;
        synchronized (this.mDeviceStateLock) {
            a2dpCodec = this.mBtHelper.getA2dpCodec(device);
        }
        return a2dpCodec;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetForceUse(int useCase, int config, String eventSource) {
        if (useCase == 1) {
            postReportNewRoutes();
        }
        AudioService.sForceUseLogger.log(new AudioServiceEvents.ForceUseEvent(useCase, config, eventSource));
        AudioSystem.setForceUse(useCase, config);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSendBecomingNoisyIntent() {
        AudioService.sDeviceLogger.log(new AudioEventLogger.StringEvent("broadcast ACTION_AUDIO_BECOMING_NOISY").printLog(TAG));
        sendBroadcastToAll(new Intent("android.media.AUDIO_BECOMING_NOISY"));
    }

    private void setupMessaging(Context ctxt) {
        PowerManager pm = (PowerManager) ctxt.getSystemService("power");
        this.mBrokerEventWakeLock = pm.newWakeLock(1, "handleAudioDeviceEvent");
        this.mBrokerThread = new BrokerThread();
        this.mBrokerThread.start();
        waitForBrokerHandlerCreation();
    }

    private void waitForBrokerHandlerCreation() {
        synchronized (this) {
            while (this.mBrokerHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interruption while waiting on BrokerHandler");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class BrokerThread extends Thread {
        BrokerThread() {
            super("AudioDeviceBroker");
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            Looper.prepare();
            synchronized (AudioDeviceBroker.this) {
                AudioDeviceBroker.this.mBrokerHandler = new BrokerHandler();
                AudioDeviceBroker.this.notify();
            }
            Looper.loop();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class BrokerHandler extends Handler {
        private BrokerHandler() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onRestoreDevices();
                        AudioDeviceBroker.this.mBtHelper.onAudioServerDiedRestoreA2dp();
                    }
                    break;
                case 2:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onSetWiredDeviceConnectionState((AudioDeviceInventory.WiredDeviceConnectionState) msg.obj);
                    }
                    break;
                case 3:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.onBroadcastScoConnectionState(msg.arg1);
                    }
                    break;
                case 4:
                case 5:
                    AudioDeviceBroker.this.onSetForceUse(msg.arg1, msg.arg2, (String) msg.obj);
                    break;
                case 6:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onToggleHdmi();
                    }
                    break;
                case 7:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onSetA2dpSourceConnectionState((BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    }
                    break;
                case 8:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onSetHearingAidConnectionState((BluetoothDevice) msg.obj, msg.arg1, AudioDeviceBroker.this.mAudioService.getHearingAidStreamType());
                    }
                    break;
                case 9:
                    synchronized (AudioDeviceBroker.this.mSetModeLock) {
                        synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                            AudioDeviceBroker.this.mBtHelper.resetBluetoothSco();
                        }
                        break;
                    }
                case 10:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onMakeA2dpDeviceUnavailableNow((String) msg.obj, msg.arg1);
                    }
                    break;
                case 11:
                    BluetoothDevice btDevice = (BluetoothDevice) msg.obj;
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        int a2dpCodec = AudioDeviceBroker.this.mBtHelper.getA2dpCodec(btDevice);
                        AudioDeviceBroker.this.mDeviceInventory.onBluetoothA2dpActiveDeviceChange(new BtHelper.BluetoothA2dpDeviceInfo(btDevice, -1, a2dpCodec), 0);
                    }
                    break;
                case 12:
                    AudioDeviceBroker.this.onSendBecomingNoisyIntent();
                    break;
                case 13:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onReportNewRoutes();
                    }
                    break;
                case 14:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.setHearingAidVolume(msg.arg1, msg.arg2);
                    }
                    break;
                case 15:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.setAvrcpAbsoluteVolumeIndex(msg.arg1);
                    }
                    break;
                case 16:
                    synchronized (AudioDeviceBroker.this.mSetModeLock) {
                        synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                            AudioDeviceBroker.this.mBtHelper.disconnectBluetoothSco(msg.arg1);
                        }
                        break;
                    }
                case 17:
                default:
                    Log.wtf(AudioDeviceBroker.TAG, "Invalid message " + msg.what);
                    break;
                case 18:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onBluetoothA2dpActiveDeviceChange((BtHelper.BluetoothA2dpDeviceInfo) msg.obj, 1);
                    }
                    break;
                case 19:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.disconnectA2dp();
                    }
                    break;
                case 20:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.disconnectA2dpSink();
                    }
                    break;
                case 21:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.disconnectHearingAid();
                    }
                    break;
                case 22:
                    synchronized (AudioDeviceBroker.this.mSetModeLock) {
                        synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                            AudioDeviceBroker.this.mBtHelper.disconnectHeadset();
                        }
                        break;
                    }
                case 23:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.onA2dpProfileConnected((BluetoothA2dp) msg.obj);
                    }
                    break;
                case 24:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.onA2dpSinkProfileConnected((BluetoothProfile) msg.obj);
                    }
                    break;
                case 25:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mBtHelper.onHearingAidProfileConnected((BluetoothHearingAid) msg.obj);
                    }
                    break;
                case AudioDeviceBroker.MSG_L_BT_SERVICE_CONNECTED_PROFILE_HEADSET /* 26 */:
                    synchronized (AudioDeviceBroker.this.mSetModeLock) {
                        synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                            AudioDeviceBroker.this.mBtHelper.onHeadsetProfileConnected((BluetoothHeadset) msg.obj);
                        }
                        break;
                    }
                case AudioDeviceBroker.MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED /* 27 */:
                case AudioDeviceBroker.MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED /* 28 */:
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.onSetA2dpSinkConnectionState((BtHelper.BluetoothA2dpDeviceInfo) msg.obj, msg.arg1);
                    }
                    break;
                case 29:
                case 30:
                    BtDeviceConnectionInfo info = (BtDeviceConnectionInfo) msg.obj;
                    AudioEventLogger audioEventLogger = AudioService.sDeviceLogger;
                    audioEventLogger.log(new AudioEventLogger.StringEvent("setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent  state=" + info.mState + " addr=" + info.mDevice.getAddress() + " prof=" + info.mProfile + " supprNoisy=" + info.mSupprNoisy + " vol=" + info.mVolume).printLog(AudioDeviceBroker.TAG));
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.setBluetoothA2dpDeviceConnectionState(info.mDevice, info.mState, info.mProfile, info.mSupprNoisy, 0, info.mVolume);
                    }
                    break;
                case 31:
                    HearingAidDeviceConnectionInfo info2 = (HearingAidDeviceConnectionInfo) msg.obj;
                    AudioEventLogger audioEventLogger2 = AudioService.sDeviceLogger;
                    audioEventLogger2.log(new AudioEventLogger.StringEvent("setHearingAidDeviceConnectionState state=" + info2.mState + " addr=" + info2.mDevice.getAddress() + " supprNoisy=" + info2.mSupprNoisy + " src=" + info2.mEventSource).printLog(AudioDeviceBroker.TAG));
                    synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                        AudioDeviceBroker.this.mDeviceInventory.setBluetoothHearingAidDeviceConnectionState(info2.mDevice, info2.mState, info2.mSupprNoisy, info2.mMusicDevice);
                    }
                    break;
                case 32:
                    synchronized (AudioDeviceBroker.this.mSetModeLock) {
                        synchronized (AudioDeviceBroker.this.mDeviceStateLock) {
                            AudioDeviceBroker.this.mBtHelper.scoClientDied(msg.obj);
                        }
                        break;
                    }
            }
            if (AudioDeviceBroker.isMessageHandledUnderWakelock(msg.what)) {
                try {
                    AudioDeviceBroker.this.mBrokerEventWakeLock.release();
                } catch (Exception e) {
                    Log.e(AudioDeviceBroker.TAG, "Exception releasing wakelock", e);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isMessageHandledUnderWakelock(int msgId) {
        if (msgId == 2 || msgId == 18 || msgId == 6 || msgId == 7 || msgId == 8 || msgId == 10 || msgId == 11) {
            return true;
        }
        switch (msgId) {
            case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED /* 27 */:
            case MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED /* 28 */:
            case 29:
            case 30:
            case 31:
                return true;
            default:
                return false;
        }
    }

    private void sendMsg(int msg, int existingMsgPolicy, int delay) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, null, delay);
    }

    private void sendILMsg(int msg, int existingMsgPolicy, int arg, Object obj, int delay) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, obj, delay);
    }

    private void sendLMsg(int msg, int existingMsgPolicy, Object obj, int delay) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, obj, delay);
    }

    private void sendIMsg(int msg, int existingMsgPolicy, int arg, int delay) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, null, delay);
    }

    private void sendMsgNoDelay(int msg, int existingMsgPolicy) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, null, 0);
    }

    private void sendIMsgNoDelay(int msg, int existingMsgPolicy, int arg) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, null, 0);
    }

    private void sendIIMsgNoDelay(int msg, int existingMsgPolicy, int arg1, int arg2) {
        sendIILMsg(msg, existingMsgPolicy, arg1, arg2, null, 0);
    }

    private void sendILMsgNoDelay(int msg, int existingMsgPolicy, int arg, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, arg, 0, obj, 0);
    }

    private void sendLMsgNoDelay(int msg, int existingMsgPolicy, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, 0, 0, obj, 0);
    }

    private void sendIILMsgNoDelay(int msg, int existingMsgPolicy, int arg1, int arg2, Object obj) {
        sendIILMsg(msg, existingMsgPolicy, arg1, arg2, obj, 0);
    }

    private void sendIILMsg(int msg, int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {
        if (existingMsgPolicy == 0) {
            this.mBrokerHandler.removeMessages(msg);
        } else if (existingMsgPolicy == 1 && this.mBrokerHandler.hasMessages(msg)) {
            return;
        }
        if (isMessageHandledUnderWakelock(msg)) {
            long identity = Binder.clearCallingIdentity();
            try {
                this.mBrokerEventWakeLock.acquire(BROKER_WAKELOCK_TIMEOUT_MS);
            } catch (Exception e) {
                Log.e(TAG, "Exception acquiring wakelock", e);
            }
            Binder.restoreCallingIdentity(identity);
        }
        synchronized (sLastDeviceConnectionMsgTimeLock) {
            long time = SystemClock.uptimeMillis() + delay;
            if (msg == 2 || msg == 18 || msg == 7 || msg == 8 || msg == 10 || msg == 11 || msg == MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_CONNECTED || msg == MSG_IL_SET_A2DP_SINK_CONNECTION_STATE_DISCONNECTED) {
                if (sLastDeviceConnectMsgTime >= time) {
                    time = sLastDeviceConnectMsgTime + 30;
                }
                sLastDeviceConnectMsgTime = time;
            }
            this.mBrokerHandler.sendMessageAtTime(this.mBrokerHandler.obtainMessage(msg, arg1, arg2, obj), time);
        }
    }

    private void sendBroadcastToAll(Intent intent) {
        intent.addFlags(67108864);
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
