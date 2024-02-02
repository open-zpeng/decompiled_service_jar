package com.xiaopeng;

import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class XpEffectSet {
    private static final int EFFECTTOOLNUM = 3;
    public static final String EqualizerBand1 = "EqualizerBand1";
    public static final String EqualizerBand2 = "EqualizerBand2";
    public static final String EqualizerBand3 = "EqualizerBand3";
    public static final String EqualizerBand4 = "EqualizerBand4";
    public static final String EqualizerBand5 = "EqualizerBand5";
    public static final String EqualizerUsePreset = "EqualizerUsePreset";
    private static final String TAG = "XpEffectSet";
    public static final String bassBoostStrength = "bassBoostStrength";
    public static final String presetReverb = "presetReverb";
    private static Object xpAuioEffectLock = new Object();
    EffectToolList[] mEffectToolList = new EffectToolList[3];
    private XpEffectParam mXpEffectParam;

    /* loaded from: classes.dex */
    public class XpEffectParam {
        public int[] EqualizerBand;
        public int xEqualizerUsePreset;
        public int xbassBoostStrength;
        public int xpresetReverb;

        public XpEffectParam() {
        }
    }

    /* loaded from: classes.dex */
    public class EffectToolList {
        public long lastStartTime;
        public long lastStopTime;
        public BassBoost mBassBoost;
        public Equalizer mEqualizer;
        public PresetReverb mPresetReverb;
        public int sessinID;

        public EffectToolList() {
        }
    }

    public XpEffectSet() {
        parseXpSpeechEffect();
        initEffectToolList();
    }

    private String getXpSpeechEffectConfig() {
        try {
            File readname = new File("/data/xuiservice/xuispeecheffect.json");
            if (!readname.exists()) {
                readname = new File("/system/etc/xuispeecheffect.json");
                if (!readname.exists()) {
                    Log.e(TAG, "getXpEffectConfig  xuispeecheffect.json not exist!!");
                    return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                }
            }
            StringBuilder stringBuilder = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(new FileInputStream(readname));
            BufferedReader br = new BufferedReader(reader);
            while (true) {
                String line = br.readLine();
                if (line != null) {
                    stringBuilder.append(line);
                } else {
                    br.close();
                    reader.close();
                    return stringBuilder.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    private void initEffectToolList() {
        for (int i = 0; i < 3; i++) {
            this.mEffectToolList[i] = new EffectToolList();
            this.mEffectToolList[i].sessinID = -1;
        }
    }

    public void parseXpSpeechEffect() {
        if (this.mXpEffectParam != null) {
            Log.w(TAG, "parseXpSpeechEffect()  already parsed!!");
            return;
        }
        String xpAudioEffect = getXpSpeechEffectConfig();
        if (xpAudioEffect.equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
            Log.w(TAG, "parseXpSpeechEffect()  NO VALUE");
            return;
        }
        Log.i(TAG, "parseXpSpeechEffect()");
        this.mXpEffectParam = new XpEffectParam();
        this.mXpEffectParam.EqualizerBand = new int[5];
        try {
            JSONObject jObject = new JSONObject(xpAudioEffect);
            this.mXpEffectParam.EqualizerBand[0] = jObject.getInt(EqualizerBand1);
            this.mXpEffectParam.EqualizerBand[1] = jObject.getInt(EqualizerBand2);
            this.mXpEffectParam.EqualizerBand[2] = jObject.getInt(EqualizerBand3);
            this.mXpEffectParam.EqualizerBand[3] = jObject.getInt(EqualizerBand4);
            this.mXpEffectParam.EqualizerBand[4] = jObject.getInt(EqualizerBand5);
            this.mXpEffectParam.xEqualizerUsePreset = jObject.getInt(EqualizerUsePreset);
            this.mXpEffectParam.xbassBoostStrength = jObject.getInt(bassBoostStrength);
            this.mXpEffectParam.xpresetReverb = jObject.getInt(presetReverb);
            Log.i(TAG, " " + this.mXpEffectParam.EqualizerBand[0] + " " + this.mXpEffectParam.EqualizerBand[1] + " " + this.mXpEffectParam.EqualizerBand[2] + " " + this.mXpEffectParam.EqualizerBand[3] + " " + this.mXpEffectParam.EqualizerBand[4] + " " + this.mXpEffectParam.xEqualizerUsePreset + " " + this.mXpEffectParam.xbassBoostStrength + " " + this.mXpEffectParam.xpresetReverb);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "parseXpSpeechEffect() " + e);
        }
    }

    private void setSpeechEffect(int effectToolIndex) {
        if (this.mEffectToolList[effectToolIndex].mEqualizer == null || this.mEffectToolList[effectToolIndex].mBassBoost == null || this.mEffectToolList[effectToolIndex].mPresetReverb == null || this.mXpEffectParam == null) {
            Log.e(TAG, "setSpeechEffect  ERROR:" + this.mEffectToolList[effectToolIndex].mEqualizer + " " + this.mEffectToolList[effectToolIndex].mBassBoost + " " + this.mEffectToolList[effectToolIndex].mPresetReverb + " " + this.mXpEffectParam);
            return;
        }
        if (this.mXpEffectParam.xEqualizerUsePreset == -1) {
            for (short bandIdx = 0; bandIdx < 5; bandIdx = (short) (bandIdx + 1)) {
                this.mEffectToolList[effectToolIndex].mEqualizer.setBandLevel(bandIdx, (short) this.mXpEffectParam.EqualizerBand[bandIdx]);
            }
        } else {
            this.mEffectToolList[effectToolIndex].mEqualizer.usePreset((short) this.mXpEffectParam.xEqualizerUsePreset);
        }
        this.mEffectToolList[effectToolIndex].mBassBoost.setStrength((short) this.mXpEffectParam.xbassBoostStrength);
        this.mEffectToolList[effectToolIndex].mPresetReverb.setPreset((short) this.mXpEffectParam.xpresetReverb);
    }

    private int findEffectToolIndex(int audiosessionid) {
        for (int i = 0; i < 3; i++) {
            if (this.mEffectToolList[i] == null) {
                this.mEffectToolList[i] = new EffectToolList();
            }
            if (this.mEffectToolList[i].sessinID == audiosessionid) {
                Log.i(TAG, "findEffectToolIndex  audiosessionid:" + audiosessionid + " index:" + i);
                return i;
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int findEffectToolUsed() {
        int num = 0;
        for (int i = 0; i < 3; i++) {
            if (this.mEffectToolList[i] != null && this.mEffectToolList[i].sessinID != -1) {
                num++;
            }
        }
        Log.i(TAG, "findEffectToolUsed  " + num);
        return num;
    }

    private void disableEffect(final int effectToolIndex) {
        if (effectToolIndex == -1) {
            return;
        }
        this.mEffectToolList[effectToolIndex].lastStopTime = System.currentTimeMillis();
        new Thread(new Runnable() { // from class: com.xiaopeng.XpEffectSet.1
            @Override // java.lang.Runnable
            public void run() {
                long curtime;
                try {
                    Thread.sleep(50L);
                    curtime = System.currentTimeMillis();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (XpEffectSet.this.mEffectToolList[effectToolIndex].lastStartTime <= XpEffectSet.this.mEffectToolList[effectToolIndex].lastStopTime || curtime <= XpEffectSet.this.mEffectToolList[effectToolIndex].lastStartTime) {
                    synchronized (XpEffectSet.xpAuioEffectLock) {
                        Log.i(XpEffectSet.TAG, "disableEffect   effectToolIndex:" + effectToolIndex);
                        XpEffectSet.this.mEffectToolList[effectToolIndex].sessinID = -1;
                        if (XpEffectSet.this.mEffectToolList[effectToolIndex].mEqualizer != null) {
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mEqualizer.setEnabled(false);
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mEqualizer.release();
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mEqualizer = null;
                        }
                        if (XpEffectSet.this.mEffectToolList[effectToolIndex].mBassBoost != null) {
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mBassBoost.setEnabled(false);
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mBassBoost.release();
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mBassBoost = null;
                        }
                        if (XpEffectSet.this.mEffectToolList[effectToolIndex].mPresetReverb != null) {
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mPresetReverb.setEnabled(false);
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mPresetReverb.release();
                            XpEffectSet.this.mEffectToolList[effectToolIndex].mPresetReverb = null;
                        }
                        Thread.sleep(20L);
                        Log.i(XpEffectSet.TAG, "disableEffect   done");
                    }
                    XpEffectSet.this.findEffectToolUsed();
                    return;
                }
                Log.w(XpEffectSet.TAG, "disableEffect PATCH lastStartTime:" + XpEffectSet.this.mEffectToolList[effectToolIndex].lastStartTime + " lastStopTime:" + XpEffectSet.this.mEffectToolList[effectToolIndex].lastStartTime + " curtime:" + curtime);
            }
        }).start();
    }

    private void enableEffect(int effectToolIndex, int audiosessionid) {
        try {
            synchronized (xpAuioEffectLock) {
                if (effectToolIndex == -1) {
                    effectToolIndex = 0;
                    this.mEffectToolList[0].sessinID = audiosessionid;
                } else if (this.mEffectToolList[effectToolIndex].sessinID == audiosessionid) {
                    this.mEffectToolList[effectToolIndex].lastStartTime = System.currentTimeMillis();
                    Log.d(TAG, "enableEffect done same sessinID");
                    return;
                }
                boolean needsleep = false;
                this.mEffectToolList[effectToolIndex].lastStartTime = System.currentTimeMillis();
                if (this.mEffectToolList[effectToolIndex].mEqualizer != null) {
                    this.mEffectToolList[effectToolIndex].mEqualizer.release();
                    needsleep = true;
                }
                if (this.mEffectToolList[effectToolIndex].mBassBoost != null) {
                    this.mEffectToolList[effectToolIndex].mBassBoost.release();
                    needsleep = true;
                }
                if (this.mEffectToolList[effectToolIndex].mPresetReverb != null) {
                    this.mEffectToolList[effectToolIndex].mPresetReverb.release();
                    needsleep = true;
                }
                if (needsleep) {
                    Thread.sleep(20L);
                }
                this.mEffectToolList[effectToolIndex].mEqualizer = new Equalizer(0, audiosessionid);
                this.mEffectToolList[effectToolIndex].mBassBoost = new BassBoost(0, audiosessionid);
                this.mEffectToolList[effectToolIndex].mPresetReverb = new PresetReverb(0, audiosessionid);
                setSpeechEffect(effectToolIndex);
                this.mEffectToolList[effectToolIndex].mEqualizer.setEnabled(true);
                this.mEffectToolList[effectToolIndex].mBassBoost.setEnabled(true);
                this.mEffectToolList[effectToolIndex].mPresetReverb.setEnabled(true);
                Log.i(TAG, "enableEffect   done");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setEffect(boolean enable, int audiosessionid) {
        if (this.mXpEffectParam == null) {
            Log.w(TAG, "setEffect no mXpEffectParam");
            return;
        }
        Log.i(TAG, "setEffect " + enable + "  audiosessionid:" + audiosessionid);
        int effectToolIndex = findEffectToolIndex(audiosessionid);
        if (enable) {
            enableEffect(effectToolIndex, audiosessionid);
        } else {
            disableEffect(effectToolIndex);
        }
    }
}
