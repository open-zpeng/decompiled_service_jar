package com.xiaopeng.xui.xuiaudio.xuiAudioEffect;

import android.content.Context;
import android.media.AudioSystem;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.os.SystemProperties;
import android.util.Log;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioEffect.AudioEffectData;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.List;

/* loaded from: classes2.dex */
public class xuiEffectPolicy {
    public static final int DYNAUDIO_DEFAULT_TYPE = 3;
    private static final String TAG = "xuiEffectPolicy";
    public static final int XSOUND_DEFAULT_TYPE = 1;
    public static final int XUI_DEFAULT_MODE = 3;
    public static final int XUI_DYNAUDIO_MODE = 3;
    public static final int XUI_DYNAUDIO_TYPE_DYNAMIC = 3;
    public static final int XUI_DYNAUDIO_TYPE_HUMAN = 4;
    public static final int XUI_DYNAUDIO_TYPE_REAL = 1;
    public static final int XUI_DYNAUDIO_TYPE_SOFT = 2;
    public static final int XUI_FORM2_TYPE_5DMOVIE = 4;
    public static final int XUI_FORM2_TYPE_AUTHENTIC = 0;
    public static final int XUI_FORM2_TYPE_CUSTOMER = 3;
    public static final int XUI_FORM2_TYPE_DYNAMIC = 1;
    public static final int XUI_FORM2_TYPE_MOVIE = 2;
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
    public static final int XUI_XSOUND_TYPE_CUSTOMIZE = 7;
    public static final int XUI_XSOUND_TYPE_DISCO = 5;
    public static final int XUI_XSOUND_TYPE_HUMAN = 2;
    public static final int XUI_XSOUND_TYPE_JAZZ = 3;
    public static final int XUI_XSOUND_TYPE_ROCK = 4;
    private static int lastMode;
    private static int lastSoundType;
    private static Context mContext;
    private static xuiEffectPolicy mInstance;
    private static List<AudioEffectData> mXpAudioEffectList;
    private static xuiAudioData mXuiAudioData;
    private static IXuiEffectInterface mXuiEffectAmp;
    private static xuiEffectToHal mXuiEffectHal;
    private static int maxModeIndex;
    private static boolean pioneerAmpOpen;
    private final boolean isAmpHigh = FeatureOption.hasFeature("AMP");
    private static int mAudioFeature = 0;
    private static final int AUDIO_EFFECT_FORM = FeatureOption.FO_AUDIO_EFFECT_FORM;
    public static final boolean XSOUND_AMP_SOP_PROTECT = SystemProperties.getBoolean("persist.audio.SOP.protect", false);
    public static int DEFAULT_SOUNDFIELD_X = SystemProperties.getInt("persist.xpaudio.soundfield.x", 50);
    public static int DEFAULT_SOUNDFIELD_Y = SystemProperties.getInt("persist.xpaudio.soundfield.y", 50);

    static {
        pioneerAmpOpen = FeatureOption.FO_AMP_TYPE == 1;
        lastSoundType = -1;
        lastMode = -1;
        maxModeIndex = 0;
    }

