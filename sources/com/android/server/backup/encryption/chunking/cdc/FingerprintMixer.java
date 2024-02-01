package com.android.server.backup.encryption.chunking.cdc;

import com.android.internal.util.Preconditions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import javax.crypto.SecretKey;

/* loaded from: classes.dex */
public class FingerprintMixer {
    private static final String DERIVED_KEY_NAME = "RabinFingerprint64Mixer";
    public static final int SALT_LENGTH_BYTES = 32;
    private final long mAddend;
    private final long mMultiplicand;

    public FingerprintMixer(SecretKey secretKey, byte[] salt) throws InvalidKeyException {
        Preconditions.checkArgument(salt.length == 32, "Requires a 256-bit salt.");
        byte[] keyBytes = secretKey.getEncoded();
        if (keyBytes == null) {
            throw new InvalidKeyException("SecretKey must support encoding for FingerprintMixer.");
        }
        byte[] derivedKey = Hkdf.hkdf(keyBytes, salt, DERIVED_KEY_NAME.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.wrap(derivedKey);
        this.mAddend = buffer.getLong();
        this.mMultiplicand = buffer.getLong() | 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long mix(long fingerprint) {
        return (this.mAddend + fingerprint) * this.mMultiplicand;
    }

    long getAddend() {
        return this.mAddend;
    }

    long getMultiplicand() {
        return this.mMultiplicand;
    }
}
