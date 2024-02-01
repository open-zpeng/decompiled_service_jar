package com.android.server.locksettings.recoverablekeystore.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;
import com.android.server.locksettings.recoverablekeystore.TestOnlyInsecureCertificateHelper;
import com.android.server.locksettings.recoverablekeystore.WrappedKey;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.IntConsumer;
/* loaded from: classes.dex */
public class RecoverableKeyStoreDb {
    private static final String CERT_PATH_ENCODING = "PkiPath";
    private static final int IDLE_TIMEOUT_SECONDS = 30;
    private static final int LAST_SYNCED_AT_UNSYNCED = -1;
    private static final String TAG = "RecoverableKeyStoreDb";
    private final RecoverableKeyStoreDbHelper mKeyStoreDbHelper;
    private final TestOnlyInsecureCertificateHelper mTestOnlyInsecureCertificateHelper = new TestOnlyInsecureCertificateHelper();

    public static RecoverableKeyStoreDb newInstance(Context context) {
        RecoverableKeyStoreDbHelper helper = new RecoverableKeyStoreDbHelper(context);
        helper.setWriteAheadLoggingEnabled(true);
        helper.setIdleConnectionTimeout(30L);
        return new RecoverableKeyStoreDb(helper);
    }

    private RecoverableKeyStoreDb(RecoverableKeyStoreDbHelper keyStoreDbHelper) {
        this.mKeyStoreDbHelper = keyStoreDbHelper;
    }

