package com.android.server.hdmi;

import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class OneTouchPlayAction extends HdmiCecFeatureAction {
    private static final int LOOP_COUNTER_MAX = 10;
    private static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;
    private static final String TAG = "OneTouchPlayAction";
    private final List<IHdmiControlCallback> mCallbacks;
    private int mPowerStatusCounter;
    private final int mTargetAddress;

    /* JADX INFO: Access modifiers changed from: package-private */
    public static OneTouchPlayAction create(HdmiCecLocalDeviceSource source, int targetAddress, IHdmiControlCallback callback) {
        if (source == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new OneTouchPlayAction(source, targetAddress, callback);
    }

    private OneTouchPlayAction(HdmiCecLocalDevice localDevice, int targetAddress, IHdmiControlCallback callback) {
        super(localDevice);
        this.mCallbacks = new ArrayList();
        this.mPowerStatusCounter = 0;
        this.mTargetAddress = targetAddress;
        addCallback(callback);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    public boolean start() {
        sendCommand(HdmiCecMessageBuilder.buildTextViewOn(getSourceAddress(), this.mTargetAddress));
        broadcastActiveSource();
        queryDevicePowerStatus();
        this.mState = 1;
        addTimer(this.mState, 2000);
        return true;
    }

    private void broadcastActiveSource() {
        HdmiCecLocalDeviceSource source = source();
        source.mService.setAndBroadcastActiveSourceFromOneDeviceType(this.mTargetAddress, getSourcePath());
        if (source.mService.audioSystem() != null) {
            source = source.mService.audioSystem();
        }
        if (source.getLocalActivePort() != 0) {
            source.switchInputOnReceivingNewActivePath(getSourcePath());
        }
        source.setRoutingPort(0);
        source.setLocalActivePort(0);
    }

    private void queryDevicePowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(), this.mTargetAddress));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    public boolean processCommand(HdmiCecMessage cmd) {
        if (this.mState == 1 && this.mTargetAddress == cmd.getSource() && cmd.getOpcode() == 144) {
            int status = cmd.getParams()[0];
            if (status == 0) {
                broadcastActiveSource();
                invokeCallback(0);
                finish();
            }
            return true;
        }
        return false;
    }

    @Override // com.android.server.hdmi.HdmiCecFeatureAction
    void handleTimerEvent(int state) {
        if (this.mState == state && state == 1) {
            int i = this.mPowerStatusCounter;
            this.mPowerStatusCounter = i + 1;
            if (i < 10) {
                queryDevicePowerStatus();
                addTimer(this.mState, 2000);
                return;
            }
            invokeCallback(1);
            finish();
        }
    }

    public void addCallback(IHdmiControlCallback callback) {
        this.mCallbacks.add(callback);
    }

    private void invokeCallback(int result) {
        try {
            for (IHdmiControlCallback callback : this.mCallbacks) {
                callback.onComplete(result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
