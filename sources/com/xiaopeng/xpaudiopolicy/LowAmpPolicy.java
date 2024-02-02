package com.xiaopeng.xpaudiopolicy;

import java.util.Map;
/* loaded from: classes.dex */
class LowAmpPolicy implements IXpAmpPolicy {
    private static final String TAG = "LowAmpPolicy";
    private boolean mStereoAlarm = false;
    private boolean mSpeechSurround = false;
    private boolean mMainDriver = false;
    private int XP_AMP_CHANNEL_ALL = 15;
    private int XP_AMP_CHANNEL_FRONT = 3;

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void initAreaConfig(Map<String, Integer> areaMap) {
        if (areaMap == null) {
            return;
        }
        this.XP_AMP_CHANNEL_ALL = areaMap.get("ALL_AREA").intValue();
        this.XP_AMP_CHANNEL_FRONT = areaMap.get("FRONT").intValue();
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setStereoAlarm(boolean enable) {
        this.mStereoAlarm = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSpeechSurround(boolean enable) {
        this.mSpeechSurround = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setMainDriver(boolean enable) {
        this.mMainDriver = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtHeadPhone(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setAlarmChannels(int bits) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setVoicePosition(int position, int flag) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getVoicePosition() {
        return 0;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setCallingOut(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setCallingIn(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setKaraokeOn(boolean on) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setDangerousTtsOn(boolean on) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getChannels(int soundType) {
        switch (soundType) {
            case 1:
                int channelBits = this.XP_AMP_CHANNEL_FRONT;
                return channelBits;
            case 2:
                int channelBits2 = this.XP_AMP_CHANNEL_ALL;
                return channelBits2;
            case 3:
                int channelBits3 = this.XP_AMP_CHANNEL_ALL;
                return channelBits3;
            case 4:
                int channelBits4 = this.XP_AMP_CHANNEL_FRONT;
                return channelBits4;
            case 5:
                int channelBits5 = this.XP_AMP_CHANNEL_FRONT;
                return channelBits5;
            case 6:
                int channelBits6 = this.XP_AMP_CHANNEL_FRONT;
                return channelBits6;
            case 7:
                int channelBits7 = this.XP_AMP_CHANNEL_FRONT;
                return channelBits7;
            default:
                return 0;
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setAmpChanVolSource(int channelbit, int volume, int soundSource, int activebit) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectMode(int mode) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectType(int mode, int type) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectScene(int mode, int scene) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundField(int mode, int xSound, int ySound) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtCallOn(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setMainDriverMode(int mode) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getMainDriverMode() {
        return 0;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtCallOnFlag(int flag) {
    }
}
