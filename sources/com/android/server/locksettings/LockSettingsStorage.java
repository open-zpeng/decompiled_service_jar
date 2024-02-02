package com.android.server.locksettings;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.backup.BackupManagerConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class LockSettingsStorage {
    private static final String BASE_ZERO_LOCK_PATTERN_FILE = "gatekeeper.gesture.key";
    private static final String CHILD_PROFILE_LOCK_FILE = "gatekeeper.profile.key";
    private static final String COLUMN_KEY = "name";
    private static final String COLUMN_USERID = "user";
    private static final boolean DEBUG = false;
    private static final String LEGACY_LOCK_PASSWORD_FILE = "password.key";
    private static final String LEGACY_LOCK_PATTERN_FILE = "gesture.key";
    private static final String LOCK_PASSWORD_FILE = "gatekeeper.password.key";
    private static final String LOCK_PATTERN_FILE = "gatekeeper.pattern.key";
    private static final String SYNTHETIC_PASSWORD_DIRECTORY = "spblob/";
    private static final String SYSTEM_DIRECTORY = "/system/";
    private static final String TABLE = "locksettings";
    private static final String TAG = "LockSettingsStorage";
    private final Context mContext;
    private final DatabaseHelper mOpenHelper;
    private PersistentDataBlockManagerInternal mPersistentDataBlockManagerInternal;
    private static final String COLUMN_VALUE = "value";
    private static final String[] COLUMNS_FOR_QUERY = {COLUMN_VALUE};
    private static final String[] COLUMNS_FOR_PREFETCH = {"name", COLUMN_VALUE};
    private static final Object DEFAULT = new Object();
    private final Cache mCache = new Cache();
    private final Object mFileWriteLock = new Object();

    /* loaded from: classes.dex */
    public interface Callback {
        void initialize(SQLiteDatabase sQLiteDatabase);
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class CredentialHash {
        static final int VERSION_GATEKEEPER = 1;
        static final int VERSION_LEGACY = 0;
        byte[] hash;
        boolean isBaseZeroPattern;
        int type;
        int version;

        private CredentialHash(byte[] hash, int type, int version) {
            this(hash, type, version, false);
        }

        private CredentialHash(byte[] hash, int type, int version, boolean isBaseZeroPattern) {
            if (type != -1) {
                if (hash == null) {
                    throw new RuntimeException("Empty hash for CredentialHash");
                }
            } else if (hash != null) {
                throw new RuntimeException("None type CredentialHash should not have hash");
            }
            this.hash = hash;
            this.type = type;
            this.version = version;
            this.isBaseZeroPattern = isBaseZeroPattern;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static CredentialHash createBaseZeroPattern(byte[] hash) {
            return new CredentialHash(hash, 1, 1, true);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static CredentialHash create(byte[] hash, int type) {
            if (type == -1) {
                throw new RuntimeException("Bad type for CredentialHash");
            }
            return new CredentialHash(hash, type, 1);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static CredentialHash createEmptyHash() {
            return new CredentialHash(null, -1, 1);
        }

        public byte[] toBytes() {
            Preconditions.checkState(!this.isBaseZeroPattern, "base zero patterns are not serializable");
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(os);
                dos.write(this.version);
                dos.write(this.type);
                if (this.hash != null && this.hash.length > 0) {
                    dos.writeInt(this.hash.length);
                    dos.write(this.hash);
                } else {
                    dos.writeInt(0);
                }
                dos.close();
                return os.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static CredentialHash fromBytes(byte[] bytes) {
            try {
                DataInputStream is = new DataInputStream(new ByteArrayInputStream(bytes));
                int version = is.read();
                int type = is.read();
                int hashSize = is.readInt();
                byte[] hash = null;
                if (hashSize > 0) {
                    hash = new byte[hashSize];
                    is.readFully(hash);
                }
                return new CredentialHash(hash, type, version);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public LockSettingsStorage(Context context) {
        this.mContext = context;
        this.mOpenHelper = new DatabaseHelper(context);
    }

    public void setDatabaseOnCreateCallback(Callback callback) {
        this.mOpenHelper.setCallback(callback);
    }

    public void writeKeyValue(String key, String value, int userId) {
        writeKeyValue(this.mOpenHelper.getWritableDatabase(), key, value, userId);
    }

    public void writeKeyValue(SQLiteDatabase db, String key, String value, int userId) {
        ContentValues cv = new ContentValues();
        cv.put("name", key);
        cv.put(COLUMN_USERID, Integer.valueOf(userId));
        cv.put(COLUMN_VALUE, value);
        db.beginTransaction();
        try {
            db.delete(TABLE, "name=? AND user=?", new String[]{key, Integer.toString(userId)});
            db.insert(TABLE, null, cv);
            db.setTransactionSuccessful();
            this.mCache.putKeyValue(key, value, userId);
        } finally {
            db.endTransaction();
        }
    }

    public String readKeyValue(String key, String defaultValue, int userId) {
        synchronized (this.mCache) {
            if (this.mCache.hasKeyValue(key, userId)) {
                return this.mCache.peekKeyValue(key, defaultValue, userId);
            }
            int version = this.mCache.getVersion();
            Object result = DEFAULT;
            SQLiteDatabase db = this.mOpenHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE, COLUMNS_FOR_QUERY, "user=? AND name=?", new String[]{Integer.toString(userId), key}, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0);
                }
                cursor.close();
            }
            this.mCache.putKeyValueIfUnchanged(key, result, userId, version);
            return result == DEFAULT ? defaultValue : (String) result;
        }
    }

    public void prefetchUser(int userId) {
        synchronized (this.mCache) {
            if (this.mCache.isFetched(userId)) {
                return;
            }
            this.mCache.setFetched(userId);
            int version = this.mCache.getVersion();
            SQLiteDatabase db = this.mOpenHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE, COLUMNS_FOR_PREFETCH, "user=?", new String[]{Integer.toString(userId)}, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    String value = cursor.getString(1);
                    this.mCache.putKeyValueIfUnchanged(key, value, userId, version);
                }
                cursor.close();
            }
            readCredentialHash(userId);
        }
    }

    private CredentialHash readPasswordHashIfExists(int userId) {
        byte[] stored = readFile(getLockPasswordFilename(userId));
        if (!ArrayUtils.isEmpty(stored)) {
            return new CredentialHash(stored, 2, 1);
        }
        byte[] stored2 = readFile(getLegacyLockPasswordFilename(userId));
        if (ArrayUtils.isEmpty(stored2)) {
            return null;
        }
        return new CredentialHash(stored2, 2, 0);
    }

    private CredentialHash readPatternHashIfExists(int userId) {
        byte[] stored = readFile(getLockPatternFilename(userId));
        if (!ArrayUtils.isEmpty(stored)) {
            return new CredentialHash(stored, 1, 1);
        }
        byte[] stored2 = readFile(getBaseZeroLockPatternFilename(userId));
        if (!ArrayUtils.isEmpty(stored2)) {
            return CredentialHash.createBaseZeroPattern(stored2);
        }
        byte[] stored3 = readFile(getLegacyLockPatternFilename(userId));
        if (ArrayUtils.isEmpty(stored3)) {
            return null;
        }
        return new CredentialHash(stored3, 1, 0);
    }

    public CredentialHash readCredentialHash(int userId) {
        CredentialHash passwordHash = readPasswordHashIfExists(userId);
        CredentialHash patternHash = readPatternHashIfExists(userId);
        if (passwordHash != null && patternHash != null) {
            if (passwordHash.version == 1) {
                return passwordHash;
            }
            return patternHash;
        } else if (passwordHash != null) {
            return passwordHash;
        } else {
            if (patternHash != null) {
                return patternHash;
            }
            return CredentialHash.createEmptyHash();
        }
    }

    public void removeChildProfileLock(int userId) {
        try {
            deleteFile(getChildProfileLockFile(userId));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void writeChildProfileLock(int userId, byte[] lock) {
        writeFile(getChildProfileLockFile(userId), lock);
    }

    public byte[] readChildProfileLock(int userId) {
        return readFile(getChildProfileLockFile(userId));
    }

    public boolean hasChildProfileLock(int userId) {
        return hasFile(getChildProfileLockFile(userId));
    }

    public boolean hasPassword(int userId) {
        return hasFile(getLockPasswordFilename(userId)) || hasFile(getLegacyLockPasswordFilename(userId));
    }

    public boolean hasPattern(int userId) {
        return hasFile(getLockPatternFilename(userId)) || hasFile(getBaseZeroLockPatternFilename(userId)) || hasFile(getLegacyLockPatternFilename(userId));
    }

    public boolean hasCredential(int userId) {
        return hasPassword(userId) || hasPattern(userId);
    }

    private boolean hasFile(String name) {
        byte[] contents = readFile(name);
        return contents != null && contents.length > 0;
    }

    private byte[] readFile(String name) {
        String str;
        StringBuilder sb;
        synchronized (this.mCache) {
            if (this.mCache.hasFile(name)) {
                return this.mCache.peekFile(name);
            }
            int version = this.mCache.getVersion();
            RandomAccessFile raf = null;
            byte[] stored = null;
            try {
                try {
                    raf = new RandomAccessFile(name, "r");
                    stored = new byte[(int) raf.length()];
                    raf.readFully(stored, 0, stored.length);
                    raf.close();
                    try {
                        raf.close();
                    } catch (IOException e) {
                        e = e;
                        str = TAG;
                        sb = new StringBuilder();
                        sb.append("Error closing file ");
                        sb.append(e);
                        Slog.e(str, sb.toString());
                        this.mCache.putFileIfUnchanged(name, stored, version);
                        return stored;
                    }
                } catch (Throwable th) {
                    if (raf != null) {
                        try {
                            raf.close();
                        } catch (IOException e2) {
                            Slog.e(TAG, "Error closing file " + e2);
                        }
                    }
                    throw th;
                }
            } catch (IOException e3) {
                Slog.e(TAG, "Cannot read file " + e3);
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (IOException e4) {
                        e = e4;
                        str = TAG;
                        sb = new StringBuilder();
                        sb.append("Error closing file ");
                        sb.append(e);
                        Slog.e(str, sb.toString());
                        this.mCache.putFileIfUnchanged(name, stored, version);
                        return stored;
                    }
                }
            }
            this.mCache.putFileIfUnchanged(name, stored, version);
            return stored;
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(9:3|(2:4|5)|(6:10|11|12|13|14|15)|24|11|12|13|14|15) */
    /* JADX WARN: Code restructure failed: missing block: B:15:0x0026, code lost:
        r2 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0027, code lost:
        r3 = com.android.server.locksettings.LockSettingsStorage.TAG;
        r4 = "Error closing file " + r2;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void writeFile(java.lang.String r8, byte[] r9) {
        /*
            r7 = this;
            java.lang.Object r0 = r7.mFileWriteLock
            monitor-enter(r0)
            r1 = 0
            java.io.RandomAccessFile r2 = new java.io.RandomAccessFile     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            java.lang.String r3 = "rws"
            r2.<init>(r8, r3)     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            r1 = r2
            if (r9 == 0) goto L19
            int r2 = r9.length     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            if (r2 != 0) goto L13
            goto L19
        L13:
            r2 = 0
            int r3 = r9.length     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            r1.write(r9, r2, r3)     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            goto L1e
        L19:
            r2 = 0
            r1.setLength(r2)     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
        L1e:
            r1.close()     // Catch: java.lang.Throwable -> L3e java.io.IOException -> L40
            r1.close()     // Catch: java.io.IOException -> L26 java.lang.Throwable -> L7f
        L25:
            goto L72
        L26:
            r2 = move-exception
            java.lang.String r3 = "LockSettingsStorage"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7f
            r4.<init>()     // Catch: java.lang.Throwable -> L7f
            java.lang.String r5 = "Error closing file "
            r4.append(r5)     // Catch: java.lang.Throwable -> L7f
            r4.append(r2)     // Catch: java.lang.Throwable -> L7f
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L7f
        L3a:
            android.util.Slog.e(r3, r4)     // Catch: java.lang.Throwable -> L7f
            goto L25
        L3e:
            r2 = move-exception
            goto L79
        L40:
            r2 = move-exception
            java.lang.String r3 = "LockSettingsStorage"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L3e
            r4.<init>()     // Catch: java.lang.Throwable -> L3e
            java.lang.String r5 = "Error writing to file "
            r4.append(r5)     // Catch: java.lang.Throwable -> L3e
            r4.append(r2)     // Catch: java.lang.Throwable -> L3e
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L3e
            android.util.Slog.e(r3, r4)     // Catch: java.lang.Throwable -> L3e
            if (r1 == 0) goto L72
            r1.close()     // Catch: java.io.IOException -> L5d java.lang.Throwable -> L7f
            goto L25
        L5d:
            r2 = move-exception
            java.lang.String r3 = "LockSettingsStorage"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7f
            r4.<init>()     // Catch: java.lang.Throwable -> L7f
            java.lang.String r5 = "Error closing file "
            r4.append(r5)     // Catch: java.lang.Throwable -> L7f
            r4.append(r2)     // Catch: java.lang.Throwable -> L7f
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L7f
            goto L3a
        L72:
            com.android.server.locksettings.LockSettingsStorage$Cache r2 = r7.mCache     // Catch: java.lang.Throwable -> L7f
            r2.putFile(r8, r9)     // Catch: java.lang.Throwable -> L7f
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L7f
            return
        L79:
            if (r1 == 0) goto L98
            r1.close()     // Catch: java.lang.Throwable -> L7f java.io.IOException -> L81
            goto L98
        L7f:
            r1 = move-exception
            goto L99
        L81:
            r3 = move-exception
            java.lang.String r4 = "LockSettingsStorage"
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7f
            r5.<init>()     // Catch: java.lang.Throwable -> L7f
            java.lang.String r6 = "Error closing file "
            r5.append(r6)     // Catch: java.lang.Throwable -> L7f
            r5.append(r3)     // Catch: java.lang.Throwable -> L7f
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L7f
            android.util.Slog.e(r4, r5)     // Catch: java.lang.Throwable -> L7f
        L98:
            throw r2     // Catch: java.lang.Throwable -> L7f
        L99:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L7f
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.locksettings.LockSettingsStorage.writeFile(java.lang.String, byte[]):void");
    }

    private void deleteFile(String name) {
        synchronized (this.mFileWriteLock) {
            File file = new File(name);
            if (file.exists()) {
                file.delete();
                this.mCache.putFile(name, null);
            }
        }
    }

    public void writeCredentialHash(CredentialHash hash, int userId) {
        byte[] patternHash = null;
        byte[] passwordHash = null;
        if (hash.type == 2) {
            passwordHash = hash.hash;
        } else if (hash.type == 1) {
            patternHash = hash.hash;
        }
        writeFile(getLockPasswordFilename(userId), passwordHash);
        writeFile(getLockPatternFilename(userId), patternHash);
    }

    @VisibleForTesting
    String getLockPatternFilename(int userId) {
        return getLockCredentialFilePathForUser(userId, LOCK_PATTERN_FILE);
    }

    @VisibleForTesting
    String getLockPasswordFilename(int userId) {
        return getLockCredentialFilePathForUser(userId, LOCK_PASSWORD_FILE);
    }

    @VisibleForTesting
    String getLegacyLockPatternFilename(int userId) {
        return getLockCredentialFilePathForUser(userId, LEGACY_LOCK_PATTERN_FILE);
    }

    @VisibleForTesting
    String getLegacyLockPasswordFilename(int userId) {
        return getLockCredentialFilePathForUser(userId, LEGACY_LOCK_PASSWORD_FILE);
    }

    private String getBaseZeroLockPatternFilename(int userId) {
        return getLockCredentialFilePathForUser(userId, BASE_ZERO_LOCK_PATTERN_FILE);
    }

    @VisibleForTesting
    String getChildProfileLockFile(int userId) {
        return getLockCredentialFilePathForUser(userId, CHILD_PROFILE_LOCK_FILE);
    }

    private String getLockCredentialFilePathForUser(int userId, String basename) {
        String dataSystemDirectory = Environment.getDataDirectory().getAbsolutePath() + SYSTEM_DIRECTORY;
        if (userId == 0) {
            return dataSystemDirectory + basename;
        }
        return new File(Environment.getUserSystemDirectory(userId), basename).getAbsolutePath();
    }

    public void writeSyntheticPasswordState(int userId, long handle, String name, byte[] data) {
        ensureSyntheticPasswordDirectoryForUser(userId);
        writeFile(getSynthenticPasswordStateFilePathForUser(userId, handle, name), data);
    }

    public byte[] readSyntheticPasswordState(int userId, long handle, String name) {
        return readFile(getSynthenticPasswordStateFilePathForUser(userId, handle, name));
    }

    public void deleteSyntheticPasswordState(int userId, long handle, String name) {
        RandomAccessFile raf;
        String path = getSynthenticPasswordStateFilePathForUser(userId, handle, name);
        File file = new File(path);
        if (file.exists()) {
            try {
                try {
                    raf = new RandomAccessFile(path, "rws");
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to zeroize " + path, e);
                }
                try {
                    int fileSize = (int) raf.length();
                    raf.write(new byte[fileSize]);
                    raf.close();
                    file.delete();
                    this.mCache.putFile(path, null);
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        if (th != null) {
                            try {
                                raf.close();
                            } catch (Throwable th3) {
                                th.addSuppressed(th3);
                            }
                        } else {
                            raf.close();
                        }
                        throw th2;
                    }
                }
            } catch (Throwable th4) {
                file.delete();
                throw th4;
            }
        }
    }

    public Map<Integer, List<Long>> listSyntheticPasswordHandlesForAllUsers(String stateName) {
        Map<Integer, List<Long>> result = new ArrayMap<>();
        UserManager um = UserManager.get(this.mContext);
        for (UserInfo user : um.getUsers(false)) {
            result.put(Integer.valueOf(user.id), listSyntheticPasswordHandlesForUser(stateName, user.id));
        }
        return result;
    }

    public List<Long> listSyntheticPasswordHandlesForUser(String stateName, int userId) {
        File baseDir = getSyntheticPasswordDirectoryForUser(userId);
        List<Long> result = new ArrayList<>();
        File[] files = baseDir.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            if (parts.length == 2 && parts[1].equals(stateName)) {
                try {
                    result.add(Long.valueOf(Long.parseUnsignedLong(parts[0], 16)));
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse handle " + parts[0]);
                }
            }
        }
        return result;
    }

    @VisibleForTesting
    protected File getSyntheticPasswordDirectoryForUser(int userId) {
        return new File(Environment.getDataSystemDeDirectory(userId), SYNTHETIC_PASSWORD_DIRECTORY);
    }

    private void ensureSyntheticPasswordDirectoryForUser(int userId) {
        File baseDir = getSyntheticPasswordDirectoryForUser(userId);
        if (!baseDir.exists()) {
            baseDir.mkdir();
        }
    }

    @VisibleForTesting
    protected String getSynthenticPasswordStateFilePathForUser(int userId, long handle, String name) {
        File baseDir = getSyntheticPasswordDirectoryForUser(userId);
        String baseName = String.format("%016x.%s", Long.valueOf(handle), name);
        return new File(baseDir, baseName).getAbsolutePath();
    }

    public void removeUser(int userId) {
        SQLiteDatabase db = this.mOpenHelper.getWritableDatabase();
        UserManager um = (UserManager) this.mContext.getSystemService(COLUMN_USERID);
        UserInfo parentInfo = um.getProfileParent(userId);
        if (parentInfo == null) {
            synchronized (this.mFileWriteLock) {
                String name = getLockPasswordFilename(userId);
                File file = new File(name);
                if (file.exists()) {
                    file.delete();
                    this.mCache.putFile(name, null);
                }
                String name2 = getLockPatternFilename(userId);
                File file2 = new File(name2);
                if (file2.exists()) {
                    file2.delete();
                    this.mCache.putFile(name2, null);
                }
            }
        } else {
            removeChildProfileLock(userId);
        }
        File spStateDir = getSyntheticPasswordDirectoryForUser(userId);
        try {
            db.beginTransaction();
            db.delete(TABLE, "user='" + userId + "'", null);
            db.setTransactionSuccessful();
            this.mCache.removeUser(userId);
            this.mCache.purgePath(spStateDir.getAbsolutePath());
        } finally {
            db.endTransaction();
        }
    }

    @VisibleForTesting
    void closeDatabase() {
        this.mOpenHelper.close();
    }

    @VisibleForTesting
    void clearCache() {
        this.mCache.clear();
    }

    public PersistentDataBlockManagerInternal getPersistentDataBlock() {
        if (this.mPersistentDataBlockManagerInternal == null) {
            this.mPersistentDataBlockManagerInternal = (PersistentDataBlockManagerInternal) LocalServices.getService(PersistentDataBlockManagerInternal.class);
        }
        return this.mPersistentDataBlockManagerInternal;
    }

    public void writePersistentDataBlock(int persistentType, int userId, int qualityForUi, byte[] payload) {
        PersistentDataBlockManagerInternal persistentDataBlock = getPersistentDataBlock();
        if (persistentDataBlock == null) {
            return;
        }
        persistentDataBlock.setFrpCredentialHandle(PersistentData.toBytes(persistentType, userId, qualityForUi, payload));
    }

    public PersistentData readPersistentDataBlock() {
        PersistentDataBlockManagerInternal persistentDataBlock = getPersistentDataBlock();
        if (persistentDataBlock == null) {
            return PersistentData.NONE;
        }
        try {
            return PersistentData.fromBytes(persistentDataBlock.getFrpCredentialHandle());
        } catch (IllegalStateException e) {
            Slog.e(TAG, "Error reading persistent data block", e);
            return PersistentData.NONE;
        }
    }

    /* loaded from: classes.dex */
    public static class PersistentData {
        public static final PersistentData NONE = new PersistentData(0, -10000, 0, null);
        public static final int TYPE_NONE = 0;
        public static final int TYPE_SP = 1;
        public static final int TYPE_SP_WEAVER = 2;
        static final byte VERSION_1 = 1;
        static final int VERSION_1_HEADER_SIZE = 10;
        final byte[] payload;
        final int qualityForUi;
        final int type;
        final int userId;

        private PersistentData(int type, int userId, int qualityForUi, byte[] payload) {
            this.type = type;
            this.userId = userId;
            this.qualityForUi = qualityForUi;
            this.payload = payload;
        }

        public static PersistentData fromBytes(byte[] frpData) {
            if (frpData == null || frpData.length == 0) {
                return NONE;
            }
            DataInputStream is = new DataInputStream(new ByteArrayInputStream(frpData));
            try {
                byte version = is.readByte();
                if (version == 1) {
                    int type = is.readByte() & 255;
                    int userId = is.readInt();
                    int qualityForUi = is.readInt();
                    byte[] payload = new byte[frpData.length - 10];
                    System.arraycopy(frpData, 10, payload, 0, payload.length);
                    return new PersistentData(type, userId, qualityForUi, payload);
                }
                Slog.wtf(LockSettingsStorage.TAG, "Unknown PersistentData version code: " + ((int) version));
                return NONE;
            } catch (IOException e) {
                Slog.wtf(LockSettingsStorage.TAG, "Could not parse PersistentData", e);
                return NONE;
            }
        }

        public static byte[] toBytes(int persistentType, int userId, int qualityForUi, byte[] payload) {
            if (persistentType == 0) {
                Preconditions.checkArgument(payload == null, "TYPE_NONE must have empty payload");
                return null;
            }
            if (payload != null && payload.length > 0) {
                r0 = true;
            }
            Preconditions.checkArgument(r0, "empty payload must only be used with TYPE_NONE");
            ByteArrayOutputStream os = new ByteArrayOutputStream(10 + payload.length);
            DataOutputStream dos = new DataOutputStream(os);
            try {
                dos.writeByte(1);
                dos.writeByte(persistentType);
                dos.writeInt(userId);
                dos.writeInt(qualityForUi);
                dos.write(payload);
                return os.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("ByteArrayOutputStream cannot throw IOException");
            }
        }
    }

    /* loaded from: classes.dex */
    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "locksettings.db";
        private static final int DATABASE_VERSION = 2;
        private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;
        private static final String TAG = "LockSettingsDB";
        private Callback mCallback;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, (SQLiteDatabase.CursorFactory) null, 2);
            setWriteAheadLoggingEnabled(true);
            setIdleConnectionTimeout(30000L);
        }

        public void setCallback(Callback callback) {
            this.mCallback = callback;
        }

        private void createTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE locksettings (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,user INTEGER,value TEXT);");
        }

        @Override // android.database.sqlite.SQLiteOpenHelper
        public void onCreate(SQLiteDatabase db) {
            createTable(db);
            if (this.mCallback != null) {
                this.mCallback.initialize(db);
            }
        }

        @Override // android.database.sqlite.SQLiteOpenHelper
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            int upgradeVersion = oldVersion;
            if (upgradeVersion == 1) {
                upgradeVersion = 2;
            }
            if (upgradeVersion != 2) {
                Log.w(TAG, "Failed to upgrade database!");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Cache {
        private final ArrayMap<CacheKey, Object> mCache;
        private final CacheKey mCacheKey;
        private int mVersion;

        private Cache() {
            this.mCache = new ArrayMap<>();
            this.mCacheKey = new CacheKey();
            this.mVersion = 0;
        }

        String peekKeyValue(String key, String defaultValue, int userId) {
            Object cached = peek(0, key, userId);
            return cached == LockSettingsStorage.DEFAULT ? defaultValue : (String) cached;
        }

        boolean hasKeyValue(String key, int userId) {
            return contains(0, key, userId);
        }

        void putKeyValue(String key, String value, int userId) {
            put(0, key, value, userId);
        }

        void putKeyValueIfUnchanged(String key, Object value, int userId, int version) {
            putIfUnchanged(0, key, value, userId, version);
        }

        byte[] peekFile(String fileName) {
            return (byte[]) peek(1, fileName, -1);
        }

        boolean hasFile(String fileName) {
            return contains(1, fileName, -1);
        }

        void putFile(String key, byte[] value) {
            put(1, key, value, -1);
        }

        void putFileIfUnchanged(String key, byte[] value, int version) {
            putIfUnchanged(1, key, value, -1, version);
        }

        void setFetched(int userId) {
            put(2, "isFetched", "true", userId);
        }

        boolean isFetched(int userId) {
            return contains(2, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, userId);
        }

        private synchronized void put(int type, String key, Object value, int userId) {
            this.mCache.put(new CacheKey().set(type, key, userId), value);
            this.mVersion++;
        }

        private synchronized void putIfUnchanged(int type, String key, Object value, int userId, int version) {
            if (!contains(type, key, userId) && this.mVersion == version) {
                put(type, key, value, userId);
            }
        }

        private synchronized boolean contains(int type, String key, int userId) {
            return this.mCache.containsKey(this.mCacheKey.set(type, key, userId));
        }

        private synchronized Object peek(int type, String key, int userId) {
            return this.mCache.get(this.mCacheKey.set(type, key, userId));
        }

        /* JADX INFO: Access modifiers changed from: private */
        public synchronized int getVersion() {
            return this.mVersion;
        }

        synchronized void removeUser(int userId) {
            for (int i = this.mCache.size() - 1; i >= 0; i--) {
                if (this.mCache.keyAt(i).userId == userId) {
                    this.mCache.removeAt(i);
                }
            }
            int i2 = this.mVersion;
            this.mVersion = i2 + 1;
        }

        synchronized void purgePath(String path) {
            for (int i = this.mCache.size() - 1; i >= 0; i--) {
                CacheKey entry = this.mCache.keyAt(i);
                if (entry.type == 1 && entry.key.startsWith(path)) {
                    this.mCache.removeAt(i);
                }
            }
            int i2 = this.mVersion;
            this.mVersion = i2 + 1;
        }

        synchronized void clear() {
            this.mCache.clear();
            this.mVersion++;
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static final class CacheKey {
            static final int TYPE_FETCHED = 2;
            static final int TYPE_FILE = 1;
            static final int TYPE_KEY_VALUE = 0;
            String key;
            int type;
            int userId;

            private CacheKey() {
            }

            public CacheKey set(int type, String key, int userId) {
                this.type = type;
                this.key = key;
                this.userId = userId;
                return this;
            }

            public boolean equals(Object obj) {
                if (obj instanceof CacheKey) {
                    CacheKey o = (CacheKey) obj;
                    return this.userId == o.userId && this.type == o.type && this.key.equals(o.key);
                }
                return false;
            }

            public int hashCode() {
                return (this.key.hashCode() ^ this.userId) ^ this.type;
            }
        }
    }
}
