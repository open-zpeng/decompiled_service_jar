package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

/* loaded from: classes2.dex */
public interface IXuiChannelInterface {
    void changeChannel();

    void forceChangeToAmpChannel(int i, int i2, int i3, boolean z);

    boolean getSoundPositionEnable();

    int getVoicePosition();

    void igStatusChange(int i);

    void setBtCallOnFlag(int i);

    void setBtHeadPhone(boolean z);

    void setMainDriverMode(int i);

    void setSoundPositionEnable(boolean z);

    void setStreamPosition(int i, String str, int i2, int i3);

    void setVoicePosition(int i);

    void triggerChannelChange(int i, int i2, int i3);
}
