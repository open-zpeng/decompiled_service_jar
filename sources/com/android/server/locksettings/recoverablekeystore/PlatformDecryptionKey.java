package com.android.server.locksettings.recoverablekeystore;

import android.security.keystore.AndroidKeyStoreSecretKey;

/* loaded from: classes.dex */
public class PlatformDecryptionKey {
    private final int mGenerationId;
    private final AndroidKeyStoreSecretKey mKey;

    public PlatformDecryptionKey(int generationId, AndroidKeyStoreSecretKey key) {
        this.mGenerationId = generationId;
        this.mKey = key;
    }

    public int getGenerationId() {
        return this.mGenerationId;
    }

    public AndroidKeyStoreSecretKey getKey() {
        return this.mKey;
    }
}
