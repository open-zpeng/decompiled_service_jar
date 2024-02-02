package com.android.server.usb.descriptors;

import android.hardware.usb.UsbDevice;
import android.util.Log;
import java.util.ArrayList;
import java.util.Iterator;
/* loaded from: classes.dex */
public final class UsbDescriptorParser {
    private static final boolean DEBUG = false;
    private static final int DESCRIPTORS_ALLOC_SIZE = 128;
    private static final float IN_HEADSET_TRIGGER = 0.75f;
    private static final float OUT_HEADSET_TRIGGER = 0.75f;
    private static final String TAG = "UsbDescriptorParser";
    private int mACInterfacesSpec;
    private UsbConfigDescriptor mCurConfigDescriptor;
    private UsbInterfaceDescriptor mCurInterfaceDescriptor;
    private final ArrayList<UsbDescriptor> mDescriptors;
    private final String mDeviceAddr;
    private UsbDeviceDescriptor mDeviceDescriptor;

    private native String getDescriptorString_native(String str, int i);

    private native byte[] getRawDescriptors_native(String str);

    public UsbDescriptorParser(String deviceAddr, ArrayList<UsbDescriptor> descriptors) {
        this.mACInterfacesSpec = 256;
        this.mDeviceAddr = deviceAddr;
        this.mDescriptors = descriptors;
        this.mDeviceDescriptor = (UsbDeviceDescriptor) descriptors.get(0);
    }

    public UsbDescriptorParser(String deviceAddr, byte[] rawDescriptors) {
        this.mACInterfacesSpec = 256;
        this.mDeviceAddr = deviceAddr;
        this.mDescriptors = new ArrayList<>(128);
        parseDescriptors(rawDescriptors);
    }

    public String getDeviceAddr() {
        return this.mDeviceAddr;
    }

    public int getUsbSpec() {
        if (this.mDeviceDescriptor != null) {
            return this.mDeviceDescriptor.getSpec();
        }
        throw new IllegalArgumentException();
    }

    public void setACInterfaceSpec(int spec) {
        this.mACInterfacesSpec = spec;
    }

