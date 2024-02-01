package com.android.server.voiceinteraction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import android.text.TextUtils;
import android.util.Slog;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/* loaded from: classes2.dex */
public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String CREATE_TABLE_SOUND_MODEL = "CREATE TABLE sound_model(model_uuid TEXT,vendor_uuid TEXT,keyphrase_id INTEGER,type INTEGER,data BLOB,recognition_modes INTEGER,locale TEXT,hint_text TEXT,users TEXT,PRIMARY KEY (keyphrase_id,locale,users))";
    static final boolean DBG = false;
    private static final String NAME = "sound_model.db";
    static final String TAG = "SoundModelDBHelper";
    private static final int VERSION = 6;

    /* loaded from: classes2.dex */
    public interface SoundModelContract {
        public static final String KEY_DATA = "data";
        public static final String KEY_HINT_TEXT = "hint_text";
        public static final String KEY_KEYPHRASE_ID = "keyphrase_id";
        public static final String KEY_LOCALE = "locale";
        public static final String KEY_MODEL_UUID = "model_uuid";
        public static final String KEY_RECOGNITION_MODES = "recognition_modes";
        public static final String KEY_TYPE = "type";
        public static final String KEY_USERS = "users";
        public static final String KEY_VENDOR_UUID = "vendor_uuid";
        public static final String TABLE = "sound_model";
    }

    public DatabaseHelper(Context context) {
        super(context, NAME, (SQLiteDatabase.CursorFactory) null, 6);
    }

    @Override // android.database.sqlite.SQLiteOpenHelper
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_SOUND_MODEL);
    }

    /* JADX WARN: Removed duplicated region for block: B:21:0x005a  */
    @Override // android.database.sqlite.SQLiteOpenHelper
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void onUpgrade(android.database.sqlite.SQLiteDatabase r11, int r12, int r13) {
        /*
            r10 = this;
            java.lang.String r0 = "DROP TABLE IF EXISTS sound_model"
            r1 = 4
            java.lang.String r2 = "SoundModelDBHelper"
            if (r12 >= r1) goto Le
            r11.execSQL(r0)
            r10.onCreate(r11)
            goto L1c
        Le:
            if (r12 != r1) goto L1c
            java.lang.String r1 = "Adding vendor UUID column"
            android.util.Slog.d(r2, r1)
            java.lang.String r1 = "ALTER TABLE sound_model ADD COLUMN vendor_uuid TEXT"
            r11.execSQL(r1)
            int r12 = r12 + 1
        L1c:
            r1 = 5
            if (r12 != r1) goto Lb0
            java.lang.String r3 = "SELECT * FROM sound_model"
            r4 = 0
            android.database.Cursor r4 = r11.rawQuery(r3, r4)
            java.util.ArrayList r5 = new java.util.ArrayList
            r5.<init>()
            boolean r6 = r4.moveToFirst()     // Catch: java.lang.Throwable -> Lab
            if (r6 == 0) goto L46
        L31:
            com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord r6 = new com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lab
            r6.<init>(r1, r4)     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lab
            r5.add(r6)     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lab
            goto L40
        L3a:
            r6 = move-exception
            java.lang.String r7 = "Failed to extract V5 record"
            android.util.Slog.e(r2, r7, r6)     // Catch: java.lang.Throwable -> Lab
        L40:
            boolean r6 = r4.moveToNext()     // Catch: java.lang.Throwable -> Lab
            if (r6 != 0) goto L31
        L46:
            r4.close()
            r11.execSQL(r0)
            r10.onCreate(r11)
            java.util.Iterator r0 = r5.iterator()
        L54:
            boolean r1 = r0.hasNext()
            if (r1 == 0) goto La8
            java.lang.Object r1 = r0.next()
            com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord r1 = (com.android.server.voiceinteraction.DatabaseHelper.SoundModelRecord) r1
            boolean r6 = r1.ifViolatesV6PrimaryKeyIsFirstOfAnyDuplicates(r5)
            if (r6 == 0) goto La7
            r6 = 6
            long r6 = r1.writeToDatabase(r6, r11)     // Catch: java.lang.Exception -> L90
            r8 = -1
            int r8 = (r6 > r8 ? 1 : (r6 == r8 ? 0 : -1))
            if (r8 != 0) goto L8f
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Exception -> L90
            r8.<init>()     // Catch: java.lang.Exception -> L90
            java.lang.String r9 = "Database write failed "
            r8.append(r9)     // Catch: java.lang.Exception -> L90
            java.lang.String r9 = r1.modelUuid     // Catch: java.lang.Exception -> L90
            r8.append(r9)     // Catch: java.lang.Exception -> L90
            java.lang.String r9 = ": "
            r8.append(r9)     // Catch: java.lang.Exception -> L90
            r8.append(r6)     // Catch: java.lang.Exception -> L90
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Exception -> L90
            android.util.Slog.e(r2, r8)     // Catch: java.lang.Exception -> L90
        L8f:
            goto La7
        L90:
            r6 = move-exception
            java.lang.StringBuilder r7 = new java.lang.StringBuilder
            r7.<init>()
            java.lang.String r8 = "Failed to update V6 record "
            r7.append(r8)
            java.lang.String r8 = r1.modelUuid
            r7.append(r8)
            java.lang.String r7 = r7.toString()
            android.util.Slog.e(r2, r7, r6)
        La7:
            goto L54
        La8:
            int r12 = r12 + 1
            goto Lb0
        Lab:
            r0 = move-exception
            r4.close()
            throw r0
        Lb0:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.voiceinteraction.DatabaseHelper.onUpgrade(android.database.sqlite.SQLiteDatabase, int, int):void");
    }

    public boolean updateKeyphraseSoundModel(SoundTrigger.KeyphraseSoundModel soundModel) {
        synchronized (this) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("model_uuid", soundModel.uuid.toString());
            if (soundModel.vendorUuid != null) {
                values.put("vendor_uuid", soundModel.vendorUuid.toString());
            }
            values.put("type", (Integer) 0);
            values.put("data", soundModel.data);
            if (soundModel.keyphrases == null || soundModel.keyphrases.length != 1) {
                return false;
            }
            values.put(SoundModelContract.KEY_KEYPHRASE_ID, Integer.valueOf(soundModel.keyphrases[0].id));
            values.put(SoundModelContract.KEY_RECOGNITION_MODES, Integer.valueOf(soundModel.keyphrases[0].recognitionModes));
            values.put(SoundModelContract.KEY_USERS, getCommaSeparatedString(soundModel.keyphrases[0].users));
            values.put(SoundModelContract.KEY_LOCALE, soundModel.keyphrases[0].locale);
            values.put(SoundModelContract.KEY_HINT_TEXT, soundModel.keyphrases[0].text);
            boolean z = db.insertWithOnConflict(SoundModelContract.TABLE, null, values, 5) != -1;
            db.close();
            return z;
        }
    }

    public boolean deleteKeyphraseSoundModel(int keyphraseId, int userHandle, String bcp47Locale) {
        String bcp47Locale2 = Locale.forLanguageTag(bcp47Locale).toLanguageTag();
        synchronized (this) {
            SoundTrigger.KeyphraseSoundModel soundModel = getKeyphraseSoundModel(keyphraseId, userHandle, bcp47Locale2);
            if (soundModel == null) {
                return false;
            }
            SQLiteDatabase db = getWritableDatabase();
            String soundModelClause = "model_uuid='" + soundModel.uuid.toString() + "'";
            boolean z = db.delete(SoundModelContract.TABLE, soundModelClause, null) != 0;
            db.close();
            return z;
        }
    }

    public SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, int userHandle, String bcp47Locale) {
        String bcp47Locale2;
        String vendorUuidString;
        boolean isAvailableForCurrentUser;
        String bcp47Locale3 = Locale.forLanguageTag(bcp47Locale).toLanguageTag();
        synchronized (this) {
            try {
                try {
                    String selectQuery = "SELECT  * FROM sound_model WHERE keyphrase_id= '" + keyphraseId + "' AND " + SoundModelContract.KEY_LOCALE + "='" + bcp47Locale3 + "'";
                    SQLiteDatabase db = getReadableDatabase();
                    Cursor c = db.rawQuery(selectQuery, null);
                    try {
                        if (c.moveToFirst()) {
                            while (true) {
                                int type = c.getInt(c.getColumnIndex("type"));
                                if (type != 0) {
                                    bcp47Locale2 = bcp47Locale3;
                                } else {
                                    String modelUuid = c.getString(c.getColumnIndex("model_uuid"));
                                    if (modelUuid == null) {
                                        try {
                                            Slog.w(TAG, "Ignoring SoundModel since it doesn't specify an ID");
                                            bcp47Locale2 = bcp47Locale3;
                                        } catch (Throwable th) {
                                            th = th;
                                            c.close();
                                            db.close();
                                            throw th;
                                        }
                                    } else {
                                        int vendorUuidColumn = c.getColumnIndex("vendor_uuid");
                                        if (vendorUuidColumn != -1) {
                                            String vendorUuidString2 = c.getString(vendorUuidColumn);
                                            vendorUuidString = vendorUuidString2;
                                        } else {
                                            vendorUuidString = null;
                                        }
                                        byte[] data = c.getBlob(c.getColumnIndex("data"));
                                        int recognitionModes = c.getInt(c.getColumnIndex(SoundModelContract.KEY_RECOGNITION_MODES));
                                        int[] users = getArrayForCommaSeparatedString(c.getString(c.getColumnIndex(SoundModelContract.KEY_USERS)));
                                        String modelLocale = c.getString(c.getColumnIndex(SoundModelContract.KEY_LOCALE));
                                        String text = c.getString(c.getColumnIndex(SoundModelContract.KEY_HINT_TEXT));
                                        if (users == null) {
                                            Slog.w(TAG, "Ignoring SoundModel since it doesn't specify users");
                                            bcp47Locale2 = bcp47Locale3;
                                        } else {
                                            int length = users.length;
                                            int i = 0;
                                            while (true) {
                                                if (i >= length) {
                                                    bcp47Locale2 = bcp47Locale3;
                                                    isAvailableForCurrentUser = false;
                                                    break;
                                                }
                                                int user = users[i];
                                                bcp47Locale2 = bcp47Locale3;
                                                if (userHandle == user) {
                                                    isAvailableForCurrentUser = true;
                                                    break;
                                                }
                                                i++;
                                                bcp47Locale3 = bcp47Locale2;
                                            }
                                            if (isAvailableForCurrentUser) {
                                                SoundTrigger.Keyphrase[] keyphrases = {new SoundTrigger.Keyphrase(keyphraseId, recognitionModes, modelLocale, text, users)};
                                                UUID vendorUuid = vendorUuidString != null ? UUID.fromString(vendorUuidString) : null;
                                                SoundTrigger.KeyphraseSoundModel model = new SoundTrigger.KeyphraseSoundModel(UUID.fromString(modelUuid), vendorUuid, data, keyphrases);
                                                c.close();
                                                db.close();
                                                return model;
                                            }
                                        }
                                    }
                                }
                                try {
                                    boolean isAvailableForCurrentUser2 = c.moveToNext();
                                    if (!isAvailableForCurrentUser2) {
                                        break;
                                    }
                                    bcp47Locale3 = bcp47Locale2;
                                } catch (Throwable th2) {
                                    th = th2;
                                    c.close();
                                    db.close();
                                    throw th;
                                }
                            }
                        }
                        Slog.w(TAG, "No SoundModel available for the given keyphrase");
                        c.close();
                        db.close();
                        return null;
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                    throw th;
                }
            } catch (Throwable th5) {
                th = th5;
                throw th;
            }
        }
    }

    private static String getCommaSeparatedString(int[] users) {
        if (users == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < users.length; i++) {
            if (i != 0) {
                sb.append(',');
            }
            sb.append(users[i]);
        }
        return sb.toString();
    }

    private static int[] getArrayForCommaSeparatedString(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String[] usersStr = text.split(",");
        int[] users = new int[usersStr.length];
        for (int i = 0; i < usersStr.length; i++) {
            users[i] = Integer.parseInt(usersStr[i]);
        }
        return users;
    }

    /* loaded from: classes2.dex */
    private static class SoundModelRecord {
        public final byte[] data;
        public final String hintText;
        public final int keyphraseId;
        public final String locale;
        public final String modelUuid;
        public final int recognitionModes;
        public final int type;
        public final String users;
        public final String vendorUuid;

        public SoundModelRecord(int version, Cursor c) {
            this.modelUuid = c.getString(c.getColumnIndex("model_uuid"));
            if (version >= 5) {
                this.vendorUuid = c.getString(c.getColumnIndex("vendor_uuid"));
            } else {
                this.vendorUuid = null;
            }
            this.keyphraseId = c.getInt(c.getColumnIndex(SoundModelContract.KEY_KEYPHRASE_ID));
            this.type = c.getInt(c.getColumnIndex("type"));
            this.data = c.getBlob(c.getColumnIndex("data"));
            this.recognitionModes = c.getInt(c.getColumnIndex(SoundModelContract.KEY_RECOGNITION_MODES));
            this.locale = c.getString(c.getColumnIndex(SoundModelContract.KEY_LOCALE));
            this.hintText = c.getString(c.getColumnIndex(SoundModelContract.KEY_HINT_TEXT));
            this.users = c.getString(c.getColumnIndex(SoundModelContract.KEY_USERS));
        }

        private boolean V6PrimaryKeyMatches(SoundModelRecord record) {
            return this.keyphraseId == record.keyphraseId && stringComparisonHelper(this.locale, record.locale) && stringComparisonHelper(this.users, record.users);
        }

        public boolean ifViolatesV6PrimaryKeyIsFirstOfAnyDuplicates(List<SoundModelRecord> records) {
            for (SoundModelRecord record : records) {
                if (this != record && V6PrimaryKeyMatches(record) && !Arrays.equals(this.data, record.data)) {
                    return false;
                }
            }
            Iterator<SoundModelRecord> it = records.iterator();
            while (it.hasNext()) {
                SoundModelRecord record2 = it.next();
                if (V6PrimaryKeyMatches(record2)) {
                    return this == record2;
                }
            }
            return true;
        }

        public long writeToDatabase(int version, SQLiteDatabase db) {
            ContentValues values = new ContentValues();
            values.put("model_uuid", this.modelUuid);
            if (version >= 5) {
                values.put("vendor_uuid", this.vendorUuid);
            }
            values.put(SoundModelContract.KEY_KEYPHRASE_ID, Integer.valueOf(this.keyphraseId));
            values.put("type", Integer.valueOf(this.type));
            values.put("data", this.data);
            values.put(SoundModelContract.KEY_RECOGNITION_MODES, Integer.valueOf(this.recognitionModes));
            values.put(SoundModelContract.KEY_LOCALE, this.locale);
            values.put(SoundModelContract.KEY_HINT_TEXT, this.hintText);
            values.put(SoundModelContract.KEY_USERS, this.users);
            return db.insertWithOnConflict(SoundModelContract.TABLE, null, values, 5);
        }

        private static boolean stringComparisonHelper(String a, String b) {
            if (a != null) {
                return a.equals(b);
            }
            return a == b;
        }
    }
}
