package com.android.server.hdmi;

import com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem;
import com.android.server.hdmi.HdmiControlService;

/* loaded from: classes.dex */
public class DetectTvSystemAudioModeSupportAction extends HdmiCecFeatureAction {
    private static final int STATE_WAITING_FOR_FEATURE_ABORT = 1;
    private HdmiCecLocalDeviceAudioSystem.TvSystemAudioModeSupportedCallback mCallback;
    private int mState;

    /* JADX INFO: Access modifiers changed from: package-private */
    public DetectTvSystemAudioModeSupportAction(HdmiCecLocalDevice source, HdmiCecLocalDeviceAudioSystem.TvSystemAudioModeSupportedCallback callback) {
        super(source);
        this.mCallback = callback;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    public boolean start() {
        this.mState = 1;
        addTimer(this.mState, 2000);
        sendSetSystemAudioMode();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    public boolean processCommand(HdmiCecMessage cmd) {
        if (cmd.getOpcode() == 0 && this.mState == 1 && (cmd.getParams()[0] & 255) == 114) {
            finishAction(false);
            return true;
        }
        return false;
    }

    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    void handleTimerEvent(int state) {
        int i = this.mState;
        if (i == state && i == 1) {
            finishAction(true);
        }
    }

    protected void sendSetSystemAudioMode() {
        sendCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(getSourceAddress(), 0, true), new HdmiControlService.SendMessageCallback() { // from class: com.android.server.hdmi.-$$Lambda$DetectTvSystemAudioModeSupportAction$9ZB9uijssEfI695RNRL5G3nnAaM
            @Override // com.android.server.hdmi.HdmiControlService.SendMessageCallback
            public final void onSendCompleted(int i) {
                DetectTvSystemAudioModeSupportAction.this.lambda$sendSetSystemAudioMode$0$DetectTvSystemAudioModeSupportAction(i);
            }
        });
    }

    public /* synthetic */ void lambda$sendSetSystemAudioMode$0$DetectTvSystemAudioModeSupportAction(int result) {
        if (result != 0) {
            finishAction(false);
        }
    }

    private void finishAction(boolean supported) {
        this.mCallback.onResult(supported);
        audioSystem().setTvSystemAudioModeSupport(supported);
        finish();
    }
}
