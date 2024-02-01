package android.hardware.broadcastradio.V2_0;

import android.os.HidlSupport;
import android.os.HwBlob;
import android.os.HwParcel;
import java.util.ArrayList;
import java.util.Objects;
/* loaded from: classes.dex */
public final class ProgramInfo {
    public int infoFlags;
    public int signalQuality;
    public final ProgramSelector selector = new ProgramSelector();
    public final ProgramIdentifier logicallyTunedTo = new ProgramIdentifier();
    public final ProgramIdentifier physicallyTunedTo = new ProgramIdentifier();
    public final ArrayList<ProgramIdentifier> relatedContent = new ArrayList<>();
    public final ArrayList<Metadata> metadata = new ArrayList<>();
    public final ArrayList<VendorKeyValue> vendorInfo = new ArrayList<>();

    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (otherObject == null || otherObject.getClass() != ProgramInfo.class) {
            return false;
        }
        ProgramInfo other = (ProgramInfo) otherObject;
        if (HidlSupport.deepEquals(this.selector, other.selector) && HidlSupport.deepEquals(this.logicallyTunedTo, other.logicallyTunedTo) && HidlSupport.deepEquals(this.physicallyTunedTo, other.physicallyTunedTo) && HidlSupport.deepEquals(this.relatedContent, other.relatedContent) && HidlSupport.deepEquals(Integer.valueOf(this.infoFlags), Integer.valueOf(other.infoFlags)) && this.signalQuality == other.signalQuality && HidlSupport.deepEquals(this.metadata, other.metadata) && HidlSupport.deepEquals(this.vendorInfo, other.vendorInfo)) {
            return true;
        }
        return false;
    }

    public final int hashCode() {
        return Objects.hash(Integer.valueOf(HidlSupport.deepHashCode(this.selector)), Integer.valueOf(HidlSupport.deepHashCode(this.logicallyTunedTo)), Integer.valueOf(HidlSupport.deepHashCode(this.physicallyTunedTo)), Integer.valueOf(HidlSupport.deepHashCode(this.relatedContent)), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.infoFlags))), Integer.valueOf(HidlSupport.deepHashCode(Integer.valueOf(this.signalQuality))), Integer.valueOf(HidlSupport.deepHashCode(this.metadata)), Integer.valueOf(HidlSupport.deepHashCode(this.vendorInfo)));
    }

    public final String toString() {
        return "{.selector = " + this.selector + ", .logicallyTunedTo = " + this.logicallyTunedTo + ", .physicallyTunedTo = " + this.physicallyTunedTo + ", .relatedContent = " + this.relatedContent + ", .infoFlags = " + ProgramInfoFlags.dumpBitfield(this.infoFlags) + ", .signalQuality = " + this.signalQuality + ", .metadata = " + this.metadata + ", .vendorInfo = " + this.vendorInfo + "}";
    }

    public final void readFromParcel(HwParcel parcel) {
        HwBlob blob = parcel.readBuffer(120L);
        readEmbeddedFromParcel(parcel, blob, 0L);
    }

    public static final ArrayList<ProgramInfo> readVectorFromParcel(HwParcel parcel) {
        ArrayList<ProgramInfo> _hidl_vec = new ArrayList<>();
        HwBlob _hidl_blob = parcel.readBuffer(16L);
        int _hidl_vec_size = _hidl_blob.getInt32(8L);
        HwBlob childBlob = parcel.readEmbeddedBuffer(_hidl_vec_size * 120, _hidl_blob.handle(), 0L, true);
        _hidl_vec.clear();
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            ProgramInfo _hidl_vec_element = new ProgramInfo();
            _hidl_vec_element.readEmbeddedFromParcel(parcel, childBlob, _hidl_index_0 * 120);
            _hidl_vec.add(_hidl_vec_element);
        }
        return _hidl_vec;
    }

    public final void readEmbeddedFromParcel(HwParcel parcel, HwBlob _hidl_blob, long _hidl_offset) {
        this.selector.readEmbeddedFromParcel(parcel, _hidl_blob, _hidl_offset + 0);
        this.logicallyTunedTo.readEmbeddedFromParcel(parcel, _hidl_blob, _hidl_offset + 32);
        this.physicallyTunedTo.readEmbeddedFromParcel(parcel, _hidl_blob, _hidl_offset + 48);
        int _hidl_vec_size = _hidl_blob.getInt32(_hidl_offset + 64 + 8);
        HwBlob childBlob = parcel.readEmbeddedBuffer(_hidl_vec_size * 16, _hidl_blob.handle(), _hidl_offset + 64 + 0, true);
        this.relatedContent.clear();
        int _hidl_index_0 = 0;
        for (int _hidl_index_02 = 0; _hidl_index_02 < _hidl_vec_size; _hidl_index_02++) {
            ProgramIdentifier _hidl_vec_element = new ProgramIdentifier();
            _hidl_vec_element.readEmbeddedFromParcel(parcel, childBlob, _hidl_index_02 * 16);
            this.relatedContent.add(_hidl_vec_element);
        }
        this.infoFlags = _hidl_blob.getInt32(_hidl_offset + 80);
        this.signalQuality = _hidl_blob.getInt32(_hidl_offset + 84);
        int _hidl_vec_size2 = _hidl_blob.getInt32(_hidl_offset + 88 + 8);
        HwBlob childBlob2 = parcel.readEmbeddedBuffer(_hidl_vec_size2 * 32, _hidl_blob.handle(), _hidl_offset + 88 + 0, true);
        this.metadata.clear();
        for (int _hidl_index_03 = 0; _hidl_index_03 < _hidl_vec_size2; _hidl_index_03++) {
            Metadata _hidl_vec_element2 = new Metadata();
            _hidl_vec_element2.readEmbeddedFromParcel(parcel, childBlob2, _hidl_index_03 * 32);
            this.metadata.add(_hidl_vec_element2);
        }
        int _hidl_vec_size3 = _hidl_blob.getInt32(_hidl_offset + 104 + 8);
        HwBlob childBlob3 = parcel.readEmbeddedBuffer(_hidl_vec_size3 * 32, _hidl_blob.handle(), 0 + _hidl_offset + 104, true);
        this.vendorInfo.clear();
        while (true) {
            int _hidl_index_04 = _hidl_index_0;
            if (_hidl_index_04 < _hidl_vec_size3) {
                VendorKeyValue _hidl_vec_element3 = new VendorKeyValue();
                _hidl_vec_element3.readEmbeddedFromParcel(parcel, childBlob3, _hidl_index_04 * 32);
                this.vendorInfo.add(_hidl_vec_element3);
                _hidl_index_0 = _hidl_index_04 + 1;
            } else {
                return;
            }
        }
    }

    public final void writeToParcel(HwParcel parcel) {
        HwBlob _hidl_blob = new HwBlob(120);
        writeEmbeddedToBlob(_hidl_blob, 0L);
        parcel.writeBuffer(_hidl_blob);
    }

    public static final void writeVectorToParcel(HwParcel parcel, ArrayList<ProgramInfo> _hidl_vec) {
        HwBlob _hidl_blob = new HwBlob(16);
        int _hidl_vec_size = _hidl_vec.size();
        _hidl_blob.putInt32(8L, _hidl_vec_size);
        _hidl_blob.putBool(12L, false);
        HwBlob childBlob = new HwBlob(_hidl_vec_size * 120);
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            _hidl_vec.get(_hidl_index_0).writeEmbeddedToBlob(childBlob, _hidl_index_0 * 120);
        }
        _hidl_blob.putBlob(0L, childBlob);
        parcel.writeBuffer(_hidl_blob);
    }

    public final void writeEmbeddedToBlob(HwBlob _hidl_blob, long _hidl_offset) {
        this.selector.writeEmbeddedToBlob(_hidl_blob, _hidl_offset + 0);
        this.logicallyTunedTo.writeEmbeddedToBlob(_hidl_blob, _hidl_offset + 32);
        this.physicallyTunedTo.writeEmbeddedToBlob(_hidl_blob, _hidl_offset + 48);
        int _hidl_vec_size = this.relatedContent.size();
        _hidl_blob.putInt32(_hidl_offset + 64 + 8, _hidl_vec_size);
        _hidl_blob.putBool(_hidl_offset + 64 + 12, false);
        HwBlob childBlob = new HwBlob(_hidl_vec_size * 16);
        for (int _hidl_index_0 = 0; _hidl_index_0 < _hidl_vec_size; _hidl_index_0++) {
            this.relatedContent.get(_hidl_index_0).writeEmbeddedToBlob(childBlob, _hidl_index_0 * 16);
        }
        _hidl_blob.putBlob(_hidl_offset + 64 + 0, childBlob);
        _hidl_blob.putInt32(_hidl_offset + 80, this.infoFlags);
        _hidl_blob.putInt32(_hidl_offset + 84, this.signalQuality);
        int _hidl_vec_size2 = this.metadata.size();
        _hidl_blob.putInt32(_hidl_offset + 88 + 8, _hidl_vec_size2);
        _hidl_blob.putBool(_hidl_offset + 88 + 12, false);
        HwBlob childBlob2 = new HwBlob(_hidl_vec_size2 * 32);
        for (int _hidl_index_02 = 0; _hidl_index_02 < _hidl_vec_size2; _hidl_index_02++) {
            this.metadata.get(_hidl_index_02).writeEmbeddedToBlob(childBlob2, _hidl_index_02 * 32);
        }
        _hidl_blob.putBlob(_hidl_offset + 88 + 0, childBlob2);
        int _hidl_vec_size3 = this.vendorInfo.size();
        _hidl_blob.putInt32(_hidl_offset + 104 + 8, _hidl_vec_size3);
        int _hidl_index_03 = 0;
        _hidl_blob.putBool(_hidl_offset + 104 + 12, false);
        HwBlob childBlob3 = new HwBlob(_hidl_vec_size3 * 32);
        while (true) {
            int _hidl_index_04 = _hidl_index_03;
            if (_hidl_index_04 < _hidl_vec_size3) {
                this.vendorInfo.get(_hidl_index_04).writeEmbeddedToBlob(childBlob3, _hidl_index_04 * 32);
                _hidl_index_03 = _hidl_index_04 + 1;
            } else {
                _hidl_blob.putBlob(_hidl_offset + 104 + 0, childBlob3);
                return;
            }
        }
    }
}
