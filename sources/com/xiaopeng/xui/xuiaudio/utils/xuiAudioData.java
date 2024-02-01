package com.xiaopeng.xui.xuiaudio.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.media.SoundField;
import android.os.Binder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;

/* loaded from: classes2.dex */
public class xuiAudioData {
    public static final int AmpValueIgnore = -1;
    private static final int BaseDafaultVol = 15;
    public static final String PROP_DYNAUDIO_TYPE = "persist.audio.xpeffect.dynaudio.type";
    private static final String PROP_NAVIGATION_MUTED = "persist.audio.navi.muted";
    private static final String PROP_VOLUME_LIMITEDMODE = "persist.audio.volume.limitemode";
    public static final String PROP_XSOUND_TYPE = "persist.audio.xpeffect.xsound.type";
    public static final String PROP_XUIAUDIO_MODE = "persist.audio.xpeffect.mode";
    private static final String TAG = "xuiAudioData";
    private static final String XP_VOLUME_AVAS = "XP_VOLUME_AVAS";
    private static final String XP_VOLUME_DANGEROUS = "XP_VOLUME_DANGEROUS";
    private static final String XP_VOLUME_HFP = "XP_VOLUME_HFP";
    private static final String XP_VOLUME_KARAOKE = "XP_VOLUME_KARAOKE";
    private static final String XP_VOLUME_MEDIA = "XP_VOLUME_MEDIA";
    private static final String XP_VOLUME_NAVIGATION = "XP_VOLUME_NAVIGATION";
    private static final String XP_VOLUME_PASSENGER_BT = "XP_VOLUME_PASSENGER_BT";
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
        volumeJsonObject.streamVolumePare streamvolumepare = mVolumePare;
        if (streamvolumepare != null && mXpVolumes == null) {
            mXpVolumes = new int[streamvolumepare.getMaxVolType()];
            debugPrintLog("MaxVolType:" + mVolumePare.getMaxVolType());
            for (int i = 0; i < mVolumePare.getMaxVolType(); i++) {
                debugPrintLog("" + i + SliceClientPermissions.SliceAuthority.DELIMITER + mVolumePare.getMaxVolType());
                String voltype = getVolumeTypeStr(i);
                String prop = getPropertyForVolumeType(voltype);
                int defvolume = getDefaultVolume(i);
                mXpVolumes[i] = getSavedVolume(prop, defvolume);
                debugPrintLog("voltype:" + voltype + " prop:" + prop + " " + mXpVolumes[i]);
            }
        }
        volumeJsonObject.streamVolumePare streamvolumepare2 = mVolumePare;
        if (streamvolumepare2 != null && mXpMutes == null) {
            mXpMutes = new boolean[streamvolumepare2.getMaxVolType()];
            for (int i2 = 0; i2 < mVolumePare.getMaxVolType(); i2++) {
                mXpMutes[i2] = false;
            }
        }
        setSoundPositionEnable(getSoundPositionEnable());
    }

    public static xuiAudioData getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new xuiAudioData(context);
        }
        return mInstance;
    }

    private String getVolumeType(int streamType) {
        if (mVolumePare == null) {
            return "";
        }
        for (int i = 0; i < mVolumePare.volumeTypeList.length; i++) {
            if (mVolumePare.volumeTypeList[i].streamTypeIndex == streamType) {
                return mVolumePare.volumeTypeList[i].volumeType;
            }
        }
        return "";
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
            return "";
        }
        debugPrintLog("getVolumeTypeStr " + volumeType + " " + mVolumePare.volumeTypeList.length);
        for (int i = 0; i < mVolumePare.volumeTypeList.length; i++) {
            if (mVolumePare.volumeTypeList[i].volumeTypeIndex == volumeType) {
                return mVolumePare.volumeTypeList[i].volumeType;
            }
        }
        return "";
    }

    private String getPropertyForVolumeType(String volumeType) {
        volumeJsonObject.volumeTypeData volumetypedata = mVolumeTypeData;
        if (volumetypedata == null && volumetypedata.volumeTypeDataList == null) {
            return "";
        }
        debugPrintLog("getPropertyForVolumeType " + volumeType + " " + mVolumeTypeData.volumeTypeDataList.length);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            debugPrintLog("getPropertyForVolumeType i:" + i + " " + mVolumeTypeData.volumeTypeDataList[i]);
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volumeType)) {
                return mVolumeTypeData.volumeTypeDataList[i].property;
            }
        }
        return "";
    }

    private int getDefaultVolume(int volumeType) {
        volumeJsonObject.volumeTypeData volumetypedata = mVolumeTypeData;
        if (volumetypedata == null && volumetypedata.volumeTypeDataList == null) {
            return 15;
        }
        debugPrintLog("getDefaultVolume " + volumeType);
        String volType = getVolumeTypeStr(volumeType);
        for (int i = 0; i < mVolumeTypeData.volumeTypeDataList.length; i++) {
            if (mVolumeTypeData.volumeTypeDataList[i].volumeType.equals(volType) && mVolumeTypeData.volumeTypeDataList[i].volumeValue != null) {
                for (int j = 0; j < mVolumeTypeData.volumeTypeDataList[i].volumeValue.length; j++) {
                    volumeJsonObject.volumeTypeData.VolumeValue mVolumeValue = mVolumeTypeData.volumeTypeDataList[i].volumeValue[j];
                    if (mVolumeValue.getAmpValue() == -1 || mVolumeValue.getAmpValue() == AmpValue) {
                        debugPrintLog("getDefaultVolume modeIndex:" + mVolumeValue.modeindex + " defaultVal:" + mVolumeValue.defaultVal);
                        return mVolumeValue.defaultVal;
                    }
                }
                continue;
            }
        }
        return 15;
    }

    public void saveStreamVolume(int streamType, int index, String prop) {
        saveStreamVolume(streamType, index, true, prop);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public synchronized void saveStreamVolume(int streamType, int index, boolean savemute, String prop) {
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (!volumeType.equals("")) {
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
                    case 1004426101:
                        if (volumeType.equals(XP_VOLUME_PASSENGER_BT)) {
                            c = 7;
                            break;
                        }
                        break;
                    case 1194309036:
                        if (volumeType.equals(XP_VOLUME_DANGEROUS)) {
                            c = 6;
                            break;
                        }
                        break;
                    case 1443252837:
                        if (volumeType.equals(XP_VOLUME_AVAS)) {
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
                    case 6:
                        saveVolume(prop, index);
                        break;
                    case 7:
                        saveVolume(prop, index);
                        break;
                }
                return;
            }
        }
        Log.e(TAG, "saveStreamVolume error: streamType=" + streamType);
    }

    public int getStreamVolume(int streamType) {
        int[] iArr;
        String volumeType = getVolumeType(streamType);
        int volumeTypeIndex = getVolumeTypeInt(volumeType);
        if (volumeTypeIndex == -1 || (iArr = mXpVolumes) == null) {
            Log.e(TAG, "getStreamVolume error: streamType=" + streamType);
            return -1;
        }
        return iArr[volumeTypeIndex];
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
        Context context = mContext;
        if (context == null) {
            return 3;
        }
        int ttsVolLevel = Settings.System.getInt(context.getContentResolver(), "DangerousTtsVolLevel", 3);
        return ttsVolLevel;
    }

    public boolean getMusicLimitMode() {
        String limitmode = SystemProperties.get(PROP_VOLUME_LIMITEDMODE, "false");
        return limitmode.equals("true");
    }

    public void setBtHeadPhone(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpBtHeadPhoneOn", enable ? 1 : 0);
        }
    }

    public boolean isBtHeadPhoneOn() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpBtHeadPhoneOn", 0) == 1;
    }

    public void setMainDriverMode(int mode) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpMainDriverMode", mode);
        }
    }

    public int getMainDriverMode() {
        Context context = mContext;
        if (context != null) {
            return Settings.System.getInt(context.getContentResolver(), "XpMainDriverMode", AmpValue);
        }
        return 0;
    }

    public void setStereoAlarm(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpStereoAlarmOn", enable ? 1 : 0);
        }
    }

    public boolean isStereoAlarmOn() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpStereoAlarmOn", 0) == 1;
    }

    public void setSoundPositionEnable(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpSoundPositionEnable", enable ? 1 : 0);
        }
        SystemProperties.set("persist.xpsound.position.enable", enable ? "true" : "false");
    }

    public boolean getSoundPositionEnable() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpSoundPositionEnable", AmpValue) == 1;
    }

    public void setMusicSeatEnable(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpMusicSeatEnable", enable ? 1 : 0);
        }
    }

    public boolean getMusicSeatEnable() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpMusicSeatEnable", 0) == 1;
    }

    public void setMusicSeatEffect(int index) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpMusicSeatEffect", index);
        }
    }

    public int getMusicSeatEffect() {
        Context context = mContext;
        if (context != null) {
            return Settings.System.getInt(context.getContentResolver(), "XpMusicSeatEffect", 0);
        }
        return 0;
    }

    public void setMmapToAvasEnable(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpMmapToAvasEnable", enable ? 1 : 0);
        }
    }

    public boolean getMmapToAvasEnable() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpMmapToAvasEnable", 0) == 1;
    }

    public void setKaraokeEnable(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpKaraokeEnable", enable ? 1 : 0);
        }
    }

    public boolean getKaraokeEnable() {
        Context context = mContext;
        return context != null && Settings.System.getInt(context.getContentResolver(), "XpKaraokeEnable", 0) == 1;
    }

    public void setDangerousTtsStatus(int on) {
        this.dangerousTtsOn = on;
    }

    public int getDangerousTtsStatus() {
        return this.dangerousTtsOn;
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putString(context.getContentResolver(), "XpSoundField_" + mode, "x=" + xSound + ":y=" + ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        int x = 50;
        int y = 50;
        Context context = mContext;
        if (context != null) {
            try {
                String str = Settings.System.getString(context.getContentResolver(), "XpSoundField_" + mode);
                if (str != null) {
                    String[] parms = str.split(":");
                    for (String parm : parms) {
                        if (parm.startsWith("x=")) {
                            x = Integer.parseInt(parm.substring(2));
                        } else if (parm.startsWith("y=")) {
                            y = Integer.parseInt(parm.substring(2));
                        }
                    }
                    Log.d(TAG, "getSoundField  MODE:" + mode + "x:" + x + " y:" + y);
                    return new SoundField(x, y);
                }
            } catch (Exception e) {
                Log.e(TAG, "getSoundField E:" + e);
            }
        }
        return new SoundField(x, y);
    }

    public void setSoundEffectScene(int mode, int type) {
        Context context = mContext;
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            Settings.System.putInt(contentResolver, "XpSoundEffectScene_" + mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        Context context = mContext;
        if (context != null) {
            ContentResolver contentResolver = context.getContentResolver();
            return Settings.System.getInt(contentResolver, "XpSoundEffectScene_" + mode, 1);
        }
        return 1;
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

    public void setSoundSpeedLinkLevel(int level) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpSoundSpeedLinkLevel", level);
        }
    }

    public int getSoundSpeedLinkLevel() {
        Context context = mContext;
        if (context != null) {
            return Settings.System.getInt(context.getContentResolver(), "XpSoundSpeedLinkLevel", 0);
        }
        return 0;
    }

    public void setDyn3dEffectLevel(int level) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpDyn3dEffectLevel", level);
        }
    }

    public int getDyn3dEffectLevel() {
        Context context = mContext;
        if (context != null) {
            return Settings.System.getInt(context.getContentResolver(), "XpDyn3dEffectLevel", 0);
        }
        return 0;
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        Context context = mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "decrease_volume_navigating", enable ? 1 : 0);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        Context context = mContext;
        return context == null || Settings.System.getInt(context.getContentResolver(), "decrease_volume_navigating", 1) == 1;
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

    public void saveTempChangeVol(int streamtype, int vol) {
        if (mContext != null) {
            long token = Binder.clearCallingIdentity();
            try {
                try {
                    ContentResolver contentResolver = mContext.getContentResolver();
                    Settings.System.putInt(contentResolver, "XpTempChangeVol_" + streamtype, vol);
                } catch (Exception e) {
                    Log.e(TAG, "saveTempChangeVol " + e);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public int getTempChangeVol(int streamtype) {
        if (mContext != null) {
            int ret = -1;
            long token = Binder.clearCallingIdentity();
            try {
                try {
                    ContentResolver contentResolver = mContext.getContentResolver();
                    ret = Settings.System.getInt(contentResolver, "XpTempChangeVol_" + streamtype, -1);
                } catch (Exception e) {
                    Log.e(TAG, "getTempChangeVol " + e);
                }
                return ret;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return -1;
    }

    private void debugPrintLog(String str) {
        Log.d(TAG, "" + str);
    }
}
