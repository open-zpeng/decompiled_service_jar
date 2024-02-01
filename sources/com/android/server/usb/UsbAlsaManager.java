package com.android.server.usb;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.media.IAudioService;
import android.os.Bundle;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import com.android.internal.alsa.AlsaCardsParser;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public final class UsbAlsaManager {
    private static final String ALSA_DIRECTORY = "/dev/snd/";
    private static final boolean DEBUG = false;
    private static final String TAG = UsbAlsaManager.class.getSimpleName();
    private IAudioService mAudioService;
    private final Context mContext;
    private final boolean mHasMidiFeature;
    private UsbAlsaDevice mSelectedDevice;
    private final AlsaCardsParser mCardsParser = new AlsaCardsParser();
    private final ArrayList<UsbAlsaDevice> mAlsaDevices = new ArrayList<>();
    private final HashMap<String, UsbMidiDevice> mMidiDevices = new HashMap<>();
    private UsbMidiDevice mPeripheralMidiDevice = null;

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbAlsaManager(Context context) {
        this.mContext = context;
        this.mHasMidiFeature = context.getPackageManager().hasSystemFeature("android.software.midi");
    }

    public void systemReady() {
        this.mAudioService = IAudioService.Stub.asInterface(ServiceManager.getService("audio"));
    }

    private synchronized void selectAlsaDevice(UsbAlsaDevice alsaDevice) {
        if (this.mSelectedDevice != null) {
            deselectAlsaDevice();
        }
        int isDisabled = Settings.Secure.getInt(this.mContext.getContentResolver(), "usb_audio_automatic_routing_disabled", 0);
        if (isDisabled != 0) {
            return;
        }
        this.mSelectedDevice = alsaDevice;
        alsaDevice.start();
    }

    private synchronized void deselectAlsaDevice() {
        if (this.mSelectedDevice != null) {
            this.mSelectedDevice.stop();
            this.mSelectedDevice = null;
        }
    }

    private int getAlsaDeviceListIndexFor(String deviceAddress) {
        for (int index = 0; index < this.mAlsaDevices.size(); index++) {
            if (this.mAlsaDevices.get(index).getDeviceAddress().equals(deviceAddress)) {
                return index;
            }
        }
        return -1;
    }

    private UsbAlsaDevice removeAlsaDeviceFromList(String deviceAddress) {
        int index = getAlsaDeviceListIndexFor(deviceAddress);
        if (index > -1) {
            return this.mAlsaDevices.remove(index);
        }
        return null;
    }

    UsbAlsaDevice selectDefaultDevice() {
        if (this.mAlsaDevices.size() > 0) {
            UsbAlsaDevice alsaDevice = this.mAlsaDevices.get(0);
            if (alsaDevice != null) {
                selectAlsaDevice(alsaDevice);
            }
            return alsaDevice;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void usbDeviceAdded(String deviceAddress, UsbDevice usbDevice, UsbDescriptorParser parser) {
        String name;
        this.mCardsParser.scan();
        AlsaCardsParser.AlsaCardRecord cardRec = this.mCardsParser.findCardNumFor(deviceAddress);
        if (cardRec == null) {
            return;
        }
        boolean hasInput = parser.hasInput();
        boolean hasOutput = parser.hasOutput();
        if (hasInput || hasOutput) {
            boolean isInputHeadset = parser.isInputHeadset();
            boolean isOutputHeadset = parser.isOutputHeadset();
            if (this.mAudioService == null) {
                Slog.e(TAG, "no AudioService");
                return;
            }
            UsbAlsaDevice alsaDevice = new UsbAlsaDevice(this.mAudioService, cardRec.getCardNum(), 0, deviceAddress, hasOutput, hasInput, isInputHeadset, isOutputHeadset);
            alsaDevice.setDeviceNameAndDescription(cardRec.getCardName(), cardRec.getCardDescription());
            this.mAlsaDevices.add(0, alsaDevice);
            selectAlsaDevice(alsaDevice);
        }
        boolean hasMidi = parser.hasMIDIInterface();
        if (hasMidi && this.mHasMidiFeature) {
            Bundle properties = new Bundle();
            String manufacturer = usbDevice.getManufacturerName();
            String product = usbDevice.getProductName();
            String version = usbDevice.getVersion();
            if (manufacturer == null || manufacturer.isEmpty()) {
                name = product;
            } else if (product == null || product.isEmpty()) {
                name = manufacturer;
            } else {
                name = manufacturer + " " + product;
            }
            properties.putString(com.android.server.pm.Settings.ATTR_NAME, name);
            properties.putString("manufacturer", manufacturer);
            properties.putString("product", product);
            properties.putString("version", version);
            properties.putString("serial_number", usbDevice.getSerialNumber());
            properties.putInt("alsa_card", cardRec.getCardNum());
            properties.putInt("alsa_device", 0);
            properties.putParcelable("usb_device", usbDevice);
            UsbMidiDevice usbMidiDevice = UsbMidiDevice.create(this.mContext, properties, cardRec.getCardNum(), 0);
            if (usbMidiDevice != null) {
                this.mMidiDevices.put(deviceAddress, usbMidiDevice);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void usbDeviceRemoved(String deviceAddress) {
        UsbAlsaDevice alsaDevice = removeAlsaDeviceFromList(deviceAddress);
        String str = TAG;
        Slog.i(str, "USB Audio Device Removed: " + alsaDevice);
        if (alsaDevice != null && alsaDevice == this.mSelectedDevice) {
            deselectAlsaDevice();
            selectDefaultDevice();
        }
        UsbMidiDevice usbMidiDevice = this.mMidiDevices.remove(deviceAddress);
        if (usbMidiDevice != null) {
            String str2 = TAG;
            Slog.i(str2, "USB MIDI Device Removed: " + usbMidiDevice);
            IoUtils.closeQuietly(usbMidiDevice);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPeripheralMidiState(boolean enabled, int card, int device) {
        if (!this.mHasMidiFeature) {
            return;
        }
        if (!enabled || this.mPeripheralMidiDevice != null) {
            if (!enabled && this.mPeripheralMidiDevice != null) {
                IoUtils.closeQuietly(this.mPeripheralMidiDevice);
                this.mPeripheralMidiDevice = null;
                return;
            }
            return;
        }
        Bundle properties = new Bundle();
        Resources r = this.mContext.getResources();
        properties.putString(com.android.server.pm.Settings.ATTR_NAME, r.getString(17041016));
        properties.putString("manufacturer", r.getString(17041015));
        properties.putString("product", r.getString(17041017));
        properties.putInt("alsa_card", card);
        properties.putInt("alsa_device", device);
        this.mPeripheralMidiDevice = UsbMidiDevice.create(this.mContext, properties, card, device);
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        dump.write("cards_parser", 1120986464257L, this.mCardsParser.getScanStatus());
        Iterator<UsbAlsaDevice> it = this.mAlsaDevices.iterator();
        while (it.hasNext()) {
            UsbAlsaDevice usbAlsaDevice = it.next();
            usbAlsaDevice.dump(dump, "alsa_devices", 2246267895810L);
        }
        for (String deviceAddr : this.mMidiDevices.keySet()) {
            this.mMidiDevices.get(deviceAddr).dump(deviceAddr, dump, "midi_devices", 2246267895811L);
        }
        dump.end(token);
    }
}
