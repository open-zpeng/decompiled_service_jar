package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.channelData;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes2.dex */
public class xuiChannelToAmp implements IXuiChannelInterface, remoteConnect.RemoteConnectListerer {
    private static final int ACCESSIBILITY_COMMON_ID = 65546;
    public static final int ANALOG = 2;
    private static final int BLUETOOTH_SCO_COMMON_ID = 65542;
    public static final int MIX = 3;
    public static final String MODE_DANGER = "dangerVolLevel";
    public static final String MODE_DRIVING = "driveringMode";
    public static final String MODE_NORMAL = "normal";
    private static final int MUSIC_COMMON_ID = 65539;
    public static final int SPDIF = 1;
    private static final int SYSTEM_COMMON_ID = 65537;
    private static final String TAG = "xuiChannelToAmp";
    public static final int VOICE_POSITION_ALL = 5;
    public static final int VOICE_POSITION_CENTER = 0;
    public static final int VOICE_POSITION_FRONTLEFT = 1;
    public static final int VOICE_POSITION_FRONTRIGHT = 2;
    public static final int VOICE_POSITION_INVALID = -1;
    public static final int VOICE_POSITION_REARLEFT = 3;
    public static final int VOICE_POSITION_REARRIGHT = 4;
    public static final int XP_AMP_CHANNEL_1 = 1;
    public static final int XP_AMP_CHANNEL_10 = 512;
    public static final int XP_AMP_CHANNEL_11 = 1024;
    public static final int XP_AMP_CHANNEL_12 = 2048;
    public static final int XP_AMP_CHANNEL_2 = 2;
    public static final int XP_AMP_CHANNEL_3 = 4;
    public static final int XP_AMP_CHANNEL_4 = 8;
    public static final int XP_AMP_CHANNEL_5 = 16;
    public static final int XP_AMP_CHANNEL_6 = 32;
    public static final int XP_AMP_CHANNEL_7 = 64;
    public static final int XP_AMP_CHANNEL_8 = 128;
    public static final int XP_AMP_CHANNEL_9 = 256;
    private static int mAudioFeature = 0;
    private static channelData mChannelData;
    private static Context mContext;
    private static xuiChannelToAmp mInstance;
    private static remoteConnect mRemoteConnect;
    private static volumeJsonObject.streamVolumePare mStreamVolumeType;
    private static xuiChannelPolicy mXuiChannelPolicy;
    private int activeChannelBits;
    private List<Integer>[] mActiveStreamList;
    private IXpVehicle mXpVehicle;
    private int XP_AMP_CHANNEL_ALL = 4095;
    private int XP_AMP_CHANNEL_ALL_LOCATION = 63;
    private int XP_AMP_CHANNEL_FRONT = 67;
    private int XP_AMP_CHANNEL_FRONTLEFT = 1;
    private int XP_AMP_CHANNEL_FRONTRIGHT = 2;
    private int XP_AMP_CHANNEL_REARLEFT = 4;
    private int XP_AMP_CHANNEL_REARRIGHT = 8;
    private int XP_AMP_CHANNEL_FRONT_LOCATION = 3;
    private int XP_AMP_CHANNEL_MAIN_DRIVER = 3072;
    private int mVoicePosition = -1;
    private int btCallOnFlag = 0;
    private boolean mBtHeadPhone = false;
    private int mMainDriverMode = 0;
    private boolean mForceSurroundMode = false;
    private int lastSpdifbits = -1;
    private int lastAnalogbits = -1;
    private int lastMixbits = -1;
    private int lastActiveBits = -1;
    private int mSpeechChannels = -1;
    private final String SOUND_TYPE_SPDIF = "spdif";
    private final String SOUND_TYPE_ANALOG = "analog";
    private final String SOUND_TYPE_MIX = "mix";
    private Object activeStreamListObject = new Object();
    private final BroadcastReceiver mBootReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiChannelToAmp.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if ("android.intent.action.ACTION_SHUTDOWN".equals(action) && xuiChannelToAmp.this.mXpVehicle != null) {
                    Log.i(xuiChannelToAmp.TAG, "REBOOTING!!!");
                    try {
                        xuiChannelToAmp.this.mXpVehicle.setAmpMute(1);
                    } catch (Exception e) {
                    }
                }
            }
        }
    };

    private xuiChannelToAmp(xuiChannelPolicy instance, Context context) {
        mContext = context;
        mXuiChannelPolicy = instance;
        mRemoteConnect = remoteConnect.getInstance(context);
        mRemoteConnect.registerListener(this);
        initXuiChannelData();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction("android.intent.action.ACTION_SHUTDOWN");
        mContext.registerReceiver(this.mBootReceiver, mIntentFilter);
    }

    public static xuiChannelToAmp getInstance(xuiChannelPolicy instance, Context context) {
        if (mInstance == null) {
            mInstance = new xuiChannelToAmp(instance, context);
        }
        return mInstance;
    }

    private void initXuiChannelData() {
        if (mChannelData == null) {
            mChannelData = xuiAudioParser.parseChannelData();
        }
        if (mStreamVolumeType == null) {
            mStreamVolumeType = xuiAudioParser.parseStreamVolumePare();
        }
        int i = this.activeChannelBits;
        if (i == 0) {
            this.activeChannelBits = i + 8;
            this.activeChannelBits += 2;
        }
        synchronized (this.activeStreamListObject) {
            if (this.mActiveStreamList == null) {
                this.mActiveStreamList = new ArrayList[mStreamVolumeType.getMaxStreamType()];
                for (int i2 = 0; i2 < mStreamVolumeType.getMaxStreamType(); i2++) {
                    this.mActiveStreamList[i2] = new ArrayList();
                }
                this.mActiveStreamList[3].add(Integer.valueOf((int) MUSIC_COMMON_ID));
                this.mActiveStreamList[1].add(Integer.valueOf((int) SYSTEM_COMMON_ID));
            }
        }
        debugDumpChannelData();
    }

    private int getSpdifChannelBits() {
        if (this.mActiveStreamList == null) {
            return 0;
        }
        int spdifbits = 0;
        for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
            List<Integer>[] listArr = this.mActiveStreamList;
            if (listArr[i] != null && listArr[i].size() != 0) {
                channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(i));
                if (mTypePolicy.type.equals("spdif")) {
                    if (0 != mTypePolicy.priority) {
                        if (mTypePolicy.priority > 0) {
                            spdifbits = mTypePolicy.channel;
                        }
                    } else {
                        spdifbits |= mTypePolicy.channel;
                    }
                }
            }
        }
        return spdifbits;
    }

    private int getAnalogChannelBits() {
        if (this.mActiveStreamList == null) {
            return 0;
        }
        int analogbits = 0;
        for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
            List<Integer>[] listArr = this.mActiveStreamList;
            if (listArr[i] != null && listArr[i].size() != 0) {
                channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(i));
                if (mTypePolicy.type.equals("analog")) {
                    analogbits |= mTypePolicy.channel;
                }
            }
        }
        return analogbits;
    }

    private int getMixChannelBits(int spdifbits, int analogbits) {
        if (this.mActiveStreamList == null) {
            return 0;
        }
        int mixbits = spdifbits & analogbits;
        for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
            List<Integer>[] listArr = this.mActiveStreamList;
            if (listArr[i] != null && listArr[i].size() != 0) {
                channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(i));
                if (mTypePolicy.type.equals("mix")) {
                    mixbits |= mTypePolicy.channel;
                }
            }
        }
        return mixbits;
    }

    private int[] getAllChannelBits() {
        channelData.TypePolicy mTypePolicy;
        int[] allChannelBits = new int[4];
        if (this.mActiveStreamList == null) {
            return allChannelBits;
        }
        int spdifbits = 0;
        int analogbits = 0;
        int mixbits = 0;
        int spdifpriority = 0;
        int analogpriority = 0;
        int mixpriority = 0;
        debugPrint("getAllChannelBits start!!!");
        for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
            List<Integer>[] listArr = this.mActiveStreamList;
            if (listArr[i] != null && listArr[i].size() != 0 && (mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(i))) != null) {
                if (mTypePolicy.type.equals("spdif")) {
                    if (spdifpriority == mTypePolicy.priority) {
                        spdifbits |= mTypePolicy.channel;
                    } else if (mTypePolicy.priority > spdifpriority) {
                        spdifbits = mTypePolicy.channel;
                        spdifpriority = mTypePolicy.priority;
                    }
                    debugPrint("getAllChannelBits spdif priority:" + spdifpriority + " bits:" + spdifbits);
                } else if (mTypePolicy.type.equals("analog")) {
                    if (analogpriority == mTypePolicy.priority) {
                        analogbits |= mTypePolicy.channel;
                    } else if (mTypePolicy.priority > analogpriority) {
                        analogbits = mTypePolicy.channel;
                        analogpriority = mTypePolicy.priority;
                    }
                    debugPrint("getAllChannelBits analog priority:" + analogpriority + " bits:" + analogbits);
                } else if (mTypePolicy.type.equals("mix")) {
                    if (mixpriority == mTypePolicy.priority) {
                        mixbits |= mTypePolicy.channel;
                    } else if (mTypePolicy.priority > mixpriority) {
                        mixbits = mTypePolicy.channel;
                        mixpriority = mTypePolicy.priority;
                    }
                    debugPrint("getAllChannelBits mix priority:" + mixpriority + " bits:" + mixbits);
                }
            }
        }
        int i2 = this.mVoicePosition;
        if (i2 != -1 && this.btCallOnFlag == 0 && this.mMainDriverMode != 2) {
            analogbits = this.mSpeechChannels;
        }
        int spdifbits2 = spdifbits & (~mixbits);
        int analogbits2 = analogbits & (~mixbits);
        int activeChannelBits = spdifbits2 | analogbits2 | mixbits;
        int spdifChannelBits = (~analogbits2) & spdifbits2;
        int analogChannelBits = (~spdifbits2) & analogbits2;
        int mixbits2 = mixbits | (spdifbits2 & analogbits2);
        debugPrint("getAllChannelBits spdifbits:" + spdifChannelBits + " analogbits:" + analogChannelBits + " mixbits:" + mixbits2 + " activeChannelBits:" + activeChannelBits);
        debugPrint("getAllChannelBits end!!!");
        allChannelBits[0] = activeChannelBits;
        allChannelBits[1] = spdifChannelBits;
        allChannelBits[2] = analogChannelBits;
        allChannelBits[3] = mixbits2;
        return allChannelBits;
    }

    private int getVolumeValueModeIndex(channelData.ScreenPolicy value) {
        if (value == null) {
            return 0;
        }
        String modeName = value.getModeName();
        char c = 65535;
        if (modeName.hashCode() == 2032856957 && modeName.equals("driveringMode")) {
            c = 0;
        }
        if (c != 0) {
            return 0;
        }
        debugPrint("getVolumeValueModeIndex mMainDriverMode:" + this.mMainDriverMode);
        return this.mMainDriverMode;
    }

    private String getSteamTypeStr(int StreamType) {
        volumeJsonObject.streamVolumePare streamvolumepare = mStreamVolumeType;
        if (streamvolumepare == null || streamvolumepare.volumeTypeList == null) {
            Log.e(TAG, "getSteamTypeStr " + StreamType + " ERROR :" + mStreamVolumeType);
            return "";
        }
        debugPrint("getVolumeType() " + StreamType + " length:" + mStreamVolumeType.volumeTypeList.length);
        for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
            if (mStreamVolumeType.volumeTypeList[i].streamTypeIndex == StreamType) {
                return mStreamVolumeType.volumeTypeList[i].streamType;
            }
        }
        return "";
    }

    private channelData.TypePolicy getTypePolicy(channelData.ScreenPolicy tScreenPolicy) {
        if (tScreenPolicy == null || tScreenPolicy.typePolicy == null) {
            return null;
        }
        int modeindex = getVolumeValueModeIndex(tScreenPolicy);
        for (int i = 0; i < tScreenPolicy.typePolicy.length; i++) {
            if (tScreenPolicy.typePolicy[i].mode == modeindex) {
                debugPrint("getTypePolicy got!!! modeindex:" + modeindex);
                return tScreenPolicy.typePolicy[i];
            }
        }
        return null;
    }

    private channelData.ScreenPolicy getSceenPolicyByStreamType(int streamType) {
        String streamStr = getSteamTypeStr(streamType);
        if (streamStr.equals("") || !checkChannelData()) {
            Log.e(TAG, "getSceenPolicyByStreamType  streamStr:" + streamStr + " ERROR!");
            return null;
        }
        for (int i = 0; i < mChannelData.policy.length; i++) {
            if (getSceenId() == mChannelData.policy[i].getScreenId() && mChannelData.policy[i].screenPolicy != null) {
                for (int j = 0; j < mChannelData.policy[i].screenPolicy.length; j++) {
                    channelData.ScreenPolicy mScreenPolicy = mChannelData.policy[i].screenPolicy[j];
                    if (streamStr.equals(mScreenPolicy.streamType)) {
                        debugPrint("getSceenPolicyByStreamType got!!! " + streamStr);
                        return mScreenPolicy;
                    }
                }
                continue;
            }
        }
        return null;
    }

    private int getSceenId() {
        return 1;
    }

    private boolean checkChannelData() {
        channelData channeldata = mChannelData;
        if (channeldata == null || channeldata.policy == null) {
            return false;
        }
        return true;
    }

    private void doSaveActiveChannelBits(int streamType, int id, int playFlag) {
        Log.d(TAG, "doSaveActiveChannelBits " + streamType + " " + id + "  " + playFlag);
        synchronized (this.activeStreamListObject) {
            if (playFlag == 1) {
                try {
                    if (!this.mActiveStreamList[streamType].contains(Integer.valueOf(id))) {
                        this.mActiveStreamList[streamType].add(Integer.valueOf(id));
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (playFlag == 0 && this.mActiveStreamList[streamType].contains(Integer.valueOf(id))) {
                this.mActiveStreamList[streamType].remove(Integer.valueOf(id));
            }
        }
        Log.d(TAG, "doSaveActiveChannelBits " + streamType + " size" + this.mActiveStreamList[streamType].size());
        if (this.mActiveStreamList[streamType].size() > 0) {
            this.activeChannelBits |= 1 << streamType;
        } else {
            this.activeChannelBits &= ~(1 << streamType);
        }
    }

    private void saveStreamTypeStatus(int streamType, int id, int playFlag) {
        volumeJsonObject.streamVolumePare streamvolumepare;
        if (streamType == 6 || streamType == 9 || streamType == 10) {
            doSaveActiveChannelBits(streamType, id, playFlag);
        }
        if (streamType > 10 && (streamvolumepare = mStreamVolumeType) != null && streamType < streamvolumepare.getMaxStreamType()) {
            doSaveActiveChannelBits(streamType, id, playFlag);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onCarServiceConnected() {
        Log.d(TAG, "onCarServiceConnected()");
        remoteConnect remoteconnect = mRemoteConnect;
        if (remoteconnect != null) {
            this.mXpVehicle = remoteconnect.getXpVehicle();
        }
        if (this.mXpVehicle != null) {
            try {
                Log.i(TAG, "set AMP unMute!!");
                this.mXpVehicle.setAmpMute(0);
            } catch (Exception e) {
                Log.e(TAG, "onCarServiceConnected " + e);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onXuiServiceConnected() {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void igStatusChange(int state) {
        Log.d(TAG, "igStatusChange() " + state);
        if (state == 0) {
            this.mForceSurroundMode = false;
            this.mVoicePosition = -1;
            this.mSpeechChannels = -1;
            initXuiChannelData();
            synchronized (this.activeStreamListObject) {
                for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
                    if (this.mActiveStreamList[i] == null) {
                        this.mActiveStreamList[i] = new ArrayList();
                    }
                    this.mActiveStreamList[i].clear();
                }
                this.mActiveStreamList[3].add(65535);
                this.mActiveStreamList[1].add(65535);
            }
            this.lastSpdifbits = -1;
            this.lastAnalogbits = -1;
            this.lastMixbits = -1;
            this.lastActiveBits = -1;
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setBtHeadPhone(boolean enable) {
        this.mBtHeadPhone = enable;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setBtCallOnFlag(int flag) {
        this.btCallOnFlag = flag;
        triggerChannelChange(6, BLUETOOTH_SCO_COMMON_ID, flag != 0 ? 1 : 0);
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setMainDriverMode(int mode) {
        this.mMainDriverMode = mode;
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setVoicePosition(int position) {
        Log.i(TAG, "setVoicePosition position:" + position);
        switch (position) {
            case -1:
                this.mSpeechChannels = -1;
                break;
            case 0:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONT;
                break;
            case 1:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONTLEFT;
                break;
            case 2:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONTRIGHT;
                break;
            case 3:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_REARLEFT;
                break;
            case 4:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_REARRIGHT;
                break;
            case 5:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_ALL;
                break;
            default:
                Log.e(TAG, "setVoicePosition  ERROR position:" + position);
                return;
        }
        this.mVoicePosition = position;
        triggerChannelChange(10, ACCESSIBILITY_COMMON_ID, this.mVoicePosition != -1 ? 1 : 0);
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public int getVoicePosition() {
        return this.mVoicePosition;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void triggerChannelChange(int streamType, int id, int playFlag) {
        Log.i(TAG, "triggerChannelChange " + streamType + " " + id + " " + playFlag);
        saveStreamTypeStatus(streamType, id, playFlag);
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void changeChannel() {
        if (this.mForceSurroundMode) {
            Log.i(TAG, "changeChannel()  in ForceSurroundMode  do not changeChannel!!");
            return;
        }
        int[] allChannelBit = getAllChannelBits();
        int activeBits = allChannelBit[0];
        int spdifbits = allChannelBit[1];
        int analogbits = allChannelBit[2];
        int mixbits = allChannelBit[3];
        if (this.lastSpdifbits == spdifbits && this.lastAnalogbits == analogbits && this.lastMixbits == mixbits && this.lastActiveBits == activeBits) {
            Log.d(TAG, "changeChannel() same bits as last");
            return;
        }
        this.lastSpdifbits = spdifbits;
        this.lastAnalogbits = analogbits;
        this.lastMixbits = mixbits;
        this.lastActiveBits = activeBits;
        debugPrint("changeChannel spdifbits:" + spdifbits + " analogbits:" + analogbits + " mixbits:" + mixbits + " activeBits:" + activeBits);
        try {
            if (this.mXpVehicle != null && activeBits != 0) {
                if (spdifbits != 0) {
                    this.mXpVehicle.setAmpChannelVolAndSource(spdifbits, 100, 1, activeBits);
                }
                if (analogbits != 0) {
                    this.mXpVehicle.setAmpChannelVolAndSource(analogbits, 100, 2, activeBits);
                }
                if (mixbits != 0) {
                    this.mXpVehicle.setAmpChannelVolAndSource(mixbits, 100, 3, activeBits);
                }
            } else if (this.mXpVehicle != null) {
                this.mXpVehicle.setAmpChannelVolAndSource(0, 100, 3, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "changeChannel:" + e);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        this.mForceSurroundMode = !stop;
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.setAmpChannelVolAndSource(channelBits, volume, 3, activeBits);
            }
        } catch (Exception e) {
            Log.e(TAG, "forceChangeToAmpChannel:" + e);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setStreamPosition(int streamType, String pkgName, int position, int id) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setSoundPositionEnable(boolean enable) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public boolean getSoundPositionEnable() {
        return false;
    }

    private void debugDumpChannelData() {
        channelData channeldata = mChannelData;
        if (channeldata == null || channeldata.policy == null) {
            debugPrint("debugDumpChannelData error:" + mChannelData);
        }
        debugPrint("debugDumpChannelData start");
        for (int i = 0; i < mChannelData.policy.length; i++) {
            try {
                channelData.ScreenPolicy[] tSceenPolicy = mChannelData.policy[i].screenPolicy;
                debugPrint("sceenid:" + mChannelData.policy[i].screenId + " ");
                for (int j = 0; j < tSceenPolicy.length; j++) {
                    debugPrint("  streamType:" + tSceenPolicy[j].streamType + " " + tSceenPolicy[j].modeName);
                    channelData.TypePolicy[] tTypePolicy = tSceenPolicy[j].getTypePolicy();
                    for (int k = 0; k < tTypePolicy.length; k++) {
                        debugPrint("    mode:" + tTypePolicy[k].mode + " type:" + tTypePolicy[k].type + " priority:" + tTypePolicy[k].priority + " channel:" + tTypePolicy[k].channel);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        debugPrint("debugDumpChannelData end!!!");
    }

    private void debugPrint(String str) {
        Log.d(TAG, str);
    }
}
