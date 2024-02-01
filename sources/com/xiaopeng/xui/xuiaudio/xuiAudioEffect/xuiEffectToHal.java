package com.xiaopeng.xui.xuiaudio.xuiAudioEffect;

import android.content.Context;
import android.media.AudioSystem;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.xuiAudioEffect.AudioEffectData;
/* loaded from: classes.dex */
public class xuiEffectToHal {
    private static final String TAG = "xuiEffectToHal";
    private static int mAudioFeature = 0;
    private static Context mContext;
    private static xuiEffectToHal mInstance;
    private static xuiAudioData mXuiAudioData;

    private xuiEffectToHal(Context context) {
        mContext = context;
        mXuiAudioData = xuiAudioData.getInstance(context);
    }

    public static xuiEffectToHal getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new xuiEffectToHal(context);
        }
        return mInstance;
    }

    public void igStatusChange(int igstat) {
    }

    public void setSoundEffectMode(int mode) {
    }

    public void setSoundEffectType(int mode, int type) {
        int ret = AudioSystem.setParameters("method=setSoundEffectType;mode=" + mode + ";type=" + type + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectType ERROR");
        }
    }

    public void setSoundEffectScene(int mode, int scene) {
        Log.i(TAG, "setSoundEffectScene mode=" + mode + " scene=" + scene);
        int ret = AudioSystem.setParameters("method=setSoundEffectScene;mode=" + mode + ";scene=" + scene + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectScene ERROR");
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        Log.i(TAG, "setSoundField x=" + xSound + ", y=" + ySound + ", mode=" + mode);
        int ret = AudioSystem.setParameters("method=setSoundField;mode=" + mode + ";x=" + xSound + ";y=" + ySound + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundField ERROR");
        }
    }

    public int getSoundEffectMode() {
        String tStr = AudioSystem.getParameters("method=getSoundEffectMode;");
        Log.d(TAG, "getSoundEffectMode " + tStr);
        String[] parms = tStr.split(";");
        for (String parm : parms) {
            if (parm.startsWith("mode=")) {
                int mode = Integer.parseInt(parm.substring(5));
                return mode;
            }
        }
        return -1;
    }

    public int getSoundEffectType(int mode) {
        String tStr = AudioSystem.getParameters("method=getSoundEffectType;mode=" + mode + ";");
        Log.d(TAG, "getSoundEffectType mode=" + mode + " " + tStr);
        String[] parms = tStr.split(";");
        for (String parm : parms) {
            if (parm.startsWith("type=")) {
                int type = Integer.parseInt(parm.substring(5));
                return type;
            }
        }
        return -1;
    }

    public int getSoundEffectScene(int mode) {
        String tStr = AudioSystem.getParameters("method=getSoundEffectScene;mode=" + mode + ";");
        Log.d(TAG, "getSoundEffectScene mode=" + mode + " " + tStr);
        String[] parms = tStr.split(";");
        for (String parm : parms) {
            if (parm.startsWith("scene=")) {
                int scene = Integer.parseInt(parm.substring(6));
                return scene;
            }
        }
        return -1;
    }

    public SoundField getSoundField(int mode) {
        int x = 0;
        int y = 0;
        String str = AudioSystem.getParameters("method=getSoundField;mode=" + mode + ";");
        Log.d(TAG, "getSoundField mode=" + mode + " " + str);
        String[] parms = str.split(";");
        for (String parm : parms) {
            if (parm.startsWith("x=")) {
                x = Integer.parseInt(parm.substring(2));
            } else if (parm.startsWith("y=")) {
                y = Integer.parseInt(parm.substring(2));
            }
        }
        return new SoundField(x, y);
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        Log.i(TAG, "setSoundEffectParms effectType=" + effectType + ", native=" + nativeValue + ", soft=" + softValue + ", innervation=" + innervationValue);
        int ret = AudioSystem.setParameters("method=setSoundEffectParms;effect=" + effectType + ";native=" + nativeValue + ";soft=" + softValue + ";innervation=" + innervationValue);
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectParms ERROR");
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        int nativeValue = 0;
        int softValue = 0;
        int innervationValue = 0;
        String str = AudioSystem.getParameters("method=getSoundEffectParms;effect=" + effectType + ";mode=" + modeType);
        StringBuilder sb = new StringBuilder();
        sb.append("getSoundEffectParms ");
        sb.append(str);
        Log.d(TAG, sb.toString());
        String[] parms = str.split(";");
        for (String parm : parms) {
            if (parm.startsWith("native=")) {
                nativeValue = Integer.parseInt(parm.substring(7));
            } else if (parm.startsWith("soft=")) {
                softValue = Integer.parseInt(parm.substring(5));
            } else if (parm.startsWith("innervation=")) {
                innervationValue = Integer.parseInt(parm.substring(12));
            }
        }
        return new SoundEffectParms(nativeValue, softValue, innervationValue);
    }

    public void setAudioEffectParams(String name, int index, AudioEffectData.XpEffectModeParam param) {
        AudioSystem.setParameters("method=setSelfDefEffectParam;modeName=" + name + ";modeIndex=" + index + ";EQLevel=" + param.EQLevel + ";Freq1=" + param.Freq1 + ";Gain1=" + param.Gain1 + ";Q1=" + param.Q1 + ";Freq2=" + param.Freq2 + ";Gain2=" + param.Gain2 + ";Q2=" + param.Q2 + ";Freq3=" + param.Freq3 + ";Gain3=" + param.Gain3 + ";Q3=" + param.Q3 + ";Freq4=" + param.Freq4 + ";Gain4=" + param.Gain4 + ";Q4=" + param.Q4 + ";Freq5=" + param.Freq5 + ";Gain5=" + param.Gain5 + ";Q5=" + param.Q5 + ";Freq6=" + param.Freq6 + ";Gain6=" + param.Gain6 + ";Q6=" + param.Q6 + ";Freq7=" + param.Freq7 + ";Gain7=" + param.Gain7 + ";Q7=" + param.Q7 + ";Freq8=" + param.Freq8 + ";Gain8=" + param.Gain8 + ";Q8=" + param.Q8 + ";Freq9=" + param.Freq9 + ";Gain9=" + param.Gain9 + ";Q9=" + param.Q9 + ";");
    }

    public void setXpCustomizeEffect() {
        try {
            int[] data = new int[10];
            for (int i = 0; i < 10; i++) {
                data[i] = mXuiAudioData.getXpCustomizeEffect(i);
            }
            AudioSystem.setParameters("method=setXpCustomizeEffect;value1=" + data[0] + ";value2=" + data[1] + ";value3=" + data[2] + ";value4=" + data[3] + ";value5=" + data[4] + ";value6=" + data[5] + ";value7=" + data[6] + ";value8=" + data[7] + ";value9=" + data[8] + ";value10=" + data[9] + ";");
        } catch (Exception e) {
            Log.e(TAG, "setXpCustomizeEffect ERROR:" + e);
        }
    }
}
