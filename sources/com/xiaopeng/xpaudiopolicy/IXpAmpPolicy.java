package com.xiaopeng.xpaudiopolicy;

import java.util.Map;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public interface IXpAmpPolicy {
    int getChannels(int i);

    int getMainDriverMode();

    int getVoicePosition();

    void initAreaConfig(Map<String, Integer> map);

    void setAlarmChannels(int i);

    void setAmpChanVolSource(int i, int i2, int i3, int i4);

    void setBtCallOn(boolean z);

    void setBtCallOnFlag(int i);

    void setBtHeadPhone(boolean z);

    void setCallingIn(boolean z);

    void setCallingOut(boolean z);

    void setDangerousTtsOn(boolean z);

    void setKaraokeOn(boolean z);

    void setMainDriver(boolean z);

    void setMainDriverMode(int i);

    void setSoundEffectMode(int i);

    void setSoundEffectScene(int i, int i2);

    void setSoundEffectType(int i, int i2);

    void setSoundField(int i, int i2, int i3);

    void setSpeechSurround(boolean z);

    void setStereoAlarm(boolean z);

    void setVoicePosition(int i, int i2);
}