    private xuiEffectPolicy(Context context, xuiAudioPolicy instance) {
        mContext = context;
        mXuiAudioData = xuiAudioData.getInstance(context);
        if (AUDIO_EFFECT_FORM != 0) {
            mXpAudioEffectList = xuiAudioParser.getAudioEffectDataList();
        }
        mXuiEffectHal = xuiEffectToHal.getInstance(context);
        if (this.isAmpHigh && pioneerAmpOpen) {
            mXuiEffectAmp = xuiEffectToPioneerAmp.getInstance(context, instance);
        }
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
        AudioSystem.setParameters(this.isAmpHigh ? "vehicle_model=2" : "vehicle_model=1");
        if (SystemProperties.get("persist.audio.xpeffect.mode", "").equals("")) {
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
            return;
        }
        setXpAudioEffectMode(mXuiAudioData.getXpAudioEffectMode());
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

    public void audioServerDiedRestore() {
        lastSoundType = -1;
        initEffectPolicy();
    }

    public void igStatusChange(int status) {
        if (status == 1) {
            lastSoundType = -1;
            setSoundEffectMode(getSoundEffectMode());
            setDyn3dEffectLevel(getDyn3dEffectLevel());
            setSoundSpeedLinkLevel(getSoundSpeedLinkLevel());
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        xuiAudioData xuiaudiodata;
        Log.i(TAG, "setSoundField mode:" + mode + " " + xSound + " " + ySound);
        if (AUDIO_EFFECT_FORM == 2 && (xuiaudiodata = mXuiAudioData) != null) {
            xuiaudiodata.setSoundField(mode, xSound, ySound);
        }
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            xuieffecttohal.setSoundField(mode, xSound, ySound);
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        if (AUDIO_EFFECT_FORM == 2) {
            xuiAudioData xuiaudiodata = mXuiAudioData;
            if (xuiaudiodata != null) {
                return xuiaudiodata.getSoundField(mode);
            }
            return null;
        }
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            return xuieffecttohal.getSoundField(mode);
        }
        return null;
    }

    public int getSoundEffectMode() {
        xuiAudioData xuiaudiodata;
        int i = AUDIO_EFFECT_FORM;
        if (i == 0) {
            xuiEffectToHal xuieffecttohal = mXuiEffectHal;
            if (xuieffecttohal != null) {
                return xuieffecttohal.getSoundEffectMode();
            }
            return -1;
        } else if ((i == 1 || i == 2) && (xuiaudiodata = mXuiAudioData) != null) {
            return xuiaudiodata.getXpAudioEffectMode();
        } else {
            return -1;
        }
    }

    public void setSoundEffectMode(int mode) {
        Log.i(TAG, "setSoundEffectMode mode=" + mode);
        int i = AUDIO_EFFECT_FORM;
        if (i == 1 || i == 2) {
            IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
            if (iXuiEffectInterface != null) {
                iXuiEffectInterface.setSoundEffectMode(mode);
            }
            setXpAudioEffectMode(mode);
        } else if (i == 0) {
            setSoundEffectType(mode, getSoundEffectType(mode));
        }
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            xuieffecttohal.setSoundEffectMode(mode);
        }
        SoundField sf = getSoundField(mode);
        setSoundField(mode, sf.x, sf.y);
        setSoundEffectScene(mode, getSoundEffectScene(mode));
    }

    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType mode=" + mode + " type=" + type);
        int i = AUDIO_EFFECT_FORM;
        if (i == 0) {
            xuiEffectToHal xuieffecttohal = mXuiEffectHal;
            if (xuieffecttohal != null) {
                xuieffecttohal.setSoundEffectType(mode, type);
            }
        } else if (i == 1) {
            IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
            if (iXuiEffectInterface != null) {
                iXuiEffectInterface.setSoundEffectType(mode, type);
            }
            setXpAudioEffect(mode, type, false);
        } else if (i == 2) {
            IXuiEffectInterface iXuiEffectInterface2 = mXuiEffectAmp;
            if (iXuiEffectInterface2 != null) {
                iXuiEffectInterface2.setSoundEffectType(mode, type);
            }
            if (this.isAmpHigh) {
                mXuiAudioData.setXpAudioEffect(mode, type);
            } else {
                setXpAudioEffect(mode, type, false);
            }
        }
    }

    public int getSoundEffectType(int mode) {
        xuiAudioData xuiaudiodata;
        int i = AUDIO_EFFECT_FORM;
        if (i == 0) {
            xuiEffectToHal xuieffecttohal = mXuiEffectHal;
            if (xuieffecttohal != null) {
                return xuieffecttohal.getSoundEffectType(mode);
            }
            return -1;
        } else if ((i == 1 || i == 2) && (xuiaudiodata = mXuiAudioData) != null) {
            return xuiaudiodata.getXpAudioEffectType(mode);
        } else {
            return -1;
        }
    }

    public void setSoundEffectScene(int mode, int type) {
        xuiAudioData xuiaudiodata;
        Log.i(TAG, "setSoundEffectScene mode=" + mode + " type=" + type);
        if (AUDIO_EFFECT_FORM == 2 && (xuiaudiodata = mXuiAudioData) != null) {
            xuiaudiodata.setSoundEffectScene(mode, type);
        }
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            xuieffecttohal.setSoundEffectScene(mode, type);
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        if (AUDIO_EFFECT_FORM == 2) {
            xuiAudioData xuiaudiodata = mXuiAudioData;
            if (xuiaudiodata != null) {
                xuiaudiodata.getSoundEffectScene(mode);
                return -1;
            }
            return -1;
        }
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            return xuieffecttohal.getSoundEffectScene(mode);
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            xuieffecttohal.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            return xuieffecttohal.getSoundEffectParms(effectType, modeType);
        }
        return new SoundEffectParms(0, 0, 0);
    }

    public void setXpAudioEffectMode(int mode) {
        Log.i(TAG, "setXpAudioEffectMode mode:" + mode);
        if (mode < 1 || mode > 4) {
            Log.e(TAG, "setXpAudioEffectMode  mode:" + mode + " over limit!!!");
            return;
        }
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata == null) {
            Log.e(TAG, "setXpAudioEffectMode ERROR mXuiAudioData==null");
            return;
        }
        xuiaudiodata.setXpAudioEffectMode(mode);
        int paramType = mXuiAudioData.getXpAudioEffectType(mode);
        if (AUDIO_EFFECT_FORM == 2) {
            if (this.isAmpHigh) {
                mXuiAudioData.setXpAudioEffect(mode, paramType);
                return;
            } else {
                setXpAudioEffect(mode, paramType, false);
                return;
            }
        }
        setXpAudioEffect(mode, paramType, true);
    }

    public synchronized void setXpAudioEffect(int mode, int type, boolean force) {
        Log.i(TAG, "setXpAudioEffect mode:" + mode + " type:" + type);
        if (mXpAudioEffectList != null && mXuiAudioData != null) {
            Log.d(TAG, "setXpAudioEffect  paramType:" + type);
            if (type >= 0 && type <= maxModeIndex) {
                mXuiAudioData.setXpAudioEffect(mode, type);
                if (this.isAmpHigh && !XSOUND_AMP_SOP_PROTECT && mode == 1) {
                    type = 0;
                } else if (this.isAmpHigh && !XSOUND_AMP_SOP_PROTECT && mode == 3) {
                    type = 0;
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
                if (name.equals("Customize")) {
                    mXuiEffectHal.setXpCustomizeEffect();
                }
                return;
            }
            Log.e(TAG, "setXpAudioEffect param error!  type:" + type + "  maxModeIndex:" + maxModeIndex);
            return;
        }
        Log.e(TAG, "setXpAudioEffect mXpAudioEffectList or mXuiAudioData == null");
    }

    public void setAudioEffectParams(String name, int index, AudioEffectData.XpEffectModeParam param) {
        xuiEffectToHal xuieffecttohal = mXuiEffectHal;
        if (xuieffecttohal != null) {
            xuieffecttohal.setAudioEffectParams(name, index, param);
        }
    }

    public void setSoundSpeedLinkLevel(int level) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setSoundSpeedLinkLevel(level);
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setSoundSpeedLinkLevel(level);
        }
    }

    public int getSoundSpeedLinkLevel() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getSoundSpeedLinkLevel();
        }
        return 0;
    }

    public void setDyn3dEffectLevel(int level) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setDyn3dEffectLevel(level);
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setDyn3dEffectLevel(level);
        }
    }

    public int getDyn3dEffectLevel() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getDyn3dEffectLevel();
        }
        return 0;
    }

    public void setXpCustomizeEffect(int type, int value) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setXpCustomizeEffect(type, value);
        }
        if (!this.isAmpHigh) {
            mXuiEffectHal.setXpCustomizeEffect();
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setXpCustomizeEffect();
        }
    }

    public int getXpCustomizeEffect(int type) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getXpCustomizeEffect(type);
        }
        return 0;
    }

    public void flushXpCustomizeEffects(int[] values) {
        for (int i = 0; i < values.length; i++) {
            xuiAudioData xuiaudiodata = mXuiAudioData;
            if (xuiaudiodata != null) {
                xuiaudiodata.setXpCustomizeEffect(i, values[i]);
            }
        }
        if (!this.isAmpHigh) {
            mXuiEffectHal.setXpCustomizeEffect();
        }
        IXuiEffectInterface iXuiEffectInterface = mXuiEffectAmp;
        if (iXuiEffectInterface != null) {
            iXuiEffectInterface.setXpCustomizeEffect();
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
