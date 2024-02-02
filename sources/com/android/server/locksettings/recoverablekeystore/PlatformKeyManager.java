package com.android.server.locksettings.recoverablekeystore;

import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyProtection;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.slice.SliceClientPermissions;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Locale;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
/* loaded from: classes.dex */
public class PlatformKeyManager {
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String DECRYPT_KEY_ALIAS_SUFFIX = "decrypt";
    private static final String ENCRYPT_KEY_ALIAS_SUFFIX = "encrypt";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_ALIAS_PREFIX = "com.android.server.locksettings.recoverablekeystore/platform/";
    private static final int KEY_SIZE_BITS = 256;
    private static final String TAG = "PlatformKeyManager";
    private static final int USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS = 15;
    private final Context mContext;
    private final RecoverableKeyStoreDb mDatabase;
    private final KeyStoreProxy mKeyStore;

    public static PlatformKeyManager getInstance(Context context, RecoverableKeyStoreDb database) throws KeyStoreException, NoSuchAlgorithmException {
        return new PlatformKeyManager(context.getApplicationContext(), new KeyStoreProxyImpl(getAndLoadAndroidKeyStore()), database);
    }

    @VisibleForTesting
    PlatformKeyManager(Context context, KeyStoreProxy keyStore, RecoverableKeyStoreDb database) {
        this.mKeyStore = keyStore;
        this.mContext = context;
        this.mDatabase = database;
    }

    public int getGenerationId(int userId) {
        return this.mDatabase.getPlatformKeyGenerationId(userId);
    }

    public boolean isAvailable(int userId) {
        return ((KeyguardManager) this.mContext.getSystemService(KeyguardManager.class)).isDeviceSecure(userId);
    }

    public void invalidatePlatformKey(int userId, int generationId) {
        if (generationId != -1) {
            try {
                this.mKeyStore.deleteEntry(getEncryptAlias(userId, generationId));
                this.mKeyStore.deleteEntry(getDecryptAlias(userId, generationId));
            } catch (KeyStoreException e) {
            }
        }
    }

    @VisibleForTesting
    void regenerate(int userId) throws NoSuchAlgorithmException, KeyStoreException, InsecureUserException, IOException {
        int nextId;
        if (!isAvailable(userId)) {
            throw new InsecureUserException(String.format(Locale.US, "%d does not have a lock screen set.", Integer.valueOf(userId)));
        }
        int generationId = getGenerationId(userId);
        if (generationId == -1) {
            nextId = 1;
        } else {
            invalidatePlatformKey(userId, generationId);
            nextId = generationId + 1;
        }
        generateAndLoadKey(userId, nextId);
    }

    public PlatformEncryptionKey getEncryptKey(int userId) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, InsecureUserException, IOException {
        init(userId);
        try {
            getDecryptKeyInternal(userId);
            return getEncryptKeyInternal(userId);
        } catch (UnrecoverableKeyException e) {
            Log.i(TAG, String.format(Locale.US, "Regenerating permanently invalid Platform key for user %d.", Integer.valueOf(userId)));
            regenerate(userId);
            return getEncryptKeyInternal(userId);
        }
    }

    private PlatformEncryptionKey getEncryptKeyInternal(int userId) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, InsecureUserException {
        int generationId = getGenerationId(userId);
        String alias = getEncryptAlias(userId, generationId);
        if (!isKeyLoaded(userId, generationId)) {
            throw new UnrecoverableKeyException("KeyStore doesn't contain key " + alias);
        }
        AndroidKeyStoreSecretKey key = this.mKeyStore.getKey(alias, null);
        return new PlatformEncryptionKey(generationId, key);
    }

    public PlatformDecryptionKey getDecryptKey(int userId) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, InsecureUserException, IOException {
        init(userId);
        try {
            return getDecryptKeyInternal(userId);
        } catch (UnrecoverableKeyException e) {
            Log.i(TAG, String.format(Locale.US, "Regenerating permanently invalid Platform key for user %d.", Integer.valueOf(userId)));
            regenerate(userId);
            return getDecryptKeyInternal(userId);
        }
    }

