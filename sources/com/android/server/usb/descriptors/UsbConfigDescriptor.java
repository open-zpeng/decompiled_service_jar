package com.android.server.usb.descriptors;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbInterface;
import com.android.server.usb.descriptors.report.ReportCanvas;
import java.util.ArrayList;
/* loaded from: classes.dex */
public final class UsbConfigDescriptor extends UsbDescriptor {
    private static final boolean DEBUG = false;
    private static final String TAG = "UsbConfigDescriptor";
    private int mAttribs;
    private byte mConfigIndex;
    private int mConfigValue;
    private ArrayList<UsbInterfaceDescriptor> mInterfaceDescriptors;
    private int mMaxPower;
    private byte mNumInterfaces;
    private int mTotalLength;

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbConfigDescriptor(int length, byte type) {
        super(length, type);
        this.mInterfaceDescriptors = new ArrayList<>();
        this.mHierarchyLevel = 2;
    }

    public int getTotalLength() {
        return this.mTotalLength;
    }

    public byte getNumInterfaces() {
        return this.mNumInterfaces;
    }

    public int getConfigValue() {
        return this.mConfigValue;
    }

    public byte getConfigIndex() {
        return this.mConfigIndex;
    }

    public int getAttribs() {
        return this.mAttribs;
    }

    public int getMaxPower() {
        return this.mMaxPower;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addInterfaceDescriptor(UsbInterfaceDescriptor interfaceDesc) {
        this.mInterfaceDescriptors.add(interfaceDesc);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbConfiguration toAndroid(UsbDescriptorParser parser) {
        String name = parser.getDescriptorString(this.mConfigIndex);
        UsbConfiguration config = new UsbConfiguration(this.mConfigValue, name, this.mAttribs, this.mMaxPower);
        UsbInterface[] interfaces = new UsbInterface[this.mInterfaceDescriptors.size()];
        for (int index = 0; index < this.mInterfaceDescriptors.size(); index++) {
            interfaces[index] = this.mInterfaceDescriptors.get(index).toAndroid(parser);
        }
        config.setInterfaces(interfaces);
        return config;
    }

    @Override // com.android.server.usb.descriptors.UsbDescriptor
    public int parseRawDescriptors(ByteStream stream) {
        this.mTotalLength = stream.unpackUsbShort();
        this.mNumInterfaces = stream.getByte();
        this.mConfigValue = stream.getUnsignedByte();
        this.mConfigIndex = stream.getByte();
        this.mAttribs = stream.getUnsignedByte();
        this.mMaxPower = stream.getUnsignedByte();
        return this.mLength;
    }

    @Override // com.android.server.usb.descriptors.UsbDescriptor, com.android.server.usb.descriptors.report.Reporting
    public void report(ReportCanvas canvas) {
        super.report(canvas);
        canvas.openList();
        canvas.writeListItem("Config # " + getConfigValue());
        canvas.writeListItem(((int) getNumInterfaces()) + " Interfaces.");
        canvas.writeListItem("Attributes: " + ReportCanvas.getHexString(getAttribs()));
        canvas.closeList();
    }
}
