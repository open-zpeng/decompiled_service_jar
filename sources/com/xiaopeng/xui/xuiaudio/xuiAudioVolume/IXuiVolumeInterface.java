package com.xiaopeng.xui.xuiaudio.xuiAudioVolume;
/* loaded from: classes.dex */
public interface IXuiVolumeInterface {
    void broadcastVolumeToICM(int i, int i2, int i3, boolean z);

    void sendMusicVolumeToAmp(int i);

    void setGroupVolume(int i, int i2, int i3);
}
