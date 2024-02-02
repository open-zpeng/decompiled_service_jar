package com.android.server.hdmi;
/* loaded from: classes.dex */
abstract class RequestArcAction extends HdmiCecFeatureAction {
    protected static final int STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE = 1;
    private static final String TAG = "RequestArcAction";
    protected final int mAvrAddress;

    /* JADX INFO: Access modifiers changed from: package-private */
    public RequestArcAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source);
        HdmiUtils.verifyAddressType(getSourceAddress(), 0);
        HdmiUtils.verifyAddressType(avrAddress, 5);
        this.mAvrAddress = avrAddress;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    public boolean processCommand(HdmiCecMessage cmd) {
        if (this.mState == 1 && HdmiUtils.checkCommandSource(cmd, this.mAvrAddress, TAG)) {
            int opcode = cmd.getOpcode();
            if (opcode != 0) {
                return false;
            }
            int originalOpcode = cmd.getParams()[0] & 255;
            if (originalOpcode == 196) {
                disableArcTransmission();
                finish();
                return true;
            } else if (originalOpcode == 195) {
                tv().setArcStatus(false);
                finish();
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public final void disableArcTransmission() {
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(localDevice(), this.mAvrAddress, false);
        addAndStartAction(action);
    }

    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    final void handleTimerEvent(int state) {
        if (this.mState != state || state != 1) {
            return;
        }
        HdmiLogger.debug("[T] RequestArcAction.", new Object[0]);
        disableArcTransmission();
        finish();
    }
}
