package com.xiaopeng.xui.xuiaudio.xuiAudioVolume;

import android.content.Context;
import android.content.Intent;
import android.media.AudioSystem;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* loaded from: classes2.dex */
public class xuiVolumePolicy {
    private static final String ANDROIDSYS_PKGNAME = "android";
    private static final String AUDIO_EXTRA_AUTOVOLUME_FLAG = "android.media.EXTRA_AUTOVOLUME_FLAG";
    private static final String AUDIO_EXTRA_MUSIC_RUNNING_FLAG = "android.media.EXTRA_HAS_MUSIC_RUNNING_FLAG";
    private static final String AUDIO_EXTRA_VOLUME_STREAM_FLAG = "android.media.EXTRA_VOLUME_STREAM_FLAG";
    private static final String AUDIO_SYSTEM_PACKAGENAME = "android.audio.System";
    private static final String BTCALL_VOLCHANGE_PACKAGE = "xpaudio_btcall";
    private static final int CONFLICTPOLICY_MODE_DOWN = 1;
    private static final int DEF_FADE_TIME = 500;
    private static final String ENV_TRIGGER_BTPHONE = "BT_PHONE";
    private static final String ENV_TRIGGER_FLDOOR = "FL_DOOR";
    private static final String ENV_TRIGGER_GEAR = "GEAR";
    private static final String ENV_TRIGGER_SYSVOL_DIRECTION_DOWN = "downBySystemVolume";
    private static final String ENV_TRIGGER_SYSVOL_DIRECTION_NONE = "triggerBySystemVolume";
    private static final String ENV_TRIGGER_SYSVOL_DIRECTION_UP = "upBySystemVolume";
    public static final String EXTMODE_BTPHONE = "btPhone";
    public static final String EXTMODE_NORMAL = "normal";
    private static final String EXTRA_OTHERPLAYING_PACKAGENAME = "android.media.other_musicplaying.PACKAGE_NAME";
    private static final String EXTRA_VOLCHANGE_PACKAGENAME = "android.media.vol_change.PACKAGE_NAME";
    private static final int INVALID_DSTVOL = -1;
    public static final String MODE_DANGER = "dangerVolLevel";
    public static final String MODE_DRIVING = "driveringMode";
    public static final String MODE_NORMAL = "normal";
    private static final int MSG_ADJUSTSTREAM_VOLUME = 2;
    private static final int MSG_SETSTREAM_VOLUME = 1;
    private static final int MSG_VOLUME_FADING = 100;
    private static final String SETTINGS_PACKAGE = "com.xiaopeng.car.settings";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String TAG = "xuiVolumePolicy";
    private static final int TEMPVOLCHANGE_FLAG_AEB = 128;
    private static final int TEMPVOLCHANGE_FLAG_BOOT = 64;
    private static final int TEMPVOLCHANGE_FLAG_BTCALL = 4;
    private static final int TEMPVOLCHANGE_FLAG_DANGERTTS = 8;
    private static final int TEMPVOLCHANGE_FLAG_DOOR = 2;
    private static final int TEMPVOLCHANGE_FLAG_GEAR = 1;
    private static final int TEMPVOLCHANGE_FLAG_XUIALARM = 16;
    private static final int TEMPVOLCHANGE_FLAG_ZENMODE = 32;
    public static final int VALIDVOL_RESET_FLAG = 8;
    private static final int VOL_CHANGE_DIRECTION_DOWN = 1;
    private static final int VOL_CHANGE_DIRECTION_NONE = 0;
    private static final int VOL_CHANGE_DIRECTION_UP = 2;
    private static final String XP_VOLUME_DANGEROUS = "XP_VOLUME_DANGEROUS";
    private static final String XP_VOLUME_HFP = "XP_VOLUME_HFP";
    private static final String XP_VOLUME_KARAOKE = "XP_VOLUME_KARAOKE";
    private static final String XP_VOLUME_MEDIA = "XP_VOLUME_MEDIA";
    private static final String XP_VOLUME_NAVIGATION = "XP_VOLUME_NAVIGATION";
    private static final String XP_VOLUME_SPEECH = "XP_VOLUME_SPEECH";
    private static final String XP_VOLUME_SYSTEM = "XP_VOLUME_SYSTEM";
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static final String XUI_SERVICE_PACKAGE2 = "com.xiaopeng.xuiservice2";
    private static final long adjustInterval = 50;
    private static int ampValue;
    private static Object fadingLock;
    private static long lastAdjustTime;
    private static volumeJsonObject.conflictVolumeObject mConflictVolumeObject;
    private static Context mContext;
    private static xuiVolumePolicy mInstance;
    private static volumeJsonObject.streamVolumePare mStreamVolumeType;
    private static volumeJsonObject.volumeTypeData mVolumeTypeData;
    private static xuiAudioData mXuiAudioData;
    private static xuiAudioPolicy mXuiAudioPolicy;
    private static xuiExternalAudioPath mXuiExternalAudioPath;
    private static IXuiVolumeInterface mXuiVolumeToHal;
    private static boolean passengerBtExist;
    private static Object xpAuioBroadcastLock;
    private boolean[] VolumeFadeBreakFlag;
    private boolean[] mFadingFlag;
    private boolean[] mFixedVolumeMode;
    private Handler mHandler;
    private Intent mVolumeChanged;
    private int[] mXpVolBanMode;
    private int musicRestoreVolume;
    private static int mAudioFeature = 0;
    private static int MaxVol = 30;
    private static int MinVol = 0;
    private static int BaseDafaultVol = 15;
    boolean mIsMusicLimitmode = false;
    private HandlerThread mHandlerThread = null;
    private final ArrayList<tempVolChangeData> mTempVolChangeDataList = new ArrayList<>();
    private final ArrayList<FixedVolInfo> mFixedPkgList = new ArrayList<>();
    private boolean isHighAmp = FeatureOption.hasFeature("AMP");

    static {
        passengerBtExist = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST > 0;
        ampValue = SystemProperties.getInt("persist.sys.xiaopeng.AMP", 0);
        xpAuioBroadcastLock = new Object();
        fadingLock = new Object();
        lastAdjustTime = -1L;
    }

    private xuiVolumePolicy(Context context, xuiAudioPolicy instance) {
        this.musicRestoreVolume = this.isHighAmp ? FeatureOption.FO_MEDIA_RESTORE_VOLUME_AMP : FeatureOption.FO_MEDIA_RESTORE_VOLUME;
        mContext = context;
        mXuiAudioPolicy = instance;
        Log.d(TAG, "xuiVolumePolicy()");
        initXuiVolumePolicy(context, instance);
        initPrivateData();
        clearTempVolChangeData();
        restoreTempChangeVol();
    }

