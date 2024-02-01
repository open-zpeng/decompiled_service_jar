package android.gsi;

import android.os.Parcel;
import android.os.Parcelable;

/* loaded from: classes.dex */
public class GsiInstallParams implements Parcelable {
    public static final Parcelable.Creator<GsiInstallParams> CREATOR = new Parcelable.Creator<GsiInstallParams>() { // from class: android.gsi.GsiInstallParams.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public GsiInstallParams createFromParcel(Parcel _aidl_source) {
            GsiInstallParams _aidl_out = new GsiInstallParams();
            _aidl_out.readFromParcel(_aidl_source);
            return _aidl_out;
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public GsiInstallParams[] newArray(int _aidl_size) {
            return new GsiInstallParams[_aidl_size];
        }
    };
    public long gsiSize;
    public String installDir;
    public long userdataSize;
    public boolean wipeUserdata;

    @Override // android.os.Parcelable
    public final void writeToParcel(Parcel _aidl_parcel, int _aidl_flag) {
        int _aidl_start_pos = _aidl_parcel.dataPosition();
        _aidl_parcel.writeInt(0);
        _aidl_parcel.writeString(this.installDir);
        _aidl_parcel.writeLong(this.gsiSize);
        _aidl_parcel.writeLong(this.userdataSize);
        _aidl_parcel.writeInt(this.wipeUserdata ? 1 : 0);
        int _aidl_end_pos = _aidl_parcel.dataPosition();
        _aidl_parcel.setDataPosition(_aidl_start_pos);
        _aidl_parcel.writeInt(_aidl_end_pos - _aidl_start_pos);
        _aidl_parcel.setDataPosition(_aidl_end_pos);
    }

    public final void readFromParcel(Parcel _aidl_parcel) {
        int _aidl_start_pos = _aidl_parcel.dataPosition();
        int _aidl_parcelable_size = _aidl_parcel.readInt();
        if (_aidl_parcelable_size < 0) {
            return;
        }
        try {
            this.installDir = _aidl_parcel.readString();
            if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) {
                return;
            }
            this.gsiSize = _aidl_parcel.readLong();
            if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) {
                return;
            }
            this.userdataSize = _aidl_parcel.readLong();
            if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) {
                return;
            }
            this.wipeUserdata = _aidl_parcel.readInt() != 0;
            if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) {
            }
        } finally {
            _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
        }
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }
}
