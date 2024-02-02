package com.xiaopeng.xui.xuiaudio.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
/* loaded from: classes.dex */
public class xuiAudioData {
    private static final int BaseDafaultVol = 15;
    public static final String PROP_DYNAUDIO_TYPE = "persist.audio.xpeffect.dynaudio.type";
    private static final String PROP_NAVIGATION_MUTED = "persist.audio.navi.muted";
    private static final String PROP_VOLUME_LIMITEDMODE = "persist.audio.volume.limitemode";
    public static final String PROP_XSOUND_TYPE = "persist.audio.xpeffect.xsound.type";
    public static final String PROP_XUIAUDIO_MODE = "persist.audio.xpeffect.mode";
    private static final String TAG = "xuiAudioData";
    private static final String XP_VOLUME_DANGEROUS = "XP_VOLUME_DANGEROUS";
    private static final String XP_VOLUME_HFP = "XP_VOLUME_HFP";
    private static final String XP_VOLUME_KARAOKE = "XP_VOLUME_KARAOKE";
    private static final String XP_VOLUME_MEDIA = "XP_VOLUME_MEDIA";
    private static final String XP_VOLUME_NAVIGATION = "XP_VOLUME_NAVIGATION";
    private static final String XP_VOLUME_SPEECH = "XP_VOLUME_SPEECH";
    private static final String XP_VOLUME_SYSTEM = "XP_VOLUME_SYSTEM";
    private static Context mContext;
    private int dangerousTtsOn = 0;
    private static int AmpValue = SystemProperties.getInt("persist.sys.xiaopeng.AMP", 0);
    private static volumeJsonObject.streamVolumePare mVolumePare = null;
    private static volumeJsonObject.volumeTypeData mVolumeTypeData = null;
    private static xuiAudioData mInstance = null;
    private static int[] mXpVolumes = null;
    private static boolean[] mXpMutes = null;

    private xuiAudioData(Context context) {
        mContext = context;
        mVolumePare = xuiAudioParser.parseStreamVolumePare();
        mVolumeTypeData = xuiAudioParser.parseVolumeData();
        if (mVolumePare != null && mXpVolumes == null) {
            mXpVolumes = new int[mVolumePare.getMaxVolType()];
            debugPrintLog("MaxVolType:" + mVolumePare.getMaxVolType());
            for (int i = 0; i < mVolumePare.getMaxVolType(); i++) {
                debugPrintLog(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + i + SliceClientPermissions.SliceAuthority.DELIMITER + mVolumePare.getMaxVolType());
                String voltype = getVolumeTypeStr(i);
                String prop = getPropertyForVolumeType(voltype);
                if (!prop.equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
                    mXpVolumes[i] = getSavedVolume(prop, 15);
                } else {
                    mXpVolumes[i] = getDefaultVolume(i);
                }
                debugPrintLog("voltype:" + voltype + " prop:" + prop + " " + mXpVolumes[i]);
            }
        }
        if (mVolumePare != null && mXpMutes == null) {
            mXpMutes = new boolean[mVolumePare.getMaxVolType()];
            for (int i2 = 0; i2 < mVolumePare.getMaxVolType(); i2++) {
                mXpMutes[i2] = false;
            }
        }
    }