    public static xuiVolumePolicy getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiVolumePolicy(context, instance);
        }
        return mInstance;
    }

    private void initXuiVolumePolicy(Context context, xuiAudioPolicy instance) {
        volumeJsonObject.streamVolumePare streamvolumepare;
        volumeJsonObject.streamVolumePare streamvolumepare2;
        volumeJsonObject.streamVolumePare streamvolumepare3;
        if (mStreamVolumeType == null) {
            mStreamVolumeType = xuiAudioParser.parseStreamVolumePare();
        }
        if (mVolumeTypeData == null) {
            mVolumeTypeData = xuiAudioParser.parseVolumeData();
        }
        if (mConflictVolumeObject == null) {
            mConflictVolumeObject = xuiAudioParser.parseConflictVolumePolicy();
        }
        if (this.mFixedVolumeMode == null && (streamvolumepare3 = mStreamVolumeType) != null) {
            this.mFixedVolumeMode = new boolean[streamvolumepare3.getMaxStreamType()];
            Arrays.fill(this.mFixedVolumeMode, false);
        }
        if (this.mXpVolBanMode == null && (streamvolumepare2 = mStreamVolumeType) != null) {
            this.mXpVolBanMode = new int[streamvolumepare2.getMaxVolType()];
        }
        if (this.mFadingFlag == null && (streamvolumepare = mStreamVolumeType) != null) {
            this.mFadingFlag = new boolean[streamvolumepare.getMaxStreamType()];
        }
        if (mXuiAudioData == null) {
            mXuiAudioData = xuiAudioData.getInstance(context);
        }
        if (mXuiVolumeToHal == null) {
            mXuiVolumeToHal = xuiVolumeToHal.getInstance(this, context);
        }
        if (this.mVolumeChanged == null) {
            this.mVolumeChanged = new Intent("android.media.VOLUME_CHANGED_ACTION");
        }
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread(TAG);
            this.mHandlerThread.start();
        }
        if (passengerBtExist) {
            mXuiExternalAudioPath = xuiExternalAudioPath.getInstance(context);
        }
        debugDumpVolumePare();
        debugDumpmVolumeTypeData();
        debugDumpConflictVolumeObject();
        if (this.mHandler == null) {
            this.mHandler = new xuiVolumeHandler(this.mHandlerThread.getLooper());
        }
    }

    private void initPrivateData() {
        try {
            Arrays.fill(this.mXpVolBanMode, 0);
            Arrays.fill(this.mFadingFlag, false);
            MaxVol = mVolumeTypeData.getMaxVol();
            MinVol = mVolumeTypeData.getMinVol();
            this.mIsMusicLimitmode = isMusicLimitMode();
        } catch (Exception e) {
            handleException("initPrivateData() ", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class FixedVolInfo {
        int StreamType;
        int fixedVol;
        String packageName;

        private FixedVolInfo() {
        }
    }

    private void resetFixedVolumeList() {
        this.mFixedPkgList.clear();
    }

    private void addFixedVolumeInfo(int fixedvol, int streamType, String pkgName) {
        for (int i = 0; i < this.mFixedPkgList.size(); i++) {
            FixedVolInfo tempFixedInfo = this.mFixedPkgList.get(i);
            if (tempFixedInfo.StreamType == streamType && pkgName.equals(tempFixedInfo.packageName)) {
                return;
            }
        }
        FixedVolInfo newFixedInfo = new FixedVolInfo();
        newFixedInfo.fixedVol = fixedvol;
        newFixedInfo.StreamType = streamType;
        newFixedInfo.packageName = pkgName;
        this.mFixedPkgList.add(newFixedInfo);
        dumpFixedVolList("addFixedVolume");
    }

    private void removeFixedVolumeInfo(int streamType, String pkgName) {
        for (int i = 0; i < this.mFixedPkgList.size(); i++) {
            FixedVolInfo tempFixedInfo = this.mFixedPkgList.get(i);
            if (tempFixedInfo.StreamType == streamType && pkgName.equals(tempFixedInfo.packageName)) {
                this.mFixedPkgList.remove(i);
            }
        }
        dumpFixedVolList("removeFixedVolume");
    }

    private int getVolumeFixedInfoByType(int streamType) {
        int retVol = 100;
        for (int i = 0; i < this.mFixedPkgList.size(); i++) {
            FixedVolInfo tempFixedInfo = this.mFixedPkgList.get(i);
            if (tempFixedInfo.StreamType == streamType && tempFixedInfo.fixedVol < retVol) {
                retVol = tempFixedInfo.fixedVol;
            }
        }
        if (retVol >= 0 && retVol <= 30) {
            return retVol;
        }
        return -1;
    }

    private void dumpFixedVolList(String func) {
        if (xuiAudioPolicy.DEBUG_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, "dumpFixedVolList " + func + " size:" + this.mFixedPkgList.size());
        for (int i = 0; i < this.mFixedPkgList.size(); i++) {
            FixedVolInfo tempFixedInfo = this.mFixedPkgList.get(i);
            Log.d(TAG, "dumpFixedVolList i:" + i + " type:" + tempFixedInfo.StreamType + " fixVol:" + tempFixedInfo.fixedVol + " " + tempFixedInfo.packageName);
        }
    }

    public synchronized void igStatusChange(int status) {
        Log.d(TAG, "igStatusChange() " + status);
        if (status == 1) {
            if (!FeatureOption.FO_DEVICE_INTERNATIONAL_ENABLED) {
                resetFixedVolumeList();
                for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
                    if (this.mFixedVolumeMode[i]) {
                        setFixedVolume(false, 15, i, "xuiaudio");
                        this.mFixedVolumeMode[i] = false;
                    }
                }
            }
            restoreTempChangeVol();
            setBtCallOnFlag(0, false);
            initPrivateData();
        }
    }

    public void resetAllVolume() {
        int volume = getStreamVolume(3);
        if (mXuiAudioPolicy.isOtherSessionOn() || mXuiAudioPolicy.isKaraokeOn()) {
        }
        boolean broadcastMuteFlag = this.mIsMusicLimitmode;
        int index = broadcastMuteFlag ? volume / 2 : volume;
        sendVolumeValueOut(3, index);
        doSetStreamVolume(3, index, 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        broadcastVolumeChangedToAll(3, volume, volume, 0, AUDIO_SYSTEM_PACKAGENAME);
        setVolWhenPlay(0);
        setVolWhenPlay(9);
        int volume2 = getStreamVolume(9);
        broadcastVolumeChangedToAll(9, volume2, volume2, 0, AUDIO_SYSTEM_PACKAGENAME);
        doSetStreamVolume(1, getStreamVolume(1), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        if (FeatureOption.FO_AVAS_SUPPORT_TYPE == 1) {
            Log.d(TAG, "resetAllVolume  avas:" + getStreamVolume(11));
            doSetStreamVolume(11, getStreamVolume(11), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        mXuiAudioPolicy.setDangerousTtsStatus(0);
    }

    public void audioServerDiedRestore() {
        int index;
        Log.d(TAG, "audioServerDiedRestore  start");
        doSetStreamVolume(3, getStreamMute(3) ? 0 : getStreamVolume(3), 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        doSetStreamVolume(1, getStreamVolume(1), 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        if (FeatureOption.FO_AVAS_SUPPORT_TYPE == 1) {
            Log.d(TAG, "audioServerDiedRestore  avas:" + getStreamVolume(11));
            doSetStreamVolume(11, getStreamVolume(11), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        if ((mXuiAudioPolicy.getBtCallOnFlag() == 2 || mXuiAudioPolicy.getBtCallOnFlag() == 3) && mXuiAudioPolicy.getBtCallMode() == 1) {
            doSetStreamVolume(6, getStreamVolume(6), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        } else if (!getStreamMute(2)) {
            doSetStreamVolume(2, getStreamVolume(2), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        if (mXuiAudioData.getDangerousTtsStatus() == 0) {
            if (getStreamMute(9)) {
                index = 0;
            } else {
                int index2 = getStreamVolume(9);
                index = index2;
            }
            doSetStreamVolume(9, index, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        } else {
            int StreamDangerous = getStreamTypeIndex(xuiAudioPolicy.STREAM_DANGEROUS);
            int index3 = mXuiAudioData.getStreamVolume(StreamDangerous);
            doSetStreamVolume(StreamDangerous, index3, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        Log.d(TAG, "audioServerDiedRestore  end");
    }

    private boolean checkVolumeDataExist() {
        volumeJsonObject.streamVolumePare streamvolumepare = mStreamVolumeType;
        if (streamvolumepare == null || streamvolumepare.volumeTypeList == null) {
            Log.e(TAG, "checkVolumeDataExist  volumeType IS NULL");
            return false;
        }
        return true;
    }

    private boolean checkVolumeObjectDataExist() {
        volumeJsonObject.volumeTypeData volumetypedata = mVolumeTypeData;
        if (volumetypedata == null || volumetypedata.volumeTypeDataList == null) {
            Log.e(TAG, "getVolumeType  volumeType IS NULL");
            return false;
        }
        return true;
    }

    private String getVolumeType(int StreamType) {
        if (checkVolumeDataExist()) {
            debugPrintLog("getVolumeType() " + StreamType + " length:" + mStreamVolumeType.volumeTypeList.length);
            for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
                debugPrintLog("getVolumeType() i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " voltype:" + mStreamVolumeType.volumeTypeList[i].volumeType);
                if (mStreamVolumeType.volumeTypeList[i].streamTypeIndex == StreamType) {
                    return mStreamVolumeType.volumeTypeList[i].volumeType;
                }
            }
            return "";
        }
        return "";
    }

    private String getVolumeTypeByStr(String StreamType) {
        if (checkVolumeDataExist()) {
            debugPrintLog("getVolumeType() " + StreamType + " length:" + mStreamVolumeType.volumeTypeList.length);
            for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
                debugPrintLog("getVolumeType() i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " voltype:" + mStreamVolumeType.volumeTypeList[i].volumeType);
                if (mStreamVolumeType.volumeTypeList[i].streamType.equals(StreamType)) {
                    return mStreamVolumeType.volumeTypeList[i].volumeType;
                }
            }
            return "";
        }
        return "";
    }

    private int getStreamTypeIndex(String streamType) {
        if (checkVolumeDataExist()) {
            debugPrintLog("getStreamTypeIndex() " + streamType + " length:" + mStreamVolumeType.volumeTypeList.length);
            for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
                debugPrintLog("getStreamTypeIndex() i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " streamType:" + mStreamVolumeType.volumeTypeList[i].streamType);
                if (mStreamVolumeType.volumeTypeList[i].streamType.equals(streamType)) {
                    return mStreamVolumeType.volumeTypeList[i].streamTypeIndex;
                }
            }
            return -1;
        }
        return -1;
    }

    private int getVolumeTypeId(int StreamType) {
        if (checkVolumeDataExist()) {
            for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
                if (mStreamVolumeType.volumeTypeList[i].streamTypeIndex == StreamType) {
                    return mStreamVolumeType.volumeTypeList[i].volumeTypeIndex;
                }
            }
            return -1;
        }
        return -1;
    }

    private int getGroupId(String volumeType) {
        debugPrintLog("getGroupId type:" + volumeType);
        if (checkVolumeObjectDataExist()) {
            for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
                if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                    debugPrintLog("getGroupId " + mVolumeTypeData.volumeTypeDataList[i].groupId);
                    return mVolumeTypeData.volumeTypeDataList[i].groupId;
                }
            }
            return -1;
        }
        return -1;
    }

    private boolean checkStreamConfigedFixed(int StreamType) {
        if (checkVolumeObjectDataExist()) {
            String volType = getVolumeType(StreamType);
            for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
                if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volType) && mVolumeTypeData.volumeTypeDataList[i].isFixed) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private int getVolumeValueModeIndex(volumeJsonObject.volumeTypeData.VolumeTypeData value) {
        if (value == null) {
            return 0;
        }
        String mode = value.getMode();
        char c = 65535;
        int hashCode = mode.hashCode();
        if (hashCode != -902359628) {
            if (hashCode == 2032856957 && mode.equals("driveringMode")) {
                c = 0;
            }
        } else if (mode.equals("dangerVolLevel")) {
            c = 1;
        }
        if (c != 0) {
            if (c != 1) {
                return 0;
            }
            return getDangerousTtsVolLevel();
        }
        return mXuiAudioPolicy.getMainDriverMode();
    }

    private int getDefaultVolume(int StreamType) {
        if (!checkVolumeObjectDataExist()) {
            return BaseDafaultVol;
        }
        debugPrintLog("getDefaultVolume " + StreamType);
        String volType = getVolumeType(StreamType);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volType) && mVolumeTypeData.volumeTypeDataList[i].volumeValue != null) {
                for (int j = 0; j < mVolumeTypeData.volumeTypeDataList[i].volumeValue.length; j++) {
                    volumeJsonObject.volumeTypeData.VolumeValue mVolumeValue = mVolumeTypeData.volumeTypeDataList[i].volumeValue[j];
                    if (getVolumeValueModeIndex(mVolumeTypeData.volumeTypeDataList[i]) == mVolumeValue.modeindex && (mVolumeValue.ampValue == -1 || mVolumeValue.ampValue == ampValue)) {
                        debugPrintLog("getDefaultVolume modeIndex:" + mVolumeValue.modeindex + " defaultVal:" + mVolumeValue.defaultVal);
                        return mVolumeValue.defaultVal;
                    }
                }
                continue;
            }
        }
        int i2 = BaseDafaultVol;
        return i2;
    }

    private String getPropertyForVolumeType(String volumeType) {
        if (checkVolumeObjectDataExist()) {
            debugPrintLog("getPropertyForVolumeType " + volumeType);
            for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
                if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                    debugPrintLog("getPropertyForVolumeType property:" + mVolumeTypeData.volumeTypeDataList[i].property);
                    return mVolumeTypeData.volumeTypeDataList[i].property;
                }
            }
            return "";
        }
        return "";
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private boolean checkVolumeTypeIsOn(String volumeType) {
        char c;
        xuiAudioPolicy xuiaudiopolicy;
        int temp;
        boolean ret = false;
        switch (volumeType.hashCode()) {
            case -1273201210:
                if (volumeType.equals(XP_VOLUME_KARAOKE)) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case -721976622:
                if (volumeType.equals(XP_VOLUME_NAVIGATION)) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 201431200:
                if (volumeType.equals(XP_VOLUME_SPEECH)) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 210174445:
                if (volumeType.equals(XP_VOLUME_SYSTEM)) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case 1194309036:
                if (volumeType.equals(XP_VOLUME_DANGEROUS)) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 1709130772:
                if (volumeType.equals(XP_VOLUME_HFP)) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 1801743430:
                if (volumeType.equals(XP_VOLUME_MEDIA)) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        if (c == 0) {
            xuiAudioPolicy xuiaudiopolicy2 = mXuiAudioPolicy;
            if (xuiaudiopolicy2 != null) {
                ret = xuiaudiopolicy2.isKaraokeOn();
            }
        } else if (c == 1) {
            xuiAudioPolicy xuiaudiopolicy3 = mXuiAudioPolicy;
            if (xuiaudiopolicy3 != null) {
                ret = xuiaudiopolicy3.getDangerousTtsStatus() != 0;
            }
        } else if (c == 2 && (xuiaudiopolicy = mXuiAudioPolicy) != null && (((temp = xuiaudiopolicy.getBtCallOnFlag()) == 2 || temp == 3) && mXuiAudioPolicy.getBtCallMode() == 1)) {
            ret = true;
        }
        Log.d(TAG, "checkVolumeTypeIsOn " + volumeType + " " + ret);
        return ret;
    }

    private boolean checkIsHighPriorityTypeRunning(int streamtype) {
        if (checkVolumeObjectDataExist()) {
            String volumeType = getVolumeType(streamtype);
            int groupid = getGroupId(volumeType);
            int currentPriority = 0;
            debugPrintLog("checkIsHighPriorityExist streamtype:" + streamtype + " groupid:" + groupid + " volumeType:" + volumeType);
            int i = 0;
            while (true) {
                if (i < mVolumeTypeData.volumeTypeDataList.length) {
                    if (!mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                        i++;
                    } else {
                        currentPriority = mVolumeTypeData.volumeTypeDataList[i].priority;
                        break;
                    }
                } else {
                    break;
                }
            }
            for (int i2 = 0; i2 < mVolumeTypeData.volumeTypeDataList.length; i2++) {
                if (mVolumeTypeData.volumeTypeDataList[i2].groupId == groupid && mVolumeTypeData.volumeTypeDataList[i2].priority > currentPriority && checkVolumeTypeIsOn(mVolumeTypeData.volumeTypeDataList[i2].volumeType)) {
                    debugPrintLog("checkIsHighPriorityTypeRunning  " + mVolumeTypeData.volumeTypeDataList[i2].volumeType + " is running  priority:" + mVolumeTypeData.volumeTypeDataList[i2].priority + " currentPriority:" + currentPriority);
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private String getHighPriorityRunningType(int streamtype) {
        if (checkVolumeObjectDataExist()) {
            String volumeType = getVolumeType(streamtype);
            int groupid = getGroupId(volumeType);
            int currentPriority = 0;
            debugPrintLog("getHighPriorityRunningType streamtype:" + streamtype + " groupid:" + groupid + " volumeType:" + volumeType);
            int i = 0;
            while (true) {
                if (i < mVolumeTypeData.volumeTypeDataList.length) {
                    if (!mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                        i++;
                    } else {
                        currentPriority = mVolumeTypeData.volumeTypeDataList[i].priority;
                        break;
                    }
                } else {
                    break;
                }
            }
            for (int i2 = 0; i2 < mVolumeTypeData.volumeTypeDataList.length; i2++) {
                if (mVolumeTypeData.volumeTypeDataList[i2].groupId == groupid && mVolumeTypeData.volumeTypeDataList[i2].priority > currentPriority && checkVolumeTypeIsOn(mVolumeTypeData.volumeTypeDataList[i2].volumeType)) {
                    debugPrintLog("getHighPriorityRunningType  " + mVolumeTypeData.volumeTypeDataList[i2].volumeType + " is running  priority:" + mVolumeTypeData.volumeTypeDataList[i2].priority + " currentPriority:" + currentPriority);
                    return mVolumeTypeData.volumeTypeDataList[i2].volumeType;
                }
            }
            return null;
        }
        return null;
    }

    private boolean checkVolTypeFixed(int StreamType) {
        String volType = getVolumeType(StreamType);
        for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
            if (this.mFixedVolumeMode[i] && volType.equals(getVolumeType(i))) {
                Log.w(TAG, "checkVolTypeFixed streamType:" + StreamType + " find same voltype:" + volType + " is fixed, the  type is:" + i);
                return true;
            }
        }
        return false;
    }

    private volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger[] getEnvTriggerObject(int screenId, int ampLevel) {
        volumeJsonObject.conflictVolumeObject conflictvolumeobject = mConflictVolumeObject;
        if (conflictvolumeobject == null || conflictvolumeobject.policy == null) {
            return null;
        }
        debugPrintLog("getEnvTriggerObject " + screenId + " " + ampLevel);
        for (int i = 0; i < mConflictVolumeObject.policy.length; i++) {
            if (mConflictVolumeObject.policy[i].screenId == screenId && mConflictVolumeObject.policy[i].ampLevel == ampLevel) {
                debugPrintLog("getEnvTriggerObject " + screenId + " " + ampLevel + " got!!");
                return mConflictVolumeObject.policy[i].envtriggerChange;
            }
        }
        return null;
    }

    private volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger getEnvTriggerByFlag(int flag) {
        boolean hasFeature = FeatureOption.hasFeature("AMP");
        int screenId = getScreenId();
        int ampLevel = hasFeature ? 1 : 0;
        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger[] envTriggerList = getEnvTriggerObject(screenId, ampLevel);
        debugPrintLog("getEnvTriggerByFlag flag:" + flag + " envTriggerList:" + envTriggerList);
        for (int i = 0; i < envTriggerList.length; i++) {
            debugPrintLog("getEnvTriggerByFlag i:" + i + " triggerFlag:" + envTriggerList[i].triggerFlag);
            if (envTriggerList[i].triggerFlag == flag) {
                return envTriggerList[i];
            }
        }
        debugPrintLog("getEnvTriggerByFlag flag:" + flag + " not found");
        return null;
    }

    private int getConflictPolicyModeIndex(volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger mPolicy) {
        if (mPolicy == null) {
            return -1;
        }
        String modeName = mPolicy.getModeName();
        if ((modeName.hashCode() == 2032856957 && modeName.equals("driveringMode")) ? false : true) {
            return -1;
        }
        return mXuiAudioPolicy.getMainDriverMode();
    }

    private volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.conflictModePolicy[] getEnvConflictModePolicy(int flag) {
        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger tEnvTrigger = getEnvTriggerByFlag(flag);
        if (tEnvTrigger == null) {
            return null;
        }
        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.conflictPolicy[] tConflictPolicy = tEnvTrigger.getPolicy();
        int modeindex = getConflictPolicyModeIndex(tEnvTrigger);
        debugPrintLog("getEnvConflictModePolicy flag:" + flag + " ConflictPolicy-length:" + tConflictPolicy.length + " modeindex:" + modeindex);
        for (int i = 0; i < tConflictPolicy.length; i++) {
            if (tConflictPolicy[i].modeIndex == modeindex) {
                return tConflictPolicy[i].getModePolicy();
            }
        }
        return null;
    }

    private int getDstVolByParseConflictPolicy(volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.conflictModePolicy tConflictModePolicy) {
        if (tConflictModePolicy == null || tConflictModePolicy.changeValue == null) {
            Log.e(TAG, "getDstVolByParseConflictPolicy ERROR PARAM");
            return -1;
        }
        int dstVol = -1;
        int streamType = getStreamTypeIndex(tConflictModePolicy.targetStream);
        try {
            if (tConflictModePolicy.changeValue.contains("%")) {
                String[] split2 = tConflictModePolicy.changeValue.split("%");
                if (split2 == null) {
                    return -1;
                }
                int index = getTempChangeVol(streamType) == -1 ? getStreamVolume(streamType) : getTempChangeVol(streamType);
                int percent = Integer.parseInt(split2[0]);
                int i = 1;
                if (tConflictModePolicy.changeMode == 1) {
                    if ((index * percent) / 100 > 1) {
                        i = (index * percent) / 100;
                    }
                    dstVol = i;
                }
                Log.d(TAG, "getDstVolByParseConflictPolicy  cur:" + index + " " + tConflictModePolicy.changeValue + " dstVol:" + dstVol);
                return dstVol;
            }
            int dstVol2 = Integer.parseInt(tConflictModePolicy.changeValue);
            Log.d(TAG, "getDstVolByParseConflictPolicy  dstVol:" + dstVol2);
            return dstVol2;
        } catch (Exception e) {
            handleException("getDstVolByParseConflictPolicy", e);
            return -1;
        }
    }

    private int getScreenId() {
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void debugPrintLog(String str) {
        if (!xuiAudioPolicy.DEBUG_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, str);
    }

    private void debugDumpVolumePare() {
        if (!checkVolumeDataExist()) {
            debugPrintLog("debugDumpVolumePare() error");
            return;
        }
        debugPrintLog("debugDumpVolumePare() start length:" + mStreamVolumeType.volumeTypeList.length);
        for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
            debugPrintLog("  i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " voltype:" + mStreamVolumeType.volumeTypeList[i].volumeType);
        }
        debugPrintLog("debugDumpVolumePare() end ");
    }

    private void debugDumpmVolumeTypeData() {
        if (!checkVolumeObjectDataExist()) {
            debugPrintLog("debugDumpmVolumeTypeData() error");
            return;
        }
        debugPrintLog("debugDumpmVolumeTypeData() start max:" + mVolumeTypeData.maxVol + " min:" + mVolumeTypeData.minVol);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            volumeJsonObject.volumeTypeData.VolumeTypeData tVolTypeData = mVolumeTypeData.volumeTypeDataList[i];
            debugPrintLog(" i:" + i + " groupId=" + tVolTypeData.groupId + " voltype:" + tVolTypeData.volumeType + " priority:" + tVolTypeData.priority + " property:" + tVolTypeData.property);
            if (tVolTypeData.volumeValue == null) {
                debugPrintLog(" i:" + i + " volumeValue = null!!!");
            } else {
                for (int j = 0; j < tVolTypeData.volumeValue.length; j++) {
                    debugPrintLog("    i:" + i + " j:" + j + " mode:" + tVolTypeData.mode + " modeIndex:" + tVolTypeData.volumeValue[j].modeindex + " defaultval:" + tVolTypeData.volumeValue[j].defaultVal);
                }
                debugPrintLog("debugDumpmVolumeTypeData()  end!!!");
            }
        }
    }

    private void debugDumpConflictVolumeObject() {
        volumeJsonObject.conflictVolumeObject conflictvolumeobject = mConflictVolumeObject;
        if (conflictvolumeobject == null || conflictvolumeobject.policy == null) {
            debugPrintLog("debugDumpConflictVolumeObject error!!! mConflictVolumeObject:" + mConflictVolumeObject);
            return;
        }
        debugPrintLog("debugDumpConflictVolumeObject start");
        for (int i = 0; i < mConflictVolumeObject.policy.length; i++) {
            debugPrintLog("  debugDumpConflictVolumeObject i:" + i + " screenId:" + mConflictVolumeObject.policy[i].screenId);
            StringBuilder sb = new StringBuilder();
            sb.append("  ");
            sb.append(mConflictVolumeObject.policy[i].envtriggerChange);
            debugPrintLog(sb.toString());
            debugPrintLog("  " + mConflictVolumeObject.policy[i].streamTypeChange);
            if (mConflictVolumeObject.policy[i].streamTypeChange != null) {
                for (int j = 0; j < mConflictVolumeObject.policy[i].streamTypeChange.length; j++) {
                    volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.StreamTypeTrigger tStreamTypeTrigger = mConflictVolumeObject.policy[i].streamTypeChange[j];
                    debugPrintLog("    j:" + j + " " + tStreamTypeTrigger.streamType + " " + tStreamTypeTrigger.modeName + " " + tStreamTypeTrigger.extModeName);
                }
                if (mConflictVolumeObject.policy[i].envtriggerChange != null) {
                    for (int j2 = 0; j2 < mConflictVolumeObject.policy[i].envtriggerChange.length; j2++) {
                        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger tEnvTrigger = mConflictVolumeObject.policy[i].envtriggerChange[j2];
                        debugPrintLog("    j:" + j2 + " " + tEnvTrigger.trigger + " " + tEnvTrigger.triggerFlag + " " + tEnvTrigger.modeName + " " + tEnvTrigger.extModeName);
                    }
                }
            }
        }
        debugPrintLog("debugDumpConflictVolumeObject end!!!");
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        breakVolumeFade(streamType);
        long currentTime = System.currentTimeMillis();
        if ((direction == 1 || direction == -1) && Math.abs(currentTime - lastAdjustTime) < adjustInterval) {
            Log.w(TAG, "adjustStreamVolume too fast ,  return  timelogs:" + currentTime + "," + lastAdjustTime + "," + Math.abs(currentTime - lastAdjustTime) + "," + adjustInterval);
            return;
        }
        lastAdjustTime = currentTime;
        sendVolumeMessage(2, streamType, direction, flags, packageName);
    }

    public void setVolWhenPlay(int streamType) {
        if (streamType == 3 || streamType == 9) {
            return;
        }
        boolean muteflag = getStreamMute(streamType);
        String volumeType = getVolumeType(streamType);
        if (muteflag) {
            Log.d(TAG, "streamType:" + streamType + " volume can not be set for mute");
        } else if (XP_VOLUME_SYSTEM.equals(volumeType)) {
        } else {
            Log.d(TAG, "setVolWhenPlay xui streamType=" + streamType);
            forceSetStreamVolume(streamType);
        }
    }

    public void forceSetStreamVolume(int streamType) {
        int index = getStreamVolume(streamType);
        doSetStreamVolume(streamType, index, 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
    }

    public void forceSetStreamVolumeByStr(String streamType) {
        int streamIndex = getStreamTypeIndex(streamType);
        int index = getStreamVolume(streamIndex);
        doSetStreamVolume(streamIndex, index, 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
    }

    public void setStreamVolume(int streamType, int index, int flags, String packageName) {
        Log.d(TAG, "setStreamVolume " + streamType + " " + index + " " + flags + " " + packageName);
        breakVolumeFade(streamType);
        if (flags == 4 && XUI_SERVICE_PACKAGE.equals(packageName)) {
            packageName = XUI_SERVICE_PACKAGE2;
        }
        doSetStreamVolume(streamType, index, flags, 0, false, true, packageName);
    }

    public boolean getStreamMute(int streamType) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getStreamMute(streamType);
        }
        return false;
    }

    public int getStreamVolume(int streamType) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getStreamVolume(streamType);
        }
        return -1;
    }

    public int getStreamMaxVolume(int streamType) {
        return MaxVol;
    }

    public int getStreamMinVolume(int streamType) {
        return MinVol;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getStreamVolume(streamType);
        }
        return -1;
    }

    public void setMusicLimitMode(boolean modeOn) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.saveMusicLimitMode(modeOn);
        }
        this.mIsMusicLimitmode = modeOn;
        int index = getStreamVolume(3);
        sendVolumeMessage(1, 3, index, 1, AUDIO_SYSTEM_PACKAGENAME);
    }

    public boolean isMusicLimitMode() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.getMusicLimitMode();
            return false;
        }
        return false;
    }

    public void setBanTemporaryVolChangeMode(int streamType, int mode, String pkgName) {
        Log.i(TAG, "setBanTemporaryVolChangeMode  " + streamType + " " + mode + " " + pkgName);
        int volumeType = getVolumeTypeId(streamType);
        if (volumeType == -1) {
            Log.e(TAG, "setBanTemporaryVolChangeMode  volumeType out of range");
        } else {
            this.mXpVolBanMode[volumeType] = mode;
        }
    }

    public int getBanTemporaryVolChangeMode(int streamType) {
        int volumeType = getVolumeTypeId(streamType);
        if (volumeType == -1) {
            Log.e(TAG, "getBanTemporaryVolChangeMode  volumeType out of range");
            return 0;
        }
        return this.mXpVolBanMode[volumeType];
    }

    public void checkAlarmVolume() {
    }

    /* JADX WARN: Removed duplicated region for block: B:54:0x00ec  */
    /* JADX WARN: Removed duplicated region for block: B:59:0x0129 A[Catch: all -> 0x016e, TryCatch #0 {, blocks: (B:3:0x0001, B:5:0x0032, B:7:0x0038, B:10:0x0042, B:14:0x0056, B:19:0x0064, B:23:0x007e, B:25:0x0087, B:27:0x008f, B:31:0x00a0, B:29:0x0098, B:34:0x00a9, B:36:0x00af, B:67:0x0169, B:37:0x00b8, B:56:0x00f0, B:60:0x013a, B:57:0x0107, B:58:0x0118, B:59:0x0129, B:44:0x00cd, B:47:0x00d7, B:50:0x00e1, B:62:0x0159, B:11:0x004b), top: B:73:0x0001 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized boolean setFixedVolume(boolean r12, int r13, int r14, java.lang.String r15) {
        /*
            Method dump skipped, instructions count: 369
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xui.xuiaudio.xuiAudioVolume.xuiVolumePolicy.setFixedVolume(boolean, int, int, java.lang.String):boolean");
    }

    public boolean isFixedVolume(int streamType) {
        return this.mFixedVolumeMode[streamType];
    }

    public void restoreMusicVolume(String callingPackage) {
        Log.i(TAG, "restoreMusicVolume() " + callingPackage);
        if (getStreamVolume(3) == 0) {
            setStreamVolume(3, this.musicRestoreVolume, 0, callingPackage);
        } else if (getStreamMute(3)) {
            adjustStreamVolume(3, 100, 0, callingPackage);
        }
    }

    public void setDangerousTtsVolLevel(int level) {
        int dangerttsStatus;
        Log.i(TAG, "setDangerousTtsVolLevel: level=" + level);
        mXuiAudioData.saveDangerTtsLevel(level);
        int StreamDangerous = getStreamTypeIndex(xuiAudioPolicy.STREAM_DANGEROUS);
        String prop = getPropertyForVolumeType(getVolumeType(StreamDangerous));
        int index = getDefaultVolume(StreamDangerous);
        mXuiAudioData.saveStreamVolume(StreamDangerous, index, false, prop);
        xuiAudioPolicy xuiaudiopolicy = mXuiAudioPolicy;
        if (xuiaudiopolicy != null && (dangerttsStatus = xuiaudiopolicy.getDangerousTtsStatus()) != 0) {
            mXuiAudioPolicy.setDangerousTtsStatus(dangerttsStatus);
        }
    }

    public int getDangerousTtsVolLevel() {
        return mXuiAudioData.getDangerTtsLevel();
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getNavVolDecreaseEnable();
        }
        return true;
    }

    public void triggerEnvConflictPolicy(int flag, int volume, boolean on, String packageName) {
        int index;
        int direction;
        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.conflictModePolicy[] tConflictModePolicy = getEnvConflictModePolicy(flag);
        if (tConflictModePolicy == null) {
            Log.e(TAG, "triggerEnvConflictPolicy " + flag + " conflictModePolicy not found!!");
            return;
        }
        Log.d(TAG, "triggerEnvConflictPolicy  " + flag + " on:" + on);
        for (int i = 0; i < tConflictModePolicy.length; i++) {
            int streamType = getStreamTypeIndex(tConflictModePolicy[i].targetStream);
            if (!getStreamMute(streamType)) {
                boolean exist = checkTempVolChangeDataExist(streamType, flag, packageName);
                if (exist != on) {
                    int index2 = getDstVolByParseConflictPolicy(tConflictModePolicy[i]);
                    if (index2 == -1 && volume != -1) {
                        Log.d(TAG, "triggerEnvConflictPolicy  flag:" + flag + " is special type ,set dst volume:" + volume);
                        index = volume;
                    } else {
                        index = index2;
                    }
                    if (index > MinVol && index < MaxVol) {
                        if (ENV_TRIGGER_SYSVOL_DIRECTION_DOWN.equals(tConflictModePolicy[i].changeType)) {
                            direction = 1;
                        } else if (!ENV_TRIGGER_SYSVOL_DIRECTION_UP.equals(tConflictModePolicy[i].changeType)) {
                            direction = 0;
                        } else {
                            direction = 2;
                        }
                        boolean exist2 = !on;
                        doTemporaryChangeVolumeDown(streamType, index, direction, exist2, flag, packageName);
                    }
                } else {
                    Log.d(TAG, "triggerEnvConflictPolicy  exist==on");
                }
            } else {
                Log.d(TAG, "triggerEnvConflictPolicy  " + tConflictModePolicy[i].targetStream + " is muted do not change");
            }
        }
    }

    public void setVolumeFaded(int StreamType, int vol, int fadetime, int xuiflag, String callingPackage) {
        if (vol > MaxVol || vol < MinVol) {
            Log.d(TAG, "setVolumeFaded vol:" + vol + " not valid!!");
            return;
        }
        synchronized (fadingLock) {
            if (this.mFadingFlag[StreamType]) {
                Log.i(TAG, "setVolumeFaded stop fading , just set! StreamType=" + StreamType);
                int i = StreamType + 100;
                breakVolumeFade(StreamType);
                try {
                    Thread.sleep(20L);
                } catch (Exception e) {
                    handleException("setVolumeFaded", e);
                }
                doSetStreamVolume(StreamType, vol, 0, xuiflag, false, true, callingPackage);
                return;
            }
            int mFadeTime = fadetime <= 0 ? 500 : fadetime;
            int currentVol = getStreamVolume(StreamType);
            if (currentVol == vol) {
                Log.i(TAG, "setVolumeFaded  currentVol == destVolume");
                return;
            }
            boolean z = currentVol <= vol;
            doFadeVolume(StreamType, currentVol, vol, mFadeTime, 1, 0, 268468224, xuiflag, callingPackage);
            saveStreamVolume(StreamType, vol, true);
            broadcastVolumeChangedToAll(StreamType, vol, vol, xuiflag, callingPackage);
        }
    }

    public void setDangerousTtsStatus(int on) {
        int index;
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setDangerousTtsStatus(on);
        }
        if (on == 0) {
            if (getStreamMute(9)) {
                index = 0;
            } else {
                index = getStreamVolume(9);
            }
            debugPrintLog("setDangerousTtsStatus " + on + "  dangerous index=" + index);
            doSetStreamVolume(9, index, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
            triggerEnvConflictPolicy(8, -1, false, XUI_SERVICE_PACKAGE);
            return;
        }
        int StreamDangerous = getStreamTypeIndex(xuiAudioPolicy.STREAM_DANGEROUS);
        int index2 = mXuiAudioData.getStreamVolume(StreamDangerous);
        debugPrintLog("setDangerousTtsStatus " + on + "  dangerous index=" + index2);
        doSetStreamVolume(StreamDangerous, index2, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        triggerEnvConflictPolicy(8, -1, true, XUI_SERVICE_PACKAGE);
    }

    public int getDangerousTtsStatus() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setBtCallOnFlag(int flag, boolean isEcall) {
        if ((flag == 2 || flag == 3) && mXuiAudioPolicy.getBtCallMode() == 1) {
            doSetStreamVolume(6, getStreamVolume(6), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        } else {
            doSetStreamVolume(2, getStreamVolume(2), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        if (!isEcall) {
            triggerEnvConflictPolicy(4, -1, flag != 0, BTCALL_VOLCHANGE_PACKAGE);
        }
    }

    private void saveStreamVolume(int streamType, int index, boolean savemute) {
        String volumeType = getVolumeType(streamType);
        String prop = getPropertyForVolumeType(volumeType);
        mXuiAudioData.saveStreamVolume(streamType, index, savemute, prop);
    }

    private void setVolumeToGroup(int streamType, int index, int flag) {
        if (checkIsHighPriorityTypeRunning(streamType)) {
            Log.i(TAG, "setVolumeToGroup fail!!  has high priority type running");
        } else if (checkVolTypeFixed(streamType)) {
            Log.i(TAG, "setVolumeToGroup fail!!  streamtype:" + streamType + " is fixed!!!");
        } else {
            Log.d(TAG, "setVolumeToGroup " + streamType + " " + index + " flag:" + flag + " mFixedVolumeMode:" + this.mFixedVolumeMode[streamType]);
            if (flag == 268468224 || flag == 8 || !this.mFixedVolumeMode[streamType]) {
                if (streamType == 3 && this.mIsMusicLimitmode) {
                    index /= 2;
                }
                mXuiVolumeToHal.setGroupVolume(getGroupId(getVolumeType(streamType)), index, flag);
                sendVolumeValueOut(streamType, index);
            }
        }
    }

    private void sendVolumeValueOut(int streamType, int index) {
        if (streamType != 3 || !this.isHighAmp) {
            if (streamType == 11) {
                Log.i(TAG, "sendVolumeValueOut  index=" + index);
                IXuiVolumeInterface iXuiVolumeInterface = mXuiVolumeToHal;
                if (iXuiVolumeInterface != null) {
                    iXuiVolumeInterface.sendAvasVolumeToMcu(index);
                }
            }
        } else if (index < 0 || index > 30) {
            Log.w(TAG, "sendMusicVolumeToAmp  index=" + index + "  out of range");
        } else {
            Log.i(TAG, "sendMusicVolumeToAmp  index=" + index);
            IXuiVolumeInterface iXuiVolumeInterface2 = mXuiVolumeToHal;
            if (iXuiVolumeInterface2 != null) {
                iXuiVolumeInterface2.sendMusicVolumeToAmp(index);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class tempVolChangeData {
        int StreamType;
        int changeIndex;
        int flag;
        String packageName;

        private tempVolChangeData() {
        }
    }

    private tempVolChangeData getTempMinVolChangeDataByStreamType(int StreamType) {
        Log.d(TAG, "getTempMinVolChangeDataByStreamType StreamType:" + StreamType + " size:" + this.mTempVolChangeDataList.size());
        if (this.mTempVolChangeDataList.size() == 0) {
            return null;
        }
        tempVolChangeData xTempData = null;
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && (xTempData == null || xTempData.changeIndex > mTempData.changeIndex)) {
                Log.d(TAG, "getTempMinVolChangeDataByStreamType index:" + mTempData.changeIndex + " " + mTempData.packageName);
                xTempData = mTempData;
            }
        }
        return xTempData;
    }

    private void rmTempVolChangeWhenVolChange(int streamType, String packageName) {
        if (packageName.equals(XUI_SERVICE_PACKAGE2) || packageName.equals(SYSTEMUI_PACKAGE) || packageName.equals(SETTINGS_PACKAGE) || packageName.equals("android")) {
            debugPrintLog("rmTempVolChangeWhenVolChange " + streamType + " " + packageName);
            removeTempChangeVol(streamType);
            removeTempVolChangeDataOfStreamType(streamType);
        }
    }

    private synchronized void removeTempVolChangeData(int StreamType, int dstVol, int flag, String packageName) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return;
        }
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && mTempData.flag == flag && packageName.equals(mTempData.packageName)) {
                Log.d(TAG, "removeTempVolChangeData " + StreamType + " " + flag + " " + mTempData.changeIndex);
                this.mTempVolChangeDataList.remove(i);
                return;
            }
        }
    }

    private synchronized void removeTempVolChangeDataOfStreamType(int StreamType) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return;
        }
        for (int i = this.mTempVolChangeDataList.size() - 1; i >= 0; i--) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType) {
                Log.d(TAG, "removeTempVolChangeDataOfStreamType " + mTempData.changeIndex);
                this.mTempVolChangeDataList.remove(i);
                dumpTempVolChangeData();
            }
        }
    }

    private boolean checkTempVolChangeDataExist(int StreamType, int flag, String packageName) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return false;
        }
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && mTempData.flag == flag && packageName.equals(mTempData.packageName)) {
                Log.d(TAG, "checkTempVolChangeDataExist :" + StreamType + " " + mTempData.changeIndex + " " + flag);
                return true;
            }
        }
        return false;
    }

    private synchronized boolean addTempVolChangeData(int StreamType, int vol, int flag, String packageName) {
        if (checkTempVolChangeDataExist(StreamType, flag, packageName)) {
            return false;
        }
        Log.d(TAG, "addTempVolChangeData " + StreamType + " " + vol + " " + flag + " " + packageName);
        tempVolChangeData mTempData = new tempVolChangeData();
        mTempData.StreamType = StreamType;
        mTempData.changeIndex = vol;
        mTempData.packageName = packageName;
        mTempData.flag = flag;
        this.mTempVolChangeDataList.add(mTempData);
        dumpTempVolChangeData();
        return true;
    }

    private synchronized void clearTempVolChangeData() {
        Log.d(TAG, "clearTempVolChangeData()");
        this.mTempVolChangeDataList.clear();
    }

    private void dumpTempVolChangeData() {
        if (this.mTempVolChangeDataList.size() == 0) {
            return;
        }
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            Log.d(TAG, "dumpTempVolChangeData  :" + mTempData.StreamType + " " + mTempData.changeIndex + " " + mTempData.flag + " " + mTempData.packageName);
        }
    }

    private synchronized void saveTempChangeVol(int StreamType) {
        if (mXuiAudioData != null) {
            Log.d(TAG, "saveTempChangeVol " + StreamType + " " + mXuiAudioData.getTempChangeVol(StreamType) + " " + getStreamVolume(StreamType));
        }
        if (mXuiAudioData != null && StreamType <= 14 && StreamType >= 0 && mXuiAudioData.getTempChangeVol(StreamType) == -1) {
            try {
                mXuiAudioData.saveTempChangeVol(StreamType, getStreamVolume(StreamType));
            } catch (Exception e) {
                handleException("saveTempChangeVol ", e);
            }
            debugPrintLog("saveTempChangeVol " + StreamType + " " + mXuiAudioData.getTempChangeVol(StreamType));
        }
    }

    private synchronized void removeTempChangeVol(int StreamType) {
        Log.d(TAG, "removeTempChangeVol " + StreamType);
        if (mXuiAudioData != null && StreamType <= 14 && StreamType >= 0 && mXuiAudioData.getTempChangeVol(StreamType) != -1) {
            try {
                mXuiAudioData.saveTempChangeVol(StreamType, -1);
            } catch (Exception e) {
                handleException("removeTempChangeVol ", e);
            }
            debugPrintLog("removeTempChangeVol " + StreamType + " " + mXuiAudioData.getTempChangeVol(StreamType));
        }
    }

    private int getTempChangeVol(int StreamType) {
        if (mXuiAudioData != null && StreamType <= 14 && StreamType >= 0) {
            debugPrintLog("getTempChangeVol " + StreamType + " " + mXuiAudioData.getTempChangeVol(StreamType));
            return mXuiAudioData.getTempChangeVol(StreamType);
        }
        return -1;
    }

    private synchronized void restoreTempChangeVol() {
        for (int i = 0; i <= 14; i++) {
            if (mXuiAudioData != null && mXuiAudioData.getTempChangeVol(i) != -1) {
                int index = mXuiAudioData.getTempChangeVol(i);
                Log.d(TAG, "restoreTempChangeVol()  streamType:" + i + "  vol:" + index);
                doSetStreamVolume(i, index, 0, 0, false, true, AUDIO_SYSTEM_PACKAGENAME);
                removeTempChangeVol(i);
            }
        }
    }

    private void dealVolChangeWhenMute(int StreamType, int Vol, int xuiflag, String packageName) {
        Log.i(TAG, "dealVolChangeWhenMute  " + StreamType + " " + Vol + " " + packageName);
        if (StreamType > 0 && StreamType < AudioSystem.getNumStreamTypes() && Vol >= 0 && Vol <= 30) {
            saveStreamVolume(StreamType, Vol, false);
            broadcastVolumeChangedToAll(StreamType, Vol, 0, xuiflag, packageName);
        }
    }

    private void dealTemporaryVolChange(int StreamType, int vol, String packageName) {
        if (getBanTemporaryVolChangeMode(StreamType) == 0) {
            if (getStreamMute(StreamType)) {
                dealVolChangeWhenMute(StreamType, vol, 1, packageName);
                return;
            } else {
                setVolumeFaded(StreamType, vol, 0, 1, packageName);
                return;
            }
        }
        Log.w(TAG, "StreamType " + StreamType + "  is in ban mode !!! do not change volume");
    }

    private synchronized void doTemporaryChangeVolumeDown(int StreamType, int dstVol, int direction, boolean restoreVol, int flag, String packageName) {
        Log.i(TAG, "temporaryChangeVolumeDown StreamType:" + StreamType + " " + dstVol + " " + restoreVol + " " + packageName + " " + getTempChangeVol(StreamType) + " flag:" + flag);
        if (restoreVol) {
            removeTempVolChangeData(StreamType, dstVol, flag, packageName);
            tempVolChangeData tData = getTempMinVolChangeDataByStreamType(StreamType);
            if (tData == null && getTempChangeVol(StreamType) >= 0) {
                dealTemporaryVolChange(StreamType, getTempChangeVol(StreamType), packageName);
                removeTempChangeVol(StreamType);
            } else if (tData != null) {
                dealTemporaryVolChange(StreamType, tData.changeIndex, packageName);
            }
        } else if (direction == 1 && dstVol > getStreamVolume(StreamType)) {
            Log.d(TAG, "triggerEnvConflictPolicy stream:" + StreamType + "  VOL is already below " + dstVol);
        } else if (direction == 2 && dstVol < getStreamVolume(StreamType)) {
            Log.d(TAG, "triggerEnvConflictPolicy stream:" + StreamType + "  VOL is already above " + dstVol);
        } else if (addTempVolChangeData(StreamType, dstVol, flag, packageName)) {
            saveTempChangeVol(StreamType);
            tempVolChangeData tData2 = getTempMinVolChangeDataByStreamType(StreamType);
            if (tData2 == null) {
                return;
            }
            if (dstVol == getStreamVolume(StreamType)) {
                Log.d(TAG, "triggerEnvConflictPolicy stream:" + StreamType + "  VOL is already " + dstVol);
                return;
            }
            dealTemporaryVolChange(StreamType, tData2.changeIndex, packageName);
        }
    }

    private void broadcastVolumeToICM(int streamType, int volume, boolean fromadj) {
        IXuiVolumeInterface iXuiVolumeInterface = mXuiVolumeToHal;
        if (iXuiVolumeInterface != null) {
            iXuiVolumeInterface.broadcastVolumeToICM(streamType, volume, getStreamVolume(streamType), fromadj);
        }
    }

    private void broadcastVolumeChangedToAll(int streamType, int newVol, int oldVol, int xuiflag, String packageName) {
        long ident = Binder.clearCallingIdentity();
        boolean adjust = packageName.equals("android");
        int i = 1;
        int i2 = 0;
        boolean muteflag = streamType == 3 && (mXuiAudioPolicy.isOtherSessionOn() || mXuiAudioPolicy.isKaraokeOn());
        Log.i(TAG, "broadcastVolumeChangedToAll streamType:" + streamType + " newVol:" + newVol + " pkgName:" + packageName + " muteflag:" + muteflag + " adjust:" + adjust + " xuiflag:" + xuiflag);
        try {
            try {
            } catch (Exception e) {
                e = e;
            } catch (Throwable th) {
                th = th;
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
            try {
                synchronized (xpAuioBroadcastLock) {
                    try {
                        this.mVolumeChanged.addFlags(67108864);
                        this.mVolumeChanged.addFlags(268435456);
                        this.mVolumeChanged.putExtra(EXTRA_VOLCHANGE_PACKAGENAME, packageName);
                        this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", streamType);
                        this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", newVol);
                        this.mVolumeChanged.putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", oldVol);
                        this.mVolumeChanged.putExtra(AUDIO_EXTRA_VOLUME_STREAM_FLAG, adjust ? 1 : 0);
                        Intent intent = this.mVolumeChanged;
                        if (!muteflag) {
                            i = 0;
                        }
                        intent.putExtra(AUDIO_EXTRA_MUSIC_RUNNING_FLAG, i);
                        this.mVolumeChanged.putExtra(AUDIO_EXTRA_AUTOVOLUME_FLAG, xuiflag);
                        String str = "";
                        if (muteflag) {
                            StringBuilder mStr = new StringBuilder();
                            List<String> mList = mXuiAudioPolicy.getOtherMusicPlayingPkgs();
                            while (i2 < mList.size()) {
                                mStr.append("" + mList.get(i2) + ";");
                                i2++;
                                str = str;
                            }
                            str = mStr.toString();
                        }
                        this.mVolumeChanged.putExtra(EXTRA_OTHERPLAYING_PACKAGENAME, str);
                        mContext.sendBroadcastAsUser(this.mVolumeChanged, UserHandle.ALL);
                        Binder.restoreCallingIdentity(ident);
                    } catch (Throwable th2) {
                        th = th2;
                        try {
                            throw th;
                        } catch (Exception e2) {
                            e = e2;
                            handleException("broadcastVolumeChangedToAll ", e);
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                }
            } catch (Throwable th3) {
                th = th3;
            }
        } catch (Throwable th4) {
            th = th4;
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void xpadjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        boolean isAdjustFlag;
        int index;
        try {
            Log.i(TAG, "adjustStreamVolume: streamType=" + streamType + " direction=" + direction + " flags:" + flags + " packageName:" + packageName);
            boolean MuteFlag = false;
            boolean SendToIcm_e28 = false;
            if (!packageName.equals("android")) {
                isAdjustFlag = false;
            } else {
                SendToIcm_e28 = true;
                isAdjustFlag = true;
            }
            boolean isMuted = getStreamMute(streamType);
            int oldindex = getStreamVolume(streamType);
            if (checkStreamConfigedFixed(streamType) && -1 != getDefaultVolume(streamType)) {
                Log.d(TAG, "adjustSteamVolume  please do not change system volume. streamType:" + streamType + " index:-1");
                return;
            }
            int i = 0;
            if (direction == -100) {
                MuteFlag = true;
                rmTempVolChangeWhenVolChange(streamType, packageName);
                index = oldindex;
            } else if (direction == -1 || direction == 1) {
                index = oldindex + direction;
                if (streamType == 3 && isMuted && index > 0) {
                    SendToIcm_e28 = true;
                }
                if (isMuted && index > MaxVol) {
                    index = MaxVol;
                } else {
                    if (index >= MinVol) {
                        if (index > MaxVol) {
                        }
                    }
                    Log.d(TAG, "adjustStreamVolume out of range.");
                    if (isAdjustFlag) {
                        int broadcastVolume = index < MinVol ? MinVol : MaxVol;
                        broadcastVolumeToICM(streamType, broadcastVolume, SendToIcm_e28);
                        broadcastVolumeChangedToAll(streamType, broadcastVolume, broadcastVolume, 0, packageName);
                        return;
                    }
                    return;
                }
                rmTempVolChangeWhenVolChange(streamType, packageName);
            } else if (direction != 100) {
                if (direction == 101) {
                    MuteFlag = !getStreamMute(streamType);
                    rmTempVolChangeWhenVolChange(streamType, packageName);
                    index = oldindex;
                } else {
                    Log.d(TAG, "adjustStreamVolume unkown direction=" + direction);
                    return;
                }
            } else {
                MuteFlag = false;
                rmTempVolChangeWhenVolChange(streamType, packageName);
                index = oldindex;
            }
            if (MuteFlag != isMuted) {
                mXuiAudioData.saveStreamMute(streamType, MuteFlag);
            }
            int groupId = getGroupId(getVolumeType(streamType));
            if (groupId < 0) {
                doSetExternalStreamVolume(streamType, index, MuteFlag, packageName);
                return;
            }
            saveStreamVolume(streamType, index, false);
            if (!MuteFlag) {
                i = index;
            }
            setVolumeToGroup(streamType, i, flags);
            if (direction != -100 && (direction != 101 || !getStreamMute(streamType))) {
                if (index <= 30 && index >= 0) {
                    broadcastVolumeToICM(streamType, index, SendToIcm_e28);
                }
                broadcastVolumeChangedToAll(streamType, index, oldindex, 0, packageName);
                Log.i(TAG, "adjustStreamVolume: index=" + index);
            }
            Log.e(TAG, "AdjustVolume -> mute, do not change volume");
            broadcastVolumeToICM(streamType, 255, SendToIcm_e28);
            broadcastVolumeChangedToAll(streamType, index, oldindex, 0, packageName);
            Log.i(TAG, "adjustStreamVolume: index=" + index);
        } catch (Exception e) {
            handleException("xpadjustStreamVolume", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doSetStreamVolume(int streamType, int index, int flags, int xuiflag, boolean notSaveVolume, boolean needbroadcast, String packageName) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("doSetStreamVolume ");
            sb.append(streamType);
            sb.append(" ");
            sb.append(index);
            sb.append(" ");
            sb.append(flags);
            sb.append(" ");
            try {
                sb.append(xuiflag);
                sb.append(" ");
                sb.append(notSaveVolume);
                sb.append(" ");
                sb.append(needbroadcast);
                sb.append(" ");
                sb.append(packageName);
                Log.d(TAG, sb.toString());
                int groupId = getGroupId(getVolumeType(streamType));
                if (groupId < 0) {
                    doSetExternalStreamVolume(streamType, index, false, packageName);
                    return;
                }
                int maxVol = MaxVol;
                int minVol = MinVol;
                int oldVol = getStreamVolume(streamType);
                if (index >= minVol && index <= maxVol) {
                    if (checkStreamConfigedFixed(streamType) && getDefaultVolume(streamType) != index) {
                        Log.w(TAG, "setStreamVolume  please do not change fixed volume. streamType:" + streamType + " index:" + index);
                        return;
                    }
                    rmTempVolChangeWhenVolChange(streamType, packageName);
                    setVolumeToGroup(streamType, index, flags);
                    Log.i(TAG, "setStreamVolume newGroupVol=" + index + " groupId:" + groupId + ", streamType=" + streamType + " flags:" + flags + " notSaveVolume:" + notSaveVolume + " isActive:" + mXuiAudioPolicy.checkStreamActive(streamType) + " (minVol:" + minVol + "|maxVol:" + maxVol + ") needbroadcast:" + needbroadcast + " pkgName:" + packageName);
                    if (!notSaveVolume) {
                        saveStreamVolume(streamType, index, true);
                    }
                    if (needbroadcast) {
                        boolean z = false;
                        broadcastVolumeChangedToAll(streamType, index, oldVol, xuiflag, packageName);
                        if (streamType == 3 && getStreamMute(streamType) && index != 0 && FeatureOption.FO_ICM_TYPE == 0) {
                            z = true;
                        }
                        boolean SendToIcm_e28 = z;
                        if (FeatureOption.FO_ICM_TYPE == 1 || (FeatureOption.FO_ICM_TYPE == 0 && packageName != null && !packageName.equals(XUI_SERVICE_PACKAGE) && !packageName.equals(BTCALL_VOLCHANGE_PACKAGE))) {
                            broadcastVolumeToICM(streamType, index, SendToIcm_e28);
                        }
                    }
                    return;
                }
                Log.w(TAG, "doSetStreamVolume out of range. streamType=" + streamType);
            } catch (Exception e) {
                e = e;
                handleException("doSetStreamVolume", e);
            }
        } catch (Exception e2) {
            e = e2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doFadeVolume(int streamType, int srcVol, int dstVol, int fadeTime, int curStep, int totalStep, int flag, int xuiflag, String pkgName) {
        boolean fadeIn = srcVol <= dstVol;
        int i = 10;
        if (totalStep != 0) {
            i = totalStep;
        } else if (Math.abs(srcVol - dstVol) <= 10) {
            i = Math.abs(srcVol - dstVol);
        }
        int step = i;
        Log.d(TAG, "doFadeVolume streamType:" + streamType + " srcVol:" + srcVol + " dstVol:" + dstVol + " fadeTime:" + fadeTime + " curStep:" + curStep + " totalstep:" + step + " pkgName:" + pkgName);
        if (curStep > step) {
            this.mFadingFlag[streamType] = false;
            return;
        }
        this.mFadingFlag[streamType] = true;
        int sleepTime = fadeTime / step;
        int abs = (Math.abs(srcVol - dstVol) * curStep) / step;
        int stepSetVol = fadeIn ? abs + srcVol : srcVol - abs;
        int messageId = streamType + 100;
        sendVolFadeMessage(messageId, streamType, stepSetVol, srcVol, dstVol, curStep, step, fadeTime, flag, sleepTime, xuiflag, pkgName);
    }

    private void doSetExternalStreamVolume(int streamType, int index, boolean isMute, String packageName) {
        Log.d(TAG, "doSetExternalStreamVolume " + streamType + " " + index + " passengerBtExist:" + passengerBtExist + " " + mXuiExternalAudioPath + " " + packageName);
        saveStreamVolume(streamType, index, false);
        broadcastVolumeChangedToAll(streamType, index, index, 0, packageName);
        xuiExternalAudioPath xuiexternalaudiopath = mXuiExternalAudioPath;
        if (xuiexternalaudiopath != null) {
            xuiexternalaudiopath.setPassengerBtVolume(isMute ? 0 : index);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class StreamVolumeClass {
        int flag;
        String packageName;
        int streamType;
        int value;

        private StreamVolumeClass() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class StreamVolumeFadeClass {
        int curIndex;
        int curStep;
        int dstIndex;
        int fadeTime;
        int flag;
        String packageName;
        int srcIndex;
        int streamType;
        int totalStep;
        int xuiflag;

        private StreamVolumeFadeClass() {
        }
    }

    private void sendVolumeMessage(int messageid, int streamType, int value, int flags, String packageName) {
        if (this.mHandler != null) {
            StreamVolumeClass mStreamVolumeClass = new StreamVolumeClass();
            mStreamVolumeClass.flag = flags;
            mStreamVolumeClass.packageName = packageName;
            mStreamVolumeClass.streamType = streamType;
            mStreamVolumeClass.value = value;
            Message m = this.mHandler.obtainMessage(messageid, streamType, value, mStreamVolumeClass);
            this.mHandler.sendMessage(m);
        }
    }

    private void sendVolFadeMessage(int messageid, int streamType, int curIndex, int srcIndex, int dstIndex, int curStep, int totalStep, int fadeTime, int flags, int sleepTime, int xuiflag, String packageName) {
        if (this.mHandler != null) {
            StreamVolumeFadeClass mStreamVolumeFadeClass = new StreamVolumeFadeClass();
            mStreamVolumeFadeClass.flag = flags;
            mStreamVolumeFadeClass.packageName = packageName;
            mStreamVolumeFadeClass.streamType = streamType;
            mStreamVolumeFadeClass.curIndex = curIndex;
            mStreamVolumeFadeClass.dstIndex = dstIndex;
            mStreamVolumeFadeClass.srcIndex = srcIndex;
            mStreamVolumeFadeClass.curStep = curStep;
            mStreamVolumeFadeClass.totalStep = totalStep;
            mStreamVolumeFadeClass.fadeTime = fadeTime;
            mStreamVolumeFadeClass.xuiflag = xuiflag;
            Message m = this.mHandler.obtainMessage(messageid, streamType, 0, mStreamVolumeFadeClass);
            this.mHandler.sendMessageDelayed(m, sleepTime);
        }
    }

    public void breakVolumeFade(int streamType) {
        synchronized (fadingLock) {
            int messageId = streamType + 100;
            removeMessage(messageId);
            this.mFadingFlag[streamType] = false;
        }
    }

    private void removeMessage(int messageId) {
        if (this.mHandler != null) {
            Log.d(TAG, "removeMessage " + messageId);
            this.mHandler.removeMessages(messageId);
        }
    }

    /* loaded from: classes2.dex */
    public class xuiVolumeHandler extends Handler {
        public xuiVolumeHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                Log.i(xuiVolumePolicy.TAG, "handle MSG_SETSTREAM_VOLUME streamtype:" + msg.arg1);
                StreamVolumeClass mStreamVolumeClass = (StreamVolumeClass) msg.obj;
                xuiVolumePolicy.this.doSetStreamVolume(msg.arg1, msg.arg2, 0, 0, false, mStreamVolumeClass.flag != 0, mStreamVolumeClass.packageName);
            } else if (i == 2) {
                Log.i(xuiVolumePolicy.TAG, "handle MSG_ADJUSTSTREAM_VOLUME");
                StreamVolumeClass mStreamVolumeClass2 = (StreamVolumeClass) msg.obj;
                xuiVolumePolicy.this.xpadjustStreamVolume(msg.arg1, msg.arg2, mStreamVolumeClass2.flag, mStreamVolumeClass2.packageName);
            } else if (msg.what >= 100 && msg.what < 120) {
                int streamType = msg.what - 100;
                StreamVolumeFadeClass mStreamVolumeFadeClass = (StreamVolumeFadeClass) msg.obj;
                int curIndex = mStreamVolumeFadeClass.curIndex;
                int srcIndex = mStreamVolumeFadeClass.srcIndex;
                int dstIndex = mStreamVolumeFadeClass.dstIndex;
                int fadetime = mStreamVolumeFadeClass.fadeTime;
                int curStep = 1 + mStreamVolumeFadeClass.curStep;
                int totalStep = mStreamVolumeFadeClass.totalStep;
                int xuiflag = mStreamVolumeFadeClass.xuiflag;
                String pkgName = mStreamVolumeFadeClass.packageName;
                synchronized (xuiVolumePolicy.fadingLock) {
                    try {
                        try {
                            if (xuiVolumePolicy.this.mFadingFlag[streamType]) {
                                try {
                                    xuiVolumePolicy.this.doSetStreamVolume(streamType, curIndex, 268468224, xuiflag, true, false, pkgName);
                                    xuiVolumePolicy.this.debugPrintLog("fading curStep:" + curStep);
                                    xuiVolumePolicy.this.doFadeVolume(streamType, srcIndex, dstIndex, fadetime, curStep, totalStep, 268468224, xuiflag, pkgName);
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            }
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    } catch (Throwable th3) {
                        th = th3;
                    }
                }
            }
        }
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, "" + func + " " + e);
    }
}
