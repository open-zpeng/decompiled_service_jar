package com.xiaopeng.xui.xuiaudio.xuiAudioVolume;

/* loaded from: classes2.dex */
public interface IXuiVolumeInterface {
    void broadcastVolumeToICM(int i, int i2, int i3, boolean z);

    void sendAvasVolumeToMcu(int i);

    void sendMusicVolumeToAmp(int i);

    void setGroupVolume(int i, int i2, int i3);
}
