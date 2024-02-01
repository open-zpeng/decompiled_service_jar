package android.hardware.health.V1_0;

import android.os.HidlSupport;
import android.os.HwBlob;
import android.os.HwParcel;
import com.android.server.display.DisplayTransformManager;
import java.util.ArrayList;
import java.util.Objects;
/* loaded from: classes.dex */
public final class HealthConfig {
    public int periodicChoresIntervalFast;
    public int periodicChoresIntervalSlow;
    public String batteryStatusPath = new String();
    public String batteryHealthPath = new String();
    public String batteryPresentPath = new String();
    public String batteryCapacityPath = new String();
    public String batteryVoltagePath = new String();
    public String batteryTemperaturePath = new String();
    public String batteryTechnologyPath = new String();
    public String batteryCurrentNowPath = new String();
    public String batteryCurrentAvgPath = new String();
    public String batteryChargeCounterPath = new String();
    public String batteryFullChargePath = new String();
    public String batteryCycleCountPath = new String();

    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (otherObject == null || otherObject.getClass() != HealthConfig.class) {
            return false;
        }
        HealthConfig other = (HealthConfig) otherObject;
        if (this.periodicChoresIntervalFast == other.periodicChoresIntervalFast && this.periodicChoresIntervalSlow == other.periodicChoresIntervalSlow && HidlSupport.deepEquals(this.batteryStatusPath, other.batteryStatusPath) && HidlSupport.deepEquals(this.batteryHealthPath, other.batteryHealthPath) && HidlSupport.deepEquals(this.batteryPresentPath, other.batteryPresentPath) && HidlSupport.deepEquals(this.batteryCapacityPath, other.batteryCapacityPath) && HidlSupport.deepEquals(this.batteryVoltagePath, other.batteryVoltagePath) && HidlSupport.deepEquals(this.batteryTemperaturePath, other.batteryTemperaturePath) && HidlSupport.deepEquals(this.batteryTechnologyPath, other.batteryTechnologyPath) && HidlSupport.deepEquals(this.batteryCurrentNowPath, other.batteryCurrentNowPath) && HidlSupport.deepEquals(this.batteryCurrentAvgPath, other.batteryCurrentAvgPath) && HidlSupport.deepEquals(this.batteryChargeCounterPath, other.batteryChargeCounterPath) && HidlSupport.deepEquals(this.batteryFullChargePath, other.batteryFullChargePath) && HidlSupport.deepEquals(this.batteryCycleCountPath, other.batteryCycleCountPath)) {
            return true;
        }
        return false;
    }

    public final int hashCode() {
        return Objects.hash(Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.periodicChoresIntervalFast))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.periodicChoresIntervalSlow))), Integer.valueOf(HidlSupport.deepHashCode(this.batteryStatusPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryHealthPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryPresentPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryCapacityPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryVoltagePath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryTemperaturePath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryTechnologyPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryCurrentNowPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryCurrentAvgPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryChargeCounterPath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryFullChargePath)), Integer.valueOf(HidlSupport.deepHashCode(this.batteryCycleCountPath)));
    }

    public final String toString() {
        return "{.periodicChoresIntervalFast = " + this.periodicChoresIntervalFast + ", .periodicChoresIntervalSlow = " + this.periodicChoresIntervalSlow + ", .batteryStatusPath = " + this.batteryStatusPath + ", .batteryHealthPath = " + this.batteryHealthPath + ", .batteryPresentPath = " + this.batteryPresentPath + ", .batteryCapacityPath = " + this.batteryCapacityPath + ", .batteryVoltagePath = " + this.batteryVoltagePath + ", .batteryTemperaturePath = " + this.batteryTemperaturePath + ", .batteryTechnologyPath = " + this.batteryTechnologyPath + ", .batteryCurrentNowPath = " + this.batteryCurrentNowPath + ", .batteryCurrentAvgPath = " + this.batteryCurrentAvgPath + ", .batteryChargeCounterPath = " + this.batteryChargeCounterPath + ", .batteryFullChargePath = " + this.batteryFullChargePath + ", .batteryCycleCountPath = " + this.batteryCycleCountPath + "}";
    }

    public final void readFromParcel(HwParcel parcel) {
        HwBlob blob = parcel.readBuffer(200L);
        readEmbeddedFromParcel(parcel, blob, 0L);
    }

    public static final ArrayList<HealthConfig> readVectorFromParcel(HwParcel parcel) {
        ArrayList<HealthConfig> _hidl_vec = new ArrayList<>();
        HwBlob _hidl_blob = parcel.readBuffer(16L);
        int _hidl_vec_size = _hidl_blob.getInt32(8L);
        HwBlob childBlob = parcel.readEmbeddedBuffer(_hidl_vec_size * DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE, _hidl_blob.handle(), 0L, true);
        _hidl_vec.clear();
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            HealthConfig _hidl_vec_element = new HealthConfig();
            _hidl_vec_element.readEmbeddedFromParcel(parcel, childBlob, _hidl_index_0 * DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
            _hidl_vec.add(_hidl_vec_element);
        }
        return _hidl_vec;
    }

    public final void readEmbeddedFromParcel(HwParcel parcel, HwBlob _hidl_blob, long _hidl_offset) {
        this.periodicChoresIntervalFast = _hidl_blob.getInt32(_hidl_offset + 0);
        this.periodicChoresIntervalSlow = _hidl_blob.getInt32(_hidl_offset + 4);
        this.batteryStatusPath = _hidl_blob.getString(_hidl_offset + 8);
        parcel.readEmbeddedBuffer(this.batteryStatusPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 8 + 0, false);
        this.batteryHealthPath = _hidl_blob.getString(_hidl_offset + 24);
        parcel.readEmbeddedBuffer(this.batteryHealthPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 24 + 0, false);
        this.batteryPresentPath = _hidl_blob.getString(_hidl_offset + 40);
        parcel.readEmbeddedBuffer(this.batteryPresentPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 40 + 0, false);
        this.batteryCapacityPath = _hidl_blob.getString(_hidl_offset + 56);
        parcel.readEmbeddedBuffer(this.batteryCapacityPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 56 + 0, false);
        this.batteryVoltagePath = _hidl_blob.getString(_hidl_offset + 72);
        parcel.readEmbeddedBuffer(this.batteryVoltagePath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 72 + 0, false);
        this.batteryTemperaturePath = _hidl_blob.getString(_hidl_offset + 88);
        parcel.readEmbeddedBuffer(this.batteryTemperaturePath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 88 + 0, false);
        this.batteryTechnologyPath = _hidl_blob.getString(_hidl_offset + 104);
        parcel.readEmbeddedBuffer(this.batteryTechnologyPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 104 + 0, false);
        this.batteryCurrentNowPath = _hidl_blob.getString(_hidl_offset + 120);
        parcel.readEmbeddedBuffer(this.batteryCurrentNowPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 120 + 0, false);
        this.batteryCurrentAvgPath = _hidl_blob.getString(_hidl_offset + 136);
        parcel.readEmbeddedBuffer(this.batteryCurrentAvgPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 136 + 0, false);
        this.batteryChargeCounterPath = _hidl_blob.getString(_hidl_offset + 152);
        parcel.readEmbeddedBuffer(this.batteryChargeCounterPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 152 + 0, false);
        this.batteryFullChargePath = _hidl_blob.getString(_hidl_offset + 168);
        parcel.readEmbeddedBuffer(this.batteryFullChargePath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 168 + 0, false);
        this.batteryCycleCountPath = _hidl_blob.getString(_hidl_offset + 184);
        parcel.readEmbeddedBuffer(this.batteryCycleCountPath.getBytes().length + 1, _hidl_blob.handle(), _hidl_offset + 184 + 0, false);
    }

    public final void writeToParcel(HwParcel parcel) {
        HwBlob _hidl_blob = new HwBlob(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
        writeEmbeddedToBlob(_hidl_blob, 0L);
        parcel.writeBuffer(_hidl_blob);
    }

    public static final void writeVectorToParcel(HwParcel parcel, ArrayList<HealthConfig> _hidl_vec) {
        HwBlob _hidl_blob = new HwBlob(16);
        int _hidl_vec_size = _hidl_vec.size();
        _hidl_blob.putInt32(8L, _hidl_vec_size);
        _hidl_blob.putBool(12L, false);
        HwBlob childBlob = new HwBlob(_hidl_vec_size * DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            _hidl_vec.get(_hidl_index_0).writeEmbeddedToBlob(childBlob, _hidl_index_0 * DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
        }
        _hidl_blob.putBlob(0L, childBlob);
        parcel.writeBuffer(_hidl_blob);
    }

    public final void writeEmbeddedToBlob(HwBlob _hidl_blob, long _hidl_offset) {
        _hidl_blob.putInt32(0 + _hidl_offset, this.periodicChoresIntervalFast);
        _hidl_blob.putInt32(4 + _hidl_offset, this.periodicChoresIntervalSlow);
        _hidl_blob.putString(8 + _hidl_offset, this.batteryStatusPath);
        _hidl_blob.putString(24 + _hidl_offset, this.batteryHealthPath);
        _hidl_blob.putString(40 + _hidl_offset, this.batteryPresentPath);
        _hidl_blob.putString(56 + _hidl_offset, this.batteryCapacityPath);
        _hidl_blob.putString(72 + _hidl_offset, this.batteryVoltagePath);
        _hidl_blob.putString(88 + _hidl_offset, this.batteryTemperaturePath);
        _hidl_blob.putString(104 + _hidl_offset, this.batteryTechnologyPath);
        _hidl_blob.putString(120 + _hidl_offset, this.batteryCurrentNowPath);
        _hidl_blob.putString(136 + _hidl_offset, this.batteryCurrentAvgPath);
        _hidl_blob.putString(152 + _hidl_offset, this.batteryChargeCounterPath);
        _hidl_blob.putString(168 + _hidl_offset, this.batteryFullChargePath);
        _hidl_blob.putString(184 + _hidl_offset, this.batteryCycleCountPath);
    }
}
