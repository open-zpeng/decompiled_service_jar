package com.android.server;
/* loaded from: classes.dex */
public interface PersistentDataBlockManagerInternal {
    void forceOemUnlockEnabled(boolean z);

    byte[] getFrpCredentialHandle();

    void setFrpCredentialHandle(byte[] bArr);
}
