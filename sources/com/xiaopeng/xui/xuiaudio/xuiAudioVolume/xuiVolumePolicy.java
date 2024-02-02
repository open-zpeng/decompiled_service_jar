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
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
/* loaded from: classes.dex */
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
    private static final String ENV_TRIGGER_SYSVOL_DIRECTION_UP = "upBySystemVolume";
    public static final String EXTMODE_BTPHONE = "btPhone";
    public static final String EXTMODE_NORMAL = "normal";
    private static final String EXTRA_OTHERPLAYING_PACKAGENAME = "android.media.other_musicplaying.PACKAGE_NAME";
    private static final String EXTRA_VOLCHANGE_PACKAGENAME = "android.media.vol_change.PACKAGE_NAME";
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
    private static volumeJsonObject.conflictVolumeObject mConflictVolumeObject;
    private static Context mContext;
    private static xuiVolumePolicy mInstance;
    private static volumeJsonObject.streamVolumePare mStreamVolumeType;
    private static volumeJsonObject.volumeTypeData mVolumeTypeData;
    private static xuiAudioData mXuiAudioData;
    private static xuiAudioPolicy mXuiAudioPolicy;
    private static IXuiVolumeInterface mXuiVolumeToHal;
    private static int[] tempVolChangeSaved;
    private boolean[] VolumeFadeBreakFlag;
    private boolean isHighAmp;
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
    private static Object xpAuioBroadcastLock = new Object();
    private static Object fadingLock = new Object();
    private static Object saveAndSetVolumeLock = new Object();
    private static long lastAdjustTime = -1;
    boolean mIsMusicLimitmode = false;
    private HandlerThread mHandlerThread = null;
    private final ArrayList<tempVolChangeData> mTempVolChangeDataList = new ArrayList<>();

    private xuiVolumePolicy(Context context, xuiAudioPolicy instance) {
        this.isHighAmp = SystemProperties.getInt("persist.sys.xiaopeng.AMP", 0) == 1;
        this.musicRestoreVolume = this.isHighAmp ? FeatureOption.FO_MEDIA_RESTORE_VOLUME_AMP : FeatureOption.FO_MEDIA_RESTORE_VOLUME;
        mContext = context;
        mXuiAudioPolicy = instance;
        Log.d(TAG, "xuiVolumePolicy()");
        initXuiVolumePolicy(context, instance);
        initPrivateData();
        clearTempVolChangeData();
    }

    public static xuiVolumePolicy getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiVolumePolicy(context, instance);
        }
        return mInstance;
    }

    private void initXuiVolumePolicy(Context context, xuiAudioPolicy instance) {
        if (mStreamVolumeType == null) {
            mStreamVolumeType = xuiAudioParser.parseStreamVolumePare();
        }
        if (mVolumeTypeData == null) {
            mVolumeTypeData = xuiAudioParser.parseVolumeData();
        }
        if (mConflictVolumeObject == null) {
            mConflictVolumeObject = xuiAudioParser.parseConflictVolumePolicy();
        }
        if (tempVolChangeSaved == null && mStreamVolumeType != null) {
            tempVolChangeSaved = new int[mStreamVolumeType.getMaxStreamType()];
        }
        if (this.mFixedVolumeMode == null && mStreamVolumeType != null) {
            this.mFixedVolumeMode = new boolean[mStreamVolumeType.getMaxStreamType()];
            Arrays.fill(this.mFixedVolumeMode, false);
        }
        if (this.mXpVolBanMode == null && mStreamVolumeType != null) {
            this.mXpVolBanMode = new int[mStreamVolumeType.getMaxVolType()];
        }
        if (this.mFadingFlag == null && mStreamVolumeType != null) {
            this.mFadingFlag = new boolean[mStreamVolumeType.getMaxStreamType()];
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

    public synchronized void igStatusChange(int status) {
        Log.d(TAG, "igStatusChange() " + status);
        if (status == 1) {
            if (!FeatureOption.FO_DEVICE_INTERNATIONAL_ENABLED) {
                for (int i = 0; i < mStreamVolumeType.getMaxStreamType(); i++) {
                    if (this.mFixedVolumeMode[i]) {
                        setFixedVolume(false, 15, i, "xuiaudio");
                        this.mFixedVolumeMode[i] = false;
                    }
                }
            }
            setBtCallOnFlag(0);
            initPrivateData();
        }
    }

    public void resetAllVolume() {
        setVolWhenPlay(3);
        int volume = getStreamVolume(3);
        boolean broadcastMuteFlag = false;
        broadcastMuteFlag = (mXuiAudioPolicy.isOtherSessionOn() || mXuiAudioPolicy.isKaraokeOn()) ? true : true;
        broadcastVolumeChangedToAll(3, volume, volume, 0, AUDIO_SYSTEM_PACKAGENAME);
        setVolWhenPlay(0);
        setVolWhenPlay(9);
        int volume2 = getStreamVolume(9);
        broadcastVolumeChangedToAll(9, volume2, volume2, 0, AUDIO_SYSTEM_PACKAGENAME);
        doSetStreamVolume(1, getStreamVolume(1), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        mXuiAudioPolicy.setDangerousTtsStatus(0);
    }

    private boolean checkVolumeDataExist() {
        if (mStreamVolumeType == null || mStreamVolumeType.volumeTypeList == null) {
            Log.e(TAG, "checkVolumeDataExist  volumeType IS NULL");
            return false;
        }
        return true;
    }

    private boolean checkVolumeObjectDataExist() {
        if (mVolumeTypeData == null || mVolumeTypeData.volumeTypeDataList == null) {
            Log.e(TAG, "getVolumeType  volumeType IS NULL");
            return false;
        }
        return true;
    }

    private String getVolumeType(int StreamType) {
        if (!checkVolumeDataExist()) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        debugPrintLog("getVolumeType() " + StreamType + " length:" + mStreamVolumeType.volumeTypeList.length);
        for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
            debugPrintLog("getVolumeType() i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " voltype:" + mStreamVolumeType.volumeTypeList[i].volumeType);
            if (mStreamVolumeType.volumeTypeList[i].streamTypeIndex == StreamType) {
                return mStreamVolumeType.volumeTypeList[i].volumeType;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    private String getVolumeTypeByStr(String StreamType) {
        if (!checkVolumeDataExist()) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        debugPrintLog("getVolumeType() " + StreamType + " length:" + mStreamVolumeType.volumeTypeList.length);
        for (int i = 0; i < mStreamVolumeType.volumeTypeList.length; i++) {
            debugPrintLog("getVolumeType() i:" + i + " streamTypeIndex=" + mStreamVolumeType.volumeTypeList[i].streamTypeIndex + " voltype:" + mStreamVolumeType.volumeTypeList[i].volumeType);
            if (mStreamVolumeType.volumeTypeList[i].streamType.equals(StreamType)) {
                return mStreamVolumeType.volumeTypeList[i].volumeType;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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

    private boolean checkStreamIsFixed(int StreamType) {
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
        boolean z;
        if (value == null) {
            return 0;
        }
        String mode = value.getMode();
        int hashCode = mode.hashCode();
        if (hashCode != -902359628) {
            if (hashCode == 2032856957 && mode.equals("driveringMode")) {
                z = false;
            }
            z = true;
        } else {
            if (mode.equals("dangerVolLevel")) {
                z = true;
            }
            z = true;
        }
        switch (z) {
            case false:
                return mXuiAudioPolicy.getMainDriverMode();
            case true:
                return getDangerousTtsVolLevel();
            default:
                return 0;
        }
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
                    if (getVolumeValueModeIndex(mVolumeTypeData.volumeTypeDataList[i]) == mVolumeValue.modeindex) {
                        debugPrintLog("getDefaultVolume modeIndex:" + mVolumeValue.modeindex + " defaultVal:" + mVolumeValue.defaultVal);
                        return mVolumeValue.defaultVal;
                    }
                }
                continue;
            }
        }
        return BaseDafaultVol;
    }

    private String getPropertyForVolumeType(String volumeType) {
        if (!checkVolumeObjectDataExist()) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        debugPrintLog("getPropertyForVolumeType " + volumeType);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                debugPrintLog("getPropertyForVolumeType property:" + mVolumeTypeData.volumeTypeDataList[i].property);
                return mVolumeTypeData.volumeTypeDataList[i].property;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private boolean checkVolumeTypeIsOn(String volumeType) {
        char c;
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
        switch (c) {
            case 0:
                if (mXuiAudioPolicy != null) {
                    ret = mXuiAudioPolicy.isKaraokeOn();
                    break;
                }
                break;
            case 1:
                if (mXuiAudioPolicy != null) {
                    ret = mXuiAudioPolicy.getDangerousTtsStatus() != 0;
                    break;
                }
                break;
            case 2:
                if (mXuiAudioPolicy != null && (((temp = mXuiAudioPolicy.getBtCallOnFlag()) == 2 || temp == 3) && mXuiAudioPolicy.getBtCallMode() == 1)) {
                    ret = true;
                    break;
                }
                break;
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

    private volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.EnvTrigger[] getEnvTriggerObject(int screenId, int ampLevel) {
        if (mConflictVolumeObject == null || mConflictVolumeObject.policy == null) {
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
                if (split2 != null) {
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
                return -1;
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
        Log.d(TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + str);
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
        if (mConflictVolumeObject == null || mConflictVolumeObject.policy == null) {
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
        if (mXuiAudioData != null) {
            return mXuiAudioData.getStreamMute(streamType);
        }
        return false;
    }

    public int getStreamVolume(int streamType) {
        if (mXuiAudioData != null) {
            return mXuiAudioData.getStreamVolume(streamType);
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
        if (mXuiAudioData != null) {
            return mXuiAudioData.getStreamVolume(streamType);
        }
        return -1;
    }

    public void setMusicLimitMode(boolean modeOn) {
        if (mXuiAudioData != null) {
            mXuiAudioData.saveMusicLimitMode(modeOn);
        }
        this.mIsMusicLimitmode = modeOn;
        int index = getStreamVolume(3);
        sendVolumeMessage(1, 3, index, 1, AUDIO_SYSTEM_PACKAGENAME);
    }

    public boolean isMusicLimitMode() {
        if (mXuiAudioData != null) {
            mXuiAudioData.getMusicLimitMode();
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

    public synchronized boolean setFixedVolume(boolean enable, int vol, int streamType, String callingPackage) {
        Log.i(TAG, "setFixedVolume  " + enable + " vol:" + vol + " streamType:" + streamType + " packageName:" + callingPackage);
        int resetVol = 0;
        if (!enable && !getStreamMute(streamType)) {
            resetVol = getStreamVolume(streamType);
        }
        if (this.mFixedVolumeMode[streamType] == enable) {
            String reason = enable ? "setted!" : "restored";
            Log.w(TAG, "setFixedVolume  volume " + reason);
            return false;
        }
        doSetStreamVolume(streamType, enable ? vol : resetVol, enable ? 268468224 : 8, 0, true, false, callingPackage);
        SystemProperties.set("persist.audioconfig.fixedvolume", enable ? "true" : "false");
        this.mFixedVolumeMode[streamType] = enable;
        return false;
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
        if (mXuiAudioPolicy != null && (dangerttsStatus = mXuiAudioPolicy.getDangerousTtsStatus()) != 0) {
            mXuiAudioPolicy.setDangerousTtsStatus(dangerttsStatus);
        }
    }

    public int getDangerousTtsVolLevel() {
        return mXuiAudioData.getDangerTtsLevel();
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        if (mXuiAudioData != null) {
            mXuiAudioData.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        if (mXuiAudioData != null) {
            return mXuiAudioData.getNavVolDecreaseEnable();
        }
        return true;
    }

    public void triggerEnvConflictPolicy(int flag, boolean on, String packageName) {
        volumeJsonObject.conflictVolumeObject.conflictVolumePolicy.conflictModePolicy[] tConflictModePolicy = getEnvConflictModePolicy(flag);
        if (tConflictModePolicy == null) {
            Log.e(TAG, "triggerEnvConflictPolicy " + flag + " conflictModePolicy not found!!");
            return;
        }
        Log.d(TAG, "triggerEnvConflictPolicy  " + flag + " on:" + on);
        for (int i = 0; i < tConflictModePolicy.length; i++) {
            int streamType = getStreamTypeIndex(tConflictModePolicy[i].targetStream);
            if (getStreamMute(streamType)) {
                Log.d(TAG, "triggerEnvConflictPolicy  " + tConflictModePolicy[i].targetStream + " is muted do not change");
            } else {
                boolean exist = checkTempVolChangeDataExist(streamType, flag, packageName);
                if (exist == on) {
                    Log.d(TAG, "triggerEnvConflictPolicy  exist==on");
                } else {
                    int index = getDstVolByParseConflictPolicy(tConflictModePolicy[i]);
                    if (index > MinVol && index < MaxVol) {
                        doTemporaryChangeVolumeDown(streamType, index, ENV_TRIGGER_SYSVOL_DIRECTION_DOWN.equals(tConflictModePolicy[i].changeType) ? 1 : 2, !on, flag, packageName);
                    }
                }
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
                int i = 100 + StreamType;
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
        if (mXuiAudioData != null) {
            mXuiAudioData.setDangerousTtsStatus(on);
        }
        if (on == 0) {
            if (getStreamMute(9)) {
                index = 0;
            } else {
                index = getStreamVolume(9);
            }
            debugPrintLog("setDangerousTtsStatus " + on + "  dangerous index=" + index);
            doSetStreamVolume(9, index, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
            triggerEnvConflictPolicy(8, false, XUI_SERVICE_PACKAGE);
            return;
        }
        int StreamDangerous = getStreamTypeIndex(xuiAudioPolicy.STREAM_DANGEROUS);
        int index2 = mXuiAudioData.getStreamVolume(StreamDangerous);
        debugPrintLog("setDangerousTtsStatus " + on + "  dangerous index=" + index2);
        doSetStreamVolume(StreamDangerous, index2, 0, 0, true, false, AUDIO_SYSTEM_PACKAGENAME);
        triggerEnvConflictPolicy(8, true, XUI_SERVICE_PACKAGE);
    }

    public int getDangerousTtsStatus() {
        if (mXuiAudioData != null) {
            return mXuiAudioData.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setBtCallOnFlag(int flag) {
        if ((flag == 2 || flag == 3) && mXuiAudioPolicy.getBtCallMode() == 1) {
            doSetStreamVolume(6, getStreamVolume(6), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        } else {
            doSetStreamVolume(2, getStreamVolume(2), 0, 0, false, false, AUDIO_SYSTEM_PACKAGENAME);
        }
        if (flag != 0) {
            triggerEnvConflictPolicy(4, true, BTCALL_VOLCHANGE_PACKAGE);
        } else {
            triggerEnvConflictPolicy(4, false, BTCALL_VOLCHANGE_PACKAGE);
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
            return;
        }
        Log.d(TAG, "setVolumeToGroup " + streamType + " " + index + " flag:" + flag + " mFixedVolumeMode:" + this.mFixedVolumeMode[streamType]);
        if (flag == 268468224 || flag == 8 || !this.mFixedVolumeMode[streamType]) {
            if (streamType == 3 && this.mIsMusicLimitmode) {
                index /= 2;
            }
            mXuiVolumeToHal.setGroupVolume(getGroupId(getVolumeType(streamType)), index, flag);
            sendMusicVolumeToAmp(streamType, index);
        }
    }

    private void sendMusicVolumeToAmp(int streamType, int index) {
        if (streamType == 3 && this.isHighAmp) {
            if (index < 0 || index > 30) {
                Log.w(TAG, "sendMusicVolumeToAmp  index=" + index + "  out of range");
                return;
            }
            Log.i(TAG, "sendMusicVolumeToAmp  index=" + index);
            if (mXuiVolumeToHal != null) {
                mXuiVolumeToHal.sendMusicVolumeToAmp(index);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
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
        if (packageName.equals(XUI_SERVICE_PACKAGE2) || packageName.equals("com.android.systemui") || packageName.equals(SETTINGS_PACKAGE) || packageName.equals("android")) {
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
        if (tempVolChangeSaved == null) {
            tempVolChangeSaved = new int[11];
        }
        Arrays.fill(tempVolChangeSaved, -1);
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
        Log.d(TAG, "saveTempChangeVol " + StreamType + " " + tempVolChangeSaved[StreamType] + " " + getStreamVolume(StreamType));
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0 && tempVolChangeSaved[StreamType] == -1) {
            tempVolChangeSaved[StreamType] = getStreamVolume(StreamType);
            debugPrintLog("saveTempChangeVol " + StreamType + " " + tempVolChangeSaved[StreamType]);
        }
    }

    private synchronized void removeTempChangeVol(int StreamType) {
        Log.d(TAG, "removeTempChangeVol " + StreamType);
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0 && tempVolChangeSaved[StreamType] != -1) {
            tempVolChangeSaved[StreamType] = -1;
            debugPrintLog("removeTempChangeVol " + StreamType + " " + tempVolChangeSaved[StreamType]);
        }
    }

    private int getTempChangeVol(int StreamType) {
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0) {
            debugPrintLog("getTempChangeVol " + StreamType + " " + tempVolChangeSaved[StreamType]);
            return tempVolChangeSaved[StreamType];
        }
        return -1;
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
        if (mXuiVolumeToHal != null) {
            mXuiVolumeToHal.broadcastVolumeToICM(streamType, volume, getStreamVolume(streamType), fromadj);
        }
    }

    private void broadcastVolumeChangedToAll(int streamType, int newVol, int oldVol, int xuiflag, String packageName) {
        long ident = Binder.clearCallingIdentity();
        boolean adjust = false;
        boolean muteflag = false;
        if (streamType == 3 && (mXuiAudioPolicy.isOtherSessionOn() || mXuiAudioPolicy.isKaraokeOn())) {
            muteflag = true;
        }
        if (packageName.equals("android")) {
            adjust = true;
        }
        boolean adjust2 = adjust;
        Log.i(TAG, "broadcastVolumeChangedToAll streamType:" + streamType + " newVol:" + newVol + " pkgName:" + packageName + " muteflag:" + muteflag + " adjust:" + adjust2 + " xuiflag:" + xuiflag);
        try {
            try {
            } catch (Throwable th) {
                th = th;
            }
        } catch (Exception e) {
            e = e;
        } catch (Throwable th2) {
            th = th2;
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        synchronized (xpAuioBroadcastLock) {
            try {
                this.mVolumeChanged.addFlags(67108864);
                this.mVolumeChanged.addFlags(268435456);
                this.mVolumeChanged.putExtra(EXTRA_VOLCHANGE_PACKAGENAME, packageName);
                this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", streamType);
                this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", newVol);
                this.mVolumeChanged.putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", oldVol);
                this.mVolumeChanged.putExtra(AUDIO_EXTRA_AUTOVOLUME_FLAG, xuiflag);
                int i = 1;
                if (!adjust2) {
                    this.mVolumeChanged.putExtra(AUDIO_EXTRA_VOLUME_STREAM_FLAG, 0);
                } else {
                    this.mVolumeChanged.putExtra(AUDIO_EXTRA_VOLUME_STREAM_FLAG, 1);
                }
                Intent intent = this.mVolumeChanged;
                if (!muteflag) {
                    i = 0;
                }
                intent.putExtra(AUDIO_EXTRA_MUSIC_RUNNING_FLAG, i);
                String str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                if (muteflag) {
                    StringBuilder mStr = new StringBuilder();
                    List<String> mList = mXuiAudioPolicy.getOtherMusicPlayingPkgs();
                    for (int i2 = 0; i2 < mList.size(); i2++) {
                        mStr.append(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + mList.get(i2) + ";");
                    }
                    str = mStr.toString();
                }
                this.mVolumeChanged.putExtra(EXTRA_OTHERPLAYING_PACKAGENAME, str);
                mContext.sendBroadcastAsUser(this.mVolumeChanged, UserHandle.ALL);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th3) {
                th = th3;
                try {
                    try {
                        throw th;
                    } catch (Exception e2) {
                        e = e2;
                        handleException("broadcastVolumeChangedToAll ", e);
                        Binder.restoreCallingIdentity(ident);
                    }
                } catch (Throwable th4) {
                    th = th4;
                    Binder.restoreCallingIdentity(ident);
                    throw th;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:51:0x0116 A[Catch: Exception -> 0x0186, TryCatch #1 {Exception -> 0x0186, blocks: (B:3:0x000a, B:6:0x0047, B:8:0x0059, B:10:0x005f, B:18:0x0089, B:19:0x008c, B:21:0x00a4, B:51:0x0116, B:52:0x011b, B:53:0x011d, B:64:0x0130, B:73:0x0144, B:75:0x0155, B:74:0x0148, B:22:0x00b0, B:23:0x00b7, B:30:0x00c4, B:32:0x00c8, B:38:0x00d5, B:33:0x00cc, B:35:0x00d0, B:39:0x00dd, B:41:0x00e7, B:43:0x00eb, B:45:0x00f0, B:44:0x00ee, B:48:0x010b, B:55:0x011f, B:59:0x0126, B:60:0x0129), top: B:86:0x000a }] */
    /* JADX WARN: Removed duplicated region for block: B:54:0x011e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void xpadjustStreamVolume(int r21, int r22, int r23, java.lang.String r24) {
        /*
            Method dump skipped, instructions count: 408
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xui.xuiaudio.xuiAudioVolume.xuiVolumePolicy.xpadjustStreamVolume(int, int, int, java.lang.String):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:43:0x014f  */
    /* JADX WARN: Removed duplicated region for block: B:81:0x0142 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:93:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void doSetStreamVolume(int r23, int r24, int r25, int r26, boolean r27, boolean r28, java.lang.String r29) {
        /*
            Method dump skipped, instructions count: 462
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xui.xuiaudio.xuiAudioVolume.xuiVolumePolicy.doSetStreamVolume(int, int, int, int, boolean, boolean, java.lang.String):void");
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
        if (curStep <= step) {
            this.mFadingFlag[streamType] = true;
            int sleepTime = fadeTime / step;
            int stepSetVol = fadeIn ? ((Math.abs(srcVol - dstVol) * curStep) / step) + srcVol : srcVol - ((Math.abs(srcVol - dstVol) * curStep) / step);
            int messageId = 100 + streamType;
            sendVolFadeMessage(messageId, streamType, stepSetVol, srcVol, dstVol, curStep, step, fadeTime, flag, sleepTime, xuiflag, pkgName);
            return;
        }
        this.mFadingFlag[streamType] = false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class StreamVolumeClass {
        int flag;
        String packageName;
        int streamType;
        int value;

        private StreamVolumeClass() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
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
            int messageId = 100 + streamType;
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

    /* loaded from: classes.dex */
    public class xuiVolumeHandler extends Handler {
        public xuiVolumeHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    Log.i(xuiVolumePolicy.TAG, "handle MSG_SETSTREAM_VOLUME streamtype:" + msg.arg1);
                    StreamVolumeClass mStreamVolumeClass = (StreamVolumeClass) msg.obj;
                    xuiVolumePolicy.this.doSetStreamVolume(msg.arg1, msg.arg2, 0, 0, false, mStreamVolumeClass.flag != 0, mStreamVolumeClass.packageName);
                    return;
                case 2:
                    Log.i(xuiVolumePolicy.TAG, "handle MSG_ADJUSTSTREAM_VOLUME");
                    StreamVolumeClass mStreamVolumeClass2 = (StreamVolumeClass) msg.obj;
                    xuiVolumePolicy.this.xpadjustStreamVolume(msg.arg1, msg.arg2, mStreamVolumeClass2.flag, mStreamVolumeClass2.packageName);
                    return;
                default:
                    if (msg.what < 100 || msg.what >= 120) {
                        return;
                    }
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
                                return;
                            } catch (Throwable th2) {
                                th = th2;
                            }
                        } catch (Throwable th3) {
                            th = th3;
                        }
                    }
                    break;
            }
        }
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + func + " " + e);
    }
}
