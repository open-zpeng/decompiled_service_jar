package com.xiaopeng.xui.xuiaudio.xuiAudioEffect;

import android.content.Context;
import android.media.AudioSystem;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.os.SystemProperties;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioEffect.AudioEffectData;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.List;
/* loaded from: classes.dex */
public class xuiEffectPolicy {
    public static final int DYNAUDIO_DEFAULT_TYPE = 3;
    private static final String TAG = "xuiEffectPolicy";
    public static final int XSOUND_DEFAULT_TYPE = 1;
    public static final int XUI_DEFAULT_MODE = 3;
    public static final int XUI_DYNAUDIO_MODE = 3;
    public static final int XUI_DYNAUDIO_TYPE_CUSTOMER = 5;
    public static final int XUI_DYNAUDIO_TYPE_DYNAMIC = 3;
    public static final int XUI_DYNAUDIO_TYPE_HUMAN = 4;
    public static final int XUI_DYNAUDIO_TYPE_REAL = 1;
    public static final int XUI_DYNAUDIO_TYPE_SOFT = 2;
    public static final int XUI_HIGH_XSOUND_COMMON = 99;
    public static final int XUI_ORIGIN_MODE = 4;
    public static final int XUI_SCENE_HAL = 4;
    public static final int XUI_SCENE_KTV = 3;
    public static final int XUI_SCENE_LIVE = 2;
    public static final int XUI_SCENE_OFF = 5;
    public static final int XUI_SCENE_THEATRE = 1;
    public static final int XUI_SMART_MODE = 2;
    public static final int XUI_SOUND_TYPE_ORIGIN = 0;
    public static final int XUI_XSOUND_MODE = 1;
    public static final int XUI_XSOUND_TYPE_CLASIC = 6;
    public static final int XUI_XSOUND_TYPE_COMMON = 1;
    public static final int XUI_XSOUND_TYPE_CUSTMOER = 7;
    public static final int XUI_XSOUND_TYPE_DISCO = 5;
    public static final int XUI_XSOUND_TYPE_HUMAN = 2;
    public static final int XUI_XSOUND_TYPE_JAZZ = 3;
    public static final int XUI_XSOUND_TYPE_ROCK = 4;
    private static boolean isSupportSoundEffectScene;
    private static Context mContext;
    private static xuiEffectPolicy mInstance;
    private static List<AudioEffectData> mXpAudioEffectList;
    private static xuiAudioData mXuiAudioData;
    private static IXuiEffectInterface mXuiEffectAmp;
    private static xuiEffectToHal mXuiEffectHal;
    private boolean isAmpHigh;
    private static int mAudioFeature = 0;
    private static final int AUDIO_EFFECT_FORM = FeatureOption.FO_AUDIO_EFFECT_FORM;
    public static final boolean XSOUND_AMP_SOP_PROTECT = SystemProperties.getBoolean("persist.audio.SOP.protect", false);
    public static int DEFAULT_SOUNDFIELD_X = SystemProperties.getInt("persist.xpaudio.soundfield.x", 50);
    public static int DEFAULT_SOUNDFIELD_Y = SystemProperties.getInt("persist.xpaudio.soundfield.y", 50);
    private static int lastSoundType = -1;
    private static int lastMode = -1;
    private static int maxModeIndex = 0;

    static {
        isSupportSoundEffectScene = FeatureOption.FO_AUDIO_SOUND_EFFECT_SCENE == 1;
    }

    private xuiEffectPolicy(Context context, xuiAudioPolicy instance) {
        this.isAmpHigh = false;
        mContext = context;
        this.isAmpHigh = SystemProperties.getInt("persist.sys.xiaopeng.AMP", 0) == 1;
        mXuiAudioData = xuiAudioData.getInstance(context);
        if (AUDIO_EFFECT_FORM != 0) {
            mXpAudioEffectList = xuiAudioParser.getAudioEffectDataList();
        }
        mXuiEffectHal = xuiEffectToHal.getInstance(context);
        mXuiEffectAmp = xuiEffectToAmp.getInstance(context, instance);
        maxModeIndex = getMaxIndex();
        initEffectPolicy();
    }

