package com.android.server;

/* loaded from: classes.dex */
public interface PersistentDataBlockManagerInternal {
    void clearTestHarnessModeData();

    void forceOemUnlockEnabled(boolean z);

    byte[] getFrpCredentialHandle();

    byte[] getTestHarnessModeData();

    void setFrpCredentialHandle(byte[] bArr);

    void setTestHarnessModeData(byte[] bArr);
}
