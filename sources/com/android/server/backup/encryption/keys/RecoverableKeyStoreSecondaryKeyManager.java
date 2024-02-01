package com.android.server.backup.encryption.keys;

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.security.keystore.recovery.RecoveryController;
import android.util.ByteStringUtils;
import com.android.internal.annotations.VisibleForTesting;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.SecretKey;

/* loaded from: classes.dex */
public class RecoverableKeyStoreSecondaryKeyManager {
    private static final String BACKUP_KEY_ALIAS_PREFIX = "com.android.server.backup/recoverablekeystore/";
    private static final int BACKUP_KEY_SUFFIX_LENGTH_BITS = 128;
    private static final int BITS_PER_BYTE = 8;
    private final RecoveryController mRecoveryController;
    private final SecureRandom mSecureRandom;

    /* loaded from: classes.dex */
    public interface RecoverableKeyStoreSecondaryKeyManagerProvider {
        RecoverableKeyStoreSecondaryKeyManager get();
    }

    public static RecoverableKeyStoreSecondaryKeyManager getInstance(Context context) {
        return new RecoverableKeyStoreSecondaryKeyManager(RecoveryController.getInstance(context), new SecureRandom());
    }

    @VisibleForTesting
    public RecoverableKeyStoreSecondaryKeyManager(RecoveryController recoveryController, SecureRandom secureRandom) {
        this.mRecoveryController = recoveryController;
        this.mSecureRandom = secureRandom;
    }

    public RecoverableKeyStoreSecondaryKey generate() throws InternalRecoveryServiceException, LockScreenRequiredException, UnrecoverableKeyException {
        String alias = generateId();
        this.mRecoveryController.generateKey(alias);
        SecretKey key = (SecretKey) this.mRecoveryController.getKey(alias);
        if (key == null) {
            throw new InternalRecoveryServiceException(String.format("Generated key %s but could not get it back immediately afterwards.", alias));
        }
        return new RecoverableKeyStoreSecondaryKey(alias, key);
    }

    public void remove(String alias) throws InternalRecoveryServiceException {
        this.mRecoveryController.removeKey(alias);
    }

    public Optional<RecoverableKeyStoreSecondaryKey> get(final String alias) throws InternalRecoveryServiceException, UnrecoverableKeyException {
        SecretKey secretKey = (SecretKey) this.mRecoveryController.getKey(alias);
        return Optional.ofNullable(secretKey).map(new Function() { // from class: com.android.server.backup.encryption.keys.-$$Lambda$RecoverableKeyStoreSecondaryKeyManager$e3XnfsZLX7gDR6_HV8RXEgR851s
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return RecoverableKeyStoreSecondaryKeyManager.lambda$get$0(alias, (SecretKey) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ RecoverableKeyStoreSecondaryKey lambda$get$0(String alias, SecretKey key) {
        return new RecoverableKeyStoreSecondaryKey(alias, key);
    }

    private String generateId() {
        byte[] id = new byte[16];
        this.mSecureRandom.nextBytes(id);
        return BACKUP_KEY_ALIAS_PREFIX + ByteStringUtils.toHexString(id);
    }
}
