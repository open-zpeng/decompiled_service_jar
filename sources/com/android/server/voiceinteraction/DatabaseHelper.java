package com.android.server.voiceinteraction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import android.text.TextUtils;
import com.android.server.backup.BackupManagerConstants;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
/* loaded from: classes.dex */
public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String CREATE_TABLE_SOUND_MODEL = "CREATE TABLE sound_model(model_uuid TEXT,vendor_uuid TEXT,keyphrase_id INTEGER,type INTEGER,data BLOB,recognition_modes INTEGER,locale TEXT,hint_text TEXT,users TEXT,PRIMARY KEY (keyphrase_id,locale,users))";
    static final boolean DBG = false;
    private static final String NAME = "sound_model.db";
    static final String TAG = "SoundModelDBHelper";
    private static final int VERSION = 6;

    /* loaded from: classes.dex */
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

    /* JADX WARN: Removed duplicated region for block: B:21:0x005e  */
    @Override // android.database.sqlite.SQLiteOpenHelper
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void onUpgrade(android.database.sqlite.SQLiteDatabase r11, int r12, int r13) {
        /*
            r10 = this;
            r0 = 4
            if (r12 >= r0) goto Lc
            java.lang.String r0 = "DROP TABLE IF EXISTS sound_model"
            r11.execSQL(r0)
            r10.onCreate(r11)
            goto L1c
        Lc:
            if (r12 != r0) goto L1c
            java.lang.String r0 = "SoundModelDBHelper"
            java.lang.String r1 = "Adding vendor UUID column"
            android.util.Slog.d(r0, r1)
            java.lang.String r0 = "ALTER TABLE sound_model ADD COLUMN vendor_uuid TEXT"
            r11.execSQL(r0)
            int r12 = r12 + 1
        L1c:
            r0 = 5
            if (r12 != r0) goto Lb8
            java.lang.String r1 = "SELECT * FROM sound_model"
            r2 = 0
            android.database.Cursor r2 = r11.rawQuery(r1, r2)
            java.util.ArrayList r3 = new java.util.ArrayList
            r3.<init>()
            boolean r4 = r2.moveToFirst()     // Catch: java.lang.Throwable -> Lb3
            if (r4 == 0) goto L48
        L31:
            com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord r4 = new com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lb3
            r4.<init>(r0, r2)     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lb3
            r3.add(r4)     // Catch: java.lang.Exception -> L3a java.lang.Throwable -> Lb3
            goto L42
        L3a:
            r4 = move-exception
            java.lang.String r5 = "SoundModelDBHelper"
            java.lang.String r6 = "Failed to extract V5 record"
            android.util.Slog.e(r5, r6, r4)     // Catch: java.lang.Throwable -> Lb3
        L42:
            boolean r4 = r2.moveToNext()     // Catch: java.lang.Throwable -> Lb3
            if (r4 != 0) goto L31
        L48:
            r2.close()
            java.lang.String r0 = "DROP TABLE IF EXISTS sound_model"
            r11.execSQL(r0)
            r10.onCreate(r11)
            java.util.Iterator r0 = r3.iterator()
        L58:
            boolean r4 = r0.hasNext()
            if (r4 == 0) goto Lb0
            java.lang.Object r4 = r0.next()
            com.android.server.voiceinteraction.DatabaseHelper$SoundModelRecord r4 = (com.android.server.voiceinteraction.DatabaseHelper.SoundModelRecord) r4
            boolean r5 = r4.ifViolatesV6PrimaryKeyIsFirstOfAnyDuplicates(r3)
            if (r5 == 0) goto Laf
            r5 = 6
            long r5 = r4.writeToDatabase(r5, r11)     // Catch: java.lang.Exception -> L96
            r7 = -1
            int r7 = (r5 > r7 ? 1 : (r5 == r7 ? 0 : -1))
            if (r7 != 0) goto L95
            java.lang.String r7 = "SoundModelDBHelper"
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Exception -> L96
            r8.<init>()     // Catch: java.lang.Exception -> L96
            java.lang.String r9 = "Database write failed "
            r8.append(r9)     // Catch: java.lang.Exception -> L96
            java.lang.String r9 = r4.modelUuid     // Catch: java.lang.Exception -> L96
            r8.append(r9)     // Catch: java.lang.Exception -> L96
            java.lang.String r9 = ": "
            r8.append(r9)     // Catch: java.lang.Exception -> L96
            r8.append(r5)     // Catch: java.lang.Exception -> L96
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Exception -> L96
            android.util.Slog.e(r7, r8)     // Catch: java.lang.Exception -> L96
        L95:
            goto Laf
        L96:
            r5 = move-exception
            java.lang.String r6 = "SoundModelDBHelper"
            java.lang.StringBuilder r7 = new java.lang.StringBuilder
            r7.<init>()
            java.lang.String r8 = "Failed to update V6 record "
            r7.append(r8)
            java.lang.String r8 = r4.modelUuid
            r7.append(r8)
            java.lang.String r7 = r7.toString()
            android.util.Slog.e(r6, r7, r5)
        Laf:
            goto L58
        Lb0:
            int r12 = r12 + 1
            goto Lb8
        Lb3:
            r0 = move-exception
            r2.close()
            throw r0
        Lb8:
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

    /* JADX WARN: Removed duplicated region for block: B:42:0x0101 A[LOOP:0: B:8:0x0046->B:42:0x0101, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:77:0x0100 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int r26, int r27, java.lang.String r28) {
        /*
            Method dump skipped, instructions count: 347
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.voiceinteraction.DatabaseHelper.getKeyphraseSoundModel(int, int, java.lang.String):android.hardware.soundtrigger.SoundTrigger$KeyphraseSoundModel");
    }

    private static String getCommaSeparatedString(int[] users) {
        if (users == null) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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

    /* loaded from: classes.dex */
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
