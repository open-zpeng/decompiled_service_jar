package com.android.server.hdmi;

import android.hardware.hdmi.HdmiDeviceInfo;
import java.util.ArrayList;
import java.util.Iterator;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class DelayedMessageBuffer {
    private final ArrayList<HdmiCecMessage> mBuffer = new ArrayList<>();
    private final HdmiCecLocalDevice mDevice;

    /* JADX INFO: Access modifiers changed from: package-private */
    public DelayedMessageBuffer(HdmiCecLocalDevice device) {
        this.mDevice = device;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:12:0x0024  */
    /* JADX WARN: Removed duplicated region for block: B:14:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void add(com.android.server.hdmi.HdmiCecMessage r4) {
        /*
            r3 = this;
            r0 = 1
            int r1 = r4.getOpcode()
            r2 = 114(0x72, float:1.6E-43)
            if (r1 == r2) goto L1c
            r2 = 130(0x82, float:1.82E-43)
            if (r1 == r2) goto L13
            r2 = 192(0xc0, float:2.69E-43)
            if (r1 == r2) goto L1c
            r0 = 0
            goto L22
        L13:
            r3.removeActiveSource()
            java.util.ArrayList<com.android.server.hdmi.HdmiCecMessage> r1 = r3.mBuffer
            r1.add(r4)
            goto L22
        L1c:
            java.util.ArrayList<com.android.server.hdmi.HdmiCecMessage> r1 = r3.mBuffer
            r1.add(r4)
        L22:
            if (r0 == 0) goto L3b
            java.lang.StringBuilder r1 = new java.lang.StringBuilder
            r1.<init>()
            java.lang.String r2 = "Buffering message:"
            r1.append(r2)
            r1.append(r4)
            java.lang.String r1 = r1.toString()
            r2 = 0
            java.lang.Object[] r2 = new java.lang.Object[r2]
            com.android.server.hdmi.HdmiLogger.debug(r1, r2)
        L3b:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.hdmi.DelayedMessageBuffer.add(com.android.server.hdmi.HdmiCecMessage):void");
    }

    private void removeActiveSource() {
        Iterator<HdmiCecMessage> iter = this.mBuffer.iterator();
        while (iter.hasNext()) {
            HdmiCecMessage message = iter.next();
            if (message.getOpcode() == 130) {
                iter.remove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBuffered(int opcode) {
        Iterator<HdmiCecMessage> it = this.mBuffer.iterator();
        while (it.hasNext()) {
            HdmiCecMessage message = it.next();
            if (message.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void processAllMessages() {
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(this.mBuffer);
        this.mBuffer.clear();
        Iterator<HdmiCecMessage> it = copiedBuffer.iterator();
        while (it.hasNext()) {
            HdmiCecMessage message = it.next();
            this.mDevice.onMessage(message);
            HdmiLogger.debug("Processing message:" + message, new Object[0]);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void processMessagesForDevice(int address) {
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(this.mBuffer);
        this.mBuffer.clear();
        HdmiLogger.debug("Checking message for address:" + address, new Object[0]);
        Iterator<HdmiCecMessage> it = copiedBuffer.iterator();
        while (it.hasNext()) {
            HdmiCecMessage message = it.next();
            if (message.getSource() != address) {
                this.mBuffer.add(message);
            } else if (message.getOpcode() == 130 && !this.mDevice.isInputReady(HdmiDeviceInfo.idForCecDevice(address))) {
                this.mBuffer.add(message);
            } else {
                this.mDevice.onMessage(message);
                HdmiLogger.debug("Processing message:" + message, new Object[0]);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void processActiveSource(int address) {
        ArrayList<HdmiCecMessage> copiedBuffer = new ArrayList<>(this.mBuffer);
        this.mBuffer.clear();
        Iterator<HdmiCecMessage> it = copiedBuffer.iterator();
        while (it.hasNext()) {
            HdmiCecMessage message = it.next();
            if (message.getOpcode() == 130 && message.getSource() == address) {
                this.mDevice.onMessage(message);
                HdmiLogger.debug("Processing message:" + message, new Object[0]);
            } else {
                this.mBuffer.add(message);
            }
        }
    }
}
