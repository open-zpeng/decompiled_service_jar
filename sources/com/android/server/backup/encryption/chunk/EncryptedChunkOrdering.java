package com.android.server.backup.encryption.chunk;

import java.util.Arrays;

/* loaded from: classes.dex */
public class EncryptedChunkOrdering {
    private final byte[] mEncryptedChunkOrdering;

    public static EncryptedChunkOrdering create(byte[] encryptedChunkOrdering) {
        return new EncryptedChunkOrdering(encryptedChunkOrdering);
    }

    public byte[] encryptedChunkOrdering() {
        return this.mEncryptedChunkOrdering;
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EncryptedChunkOrdering)) {
            return false;
        }
        EncryptedChunkOrdering encryptedChunkOrdering = (EncryptedChunkOrdering) o;
        return Arrays.equals(this.mEncryptedChunkOrdering, encryptedChunkOrdering.mEncryptedChunkOrdering);
    }

    public int hashCode() {
        return Arrays.hashCode(this.mEncryptedChunkOrdering);
    }

    private EncryptedChunkOrdering(byte[] encryptedChunkOrdering) {
        this.mEncryptedChunkOrdering = encryptedChunkOrdering;
    }
}