    public static xuiEffectPolicy getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiEffectPolicy(context, instance);
        }
        return mInstance;
    }

    private void initEffectPolicy() {
        if (this.isAmpHigh) {
            AudioSystem.setParameters("vehicle_model=2");
        } else {
            AudioSystem.setParameters("vehicle_model=1");
        }
        if (SystemProperties.get("persist.audio.xpeffect.mode", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS).equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
            Log.i(TAG, "AudioEffect first setting");
            setXpAudioEffectMode(1);
            setSoundField(4, DEFAULT_SOUNDFIELD_X, DEFAULT_SOUNDFIELD_Y);
            if (this.isAmpHigh) {
                setSoundField(3, DEFAULT_SOUNDFIELD_X, DEFAULT_SOUNDFIELD_Y);
            }
            setSoundField(1, DEFAULT_SOUNDFIELD_X, DEFAULT_SOUNDFIELD_Y);
            if (AUDIO_EFFECT_FORM != 0) {
                setSoundEffectType(1, 1);
            }
            setSoundEffectScene(1, 4);
            if (this.isAmpHigh && mXuiEffectHal != null) {
                mXuiEffectHal.setXpCustomizeEffect();
                return;
            }
            return;
        }
        setXpAudioEffectMode(mXuiAudioData.getXpAudioEffectMode());
        if (AUDIO_EFFECT_FORM == 1) {
            int mode = getSoundEffectMode();
            int type = getSoundEffectType(mode);
            if (((mode == 1 && type == 7) || ((mode == 3 && type == 5) || this.isAmpHigh)) && mXuiEffectHal != null) {
                mXuiEffectHal.setXpCustomizeEffect();
            }
        }
    }

    public int getMaxIndex() {
        if (mXpAudioEffectList == null) {
            Log.e(TAG, "getMaxIndex mXpAudioEffectList ==null");
            return 0;
        }
        int maxIndex = 0;
        for (int i = 0; i < mXpAudioEffectList.size(); i++) {
            AudioEffectData tXpAudioEffectParam = mXpAudioEffectList.get(i);
            if (tXpAudioEffectParam != null && tXpAudioEffectParam.ModeIndex > maxIndex) {
                maxIndex = tXpAudioEffectParam.ModeIndex;
            }
        }
        return maxIndex;
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        Log.i(TAG, "setSoundField mode:" + mode + " " + xSound + " " + ySound);
        if (mXuiEffectHal != null) {
            mXuiEffectHal.setSoundField(mode, xSound, ySound);
        }
        if (mXuiEffectAmp != null) {
            mXuiEffectAmp.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        if (mXuiEffectHal != null) {
            return mXuiEffectHal.getSoundField(mode);
        }
        return null;
    }

    public int getSoundEffectMode() {
        if (AUDIO_EFFECT_FORM == 0) {
            if (mXuiEffectHal != null) {
                return mXuiEffectHal.getSoundEffectMode();
            }
            return -1;
        } else if (AUDIO_EFFECT_FORM == 1 && mXuiAudioData != null) {
            return mXuiAudioData.getXpAudioEffectMode();
        } else {
            return -1;
        }
    }

    public void setSoundEffectMode(int mode) {
        Log.i(TAG, "setSoundEffectMode mode=" + mode);
        if (AUDIO_EFFECT_FORM == 1) {
            if (mXuiEffectAmp != null) {
                mXuiEffectAmp.setSoundEffectMode(mode);
            }
            setXpAudioEffectMode(mode);
        } else if (AUDIO_EFFECT_FORM == 0) {
            setSoundEffectType(mode, getSoundEffectType(mode));
        }
        int ret = AudioSystem.setParameters("method=setSoundEffectMode;mode=" + mode + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectMode ERROR");
        }
        SoundField sf = getSoundField(mode);
        setSoundField(mode, sf.x, sf.y);
        setSoundEffectScene(mode, getSoundEffectScene(mode));
    }

    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType mode=" + mode + " type=" + type);
        if (AUDIO_EFFECT_FORM == 0) {
            if (mXuiEffectHal != null) {
                mXuiEffectHal.setSoundEffectType(mode, type);
            }
        } else if (AUDIO_EFFECT_FORM == 1) {
            if (mXuiEffectAmp != null) {
                mXuiEffectAmp.setSoundEffectType(mode, type);
            }
            setXpAudioEffect(mode, type, false);
        }
    }

    public int getSoundEffectType(int mode) {
        if (AUDIO_EFFECT_FORM == 0) {
            if (mXuiEffectHal != null) {
                return mXuiEffectHal.getSoundEffectType(mode);
            }
            return -1;
        } else if (AUDIO_EFFECT_FORM == 1 && mXuiAudioData != null) {
            return mXuiAudioData.getXpAudioEffectType(mode);
        } else {
            return -1;
        }
    }

    public void setXpCustomizeEffect(int type, int value) {
        if (mXuiAudioData != null) {
            mXuiAudioData.setXpCustomizeEffect(type, value);
        }
        mXuiEffectHal.setXpCustomizeEffect();
    }

    public int getXpCustomizeEffect(int type) {
        if (mXuiAudioData != null) {
            return mXuiAudioData.getXpCustomizeEffect(type);
        }
        return 0;
    }

    public void flushXpCustomizeEffects(int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (mXuiAudioData != null) {
                mXuiAudioData.setXpCustomizeEffect(i, values[i]);
            }
        }
        mXuiEffectHal.setXpCustomizeEffect();
    }

    public void setSoundEffectScene(int mode, int type) {
        Log.i(TAG, "setSoundEffectScene mode=" + mode + " type=" + type + " isSupportSoundEffectScene=" + isSupportSoundEffectScene);
        if (!isSupportSoundEffectScene) {
            return;
        }
        if (mXuiEffectHal != null) {
            mXuiEffectHal.setSoundEffectScene(mode, type);
        }
        if (mXuiEffectAmp != null) {
            mXuiEffectAmp.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        if (mXuiEffectHal != null) {
            return mXuiEffectHal.getSoundEffectScene(mode);
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        if (mXuiEffectHal != null) {
            mXuiEffectHal.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        if (mXuiEffectHal != null) {
            return mXuiEffectHal.getSoundEffectParms(effectType, modeType);
        }
        return new SoundEffectParms(0, 0, 0);
    }

    public void setXpAudioEffectMode(int mode) {
        Log.i(TAG, "setXpAudioEffectMode mode:" + mode);
        if (mode < 1 || mode > 4) {
            Log.e(TAG, "setXpAudioEffectMode  mode:" + mode + " over limit!!!");
        } else if (mXuiAudioData == null) {
            Log.e(TAG, "setXpAudioEffectMode ERROR mXuiAudioData==null");
        } else {
            mXuiAudioData.setXpAudioEffectMode(mode);
            int paramType = mXuiAudioData.getXpAudioEffectType(mode);
            setXpAudioEffect(mode, paramType, true);
        }
    }

    public synchronized void setXpAudioEffect(int mode, int type, boolean force) {
        Log.i(TAG, "setXpAudioEffect mode:" + mode + " type:" + type);
        if (mXpAudioEffectList != null && mXuiAudioData != null) {
            Log.d(TAG, "setXpAudioEffect  paramType:" + type);
            if (type >= 0 && type <= maxModeIndex) {
                mXuiAudioData.setXpAudioEffect(mode, type);
                if (this.isAmpHigh) {
                    return;
                }
                if (this.isAmpHigh && !XSOUND_AMP_SOP_PROTECT && mode == 1) {
                    type = 99;
                } else if (this.isAmpHigh && !XSOUND_AMP_SOP_PROTECT && mode == 3) {
                    type += 100;
                }
                if (type == lastSoundType && lastMode == mode) {
                    Log.d(TAG, "setXpAudioEffect  SAME TYPE type:" + type);
                    return;
                }
                AudioEffectData tXpAudioEffectParam = getXpAudioEffectParam(mXpAudioEffectList, type);
                if (tXpAudioEffectParam == null) {
                    Log.e(TAG, "setXpAudioEffect tXpAudioEffectParam==null");
                    return;
                }
                lastMode = mode;
                lastSoundType = type;
                String name = tXpAudioEffectParam.ModeName;
                int index = tXpAudioEffectParam.ModeIndex;
                AudioEffectData.XpEffectModeParam[] mParam = tXpAudioEffectParam.ModeParam;
                if (mParam != null) {
                    for (int j = 0; j < mParam.length; j++) {
                        Log.i(TAG, "setAudioEffectParams name:" + name + " index:" + index + " paramindex:" + j);
                        setAudioEffectParams(name, index, mParam[j]);
                    }
                    int ret = AudioSystem.setParameters("trigerSelfType=true");
                    if (ret == -1) {
                        Log.e(TAG, "trigerSelfType ERROR");
                    }
                }
                return;
            }
            Log.e(TAG, "setXpAudioEffect param error!  type:" + type + "  maxModeIndex:" + maxModeIndex);
            return;
        }
        Log.e(TAG, "setXpAudioEffect mXpAudioEffectList or mXuiAudioData == null");
    }

    public void setAudioEffectParams(String name, int index, AudioEffectData.XpEffectModeParam param) {
        if (mXuiEffectHal != null) {
            mXuiEffectHal.setAudioEffectParams(name, index, param);
        }
    }

    private AudioEffectData getXpAudioEffectParam(List<AudioEffectData> mXpAudioEffectList2, int index) {
        for (AudioEffectData mAudioEffectParam : mXpAudioEffectList2) {
            if (mAudioEffectParam.ModeIndex == index) {
                return mAudioEffectParam;
            }
        }
        return null;
    }
}
