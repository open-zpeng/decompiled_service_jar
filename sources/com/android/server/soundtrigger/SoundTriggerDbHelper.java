package com.android.server.soundtrigger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import java.util.UUID;
/* loaded from: classes.dex */
public class SoundTriggerDbHelper extends SQLiteOpenHelper {
    private static final String CREATE_TABLE_ST_SOUND_MODEL = "CREATE TABLE st_sound_model(model_uuid TEXT PRIMARY KEY,vendor_uuid TEXT,data BLOB )";
    static final boolean DBG = false;
    private static final String NAME = "st_sound_model.db";
    static final String TAG = "SoundTriggerDbHelper";
    private static final int VERSION = 1;

    /* loaded from: classes.dex */
    public interface GenericSoundModelContract {
        public static final String KEY_DATA = "data";
        public static final String KEY_MODEL_UUID = "model_uuid";
        public static final String KEY_VENDOR_UUID = "vendor_uuid";
        public static final String TABLE = "st_sound_model";
    }

    public SoundTriggerDbHelper(Context context) {
        super(context, NAME, (SQLiteDatabase.CursorFactory) null, 1);
    }

    @Override // android.database.sqlite.SQLiteOpenHelper
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_ST_SOUND_MODEL);
    }

    @Override // android.database.sqlite.SQLiteOpenHelper
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS st_sound_model");
        onCreate(db);
    }

    public boolean updateGenericSoundModel(SoundTrigger.GenericSoundModel soundModel) {
        boolean z;
        synchronized (this) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("model_uuid", soundModel.uuid.toString());
            values.put("vendor_uuid", soundModel.vendorUuid.toString());
            values.put("data", soundModel.data);
            z = db.insertWithOnConflict(GenericSoundModelContract.TABLE, null, values, 5) != -1;
            db.close();
        }
        return z;
    }

    public SoundTrigger.GenericSoundModel getGenericSoundModel(UUID model_uuid) {
        synchronized (this) {
            String selectQuery = "SELECT  * FROM st_sound_model WHERE model_uuid= '" + model_uuid + "'";
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);
            if (c.moveToFirst()) {
                byte[] data = c.getBlob(c.getColumnIndex("data"));
                String vendor_uuid = c.getString(c.getColumnIndex("vendor_uuid"));
                SoundTrigger.GenericSoundModel genericSoundModel = new SoundTrigger.GenericSoundModel(model_uuid, UUID.fromString(vendor_uuid), data);
                c.close();
                db.close();
                return genericSoundModel;
            }
            c.close();
            db.close();
            return null;
        }
    }

    public boolean deleteGenericSoundModel(UUID model_uuid) {
        synchronized (this) {
            SoundTrigger.GenericSoundModel soundModel = getGenericSoundModel(model_uuid);
            if (soundModel == null) {
                return false;
            }
            SQLiteDatabase db = getWritableDatabase();
            String soundModelClause = "model_uuid='" + soundModel.uuid.toString() + "'";
            boolean z = db.delete(GenericSoundModelContract.TABLE, soundModelClause, null) != 0;
            db.close();
            return z;
        }
    }
}
