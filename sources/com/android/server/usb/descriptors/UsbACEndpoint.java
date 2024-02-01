package com.android.server.usb.descriptors;

import android.util.Log;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public abstract class UsbACEndpoint extends UsbDescriptor {
    private static final String TAG = "UsbACEndpoint";
    protected final int mSubclass;
    protected byte mSubtype;

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbACEndpoint(int length, byte type, int subclass) {
        super(length, type);
        this.mSubclass = subclass;
    }

    public int getSubclass() {
        return this.mSubclass;
    }

    public byte getSubtype() {
        return this.mSubtype;
    }

    @Override // com.android.server.usb.descriptors.UsbDescriptor
    public int parseRawDescriptors(ByteStream stream) {
        this.mSubtype = stream.getByte();
        return this.mLength;
    }

    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser, int length, byte type) {
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        if (subClass != 1) {
            if (subClass != 2) {
                if (subClass == 3) {
                    return new UsbACMidiEndpoint(length, type, subClass);
                }
                Log.w(TAG, "Unknown Audio Class Endpoint id:0x" + Integer.toHexString(subClass));
                return null;
            }
            return new UsbACAudioStreamEndpoint(length, type, subClass);
        }
        return new UsbACAudioControlEndpoint(length, type, subClass);
    }
}
