package com.android.server.backup.encryption.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.ArrayMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/* loaded from: classes.dex */
public class TertiaryKeysTable {
    private final BackupEncryptionDbHelper mHelper;

    /* JADX INFO: Access modifiers changed from: package-private */
    public TertiaryKeysTable(BackupEncryptionDbHelper helper) {
        this.mHelper = helper;
    }

    public long addKey(TertiaryKey tertiaryKey) throws EncryptionDbException {
        SQLiteDatabase db = this.mHelper.getWritableDatabaseSafe();
        ContentValues values = new ContentValues();
        values.put("secondary_key_alias", tertiaryKey.getSecondaryKeyAlias());
        values.put("package_name", tertiaryKey.getPackageName());
        values.put("wrapped_key_bytes", tertiaryKey.getWrappedKeyBytes());
        return db.replace("tertiary_keys", null, values);
    }

    public Optional<TertiaryKey> getKey(String secondaryKeyAlias, String packageName) throws EncryptionDbException {
        SQLiteDatabase db = this.mHelper.getReadableDatabaseSafe();
        String[] projection = {"_id", "secondary_key_alias", "package_name", "wrapped_key_bytes"};
        String[] selectionArguments = {secondaryKeyAlias, packageName};
        Cursor cursor = db.query("tertiary_keys", projection, "secondary_key_alias = ? AND package_name = ?", selectionArguments, null, null, null);
        try {
            int count = cursor.getCount();
            if (count == 0) {
                Optional<TertiaryKey> empty = Optional.empty();
                $closeResource(null, cursor);
                return empty;
            }
            cursor.moveToFirst();
            byte[] wrappedKeyBytes = cursor.getBlob(cursor.getColumnIndexOrThrow("wrapped_key_bytes"));
            Optional<TertiaryKey> of = Optional.of(new TertiaryKey(secondaryKeyAlias, packageName, wrappedKeyBytes));
            $closeResource(null, cursor);
            return of;
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

    public Map<String, TertiaryKey> getAllKeys(String secondaryKeyAlias) throws EncryptionDbException {
        SQLiteDatabase db = this.mHelper.getReadableDatabaseSafe();
        String[] projection = {"_id", "secondary_key_alias", "package_name", "wrapped_key_bytes"};
        String[] selectionArguments = {secondaryKeyAlias};
        Map<String, TertiaryKey> keysByPackageName = new ArrayMap<>();
        Cursor cursor = db.query("tertiary_keys", projection, "secondary_key_alias = ?", selectionArguments, null, null, null);
        while (cursor.moveToNext()) {
            try {
                String packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name"));
                byte[] wrappedKeyBytes = cursor.getBlob(cursor.getColumnIndexOrThrow("wrapped_key_bytes"));
                keysByPackageName.put(packageName, new TertiaryKey(secondaryKeyAlias, packageName, wrappedKeyBytes));
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
        $closeResource(null, cursor);
        return Collections.unmodifiableMap(keysByPackageName);
    }
}
