package com.android.server.usb;

import android.content.ComponentName;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.dump.DumpUtils;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.report.TextReportCanvas;
import com.android.server.usb.descriptors.tree.UsbDescriptorsTree;
import com.xiaopeng.server.input.xpInputManagerService;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
/* loaded from: classes.dex */
public class UsbHostManager {
    private static final int APPLE_INC_VID = 1452;
    private static final int APPLE_IPHONE4_PID = 4768;
    private static final int APPLE_IPHONE5_PID = 4776;
    private static final boolean DEBUG = false;
    private static final int LINUX_FOUNDATION_VID = 7531;
    private static final int MAX_CONNECT_RECORDS = 32;
    private static final int QUECTEL_AG35_PID = 1077;
    private static final int QUECTEL_AG55_PID = 1109;
    private static final int QUECTEL_VID = 11388;
    private final Context mContext;
    @GuardedBy("mSettingsLock")
    private UsbProfileGroupSettingsManager mCurrentSettings;
    private final String[] mHostBlacklist;
    private ConnectionRecord mLastConnect;
    private int mNumConnects;
    private final UsbSettingsManager mSettingsManager;
    private final UsbAlsaManager mUsbAlsaManager;
    @GuardedBy("mHandlerLock")
    private ComponentName mUsbDeviceConnectionHandler;
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static boolean isHubMount = false;
    private static String USBHubAddress = null;
    static final SimpleDateFormat sFormat = new SimpleDateFormat("MM-dd HH:mm:ss:SSS");
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final HashMap<String, UsbDevice> mDevices = new HashMap<>();
    private Object mSettingsLock = new Object();
    private Object mHandlerLock = new Object();
    private final LinkedList<ConnectionRecord> mConnections = new LinkedList<>();

    /* JADX INFO: Access modifiers changed from: private */
    public native void monitorUsbHostBus();