    public long insertKey(int userId, int uid, String alias, WrappedKey wrappedKey) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("user_id", Integer.valueOf(userId));
        values.put(WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.valueOf(uid));
        values.put("alias", alias);
        values.put("nonce", wrappedKey.getNonce());
        values.put("wrapped_key", wrappedKey.getKeyMaterial());
        values.put("last_synced_at", (Integer) (-1));
        values.put("platform_key_generation_id", Integer.valueOf(wrappedKey.getPlatformKeyGenerationId()));
        values.put("recovery_status", Integer.valueOf(wrappedKey.getRecoveryStatus()));
        return db.replace("keys", null, values);
    }

    public WrappedKey getKey(int uid, String alias) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "nonce", "wrapped_key", "platform_key_generation_id", "recovery_status"};
        String[] selectionArguments = {Integer.toString(uid), alias};
        Cursor cursor = db.query("keys", projection, "uid = ? AND alias = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d WrappedKey entries found for uid=%d alias='%s'. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(uid), alias));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                byte[] nonce = cursor.getBlob(cursor.getColumnIndexOrThrow("nonce"));
                byte[] keyMaterial = cursor.getBlob(cursor.getColumnIndexOrThrow("wrapped_key"));
                int generationId = cursor.getInt(cursor.getColumnIndexOrThrow("platform_key_generation_id"));
                int recoveryStatus = cursor.getInt(cursor.getColumnIndexOrThrow("recovery_status"));
                WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, generationId, recoveryStatus);
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return wrappedKey;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    public boolean removeKey(int uid, String alias) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        String[] selectionArgs = {Integer.toString(uid), alias};
        return db.delete("keys", "uid = ? AND alias = ?", selectionArgs) > 0;
    }

    public Map<String, Integer> getStatusForAllKeys(int uid) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "alias", "recovery_status"};
        String[] selectionArguments = {Integer.toString(uid)};
        Cursor cursor = db.query("keys", projection, "uid = ?", selectionArguments, null, null, null);
        try {
            HashMap<String, Integer> statuses = new HashMap<>();
            while (cursor.moveToNext()) {
                String alias = cursor.getString(cursor.getColumnIndexOrThrow("alias"));
                int recoveryStatus = cursor.getInt(cursor.getColumnIndexOrThrow("recovery_status"));
                statuses.put(alias, Integer.valueOf(recoveryStatus));
            }
            if (cursor != null) {
                $closeResource(null, cursor);
            }
            return statuses;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    public int setRecoveryStatus(int uid, String alias, int status) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("recovery_status", Integer.valueOf(status));
        return db.update("keys", values, "uid = ? AND alias = ?", new String[]{String.valueOf(uid), alias});
    }

    public Map<String, WrappedKey> getAllKeys(int userId, int recoveryAgentUid, int platformKeyGenerationId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "nonce", "wrapped_key", "alias", "recovery_status"};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(recoveryAgentUid), Integer.toString(platformKeyGenerationId)};
        Cursor cursor = db.query("keys", projection, "user_id = ? AND uid = ? AND platform_key_generation_id = ?", selectionArguments, null, null, null);
        try {
            HashMap<String, WrappedKey> keys = new HashMap<>();
            while (cursor.moveToNext()) {
                byte[] nonce = cursor.getBlob(cursor.getColumnIndexOrThrow("nonce"));
                byte[] keyMaterial = cursor.getBlob(cursor.getColumnIndexOrThrow("wrapped_key"));
                String alias = cursor.getString(cursor.getColumnIndexOrThrow("alias"));
                int recoveryStatus = cursor.getInt(cursor.getColumnIndexOrThrow("recovery_status"));
                keys.put(alias, new WrappedKey(nonce, keyMaterial, platformKeyGenerationId, recoveryStatus));
            }
            if (cursor != null) {
                $closeResource(null, cursor);
            }
            return keys;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    public long setPlatformKeyGenerationId(int userId, int generationId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("user_id", Integer.valueOf(userId));
        values.put("platform_key_generation_id", Integer.valueOf(generationId));
        long result = db.replace("user_metadata", null, values);
        if (result != -1) {
            invalidateKeysWithOldGenerationId(userId, generationId);
        }
        return result;
    }

    public void invalidateKeysWithOldGenerationId(int userId, int newGenerationId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("recovery_status", (Integer) 3);
        db.update("keys", values, "user_id = ? AND platform_key_generation_id < ?", new String[]{String.valueOf(userId), String.valueOf(newGenerationId)});
    }

    public void invalidateKeysForUserIdOnCustomScreenLock(int userId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("recovery_status", (Integer) 3);
        db.update("keys", values, "user_id = ?", new String[]{String.valueOf(userId)});
    }

    public int getPlatformKeyGenerationId(int userId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"platform_key_generation_id"};
        String[] selectionArguments = {Integer.toString(userId)};
        Cursor cursor = db.query("user_metadata", projection, "user_id = ?", selectionArguments, null, null, null);
        try {
            if (cursor.getCount() == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return -1;
            }
            cursor.moveToFirst();
            int i = cursor.getInt(cursor.getColumnIndexOrThrow("platform_key_generation_id"));
            if (cursor != null) {
                $closeResource(null, cursor);
            }
            return i;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    public long setRecoveryServicePublicKey(int userId, int uid, PublicKey publicKey) {
        return setBytes(userId, uid, "public_key", publicKey.getEncoded());
    }

    public Long getRecoveryServiceCertSerial(int userId, int uid, String rootAlias) {
        return getLong(userId, uid, rootAlias, "cert_serial");
    }

    public long setRecoveryServiceCertSerial(int userId, int uid, String rootAlias, long serial) {
        return setLong(userId, uid, rootAlias, "cert_serial", serial);
    }

    public CertPath getRecoveryServiceCertPath(int userId, int uid, String rootAlias) {
        byte[] bytes = getBytes(userId, uid, rootAlias, "cert_path");
        if (bytes == null) {
            return null;
        }
        try {
            return decodeCertPath(bytes);
        } catch (CertificateException e) {
            Log.wtf(TAG, String.format(Locale.US, "Recovery service CertPath entry cannot be decoded for userId=%d uid=%d.", Integer.valueOf(userId), Integer.valueOf(uid)), e);
            return null;
        }
    }

    public long setRecoveryServiceCertPath(int userId, int uid, String rootAlias, CertPath certPath) throws CertificateEncodingException {
        if (certPath.getCertificates().size() == 0) {
            throw new CertificateEncodingException("No certificate contained in the cert path.");
        }
        return setBytes(userId, uid, rootAlias, "cert_path", certPath.getEncoded(CERT_PATH_ENCODING));
    }

    public List<Integer> getRecoveryAgents(int userId) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {WatchlistLoggingHandler.WatchlistEventKeys.UID};
        String[] selectionArguments = {Integer.toString(userId)};
        Cursor cursor = db.query("recovery_service_metadata", projection, "user_id = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            ArrayList<Integer> result = new ArrayList<>(count);
            while (cursor.moveToNext()) {
                int uid = cursor.getInt(cursor.getColumnIndexOrThrow(WatchlistLoggingHandler.WatchlistEventKeys.UID));
                result.add(Integer.valueOf(uid));
            }
            if (cursor != null) {
                $closeResource(null, cursor);
            }
            return result;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    public PublicKey getRecoveryServicePublicKey(int userId, int uid) {
        byte[] keyBytes = getBytes(userId, uid, "public_key");
        if (keyBytes == null) {
            return null;
        }
        try {
            return decodeX509Key(keyBytes);
        } catch (InvalidKeySpecException e) {
            Log.wtf(TAG, String.format(Locale.US, "Recovery service public key entry cannot be decoded for userId=%d uid=%d.", Integer.valueOf(userId), Integer.valueOf(uid)));
            return null;
        }
    }

    public long setRecoverySecretTypes(int userId, int uid, int[] secretTypes) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        final StringJoiner joiner = new StringJoiner(",");
        Arrays.stream(secretTypes).forEach(new IntConsumer() { // from class: com.android.server.locksettings.recoverablekeystore.storage.-$$Lambda$RecoverableKeyStoreDb$knfkhmVPS_11tGWkGt87bH4xjYg
            @Override // java.util.function.IntConsumer
            public final void accept(int i) {
                joiner.add(Integer.toString(i));
            }
        });
        String typesAsCsv = joiner.toString();
        values.put("secret_types", typesAsCsv);
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update("recovery_service_metadata", values, "user_id = ? AND uid = ?", new String[]{String.valueOf(userId), String.valueOf(uid)});
    }

    public int[] getRecoverySecretTypes(int userId, int uid) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, "secret_types"};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        Cursor cursor = db.query("recovery_service_metadata", projection, "user_id = ? AND uid = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                return new int[0];
            }
            if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d deviceId entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                int[] iArr = new int[0];
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return iArr;
            }
            cursor.moveToFirst();
            int idx = cursor.getColumnIndexOrThrow("secret_types");
            if (cursor.isNull(idx)) {
                int[] iArr2 = new int[0];
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return iArr2;
            }
            String csv = cursor.getString(idx);
            if (TextUtils.isEmpty(csv)) {
                int[] iArr3 = new int[0];
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return iArr3;
            }
            String[] types = csv.split(",");
            int[] result = new int[types.length];
            for (int i = 0; i < types.length; i++) {
                try {
                    result[i] = Integer.parseInt(types[i]);
                } catch (NumberFormatException e) {
                    Log.wtf(TAG, "String format error " + e);
                }
            }
            if (cursor != null) {
                $closeResource(null, cursor);
            }
            return result;
        } finally {
            if (cursor != null) {
                $closeResource(null, cursor);
            }
        }
    }

    public long setActiveRootOfTrust(int userId, int uid, String rootAlias) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("active_root_of_trust", rootAlias);
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update("recovery_service_metadata", values, "user_id = ? AND uid = ?", new String[]{String.valueOf(userId), String.valueOf(uid)});
    }

    public String getActiveRootOfTrust(int userId, int uid) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, "active_root_of_trust"};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        Cursor cursor = db.query("recovery_service_metadata", projection, "user_id = ? AND uid = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d deviceId entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                int idx = cursor.getColumnIndexOrThrow("active_root_of_trust");
                if (cursor.isNull(idx)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                String result = cursor.getString(idx);
                if (TextUtils.isEmpty(result)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return result;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    public long setCounterId(int userId, int uid, long counterId) {
        return setLong(userId, uid, "counter_id", counterId);
    }

    public Long getCounterId(int userId, int uid) {
        return getLong(userId, uid, "counter_id");
    }

    public long setServerParams(int userId, int uid, byte[] serverParams) {
        return setBytes(userId, uid, "server_params", serverParams);
    }

    public byte[] getServerParams(int userId, int uid) {
        return getBytes(userId, uid, "server_params");
    }

    public long setSnapshotVersion(int userId, int uid, long snapshotVersion) {
        return setLong(userId, uid, "snapshot_version", snapshotVersion);
    }

    public Long getSnapshotVersion(int userId, int uid) {
        return getLong(userId, uid, "snapshot_version");
    }

    public long setShouldCreateSnapshot(int userId, int uid, boolean pending) {
        return setLong(userId, uid, "should_create_snapshot", pending ? 1L : 0L);
    }

    public boolean getShouldCreateSnapshot(int userId, int uid) {
        Long res = getLong(userId, uid, "should_create_snapshot");
        return (res == null || res.longValue() == 0) ? false : true;
    }

    private Long getLong(int userId, int uid, String key) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, key};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        Cursor cursor = db.query("recovery_service_metadata", projection, "user_id = ? AND uid = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                int idx = cursor.getColumnIndexOrThrow(key);
                if (cursor.isNull(idx)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                Long valueOf = Long.valueOf(cursor.getLong(idx));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return valueOf;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    private long setLong(int userId, int uid, String key, long value) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, Long.valueOf(value));
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update("recovery_service_metadata", values, "user_id = ? AND uid = ?", selectionArguments);
    }

    private byte[] getBytes(int userId, int uid, String key) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, key};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        Cursor cursor = db.query("recovery_service_metadata", projection, "user_id = ? AND uid = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                int idx = cursor.getColumnIndexOrThrow(key);
                if (cursor.isNull(idx)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                byte[] blob = cursor.getBlob(idx);
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return blob;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    private long setBytes(int userId, int uid, String key, byte[] value) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid)};
        ensureRecoveryServiceMetadataEntryExists(userId, uid);
        return db.update("recovery_service_metadata", values, "user_id = ? AND uid = ?", selectionArguments);
    }

    private byte[] getBytes(int userId, int uid, String rootAlias, String key) {
        String rootAlias2 = this.mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, "root_alias", key};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias2};
        Cursor cursor = db.query("root_of_trust", projection, "user_id = ? AND uid = ? AND root_alias = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                int idx = cursor.getColumnIndexOrThrow(key);
                if (cursor.isNull(idx)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                byte[] blob = cursor.getBlob(idx);
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return blob;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    private long setBytes(int userId, int uid, String rootAlias, String key, byte[] value) {
        String rootAlias2 = this.mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, value);
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias2};
        ensureRootOfTrustEntryExists(userId, uid, rootAlias2);
        return db.update("root_of_trust", values, "user_id = ? AND uid = ? AND root_alias = ?", selectionArguments);
    }

    private Long getLong(int userId, int uid, String rootAlias, String key) {
        String rootAlias2 = this.mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = this.mKeyStoreDbHelper.getReadableDatabase();
        String[] projection = {"_id", "user_id", WatchlistLoggingHandler.WatchlistEventKeys.UID, "root_alias", key};
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias2};
        Cursor cursor = db.query("root_of_trust", projection, "user_id = ? AND uid = ? AND root_alias = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else if (count > 1) {
                Log.wtf(TAG, String.format(Locale.US, "%d entries found for userId=%d uid=%d. Should only ever be 0 or 1.", Integer.valueOf(count), Integer.valueOf(userId), Integer.valueOf(uid)));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return null;
            } else {
                cursor.moveToFirst();
                int idx = cursor.getColumnIndexOrThrow(key);
                if (cursor.isNull(idx)) {
                    if (cursor != null) {
                        $closeResource(null, cursor);
                    }
                    return null;
                }
                Long valueOf = Long.valueOf(cursor.getLong(idx));
                if (cursor != null) {
                    $closeResource(null, cursor);
                }
                return valueOf;
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (cursor != null) {
                    $closeResource(th, cursor);
                }
                throw th2;
            }
        }
    }

    private long setLong(int userId, int uid, String rootAlias, String key, long value) {
        String rootAlias2 = this.mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(rootAlias);
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key, Long.valueOf(value));
        String[] selectionArguments = {Integer.toString(userId), Integer.toString(uid), rootAlias2};
        ensureRootOfTrustEntryExists(userId, uid, rootAlias2);
        return db.update("root_of_trust", values, "user_id = ? AND uid = ? AND root_alias = ?", selectionArguments);
    }

    private void ensureRecoveryServiceMetadataEntryExists(int userId, int uid) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("user_id", Integer.valueOf(userId));
        values.put(WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.valueOf(uid));
        db.insertWithOnConflict("recovery_service_metadata", null, values, 4);
    }

    private void ensureRootOfTrustEntryExists(int userId, int uid, String rootAlias) {
        SQLiteDatabase db = this.mKeyStoreDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("user_id", Integer.valueOf(userId));
        values.put(WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.valueOf(uid));
        values.put("root_alias", rootAlias);
        db.insertWithOnConflict("root_of_trust", null, values, 4);
    }

    public void close() {
        this.mKeyStoreDbHelper.close();
    }

    private static PublicKey decodeX509Key(byte[] keyBytes) throws InvalidKeySpecException {
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static CertPath decodeCertPath(byte[] bytes) throws CertificateException {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return certFactory.generateCertPath(new ByteArrayInputStream(bytes), CERT_PATH_ENCODING);
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }
}
