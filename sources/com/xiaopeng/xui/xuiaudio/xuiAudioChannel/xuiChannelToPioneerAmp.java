package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.channelData;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes2.dex */
public class xuiChannelToPioneerAmp implements IXuiChannelInterface, remoteConnect.RemoteConnectListerer {
    private static final int ACCESSIBILITY_COMMON_ID = 65546;
    public static final int ANALOG = 2;
    private static final int BLUETOOTH_SCO_COMMON_ID = 65542;
    private static final int BUS_ICM = 5;
    private static final int BUS_MEDIA = 0;
    private static final int BUS_NAV = 1;
    private static final int BUS_SPEECH = 2;
    private static final int BUS_SPEECH2 = 4;
    private static final int BUS_SYSTEM = 3;
    private static final int DEF_ALARM_BITS = 16383;
    private static final int DEF_MUSIC_BITS = 4095;
    private static final int DEF_NAV_BITS = 16383;
    private static final int DEF_PHONE_BITS = 16383;
    private static final String DEF_PKGNAME = "xuiAudio";
    private static final int DEF_SPEECH_BITS = 16383;
    private static final int DEF_SYSTEM_BITS = 16383;
    private static final int MAX_BUS_NUM = 5;
    public static final int MIX = 3;
    public static final String MODE_DANGER = "dangerVolLevel";
    public static final String MODE_DRIVING = "driveringMode";
    public static final String MODE_NORMAL = "normal";
    private static final int MUSIC_COMMON_ID = 65539;
    public static final int SPDIF = 1;
    private static final int SYSTEM_COMMON_ID = 65537;
    private static final String TAG = "xuiChannelToPioneerAmp";
    public static final int VOICE_POSITION_ALL = 5;
    public static final int VOICE_POSITION_ALL_ROUND = 16;
    public static final int VOICE_POSITION_CENTER = 0;
    public static final int VOICE_POSITION_FL_FR_RL = 12;
    public static final int VOICE_POSITION_FL_FR_RR = 13;
    public static final int VOICE_POSITION_FL_RL = 8;
    public static final int VOICE_POSITION_FL_RL_RR = 14;
    public static final int VOICE_POSITION_FL_RR = 9;
    public static final int VOICE_POSITION_FRONT = 7;
    public static final int VOICE_POSITION_FRONTLEFT = 1;
    public static final int VOICE_POSITION_FRONTRIGHT = 2;
    public static final int VOICE_POSITION_FR_RL = 10;
    public static final int VOICE_POSITION_FR_RL_RR = 15;
    public static final int VOICE_POSITION_FR_RR = 11;
    public static final int VOICE_POSITION_HEAD = 23;
    public static final int VOICE_POSITION_INVALID = -1;
    public static final int VOICE_POSITION_MIDDLE = 17;
    public static final int VOICE_POSITION_REAR = 6;
    public static final int VOICE_POSITION_REARLEFT = 3;
    public static final int VOICE_POSITION_REARRIGHT = 4;
    public static final int VOICE_POSITION_SL = 21;
    public static final int VOICE_POSITION_SR = 22;
    public static final int VOICE_POSITION_TOP_ALL = 20;
    public static final int VOICE_POSITION_TOP_FRONT = 18;
    public static final int VOICE_POSITION_TOP_REAR = 19;
    public static final int XP_AMP_CHANNEL_1 = 1;
    public static final int XP_AMP_CHANNEL_10 = 512;
    public static final int XP_AMP_CHANNEL_11 = 1024;
    public static final int XP_AMP_CHANNEL_12 = 2048;
    public static final int XP_AMP_CHANNEL_13 = 4096;
    public static final int XP_AMP_CHANNEL_14 = 8192;
    public static final int XP_AMP_CHANNEL_2 = 2;
    public static final int XP_AMP_CHANNEL_3 = 4;
    public static final int XP_AMP_CHANNEL_4 = 8;
    public static final int XP_AMP_CHANNEL_5 = 16;
    public static final int XP_AMP_CHANNEL_6 = 32;
    public static final int XP_AMP_CHANNEL_7 = 64;
    public static final int XP_AMP_CHANNEL_8 = 128;
    public static final int XP_AMP_CHANNEL_9 = 256;
    private static final int XP_AMP_CHANNEL_ALL = 16383;
    private static final int XP_AMP_CHANNEL_ALL_LOCATION = 15;
    private static final int XP_AMP_CHANNEL_ALL_ROUND = 63;
    private static final int XP_AMP_CHANNEL_FL_FR_RL = 7;
    private static final int XP_AMP_CHANNEL_FL_FR_RR = 11;
    private static final int XP_AMP_CHANNEL_FL_RL = 5;
    private static final int XP_AMP_CHANNEL_FL_RL_RR = 13;
    private static final int XP_AMP_CHANNEL_FL_RR = 9;
    private static final int XP_AMP_CHANNEL_FRONT = 67;
    private static final int XP_AMP_CHANNEL_FRONTLEFT = 1;
    private static final int XP_AMP_CHANNEL_FRONTRIGHT = 2;
    private static final int XP_AMP_CHANNEL_FRONT_CENTER = 64;
    private static final int XP_AMP_CHANNEL_FRONT_LOCATION = 3;
    private static final int XP_AMP_CHANNEL_FR_RL = 6;
    private static final int XP_AMP_CHANNEL_FR_RL_RR = 14;
    private static final int XP_AMP_CHANNEL_FR_RR = 10;
    private static final int XP_AMP_CHANNEL_HEAD = 12288;
    private static final int XP_AMP_CHANNEL_INVALID = -1;
    private static final int XP_AMP_CHANNEL_MAIN_DRIVER = 12288;
    private static final int XP_AMP_CHANNEL_MUSIC_7_1_4 = 4095;
    private static final int XP_AMP_CHANNEL_REARAll = 12;
    private static final int XP_AMP_CHANNEL_REARLEFT = 4;
    private static final int XP_AMP_CHANNEL_REARRIGHT = 8;
    private static final int XP_AMP_CHANNEL_SL = 16;
    private static final int XP_AMP_CHANNEL_SR = 32;
    private static channelData mChannelData;
    private static Context mContext;
    private static xuiChannelToPioneerAmp mInstance;
    private static remoteConnect mRemoteConnect;
    private static volumeJsonObject.streamVolumePare mStreamVolumeType;
    private static xuiAudioData mXuiAudioData;
    private static xuiChannelPolicy mXuiChannelPolicy;
    private int activeChannelBits;
    private List<Integer>[] mActiveStreamList;
    private IXpVehicle mXpVehicle;
    private static int mAudioFeature = 0;
    private static boolean DEBUG_DUMP_ENABLE = SystemProperties.getBoolean("persist.sys.xiaopeng.xuiaudio.DebugDump", false);
    private static Object busPositionMapObject = new Object();
    private int mVoicePosition = -1;
    private int btCallOnFlag = 0;
    private boolean mBtHeadPhone = false;
    private int mMainDriverMode = 0;
    private boolean mForceSurroundMode = false;
    private boolean mKaraokeOn = false;
    private int mSpeechChannels = -1;
    private ConcurrentHashMap<Integer, ArrayList<positionInfo>> busPositionMap = new ConcurrentHashMap<Integer, ArrayList<positionInfo>>() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiChannelToPioneerAmp.1
        {
            put(0, new ArrayList());
            put(1, new ArrayList());
            put(2, new ArrayList());
            put(3, new ArrayList());
            put(4, new ArrayList());
            put(5, new ArrayList());
        }
    };
    private final BroadcastReceiver mBootReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiChannelToPioneerAmp.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if ("android.intent.action.ACTION_SHUTDOWN".equals(action) && xuiChannelToPioneerAmp.this.mXpVehicle != null) {
                    Log.i(xuiChannelToPioneerAmp.TAG, "REBOOTING!!!");
                }
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class positionInfo {
        int id;
        String pkgName;
        int position;
        int priority;

        private positionInfo() {
        }
    }

    private xuiChannelToPioneerAmp(xuiChannelPolicy instance, Context context) {
        mContext = context;
        mXuiChannelPolicy = instance;
        mXuiAudioData = xuiAudioData.getInstance(context);
        mRemoteConnect = remoteConnect.getInstance(context);
        mRemoteConnect.registerListener(this);
        initXuiChannelData();
    }

    public static xuiChannelToPioneerAmp getInstance(xuiChannelPolicy instance, Context context) {
        if (mInstance == null) {
            mInstance = new xuiChannelToPioneerAmp(instance, context);
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
    }

    private void restoreBusPositionMap() {
        synchronized (busPositionMapObject) {
            for (int i = 0; i < this.busPositionMap.size(); i++) {
                this.busPositionMap.get(Integer.valueOf(i)).clear();
            }
        }
        dumpBusPositionMap();
    }

    private void updateBusPosition(int busNum, String pkgName, int position, int id, int priority) {
        if (busNum > 5 || busNum <= 0) {
            Log.w(TAG, "updateBusPosition  invalid bus:" + busNum);
            return;
        }
        Log.d(TAG, "updateBusPosition busNum:" + busNum + " pkgName:" + pkgName + " position:" + position + " id:" + id + " priority:" + priority);
        synchronized (busPositionMapObject) {
            ArrayList<positionInfo> mPositionInfoList = this.busPositionMap.get(Integer.valueOf(busNum));
            int i = 0;
            if (position != -1) {
                while (i < mPositionInfoList.size()) {
                    positionInfo mPositionInfo = mPositionInfoList.get(i);
                    if (DEF_PKGNAME.equals(pkgName)) {
                        if (mPositionInfo.id == id) {
                            if (mPositionInfo.position != position) {
                                mPositionInfo.position = position;
                                mPositionInfo.priority = priority;
                                mPositionInfoList.remove(i);
                                mPositionInfoList.add(mPositionInfo);
                                this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
                                return;
                            }
                            return;
                        } else if (mPositionInfo.id == -1 && mPositionInfo.position == position && busNum == 3) {
                            mPositionInfoList.remove(i);
                            mPositionInfo.id = id;
                            mPositionInfoList.add(mPositionInfo);
                            this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
                            return;
                        }
                    } else if (pkgName != null && pkgName.equals(mPositionInfo.pkgName)) {
                        if (mPositionInfo.position != position) {
                            mPositionInfo.position = position;
                            mPositionInfo.priority = priority;
                            mPositionInfoList.remove(i);
                            mPositionInfoList.add(mPositionInfo);
                            this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
                            return;
                        }
                        return;
                    }
                    i++;
                }
                positionInfo mPositionInfo2 = new positionInfo();
                mPositionInfo2.id = id;
                mPositionInfo2.pkgName = pkgName;
                mPositionInfo2.position = position;
                mPositionInfo2.priority = priority;
                mPositionInfoList.add(mPositionInfo2);
                this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
            } else {
                while (i < mPositionInfoList.size()) {
                    positionInfo mPositionInfo3 = mPositionInfoList.get(i);
                    if (DEF_PKGNAME.equals(pkgName)) {
                        if (mPositionInfo3.id == id) {
                            mPositionInfoList.remove(i);
                            this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
                            return;
                        }
                    } else if (pkgName != null && pkgName.equals(mPositionInfo3.pkgName)) {
                        mPositionInfoList.remove(i);
                        this.busPositionMap.put(Integer.valueOf(busNum), mPositionInfoList);
                        return;
                    }
                    i++;
                }
            }
            dumpBusPositionMap();
        }
    }

    private int getBusPosition(int busNum) {
        if (busNum > 5 || busNum <= 0) {
            Log.w(TAG, "getBusPosition  invalid bus:" + busNum);
            return -1;
        }
        int busPosition = -1;
        synchronized (busPositionMapObject) {
            ArrayList<positionInfo> mPositionInfoList = this.busPositionMap.get(Integer.valueOf(busNum));
            int priority = 0;
            for (int i = 0; i < mPositionInfoList.size(); i++) {
                positionInfo mPositionInfo = mPositionInfoList.get(i);
                if (mPositionInfo.priority >= priority) {
                    busPosition = mPositionInfo.position;
                    priority = mPositionInfo.priority;
                }
            }
        }
        return busPosition;
    }

    private void setBusPositionMap(int streamType, String pkgName, int position, int id) {
        if (streamType != 1) {
            if (streamType != 14) {
                if (streamType != 99) {
                    if (streamType != 3) {
                        if (streamType != 4) {
                            if (streamType != 9 && streamType != 10) {
                                Log.w(TAG, "setBusPositionMap  streamType:" + streamType + "  NOT SUPPORTTED!!");
                                return;
                            }
                            return;
                        }
                        updateBusPosition(3, pkgName, position, id, 7);
                        return;
                    }
                    return;
                }
                updateBusPosition(5, pkgName, position, id, 3);
                return;
            }
            updateBusPosition(4, pkgName, position, id, 3);
            return;
        }
        updateBusPosition(3, pkgName, position, id, 3);
    }

    private int getMusicChannelBits() {
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(3));
        int bits = mTypePolicy.channel;
        debugPrint("getMusicChannelBits bits:" + bits);
        return bits;
    }

    private int getSpeechChannelBits() {
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(10));
        int bits = mTypePolicy.channel;
        debugPrint("getSpeechChannelBits bits:" + bits);
        return bits;
    }

    private int getNavChannelBits() {
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(9));
        int bits = mTypePolicy.channel;
        debugPrint("getNavChannelBits bits:" + bits);
        return bits;
    }

    private int getSystemChannelBits() {
        int bit;
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(1));
        int bits = mTypePolicy.channel;
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null && xuiaudiodata.getSoundPositionEnable() && this.mMainDriverMode != 2 && (bit = getBusPosition(3)) != -1) {
            bits = bit;
        }
        debugPrint("getSystemChannelBits bits:" + bits);
        return bits;
    }

    private int getPhoneChannelBits() {
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(6));
        int bits = mTypePolicy.channel;
        debugPrint("getPhoneChannelBits bits:" + bits);
        return bits;
    }

    private int getSpeech2ChannelBits() {
        int bit;
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamType(14));
        int bits = mTypePolicy.channel;
        if (mXuiAudioData != null && (bit = getBusPosition(4)) != -1) {
            bits = bit;
        }
        debugPrint("getSpeech2ChannelBits bits:" + bits);
        return bits;
    }

    private int getKarakokeChannelBits() {
        channelData.TypePolicy mTypePolicy = getTypePolicy(getSceenPolicyByStreamStr(xuiAudioPolicy.STREAM_KARAOKE));
        int bits = mTypePolicy.channel;
        debugPrint("getKarakokeChannelBits bits:" + bits);
        return bits;
    }

    private int getIcmChannelBits() {
        int bit;
        int bits = 3;
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null && xuiaudiodata.getSoundPositionEnable() && (bit = getBusPosition(5)) != -1) {
            bits = bit;
        }
        debugPrint("getIcmChannelBits bits:" + bits);
        return bits;
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

    private channelData.ScreenPolicy getSceenPolicyByStreamStr(String streamStr) {
        if (streamStr.equals("") || !checkChannelData()) {
            Log.e(TAG, "getSceenPolicyByStreamStr  streamStr:" + streamStr + " ERROR!");
            return null;
        }
        for (int i = 0; i < mChannelData.policy.length; i++) {
            if (getSceenId() == mChannelData.policy[i].getScreenId() && mChannelData.policy[i].screenPolicy != null) {
                for (int j = 0; j < mChannelData.policy[i].screenPolicy.length; j++) {
                    channelData.ScreenPolicy mScreenPolicy = mChannelData.policy[i].screenPolicy[j];
                    if (streamStr.equals(mScreenPolicy.streamType)) {
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

    private byte[] mergeAmpData(byte[] data, int channelbits, int bytePosition) {
        for (int i = 0; i < 14; i++) {
            data[i] = (byte) (data[i] | ((byte) (((channelbits >> i) & 1) << bytePosition)));
            data[i] = (byte) (data[i] | 1);
            debugPrint("mergeAmpData: data[" + i + "]: " + ((int) data[i]) + "   position:" + bytePosition + "   " + (1 << bytePosition) + " (channelbits>>i):" + (channelbits >> i) + "  " + ((channelbits >> i) & 1) + "  " + (((channelbits >> i) & 1) << bytePosition));
        }
        return data;
    }

    private byte[] getAmpChannelSwBuffer() {
        byte[] data;
        byte[] data2;
        byte[] data3 = new byte[16];
        int musicbits = getMusicChannelBits();
        debugPrint("merge music ########:");
        byte[] data4 = mergeAmpData(data3, musicbits, 6);
        int navbits = getNavChannelBits();
        debugPrint("merge nav ########:");
        byte[] data5 = mergeAmpData(data4, navbits, 5);
        debugPrint("merge system ########:");
        if (this.mKaraokeOn) {
            int karaokebits = getKarakokeChannelBits();
            data = mergeAmpData(data5, karaokebits, 3);
        } else {
            int sysbits = getSystemChannelBits();
            data = mergeAmpData(data5, sysbits, 3);
        }
        debugPrint("merge icm ########:");
        int icmbits = getIcmChannelBits();
        byte[] data6 = mergeAmpData(data, icmbits, 1);
        debugPrint("merge phone ########:");
        if (this.btCallOnFlag != 0) {
            int phonebits = getPhoneChannelBits();
            data2 = mergeAmpData(data6, phonebits, 4);
        } else {
            int speechbits = this.mSpeechChannels;
            if (speechbits == -1 || this.mMainDriverMode == 2) {
                speechbits = getSpeechChannelBits();
            }
            data2 = mergeAmpData(data6, speechbits, 4);
        }
        debugPrint("merge speech2 ########:");
        int speech2bits = getSpeech2ChannelBits();
        return mergeAmpData(data2, speech2bits, 2);
    }

    private byte[] getAmpChannelVolumeBuffer() {
        byte[] data = new byte[16];
        for (int i = 0; i < 14; i++) {
            data[i] = 100;
        }
        return data;
    }

    private void doSaveActiveChannelBits(int streamType, int id, int playFlag) {
    }

    private void saveStreamTypeStatus(int streamType, int id, int playFlag) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onCarServiceConnected() {
        Log.d(TAG, "onCarServiceConnected()");
        remoteConnect remoteconnect = mRemoteConnect;
        if (remoteconnect != null) {
            this.mXpVehicle = remoteconnect.getXpVehicle();
            changeChannel();
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onXuiServiceConnected() {
    }

    private int changePositionToBits(int position) {
        switch (position) {
            case -1:
                return -1;
            case 0:
                return 67;
            case 1:
                return 1;
            case 2:
                return 2;
            case 3:
                return 4;
            case 4:
                return 8;
            case 5:
                return 15;
            case 6:
                return 12;
            case 7:
                return 3;
            case 8:
                return 5;
            case 9:
                return 9;
            case 10:
                return 6;
            case 11:
                return 10;
            case 12:
                return 7;
            case 13:
                return 11;
            case 14:
                return 13;
            case 15:
                return 14;
            default:
                switch (position) {
                    case 21:
                        return 16;
                    case 22:
                        return 32;
                    case 23:
                        return 12288;
                    default:
                        Log.e(TAG, "changePositionToBits  ERROR position:" + position);
                        return -1;
                }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void igStatusChange(int state) {
        Log.d(TAG, "igStatusChange() " + state);
        if (state == 0) {
            this.btCallOnFlag = 0;
            this.mBtHeadPhone = false;
            this.mSpeechChannels = -1;
            this.mVoicePosition = -1;
            this.mKaraokeOn = false;
        }
        restoreBusPositionMap();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setBtHeadPhone(boolean enable) {
        this.mBtHeadPhone = enable;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setBtCallOnFlag(int flag) {
        this.btCallOnFlag = flag;
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setMainDriverMode(int mode) {
        this.mMainDriverMode = mode;
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setVoicePosition(int position) {
        Log.i(TAG, "setVoicePosition position:" + position);
        this.mSpeechChannels = changePositionToBits(position);
        this.mVoicePosition = position;
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public int getVoicePosition() {
        return this.mVoicePosition;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setStreamPosition(int streamType, String pkgName, int position, int id) {
        Log.d(TAG, "setStreamPosition  " + streamType + " " + pkgName + " " + position + " " + id);
        setBusPositionMap(streamType, pkgName, changePositionToBits(position), id);
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            if (xuiaudiodata.getSoundPositionEnable() || streamType == 14) {
                changeChannel();
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void setSoundPositionEnable(boolean enable) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setSoundPositionEnable(enable);
        }
        changeChannel();
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public boolean getSoundPositionEnable() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getSoundPositionEnable();
        }
        return false;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void triggerChannelChange(int streamType, int id, int playFlag) {
        Log.i(TAG, "triggerChannelChange " + streamType + " " + id + " " + playFlag);
        if (streamType == xuiAudioPolicy.STREAM_TYPE_KARAOKE) {
            this.mKaraokeOn = playFlag == 1;
            changeChannel();
        } else if (playFlag == 0) {
            setStreamPosition(streamType, DEF_PKGNAME, -1, id);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public synchronized void changeChannel() {
        if (this.mForceSurroundMode) {
            Log.i(TAG, "changeChannel()  in ForceSurroundMode  do not changeChannel!!");
            return;
        }
        try {
            byte[] swData = getAmpChannelSwBuffer();
            byte[] volData = getAmpChannelVolumeBuffer();
            Log.d(TAG, "changeChannel dataLen:" + swData.length + " data:" + Arrays.toString(swData) + " volLen:" + volData.length + " vol:" + Arrays.toString(volData));
            this.mXpVehicle.setAmpChannelSwitchControlStatus(swData);
            this.mXpVehicle.setAmpChannelVolumeControlValue(volData);
        } catch (Exception e) {
            Log.e(TAG, "changeChannel:" + e);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioChannel.IXuiChannelInterface
    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        this.mForceSurroundMode = !stop;
    }

    private void debugDumpChannelData() {
    }

    private void dumpBusPositionMap() {
        if (!DEBUG_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, "dumpBusPositionMap start");
        for (int i = 0; i < this.busPositionMap.size(); i++) {
            ArrayList<positionInfo> mPositionInfoList = this.busPositionMap.get(Integer.valueOf(i));
            Log.d(TAG, "   i :" + i + "  --------------------------------");
            for (int j = 0; j < mPositionInfoList.size(); j++) {
                positionInfo mPositionInfo = mPositionInfoList.get(j);
                Log.d(TAG, "   j :" + j + " " + mPositionInfo);
                if (mPositionInfo != null) {
                    Log.d(TAG, "   j :" + j + " id:" + mPositionInfo.id + " pkgName:" + mPositionInfo.pkgName + " position:" + mPositionInfo.position + " priority:" + mPositionInfo.priority);
                }
            }
        }
        Log.d(TAG, "dumpBusPositionMap end");
    }

    private void debugPrint(String str) {
        if (!DEBUG_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, str);
    }
}
