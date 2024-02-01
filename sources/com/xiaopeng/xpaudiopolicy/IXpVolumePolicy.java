package com.xiaopeng.xpaudiopolicy;

/* loaded from: classes2.dex */
public interface IXpVolumePolicy {
    boolean getStreamMute(int i);

    int getStreamVolume(int i);

    void saveStreamMute(int i, boolean z);

    void saveStreamVolume(int i, int i2);

    void saveStreamVolume(int i, int i2, boolean z);
}
