package com.android.server.usb;

import android.media.IAudioService;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.audio.AudioService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.Settings;
/* loaded from: classes.dex */
public final class UsbAlsaDevice {
    protected static final boolean DEBUG = false;
    private static final String TAG = "UsbAlsaDevice";
    private IAudioService mAudioService;
    private final int mCardNum;
    private final String mDeviceAddress;
    private final int mDeviceNum;
    private final boolean mHasInput;
    private final boolean mHasOutput;
    private int mInputState;
    private final boolean mIsInputHeadset;
    private final boolean mIsOutputHeadset;
    private UsbAlsaJackDetector mJackDetector;
    private int mOutputState;
    private boolean mSelected = false;
    private String mDeviceName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    private String mDeviceDescription = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;

    public UsbAlsaDevice(IAudioService audioService, int card, int device, String deviceAddress, boolean hasOutput, boolean hasInput, boolean isInputHeadset, boolean isOutputHeadset) {
        this.mAudioService = audioService;
        this.mCardNum = card;
        this.mDeviceNum = device;
        this.mDeviceAddress = deviceAddress;
        this.mHasOutput = hasOutput;
        this.mHasInput = hasInput;
        this.mIsInputHeadset = isInputHeadset;
        this.mIsOutputHeadset = isOutputHeadset;
    }

    public int getCardNum() {
        return this.mCardNum;
    }

    public int getDeviceNum() {
        return this.mDeviceNum;
    }

    public String getDeviceAddress() {
        return this.mDeviceAddress;
    }

    public String getAlsaCardDeviceString() {
        if (this.mCardNum < 0 || this.mDeviceNum < 0) {
            Slog.e(TAG, "Invalid alsa card or device alsaCard: " + this.mCardNum + " alsaDevice: " + this.mDeviceNum);
            return null;
        }
        return AudioService.makeAlsaAddressString(this.mCardNum, this.mDeviceNum);
    }

    public boolean hasOutput() {
        return this.mHasOutput;
    }

    public boolean hasInput() {
        return this.mHasInput;
    }

    public boolean isInputHeadset() {
        return this.mIsInputHeadset;
    }

    public boolean isOutputHeadset() {
        return this.mIsOutputHeadset;
    }

    private synchronized boolean isInputJackConnected() {
        if (this.mJackDetector == null) {
            return true;
        }
        return this.mJackDetector.isInputJackConnected();
    }

    private synchronized boolean isOutputJackConnected() {
        if (this.mJackDetector == null) {
            return true;
        }
        return this.mJackDetector.isOutputJackConnected();
    }

    private synchronized void startJackDetect() {
        this.mJackDetector = UsbAlsaJackDetector.startJackDetect(this);
    }

    private synchronized void stopJackDetect() {
        if (this.mJackDetector != null) {
            this.mJackDetector.pleaseStop();
        }
        this.mJackDetector = null;
    }

    public synchronized void start() {
        this.mSelected = true;
        this.mInputState = 0;
        this.mOutputState = 0;
        startJackDetect();
        updateWiredDeviceConnectionState(true);
    }

    public synchronized void stop() {
        stopJackDetect();
        updateWiredDeviceConnectionState(false);
        this.mSelected = false;
    }

    public synchronized void updateWiredDeviceConnectionState(boolean enable) {
        if (!this.mSelected) {
            Slog.e(TAG, "updateWiredDeviceConnectionState on unselected AlsaDevice!");
            return;
        }
        String alsaCardDeviceString = getAlsaCardDeviceString();
        if (alsaCardDeviceString == null) {
            return;
        }
        try {
            int inputState = 0;
            if (this.mHasOutput) {
                int device = this.mIsOutputHeadset ? 67108864 : 16384;
                boolean connected = isOutputJackConnected();
                Slog.i(TAG, "OUTPUT JACK connected: " + connected);
                int outputState = (enable && connected) ? 1 : 0;
                if (outputState != this.mOutputState) {
                    this.mOutputState = outputState;
                    this.mAudioService.setWiredDeviceConnectionState(device, outputState, alsaCardDeviceString, this.mDeviceName, TAG);
                }
            }
            if (this.mHasInput) {
                int device2 = this.mIsInputHeadset ? -2113929216 : -2147479552;
                boolean connected2 = isInputJackConnected();
                Slog.i(TAG, "INPUT JACK connected: " + connected2);
                if (enable && connected2) {
                    inputState = 1;
                }
                if (inputState != this.mInputState) {
                    this.mInputState = inputState;
                    this.mAudioService.setWiredDeviceConnectionState(device2, inputState, alsaCardDeviceString, this.mDeviceName, TAG);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in setWiredDeviceConnectionState");
        }
    }

    public synchronized String toString() {
        return "UsbAlsaDevice: [card: " + this.mCardNum + ", device: " + this.mDeviceNum + ", name: " + this.mDeviceName + ", hasOutput: " + this.mHasOutput + ", hasInput: " + this.mHasInput + "]";
    }

    public synchronized void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        dump.write("card", 1120986464257L, this.mCardNum);
        dump.write("device", 1120986464258L, this.mDeviceNum);
        dump.write(Settings.ATTR_NAME, 1138166333443L, this.mDeviceName);
        dump.write("has_output", 1133871366148L, this.mHasOutput);
        dump.write("has_input", 1133871366149L, this.mHasInput);
        dump.write(AudioService.CONNECT_INTENT_KEY_ADDRESS, 1138166333446L, this.mDeviceAddress);
        dump.end(token);
    }

    synchronized String toShortString() {
        return "[card:" + this.mCardNum + " device:" + this.mDeviceNum + " " + this.mDeviceName + "]";
    }

    synchronized String getDeviceName() {
        return this.mDeviceName;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void setDeviceNameAndDescription(String deviceName, String deviceDescription) {
        this.mDeviceName = deviceName;
        this.mDeviceDescription = deviceDescription;
    }

    public boolean equals(Object obj) {
        if (obj instanceof UsbAlsaDevice) {
            UsbAlsaDevice other = (UsbAlsaDevice) obj;
            return this.mCardNum == other.mCardNum && this.mDeviceNum == other.mDeviceNum && this.mHasOutput == other.mHasOutput && this.mHasInput == other.mHasInput && this.mIsInputHeadset == other.mIsInputHeadset && this.mIsOutputHeadset == other.mIsOutputHeadset;
        }
        return false;
    }

    public int hashCode() {
        int result = (31 * 1) + this.mCardNum;
        return (31 * ((31 * ((31 * ((31 * ((31 * result) + this.mDeviceNum)) + (!this.mHasOutput ? 1 : 0))) + (!this.mHasInput ? 1 : 0))) + (!this.mIsInputHeadset ? 1 : 0))) + (!this.mIsOutputHeadset ? 1 : 0);
    }
}
