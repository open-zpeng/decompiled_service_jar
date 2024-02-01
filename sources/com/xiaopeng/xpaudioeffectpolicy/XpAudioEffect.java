package com.xiaopeng.xpaudioeffectpolicy;

import android.media.AudioSystem;
import android.os.SystemProperties;
import android.util.Log;
import com.xiaopeng.xpaudioeffectpolicy.XpAudioEffectParser;
import java.util.List;

/* loaded from: classes2.dex */
public class XpAudioEffect {
    public static final int DYNAUDIO_DEFAULT_TYPE = 3;
    public static final String PROP_DYNAUDIO_TYPE = "persist.audio.xpeffect.dynaudio.type";
    public static final String PROP_XSOUND_TYPE = "persist.audio.xpeffect.xsound.type";
    public static final String PROP_XUIAUDIO_MODE = "persist.audio.xpeffect.mode";
    private static final String TAG = "XpAudioEffect";
    public static final int XSOUND_DEFAULT_TYPE = 1;
    public static final int XUI_DEFAULT_MODE = 3;
    public static final int XUI_DYNAUDIO_MODE = 3;
    public static final int XUI_DYNAUDIO_TYPE_DYNAMIC = 3;
    public static final int XUI_DYNAUDIO_TYPE_HUMAN = 4;
    public static final int XUI_DYNAUDIO_TYPE_REAL = 1;
    public static final int XUI_DYNAUDIO_TYPE_SOFT = 2;
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
    public static final int XUI_XSOUND_TYPE_DISCO = 5;
    public static final int XUI_XSOUND_TYPE_HUMAN = 2;
    public static final int XUI_XSOUND_TYPE_JAZZ = 3;
    public static final int XUI_XSOUND_TYPE_ROCK = 4;
    private static XpAudioEffectParser mParser;
    private static List<XpAudioEffectParser.XpAudioEffectParam> mXpAudioEffectList;
    private int isAmpHigh;
    public static final boolean XSOUND_AMP_SOP_PROTECT = SystemProperties.getBoolean("persist.audio.SOP.protect", false);
    private static int lastSoundType = -1;
    private static int lastMode = -1;
    private static int maxModeIndex = 0;
    private static XpAudioEffect instance = new XpAudioEffect();

    public XpAudioEffect() {
        this.isAmpHigh = 0;
        this.isAmpHigh = SystemProperties.getInt("persist.sys.xiaopeng.AMP", 0);
    }

    public static XpAudioEffect getInstance() {
        return instance;
    }

    public static void parseAndSetAudioEffect() {
        Log.i(TAG, "parseAndSetAudioEffect()");
        mParser = new XpAudioEffectParser();
        XpAudioEffectParser.XpAudioEffectListArray mArray = mParser.parseXpAudioEffect();
        if (mArray != null && mArray.mXpAudioEffectList != null) {
            mXpAudioEffectList = mArray.mXpAudioEffectList;
            maxModeIndex = getMaxIndex();
        }
    }

    public static List<XpAudioEffectParser.XpAudioEffectParam> getXpAudioEffectParam() {
        Log.d(TAG, "getXpAudioEffectParam()");
        return mXpAudioEffectList;
    }

    public static int getMaxIndex() {
        if (mXpAudioEffectList == null || mParser == null) {
            Log.e(TAG, "getMaxIndex mXpAudioEffectList or mParser==null");
            return 0;
        }
        int maxIndex = 0;
        for (int i = 0; i < mXpAudioEffectList.size(); i++) {
            XpAudioEffectParser.XpAudioEffectParam tXpAudioEffectParam = mXpAudioEffectList.get(i);
            if (tXpAudioEffectParam != null && tXpAudioEffectParam.ModeIndex > maxIndex) {
                maxIndex = tXpAudioEffectParam.ModeIndex;
            }
        }
        return maxIndex;
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

    public void setXpAudioEffectMode(int mode) {
        Log.i(TAG, "setXpAudioEffectMode mode:" + mode);
        if (mode < 1 || mode > 4) {
            Log.e(TAG, "setXpAudioEffectMode  mode:" + mode + " over limit!!!");
            return;
        }
        SystemProperties.set("persist.audio.xpeffect.mode", Integer.toString(mode));
        int paramType = getXpAudioEffectType(mode);
        setXpAudioEffect(mode, paramType, true);
    }

    public synchronized void setXpAudioEffect(int mode, int type, boolean force) {
        Log.i(TAG, "setXpAudioEffect mode:" + mode + " type:" + type);
        if (mXpAudioEffectList != null && mParser != null) {
            if (type >= 0 && type <= maxModeIndex) {
                if (mode != 1) {
                    if (mode == 3) {
                        SystemProperties.set("persist.audio.xpeffect.dynaudio.type", Integer.toString(type));
                    }
                } else {
                    SystemProperties.set("persist.audio.xpeffect.xsound.type", Integer.toString(type));
                }
                if (this.isAmpHigh == 1 && !XSOUND_AMP_SOP_PROTECT && mode == 1) {
                    type = 0;
                } else if (this.isAmpHigh == 1 && !XSOUND_AMP_SOP_PROTECT && mode == 3) {
                    type = 0;
                }
                if (type == lastSoundType && lastMode == mode) {
                    Log.d(TAG, "setXpAudioEffect  SAME TYPE type:" + type);
                    return;
                }
                XpAudioEffectParser.XpAudioEffectParam tXpAudioEffectParam = mParser.getXpAudioEffectParam(mXpAudioEffectList, type);
                if (tXpAudioEffectParam == null) {
                    Log.e(TAG, "setXpAudioEffect tXpAudioEffectParam==null");
                    return;
                }
                lastMode = mode;
                lastSoundType = type;
                String name = tXpAudioEffectParam.ModeName;
                int index = tXpAudioEffectParam.ModeIndex;
                XpAudioEffectParser.XpEffectModeParam[] mParam = tXpAudioEffectParam.ModeParam;
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
        Log.e(TAG, "setXpAudioEffect mXpAudioEffectList or mParser==null");
    }

    private static void setAudioEffectParams(String name, int index, XpAudioEffectParser.XpEffectModeParam param) {
        AudioSystem.setParameters("method=setSelfDefEffectParam;modeName=" + name + ";modeIndex=" + index + ";EQLevel=" + param.EQLevel + ";Freq1=" + param.Freq1 + ";Gain1=" + param.Gain1 + ";Q1=" + param.Q1 + ";Freq2=" + param.Freq2 + ";Gain2=" + param.Gain2 + ";Q2=" + param.Q2 + ";Freq3=" + param.Freq3 + ";Gain3=" + param.Gain3 + ";Q3=" + param.Q3 + ";Freq4=" + param.Freq4 + ";Gain4=" + param.Gain4 + ";Q4=" + param.Q4 + ";Freq5=" + param.Freq5 + ";Gain5=" + param.Gain5 + ";Q5=" + param.Q5 + ";Freq6=" + param.Freq6 + ";Gain6=" + param.Gain6 + ";Q6=" + param.Q6 + ";Freq7=" + param.Freq7 + ";Gain7=" + param.Gain7 + ";Q7=" + param.Q7 + ";Freq8=" + param.Freq8 + ";Gain8=" + param.Gain8 + ";Q8=" + param.Q8 + ";Freq9=" + param.Freq9 + ";Gain9=" + param.Gain9 + ";Q9=" + param.Q9 + ";");
    }
}