    public static xuiAudioData getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new xuiAudioData(context);
        }
        return mInstance;
    }

    private String getVolumeType(int streamType) {
        if (mVolumePare == null) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        for (int i = 0; i < mVolumePare.volumeTypeList.length; i++) {
            if (mVolumePare.volumeTypeList[i].streamTypeIndex == streamType) {
                return mVolumePare.volumeTypeList[i].volumeType;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    private int getVolumeTypeInt(String volumeType) {
        if (mVolumePare == null) {
            return -1;
        }
        for (int i = 0; i < mVolumePare.volumeTypeList.length; i++) {
            if (mVolumePare.volumeTypeList[i].volumeType.equals(volumeType)) {
                return mVolumePare.volumeTypeList[i].volumeTypeIndex;
            }
        }
        return -1;
    }

    private String getVolumeTypeStr(int volumeType) {
        if (mVolumePare == null) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        debugPrintLog("getVolumeTypeStr " + volumeType + " " + mVolumePare.volumeTypeList.length);
        for (int i = 0; i < mVolumePare.volumeTypeList.length; i++) {
            if (mVolumePare.volumeTypeList[i].volumeTypeIndex == volumeType) {
                return mVolumePare.volumeTypeList[i].volumeType;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    private String getPropertyForVolumeType(String volumeType) {
        if (mVolumeTypeData == null && mVolumeTypeData.volumeTypeDataList == null) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        debugPrintLog("getPropertyForVolumeType " + volumeType + " " + mVolumeTypeData.volumeTypeDataList.length);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            debugPrintLog("getPropertyForVolumeType i:" + i + " " + mVolumeTypeData.volumeTypeDataList[i]);
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                return mVolumeTypeData.volumeTypeDataList[i].property;
            }
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    private int getDefaultVolume(int volumeType) {
        if (mVolumeTypeData == null && mVolumeTypeData.volumeTypeDataList == null) {
            return 15;
        }
        debugPrintLog("getDefaultVolume " + volumeType);
        String volType = getVolumeTypeStr(volumeType);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volType) && mVolumeTypeData.volumeTypeDataList[i].volumeValue != null && 0 < mVolumeTypeData.volumeTypeDataList[i].volumeValue.length) {
                volumeJsonObject.volumeTypeData.VolumeValue mVolumeValue = mVolumeTypeData.volumeTypeDataList[i].volumeValue[0];
                debugPrintLog("getDefaultVolume modeIndex:" + mVolumeValue.modeindex + " defaultVal:" + mVolumeValue.defaultVal);
                return mVolumeValue.defaultVal;
            }
        }
        return 15;
    }

    public void saveStreamVolume(int streamType, int index, String prop) {
        saveStreamVolume(streamType, index, true, prop);
    }

    public synchronized void saveStreamVolume(int streamType, int index, boolean savemute, String prop) {
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (!volumeType.equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
            char c = 65535;
            if (volumeTypeIndex != -1) {
                Log.i(TAG, "saveStreamVolume : streamType=" + streamType + ", index=" + index + ", volumeType= " + volumeType + ", volTypeIndex=" + volumeTypeIndex);
                if (mXpVolumes != null) {
                    mXpVolumes[volumeTypeIndex] = index;
                }
                if (index > 0 && savemute) {
                    saveStreamMute(streamType, false);
                }
                switch (volumeType.hashCode()) {
                    case -721976622:
                        if (volumeType.equals(XP_VOLUME_NAVIGATION)) {
                            c = 2;
                            break;
                        }
                        break;
                    case 201431200:
                        if (volumeType.equals(XP_VOLUME_SPEECH)) {
                            c = 1;
                            break;
                        }
                        break;
                    case 210174445:
                        if (volumeType.equals(XP_VOLUME_SYSTEM)) {
                            c = 3;
                            break;
                        }
                        break;
                    case 1194309036:
                        if (volumeType.equals(XP_VOLUME_DANGEROUS)) {
                            c = 5;
                            break;
                        }
                        break;
                    case 1709130772:
                        if (volumeType.equals(XP_VOLUME_HFP)) {
                            c = 4;
                            break;
                        }
                        break;
                    case 1801743430:
                        if (volumeType.equals(XP_VOLUME_MEDIA)) {
                            c = 0;
                            break;
                        }
                        break;
                }
                switch (c) {
                    case 0:
                        saveVolume(prop, index);
                        break;
                    case 1:
                        saveVolume(prop, index);
                        break;
                    case 2:
                        saveVolume(prop, index);
                        break;
                    case 3:
                        Log.d(TAG, "saveStreamVolume!!! XP_VOLUME_SYSTEM WILL BE CHANGE TO " + index);
                        break;
                    case 4:
                        saveVolume(prop, index);
                        break;
                    case 5:
                        saveVolume(prop, index);
                        break;
                }
                return;
            }
        }
        Log.e(TAG, "saveStreamVolume error: streamType=" + streamType);
    }

    public int getStreamVolume(int streamType) {
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (volumeTypeIndex == -1 || mXpVolumes == null) {
            Log.e(TAG, "getStreamVolume error: streamType=" + streamType);
            return -1;
        }
        return mXpVolumes[volumeTypeIndex];
    }

    public synchronized void saveStreamMute(int streamType, boolean mute) {
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (volumeTypeIndex == -1) {
            Log.e(TAG, "saveStreamMute error: streamType=" + streamType);
            return;
        }
        Log.i(TAG, "saveStreamMute : streamType=" + streamType + ", mute=" + mute);
        mXpMutes[volumeTypeIndex] = mute;
        if (streamType == 9) {
            saveMute(PROP_NAVIGATION_MUTED, mute ? 1 : 0);
        }
    }

    public boolean getStreamMute(int streamType) {
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (volumeTypeIndex == -1) {
            return false;
        }
        if (streamType == 9) {
            int ttsMuted = getSavedMute(PROP_NAVIGATION_MUTED, 0);
            if (ttsMuted != 1) {
                return false;
            }
            return true;
        }
        return mXpMutes[volumeTypeIndex];
    }

    private void saveVolume(String prop, int index) {
        Log.v(TAG, "saveVolume " + prop);
        SystemProperties.set(prop, Integer.toString(index));
        Log.v(TAG, "saveVolume " + prop + " done");
    }

    private int getSavedVolume(String prop, int defindex) {
        return SystemProperties.getInt(prop, defindex);
    }

    private void saveMute(String prop, int muted) {
        SystemProperties.set(prop, Integer.toString(muted));
    }

    private int getSavedMute(String prop, int defindex) {
        return SystemProperties.getInt(prop, defindex);
    }

    public void saveMusicLimitMode(boolean limitMode) {
        String limitmode = limitMode ? "true" : "false";
        SystemProperties.set(PROP_VOLUME_LIMITEDMODE, limitmode);
    }

    public void saveDangerTtsLevel(int level) {
        try {
            Settings.System.putInt(mContext.getContentResolver(), "DangerousTtsVolLevel", level);
        } catch (Exception e) {
        }
    }

    public int getDangerTtsLevel() {
        if (mContext == null) {
            return 2;
        }
        int ttsVolLevel = Settings.System.getInt(mContext.getContentResolver(), "DangerousTtsVolLevel", 2);
        return ttsVolLevel;
    }

    public boolean getMusicLimitMode() {
        String limitmode = SystemProperties.get(PROP_VOLUME_LIMITEDMODE, "false");
        return limitmode.equals("true");
    }

    public void setBtHeadPhone(boolean enable) {
        if (mContext != null) {
            Settings.System.putInt(mContext.getContentResolver(), "XpBtHeadPhoneOn", enable ? 1 : 0);
        }
    }

    public boolean isBtHeadPhoneOn() {
        return mContext != null && Settings.System.getInt(mContext.getContentResolver(), "XpBtHeadPhoneOn", 0) == 1;
    }

    public void setMainDriverMode(int mode) {
        if (mContext != null) {
            Settings.System.putInt(mContext.getContentResolver(), "XpMainDriverMode", mode);
        }
    }

    public int getMainDriverMode() {
        if (mContext != null) {
            return Settings.System.getInt(mContext.getContentResolver(), "XpMainDriverMode", AmpValue);
        }
        return 0;
    }

    public void setStereoAlarm(boolean enable) {
        if (mContext != null) {
            Settings.System.putInt(mContext.getContentResolver(), "XpStereoAlarmOn", enable ? 1 : 0);
        }
    }

    public boolean isStereoAlarmOn() {
        return mContext != null && Settings.System.getInt(mContext.getContentResolver(), "XpStereoAlarmOn", 0) == 1;
    }

    public void setDangerousTtsStatus(int on) {
        this.dangerousTtsOn = on;
    }

    public int getDangerousTtsStatus() {
        return this.dangerousTtsOn;
    }

    public void setXpAudioEffectMode(int mode) {
        SystemProperties.set("persist.audio.xpeffect.mode", Integer.toString(mode));
    }

    public int getXpAudioEffectMode() {
        return SystemProperties.getInt("persist.audio.xpeffect.mode", 1);
    }

    public int getXpAudioEffectType(int mode) {
        int ret = 0;
        if (mode == 1) {
            ret = SystemProperties.getInt("persist.audio.xpeffect.xsound.type", 1);
        } else if (mode == 3) {
            ret = SystemProperties.getInt("persist.audio.xpeffect.dynaudio.type", 3);
        }
        Log.i(TAG, "getXpAudioEffectType  mode=" + mode + "  " + ret);
        return ret;
    }

    public void setXpAudioEffect(int mode, int type) {
        Log.i(TAG, "setXpAudioEffect mode:" + mode + " type:" + type);
        if (mode == 1) {
            SystemProperties.set("persist.audio.xpeffect.xsound.type", Integer.toString(type));
        } else if (mode == 3) {
            SystemProperties.set("persist.audio.xpeffect.dynaudio.type", Integer.toString(type));
        }
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        if (mContext != null) {
            Settings.System.putInt(mContext.getContentResolver(), "decrease_volume_navigating", enable ? 1 : 0);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        return mContext == null || Settings.System.getInt(mContext.getContentResolver(), "decrease_volume_navigating", 1) == 1;
    }

    public void setXpCustomizeEffect(int type, int value) {
        if (type >= 0 && type < 10) {
            ContentResolver contentResolver = mContext.getContentResolver();
            Settings.System.putInt(contentResolver, "XpCustomizeEffect" + type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        if (type < 0 || type >= 10) {
            return 0;
        }
        ContentResolver contentResolver = mContext.getContentResolver();
        return Settings.System.getInt(contentResolver, "XpCustomizeEffect" + type, 0);
    }

    private void debugPrintLog(String str) {
        Log.d(TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + str);
    }
}