    public int getACInterfaceSpec() {
        return this.mACInterfacesSpec;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UsbDescriptorsStreamFormatException extends Exception {
        String mMessage;

        UsbDescriptorsStreamFormatException(String message) {
            this.mMessage = message;
        }

        @Override // java.lang.Throwable
        public String toString() {
            return "Descriptor Stream Format Exception: " + this.mMessage;
        }
    }

    private UsbDescriptor allocDescriptor(ByteStream stream) throws UsbDescriptorsStreamFormatException {
        stream.resetReadCount();
        int length = stream.getUnsignedByte();
        byte type = stream.getByte();
        UsbDescriptor descriptor = null;
        switch (type) {
            case 1:
                UsbDeviceDescriptor usbDeviceDescriptor = new UsbDeviceDescriptor(length, type);
                this.mDeviceDescriptor = usbDeviceDescriptor;
                descriptor = usbDeviceDescriptor;
                break;
            case 2:
                UsbConfigDescriptor usbConfigDescriptor = new UsbConfigDescriptor(length, type);
                this.mCurConfigDescriptor = usbConfigDescriptor;
                descriptor = usbConfigDescriptor;
                if (this.mDeviceDescriptor != null) {
                    this.mDeviceDescriptor.addConfigDescriptor(this.mCurConfigDescriptor);
                    break;
                } else {
                    Log.e(TAG, "Config Descriptor found with no associated Device Descriptor!");
                    throw new UsbDescriptorsStreamFormatException("Config Descriptor found with no associated Device Descriptor!");
                }
            case 4:
                UsbInterfaceDescriptor usbInterfaceDescriptor = new UsbInterfaceDescriptor(length, type);
                this.mCurInterfaceDescriptor = usbInterfaceDescriptor;
                descriptor = usbInterfaceDescriptor;
                if (this.mCurConfigDescriptor != null) {
                    this.mCurConfigDescriptor.addInterfaceDescriptor(this.mCurInterfaceDescriptor);
                    break;
                } else {
                    Log.e(TAG, "Interface Descriptor found with no associated Config Descriptor!");
                    throw new UsbDescriptorsStreamFormatException("Interface Descriptor found with no associated Config Descriptor!");
                }
            case 5:
                descriptor = new UsbEndpointDescriptor(length, type);
                if (this.mCurInterfaceDescriptor != null) {
                    this.mCurInterfaceDescriptor.addEndpointDescriptor((UsbEndpointDescriptor) descriptor);
                    break;
                } else {
                    Log.e(TAG, "Endpoint Descriptor found with no associated Interface Descriptor!");
                    throw new UsbDescriptorsStreamFormatException("Endpoint Descriptor found with no associated Interface Descriptor!");
                }
            case 11:
                descriptor = new UsbInterfaceAssoc(length, type);
                break;
            case 33:
                descriptor = new UsbHIDDescriptor(length, type);
                break;
            case 36:
                descriptor = UsbACInterface.allocDescriptor(this, stream, length, type);
                break;
            case 37:
                descriptor = UsbACEndpoint.allocDescriptor(this, length, type);
                break;
        }
        if (descriptor == null) {
            Log.i(TAG, "Unknown Descriptor len: " + length + " type:0x" + Integer.toHexString(type));
            UsbDescriptor descriptor2 = new UsbUnknown(length, type);
            return descriptor2;
        }
        return descriptor;
    }

    public UsbDeviceDescriptor getDeviceDescriptor() {
        return this.mDeviceDescriptor;
    }

    public UsbInterfaceDescriptor getCurInterface() {
        return this.mCurInterfaceDescriptor;
    }

    public void parseDescriptors(byte[] descriptors) {
        ByteStream stream = new ByteStream(descriptors);
        while (stream.available() > 0) {
            UsbDescriptor descriptor = null;
            try {
                descriptor = allocDescriptor(stream);
            } catch (Exception ex) {
                Log.e(TAG, "Exception allocating USB descriptor.", ex);
            }
            if (descriptor != null) {
                try {
                    try {
                        descriptor.parseRawDescriptors(stream);
                        descriptor.postParse(stream);
                    } catch (Exception ex2) {
                        Log.e(TAG, "Exception parsing USB descriptors.", ex2);
                        descriptor.setStatus(4);
                    }
                } finally {
                    this.mDescriptors.add(descriptor);
                }
            }
        }
    }

    public byte[] getRawDescriptors() {
        return getRawDescriptors_native(this.mDeviceAddr);
    }

    public String getDescriptorString(int stringId) {
        return getDescriptorString_native(this.mDeviceAddr, stringId);
    }

    public int getParsingSpec() {
        if (this.mDeviceDescriptor != null) {
            return this.mDeviceDescriptor.getSpec();
        }
        return 0;
    }

    public ArrayList<UsbDescriptor> getDescriptors() {
        return this.mDescriptors;
    }

    public UsbDevice toAndroidUsbDevice() {
        if (this.mDeviceDescriptor == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR - No Device Descriptor");
            return null;
        }
        UsbDevice device = this.mDeviceDescriptor.toAndroid(this);
        if (device == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR Creating Device");
        }
        return device;
    }

    public ArrayList<UsbDescriptor> getDescriptors(byte type) {
        ArrayList<UsbDescriptor> list = new ArrayList<>();
        Iterator<UsbDescriptor> it = this.mDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor.getType() == type) {
                list.add(descriptor);
            }
        }
        return list;
    }

    public ArrayList<UsbDescriptor> getInterfaceDescriptorsForClass(int usbClass) {
        ArrayList<UsbDescriptor> list = new ArrayList<>();
        Iterator<UsbDescriptor> it = this.mDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor.getType() == 4) {
                if (descriptor instanceof UsbInterfaceDescriptor) {
                    UsbInterfaceDescriptor intrDesc = (UsbInterfaceDescriptor) descriptor;
                    if (intrDesc.getUsbClass() == usbClass) {
                        list.add(descriptor);
                    }
                } else {
                    Log.w(TAG, "Unrecognized Interface l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        return list;
    }

    public ArrayList<UsbDescriptor> getACInterfaceDescriptors(byte subtype, int subclass) {
        ArrayList<UsbDescriptor> list = new ArrayList<>();
        Iterator<UsbDescriptor> it = this.mDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor.getType() == 36) {
                if (descriptor instanceof UsbACInterface) {
                    UsbACInterface acDescriptor = (UsbACInterface) descriptor;
                    if (acDescriptor.getSubtype() == subtype && acDescriptor.getSubclass() == subclass) {
                        list.add(descriptor);
                    }
                } else {
                    Log.w(TAG, "Unrecognized Audio Interface l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        return list;
    }

    public boolean hasInput() {
        ArrayList<UsbDescriptor> acDescriptors = getACInterfaceDescriptors((byte) 2, 1);
        Iterator<UsbDescriptor> it = acDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal inDescr = (UsbACTerminal) descriptor;
                int type = inDescr.getTerminalType();
                int terminalCategory = type & (-256);
                if (terminalCategory != 256 && terminalCategory != 768) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    public boolean hasOutput() {
        ArrayList<UsbDescriptor> acDescriptors = getACInterfaceDescriptors((byte) 3, 1);
        Iterator<UsbDescriptor> it = acDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                int type = outDescr.getTerminalType();
                int terminalCategory = type & (-256);
                if (terminalCategory != 256 && terminalCategory != 512) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    public boolean hasMic() {
        ArrayList<UsbDescriptor> acDescriptors = getACInterfaceDescriptors((byte) 2, 1);
        Iterator<UsbDescriptor> it = acDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal inDescr = (UsbACTerminal) descriptor;
                if (inDescr.getTerminalType() == 513 || inDescr.getTerminalType() == 1026 || inDescr.getTerminalType() == 1024 || inDescr.getTerminalType() == 1539) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    public boolean hasSpeaker() {
        ArrayList<UsbDescriptor> acDescriptors = getACInterfaceDescriptors((byte) 3, 1);
        Iterator<UsbDescriptor> it = acDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == 769 || outDescr.getTerminalType() == 770 || outDescr.getTerminalType() == 1026) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    public boolean hasAudioInterface() {
        ArrayList<UsbDescriptor> descriptors = getInterfaceDescriptorsForClass(1);
        return true ^ descriptors.isEmpty();
    }

    public boolean hasHIDInterface() {
        ArrayList<UsbDescriptor> descriptors = getInterfaceDescriptorsForClass(3);
        return !descriptors.isEmpty();
    }

    public boolean hasStorageInterface() {
        ArrayList<UsbDescriptor> descriptors = getInterfaceDescriptorsForClass(8);
        return !descriptors.isEmpty();
    }

    public boolean hasMIDIInterface() {
        ArrayList<UsbDescriptor> descriptors = getInterfaceDescriptorsForClass(1);
        Iterator<UsbDescriptor> it = descriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbInterfaceDescriptor) {
                UsbInterfaceDescriptor interfaceDescr = (UsbInterfaceDescriptor) descriptor;
                if (interfaceDescr.getUsbSubclass() == 3) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Class Interface l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    public float getInputHeadsetProbability() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }
        float probability = 0.0f;
        boolean hasMic = hasMic();
        boolean hasSpeaker = hasSpeaker();
        if (hasMic && hasSpeaker) {
            probability = 0.0f + 0.75f;
        }
        if (hasMic && hasHIDInterface()) {
            return probability + 0.25f;
        }
        return probability;
    }

    public boolean isInputHeadset() {
        return getInputHeadsetProbability() >= 0.75f;
    }

    public float getOutputHeadsetProbability() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }
        boolean hasSpeaker = false;
        ArrayList<UsbDescriptor> acDescriptors = getACInterfaceDescriptors((byte) 3, 1);
        Iterator<UsbDescriptor> it = acDescriptors.iterator();
        while (it.hasNext()) {
            UsbDescriptor descriptor = it.next();
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == 769 || outDescr.getTerminalType() == 770 || outDescr.getTerminalType() == 1026) {
                    hasSpeaker = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength() + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        float probability = hasSpeaker ? 0.0f + 0.75f : 0.0f;
        if (hasSpeaker && hasHIDInterface()) {
            return probability + 0.25f;
        }
        return probability;
    }

    public boolean isOutputHeadset() {
        return getOutputHeadsetProbability() >= 0.75f;
    }
}