    private native ParcelFileDescriptor nativeOpenDevice(String str);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ConnectionRecord {
        static final int CONNECT = 0;
        static final int CONNECT_BADDEVICE = 2;
        static final int CONNECT_BADPARSE = 1;
        static final int DISCONNECT = -1;
        private static final int kDumpBytesPerLine = 16;
        final byte[] mDescriptors;
        String mDeviceAddress;
        final int mMode;
        long mTimestamp = System.currentTimeMillis();

        ConnectionRecord(String deviceAddress, int mode, byte[] descriptors) {
            this.mDeviceAddress = deviceAddress;
            this.mMode = mode;
            this.mDescriptors = descriptors;
        }

        private String formatTime() {
            return new StringBuilder(UsbHostManager.sFormat.format(new Date(this.mTimestamp))).toString();
        }

        void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);
            dump.write("device_address", 1138166333441L, this.mDeviceAddress);
            dump.write(xpInputManagerService.InputPolicyKey.KEY_MODE, 1159641169922L, this.mMode);
            dump.write(WatchlistLoggingHandler.WatchlistEventKeys.TIMESTAMP, 1112396529667L, this.mTimestamp);
            if (this.mMode != -1) {
                UsbDescriptorParser parser = new UsbDescriptorParser(this.mDeviceAddress, this.mDescriptors);
                UsbDeviceDescriptor deviceDescriptor = parser.getDeviceDescriptor();
                dump.write("manufacturer", 1120986464260L, deviceDescriptor.getVendorID());
                dump.write("product", 1120986464261L, deviceDescriptor.getProductID());
                long isHeadSetToken = dump.start("is_headset", 1146756268038L);
                dump.write("in", 1133871366145L, parser.isInputHeadset());
                dump.write("out", 1133871366146L, parser.isOutputHeadset());
                dump.end(isHeadSetToken);
            }
            dump.end(token);
        }

        void dumpShort(IndentingPrintWriter pw) {
            if (this.mMode != -1) {
                pw.println(formatTime() + " Connect " + this.mDeviceAddress + " mode:" + this.mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(this.mDeviceAddress, this.mDescriptors);
                UsbDeviceDescriptor deviceDescriptor = parser.getDeviceDescriptor();
                pw.println("manfacturer:0x" + Integer.toHexString(deviceDescriptor.getVendorID()) + " product:" + Integer.toHexString(deviceDescriptor.getProductID()));
                pw.println("isHeadset[in: " + parser.isInputHeadset() + " , out: " + parser.isOutputHeadset() + "]");
                return;
            }
            pw.println(formatTime() + " Disconnect " + this.mDeviceAddress);
        }

        void dumpTree(IndentingPrintWriter pw) {
            if (this.mMode != -1) {
                pw.println(formatTime() + " Connect " + this.mDeviceAddress + " mode:" + this.mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(this.mDeviceAddress, this.mDescriptors);
                StringBuilder stringBuilder = new StringBuilder();
                UsbDescriptorsTree descriptorTree = new UsbDescriptorsTree();
                descriptorTree.parse(parser);
                descriptorTree.report(new TextReportCanvas(parser, stringBuilder));
                stringBuilder.append("isHeadset[in: " + parser.isInputHeadset() + " , out: " + parser.isOutputHeadset() + "]");
                pw.println(stringBuilder.toString());
                return;
            }
            pw.println(formatTime() + " Disconnect " + this.mDeviceAddress);
        }

        void dumpList(IndentingPrintWriter pw) {
            if (this.mMode != -1) {
                pw.println(formatTime() + " Connect " + this.mDeviceAddress + " mode:" + this.mMode);
                UsbDescriptorParser parser = new UsbDescriptorParser(this.mDeviceAddress, this.mDescriptors);
                StringBuilder stringBuilder = new StringBuilder();
                TextReportCanvas canvas = new TextReportCanvas(parser, stringBuilder);
                Iterator<UsbDescriptor> it = parser.getDescriptors().iterator();
                while (it.hasNext()) {
                    UsbDescriptor descriptor = it.next();
                    descriptor.report(canvas);
                }
                pw.println(stringBuilder.toString());
                pw.println("isHeadset[in: " + parser.isInputHeadset() + " , out: " + parser.isOutputHeadset() + "]");
                return;
            }
            pw.println(formatTime() + " Disconnect " + this.mDeviceAddress);
        }

        void dumpRaw(IndentingPrintWriter pw) {
            if (this.mMode != -1) {
                pw.println(formatTime() + " Connect " + this.mDeviceAddress + " mode:" + this.mMode);
                int length = this.mDescriptors.length;
                StringBuilder sb = new StringBuilder();
                sb.append("Raw Descriptors ");
                sb.append(length);
                sb.append(" bytes");
                pw.println(sb.toString());
                int dataOffset = 0;
                int dataOffset2 = 0;
                while (dataOffset2 < length / 16) {
                    StringBuilder sb2 = new StringBuilder();
                    int dataOffset3 = dataOffset;
                    int dataOffset4 = 0;
                    while (dataOffset4 < 16) {
                        sb2.append("0x");
                        sb2.append(String.format("0x%02X", Byte.valueOf(this.mDescriptors[dataOffset3])));
                        sb2.append(" ");
                        dataOffset4++;
                        dataOffset3++;
                    }
                    pw.println(sb2.toString());
                    dataOffset2++;
                    dataOffset = dataOffset3;
                }
                StringBuilder sb3 = new StringBuilder();
                while (dataOffset < length) {
                    sb3.append("0x");
                    sb3.append(String.format("0x%02X", Byte.valueOf(this.mDescriptors[dataOffset])));
                    sb3.append(" ");
                    dataOffset++;
                }
                pw.println(sb3.toString());
                return;
            }
            pw.println(formatTime() + " Disconnect " + this.mDeviceAddress);
        }
    }

    public UsbHostManager(Context context, UsbAlsaManager alsaManager, UsbSettingsManager settingsManager) {
        this.mContext = context;
        this.mHostBlacklist = context.getResources().getStringArray(17236056);
        this.mUsbAlsaManager = alsaManager;
        this.mSettingsManager = settingsManager;
        String deviceConnectionHandler = context.getResources().getString(17039632);
        if (!TextUtils.isEmpty(deviceConnectionHandler)) {
            setUsbDeviceConnectionHandler(ComponentName.unflattenFromString(deviceConnectionHandler));
        }
    }

    public void setCurrentUserSettings(UsbProfileGroupSettingsManager settings) {
        synchronized (this.mSettingsLock) {
            this.mCurrentSettings = settings;
        }
    }

    private UsbProfileGroupSettingsManager getCurrentUserSettings() {
        UsbProfileGroupSettingsManager usbProfileGroupSettingsManager;
        synchronized (this.mSettingsLock) {
            usbProfileGroupSettingsManager = this.mCurrentSettings;
        }
        return usbProfileGroupSettingsManager;
    }

    public void setUsbDeviceConnectionHandler(ComponentName usbDeviceConnectionHandler) {
        synchronized (this.mHandlerLock) {
            this.mUsbDeviceConnectionHandler = usbDeviceConnectionHandler;
        }
    }

    private ComponentName getUsbDeviceConnectionHandler() {
        ComponentName componentName;
        synchronized (this.mHandlerLock) {
            componentName = this.mUsbDeviceConnectionHandler;
        }
        return componentName;
    }

    private boolean isBlackListed(String deviceAddress) {
        int count = this.mHostBlacklist.length;
        for (int i = 0; i < count; i++) {
            if (deviceAddress.startsWith(this.mHostBlacklist[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlackListed(int clazz, int subClass) {
        if (clazz == 9) {
            return true;
        }
        return clazz == 3 && subClass == 1;
    }

    private boolean isBlackListedDevices(int vid, int pid) {
        return vid == APPLE_INC_VID && (pid == APPLE_IPHONE5_PID || pid == APPLE_IPHONE4_PID);
    }

    private boolean isNoBroadcastDevices(int vid, int pid) {
        return vid == QUECTEL_VID && (pid == QUECTEL_AG35_PID || pid == QUECTEL_AG55_PID);
    }

    private void addConnectionRecord(String deviceAddress, int mode, byte[] rawDescriptors) {
        this.mNumConnects++;
        while (this.mConnections.size() >= 32) {
            this.mConnections.removeFirst();
        }
        ConnectionRecord rec = new ConnectionRecord(deviceAddress, mode, rawDescriptors);
        this.mConnections.add(rec);
        if (mode != -1) {
            this.mLastConnect = rec;
        }
    }

    private void logUsbDevice(UsbDescriptorParser descriptorParser) {
        int vid = 0;
        int pid = 0;
        String mfg = "<unknown>";
        String product = "<unknown>";
        String version = "<unknown>";
        String serial = "<unknown>";
        UsbDeviceDescriptor deviceDescriptor = descriptorParser.getDeviceDescriptor();
        if (deviceDescriptor != null) {
            vid = deviceDescriptor.getVendorID();
            pid = deviceDescriptor.getProductID();
            mfg = deviceDescriptor.getMfgString(descriptorParser);
            product = deviceDescriptor.getProductString(descriptorParser);
            version = deviceDescriptor.getDeviceReleaseString();
            serial = deviceDescriptor.getSerialString(descriptorParser);
        }
        if (vid == LINUX_FOUNDATION_VID) {
            return;
        }
        boolean hasAudio = descriptorParser.hasAudioInterface();
        boolean hasHid = descriptorParser.hasHIDInterface();
        boolean hasStorage = descriptorParser.hasStorageInterface();
        String attachedString = "USB device attached: " + String.format("vidpid %04x:%04x", Integer.valueOf(vid), Integer.valueOf(pid));
        Slog.d(TAG, (attachedString + String.format(" mfg/product/ver/serial %s/%s/%s/%s", mfg, product, version, serial)) + String.format(" hasAudio/HID/Storage: %b/%b/%b", Boolean.valueOf(hasAudio), Boolean.valueOf(hasHid), Boolean.valueOf(hasStorage)));
    }

    private boolean usbDeviceAdded(String deviceAddress, int deviceClass, int deviceSubclass, byte[] descriptors) {
        if (isBlackListed(deviceAddress)) {
            return false;
        }
        UsbDescriptorParser parser = new UsbDescriptorParser(deviceAddress, descriptors);
        logUsbDevice(parser);
        if (isBlackListed(deviceClass, deviceSubclass)) {
            return false;
        }
        UsbDeviceDescriptor deviceDes = parser.getDeviceDescriptor();
        if (isBlackListedDevices(deviceDes.getVendorID(), deviceDes.getProductID())) {
            Slog.w(TAG, "device is in black listed devices");
            return false;
        }
        synchronized (this.mLock) {
            if (this.mDevices.get(deviceAddress) != null) {
                String str = TAG;
                Slog.w(str, "device already on mDevices list: " + deviceAddress);
                return false;
            }
            UsbDevice newDevice = parser.toAndroidUsbDevice();
            if (newDevice == null) {
                Slog.e(TAG, "Couldn't create UsbDevice object.");
                addConnectionRecord(deviceAddress, 2, parser.getRawDescriptors());
            } else {
                this.mDevices.put(deviceAddress, newDevice);
                String str2 = TAG;
                Slog.d(str2, "Added device " + newDevice);
                if (!isNoBroadcastDevices(deviceDes.getVendorID(), deviceDes.getProductID())) {
                    ComponentName usbDeviceConnectionHandler = getUsbDeviceConnectionHandler();
                    if (usbDeviceConnectionHandler == null) {
                        getCurrentUserSettings().deviceAttachedForStorage(newDevice, deviceAddress, descriptors);
                    } else {
                        getCurrentUserSettings().deviceAttachedForFixedHandlerForStorage(newDevice, usbDeviceConnectionHandler, deviceAddress, descriptors);
                    }
                } else {
                    Slog.w(TAG, "USB HUB mount.");
                    isHubMount = true;
                    USBHubAddress = deviceAddress;
                }
                this.mUsbAlsaManager.usbDeviceAdded(deviceAddress, newDevice, parser);
                addConnectionRecord(deviceAddress, 0, parser.getRawDescriptors());
            }
            return true;
        }
    }

    private void usbDeviceRemoved(String deviceAddress) {
        synchronized (this.mLock) {
            UsbDevice device = this.mDevices.remove(deviceAddress);
            if (USBHubAddress != null && USBHubAddress.equals(deviceAddress)) {
                String str = TAG;
                Slog.w(str, "Removed USB HUB at " + deviceAddress);
                isHubMount = false;
                USBHubAddress = null;
            }
            if (device != null) {
                String str2 = TAG;
                Slog.d(str2, "Removed device at " + deviceAddress + ": " + device.getProductName());
                this.mUsbAlsaManager.usbDeviceRemoved(deviceAddress);
                if (!isNoBroadcastDevices(device.getVendorId(), device.getProductId())) {
                    this.mSettingsManager.usbDeviceRemoved(device);
                    getCurrentUserSettings().usbDeviceRemoved(device);
                }
                addConnectionRecord(deviceAddress, -1, null);
            } else {
                String str3 = TAG;
                Slog.d(str3, "Removed device at " + deviceAddress + " was already gone");
            }
        }
    }

    public void systemReady() {
        synchronized (this.mLock) {
            Runnable runnable = new Runnable() { // from class: com.android.server.usb.-$$Lambda$UsbHostManager$XT3F5aQci4H6VWSBYBQQNSzpnvs
                @Override // java.lang.Runnable
                public final void run() {
                    UsbHostManager.this.monitorUsbHostBus();
                }
            };
            new Thread(null, runnable, "UsbService host thread").start();
        }
    }

    public void getDeviceList(Bundle devices) {
        synchronized (this.mLock) {
            for (String name : this.mDevices.keySet()) {
                devices.putParcelable(name, this.mDevices.get(name));
            }
        }
    }

    public boolean isDeviceMount() {
        synchronized (this.mLock) {
            List<String> deviceNameList = new ArrayList<>();
            for (UsbDevice usbDevice : this.mDevices.values()) {
                if (usbDevice != null && usbDevice.getProductName() != null) {
                    deviceNameList.add(usbDevice.getProductName().toString());
                }
            }
            String str = TAG;
            Slog.w(str, "USB Device number:" + deviceNameList.size());
            String str2 = TAG;
            Slog.w(str2, "isHubMount:" + isHubMount);
            if (deviceNameList.size() <= 0 && !isHubMount) {
                return false;
            }
            return true;
        }
    }

    public ParcelFileDescriptor openDevice(String deviceAddress, UsbUserSettingsManager settings, String packageName, int uid) {
        ParcelFileDescriptor nativeOpenDevice;
        synchronized (this.mLock) {
            if (isBlackListed(deviceAddress)) {
                throw new SecurityException("USB device is on a restricted bus");
            }
            UsbDevice device = this.mDevices.get(deviceAddress);
            if (device == null) {
                throw new IllegalArgumentException("device " + deviceAddress + " does not exist or is restricted");
            }
            settings.checkPermission(device, packageName, uid);
            nativeOpenDevice = nativeOpenDevice(deviceAddress);
        }
        return nativeOpenDevice;
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        synchronized (this.mHandlerLock) {
            if (this.mUsbDeviceConnectionHandler != null) {
                DumpUtils.writeComponentName(dump, "default_usb_host_connection_handler", 1146756268033L, this.mUsbDeviceConnectionHandler);
            }
        }
        synchronized (this.mLock) {
            for (String name : this.mDevices.keySet()) {
                com.android.internal.usb.DumpUtils.writeDevice(dump, "devices", 2246267895810L, this.mDevices.get(name));
            }
            dump.write("num_connects", 1120986464259L, this.mNumConnects);
            Iterator<ConnectionRecord> it = this.mConnections.iterator();
            while (it.hasNext()) {
                ConnectionRecord rec = it.next();
                rec.dump(dump, "connections", 2246267895812L);
            }
        }
        dump.end(token);
    }

    public void dumpDescriptors(IndentingPrintWriter pw, String[] args) {
        if (this.mLastConnect != null) {
            pw.println("Last Connected USB Device:");
            if (args.length <= 1 || args[1].equals("-dump-short")) {
                this.mLastConnect.dumpShort(pw);
                return;
            } else if (args[1].equals("-dump-tree")) {
                this.mLastConnect.dumpTree(pw);
                return;
            } else if (args[1].equals("-dump-list")) {
                this.mLastConnect.dumpList(pw);
                return;
            } else if (args[1].equals("-dump-raw")) {
                this.mLastConnect.dumpRaw(pw);
                return;
            } else {
                return;
            }
        }
        pw.println("No USB Devices have been connected.");
    }
}