    private PlatformDecryptionKey getDecryptKeyInternal(int userId) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, InsecureUserException {
        int generationId = getGenerationId(userId);
        String alias = getDecryptAlias(userId, generationId);
        if (!isKeyLoaded(userId, generationId)) {
            throw new UnrecoverableKeyException("KeyStore doesn't contain key " + alias);
        }
        AndroidKeyStoreSecretKey key = this.mKeyStore.getKey(alias, null);
        return new PlatformDecryptionKey(generationId, key);
    }

    void init(int userId) throws KeyStoreException, NoSuchAlgorithmException, InsecureUserException, IOException {
        int generationId;
        if (!isAvailable(userId)) {
            throw new InsecureUserException(String.format(Locale.US, "%d does not have a lock screen set.", Integer.valueOf(userId)));
        }
        int generationId2 = getGenerationId(userId);
        if (isKeyLoaded(userId, generationId2)) {
            Log.i(TAG, String.format(Locale.US, "Platform key generation %d exists already.", Integer.valueOf(generationId2)));
            return;
        }
        if (generationId2 == -1) {
            Log.i(TAG, "Generating initial platform key generation ID.");
            generationId = 1;
        } else {
            Log.w(TAG, String.format(Locale.US, "Platform generation ID was %d but no entry was present in AndroidKeyStore. Generating fresh key.", Integer.valueOf(generationId2)));
            generationId = generationId2 + 1;
        }
        generateAndLoadKey(userId, generationId);
    }

    private String getEncryptAlias(int userId, int generationId) {
        return KEY_ALIAS_PREFIX + userId + SliceClientPermissions.SliceAuthority.DELIMITER + generationId + SliceClientPermissions.SliceAuthority.DELIMITER + ENCRYPT_KEY_ALIAS_SUFFIX;
    }

    private String getDecryptAlias(int userId, int generationId) {
        return KEY_ALIAS_PREFIX + userId + SliceClientPermissions.SliceAuthority.DELIMITER + generationId + SliceClientPermissions.SliceAuthority.DELIMITER + DECRYPT_KEY_ALIAS_SUFFIX;
    }

    private void setGenerationId(int userId, int generationId) throws IOException {
        long updatedRows = this.mDatabase.setPlatformKeyGenerationId(userId, generationId);
        if (updatedRows < 0) {
            throw new IOException("Failed to set the platform key in the local DB.");
        }
    }

    private boolean isKeyLoaded(int userId, int generationId) throws KeyStoreException {
        return this.mKeyStore.containsAlias(getEncryptAlias(userId, generationId)) && this.mKeyStore.containsAlias(getDecryptAlias(userId, generationId));
    }

    private void generateAndLoadKey(int userId, int generationId) throws NoSuchAlgorithmException, KeyStoreException, IOException {
        String encryptAlias = getEncryptAlias(userId, generationId);
        String decryptAlias = getDecryptAlias(userId, generationId);
        SecretKey secretKey = generateAesKey();
        this.mKeyStore.setEntry(decryptAlias, new KeyStore.SecretKeyEntry(secretKey), new KeyProtection.Builder(2).setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(15).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setBoundToSpecificSecureUserId(userId).build());
        this.mKeyStore.setEntry(encryptAlias, new KeyStore.SecretKeyEntry(secretKey), new KeyProtection.Builder(1).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
        setGenerationId(userId, generationId);
    }

    private static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(256);
        return keyGenerator.generateKey();
    }

    private static KeyStore getAndLoadAndroidKeyStore() throws KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        try {
            keyStore.load(null);
            return keyStore;
        } catch (IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new KeyStoreException("Unable to load keystore.", e);
        }
    }
}
